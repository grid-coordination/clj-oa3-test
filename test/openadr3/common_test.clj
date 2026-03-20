(ns openadr3.common-test
  (:require [com.stuartsierra.component :as component]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [openadr3.client.base :as base]
            [openadr3.client.ven :as ven]
            [openadr3.client.bl :as bl]))

;; ---------------------------------------------------------------------------
;; Configuration — loaded from test-config.edn
;; ---------------------------------------------------------------------------

(def ^:private config
  (let [f (io/file "test-config.edn")]
    (if (.exists f)
      (edn/read-string (slurp f))
      (do (println "WARNING: test-config.edn not found, using defaults."
                   "Copy test-config.example.edn to test-config.edn.")
          {}))))

(def VTN-url
  (get config :vtn-url "http://localhost:8080/openadr3/3.1.0"))

(def inter-suite-delay-ms
  "Delay in ms between test suites.
  Set to 0 for fast VTNs, 1000-5000 if you see connection errors."
  (get config :inter-suite-delay-ms 1000))

;; ---------------------------------------------------------------------------
;; Tokens
;; ---------------------------------------------------------------------------

(def ^:private tokens
  (get config :tokens {:ven1 "ven_token"
                       :ven2 "ven_token2"
                       :bl   "bl_token"
                       :bad  "bad_token"}))

;; ---------------------------------------------------------------------------
;; Clients
;; ---------------------------------------------------------------------------

(def ven1
  (component/start
   (ven/ven-client {:url VTN-url :token (:ven1 tokens)})))

(def ven2
  (component/start
   (ven/ven-client {:url VTN-url :token (:ven2 tokens)})))

(def bl
  (component/start
   (bl/bl-client {:url VTN-url :token (:bl tokens)})))

(def bad-token
  (component/start
   (bl/bl-client {:url VTN-url :token (:bad tokens)})))

;; ---------------------------------------------------------------------------
;; MQTT timing
;; ---------------------------------------------------------------------------

(def mqtt-settle-ms
  "Delay in ms after clearing MQTT message buffer before acting.
  Allows retained messages from prior operations to drain."
  (get config :mqtt-settle-ms 1000))

(def mqtt-await-ms
  "Default timeout in ms for awaiting MQTT channel messages."
  (get config :mqtt-await-ms 10000))

;; ---------------------------------------------------------------------------
;; MQTT broker URLs — discovered from VTN via GET /notifiers
;; ---------------------------------------------------------------------------

(def MQTT-broker-urls
  "Vector of MQTT broker URIs discovered from the VTN's notifiers endpoint.
  Returns nil if the VTN does not advertise MQTT support."
  (let [resp      (base/get-notifiers bl)
        mqtt-info (-> resp :body :MQTT)
        uris      (:URIS mqtt-info)]
    (when (seq uris)
      (vec uris))))

(def mqtt-available?
  "True if the VTN advertises MQTT broker URIs via GET /notifiers."
  (boolean (seq MQTT-broker-urls)))

(def MQTT-broker-url
  "Primary MQTT broker URL (first from the discovered list), or nil."
  (first MQTT-broker-urls))

;; ---------------------------------------------------------------------------
;; MQTT credentials — discovered from VTN via GET /notifiers
;; ---------------------------------------------------------------------------

(defn mqtt-credentials
  "Fetch MQTT credentials for a client from GET /notifiers.
  Returns opts map with :username/:password when available, empty map otherwise."
  [c]
  (let [auth (get-in (base/get-notifiers c) [:body :MQTT :authentication])]
    (if (and auth (not= "ANONYMOUS" (:method auth)) (:username auth) (:password auth))
      (select-keys auth [:username :password])
      {})))
