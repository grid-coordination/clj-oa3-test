(ns kaocha.plugin.auth-gate
  "Kaocha plugin: skip ^:auth-tagged tests when the VTN under test does not
  enforce auth, driven by :auth-enforced? in test-config.edn.

  Some VTNs (e.g. clj-oa3-vtn 0.12.1, VTN-RI with AUTH__DISABLED=true) do
  not check tokens. Running auth-bad-token tests against them produces
  spurious failures that say nothing about the VTN's correctness.

  Set :auth-enforced? false in test-config.edn and this plugin appends
  :auth to :kaocha.filter/skip-meta — equivalent to passing
  --skip-meta :auth on every invocation, but visible in the test report
  and surviving REPL / kaocha.repl/run workflows."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [kaocha.plugin :refer [defplugin]]))

(def ^:private warned-legacy? (atom false))

(defn- auth-enforced?
  "Read auth-enforcement state from test-config.edn. Prefers the canonical
  :capabilities {:http-auth {:enforced? ...}} shape; falls back to the
  legacy :auth-enforced? top-level key (with a one-time deprecation
  warning). Defaults to true — for unknown VTNs we want auth tests to run."
  []
  (let [f (io/file "test-config.edn")]
    (if-not (.exists f)
      true
      (let [config       (edn/read-string (slurp f))
            new-value    (get-in config [:capabilities :http-auth :enforced?])
            legacy-value (get config :auth-enforced?)]
        (cond
          (some? new-value) new-value

          (some? legacy-value)
          (do (when (compare-and-set! warned-legacy? false true)
                (println (str "[auth-gate] DEPRECATED: :auth-enforced? — "
                              "migrate to :capabilities {:http-auth {:enforced? ...}}")))
              legacy-value)

          :else true)))))

(def ^:private printed? (atom false))

(defplugin kaocha.plugin/auth-gate
  (config [config]
          (if (auth-enforced?)
            config
            (let [existing (set (:kaocha.filter/skip-meta config))]
              (when (compare-and-set! printed? false true)
                (println "[auth-gate] :auth-enforced? false — skipping ^:auth-tagged tests"))
              (assoc config :kaocha.filter/skip-meta
                     (vec (conj existing :auth)))))))
