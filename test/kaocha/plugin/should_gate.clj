(ns kaocha.plugin.should-gate
  "Kaocha plugin: skip ^:should-tagged tests unless :should-enforced? is true.

  Tests tagged ^:should assert SHOULD-level OA3 spec conformance — things
  the spec recommends but doesn't require. The Problem-object error-response
  body shape (RFC 7807: type/title/status/detail/instance) is the canonical
  example.

  By default these tests are SKIPPED — a VTN that returns 404 with no body
  is technically spec-compliant (just missing the recommendation), and
  default reports shouldn't surface that as a failure. VTNs aiming for
  full conformance can flip the knob and see how they fare.

  Toggle via test-config.edn:
    :capabilities {:should-enforced? true}

  Parallel design to auth-gate, but inverted default — auth-gate's default
  is ENFORCED (true), should-gate's default is NOT-ENFORCED (false)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [kaocha.plugin :refer [defplugin]]))

(def ^:private warned-legacy? (atom false))

(defn- should-enforced?
  "Read should-enforcement state from test-config.edn. Prefers
  :capabilities {:should-enforced? ...}; no legacy fallback (the knob is
  new). Defaults to false — opt-in only."
  []
  (let [f (io/file "test-config.edn")]
    (if-not (.exists f)
      false
      (let [config (edn/read-string (slurp f))]
        (when (and (contains? config :should-enforced?)
                   (compare-and-set! warned-legacy? false true))
          (println (str "[should-gate] note: top-level :should-enforced? is not used; "
                        "set :capabilities {:should-enforced? ...} instead")))
        (get-in config [:capabilities :should-enforced?] false)))))

(def ^:private printed? (atom false))

(defplugin kaocha.plugin/should-gate
  (config [config]
          (if (should-enforced?)
            (do (when (compare-and-set! printed? false true)
                  (println "[should-gate] :should-enforced? true — running ^:should-tagged tests"))
                config)
            (let [existing (set (:kaocha.filter/skip-meta config))]
              (assoc config :kaocha.filter/skip-meta
                     (vec (conj existing :should)))))))
