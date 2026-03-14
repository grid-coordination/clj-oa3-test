(ns openadr3.notifiers-test
  (:require [openadr3.client.base :as client]
            [openadr3.common-test :refer [ven1]]
            [clojure.test :refer :all]))

(deftest test-notifiers
  (testing "GET /notifiers"
    (let [resp (client/get-notifiers ven1)
          status (:status resp)
          body (:body resp)]
      (is (= 200 status) "Check for 200 status")
      (is (contains? body :WEBHOOK) "Check that notifiers includes WEBHOOK")
      (when (not= 1 (count body))
        (testing "More than one notifier supported, testing for MQTT"
          (is (contains? body :MQTT) "Check that MQTT is a supported notifier"))))))

(defn test-ns-hook []
  (test-notifiers))
