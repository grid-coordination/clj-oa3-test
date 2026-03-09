(ns openadr3.programs-test
  (:require [openadr3.client :as client]
            [openadr3.common-test :refer [bl]]
            [clojure.test :refer :all]))

(def program1-request {:programName "Program1"})
(def program2-request {:programName "Program2"})
(def test-program-requests [program1-request program2-request])

(defn delete-program-by-name [c program-name]
  (let [{program-id :id} (client/find-program-by-name c program-name)]
    (when program-id
      (client/delete-program c program-id))))

(defn delete-test-programs [c]
  (doseq [{program-name :programName} test-program-requests]
    (delete-program-by-name c program-name)))

(use-fixtures :once
  (fn [f]
    (delete-test-programs bl)
    (f)))

(deftest test-create-program1
  (testing "Create program1"
    (let [resp (client/create-program bl program1-request)]
      (is (<= (:status resp) 299) "Check for 2xx status"))))

(deftest test-create-program2
  (testing "Create program2"
    (let [resp (client/create-program bl program2-request)]
      (is (<= (:status resp) 299) "Check for 2xx status"))))
