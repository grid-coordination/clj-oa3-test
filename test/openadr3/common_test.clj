(ns openadr3.common-test
  (:require [com.stuartsierra.component :as component]
            [openadr3.client :as client]))

(def VTN-url "http://localhost:8080/openadr3/3.1.0")

(def ven1
  (component/start
   (client/oa3-client {:type :ven :url VTN-url :token "ven_token"})))

(def ven2
  (component/start
   (client/oa3-client {:type :ven :url VTN-url :token "ven_token2"})))

(def bl
  (component/start
   (client/oa3-client {:type :bl :url VTN-url :token "bl_token"})))

;; Shared state for cross-namespace test data (e.g. VEN IDs created in vens_test, used in topics_test)
(defonce test-state (atom {}))
