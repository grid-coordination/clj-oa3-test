(ns openadr3.capabilities
  "Capability profile for the VTN under test.

  A capability profile describes the *effective capabilities* of the running
  deployment — what the VTN actually exposes, given its configuration. It is
  built by merging four sources in priority order:

    1. :declared      — set explicitly in test-config.edn under :capabilities
    2. :advertised    — what the VTN tells us via GET /notifiers
    3. :auto-detected — what we observe by probing handler endpoints
    4. :defaulted     — fallback assumptions

  The merged result is a map of capability paths → values, alongside a
  parallel map of paths → source keywords (so the report can show where each
  fact came from).

  Schema (each top-level key is optional; absent ⇒ defaulted):

    {:transport         {:single-port? bool
                         :bl-port      int   ; nil if not exposed
                         :ven-port     int}  ; nil if not exposed

     :http-auth         {:method     keyword  ; :none | :basic | :bearer-jwt | :oauth2 | :mtls
                         :enforced?  bool
                         :on-bl?     bool
                         :on-ven?    bool}

     :handlers          #{keyword}            ; intrinsic resource handlers
     :handlers-bl       #{keyword}            ; subset reachable on BL port
     :handlers-ven      #{keyword}            ; subset reachable on VEN port

     :ven-routes        {keyword keyword}     ; per-resource :full | :read-only | false

     :notifiers         #{keyword}            ; #{:WEBHOOK :MQTT}
     :mqtt-auth         {:method keyword}     ; :anonymous | :dynsec | :oauth2-bearer | :certificate
     :webhook-delivery? bool
     :discovery         #{keyword}}           ; #{:static-url :mdns}

  Phase 1 (this namespace's current scope): build the merged profile and
  expose it. Tests don't yet consume it — the existing :ven-routes,
  :auth-enforced?, and :expected-notifiers knobs in test-config.edn still
  drive test behavior. Phase 3 migrates them under :capabilities."
  (:require [clojure.string :as str]
            [openadr3.client.base :as base]))

;; ---------------------------------------------------------------------------
;; Defaults
;; ---------------------------------------------------------------------------

(def ^:private defaults
  "Conservative defaults for any capability not declared, advertised, or
  detected. Reflects 'we know nothing — assume the minimum'."
  {:transport         {:single-port? true}
   :http-auth         {:method :none :enforced? false :on-bl? false :on-ven? false}
   :handlers          #{}
   :handlers-bl       #{}
   :handlers-ven      #{}
   :ven-routes        {}
   :notifiers         #{}
   :mqtt-auth         {:method :anonymous}
   :webhook-delivery? false
   :discovery         #{:static-url}})

;; ---------------------------------------------------------------------------
;; Source 1: declared (test-config.edn :capabilities)
;; ---------------------------------------------------------------------------

(defn from-declared
  "Pull the :capabilities map from test-config.edn. Returns {} if absent."
  [config]
  (get config :capabilities {}))

;; ---------------------------------------------------------------------------
;; Source 2: advertised (GET /notifiers)
;; ---------------------------------------------------------------------------

(defn- notifier-keys
  "Map a /notifiers response body into a set of advertised notifier types
  ({:WEBHOOK :MQTT} subset). The body shape is
    {:WEBHOOK true|false ...
     :MQTT    nil|{...}}"
  [body]
  (cond-> #{}
    (true? (:WEBHOOK body))            (conj :WEBHOOK)
    (some? (not-empty (:MQTT body)))   (conj :MQTT)))

(defn- mqtt-auth-method
  "Extract MQTT auth method from /notifiers MQTT.authentication.method.
  Maps the spec strings to capability keywords."
  [body]
  (when-let [m (-> body :MQTT :authentication :method)]
    (case m
      "ANONYMOUS"           :anonymous
      "OAUTH2_BEARER_TOKEN" :oauth2-bearer
      "CERTIFICATE"         :certificate
      "USERNAME_PASSWORD"   :dynsec  ; per the dynsec convention
      (keyword (str/lower-case m)))))

(defn from-advertised
  "Probe GET /notifiers via the BL client. Returns a (partial) capability map
  populated from what the VTN advertises, or {} on failure."
  [bl-client]
  (try
    (let [body (-> (base/get-notifiers bl-client) :body)]
      (cond-> {}
        (some? body)
        (assoc :notifiers (notifier-keys body))

        (some? (mqtt-auth-method body))
        (assoc :mqtt-auth {:method (mqtt-auth-method body)})))
    (catch Exception _
      {})))

;; ---------------------------------------------------------------------------
;; Source 3: auto-detected (HTTP probes)
;; ---------------------------------------------------------------------------

(def ^:private handler-probes
  "For each handler keyword, the client function to call as a probe and an
  optional argument map. The probe is a list/search GET; we only care
  whether the route is 404 (absent) or anything else (present)."
  {:programs      #(base/get-programs %)
   :events        #(base/get-events %)
   :subscriptions #(base/get-subscriptions %)
   :vens          #(base/get-vens %)
   :reports       #(base/get-reports %)
   :resources     #(base/search-ven-resources %
                                              {:venID "00000000-0000-0000-0000-000000000000"})})

(defn- probe-handler
  "Call the probe for `handler` against `client`. Returns :present (route
  exists) or :absent (404) or :unknown (probe threw / 5xx). Auth-failure
  responses (401/403) count as :present — the route exists, we just aren't
  authorized."
  [client handler]
  (let [probe (get handler-probes handler)]
    (try
      (let [status (:status (probe client))]
        (cond
          (= 404 status)          :absent
          (and (number? status)
               (>= status 500))   :unknown
          :else                   :present))
      (catch Exception _
        :unknown))))

(defn- probe-port
  "Run all handler probes against one client. Returns a set of handlers that
  responded :present."
  [client]
  (when client
    (into #{}
          (keep (fn [h]
                  (when (= :present (probe-handler client h))
                    h)))
          (keys handler-probes))))

(defn from-probed
  "Probe the running VTN via the BL and VEN clients. Returns a (partial)
  capability map populated from what we observe. Requires that the clients
  be authenticated (use the configured tokens) — auth-failure responses
  still count as 'route present'."
  [{:keys [bl-client ven-client bad-token-client]}]
  (let [bl-handlers  (probe-port bl-client)
        ven-handlers (probe-port ven-client)
        ;; auth enforcement: a request with the bad-token client should
        ;; come back 401/403 if the VTN enforces auth on that port.
        unauth-bl    (when bad-token-client
                       (try (-> (base/get-programs bad-token-client) :status)
                            (catch Exception _ nil)))
        auth-enforced? (when (number? unauth-bl)
                         (#{401 403} unauth-bl))]
    (cond-> {:handlers     (into bl-handlers ven-handlers)
             :handlers-bl  bl-handlers
             :handlers-ven ven-handlers}
      (some? auth-enforced?)
      (assoc-in [:http-auth :enforced?] auth-enforced?))))

;; ---------------------------------------------------------------------------
;; Merge with source tracking
;; ---------------------------------------------------------------------------

(defn- deep-merge-with-sources
  "Merge layered maps top-down (lower priority first). For each leaf path
  taken, record where it came from. Layers is a vector of [source-keyword
  partial-map] pairs in priority order (lowest first; highest wins)."
  [layers]
  (loop [merged  {}
         sources {}
         remain  layers]
    (if-let [[src layer] (first remain)]
      (let [paths (for [[k v] layer
                        :let [sub-paths (cond
                                          (and (map? v)
                                               (not (record? v)))
                                          (mapv (fn [k2] [[k k2] (get v k2)]) (keys v))

                                          :else
                                          [[[k] v]])]
                        sp sub-paths]
                    sp)]
        (recur (reduce (fn [m [path val]] (assoc-in m path val)) merged paths)
               (reduce (fn [m [path _]] (assoc m path src)) sources paths)
               (rest remain)))
      {:capabilities merged :sources sources})))

(defn build-profile
  "Build the merged capability profile from the four sources. Returns:
    {:capabilities <merged map>
     :sources      <map of [path...] → source-keyword>}

  `clients` is a map of {:bl-client … :ven-client … :bad-token-client …}.
  Probing is best-effort — if the VTN is unreachable, only :declared and
  :defaulted layers contribute."
  [config clients]
  (deep-merge-with-sources
   [[:defaulted    defaults]
    [:auto-detected (from-probed clients)]
    [:advertised   (from-advertised (:bl-client clients))]
    [:declared     (from-declared config)]]))
