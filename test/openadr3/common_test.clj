(ns openadr3.common-test
  (:require [com.stuartsierra.component :as component]
            [openadr3.client :as client]))

(def VTN-url "http://localhost:8080/openadr3/3.1.0")
(def MQTT-broker-url "tcp://127.0.0.1:1883")

(def ven1
  (component/start
   (client/oa3-client {:type :ven :url VTN-url :token "ven_token"})))

(def ven2
  (component/start
   (client/oa3-client {:type :ven :url VTN-url :token "ven_token2"})))

(def bl
  (component/start
   (client/oa3-client {:type :bl :url VTN-url :token "bl_token"})))
