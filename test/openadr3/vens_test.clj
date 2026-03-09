(ns openadr3.vens-test
  (:require [openadr3.client :as client]
            [openadr3.common-test :refer [ven1 ven2 bl test-state]]
            [clojure.test :refer :all]))

(def ven1-request {:objectType "VEN_VEN_REQUEST" :venName "ven1"})
(def ven2-request {:objectType "VEN_VEN_REQUEST" :venName "ven2"})
(def test-ven-requests [ven1-request ven2-request])

(defn delete-ven-by-name [c ven-name]
  (let [{ven-id :id} (client/find-ven-by-name c ven-name)]
    (when ven-id
      (client/delete-ven c ven-id))))

(defn delete-test-vens [c]
  (doseq [{ven-name :venName} test-ven-requests]
    (delete-ven-by-name c ven-name)))

(use-fixtures :once
  (fn [f]
    (delete-test-vens bl)
    (f)))

(deftest test-create-ven1
  (testing "Create ven1"
    (let [resp (client/create-ven ven1 ven1-request)
          status (:status resp)
          ven-id (-> resp :body :id)]
      (is (<= status 299) "Check for 2xx status")
      (swap! test-state assoc :ven1-id ven-id))))

(deftest test-create-ven2
  (testing "Create ven2"
    (let [resp (client/create-ven ven2 ven2-request)
          status (:status resp)
          ven-id (-> resp :body :id)]
      (is (<= status 299) "Check for 2xx status")
      (swap! test-state assoc :ven2-id ven-id))))
