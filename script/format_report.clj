(ns format-report
  "Generate a markdown campaign-report skeleton from an EDN test-report.

  Reads report/test-report.edn (or a path passed on the command line) and
  prints a markdown skeleton to stdout. The skeleton is meant to be saved
  into <shadow-repo>/clj-oa3-test/reports/campaigns/<deployment>/YYYY-MM-DD.md
  and then expanded with human narrative — findings, severities,
  reproducers, etc.

  Usage:
    bin/format-report                        # reads ./report/test-report.edn
    bin/format-report path/to/report.edn     # explicit path"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Capability profile rendering
;; ---------------------------------------------------------------------------

(defn- src-suffix
  "Format a source keyword as a parenthetical suffix. Nil when the source
  isn't known."
  [source]
  (when source (str " _(" (name source) ")_")))

(defn- render-handlers [caps sources]
  (let [handlers     (get caps :handlers)
        handlers-bl  (get caps :handlers-bl)
        handlers-ven (get caps :handlers-ven)
        sort-set     #(str/join ", " (sort (map name (or % #{}))))]
    (str/join "\n"
              (cond-> []
                handlers     (conj (str "- **Handlers (effective):** "
                                        (sort-set handlers)
                                        (src-suffix (get sources [:handlers]))))
                handlers-bl  (conj (str "- **Handlers on BL port:** "
                                        (sort-set handlers-bl)
                                        (src-suffix (get sources [:handlers-bl]))))
                handlers-ven (conj (str "- **Handlers on VEN port:** "
                                        (sort-set handlers-ven)
                                        (src-suffix (get sources [:handlers-ven]))))))))

(defn- render-transport [caps sources]
  (let [t (:transport caps)]
    (when t
      (str "- **Transport:** "
           (if (:single-port? t) "single-port" "dual-port")
           (when (:bl-port t)  (str " (BL " (:bl-port t) ")"))
           (when (:ven-port t) (str " (VEN " (:ven-port t) ")"))
           (src-suffix (get sources [:transport :single-port?]))))))

(defn- render-http-auth [caps sources]
  (let [a (:http-auth caps)]
    (when a
      (str "- **HTTP auth:** method=`" (or (some-> (:method a) name) "n/a") "`"
           " enforced=`" (boolean (:enforced? a)) "`"
           (src-suffix (get sources [:http-auth :enforced?]))))))

(defn- render-mqtt-auth [caps sources]
  (let [m (:mqtt-auth caps)]
    (when m
      (str "- **MQTT auth:** `" (some-> (:method m) name) "`"
           (src-suffix (get sources [:mqtt-auth :method]))))))

(defn- render-notifiers [caps sources]
  (let [n (:notifiers caps)]
    (when (seq n)
      (str "- **Notifiers:** " (str/join ", " (sort (map name n)))
           (src-suffix (get sources [:notifiers]))))))

(defn- render-ven-routes [caps]
  (let [r (:ven-routes caps)]
    (when (seq r)
      (str "- **VEN-port route enablement:** "
           (str/join ", " (for [[k v] (sort r)] (str (name k) "=" (pr-str v))))))))

(defn- render-capabilities [caps sources]
  (str/join "\n"
            (keep identity
                  [(render-transport  caps sources)
                   (render-http-auth  caps sources)
                   (render-notifiers  caps sources)
                   (render-mqtt-auth  caps sources)
                   (render-handlers   caps sources)
                   (render-ven-routes caps)])))

;; ---------------------------------------------------------------------------
;; Suite tables
;; ---------------------------------------------------------------------------

(defn- result-emoji [r]
  (case r
    :pass            "✅"
    :fail            "❌"
    :error           "💥"
    :skip            "⏭️"
    :skip-capability "⊘"
    :pending         "⏸"
    "?"))

(defn- result-label [r]
  (case r
    :pass            "PASS"
    :fail            "FAIL"
    :error           "ERROR"
    :skip            "SKIP"
    :skip-capability "N/A"
    :pending         "PEND"
    (str r)))

(defn- suite-summary-line [{:keys [total pass fail error skip skip-capability pending]}]
  (str/join ", "
            (cond-> [(str pass "/" total " pass")]
              (and fail (pos? fail))                        (conj (str fail " fail"))
              (and error (pos? error))                      (conj (str error " error"))
              (and skip (pos? skip))                        (conj (str skip " skip"))
              (and skip-capability (pos? skip-capability))  (conj (str skip-capability " N/A"))
              (and pending (pos? pending))                  (conj (str pending " pend")))))

(defn- render-suite-table [{:suite/keys [id tests summary]}]
  (let [id-str (name id)]
    (str/join "\n"
              (concat
               [(str "### `" id-str "` — " (suite-summary-line summary))
                ""
                "| Test | Result | Notes |"
                "| --- | --- | --- |"]
               (for [{:test/keys [name desc result skip-reason]} tests]
                 (str "| `" name "` | "
                      (result-emoji result) " " (result-label result) " | "
                      (or skip-reason desc " ") " |"))
               [""]))))

;; ---------------------------------------------------------------------------
;; Failures section
;; ---------------------------------------------------------------------------

(defn- render-failures [report]
  (let [failures (for [{:suite/keys [id tests]} (:report/suites report)
                       {:test/keys [name desc result failures file line]} tests
                       :when (#{:fail :error} result)]
                   {:suite (clojure.core/name id) :name name :desc desc
                    :failures failures :file file :line line})]
    (when (seq failures)
      (str/join "\n"
                (concat
                 ["" "## Failures" ""]
                 (for [{:keys [suite name desc file line failures]} failures]
                   (str/join "\n"
                             (concat
                              [(str "### `" suite " / " name "` — " desc)
                               ""]
                              (for [{:keys [message expected actual]} failures]
                                (str/join "\n"
                                          (cond-> ["```"]
                                            message  (conj message)
                                            expected (conj (str "expected: " expected))
                                            actual   (conj (str "actual:   " actual))
                                            true     (conj "```"))))
                              [(str "_at_ `" file ":" line "`")
                               ""]))))))))

;; ---------------------------------------------------------------------------
;; Top-level skeleton
;; ---------------------------------------------------------------------------

(defn- render-vtn-section [{:keys [implementation version commit deployment url]}]
  (str/join "\n"
            (cond-> []
              implementation (conj (str "- **Implementation:** " implementation))
              version        (conj (str "- **Version:** " version))
              commit         (conj (str "- **Commit:** `" commit "`"))
              deployment     (conj (str "- **Deployment:** " deployment))
              url            (conj (str "- **URL:** " url)))))

(defn render-markdown [report]
  (let [{:report/keys [timestamp summary suites vtn capabilities capability-sources]} report
        date (subs timestamp 0 10)
        title (or (:deployment vtn) "VTN deployment")]
    (str/join "\n"
              (concat
               [(str "# clj-oa3-test report — " title " — " date)
                ""
                (str "**Run timestamp:** " timestamp)
                ""]
               (when vtn
                 ["## VTN identity" ""
                  (render-vtn-section vtn)
                  ""])
               (when capabilities
                 ["## Capability profile"
                  ""
                  "_Source legend: `declared` (test-config.edn) → `advertised` (GET /notifiers) → `auto-detected` (HTTP probes) → `defaulted`._"
                  ""
                  (render-capabilities capabilities capability-sources)
                  ""])
               ["## Headline"
                ""
                (str "**" (suite-summary-line summary) "** across " (count suites) " suite(s).")
                ""
                "_Add narrative here: what's the one-line summary, what's new vs. last run, what should the reader walk away knowing?_"
                ""
                "## Per-suite results"
                ""]
               (map render-suite-table suites)
               (when-let [fails (render-failures report)]
                 [fails])
               ["## Findings"
                ""
                "_Numbered, severity-tagged write-up of the failures above. For each: reproducer, root-cause hypothesis, severity, what would unblock it._"
                ""
                "## Setup notes"
                ""
                "_Anything non-obvious about how this run was set up — config overrides, deviations from the canonical profile, environment caveats._"
                ""
                "---"
                ""
                "_Generated from `report/test-report.edn` by `bin/format-report`._"]))))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn -main [& args]
  (let [path (or (first args) "report/test-report.edn")
        f    (io/file path)]
    (when-not (.exists f)
      (binding [*out* *err*]
        (println (str "format-report: " path " does not exist. Run the suite first to produce it.")))
      (System/exit 1))
    (let [report (edn/read-string {:default tagged-literal} (slurp f))]
      (println (render-markdown report)))))
