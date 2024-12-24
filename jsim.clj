#!/usr/bin/env bb

(require '[babashka.fs :as fs]
         '[babashka.cli :as cli]
         '[babashka.process :refer [shell process pipeline pb]]
         '[clojure.string :as str]
         '[clojure.pprint :refer [pprint]])

(def debug-aware? false)
(defn aware [s v] (when debug-aware? (prn (list 'aware s v))))
(defn create-dirs [p] (fs/create-dirs p) p)

(def pstate (fs/xdg-state-home "jsim"))
(create-dirs pstate)

(def cwd (fs/cwd))
(defn uu [fname & args]
  (let [curr (nth args 0 cwd)]
    (cond (fs/exists? (fs/path curr fname))
          curr
          (= (fs/normalize curr) (fs/path "/"))
          (throw (Exception. "not in the thing"))
          :else
          (uu fname (fs/path curr "..")))))

(def pproj (fs/normalize (uu "deps.edn")))
(aware '*project-dir* (str pproj))
(def pproj-state (fs/path pstate (str (fs/file-name pproj)
                                      "-"
                                      (hash (str pproj)))))
(aware '*project-state-dir* (str pproj-state))
(create-dirs pproj-state)

(def deps (read-string (slurp (str (fs/path pproj "deps.edn")))))
(def proj-config (:jsim deps))
(when (empty? proj-config)
  (println (str "no :jsim configurations in "
                (str pproj)))
  (System/exit 0))
(aware '*project-jobs* (keys proj-config))

(defn check-process-active [pid pid-confirm]
  (try
   (-> (->> (shell {:out :string} (str "ps -o args " pid))
            :out
            str/split-lines
            (drop 1)
            first)
       (str/starts-with? pid-confirm))
   (catch Exception _ false)))

(defn dslurp [d]
  (fn [f]
   (try (->> f (fs/path d) str slurp read-string)
        (catch Exception _ nil))))

(defn mk-job [job-id]
  (let [pname #(str %1 "-" %2 "-" (hash %2))
        job-name (pname "job" job-id)
        pjob (create-dirs (fs/path pproj-state job-name))
        jslurp (dslurp pjob)
        pid (jslurp "pid")
        get-pproc #(and %1 (create-dirs (fs/path pjob "by-p" (pname "p" %1))))
        pproc (get-pproc pid)
        pslurp (dslurp pproc)
        pid-confirm (and pid (pslurp "pid-confirm"))
        is-active? (and pid-confirm (check-process-active pid pid-confirm))
        last-started (and pid (pslurp "started"))
        last-off (pslurp "off")
        last-off-by (pslurp "off-by")]
    {:job-id job-id
     :pid pid
     :pid-confirm pid-confirm
     :is-active? is-active?
     :last-started last-started
     :last-off last-off
     :last-off-by last-off-by
     :pjob pjob
     :pproc pproc
     :get-pproc get-pproc
     :refresh (fn []
                (fs/delete-tree (fs/path pjob "by-p"))
                (create-dirs (fs/path pjob "by-p" "next")))}))

(def action-handles
  {:status
   (fn [job]
     (->> [:job-id :pid :is-active? :last-started :last-off :last-off-by]
          (select-keys job)
          pprint))
   :start
   (fn [job]
     (if (:is-active? job)
       (println (str "job@[" (:job-id job) "] already active"))
       (let [jpid (.pid (java.lang.ProcessHandle/current))
             ts (quot (System/currentTimeMillis) 1000)
             pid-confirm (str "c-" jpid "-" ts)
             wd ((:refresh job))
             wf #(str (fs/path wd %1))
             wspit #(spit (wf %1) %2)
             runsh-lines
             ["#!/usr/bin/env bash"
              "function _do_raw_run () {"
              (get proj-config (:job-id job))
              "}"
              "function _do_run () {"
              "  _do_raw_run"
              "  STATUS=$?"
              "  echo $STATUS > $JSIM_STATE_DIR/status"
              "}"
              "in_fifo=\"$JSIM_STATE_DIR/in-fifo\""
              "err=\"$JSIM_STATE_DIR/err\""
              "log=\"$JSIM_STATE_DIR/log\""
              "rm -rf \"$in_fifo\""
              "mkfifo \"$in_fifo\""
              "cd $JSIM_EXEC_DIR"
              "cat \"$in_fifo\" | (_do_run 2> $err 1> $log)"
              ""]
             runsh (str/join "\n" runsh-lines)]
         (wspit "run.bash" runsh)
         (shell (str "chmod +x " (wf "run.bash")))
         (let [p (process {:extra-env {"JSIM_STATE_DIR" (str wd)
                                       "JSIM_EXEC_DIR" (str pproj)}}
                          (str "nohup bash -c 'exec -a " pid-confirm " bash " (wf "run.bash") "'"))
               pid (-> p :proc .pid)
               pproc ((:get-pproc job) pid)]
           (wspit "pid-confirm" (prn-str pid-confirm))
           (wspit "last-started" (prn-str ts))
           (spit (str (fs/path (:pjob job) "pid")) pid)
           (fs/delete-tree pproc)
           (fs/create-sym-link pproc wd)
           (println (str "job@[" (:job-id job) "] started with pid@[" pid "]"))))))
   :stop
   (fn [job]
     (if-not (:is-active? job)
       (println (str "job@[" (:job-id job) "] already stopped"))
       (let [ts (quot (System/currentTimeMillis) 1000)
             wd (:pproc job)
             wf #(str (fs/path wd %1))
             wspit #(spit (wf %1) %2)
             pid (:pid job)]
         (wspit "off" ts)
         (wspit "off-by" (prn-str :stop-action))
         (println (str "job@[" (:job-id job) "] stopping pid@[" pid "]"))
         (shell (str "kill -9 " pid))
         (println (str "job@[" (:job-id job) "] stopped")))))
   :plug-in
   (fn [_])
   :tail
   (fn [_])
   :tail-err
   (fn [_])
   :tail-all
   (fn [_])})
(def actions (into #{} (keys action-handles)))


(def parse-args-options
  {:spec {:help {:alias :h
                 :coerce :boolean
                   :desc "print this message"}
          :action {:ref "<act>"
                   :coerce :keyword
                   :alias :a
                   :validate actions
                   :default :status
                   :default-desc "[def(status)]"
                   :desc "what to do"}}})

(def parsed (cli/parse-args *command-line-args* parse-args-options))
(def opts (:opts parsed))
(when (:help opts)
  (println "<usage> jsim <Opts> <Sel>")
  (println "  <Opts>")
  (println (cli/format-opts (assoc parse-args-options :indent 4)))
  (println "  <act> " )
  (println (str "    " (str/join " | " (map name actions))))
  (System/exit 0))
(def args (:args parsed))
(def action (:action opts))
(def job-ids (keys proj-config)) ; TODO parse based on args
(doseq [job-id job-ids] ((get action-handles action) (mk-job job-id)))
