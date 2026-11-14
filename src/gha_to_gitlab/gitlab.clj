(ns gha-to-gitlab.gitlab
  "Target side: writes the CI Model as a GitLab `.gitlab-ci.yml`."
  (:require [clj-yaml.core :as yaml]
            [flatland.ordered.map :refer [ordered-map]]
            [gha-to-gitlab.ci-model :as ci-model]))

(defn- ci-model->gitlab-ci [model]
  (into (ordered-map)
        (map (fn [{:keys [id script]}] [id {:script script}]))
        (:jobs model)))

(defn ci-model->gitlab-ci-yaml
  "Renders a CI Model as the text of a `.gitlab-ci.yml`."
  [model]
  (yaml/generate-string (ci-model->gitlab-ci (ci-model/validate! model))
                        :dumper-options {:flow-style :block}))
