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

(def BL-url
  "URL for BL (write) clients. Falls back to :vtn-url for single-port VTNs."
  (get config :bl-url VTN-url))

(def VEN-url
  "URL for VEN (read) clients. Falls back to :vtn-url for single-port VTNs."
  (get config :ven-url VTN-url))

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
   (ven/ven-client {:url VEN-url :token (:ven1 tokens)
                    :user-agent "clj-oa3-test (ven1)"})))

(def ven2
  (component/start
   (ven/ven-client {:url VEN-url :token (:ven2 tokens)
                    :user-agent "clj-oa3-test (ven2)"})))

(def bl
  (component/start
   (bl/bl-client {:url BL-url :token (:bl tokens)
                  :user-agent "clj-oa3-test (bl)"})))

(def bad-token
  (component/start
   (bl/bl-client {:url VEN-url :token (:bad tokens)
                  :user-agent "clj-oa3-test (bad-token)"})))

;; ---------------------------------------------------------------------------
;; Expected notifiers — configurable per VTN
;; ---------------------------------------------------------------------------

(def expected-notifiers
  "Set of notifier types the VTN is expected to advertise.
  Defaults to #{:WEBHOOK :MQTT} per the OA3 spec."
  (get config :expected-notifiers #{:WEBHOOK :MQTT}))

;; ---------------------------------------------------------------------------
;; VEN port route enablement — configurable per VTN
;; ---------------------------------------------------------------------------

(def ven-routes
  "VEN port route enablement map. Keys are resource types, values are
  :full, :read-only, or false (disabled). Programs and events default
  to :read-only; everything else defaults to false (disabled).
  Configure in test-config.edn via :ven-routes."
  (merge {:programs      :read-only
          :events        :read-only
          :subscriptions false
          :vens          false
          :resources     false
          :reports       false}
         (get config :ven-routes {})))

(defn ven-route-enabled?
  "True if the given resource route is enabled on the VEN port.
  Returns the enablement level (:full, :read-only) or false."
  [resource]
  (get ven-routes resource false))

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
