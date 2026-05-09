(ns kaocha.plugin.capability-gate
  "Kaocha plugin: skip tests whose :requires capability spec isn't met by
  the merged capability profile.

  Phase 2 of OA3T-80j. Companion to kaocha.plugin/auth-gate. Where auth-gate
  handles a single boolean knob (auth-enforced?), this plugin handles
  arbitrary capability requirements declared via metadata.

  Two ways to declare requirements:

    1. Per-test metadata (Clojure metadata on a deftest):
         (deftest ^{:requires {:handlers #{:reports}}}
           test-create-report-ven
           ...)

    2. Per-suite via :capabilities/requires in tests.edn:
         {:id :reports
          :capabilities/requires {:handlers #{:reports}}
          :ns-patterns [\"reports-test$\"]}

  Matching semantics — a capability requirement is satisfied when:
    - sets:    required ⊆ profile-set
    - maps:    every entry recursively matches the profile sub-map
    - other:   equal to the profile value

  Unsatisfied requirements cause the testable to be marked with
  :kaocha.testable/skip true and :capabilities/skip-reason <string>. The
  test-report plugin renders these as SKIP with the reason."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [kaocha.plugin :refer [defplugin]]
            [kaocha.testable :as testable]))

;; ---------------------------------------------------------------------------
;; Capability profile lookup — loaded lazily so the plugin doesn't force the
;; common-test namespace (and its HTTP probes) until tests are actually being
;; loaded.
;; ---------------------------------------------------------------------------

(defn- load-profile
  "Resolve the merged capability profile from openadr3.common-test. Returns
  nil if the namespace can't be loaded (e.g. dev REPL with no VTN running)."
  []
  (try
    (require 'openadr3.common-test)
    @(resolve 'openadr3.common-test/capabilities)
    (catch Exception _ nil)))

;; ---------------------------------------------------------------------------
;; Predicate: does the profile satisfy a requirements spec?
;; ---------------------------------------------------------------------------

(defn meets-spec?
  "True if `actual` (a value from the capability profile) satisfies `required`."
  [actual required]
  (cond
    (set? required)
    (and (set? actual) (set/subset? required actual))

    (map? required)
    (every? (fn [[k v]] (meets-spec? (get actual k) v)) required)

    :else
    (= required actual)))

(defn unmet-requirements
  "Return a vector of [path required actual] entries for any requirement key
  in `reqs` that the `profile` doesn't satisfy. Empty vector means all met."
  [profile reqs]
  (reduce-kv (fn [acc k req-v]
               (let [actual (get profile k)]
                 (if (meets-spec? actual req-v)
                   acc
                   (conj acc [k req-v actual]))))
             []
             reqs))

;; ---------------------------------------------------------------------------
;; Reason formatting
;; ---------------------------------------------------------------------------

(defn- ks [s] (str/join ", " (sort (map name s))))

(defn- format-one
  "Human-readable reason for one unmet requirement entry."
  [[k required actual]]
  (case k
    :handlers     (let [missing (set/difference required (or actual #{}))]
                    (str "VTN doesn't expose handler(s): " (ks missing)))
    :handlers-bl  (let [missing (set/difference required (or actual #{}))]
                    (str "BL port doesn't expose handler(s): " (ks missing)))
    :handlers-ven (let [missing (set/difference required (or actual #{}))]
                    (str "VEN port doesn't expose handler(s): " (ks missing)))
    :notifiers    (let [missing (set/difference required (or actual #{}))]
                    (str "VTN doesn't advertise notifier(s): " (ks missing)))
    :http-auth    (str "VTN HTTP auth requirement " (pr-str required)
                       " not met (actual: " (pr-str actual) ")")
    :mqtt-auth    (str "VTN MQTT auth requirement " (pr-str required)
                       " not met (actual: " (pr-str actual) ")")
    (str (name k) " requires " (pr-str required)
         "; actual: " (pr-str actual))))

(defn format-reason
  "Compose a single reason string for a list of unmet entries."
  [unmet]
  (str/join "; " (map format-one unmet)))

;; ---------------------------------------------------------------------------
;; Testable walk
;; ---------------------------------------------------------------------------

(defn- requires-of
  "Pull a :requires spec from a testable. Looks at:
    - :capabilities/requires (suite-level, set in tests.edn)
    - :kaocha.testable/meta :requires (per-test, from deftest metadata)
  Returns nil if neither is set."
  [testable]
  (or (:capabilities/requires testable)
      (get-in testable [:kaocha.testable/meta :requires])))

(defn- gate-testable
  "If the testable declares :requires that the profile doesn't satisfy, mark
  it skipped with a reason. Otherwise leave it as-is. Skips that are already
  set by something else (e.g. focus filters) pass through."
  [profile testable]
  (if (::testable/skip testable)
    testable
    (if-let [reqs (requires-of testable)]
      (let [unmet (unmet-requirements profile reqs)]
        (if (seq unmet)
          (assoc testable
                 ::testable/skip true
                 :capabilities/skip-reason (format-reason unmet))
          testable))
      testable)))

(defn- walk-test-plan
  "Walk every testable in the plan, applying f to each. Recurses through
  :kaocha.test-plan/tests. When a node is gated (gets a
  :capabilities/skip-reason), the reason is propagated to all its descendants
  that don't have their own reason — so the test-report plugin's leaf-level
  rendering can render SKIP-with-reason on every gated testable, not just
  the suite that triggered the gate."
  [test-plan f]
  (let [walk (fn walk [t inherited]
               (let [t1     (f t)
                     t2     (if (and inherited (not (:capabilities/skip-reason t1)))
                              (-> t1
                                  (assoc ::testable/skip true)
                                  (assoc :capabilities/skip-reason inherited))
                              t1)
                     reason (:capabilities/skip-reason t2)]
                 (cond-> t2
                   (:kaocha.test-plan/tests t2)
                   (update :kaocha.test-plan/tests
                           (partial mapv #(walk % reason))))))]
    (update test-plan
            :kaocha.test-plan/tests
            (partial mapv #(walk % nil)))))

;; ---------------------------------------------------------------------------
;; Plugin
;; ---------------------------------------------------------------------------

(def ^:private printed? (atom false))

(defplugin kaocha.plugin/capability-gate
  (post-load [test-plan]
             (let [profile (load-profile)]
               (if-not profile
                 test-plan
                 (let [gated  (walk-test-plan test-plan #(gate-testable profile %))
                       skipped (->> (testable/test-seq gated)
                                    (filter :capabilities/skip-reason)
                                    count)]
                   (when (and (pos? skipped) (compare-and-set! printed? false true))
                     (println (str "[capability-gate] " skipped
                                   " test(s) skipped — VTN capability profile didn't meet requirements")))
                   gated)))))
