(ns gha-to-gitlab.cli
  "Command-line entry point."
  (:require [gha-to-gitlab.migration :as migration])
  (:gen-class))

(def ^:private usage
  "Usage: clojure -M:run <path-to-local-clone> <output-dir>")

(defn- print-err [& xs]
  (binding [*out* *err*] (apply println xs)))

(defn run
  "Runs the CLI with `args` and returns the process exit code."
  [args]
  (if (not= 2 (count args))
    (do (print-err usage)
        2)
    (let [[source-dir output-dir] args]
      (try
        (let [{:keys [gitlab-ci-path report-path]} (migration/run! {:source-dir source-dir
                                                          :output-dir output-dir})]
          (println "Wrote" gitlab-ci-path)
          (println "Wrote" report-path)
          0)
        (catch Exception e
          (print-err "Migration Run failed:" (ex-message e))
          1)))))

(defn -main [& args]
  (let [exit (run args)]
    (shutdown-agents)
    (System/exit exit)))
