(ns openadr3.programs-test
  (:require [openadr3.api :as api]
            [openadr3.common-test :refer [bl]]
            [clojure.test :refer :all]))

(def ^:dynamic bl-client)
(def ^:dynamic bl-client-var)
(def ^:dynamic program-request)

(def program1-request {:programName "Program1"})
(def program2-request {:programName "Program2"})
(def test-program-requests [program1-request program2-request])

(defn delete-program-by-name [openapi-bl program-name]
  (let [{program-id :id} (api/find-program-by-name openapi-bl program-name)]
    (when program-id
      (api/delete-program openapi-bl program-id))))

(defn delete-test-programs [openapi-bl]
  (doseq [{program-name :programName} test-program-requests]
    (delete-program-by-name openapi-bl program-name)))

(deftest test-create-program
  (testing "Create a program"
    (let [resp (api/create-program bl-client program-request)
          status (:status resp)]
      (is (<= status 299) "Check for 2xx status"))))

(deftest test-create-program1
  (testing "Create program1"
    (with-redefs [bl-client bl
                  bl-client-var #'bl
                  program-request program1-request]
      (test-create-program))))

(deftest test-create-program2
  (testing "Create program2"
    (with-redefs [bl-client bl
                  bl-client-var #'bl
                  program-request program2-request]
      (test-create-program))))

(deftest test-programs
  (testing "Combined programs tests"
    (test-create-program1)
    (test-create-program2)))

(defn test-ns-hook []
  (delete-test-programs bl)
  (test-programs))
