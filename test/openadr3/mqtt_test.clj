(ns openadr3.mqtt-test
  (:require [openadr3.client :as client]
            [openadr3.common-test :refer [ven1 bl MQTT-broker-url]]
            [clojure.test :refer :all]))

;; ---------------------------------------------------------------------------
;; Fixture: connect/disconnect MQTT for ven1
;; ---------------------------------------------------------------------------

(use-fixtures :once
  (fn [f]
    (client/connect-mqtt! ven1 MQTT-broker-url)
    (try
      (f)
      (finally
        (client/disconnect-mqtt! ven1)))))

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

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(defn- find-notification
  "Find a notification in the messages whose inner object matches pred."
  [msgs pred]
  (->> msgs
       (map :payload)
       (filter #(pred (:openadr.notification/object %)))
       first))

(deftest test-program-create-notification
  (testing "MQTT notification for program creation"
    (delete-program-by-name bl "MQTTTestProgram")
    (client/subscribe-notifications! ven1 client/get-mqtt-topics-programs)
    (client/clear-mqtt-messages! ven1)
    (Thread/sleep 200)

    (let [resp (client/create-program bl {:programName "MQTTTestProgram"})]
      (is (<= (:status resp) 299) "Program creation should succeed")

      (let [msgs    (client/await-mqtt-messages ven1 1 5000)
            notification (find-notification
                          msgs #(= "MQTTTestProgram" (:openadr.program/name %)))]
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

(deftest test-program-delete-notification
  (testing "MQTT notification for program deletion"
    ;; Ensure a program exists to delete
    (delete-program-by-name bl "MQTTDeleteTest")
    (let [create-resp (client/create-program bl {:programName "MQTTDeleteTest"})
          program-id  (-> create-resp :body :id)]
      (is (some? program-id) "Need a program ID to delete")

      (when program-id
        (client/subscribe-notifications! ven1 client/get-mqtt-topics-programs)
        (client/clear-mqtt-messages! ven1)
        (Thread/sleep 200)

        (client/delete-program bl program-id)

        (let [msgs (client/await-mqtt-messages-on-topic
                    ven1 "programs/delete" 1 5000)]
          (is (>= (count msgs) 1) "Should receive a DELETE notification")

          (when (seq msgs)
            (let [notification (:payload (first msgs))]
              (is (= :openadr.operation/delete
                     (:openadr.notification/operation notification))
                  "Notification operation should be :delete"))))))))

(deftest test-event-notification
  (testing "MQTT notification for event creation on a program"
    ;; Use an existing program (Program1 from programs-test)
    (let [program (client/find-program-by-name bl "Program1")]
      (is (some? program) "Program1 must exist (created by programs-test)")

      (when program
        (let [program-id (:id program)]
          ;; Subscribe to program-scoped event topics
          (client/subscribe-notifications!
           ven1 #(client/get-mqtt-topics-program-events % program-id))
          (client/clear-mqtt-messages! ven1)
          (Thread/sleep 200)

          ;; Create an event on this program
          (let [resp (client/create-event bl {:programID program-id
                                              :intervals [{:id 0
                                                           :payloads [{:type "PRICE"
                                                                       :values [1.5]}]}]})]
            (is (<= (:status resp) 299) "Event creation should succeed")

            (let [msgs (client/await-mqtt-messages ven1 1 5000)]
              (is (>= (count msgs) 1) "Should receive an event notification")

              (when (seq msgs)
                (let [notification (:payload (first msgs))]
                  (is (= :openadr.object-type/event
                         (:openadr.notification/object-type notification))
                      "Notification should be for an EVENT")
                  (is (= :openadr.operation/create
                         (:openadr.notification/operation notification))
                      "Operation should be CREATE")

                  (let [obj (:openadr.notification/object notification)]
                    (is (= program-id (:openadr.event/program-id obj))
                        "Event should reference the correct program")))))

            ;; Clean up the event
            (let [event-id (-> resp :body :id)]
              (when event-id
                (client/delete-event bl event-id)))))))))
