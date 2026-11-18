(ns gha-to-gitlab.migration
  "A Migration Run: Source → CI Model → Target, plus the Migration Report.
  `convert` is the whole conversion, from Workflow text to output text, with
  no file access; `run!` reads a local clone and writes the outputs around it."
  (:refer-clojure :exclude [run!])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [gha-to-gitlab.action-job-settings :as action-job-settings]
            [gha-to-gitlab.config :as config]
            [gha-to-gitlab.github :as github]
            [gha-to-gitlab.gitlab :as gitlab]
            [gha-to-gitlab.report :as report]
            [gha-to-gitlab.runner-mapping :as runner-mapping]))

(def ^:private workflows-dir ".github/workflows")

(defn- single-workflow
  "The one `[path text]` entry of `workflows`; throws ex-info otherwise."
  [workflows]
  (when (not= 1 (count workflows))
    (throw (ex-info (str "Expected exactly one Workflow, found " (count workflows)
                         (when (seq workflows) (str ": " (str/join ", " (sort (keys workflows))))))
                    {:workflows (vec (sort (keys workflows)))})))
  (first workflows))

(defn convert
  "Runs one Migration Run in memory. `:workflows` maps each Workflow's path
  in the Source (e.g. `.github/workflows/ci.yml`) to its YAML text; there
  must be exactly one. `:config` is the Migration Config value (see
  `gha-to-gitlab.config`), or nil for none. Returns `{:model :gitlab-ci-yaml
  :report}`: the finished CI Model and the texts of `.gitlab-ci.yml` and
  `MIGRATION_REPORT.md`. Throws ex-info when the Workflows, a Workflow or
  the Migration Config cannot be converted."
  [{:keys [workflows config]}]
  (let [{:keys [runner-mappings]} (config/validate-config config)
        [workflow-path workflow-yaml] (single-workflow workflows)
        model (-> (github/workflow->ci-model workflow-yaml {:workflow-path workflow-path})
                  (runner-mapping/apply-runner-mappings runner-mappings)
                  action-job-settings/apply-action-job-settings)]
    {:model model
     :gitlab-ci-yaml (gitlab/ci-model->gitlab-ci-yaml model)
     :report (report/render {:workflow-path workflow-path :model model})}))

(defn- read-workflows
  "Workflow path → YAML text for every `.yml`/`.yaml` file in the clone's
  `.github/workflows`."
  [source-dir]
  (into {}
        (for [file (.listFiles (io/file source-dir workflows-dir))
              :when (re-find #"\.ya?ml$" (.getName file))]
          [(str workflows-dir "/" (.getName file)) (slurp file)])))

(defn run!
  "Runs one Migration Run against the local clone at `source-dir`, writing
  `.gitlab-ci.yml` and `MIGRATION_REPORT.md` into `output-dir`. An optional
  `config-path` names the Migration Config file. Returns the paths written."
  [{:keys [source-dir output-dir config-path]}]
  (let [{:keys [gitlab-ci-yaml report]} (convert {:workflows (read-workflows source-dir)
                                                  :config (config/load-config config-path)})
        ci-file (io/file output-dir gitlab/ci-file-name)
        report-file (io/file output-dir report/file-name)]
    (.mkdirs (io/file output-dir))
    (spit ci-file gitlab-ci-yaml)
    (spit report-file report)
    {:gitlab-ci-path (str ci-file) :report-path (str report-file)}))
