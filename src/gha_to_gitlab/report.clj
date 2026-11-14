(ns gha-to-gitlab.report
  "Renders the Migration Report: what a Migration Run converted and every Gap."
  (:require [clojure.string :as str]
            [gha-to-gitlab.gitlab :as gitlab]))

(defn- job-line [{:keys [id script]}]
  (let [target-name (gitlab/job-name id)
        n (count script)]
    (format "- `%s`%s: %d script line%s"
            id
            (if (= id target-name) "" (str " → `" target-name "` (renamed: `" id "` is a GitLab keyword)"))
            n
            (if (= 1 n) "" "s"))))

(defn- gap-line [{:keys [message]}]
  (str "- " message))

(defn render
  "Markdown text of the Migration Report for `model`, converted from the
  Workflow at `workflow-path` (relative to the Source)."
  [{:keys [workflow-path model]}]
  (str/join
   "\n"
   (concat
    ["# Migration Report"
     ""
     "## Converted"
     ""
     (str "Workflow `" workflow-path "` became `" gitlab/ci-file-name "` with these Jobs:")
     ""]
    (map job-line (:jobs model))
    ["" "## Gaps" ""]
    (if (seq (:gaps model))
      (map gap-line (:gaps model))
      ["No Gaps: nothing needs a person to finish it."])
    [""])))
