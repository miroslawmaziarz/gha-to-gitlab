(ns gha-to-gitlab.cli
  "Command-line entry point."
  (:require [gha-to-gitlab.migration :as migration])
  (:gen-class))

(def ^:private usage
  "Usage: clojure -M:run [--config <config.edn>] <path-to-local-clone> <output-dir>")

(defn- print-err [& xs]
  (binding [*out* *err*] (apply println xs)))

(defn- parse-args
  "The Migration Run options from `args`, or nil when they don't fit the usage."
  [args]
  (let [[config-path positional] (if (= "--config" (first args))
                                   [(second args) (drop 2 args)]
                                   [nil args])]
    (when (= 2 (count positional))
      (let [[source-dir output-dir] positional]
        {:source-dir source-dir :output-dir output-dir :config-path config-path}))))

(defn run
  "Runs the CLI with `args` and returns the process exit code."
  [args]
  (if-let [opts (parse-args args)]
    (try
      (let [{:keys [gitlab-ci-path report-path]} (migration/run! opts)]
        (println "Wrote" gitlab-ci-path)
        (println "Wrote" report-path)
        0)
      (catch Exception e
        (print-err "Migration Run failed:" (ex-message e))
        1))
    (do (print-err usage)
        2)))

(defn -main [& args]
  (let [exit (run args)]
    (shutdown-agents)
    (System/exit exit)))
