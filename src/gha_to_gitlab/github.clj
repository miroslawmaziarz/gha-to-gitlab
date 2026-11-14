(ns gha-to-gitlab.github
  "Source side: reads a GitHub Actions Workflow into the CI Model."
  (:require [clj-yaml.core :as yaml]
            [clojure.string :as str]
            [gha-to-gitlab.action-translator :as action-translator]
            [gha-to-gitlab.action-translators]
            [gha-to-gitlab.ci-model :as ci-model]
            [gha-to-gitlab.report :as report]))

(defn- step-label [step index]
  (str (or (:name step) (:id step) (str "step " (inc index)))))

(defn- shell-quote
  "`s` as one single-quoted POSIX shell word."
  [s]
  (str "'" (str/replace s "'" "'\\''") "'"))

(defn- unknown-action [workflow-path job-id step index]
  (let [action (:uses step)
        label (step-label step index)]
    {:script [(str "echo " (shell-quote (str "Gap: Action " action " was not converted; see "
                                             report/file-name)))]
     :gap {:message (str "Workflow `" workflow-path "`, Job `" job-id "`, Step `" label
                         "`: Action `" action "` has no Action Translator;"
                         " replace its placeholder script line by hand.")
           :workflow workflow-path
           :job job-id
           :step label
           :action action}}))

(defn- convert-step
  "Converts one Step into `{:script [line ...]}`, plus a `:gap` when the
  Step could not be converted faithfully."
  [workflow-path job-id index step]
  (cond
    (:run step) {:script [(:run step)]}
    (:uses step) (or (action-translator/translate step)
                     (unknown-action workflow-path job-id step index))
    :else (throw (ex-info (str "Job '" job-id "' has a Step with neither run: nor uses:")
                          {:job job-id :step step}))))

(defn- convert-job [workflow-path [job-id job]]
  (let [id (name job-id)
        steps (map-indexed #(convert-step workflow-path id %1 %2) (:steps job))]
    {:job {:id id :script (into [] (mapcat :script) steps)}
     :gaps (keep :gap steps)}))

(defn workflow->ci-model
  "Converts the YAML text of one Workflow into a validated CI Model.
  `:workflow-path` (the Workflow's path in the Source) is used to name the
  Workflow in Gaps."
  ([workflow-yaml] (workflow->ci-model workflow-yaml {}))
  ([workflow-yaml {:keys [workflow-path] :or {workflow-path "workflow"}}]
   (let [workflow (yaml/parse-string workflow-yaml)
         jobs (mapv #(convert-job workflow-path %) (:jobs workflow))]
     (ci-model/validate!
      {:jobs (mapv :job jobs)
       :gaps (into [] (mapcat :gaps) jobs)}))))
