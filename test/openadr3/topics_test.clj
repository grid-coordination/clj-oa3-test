(ns openadr3.topics-test
  (:require [openadr3.client :as client]
            [openadr3.common-test :refer [ven1 ven2 bl]]
            [clojure.test :refer :all]))

(def ^:dynamic c)
(def ^:dynamic c-var)
(def ^:dynamic ven-id)
(def ^:dynamic program-name)
(def ^:dynamic program-id)

(deftest test-mqtt-topics-programs
  (testing "MQTT notifier programs topic"
    (let [resp (client/get-mqtt-topics-programs c)
          status (:status resp)
          ok? (is (<= status 299) "Check for 2xx status")]
      (when ok?
        (let [body (:body resp)
              has-topics? (is (contains? body :topics) "Check response body contains :topics")]
          (when has-topics?
            (let [{:keys [ALL CREATE DELETE UPDATE]} (:topics body)]
              (is (not (empty? ALL)) "Check for topic: ALL")
              (is (not (empty? CREATE)) "Check for topic: CREATE")
              (is (not (empty? UPDATE)) "Check for topic: UPDATE")
              (is (not (empty? DELETE)) "Check for topic: DELETE"))))))))

(deftest test-mqtt-topics-program
  (testing "MQTT notifier program topic"
    (let [resp (client/get-mqtt-topics-program c program-id)
          status (:status resp)
          ok? (is (<= status 299) "Check for 2xx status")]
      (when ok?
        (let [body (:body resp)
              has-topics? (is (contains? body :topics) "Check response body contains :topics")]
          (when has-topics?
            (let [{:keys [ALL CREATE DELETE UPDATE]} (:topics body)]
              (is (not (empty? ALL)) "Check for topic: ALL")
              (is (empty? CREATE) "Check for absence of topic: CREATE")
              (is (not (empty? UPDATE)) "Check for topic: UPDATE")
              (is (not (empty? DELETE)) "Check for topic: DELETE"))))))))

(deftest test-mqtt-topics-program-events
  (testing "MQTT notifier program events topic"
    (let [resp (client/get-mqtt-topics-program-events c program-id)
          status (:status resp)
          ok? (is (<= status 299) "Check for 2xx status")]
      (when ok?
        (let [body (:body resp)
              has-topics? (is (contains? body :topics) "Check response body contains :topics")]
          (when has-topics?
            (let [{:keys [ALL CREATE DELETE UPDATE]} (:topics body)]
              (is (not (empty? ALL)) "Check for topic: ALL")
              (is (not (empty? CREATE)) "Check for topic: CREATE")
              (is (not (empty? UPDATE)) "Check for topic: UPDATE")
              (is (not (empty? DELETE)) "Check for topic: DELETE"))))))))

(deftest test-mqtt-topics-ven
  (testing "MQTT notifier ven topic"
    (let [resp (client/get-mqtt-topics-ven c ven-id)
          status (:status resp)
          ok? (is (<= status 299) "Check for 2xx status")]
      (when ok?
        (let [body (:body resp)
              has-topics? (is (contains? body :topics) "Check response body contains :topics")]
          (when has-topics?
            (let [{:keys [ALL CREATE DELETE UPDATE]} (:topics body)]
              (is (not (empty? ALL)) "Check for topic: ALL")
              (is (empty? CREATE) "Check for absence of topic: CREATE")
              (is (not (empty? UPDATE)) "Check for topic: UPDATE")
              (is (not (empty? DELETE)) "Check for topic: DELETE"))))))))

(deftest test-mqtt-topics-ven-programs
  (testing "MQTT notifier ven programs topic"
    (let [resp (client/get-mqtt-topics-ven-programs c ven-id)
          status (:status resp)
          ok? (is (<= status 299) "Check for 2xx status")]
      (when ok?
        (let [body (:body resp)
              has-topics? (is (contains? body :topics) "Check response body contains :topics")]
          (when has-topics?
            (let [{:keys [ALL CREATE DELETE UPDATE]} (:topics body)]
              (is (not (empty? ALL)) "Check for topic: ALL")
              (is (not (empty? CREATE)) "Check for topic: CREATE")
              (is (not (empty? UPDATE)) "Check for topic: UPDATE")
              (is (not (empty? DELETE)) "Check for topic: DELETE"))))))))

(deftest test-mqtt-topics-ven-events
  (testing "MQTT notifier ven events topic"
    (let [resp (client/get-mqtt-topics-ven-events c ven-id)
          status (:status resp)
          ok? (is (<= status 299) "Check for 2xx status")]
      (when ok?
        (let [body (:body resp)
              has-topics? (is (contains? body :topics) "Check response body contains :topics")]
          (when has-topics?
            (let [{:keys [ALL CREATE DELETE UPDATE]} (:topics body)]
              (is (not (empty? ALL)) "Check for topic: ALL")
              (is (not (empty? CREATE)) "Check for topic: CREATE")
              (is (not (empty? UPDATE)) "Check for topic: UPDATE")
              (is (not (empty? DELETE)) "Check for topic: DELETE"))))))))

(deftest test-mqtt-topics-ven-resources
  (testing "MQTT notifier ven resources topic"
    (let [resp (client/get-mqtt-topics-ven-resources c ven-id)
          status (:status resp)
          ok? (is (<= status 299) "Check for 2xx status")]
      (when ok?
        (let [body (:body resp)
              has-topics? (is (contains? body :topics) "Check response body contains :topics")]
          (when has-topics?
            (let [{:keys [ALL CREATE DELETE UPDATE]} (:topics body)]
              (is (not (empty? ALL)) "Check for topic: ALL")
              (is (not (empty? CREATE)) "Check for topic: CREATE")
              (is (not (empty? UPDATE)) "Check for topic: UPDATE")
              (is (not (empty? DELETE)) "Check for topic: DELETE"))))))))

(deftest test-mqtt-topics-events
  (testing "MQTT notifier events topic"
    (let [endpoint :list-all-mqtt-notifier-topics-events
          resp (client/get-mqtt-topics-events c)
          status (:status resp)]
      (if (client/authorized? c endpoint)
        (when (is (<= status 299) "Check for 2xx status")
          (let [body (:body resp)
                has-topics? (is (contains? body :topics) "Check response body contains :topics")]
            (when has-topics?
              (let [{:keys [ALL CREATE DELETE UPDATE]} (:topics body)]
                (is (not (empty? ALL)) "Check for topic: ALL")
                (is (not (empty? CREATE)) "Check for topic: CREATE")
                (is (not (empty? UPDATE)) "Check for topic: UPDATE")
                (is (not (empty? DELETE)) "Check for topic: DELETE")))))
        (is (= status 403) "Check for 403 status")))))

(deftest test-mqtt-topics-reports
  (testing "MQTT notifier reports topic"
    (let [endpoint :list-all-mqtt-notifier-topics-reports
          resp (client/get-mqtt-topics-reports c)
          status (:status resp)]
      (if (client/authorized? c endpoint)
        (when (is (<= status 299) "Check for 2xx status")
          (let [body (:body resp)
                has-topics? (is (contains? body :topics) "Check response body contains :topics")]
            (when has-topics?
              (let [{:keys [ALL CREATE DELETE UPDATE]} (:topics body)]
                (is (not (empty? ALL)) "Check for topic: ALL")
                (is (not (empty? CREATE)) "Check for topic: CREATE")
                (is (not (empty? UPDATE)) "Check for topic: UPDATE")
                (is (not (empty? DELETE)) "Check for topic: DELETE")))))
        (is (= status 403) "Check for 403 status")))))

(deftest test-mqtt-topics-subscriptions
  (testing "MQTT notifier subscriptions topic"
    (let [endpoint :list-all-mqtt-notifier-topics-subscriptions
          resp (client/get-mqtt-topics-subscriptions c)
          status (:status resp)]
      (if (client/authorized? c endpoint)
        (when (is (<= status 299) "Check for 2xx status")
          (let [body (:body resp)
                has-topics? (is (contains? body :topics) "Check response body contains :topics")]
            (when has-topics?
              (let [{:keys [ALL CREATE DELETE UPDATE]} (:topics body)]
                (is (not (empty? ALL)) "Check for topic: ALL")
                (is (not (empty? CREATE)) "Check for topic: CREATE")
                (is (not (empty? UPDATE)) "Check for topic: UPDATE")
                (is (not (empty? DELETE)) "Check for topic: DELETE")))))
        (is (= status 403) "Check for 403 status")))))

(deftest test-mqtt-topics-vens
  (testing "MQTT notifier vens topic"
    (let [endpoint :list-all-mqtt-notifier-topics-vens
          resp (client/get-mqtt-topics-vens c)
          status (:status resp)]
      (if (client/authorized? c endpoint)
        (when (is (<= status 299) "Check for 2xx status")
          (let [body (:body resp)
                has-topics? (is (contains? body :topics) "Check response body contains :topics")]
            (when has-topics?
              (let [{:keys [ALL CREATE DELETE UPDATE]} (:topics body)]
                (is (not (empty? ALL)) "Check for topic: ALL")
                (is (not (empty? CREATE)) "Check for topic: CREATE")
                (is (not (empty? UPDATE)) "Check for topic: UPDATE")
                (is (not (empty? DELETE)) "Check for topic: DELETE")))))
        (is (= status 403) "Check for 403 status")))))

(deftest test-mqtt-topics-resources
  (testing "MQTT notifier resources topic"
    (let [endpoint :list-all-mqtt-notifier-topics-resources
          resp (client/get-mqtt-topics-resources c)
          status (:status resp)]
      (if (client/authorized? c endpoint)
        (when (is (<= status 299) "Check for 2xx status")
          (let [body (:body resp)
                has-topics? (is (contains? body :topics) "Check response body contains :topics")]
            (when has-topics?
              (let [{:keys [ALL CREATE DELETE UPDATE]} (:topics body)]
                (is (not (empty? ALL)) "Check for topic: ALL")
                (is (not (empty? CREATE)) "Check for topic: CREATE")
                (is (not (empty? UPDATE)) "Check for topic: UPDATE")
                (is (not (empty? DELETE)) "Check for topic: DELETE")))))
        (is (= status 403) "Check for 403 status")))))

(deftest test-mqtt-topics
  (testing "Client topics tests ->"
    (test-mqtt-topics-programs)
    (test-mqtt-topics-program)
    (test-mqtt-topics-program-events)
    (test-mqtt-topics-ven)
    (test-mqtt-topics-ven-programs)
    (test-mqtt-topics-ven-events)
    (test-mqtt-topics-ven-resources)
    (test-mqtt-topics-events)
    (test-mqtt-topics-reports)
    (test-mqtt-topics-subscriptions)
    (test-mqtt-topics-vens)
    (test-mqtt-topics-resources)))

(deftest test-mqtt-topics-ven1
  (testing "VEN 1 topics tests ->"
    (with-redefs [c ven1
                  c-var #'ven1
                  program-name "Program2"]
      (with-redefs [ven-id (-> c-var meta :ven-id)
                    program-id (-> (client/find-program-by-name c program-name)
                                   :id)]
        (test-mqtt-topics)))))

(deftest test-mqtt-topics-ven2
  (testing "VEN 2 topics tests ->"
    (with-redefs [c ven2
                  c-var #'ven2
                  program-name "Program1"]
      (with-redefs [ven-id (-> c-var meta :ven-id)
                    program-id (-> (client/find-program-by-name c program-name)
                                   :id)]
        (test-mqtt-topics)))))

(deftest test-mqtt-topics-bl
  (testing "BL topics tests ->"
    (with-redefs [c bl
                  c-var #'bl
                  program-name "Program1"]
      (with-redefs [ven-id (-> (client/get-vens c) :body first :id)
                    program-id (-> (client/find-program-by-name c program-name)
                                   :id)]
        (test-mqtt-topics)))))

(deftest test-topics
  (testing "Combined topics tests ->"
    (test-mqtt-topics-ven1)
    (test-mqtt-topics-ven2)
    (test-mqtt-topics-bl)))

(defn test-ns-hook []
  (test-topics))
