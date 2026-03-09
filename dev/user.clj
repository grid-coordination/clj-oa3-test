(ns user
  (:require [babashka.process :refer [shell]]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Paths — derived from this repo's location
;; ---------------------------------------------------------------------------

(def ^:private repo-root
  "Parent directory containing all sibling repos."
  (let [cwd (System/getProperty "user.dir")]
    (.getParent (java.io.File. cwd))))

(defn- sibling-path [repo-name]
  (str repo-root "/" repo-name))

(def vtn-ri-dir  (sibling-path "openadr3-vtn-reference-implementation"))
(def callback-dir (sibling-path "test-callback-service"))

;; ---------------------------------------------------------------------------
;; tmux helpers
;; ---------------------------------------------------------------------------

(defn- find-tmux []
  (-> (shell {:out :string} "which tmux") :out str/trim))

(defn- run-in-tmux
  "Start a command in a detached tmux session.
  Returns the session name on success."
  [session-name working-dir cmd & env-pairs]
  (let [args (into [(find-tmux) "new-session" "-d"
                    "-s" session-name
                    "-c" working-dir]
                   (mapcat (fn [[k v]] ["-e" (str k "=" v)]) env-pairs))
        args (conj (vec args) cmd)
        {:keys [exit err]} (apply shell {:out :string :err :string} args)]
    (case exit
      0 (println (str session-name " started — attach: tmux attach-session -t " session-name))
      (println (str session-name " failed to start\nExit: " exit "\n" err)))))

(defn kill-tmux-session [session-name]
  (let [{:keys [exit err]} (shell {:out :string :err :string}
                                  "tmux" "kill-session" "-t" session-name)]
    (case exit
      0 (println (str "tmux session " session-name " killed"))
      (println (str "tmux session " session-name " not found or kill failed\n" err)))))

;; ---------------------------------------------------------------------------
;; VTN Reference Implementation
;; ---------------------------------------------------------------------------

(defn run-vtn-ri
  "Start the VTN-RI in a tmux session.
  Activates the Python venv and runs swagger_server."
  ([] (run-vtn-ri {}))
  ([{:keys [branch] :or {branch "dcj/issue-164"}}]
   (let [script (str "bash -c 'git checkout " branch
                     " && source ./venv/bin/activate"
                     " && python -m swagger_server'")]
     (run-in-tmux "vtn-ri" vtn-ri-dir script))))

(defn kill-vtn-ri [] (kill-tmux-session "vtn-ri"))

;; ---------------------------------------------------------------------------
;; Test callback service
;; ---------------------------------------------------------------------------

(defn run-callback-svc
  "Start the test-callback-service in a tmux session."
  ([] (run-callback-svc {}))
  ([{:keys [branch] :or {branch "main"}}]
   (let [script (str "bash -c 'git checkout " branch
                     " && source ./venv/bin/activate"
                     " && python run.py'")]
     (run-in-tmux "vtn-callbk-svc" callback-dir script))))

(defn kill-callback-svc [] (kill-tmux-session "vtn-callbk-svc"))

;; ---------------------------------------------------------------------------
;; MQTT broker
;; ---------------------------------------------------------------------------

(defn restart-mqtt
  "Restart the Mosquitto MQTT broker via Homebrew services."
  []
  (let [{:keys [exit out err]} (shell {:out :string :err :string}
                                      "brew" "services" "restart" "mosquitto")]
    (println (str "Mosquitto restart — exit: " exit "\n" out))))

;; ---------------------------------------------------------------------------
;; Full stack
;; ---------------------------------------------------------------------------

(defn start-stack!
  "Start Mosquitto, VTN-RI, and callback service."
  []
  (restart-mqtt)
  (Thread/sleep 1000)
  (run-vtn-ri)
  (Thread/sleep 2000)
  (run-callback-svc)
  (println "\nStack started. VTN at http://localhost:8080/openadr3/3.1.0"))

(defn stop-stack!
  "Stop VTN-RI and callback service tmux sessions."
  []
  (kill-vtn-ri)
  (kill-callback-svc)
  (println "Stack stopped. (Mosquitto still running via brew services)"))

(comment
  ;; Start everything
  (start-stack!)

  ;; Or individually
  (restart-mqtt)
  (run-vtn-ri)
  (run-callback-svc)

  ;; Stop
  (stop-stack!)
  (kill-vtn-ri)
  (kill-callback-svc)

  ;; Run tests from REPL
  (require '[kaocha.repl :as k])
  (k/run :integration))
