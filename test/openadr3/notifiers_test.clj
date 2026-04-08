(ns openadr3.notifiers-test
  (:require [openadr3.client.base :as client]
            [openadr3.common-test :refer [ven1 expected-notifiers]]
            [clojure.test :refer :all]))

(deftest test-notifiers
  (testing "GET /notifiers"
    (let [resp (client/get-notifiers ven1)
          status (:status resp)
          body (:body resp)]
      (is (= 200 status) "Check for 200 status")
      (doseq [notifier-type expected-notifiers]
        (testing (str "expected notifier " notifier-type)
          (is (contains? body notifier-type)
              (str "Notifiers should include " notifier-type)))))))

(defn test-ns-hook []
  (test-notifiers))
