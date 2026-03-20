(ns openadr3.mqtt-auth-test
  "MQTT broker authentication tests for VTN-RI dynsec mode.

  These tests verify that when the VTN is running with Mosquitto's dynamic
  security plugin, per-VEN MQTT credentials and topic ACLs work correctly.
  If the VTN is running in ANONYMOUS mode, all tests are skipped gracefully."
  (:require [openadr3.client.base :as client]
            [openadr3.client.ven :as ven]
            [openadr3.channel :as ch]
            [openadr3.common-test :refer [ven1 ven2 bl MQTT-broker-url mqtt-available?
                                          inter-suite-delay-ms
                                          mqtt-settle-ms mqtt-await-ms]]
            [clojure.test :refer :all])
  (:import [org.eclipse.paho.client.mqttv3 MqttSecurityException MqttException]))

;; ---------------------------------------------------------------------------
;; Dynsec detection — skip tests when VTN is in ANONYMOUS mode
;; ---------------------------------------------------------------------------

(defn- notifiers-auth
  "Returns the MQTT authentication map from GET /notifiers for the given client,
  or nil if the VTN is not advertising MQTT credentials."
  [c]
  (let [resp (client/get-notifiers c)]
    (get-in resp [:body :MQTT :authentication])))

(defn- dynsec-active?
  "Returns true if the VTN is advertising per-VEN MQTT credentials (non-ANONYMOUS)."
  []
  (let [auth (notifiers-auth ven1)]
    (and auth
         (not= "ANONYMOUS" (:method auth))
         (some? (:username auth))
         (some? (:password auth)))))

(def ^:private dynsec? (delay (dynsec-active?)))

(defmacro when-dynsec
  "Execute body only when VTN advertises MQTT and is in dynsec mode,
  otherwise skip with a passing assertion."
  [& body]
  `(cond
     (not mqtt-available?) (is true "SKIPPED — VTN does not advertise MQTT support")
     (not @dynsec?)        (is true "SKIPPED — VTN not in dynsec mode")
     :else                 (do ~@body)))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- ven-mqtt-credentials
  "Fetch MQTT credentials for a VEN from GET /notifiers.
  Returns {:username str :password str} or nil."
  [c]
  (let [auth (notifiers-auth c)]
    (when (and auth (not= "ANONYMOUS" (:method auth)))
      (select-keys auth [:username :password]))))

(defn- connect-mqtt-with-creds
  "Try to connect to the MQTT broker with the given username/password.
  Returns the started MqttChannel on success, or throws on auth failure."
  [broker-url username password]
  (-> (ch/mqtt-channel broker-url {:username username :password password})
      ch/channel-start))

(defn- connect-mqtt-anon
  "Try to connect to the MQTT broker with no credentials."
  [broker-url]
  (-> (ch/mqtt-channel broker-url) ch/channel-start))

(defn- delete-ven-by-name [c ven-name]
  (let [{ven-id :id} (client/find-ven-by-name c ven-name)]
    (when ven-id
      (client/delete-ven c ven-id))))

(defn- subscribe-clear-wait!
  "Subscribe mqtt-ch to topics from topic-fn, clear messages, and wait to settle."
  [mqtt-ch c topic-fn]
  (let [resp   (topic-fn c)
        topics (client/extract-topics resp)]
    (when topics
      (ch/subscribe-topics mqtt-ch topics)))
  (ch/clear-channel-messages! mqtt-ch)
  (Thread/sleep mqtt-settle-ms)
  (ch/clear-channel-messages! mqtt-ch))

(defn- find-notification
  "Find a notification in messages matching a predicate on the payload."
  [msgs pred]
  (->> msgs
       (map :payload)
       (filter pred)
       first))

;; ---------------------------------------------------------------------------
;; Fixture
;; ---------------------------------------------------------------------------

(use-fixtures :once
  (fn [f]
    (Thread/sleep inter-suite-delay-ms)
    (f)))

;; ---------------------------------------------------------------------------
;; 1. Notifiers credential delivery
;; ---------------------------------------------------------------------------

(deftest test-notifiers-returns-ven-credentials
  (testing "GET /notifiers returns per-VEN MQTT credentials in dynsec mode"
    (when-dynsec
     (let [auth (notifiers-auth ven1)]
       (is (some? auth) "MQTT authentication should be present")
       (is (= "OAUTH2_BEARER_TOKEN" (:method auth))
           "Auth method should be OAUTH2_BEARER_TOKEN")
       (is (string? (:username auth)) "Username should be a string")
       (is (string? (:password auth)) "Password should be a string")

       (let [ven-id (ven/ven-id ven1)]
         (when ven-id
           (is (= (str "ven-" ven-id) (:username auth))
               "Username should match ven-{venID} pattern")))))))

(deftest test-notifiers-no-credentials-for-bl
  (testing "GET /notifiers for a BL client does not return VEN-specific credentials"
    (when-dynsec
     (let [auth (notifiers-auth bl)]
        ;; BL may get no credentials or non-VEN credentials
       (when auth
         (is (not (and (:username auth)
                       (.startsWith ^String (:username auth) "ven-")))
             "BL should not receive VEN-specific credentials"))))))

(deftest test-ven2-gets-different-credentials
  (testing "Each VEN gets its own unique credentials"
    (when-dynsec
     (let [creds1 (ven-mqtt-credentials ven1)
           creds2 (ven-mqtt-credentials ven2)]
       (is (some? creds1) "ven1 should have credentials")
       (is (some? creds2) "ven2 should have credentials")
       (when (and creds1 creds2)
         (is (not= (:username creds1) (:username creds2))
             "VENs should have different usernames")
         (is (not= (:password creds1) (:password creds2))
             "VENs should have different passwords"))))))

;; ---------------------------------------------------------------------------
;; 2. VEN MQTT connection with returned credentials
;; ---------------------------------------------------------------------------

(deftest test-connect-with-valid-credentials
  (testing "VEN can connect to broker using credentials from GET /notifiers"
    (when-dynsec
     (let [creds (ven-mqtt-credentials ven1)]
       (is (some? creds) "Need credentials to test")
       (when creds
         (let [mqtt-ch (connect-mqtt-with-creds
                        MQTT-broker-url (:username creds) (:password creds))]
           (try
             (is (ch/mqtt-connected? mqtt-ch)
                 "Should connect successfully with valid credentials")
             (finally
               (ch/channel-stop mqtt-ch)))))))))

(deftest test-connect-with-wrong-password-rejected
  (testing "Connection with wrong password is rejected"
    (when-dynsec
     (let [creds (ven-mqtt-credentials ven1)]
       (when creds
         (is (thrown? MqttSecurityException
                      (connect-mqtt-with-creds
                       MQTT-broker-url (:username creds) "wrong-password"))
             "Should reject connection with wrong password"))))))

(deftest test-connect-anonymous-rejected
  (testing "Anonymous connection is rejected when dynsec is active"
    (when-dynsec
     (is (thrown? MqttException
                  (connect-mqtt-anon MQTT-broker-url))
         "Should reject anonymous connection"))))

;; ---------------------------------------------------------------------------
;; 3. VEN topic ACL enforcement
;; ---------------------------------------------------------------------------

(deftest test-ven-can-subscribe-to-allowed-topics
  (testing "VEN can subscribe to its allowed topics and receive notifications"
    (when-dynsec
     (let [creds (ven-mqtt-credentials ven1)]
       (when creds
         (let [mqtt-ch (connect-mqtt-with-creds
                        MQTT-broker-url (:username creds) (:password creds))]
           (try
             (subscribe-clear-wait!
              mqtt-ch ven1 client/get-mqtt-topics-programs)

              ;; BL creates a program, VEN should receive notification
             (let [resp (client/create-program bl {:programName "DynsecACLTestProg"})]
               (is (<= (:status resp) 299) "Program creation should succeed")

               (let [msgs (ch/await-channel-messages mqtt-ch 1 mqtt-await-ms)
                     notification (find-notification
                                   msgs #(= "DynsecACLTestProg"
                                            (-> % :openadr.notification/object
                                                :openadr.program/name)))]
                 (is (some? notification)
                     "VEN should receive notification on allowed topic"))

               (when-let [pid (-> resp :body :id)]
                 (client/delete-program bl pid)))
             (finally
               (ch/channel-stop mqtt-ch)))))))))

(deftest test-ven-can-subscribe-to-own-targeted-topics
  (testing "VEN can subscribe to its own VEN-scoped topics"
    (when-dynsec
     (let [creds  (ven-mqtt-credentials ven1)
           ven-id (ven/ven-id ven1)]
       (when (and creds ven-id)
         (let [mqtt-ch (connect-mqtt-with-creds
                        MQTT-broker-url (:username creds) (:password creds))]
           (try
             (subscribe-clear-wait!
              mqtt-ch ven1 #(client/get-mqtt-topics-ven-programs % ven-id))

              ;; BL creates a program targeted at ven1
             (let [resp (client/create-program bl {:programName "DynsecTargetProg"
                                                   :targets [ven-id]})]
               (is (<= (:status resp) 299) "Targeted program creation should succeed")

               (let [msgs (ch/await-channel-messages mqtt-ch 1 mqtt-await-ms)
                     notification (find-notification
                                   msgs #(= "DynsecTargetProg"
                                            (-> % :openadr.notification/object
                                                :openadr.program/name)))]
                 (is (some? notification)
                     "VEN should receive notification on own targeted topic"))

               (when-let [pid (-> resp :body :id)]
                 (client/delete-program bl pid)))
             (finally
               (ch/channel-stop mqtt-ch)))))))))

(deftest test-ven-cannot-subscribe-to-other-ven-topics
  (testing "VEN cannot receive notifications on another VEN's targeted topics"
    (when-dynsec
     (let [creds1  (ven-mqtt-credentials ven1)
           ven2-id (ven/ven-id ven2)]
       (when (and creds1 ven2-id)
         (let [mqtt-ch (connect-mqtt-with-creds
                        MQTT-broker-url (:username creds1) (:password creds1))]
           (try
              ;; ven1 tries to subscribe to ven2's targeted topics
             (let [resp   (client/get-mqtt-topics-ven-programs ven2 ven2-id)
                   topics (client/extract-topics resp)]
               (when topics
                 (ch/subscribe-topics mqtt-ch topics))
               (ch/clear-channel-messages! mqtt-ch)
               (Thread/sleep mqtt-settle-ms)
               (ch/clear-channel-messages! mqtt-ch)

                ;; BL creates a program targeted at ven2
               (let [resp (client/create-program bl {:programName "DynsecACLCrossProg"
                                                     :targets [ven2-id]})]
                 (is (<= (:status resp) 299) "Targeted program creation should succeed")

                  ;; ven1 should NOT receive the notification
                 (Thread/sleep (* 2 mqtt-settle-ms))
                 (let [msgs (ch/channel-messages mqtt-ch)
                       notification (find-notification
                                     msgs #(= "DynsecACLCrossProg"
                                              (-> % :openadr.notification/object
                                                  :openadr.program/name)))]
                   (is (nil? notification)
                       "VEN should NOT receive notifications on another VEN's topics"))

                 (when-let [pid (-> resp :body :id)]
                   (client/delete-program bl pid))))
             (finally
               (ch/channel-stop mqtt-ch)))))))))

;; ---------------------------------------------------------------------------
;; 4. VEN deletion cleans up broker account
;; ---------------------------------------------------------------------------

(deftest test-ven-deletion-invalidates-credentials
  (testing "After VEN deletion, its old MQTT credentials are rejected"
    (when-dynsec
     ;; Get ven1's current credentials and verify they work
     (let [creds (ven-mqtt-credentials ven1)]
       (is (some? creds) "Need credentials to test")
       (when creds
         (let [username (:username creds)
               password (:password creds)]
           ;; Verify credentials work before deletion
           (let [mqtt-ch (connect-mqtt-with-creds MQTT-broker-url username password)]
             (try
               (is (ch/mqtt-connected? mqtt-ch) "Credentials should work before deletion")
               (finally
                 (ch/channel-stop mqtt-ch))))

           ;; Delete ven1
           (let [ven-id   (ven/ven-id ven1)
                 del-resp (client/delete-ven bl ven-id)]
             (is (= 200 (:status del-resp)) "VEN deletion should succeed")

             ;; Old credentials should now be rejected
             (is (thrown? MqttException
                          (connect-mqtt-with-creds MQTT-broker-url username password))
                 "Old credentials should be rejected after VEN deletion")

             ;; Re-register ven1 so subsequent suites still work
             (ven/register! ven1 "ven1"))))))))

;; ---------------------------------------------------------------------------
;; 5. Backward compatibility
;; ---------------------------------------------------------------------------

(deftest test-anonymous-mode-no-credentials
  (testing "When VTN runs with auth=ANONYMOUS, GET /notifiers returns ANONYMOUS binding"
    (if @dynsec?
      (is true "SKIPPED — VTN is in dynsec mode, this test is for ANONYMOUS mode")
      (let [auth (notifiers-auth ven1)]
        (if auth
          (is (= "ANONYMOUS" (:method auth))
              "Auth method should be ANONYMOUS")
          ;; No authentication block at all is also valid for ANONYMOUS
          (is true "No authentication block is valid for ANONYMOUS mode"))))))

;; ---------------------------------------------------------------------------
;; Test ordering
;; ---------------------------------------------------------------------------

(defn test-ns-hook []
  ;; Credential delivery
  (test-notifiers-returns-ven-credentials)
  (test-notifiers-no-credentials-for-bl)
  (test-ven2-gets-different-credentials)
  ;; Connection tests
  (test-connect-with-valid-credentials)
  (test-connect-with-wrong-password-rejected)
  (test-connect-anonymous-rejected)
  ;; ACL enforcement
  (test-ven-can-subscribe-to-allowed-topics)
  (test-ven-can-subscribe-to-own-targeted-topics)
  (test-ven-cannot-subscribe-to-other-ven-topics)
  ;; Deletion cleanup
  (test-ven-deletion-invalidates-credentials)
  ;; Backward compatibility
  (test-anonymous-mode-no-credentials))
