(ns openadr3.topics-test
  (:require [openadr3.api :as api]
            [openadr3.common-test :refer [ven1 ven2 bl]]
            [clojure.test :refer :all]))

(def ^:dynamic client)
(def ^:dynamic client-var)
(def ^:dynamic ven-id)
(def ^:dynamic program-name)
(def ^:dynamic program-id)

(deftest test-mqtt-topics-programs
  (testing "MQTT notifier programs topic"
    (let [resp (api/get-mqtt-topics-programs client)
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
    (let [resp (api/get-mqtt-topics-program client program-id)
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
    (let [resp (api/get-mqtt-topics-program-events client program-id)
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
    (let [resp (api/get-mqtt-topics-ven client ven-id)
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
    (let [resp (api/get-mqtt-topics-ven-programs client ven-id)
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
    (let [resp (api/get-mqtt-topics-ven-events client ven-id)
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
    (let [resp (api/get-mqtt-topics-ven-resources client ven-id)
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
          resp (api/get-mqtt-topics-events client)
          status (:status resp)]
      (if (api/authorized? (api/scopes client) (api/endpoint-scopes client endpoint))
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
          resp (api/get-mqtt-topics-reports client)
          status (:status resp)]
      (if (api/authorized? (api/scopes client) (api/endpoint-scopes client endpoint))
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
          resp (api/get-mqtt-topics-subscriptions client)
          status (:status resp)]
      (if (api/authorized? (api/scopes client) (api/endpoint-scopes client endpoint))
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
          resp (api/get-mqtt-topics-vens client)
          status (:status resp)]
      (if (api/authorized? (api/scopes client) (api/endpoint-scopes client endpoint))
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
          resp (api/get-mqtt-topics-resources client)
          status (:status resp)]
      (if (api/authorized? (api/scopes client) (api/endpoint-scopes client endpoint))
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
    (with-redefs [client ven1
                  client-var #'ven1
                  program-name "Program2"]
      (with-redefs [ven-id (-> client-var meta :ven-id)
                    program-id (-> client
                                   (api/find-program-by-name program-name)
                                   :id)]
        (test-mqtt-topics)))))

(deftest test-mqtt-topics-ven2
  (testing "VEN 2 topics tests ->"
    (with-redefs [client ven2
                  client-var #'ven2
                  program-name "Program1"]
      (with-redefs [ven-id (-> client-var meta :ven-id)
                    program-id (-> client
                                   (api/find-program-by-name program-name)
                                   :id)]
        (test-mqtt-topics)))))

(deftest test-mqtt-topics-bl
  (testing "BL topics tests ->"
    (with-redefs [client bl
                  client-var #'bl
                  program-name "Program1"]
      (with-redefs [ven-id (-> client api/get-vens :body first :id)
                    program-id (-> client
                                   (api/find-program-by-name program-name)
                                   :id)]
        (test-mqtt-topics)))))

(deftest test-topics
  (testing "Combined topics tests ->"
    (test-mqtt-topics-ven1)
    (test-mqtt-topics-ven2)
    (test-mqtt-topics-bl)))

(defn test-ns-hook []
  (test-topics))
