(ns openadr3.mqtt-test
  (:require [openadr3.client.base :as client]
            [openadr3.client.ven :as ven]
            [openadr3.channel :as ch]
            [openadr3.common-test :refer [ven1 bl MQTT-broker-url inter-suite-delay-ms
                                          mqtt-settle-ms mqtt-await-ms]]
            [clojure.test :refer :all]))

;; ---------------------------------------------------------------------------
;; MQTT credentials — use when broker requires authentication
;; ---------------------------------------------------------------------------

(defn- mqtt-credentials
  "Fetch MQTT credentials for a client from GET /notifiers.
  Returns opts map with :username/:password when available, empty map otherwise."
  [c]
  (let [auth (get-in (client/get-notifiers c) [:body :MQTT :authentication])]
    (if (and auth (not= "ANONYMOUS" (:method auth)) (:username auth) (:password auth))
      (select-keys auth [:username :password])
      {})))

;; ---------------------------------------------------------------------------
;; MQTT channels — created/destroyed per test run
;; ---------------------------------------------------------------------------

(def ven1-mqtt (atom nil))
(def bl-mqtt (atom nil))

(use-fixtures :once
  (fn [f]
    (Thread/sleep inter-suite-delay-ms)
    (reset! ven1-mqtt (-> (ch/mqtt-channel MQTT-broker-url (mqtt-credentials ven1))
                          ch/channel-start))
    (reset! bl-mqtt   (-> (ch/mqtt-channel MQTT-broker-url (mqtt-credentials bl))
                          ch/channel-start))
    (try
      (f)
      (finally
        (ch/channel-stop @ven1-mqtt)
        (ch/channel-stop @bl-mqtt)))))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- delete-program-by-name [c name]
  (let [{id :id} (client/find-program-by-name c name)]
    (when id (client/delete-program c id))))

(defn- delete-event-by-program [c program-id]
  (let [events (-> (client/search-events c {:programID program-id}) :body)]
    (doseq [{id :id} events]
      (client/delete-event c id))))

(defn- find-notification
  "Find a notification in the messages matching a predicate on the payload.
  pred receives the full notification (not just the inner object)."
  [msgs pred]
  (->> msgs
       (map :payload)
       (filter pred)
       first))

(defn- subscribe-clear-wait!
  "Subscribe mqtt-ch to topics from topic-fn, clear messages, and wait to settle.
  Clears twice — once immediately, then again after the settle period — to drain
  retained messages that arrive asynchronously after subscription."
  [mqtt-ch c topic-fn]
  (let [resp   (topic-fn c)
        topics (client/extract-topics resp)]
    (when topics
      (ch/subscribe-topics mqtt-ch topics)))
  (ch/clear-channel-messages! mqtt-ch)
  (Thread/sleep mqtt-settle-ms)
  (ch/clear-channel-messages! mqtt-ch))

;; ---------------------------------------------------------------------------
;; Program notifications
;; ---------------------------------------------------------------------------

(deftest test-program-create-notification
  (testing "MQTT notification for program creation"
    (delete-program-by-name bl "MQTTTestProgram")
    (subscribe-clear-wait! @ven1-mqtt ven1 client/get-mqtt-topics-programs)

    (let [resp (client/create-program bl {:programName "MQTTTestProgram"})]
      (is (<= (:status resp) 299) "Program creation should succeed")

      (let [msgs    (ch/await-channel-messages @ven1-mqtt 1 mqtt-await-ms)
            notification (find-notification
                          msgs #(and (= :openadr.operation/create
                                        (:openadr.notification/operation %))
                                     (= "MQTTTestProgram"
                                        (-> % :openadr.notification/object
                                            :openadr.program/name))))]
        (is (some? notification) "Should receive notification for MQTTTestProgram")

        (when notification
          (let [m (meta notification)]
            (testing "notification is coerced"
              (is (= :openadr.object-type/program
                     (:openadr.notification/object-type notification))
                  "Notification object-type should be :program")
              (is (= :openadr.operation/create
                     (:openadr.notification/operation notification))
                  "Notification operation should be :create"))

            (testing "inner object is a coerced Program entity"
              (let [obj (:openadr.notification/object notification)]
                (is (= :openadr.object-type/program (:openadr/object-type obj))
                    "Object should be a Program")
                (is (= "MQTTTestProgram" (:openadr.program/name obj))
                    "Program name should match")))

            (testing "channel metadata"
              (is (= :mqtt (:openadr/channel m))
                  "Channel metadata should be :mqtt")
              (is (string? (:openadr/topic m))
                  "Topic metadata should be a string"))))))))

(deftest test-program-update-notification
  (testing "MQTT notification for program update"
    (delete-program-by-name bl "MQTTUpdateProgram")
    (let [create-resp (client/create-program bl {:programName "MQTTUpdateProgram"})
          program-id  (-> create-resp :body :id)]
      (is (some? program-id) "Need a program ID to update")

      (when program-id
        (subscribe-clear-wait! @ven1-mqtt ven1 client/get-mqtt-topics-programs)

        (client/update-program bl program-id
                               {:programName "MQTTUpdateProgram"
                                :descriptions ["updated"]})

        (let [msgs (ch/await-channel-messages @ven1-mqtt 1 mqtt-await-ms)
              notification (find-notification
                            msgs #(and (= :openadr.operation/update
                                          (:openadr.notification/operation %))
                                       (= program-id
                                          (-> % :openadr.notification/object
                                              :openadr/id))))]
          (is (some? notification) "Should receive UPDATE notification")
          (when notification
            (is (= :openadr.object-type/program
                   (:openadr.notification/object-type notification)))))

        ;; Clean up
        (client/delete-program bl program-id)))))

(deftest test-program-delete-notification
  (testing "MQTT notification for program deletion"
    (delete-program-by-name bl "MQTTDeleteTest")
    (let [create-resp (client/create-program bl {:programName "MQTTDeleteTest"})
          program-id  (-> create-resp :body :id)]
      (is (some? program-id) "Need a program ID to delete")

      (when program-id
        (subscribe-clear-wait! @ven1-mqtt ven1 client/get-mqtt-topics-programs)

        (client/delete-program bl program-id)

        (let [msgs (ch/await-channel-messages @ven1-mqtt 1 mqtt-await-ms)
              notification (find-notification
                            msgs #(= :openadr.operation/delete
                                     (:openadr.notification/operation %)))]
          (is (some? notification) "Should receive a DELETE notification")
          (when notification
            (is (= :openadr.object-type/program
                   (:openadr.notification/object-type notification)))))))))

;; ---------------------------------------------------------------------------
;; Event notifications
;; ---------------------------------------------------------------------------

(deftest test-event-create-notification
  (testing "MQTT notification for event creation on a program"
    (let [program (client/find-program-by-name bl "Program1")]
      (is (some? program) "Program1 must exist (created by programs-test)")

      (when program
        (let [program-id (:id program)]
          (subscribe-clear-wait!
           @ven1-mqtt ven1 #(client/get-mqtt-topics-program-events % program-id))

          (let [resp (client/create-event bl {:programID program-id
                                              :intervals [{:id 0
                                                           :payloads [{:type "PRICE"
                                                                       :values [1.5]}]}]})]
            (is (<= (:status resp) 299) "Event creation should succeed")

            (let [msgs (ch/await-channel-messages @ven1-mqtt 1 mqtt-await-ms)
                  notification (find-notification
                                msgs #(= :openadr.operation/create
                                         (:openadr.notification/operation %)))]
              (is (some? notification) "Should receive an event notification")
              (when notification
                (is (= :openadr.object-type/event
                       (:openadr.notification/object-type notification)))
                (is (= program-id
                       (-> notification :openadr.notification/object
                           :openadr.event/program-id)))))

            (when-let [event-id (-> resp :body :id)]
              (client/delete-event bl event-id))))))))

(deftest test-event-update-notification
  (testing "MQTT notification for event update"
    (let [program (client/find-program-by-name bl "Program1")]
      (when program
        (let [program-id (:id program)
              created    (client/create-event bl {:programID program-id
                                                  :intervals [{:id 0
                                                               :payloads [{:type "PRICE"
                                                                           :values [1.0]}]}]})
              event-id   (-> created :body :id)]
          (is (some? event-id) "Need an event ID")

          (when event-id
            (subscribe-clear-wait!
             @ven1-mqtt ven1 #(client/get-mqtt-topics-program-events % program-id))

            (client/update-event bl event-id
                                 {:programID program-id
                                  :eventName "updated-event"
                                  :intervals [{:id 0
                                               :payloads [{:type "PRICE"
                                                           :values [2.0]}]}]})

            (let [msgs (ch/await-channel-messages @ven1-mqtt 1 mqtt-await-ms)
                  notification (find-notification
                                msgs #(and (= :openadr.operation/update
                                              (:openadr.notification/operation %))
                                           (= event-id
                                              (-> % :openadr.notification/object
                                                  :openadr/id))))]
              (is (some? notification) "Should receive UPDATE notification for event")
              (when notification
                (is (= :openadr.object-type/event
                       (:openadr.notification/object-type notification)))))

            (client/delete-event bl event-id)))))))

(deftest test-event-delete-notification
  (testing "MQTT notification for event deletion"
    (let [program (client/find-program-by-name bl "Program1")]
      (when program
        (let [program-id (:id program)
              created    (client/create-event bl {:programID program-id
                                                  :intervals [{:id 0
                                                               :payloads [{:type "PRICE"
                                                                           :values [1.0]}]}]})
              event-id   (-> created :body :id)]
          (is (some? event-id) "Need an event ID")

          (when event-id
            (subscribe-clear-wait!
             @ven1-mqtt ven1 #(client/get-mqtt-topics-program-events % program-id))

            (client/delete-event bl event-id)

            (let [msgs (ch/await-channel-messages @ven1-mqtt 1 mqtt-await-ms)
                  notification (find-notification
                                msgs #(= :openadr.operation/delete
                                         (:openadr.notification/operation %)))]
              (is (some? notification) "Should receive DELETE notification for event")
              (when notification
                (is (= :openadr.object-type/event
                       (:openadr.notification/object-type notification)))))))))))

;; ---------------------------------------------------------------------------
;; VEN notifications
;; ---------------------------------------------------------------------------

(deftest test-ven-update-notification
  (testing "MQTT notification for VEN update"
    (let [ven-id (ven/ven-id ven1)]
      (is (some? ven-id) "ven1 must be registered")

      (when ven-id
        (subscribe-clear-wait!
         @ven1-mqtt ven1 #(client/get-mqtt-topics-ven % ven-id))

        (client/update-ven bl ven-id {:objectType "BL_VEN_REQUEST"
                                      :clientID "ven_client"
                                      :venName "ven1"
                                      :attributes [{:type "MQTT_TEST" :values ["v1"]}]})

        (let [msgs (ch/await-channel-messages @ven1-mqtt 1 mqtt-await-ms)
              notification (find-notification
                            msgs #(and (= :openadr.operation/update
                                          (:openadr.notification/operation %))
                                       (= ven-id
                                          (-> % :openadr.notification/object
                                              :openadr/id))))]
          (is (some? notification) "Should receive UPDATE notification for VEN")
          (when notification
            (is (= :openadr.object-type/ven
                   (:openadr.notification/object-type notification)))))))))

;; ---------------------------------------------------------------------------
;; Resource notifications
;; ---------------------------------------------------------------------------

(deftest test-resource-create-notification
  (testing "MQTT notification for resource creation"
    (let [ven-id (ven/ven-id ven1)]
      (is (some? ven-id) "ven1 must be registered")

      (when ven-id
        (subscribe-clear-wait!
         @ven1-mqtt ven1 #(client/get-mqtt-topics-ven-resources % ven-id))

        (let [resp (client/create-resource ven1 {:venID ven-id
                                                 :objectType "VEN_RESOURCE_REQUEST"
                                                 :resourceName "MQTTResource1"})]
          (is (<= (:status resp) 299) "Resource creation should succeed")

          (let [msgs (ch/await-channel-messages @ven1-mqtt 1 mqtt-await-ms)
                notification (find-notification
                              msgs #(= :openadr.operation/create
                                       (:openadr.notification/operation %)))]
            (is (some? notification) "Should receive CREATE notification for resource")
            (when notification
              (is (= :openadr.object-type/resource
                     (:openadr.notification/object-type notification)))))

          ;; Clean up
          (when-let [res-id (-> resp :body :id)]
            (client/delete-resource bl res-id)))))))

(deftest test-resource-update-notification
  (testing "MQTT notification for resource update"
    (let [ven-id (ven/ven-id ven1)]
      (when ven-id
        (let [created (client/create-resource ven1 {:venID ven-id
                                                    :objectType "VEN_RESOURCE_REQUEST"
                                                    :resourceName "MQTTResource3"})
              res-id  (-> created :body :id)]
          (is (some? res-id) "Need a resource ID")

          (when res-id
            (subscribe-clear-wait!
             @ven1-mqtt ven1 #(client/get-mqtt-topics-ven-resources % ven-id))

            (client/update-resource bl res-id {:objectType "BL_RESOURCE_REQUEST"
                                               :clientID "ven_client"
                                               :venID ven-id
                                               :resourceName "MQTTResource3"
                                               :attributes [{:type "UPDATED" :values ["yes"]}]})

            (let [msgs (ch/await-channel-messages @ven1-mqtt 1 mqtt-await-ms)
                  notification (find-notification
                                msgs #(and (= :openadr.operation/update
                                              (:openadr.notification/operation %))
                                           (= res-id
                                              (-> % :openadr.notification/object
                                                  :openadr/id))))]
              (is (some? notification) "Should receive UPDATE notification for resource")
              (when notification
                (is (= :openadr.object-type/resource
                       (:openadr.notification/object-type notification)))))

            (client/delete-resource bl res-id)))))))

(deftest test-resource-delete-notification
  (testing "MQTT notification for resource deletion"
    (let [ven-id (ven/ven-id ven1)]
      (when ven-id
        (let [created (client/create-resource ven1 {:venID ven-id
                                                    :objectType "VEN_RESOURCE_REQUEST"
                                                    :resourceName "MQTTResource2"})
              res-id  (-> created :body :id)]
          (is (some? res-id) "Need a resource ID")

          (when res-id
            (subscribe-clear-wait!
             @ven1-mqtt ven1 #(client/get-mqtt-topics-ven-resources % ven-id))

            (client/delete-resource bl res-id)

            (let [msgs (ch/await-channel-messages @ven1-mqtt 1 mqtt-await-ms)
                  notification (find-notification
                                msgs #(= :openadr.operation/delete
                                         (:openadr.notification/operation %)))]
              (is (some? notification) "Should receive DELETE notification for resource")
              (when notification
                (is (= :openadr.object-type/resource
                       (:openadr.notification/object-type notification)))))))))))

;; ---------------------------------------------------------------------------
;; Report notifications (BL receives these)
;; ---------------------------------------------------------------------------

(deftest test-report-create-notification
  (testing "MQTT notification for report creation (BL receives)"
    (subscribe-clear-wait! @bl-mqtt bl client/get-mqtt-topics-reports)

    (let [program-id (:id (client/find-program-by-name bl "Program1"))
          event-resp (client/create-event bl {:programID program-id
                                              :intervals [{:id 0
                                                           :payloads [{:type "PRICE"
                                                                       :values [1.0]}]}]})
          event-id   (-> event-resp :body :id)]
      (is (some? event-id) "Need an event for the report")

      (when event-id
        (ch/clear-channel-messages! @bl-mqtt)
        (Thread/sleep mqtt-settle-ms)
        (ch/clear-channel-messages! @bl-mqtt)

        (let [resp (client/create-report ven1 {:programID program-id
                                               :eventID event-id
                                               :clientName "test-client"
                                               :reportName "MQTTReport"
                                               :resources [{:resourceName "r1"
                                                            :intervals [{:id 0
                                                                         :payloads [{:type "USAGE"
                                                                                     :values [100]}]}]}]})]
          (is (<= (:status resp) 299) "Report creation should succeed")

          (let [msgs (ch/await-channel-messages @bl-mqtt 1 mqtt-await-ms)
                notification (find-notification
                              msgs #(= :openadr.operation/create
                                       (:openadr.notification/operation %)))]
            (is (some? notification) "BL should receive CREATE notification for report")
            (when notification
              (is (= :openadr.object-type/report
                     (:openadr.notification/object-type notification)))))

          ;; Clean up
          (when-let [report-id (-> resp :body :id)]
            (client/delete-report ven1 report-id)))

        (client/delete-event bl event-id)))))

(deftest test-report-update-notification
  (testing "MQTT notification for report update (BL receives)"
    (let [program-id (:id (client/find-program-by-name bl "Program1"))
          event-resp (client/create-event bl {:programID program-id
                                              :intervals [{:id 0
                                                           :payloads [{:type "PRICE"
                                                                       :values [1.0]}]}]})
          event-id   (-> event-resp :body :id)]
      (is (some? event-id) "Need an event for the report")

      (when event-id
        (let [report-resp (client/create-report ven1 {:programID program-id
                                                      :eventID event-id
                                                      :clientName "test-client"
                                                      :reportName "MQTTReportUpd"
                                                      :resources [{:resourceName "r1"
                                                                   :intervals [{:id 0
                                                                                :payloads [{:type "USAGE"
                                                                                            :values [100]}]}]}]})
              report-id   (-> report-resp :body :id)]
          (is (some? report-id) "Need a report ID")

          (when report-id
            (subscribe-clear-wait! @bl-mqtt bl client/get-mqtt-topics-reports)

            (client/update-report ven1 report-id
                                  {:programID program-id
                                   :eventID event-id
                                   :clientName "test-client"
                                   :reportName "MQTTReportUpdated"
                                   :resources [{:resourceName "r1"
                                                :intervals [{:id 0
                                                             :payloads [{:type "USAGE"
                                                                         :values [200]}]}]}]})

            (let [msgs (ch/await-channel-messages @bl-mqtt 1 mqtt-await-ms)
                  notification (find-notification
                                msgs #(and (= :openadr.operation/update
                                              (:openadr.notification/operation %))
                                           (= report-id
                                              (-> % :openadr.notification/object
                                                  :openadr/id))))]
              (is (some? notification) "BL should receive UPDATE notification for report")
              (when notification
                (is (= :openadr.object-type/report
                       (:openadr.notification/object-type notification)))))

            (client/delete-report ven1 report-id)))

        (client/delete-event bl event-id)))))

(deftest test-report-delete-notification
  (testing "MQTT notification for report deletion (BL receives)"
    (let [program-id (:id (client/find-program-by-name bl "Program1"))
          event-resp (client/create-event bl {:programID program-id
                                              :intervals [{:id 0
                                                           :payloads [{:type "PRICE"
                                                                       :values [1.0]}]}]})
          event-id   (-> event-resp :body :id)]
      (is (some? event-id) "Need an event for the report")

      (when event-id
        (let [report-resp (client/create-report ven1 {:programID program-id
                                                      :eventID event-id
                                                      :clientName "test-client"
                                                      :reportName "MQTTReportDel"
                                                      :resources [{:resourceName "r1"
                                                                   :intervals [{:id 0
                                                                                :payloads [{:type "USAGE"
                                                                                            :values [100]}]}]}]})
              report-id   (-> report-resp :body :id)]
          (is (some? report-id) "Need a report ID")

          (when report-id
            (subscribe-clear-wait! @bl-mqtt bl client/get-mqtt-topics-reports)

            (client/delete-report ven1 report-id)

            (let [msgs (ch/await-channel-messages @bl-mqtt 1 mqtt-await-ms)
                  notification (find-notification
                                msgs #(= :openadr.operation/delete
                                         (:openadr.notification/operation %)))]
              (is (some? notification) "BL should receive DELETE notification for report")
              (when notification
                (is (= :openadr.object-type/report
                       (:openadr.notification/object-type notification)))))))

        (client/delete-event bl event-id)))))

;; ---------------------------------------------------------------------------
;; Subscription notifications (BL receives these)
;; ---------------------------------------------------------------------------

(deftest test-subscription-create-notification
  (testing "MQTT notification for subscription creation (BL receives)"
    (subscribe-clear-wait! @bl-mqtt bl client/get-mqtt-topics-subscriptions)

    (let [program-id (:id (client/find-program-by-name bl "Program1"))
          resp (client/create-subscription ven1
                                           {:programID program-id
                                            :clientName "MQTTSubCreate"
                                            :objectOperations [{:objects ["EVENT"]
                                                                :operations ["CREATE"]
                                                                :callbackUrl "https://example.com/cb"
                                                                :bearerToken "tok"}]})]
      (is (<= (:status resp) 299) "Subscription creation should succeed")

      (let [msgs (ch/await-channel-messages @bl-mqtt 1 mqtt-await-ms)
            notification (find-notification
                          msgs #(= :openadr.operation/create
                                   (:openadr.notification/operation %)))]
        (is (some? notification) "BL should receive CREATE notification for subscription")
        (when notification
          (is (= :openadr.object-type/subscription
                 (:openadr.notification/object-type notification)))))

      (when-let [sub-id (-> resp :body :id)]
        (client/delete-subscription ven1 sub-id)))))

(deftest test-subscription-delete-notification
  (testing "MQTT notification for subscription deletion (BL receives)"
    (let [program-id (:id (client/find-program-by-name bl "Program1"))
          created (client/create-subscription ven1
                                              {:programID program-id
                                               :clientName "MQTTSubDelete"
                                               :objectOperations [{:objects ["EVENT"]
                                                                   :operations ["CREATE"]
                                                                   :callbackUrl "https://example.com/cb"
                                                                   :bearerToken "tok"}]})
          sub-id  (-> created :body :id)]
      (is (some? sub-id) "Need a subscription ID")

      (when sub-id
        (subscribe-clear-wait! @bl-mqtt bl client/get-mqtt-topics-subscriptions)

        (client/delete-subscription ven1 sub-id)

        (let [msgs (ch/await-channel-messages @bl-mqtt 1 mqtt-await-ms)
              notification (find-notification
                            msgs #(= :openadr.operation/delete
                                     (:openadr.notification/operation %)))]
          (is (some? notification) "BL should receive DELETE notification for subscription")
          (when notification
            (is (= :openadr.object-type/subscription
                   (:openadr.notification/object-type notification)))))))))

;; ---------------------------------------------------------------------------
;; Targeted program notifications (VEN-scoped)
;; ---------------------------------------------------------------------------

(deftest test-targeted-program-notification
  (testing "MQTT notification on VEN-scoped program topics for targeted program"
    (let [ven-id (ven/ven-id ven1)]
      (is (some? ven-id) "ven1 must be registered")

      (when ven-id
        (delete-program-by-name bl "MQTTTargetedProg")
        (subscribe-clear-wait!
         @ven1-mqtt ven1 #(client/get-mqtt-topics-ven-programs % ven-id))

        (let [resp (client/create-program bl {:programName "MQTTTargetedProg"
                                              :targets [ven-id]})]
          (is (<= (:status resp) 299) "Targeted program creation should succeed")

          (let [msgs (ch/await-channel-messages @ven1-mqtt 1 mqtt-await-ms)
                notification (find-notification
                              msgs #(and (= :openadr.operation/create
                                            (:openadr.notification/operation %))
                                         (= "MQTTTargetedProg"
                                            (-> % :openadr.notification/object
                                                :openadr.program/name))))]
            (is (some? notification)
                "Should receive CREATE notification on VEN-scoped programs topic")
            (when notification
              (is (= :openadr.object-type/program
                     (:openadr.notification/object-type notification)))))

          ;; Clean up
          (when-let [pid (-> resp :body :id)]
            (client/delete-program bl pid)))))))

(deftest test-targeted-event-notification
  (testing "MQTT notification on VEN-scoped event topics for targeted event"
    (let [ven-id (ven/ven-id ven1)]
      (when ven-id
        (subscribe-clear-wait!
         @ven1-mqtt ven1 #(client/get-mqtt-topics-ven-events % ven-id))

        (let [program (client/find-program-by-name bl "Program1")
              program-id (:id program)]
          (when program-id
            (let [resp (client/create-event bl {:programID program-id
                                                :targets [ven-id]
                                                :intervals [{:id 0
                                                             :payloads [{:type "PRICE"
                                                                         :values [1.5]}]}]})]
              (is (<= (:status resp) 299) "Targeted event creation should succeed")

              (let [msgs (ch/await-channel-messages @ven1-mqtt 1 mqtt-await-ms)
                    notification (find-notification
                                  msgs #(= :openadr.operation/create
                                           (:openadr.notification/operation %)))]
                (is (some? notification)
                    "Should receive CREATE notification on VEN-scoped events topic")
                (when notification
                  (is (= :openadr.object-type/event
                         (:openadr.notification/object-type notification)))))

              (when-let [event-id (-> resp :body :id)]
                (client/delete-event bl event-id)))))))))
