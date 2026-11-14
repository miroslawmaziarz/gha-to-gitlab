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
    (if-let [gaps (seq (:gaps model))]
      (concat [(if (= 1 (count gaps))
                 "1 Gap needs a person to finish it:"
                 (str (count gaps) " Gaps need a person to finish them:"))
               ""]
              (map gap-line gaps))
      ["No Gaps: nothing needs a person to finish it."])
    [""])))
