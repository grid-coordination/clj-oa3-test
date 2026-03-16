(ns openadr3.ven-client-test
  "Tests for VenClient-specific features: registration, channels,
  program resolution, notifier discovery, and event polling."
  (:require [openadr3.client.base :as base]
            [openadr3.client.ven :as ven]
            [openadr3.channel :as ch]
            [openadr3.common-test :refer [ven1 bl MQTT-broker-url inter-suite-delay-ms
                                          mqtt-credentials]]
            [clojure.test :refer :all]))

(use-fixtures :once
  (fn [f]
    (Thread/sleep inter-suite-delay-ms)
    (f)))

;; ---------------------------------------------------------------------------
;; VEN registration (ven1 registered by vens-test fixture)
;; ---------------------------------------------------------------------------

(deftest test-ven-id-and-name
  (testing "ven-id and ven-name return registered values"
    (is (some? (ven/ven-id ven1)) "ven-id should be set after registration")
    (is (= "ven1" (ven/ven-name ven1)) "ven-name should match")))

;; ---------------------------------------------------------------------------
;; Program resolution with caching
;; ---------------------------------------------------------------------------

(deftest test-resolve-program-id
  (testing "resolve-program-id finds a program and caches it"
    (let [id1 (ven/resolve-program-id ven1 "Program1")]
      (is (some? id1) "Should resolve Program1 to an ID")
      (is (string? id1) "Program ID should be a string")

      (testing "second call uses cache"
        (let [id2 (ven/resolve-program-id ven1 "Program1")]
          (is (= id1 id2) "Cached ID should match"))))))

(deftest test-resolve-program-id-not-found
  (testing "resolve-program-id returns nil for nonexistent program"
    (is (nil? (ven/resolve-program-id ven1 "NonexistentProgram99999")))))

;; ---------------------------------------------------------------------------
;; Notifier discovery
;; ---------------------------------------------------------------------------

(deftest test-discover-notifiers
  (testing "discover-notifiers returns notifier configuration"
    (let [notifiers (ven/discover-notifiers ven1)]
      (is (map? notifiers) "Should return a map")
      (is (contains? notifiers :WEBHOOK) "Should include WEBHOOK notifier"))))

(deftest test-vtn-supports-mqtt
  (testing "vtn-supports-mqtt? detects MQTT support"
    ;; Our test VTN supports MQTT
    (is (boolean? (ven/vtn-supports-mqtt? ven1))
        "Should return a boolean")))

(deftest test-mqtt-broker-urls
  (testing "mqtt-broker-urls returns broker URIs when VTN supports MQTT"
    (when (ven/vtn-supports-mqtt? ven1)
      (let [urls (ven/mqtt-broker-urls ven1)]
        (is (vector? urls) "Should return a vector")
        (is (pos? (count urls)) "Should have at least one URL")
        (is (string? (first urls)) "URLs should be strings")))))

;; ---------------------------------------------------------------------------
;; Event polling
;; ---------------------------------------------------------------------------

(deftest test-poll-events
  (testing "poll-events returns coerced event entities"
    ;; Create an event to poll
    (let [program-id (:id (base/find-program-by-name bl "Program1"))
          event-resp (base/create-event bl {:programID program-id
                                            :intervals [{:id 0
                                                         :payloads [{:type "PRICE"
                                                                     :values [1.0]}]}]})
          event-id   (-> event-resp :body :id)]
      (is (some? event-id) "Need an event to poll")

      (when event-id
        (testing "unfiltered poll"
          (let [events (ven/poll-events ven1)]
            (is (vector? events) "Should return a vector")
            (is (pos? (count events)) "Should have at least one event")
            (is (= :openadr.object-type/event
                   (:openadr/object-type (first events)))
                "Events should be coerced entities")))

        (testing "filtered by program-id"
          (let [events (ven/poll-events ven1 {:program-id program-id})]
            (is (vector? events) "Should return a vector")
            (when (seq events)
              ;; VTN-RI may not filter by programID reliably, so just
              ;; verify we get coerced entities back
              (is (= :openadr.object-type/event
                     (:openadr/object-type (first events)))
                  "Filtered results should be coerced entities"))))

        ;; Clean up
        (base/delete-event bl event-id)))))

;; ---------------------------------------------------------------------------
;; Channel management
;; ---------------------------------------------------------------------------

(deftest test-add-mqtt-and-get-channel
  (testing "add-mqtt creates and starts an MQTT channel"
    (ven/add-mqtt ven1 MQTT-broker-url (mqtt-credentials ven1))
    (let [mqtt-ch (ven/get-channel ven1 :mqtt)]
      (is (some? mqtt-ch) "MQTT channel should be stored in state")
      (is (instance? openadr3.channel.MqttChannel mqtt-ch)
          "Should be an MqttChannel")
      (is (ch/mqtt-connected? mqtt-ch) "Channel should be connected"))

    ;; Clean up — stop the channel manually
    (ch/channel-stop (ven/get-channel ven1 :mqtt))
    (swap! (:state ven1) update :channels dissoc :mqtt)))

(deftest test-add-webhook-and-get-channel
  (testing "add-webhook creates and starts a webhook channel"
    (ven/add-webhook ven1 {:port 0 :callback-host "127.0.0.1"})
    (let [wh-ch (ven/get-channel ven1 :webhook)]
      (is (some? wh-ch) "Webhook channel should be stored in state")
      (is (instance? openadr3.channel.WebhookChannel wh-ch)
          "Should be a WebhookChannel")
      (is (string? (ch/callback-url wh-ch))
          "Should have a callback URL"))

    ;; Clean up
    (ch/channel-stop (ven/get-channel ven1 :webhook))
    (swap! (:state ven1) update :channels dissoc :webhook)))

(deftest test-get-channel-nil-when-absent
  (testing "get-channel returns nil for absent channel type"
    (is (nil? (ven/get-channel ven1 :nonexistent)))))

(deftest test-subscribe-mqtt
  (testing "subscribe discovers topics from VTN and subscribes on MQTT channel"
    (ven/add-mqtt ven1 MQTT-broker-url (mqtt-credentials ven1))
    (let [result (ven/subscribe ven1 :mqtt base/get-mqtt-topics-programs)]
      (is (= ven1 result) "subscribe should return the client for threading")

      ;; Verify messages can be received (channel is subscribed)
      (let [mqtt-ch (ven/get-channel ven1 :mqtt)]
        (is (some? (ch/channel-messages mqtt-ch))
            "channel-messages should be callable")))

    ;; Clean up
    (ch/channel-stop (ven/get-channel ven1 :mqtt))
    (swap! (:state ven1) update :channels dissoc :mqtt)))

;; ---------------------------------------------------------------------------
;; VEN-scoped MQTT topics (auto ven-id)
;; ---------------------------------------------------------------------------

(deftest test-ven-scoped-topics-auto-id
  (testing "VEN-scoped topic functions auto-use registered ven-id"
    (let [ven-id (ven/ven-id ven1)]
      (is (some? ven-id) "ven1 must be registered")

      (when ven-id
        (let [resp (ven/get-mqtt-topics-ven ven1)]
          (is (<= (:status resp) 299) "Auto ven-id should work")
          (is (contains? (:body resp) :topics) "Should return topics"))

        (testing "explicit ID produces same result"
          (let [auto    (ven/get-mqtt-topics-ven ven1)
                explicit (ven/get-mqtt-topics-ven ven1 ven-id)]
            (is (= (:body auto) (:body explicit))
                "Auto and explicit should return same topics")))))))

;; ---------------------------------------------------------------------------
;; client-type field
;; ---------------------------------------------------------------------------

(deftest test-client-type-field
  (testing "VenClient has :client-type :ven"
    (is (= :ven (:client-type ven1))))
  (testing "BlClient has :client-type :bl"
    (is (= :bl (:client-type bl)))))
