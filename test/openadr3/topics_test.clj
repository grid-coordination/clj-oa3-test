(ns openadr3.topics-test
  (:require [openadr3.client :as client]
            [openadr3.common-test :refer [ven1 ven2 bl bad-token inter-suite-delay-ms]]
            [clojure.test :refer :all]))

(use-fixtures :once
  (fn [f]
    (Thread/sleep inter-suite-delay-ms)
    (f)))

;; ---------------------------------------------------------------------------
;; Individual MQTT topic tests — parameterized by c, ven-id, program-id
;; ---------------------------------------------------------------------------

(defn- check-topics-present [body]
  (let [{:keys [ALL CREATE DELETE UPDATE]} (:topics body)]
    (is (not (empty? ALL)) "Check for topic: ALL")
    (is (not (empty? CREATE)) "Check for topic: CREATE")
    (is (not (empty? UPDATE)) "Check for topic: UPDATE")
    (is (not (empty? DELETE)) "Check for topic: DELETE")))

(defn- check-topics-no-create [body]
  (let [{:keys [ALL CREATE DELETE UPDATE]} (:topics body)]
    (is (not (empty? ALL)) "Check for topic: ALL")
    (is (empty? CREATE) "Check for absence of topic: CREATE")
    (is (not (empty? UPDATE)) "Check for topic: UPDATE")
    (is (not (empty? DELETE)) "Check for topic: DELETE")))

(defn- test-topics-endpoint
  "Test a topics endpoint. Checks 2xx and :topics key in body.
  topic-checker is called with body when both pass."
  [resp topic-checker]
  (let [status (:status resp)
        ok? (is (<= status 299) "Check for 2xx status")]
    (when ok?
      (let [body (:body resp)
            has-topics? (is (contains? body :topics) "Check response body contains :topics")]
        (when has-topics?
          (topic-checker body))))))

(defn- test-scoped-endpoint
  "Test an endpoint that may be forbidden based on client scopes."
  [c endpoint resp]
  (let [status (:status resp)]
    (if (client/authorized? c endpoint)
      (test-topics-endpoint resp check-topics-present)
      (is (= status 403) "Check for 403 status"))))

(defn run-topics-for-client
  "Run all MQTT topic tests for a given client, ven-id, and program-id."
  [c ven-id program-id]
  (testing "programs topics"
    (test-topics-endpoint (client/get-mqtt-topics-programs c) check-topics-present))
  (testing "program topics"
    (test-topics-endpoint (client/get-mqtt-topics-program c program-id) check-topics-no-create))
  (testing "program events topics"
    (test-topics-endpoint (client/get-mqtt-topics-program-events c program-id) check-topics-present))
  (testing "ven topics"
    (test-topics-endpoint (client/get-mqtt-topics-ven c ven-id) check-topics-no-create))
  (testing "ven programs topics"
    (test-topics-endpoint (client/get-mqtt-topics-ven-programs c ven-id) check-topics-present))
  (testing "ven events topics"
    (test-topics-endpoint (client/get-mqtt-topics-ven-events c ven-id) check-topics-present))
  (testing "ven resources topics"
    (test-topics-endpoint (client/get-mqtt-topics-ven-resources c ven-id) check-topics-present))
  (testing "events topics"
    (test-scoped-endpoint c :list-all-mqtt-notifier-topics-events
                          (client/get-mqtt-topics-events c)))
  (testing "reports topics"
    (test-scoped-endpoint c :list-all-mqtt-notifier-topics-reports
                          (client/get-mqtt-topics-reports c)))
  (testing "subscriptions topics"
    (test-scoped-endpoint c :list-all-mqtt-notifier-topics-subscriptions
                          (client/get-mqtt-topics-subscriptions c)))
  (testing "vens topics"
    (test-scoped-endpoint c :list-all-mqtt-notifier-topics-vens
                          (client/get-mqtt-topics-vens c)))
  (testing "resources topics"
    (test-scoped-endpoint c :list-all-mqtt-notifier-topics-resources
                          (client/get-mqtt-topics-resources c))))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest test-mqtt-topics-ven1
  (testing "VEN 1 topics tests"
    (let [ven-id  (client/ven-id ven1)
          prog-id (-> (client/find-program-by-name ven1 "Program2") :id)]
      (is (some? ven-id) "ven1 must be registered by vens-test")
      (when ven-id
        (run-topics-for-client ven1 ven-id prog-id)))))

(deftest test-mqtt-topics-ven2
  (testing "VEN 2 topics tests"
    (let [ven-id  (client/ven-id ven2)
          prog-id (-> (client/find-program-by-name ven2 "Program1") :id)]
      (is (some? ven-id) "ven2 must be registered by vens-test")
      (when ven-id
        (run-topics-for-client ven2 ven-id prog-id)))))

(deftest test-mqtt-topics-bl
  (testing "BL topics tests"
    (let [ven-id  (-> (client/get-vens bl) :body first :id)
          prog-id (-> (client/find-program-by-name bl "Program1") :id)]
      (is (some? ven-id) "At least one VEN must exist")
      (when ven-id
        (run-topics-for-client bl ven-id prog-id)))))

;; ---------------------------------------------------------------------------
;; Bad token tests — every topic endpoint should return 403
;; ---------------------------------------------------------------------------

(deftest test-mqtt-topics-bad-token-programs
  (testing "Bad token cannot get programs topics"
    (let [resp (client/get-mqtt-topics-programs bad-token)]
      (is (= 403 (:status resp)) "Bad token should be forbidden"))))

(deftest test-mqtt-topics-bad-token-program
  (testing "Bad token cannot get program topics"
    (let [prog-id (-> (client/find-program-by-name bl "Program1") :id)]
      (when prog-id
        (let [resp (client/get-mqtt-topics-program bad-token prog-id)]
          (is (= 403 (:status resp)) "Bad token should be forbidden"))))))

(deftest test-mqtt-topics-bad-token-program-events
  (testing "Bad token cannot get program events topics"
    (let [prog-id (-> (client/find-program-by-name bl "Program1") :id)]
      (when prog-id
        (let [resp (client/get-mqtt-topics-program-events bad-token prog-id)]
          (is (= 403 (:status resp)) "Bad token should be forbidden"))))))

(deftest test-mqtt-topics-bad-token-events
  (testing "Bad token cannot get events topics"
    (let [resp (client/get-mqtt-topics-events bad-token)]
      (is (= 403 (:status resp)) "Bad token should be forbidden"))))

(deftest test-mqtt-topics-bad-token-reports
  (testing "Bad token cannot get reports topics"
    (let [resp (client/get-mqtt-topics-reports bad-token)]
      (is (= 403 (:status resp)) "Bad token should be forbidden"))))

(deftest test-mqtt-topics-bad-token-subscriptions
  (testing "Bad token cannot get subscriptions topics"
    (let [resp (client/get-mqtt-topics-subscriptions bad-token)]
      (is (= 403 (:status resp)) "Bad token should be forbidden"))))

(deftest test-mqtt-topics-bad-token-vens
  (testing "Bad token cannot get vens topics"
    (let [resp (client/get-mqtt-topics-vens bad-token)]
      (is (= 403 (:status resp)) "Bad token should be forbidden"))))

(deftest test-mqtt-topics-bad-token-resources
  (testing "Bad token cannot get resources topics"
    (let [resp (client/get-mqtt-topics-resources bad-token)]
      (is (= 403 (:status resp)) "Bad token should be forbidden"))))

(deftest test-mqtt-topics-bad-token-ven
  (testing "Bad token cannot get ven topics"
    (let [ven-id (-> (client/get-vens bl) :body first :id)]
      (when ven-id
        (let [resp (client/get-mqtt-topics-ven bad-token ven-id)]
          (is (= 403 (:status resp)) "Bad token should be forbidden"))))))

(deftest test-mqtt-topics-bad-token-ven-resources
  (testing "Bad token cannot get ven resources topics"
    (let [ven-id (-> (client/get-vens bl) :body first :id)]
      (when ven-id
        (let [resp (client/get-mqtt-topics-ven-resources bad-token ven-id)]
          (is (= 403 (:status resp)) "Bad token should be forbidden"))))))
