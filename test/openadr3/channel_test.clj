(ns openadr3.channel-test
  "Tests for the NotificationChannel protocol lifecycle and the
  VenClient integrated channel management flow."
  (:require [openadr3.client.base :as base]
            [openadr3.client.ven :as ven]
            [openadr3.channel :as ch]
            [openadr3.common-test :refer [ven1 bl MQTT-broker-url mqtt-available?
                                          inter-suite-delay-ms mqtt-credentials]]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]))

(use-fixtures :once
  (fn [f]
    (Thread/sleep inter-suite-delay-ms)
    (f)))

;; ---------------------------------------------------------------------------
;; MqttChannel lifecycle
;; ---------------------------------------------------------------------------

(deftest test-mqtt-channel-lifecycle
  (if-not mqtt-available?
    (is true "SKIPPED — VTN does not advertise MQTT support")
    (testing "MqttChannel start → use → stop lifecycle"
      (let [ch1 (ch/mqtt-channel MQTT-broker-url (mqtt-credentials ven1))]

        (testing "before start"
          (is (nil? (ch/channel-messages ch1))
              "channel-messages returns nil before start")
          (is (nil? (ch/mqtt-connected? ch1))
              "mqtt-connected? returns nil before start"))

        (testing "after start"
          (ch/channel-start ch1)
          (is (true? (ch/mqtt-connected? ch1))
              "Should be connected after start")
          (is (= [] (ch/channel-messages ch1))
              "Should have empty messages after start"))

        (testing "clear on empty is safe"
          (ch/clear-channel-messages! ch1)
          (is (= [] (ch/channel-messages ch1))))

        (testing "after stop"
          (ch/channel-stop ch1)
          (is (nil? (ch/mqtt-connected? ch1))
              "Should not be connected after stop"))

        (testing "stop is idempotent"
          (ch/channel-stop ch1)
          (is (nil? (ch/mqtt-connected? ch1))
              "Second stop should not throw"))))))

(deftest test-mqtt-channel-subscribe-before-start-throws
  (if-not mqtt-available?
    (is true "SKIPPED — VTN does not advertise MQTT support")
    (testing "subscribe-topics before start throws"
      (let [ch1 (ch/mqtt-channel MQTT-broker-url (mqtt-credentials ven1))]
        (is (thrown? clojure.lang.ExceptionInfo
                     (ch/subscribe-topics ch1 ["test/+"])))))))

(deftest test-mqtt-channel-with-on-message
  (if-not mqtt-available?
    (is true "SKIPPED — VTN does not advertise MQTT support")
    (testing "MqttChannel with :on-message callback"
      (let [received (atom [])
            ch1 (-> (ch/mqtt-channel MQTT-broker-url
                                     (merge (mqtt-credentials ven1)
                                            {:on-message (fn [topic _meta payload]
                                                           (swap! received conj {:topic topic
                                                                                 :payload payload}))}))
                    ch/channel-start)]
        (is (true? (ch/mqtt-connected? ch1)))
        ;; Just verify it started with callback - actual message delivery
        ;; requires a publisher, tested in mqtt_test
        (ch/channel-stop ch1)))))

;; ---------------------------------------------------------------------------
;; WebhookChannel lifecycle
;; ---------------------------------------------------------------------------

(deftest test-webhook-channel-lifecycle
  (testing "WebhookChannel start → use → stop lifecycle"
    (let [ch1 (ch/webhook-channel {:port 0 :callback-host "127.0.0.1"})]

      (testing "before start"
        (is (nil? (ch/channel-messages ch1))
            "channel-messages returns nil before start")
        (is (nil? (ch/callback-url ch1))
            "callback-url returns nil before start"))

      (testing "after start"
        (ch/channel-start ch1)
        (is (= [] (ch/channel-messages ch1))
            "Should have empty messages after start")
        (let [url (ch/callback-url ch1)]
          (is (string? url) "callback-url should be a string")
          (is (re-find #"http://127\.0\.0\.1:\d+/notifications" url)
              "callback-url should have correct format")))

      (testing "subscribe-topics is a no-op for webhooks"
        (let [result (ch/subscribe-topics ch1 ["anything"])]
          (is (= ch1 result) "Should return self unchanged")))

      (testing "clear on empty is safe"
        (ch/clear-channel-messages! ch1)
        (is (= [] (ch/channel-messages ch1))))

      (testing "after stop"
        (ch/channel-stop ch1)
        (is (nil? (ch/callback-url ch1))
            "callback-url should be nil after stop"))

      (testing "stop is idempotent"
        (ch/channel-stop ch1)))))

(deftest test-webhook-channel-custom-path
  (testing "WebhookChannel with custom path"
    (let [ch1 (-> (ch/webhook-channel {:port 0
                                       :callback-host "127.0.0.1"
                                       :path "/custom/webhook"})
                  ch/channel-start)
          url (ch/callback-url ch1)]
      (is (re-find #"/custom/webhook$" url)
          "callback-url should use custom path")
      (ch/channel-stop ch1))))

;; ---------------------------------------------------------------------------
;; Channel accessor functions
;; ---------------------------------------------------------------------------

(deftest test-mqtt-conn-accessor
  (if-not mqtt-available?
    (is true "SKIPPED — VTN does not advertise MQTT support")
    (testing "mqtt-conn returns raw connection after start"
      (let [ch1 (-> (ch/mqtt-channel MQTT-broker-url (mqtt-credentials ven1)) ch/channel-start)]
        (let [conn (ch/mqtt-conn ch1)]
          (is (some? conn) "Should return connection map")
          (is (contains? conn :client) "Connection should have :client key"))
        (ch/channel-stop ch1)))))

(deftest test-webhook-receiver-accessor
  (testing "webhook-receiver returns raw receiver after start"
    (let [ch1 (-> (ch/webhook-channel {:port 0 :callback-host "127.0.0.1"})
                  ch/channel-start)]
      (let [recv (ch/webhook-receiver ch1)]
        (is (some? recv) "Should return receiver map")
        (is (contains? recv :server) "Receiver should have :server key")
        (is (pos-int? (:port recv)) "Receiver should have assigned port"))
      (ch/channel-stop ch1))))

;; ---------------------------------------------------------------------------
;; VenClient integrated channel flow (#6)
;; ---------------------------------------------------------------------------

(deftest test-ven-mqtt-end-to-end
  (if-not mqtt-available?
    (is true "SKIPPED — VTN does not advertise MQTT support")
    (testing "VenClient add-mqtt → subscribe → receive notification → stop"
      (let [ven-id (ven/ven-id ven1)]
        (is (some? ven-id) "ven1 must be registered")

        (when ven-id
          ;; Add MQTT channel to ven1
          (ven/add-mqtt ven1 MQTT-broker-url (mqtt-credentials ven1))
          (let [mqtt-ch (ven/get-channel ven1 :mqtt)]
            (is (some? mqtt-ch) "Channel should be in state")
            (is (ch/mqtt-connected? mqtt-ch) "Channel should be connected")

            ;; Subscribe to program topics via VenClient
            (ven/subscribe ven1 :mqtt base/get-mqtt-topics-programs)
            (ch/clear-channel-messages! mqtt-ch)
            (Thread/sleep 200)

            ;; BL creates a program — ven1's managed channel should receive notification
            (let [resp (base/create-program bl {:programName "ChannelFlowTest"})]
              (is (<= (:status resp) 299) "Program creation should succeed")

              (let [msgs (ch/await-channel-messages mqtt-ch 1 5000)
                    notification (->> msgs
                                      (map :payload)
                                      (filter #(= :openadr.operation/create
                                                  (:openadr.notification/operation %)))
                                      first)]
                (is (some? notification)
                    "Should receive CREATE notification through managed channel")
                (when notification
                  (is (= :openadr.object-type/program
                         (:openadr.notification/object-type notification)))))

              ;; Clean up program
              (when-let [pid (-> resp :body :id)]
                (base/delete-program bl pid)))

            ;; Clean up channel
            (ch/channel-stop mqtt-ch)
            (swap! (:state ven1) update :channels dissoc :mqtt)))))))

(deftest test-ven-component-stop-closes-channels
  (if-not mqtt-available?
    (is true "SKIPPED — VTN does not advertise MQTT support")
    (testing "component/stop auto-stops managed channels"
      ;; Create a fresh VenClient with a channel, then stop it
      (let [v (component/start
               (ven/ven-client {:url (:url ven1) :token (:token ven1)}))]
        (ven/add-mqtt v MQTT-broker-url (mqtt-credentials ven1))
        (let [mqtt-ch (ven/get-channel v :mqtt)]
          (is (ch/mqtt-connected? mqtt-ch) "Channel should be connected before stop")

          ;; Stop the component
          (component/stop v)

          ;; Channel should be disconnected
          (is (nil? (ch/mqtt-connected? mqtt-ch))
              "Channel should be disconnected after component/stop")
          (is (empty? (:channels @(:state v)))
              "Channels map should be empty after stop"))))))
