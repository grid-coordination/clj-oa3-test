(ns kaocha.plugin.test-report
  "Kaocha plugin: generate structured test reports after a test run.

  Walks the Kaocha result tree and produces:
    1. An EDN report (machine-readable, written to a file)
    2. A tabular summary (human-readable, printed to stdout)

  The tabular format is produced by formatting the EDN report — the EDN
  data is the single source of truth.

  Configuration in tests.edn:
    :kaocha.plugin.test-report/edn-file      path for EDN output (default: \"report/test-report.edn\")
    :kaocha.plugin.test-report/txt-file      path for tabular output (default: \"report/test-report.txt\")
    :kaocha.plugin.test-report/print-table?   print tabular summary to stdout (default: true)"
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.test :as t]
            [kaocha.plugin :refer [defplugin]]
            [kaocha.result :as result])
  (:import [java.time Instant]))

;; ---------------------------------------------------------------------------
;; Capturing `testing` context strings and failure details
;;
;; clojure.test's `testing` macro binds *testing-contexts* dynamically but
;; does NOT store it in reporter events or the Kaocha result tree. We capture
;; descriptions and failures via the pre-report hook into atoms, then merge
;; them into the report during post-run.
;; ---------------------------------------------------------------------------

(def ^:private test-descriptions
  "Map of testable-id (keyword from :kaocha.testable/id on :kaocha/testable)
  to the first `testing` description string seen for that test."
  (atom {}))

(def ^:private test-failures
  "Map of testable-id to vector of failure/error detail maps."
  (atom {}))

(defn- current-testable-id
  "Extract the testable id from a reporter event."
  [event]
  (get-in event [:kaocha/testable :kaocha.testable/id]))

(defn- capture-test-info
  "Pre-report hook: capture testing contexts and failure details from events."
  [event]
  (let [id (current-testable-id event)]
    (when id
      ;; Capture first testing context as description
      (when (and (#{:pass :fail :error :kaocha/fail-type} (:type event))
                 (not (contains? @test-descriptions id))
                 (seq t/*testing-contexts*))
        (swap! test-descriptions assoc id
               (str/join " > " (reverse t/*testing-contexts*))))

      ;; Capture failure/error details
      (when (#{:fail :error :kaocha/fail-type} (:type event))
        (let [detail (cond-> {:type (:type event)}
                       (:message event)  (assoc :message (:message event))
                       (:file event)     (assoc :file (:file event))
                       (:line event)     (assoc :line (:line event))
                       (:expected event) (assoc :expected (pr-str (:expected event)))
                       (:actual event)   (assoc :actual (pr-str (:actual event))))]
          (swap! test-failures update id (fnil conj []) detail)))))
  event)

;; ---------------------------------------------------------------------------
;; Result tree → EDN report
;; ---------------------------------------------------------------------------

(defn- test-result-keyword
  "Determine :pass, :fail, :error, or :pending for a leaf testable."
  [testable]
  (let [fail  (or (:kaocha.result/fail testable) 0)
        error (or (:kaocha.result/error testable) 0)
        pending (or (:kaocha.result/pending testable) 0)]
    (cond
      (pos? error)   :error
      (pos? fail)    :fail
      (pos? pending) :pending
      :else          :pass)))

(defn- extract-test
  "Convert a leaf testable into a test report map."
  [testable descriptions failures]
  (let [id       (:kaocha.testable/id testable)
        res      (test-result-keyword testable)
        desc     (get descriptions id)
        details  (get failures id)]
    (cond-> {:test/id     id
             :test/name   (name id)
             :test/desc   (or desc (name id))
             :test/result res}
      (seq details)
      (assoc :test/failures details)

      (:kaocha.testable/meta testable)
      (assoc :test/file (get-in testable [:kaocha.testable/meta :file])
             :test/line (get-in testable [:kaocha.testable/meta :line])))))

(defn- extract-suite
  "Convert a suite testable (with namespace children) into a suite report map."
  [suite descriptions failures]
  (let [suite-id (:kaocha.testable/id suite)
        tests    (->> (:kaocha.result/tests suite)
                      (mapcat :kaocha.result/tests)
                      (mapv #(extract-test % descriptions failures)))]
    {:suite/id      suite-id
     :suite/tests   tests
     :suite/summary {:total   (count tests)
                     :pass    (count (filter #(= :pass (:test/result %)) tests))
                     :fail    (count (filter #(= :fail (:test/result %)) tests))
                     :error   (count (filter #(= :error (:test/result %)) tests))
                     :pending (count (filter #(= :pending (:test/result %)) tests))}}))

(defn build-report
  "Build a complete EDN report from a Kaocha result tree.
  Filters out suites with no tests (not focused or not loaded)."
  [result descriptions failures]
  (let [suites  (->> (:kaocha.result/tests result)
                     (mapv #(extract-suite % descriptions failures))
                     (filterv #(pos? (get-in % [:suite/summary :total]))))
        summary (reduce (fn [acc s]
                          (merge-with + acc (:suite/summary s)))
                        {:total 0 :pass 0 :fail 0 :error 0 :pending 0}
                        suites)]
    {:report/timestamp (str (Instant/now))
     :report/summary   summary
     :report/suites    suites}))

;; ---------------------------------------------------------------------------
;; EDN file output
;; ---------------------------------------------------------------------------

(defn write-edn-report
  "Write the EDN report to a file, creating parent directories as needed."
  [report path]
  (let [f (io/file path)]
    (.mkdirs (.getParentFile f))
    (spit f (with-out-str (pp/pprint report)))))

;; ---------------------------------------------------------------------------
;; Tabular formatting (from EDN report)
;; ---------------------------------------------------------------------------

(defn- result-label [r]
  (case r
    :pass  "PASS"
    :fail  "FAIL"
    :error "ERROR"
    :pending "SKIP"
    (str r)))

(defn- pad-right [s width]
  (format (str "%-" width "s") (or s "")))

(defn- suite-header-line [{:keys [total pass fail error pending]}]
  (let [parts (cond-> [(str pass "/" total " passed")]
                (pos? fail)    (conj (str fail " failed"))
                (pos? error)   (conj (str error " errors"))
                (pos? pending) (conj (str pending " skipped")))]
    (str/join ", " parts)))

(defn- test-table
  "Render a single suite's test table as a string."
  [{:suite/keys [id tests summary]}]
  (if (empty? tests)
    (str "  Suite: " (name id) " (no tests)")
    (let [desc-width (max 20 (min 70 (apply max (map #(count (:test/desc %)) tests))))
          res-width  6
          border-h   (apply str (repeat (+ desc-width 2) "─"))
          border-r   (apply str (repeat (+ res-width 2) "─"))
          header     (str "  Suite: " (name id) " (" (suite-header-line summary) ")")
          top        (str "  ┌" border-h "┬" border-r "┐")
          head-row   (str "  │ " (pad-right "Test" desc-width) " │ " (pad-right "Result" res-width) " │")
          sep        (str "  ├" border-h "┼" border-r "┤")
          bottom     (str "  └" border-h "┴" border-r "┘")
          rows       (for [{:test/keys [desc result]} tests]
                       (str "  │ " (pad-right (subs desc 0 (min (count desc) desc-width)) desc-width)
                            " │ " (pad-right (result-label result) res-width) " │"))]
      (str/join "\n" (concat [header top head-row sep] rows [bottom])))))

(defn- failure-section
  "Render the FAILURES section from the full report."
  [report]
  (let [failures (for [{:suite/keys [id tests]} (:report/suites report)
                       {:test/keys [name desc result failures file line]} tests
                       :when (#{:fail :error} result)]
                   {:suite (clojure.core/name id) :name name :desc desc
                    :failures failures :file file :line line})]
    (when (seq failures)
      (str/join "\n"
                (concat
                 ["" "  FAILURES:"]
                 (for [{:keys [suite name desc file line failures]} failures]
                   (str/join "\n"
                             (concat
                              [(str "    " suite " / " name)
                               (str "      \"" desc "\"")]
                              (for [{:keys [message expected actual]} failures]
                                (str/join "\n"
                                          (cond-> []
                                            message  (conj (str "      " message))
                                            expected (conj (str "      expected: " expected))
                                            actual   (conj (str "      actual:   " actual)))))
                              [(str "      at " file ":" line)]))))))))

(defn format-table
  "Format an EDN report as a human-readable tabular string."
  [report]
  (let [{:report/keys [timestamp summary suites]} report
        width   72
        border  (apply str (repeat width "═"))
        thin    (apply str (repeat width "─"))
        title   (str "  OpenADR3 Test Report — " timestamp)
        tables  (str/join "\n\n" (map test-table suites))
        fails   (failure-section report)
        {:keys [total pass fail error]} summary
        sum-line (str "  Summary: " total " tests, " pass " passed"
                      (when (pos? fail) (str ", " fail " failed"))
                      (when (pos? error) (str ", " error " errors")))]
    (str/join "\n"
              (cond-> ["" border title border "" tables]
                fails (conj (str "\n  " thin) fails (str "  " thin))
                true  (conj "" sum-line border "")))))

;; ---------------------------------------------------------------------------
;; Plugin
;; ---------------------------------------------------------------------------

(defplugin kaocha.plugin/test-report
  (pre-report [event]
              (capture-test-info event))

  (post-run [result]
            (let [edn-file     (get result :kaocha.plugin.test-report/edn-file
                                    "report/test-report.edn")
                  txt-file     (get result :kaocha.plugin.test-report/txt-file
                                    "report/test-report.txt")
                  print-table? (get result :kaocha.plugin.test-report/print-table? true)
                  descriptions @test-descriptions
                  failures     @test-failures
                  report       (build-report result descriptions failures)
                  table-str    (format-table report)]
              (write-edn-report report edn-file)
              (spit txt-file table-str)
              (when print-table?
                (println table-str))
              ;; Reset atoms for next run
              (reset! test-descriptions {})
              (reset! test-failures {})
              result)))
