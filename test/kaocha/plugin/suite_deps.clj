(ns kaocha.plugin.suite-deps
  "Kaocha plugin: auto-include prerequisite suites when using --focus.

  Suites in tests.edn can declare dependencies via :suite-deps/requires:

    {:id :mqtt
     :suite-deps/requires [:programs :vens]
     ...}

  When you run `clojure -M:test --focus :mqtt`, this plugin computes
  the transitive dependency graph and expands the focus list to include
  :programs and :vens so they run first (in their declared order)."
  (:require [clojure.set :as set]
            [kaocha.plugin :refer [defplugin]]))

(defn- suite-dep-map
  "Build a map of suite-id → set of direct dependencies from config."
  [config]
  (into {}
        (for [suite (:kaocha/tests config)
              :let [id   (:kaocha.testable/id suite)
                    deps (:suite-deps/requires suite)]
              :when deps]
          [id (set deps)])))

(defn- transitive-deps
  "Compute transitive closure of dependencies for a set of suite ids."
  [dep-map ids]
  (loop [result #{}
         queue  (vec ids)]
    (if (empty? queue)
      result
      (let [id   (first queue)
            rest (subvec queue 1)]
        (if (result id)
          (recur result rest)
          (let [deps (get dep-map id #{})]
            (recur (conj result id)
                   (into rest deps))))))))

(def ^:private printed? (atom false))

(defplugin kaocha.plugin/suite-deps
  (config [config]
          (let [focus (:kaocha.filter/focus config)]
            (if (empty? focus)
              config
              (let [focused-ids (set focus)
                    dep-map     (suite-dep-map config)
                    required    (transitive-deps dep-map focused-ids)
                    to-add      (set/difference required focused-ids)]
                (if (empty? to-add)
                  config
                  (let [new-focus (into (vec focus) (sort to-add))]
                    (when (compare-and-set! printed? false true)
                      (println (str "[suite-deps] --focus " (vec focus)
                                    " → also running prerequisites: " (vec (sort to-add)))))
                    (assoc config :kaocha.filter/focus new-focus))))))))
