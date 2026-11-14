(ns gha-to-gitlab.migration
  "A Migration Run: Source → CI Model → Target, plus the Migration Report."
  (:refer-clojure :exclude [run!])
  (:require [clojure.java.io :as io]
            [gha-to-gitlab.github :as github]
            [gha-to-gitlab.gitlab :as gitlab]
            [gha-to-gitlab.report :as report]))

(def ^:private workflows-dir ".github/workflows")

(defn- workflow-files [source-dir]
  (->> (.listFiles (io/file source-dir workflows-dir))
       (filter #(re-find #"\.ya?ml$" (.getName %)))
       (sort-by #(.getName %))))

(defn- single-workflow [source-dir]
  (let [files (workflow-files source-dir)]
    (when (not= 1 (count files))
      (throw (ex-info (str "Expected exactly one Workflow in " source-dir "/" workflows-dir
                           ", found " (count files))
                      {:source-dir source-dir :workflows (mapv str files)})))
    (first files)))

(defn run!
  "Runs one Migration Run against the local clone at `source-dir`, writing
  `.gitlab-ci.yml` and `MIGRATION_REPORT.md` into `output-dir`.
  Returns the paths written."
  [{:keys [source-dir output-dir]}]
  (let [workflow (single-workflow source-dir)
        model (github/workflow->ci-model (slurp workflow))
        ci-file (io/file output-dir gitlab/ci-file-name)
        report-file (io/file output-dir "MIGRATION_REPORT.md")]
    (.mkdirs (io/file output-dir))
    (spit ci-file (gitlab/ci-model->gitlab-ci-yaml model))
    (spit report-file (report/render {:workflow-path (str workflows-dir "/" (.getName workflow))
                                      :model model}))
    {:gitlab-ci-path (str ci-file) :report-path (str report-file)}))
