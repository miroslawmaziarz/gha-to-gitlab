(ns gha-to-gitlab.report
  "Renders the Migration Report: what a Migration Run converted and every Gap."
  (:require [clojure.string :as str]))

(defn- job-line [{:keys [id script]}]
  (format "- `%s`: %d script line%s" id (count script) (if (= 1 (count script)) "" "s")))

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
     (str "Workflow `" workflow-path "` became `.gitlab-ci.yml` with these Jobs:")
     ""]
    (map job-line (:jobs model))
    ["" "## Gaps" ""]
    (if (seq (:gaps model))
      (map gap-line (:gaps model))
      ["No Gaps: nothing needs a person to finish it."])
    [""])))
