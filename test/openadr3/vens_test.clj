(ns openadr3.vens-test
  (:require [openadr3.api :as api]
            [openadr3.common-test :refer [ven1 ven2 bl]]
            [clojure.test :refer :all]))

(def ^:dynamic client)
(def ^:dynamic client-var)
(def ^:dynamic ven-request)

(def ven1-request {:objectType "VEN_VEN_REQUEST" :venName "ven1"})
(def ven2-request {:objectType "VEN_VEN_REQUEST" :venName "ven2"})
(def test-ven-requests [ven1-request ven2-request])

(defn delete-ven-by-name [openapi-bl ven-name]
  (let [{ven-id :id} (api/find-ven-by-name openapi-bl ven-name)]
    (when ven-id
      (api/delete-ven openapi-bl ven-id))))

(defn delete-test-vens [openapi-bl]
  (doseq [{ven-name :venName} test-ven-requests]
    (delete-ven-by-name openapi-bl ven-name)))

(deftest test-create-ven
  (testing "Create ven"
    (let [resp (api/create-ven client ven-request)
          status (:status resp)
          body (:body resp)
          ven-id (:id body)]
      (is (<= status 299) "Check for 2xx status")
      (alter-meta! client-var assoc :ven-id ven-id))))

(deftest test-create-ven1
  (testing "Create ven1"
    (with-redefs [client ven1
                  client-var #'ven1
                  ven-request ven1-request]
      (test-create-ven))))

(deftest test-create-ven2
  (testing "Create ven2"
    (with-redefs [client ven2
                  client-var #'ven2
                  ven-request ven2-request]
      (test-create-ven))))

(deftest test-vens
  (testing "Combined vens tests"
    (test-create-ven1)
    (test-create-ven2)))

(defn test-ns-hook []
  (delete-test-vens bl)
  (test-vens))
