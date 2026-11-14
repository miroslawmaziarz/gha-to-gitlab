(ns gha-to-gitlab.gitlab
  "Target side: writes the CI Model as a GitLab `.gitlab-ci.yml`."
  (:require [clj-yaml.core :as yaml]
            [flatland.ordered.map :refer [ordered-map]]
            [gha-to-gitlab.ci-model :as ci-model]))

(def ci-file-name ".gitlab-ci.yml")

(def ^:private global-keywords
  "Top-level `.gitlab-ci.yml` keys that GitLab reads as settings, not Jobs."
  #{"default" "include" "stages" "variables" "workflow" "image" "services"
    "cache" "before_script" "after_script" "types"})

(defn job-name
  "The GitLab Job name for a CI Model Job id; ids that collide with a
  global keyword get a `_job` suffix so they stay Jobs."
  [id]
  (if (global-keywords id) (str id "_job") id))

(defn- ci-model->job-map [model]
  (into (ordered-map)
        (map (fn [{:keys [id script]}] [(job-name id) {:script script}]))
        (:jobs model)))

(defn ci-model->gitlab-ci-yaml
  "Renders a CI Model as the text of a `.gitlab-ci.yml`."
  [model]
  (yaml/generate-string (ci-model->job-map (ci-model/validate! model))
                        :dumper-options {:flow-style :block}))
