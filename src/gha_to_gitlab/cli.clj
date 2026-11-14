(ns gha-to-gitlab.cli
  "Command-line entry point."
  (:require [gha-to-gitlab.migration :as migration])
  (:gen-class))

(def ^:private usage
  "Usage: clojure -M:run <path-to-local-clone> <output-dir>")

(defn run
  "Runs the CLI with `args` and returns the process exit code."
  [args]
  (if (not= 2 (count args))
    (do (binding [*out* *err*] (println usage))
        2)
    (let [[source-dir output-dir] args]
      (try
        (let [{:keys [gitlab-ci report]} (migration/run! {:source-dir source-dir
                                                          :output-dir output-dir})]
          (println "Wrote" gitlab-ci)
          (println "Wrote" report)
          0)
        (catch clojure.lang.ExceptionInfo e
          (binding [*out* *err*] (println "Migration Run failed:" (ex-message e)))
          1)))))

(defn -main [& args]
  (let [exit (run args)]
    (shutdown-agents)
    (System/exit exit)))
