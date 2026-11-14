(ns gha-to-gitlab.github
  "Source side: reads a GitHub Actions Workflow into the CI Model."
  (:require [clj-yaml.core :as yaml]
            [gha-to-gitlab.ci-model :as ci-model]))

(defn- step->script-line [job-id step]
  (or (:run step)
      (throw (ex-info (str "Job '" job-id "' has a Step without run:, which is not supported yet")
                      {:job job-id :step step}))))

(defn- job->ci-model [[job-id job]]
  (let [id (name job-id)]
    {:id id
     :script (mapv #(step->script-line id %) (:steps job))}))

(defn workflow->ci-model
  "Converts the YAML text of one Workflow into a validated CI Model."
  [workflow-yaml]
  (let [workflow (yaml/parse-string workflow-yaml)]
    (ci-model/validate!
     {:jobs (mapv job->ci-model (:jobs workflow))
      :gaps []})))
