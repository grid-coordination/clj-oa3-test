(ns openadr3.vens-test
  (:require [openadr3.client :as client]
            [openadr3.common-test :refer [ven1 ven2 bl]]
            [clojure.test :refer :all]))

(def test-ven-names ["ven1" "ven2"])

(defn delete-ven-by-name [c ven-name]
  (let [{ven-id :id} (client/find-ven-by-name c ven-name)]
    (when ven-id
      (client/delete-ven c ven-id))))

(defn delete-test-vens [c]
  (doseq [ven-name test-ven-names]
    (delete-ven-by-name c ven-name)))

(use-fixtures :once
  (fn [f]
    (delete-test-vens bl)
    (f)))

(deftest test-register-ven1
  (testing "Register ven1"
    (client/register! ven1 "ven1")
    (is (some? (client/ven-id ven1)) "ven1 should have a ven-id after registration")))

(deftest test-register-ven2
  (testing "Register ven2"
    (client/register! ven2 "ven2")
    (is (some? (client/ven-id ven2)) "ven2 should have a ven-id after registration")))
