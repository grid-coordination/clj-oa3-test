(ns openadr3.webhook-test
  (:require [openadr3.client.base :as client]
            [openadr3.channel :as ch]
            [openadr3.common-test :refer [ven1 bl inter-suite-delay-ms]]
            [clojure.test :refer [deftest is testing use-fixtures]]))

;; ---------------------------------------------------------------------------
;; Webhook channel — created/destroyed per test run
;; ---------------------------------------------------------------------------

(def ven1-webhook (atom nil))

(use-fixtures :once
  (fn [f]
    (Thread/sleep inter-suite-delay-ms)
    (reset! ven1-webhook (-> (ch/webhook-channel {:port 0 :callback-host "127.0.0.1"})
                             ch/channel-start))
    (try
      (f)
      (finally
        (ch/channel-stop @ven1-webhook)))))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- find-notification
  "Find a notification in messages matching a predicate on the payload."
  [msgs pred]
  (->> msgs
       (map :payload)
       (filter pred)
       first))

(defn- delete-program-by-name [c name]
  (let [{id :id} (client/find-program-by-name c name)]
    (when id (client/delete-program c id))))

(defn- find-program-id []
  (:id (client/find-program-by-name bl "Program1")))

(defn- webhook-subscription-body
  "Create a subscription with webhook callback URL pointing to ven1's receiver."
  [client-name program-id & {:keys [objects operations targets]
                             :or {objects ["EVENT"]
                                  operations ["CREATE"]}}]
  (cond-> {:programID program-id
           :clientName client-name
           :objectOperations [{:objects objects
                               :operations operations
                               :callbackUrl (ch/callback-url @ven1-webhook)
                               :bearerToken "test-token"}]}
    targets (assoc :targets targets)))

;; ---------------------------------------------------------------------------
;; Webhook notification: event CREATE
;; ---------------------------------------------------------------------------

(deftest test-webhook-event-create-notification
  (testing "Webhook notification for event creation via subscription"
    (let [program-id (find-program-id)]
      (is (some? program-id) "Program1 must exist (created by programs-test)")

      (when program-id
        (let [sub-resp (client/create-subscription
                        ven1 (webhook-subscription-body "WebhookEventCreate"
                                                        program-id))]
          (is (= 201 (:status sub-resp)) "Subscription creation should succeed")

          (when (= 201 (:status sub-resp))
            (let [sub-id (-> sub-resp :body :id)]
              (ch/clear-channel-messages! @ven1-webhook)
              (Thread/sleep 200)

              (let [event-resp (client/create-event
                                bl {:programID program-id
                                    :intervals [{:id 0
                                                 :payloads [{:type "PRICE"
                                                             :values [1.5]}]}]})]
                (is (<= (:status event-resp) 299) "Event creation should succeed")

                (let [msgs (ch/await-channel-messages @ven1-webhook 1 10000)
                      notification (find-notification
                                    msgs #(= :openadr.operation/create
                                             (:openadr.notification/operation %)))]
                  (is (some? notification)
                      "Should receive CREATE notification via webhook")

                  (when notification
                    (testing "notification is coerced"
                      (is (= :openadr.object-type/event
                             (:openadr.notification/object-type notification))
                          "Notification object-type should be :event")
                      (is (= :openadr.operation/create
                             (:openadr.notification/operation notification))
                          "Notification operation should be :create"))

                    (testing "inner object is a coerced Event entity"
                      (let [obj (:openadr.notification/object notification)]
                        (is (= :openadr.object-type/event (:openadr/object-type obj))
                            "Object should be an Event")
                        (is (= program-id (:openadr.event/program-id obj))
                            "Event should reference the correct program")))

                    (testing "channel metadata"
                      (let [m (meta notification)]
                        (is (= :webhook (:openadr/channel m))
                            "Channel metadata should be :webhook")
                        (is (= "/notifications" (:openadr/path m))
                            "Path metadata should be /notifications")))))

                ;; Clean up
                (when-let [event-id (-> event-resp :body :id)]
                  (client/delete-event bl event-id)))

              (client/delete-subscription ven1 sub-id))))))))

;; ---------------------------------------------------------------------------
;; Webhook notification: program CREATE
;; ---------------------------------------------------------------------------

(deftest test-webhook-program-create-notification
  (testing "Webhook notification for program creation via subscription"
    (let [program-id (find-program-id)]
      (is (some? program-id) "Program1 must exist for subscription programID")

      (when program-id
        (delete-program-by-name bl "WebhookProgTest")
        (let [sub-resp (client/create-subscription
                        ven1 (webhook-subscription-body
                              "WebhookProgCreate" program-id
                              :objects ["PROGRAM"]
                              :operations ["CREATE"]))]
          (is (= 201 (:status sub-resp)) "Subscription creation should succeed")

          (when (= 201 (:status sub-resp))
            (let [sub-id (-> sub-resp :body :id)]
              (ch/clear-channel-messages! @ven1-webhook)
              (Thread/sleep 200)

              (let [prog-resp (client/create-program bl {:programName "WebhookProgTest"})]
                (is (<= (:status prog-resp) 299) "Program creation should succeed")

                (let [msgs (ch/await-channel-messages @ven1-webhook 1 10000)
                      notification (find-notification
                                    msgs #(and (= :openadr.operation/create
                                                  (:openadr.notification/operation %))
                                               (= "WebhookProgTest"
                                                  (-> % :openadr.notification/object
                                                      :openadr.program/name))))]
                  (is (some? notification)
                      "Should receive CREATE notification for program via webhook")

                  (when notification
                    (is (= :openadr.object-type/program
                           (:openadr.notification/object-type notification)))))

                ;; Clean up
                (when-let [pid (-> prog-resp :body :id)]
                  (client/delete-program bl pid)))

              (client/delete-subscription ven1 sub-id))))))))

;; ---------------------------------------------------------------------------
;; Webhook notification: program DELETE
;; ---------------------------------------------------------------------------

(deftest test-webhook-program-delete-notification
  (testing "Webhook notification for program deletion via subscription"
    (let [program-id (find-program-id)]
      (when program-id
        (delete-program-by-name bl "WebhookProgDel")
        (let [sub-resp (client/create-subscription
                        ven1 (webhook-subscription-body
                              "WebhookProgDelete" program-id
                              :objects ["PROGRAM"]
                              :operations ["DELETE"]))]
          (when (= 201 (:status sub-resp))
            (let [sub-id (-> sub-resp :body :id)]
              ;; Create the program first
              (let [prog-resp (client/create-program bl {:programName "WebhookProgDel"})
                    pid       (-> prog-resp :body :id)]
                (when pid
                  (ch/clear-channel-messages! @ven1-webhook)
                  (Thread/sleep 200)

                  ;; Delete it and expect notification
                  (client/delete-program bl pid)

                  (let [msgs (ch/await-channel-messages @ven1-webhook 1 10000)
                        notification (find-notification
                                      msgs #(= :openadr.operation/delete
                                               (:openadr.notification/operation %)))]
                    (is (some? notification)
                        "Should receive DELETE notification for program via webhook")
                    (when notification
                      (is (= :openadr.object-type/program
                             (:openadr.notification/object-type notification)))))))

              (client/delete-subscription ven1 sub-id))))))))
