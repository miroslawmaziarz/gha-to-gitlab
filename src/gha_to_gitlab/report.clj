(ns gha-to-gitlab.report
  "Renders the Migration Report: what a Migration Run converted and every Gap."
  (:require [clojure.string :as str]
            [gha-to-gitlab.gitlab :as gitlab]))

(def file-name "MIGRATION_REPORT.md")

(defn- runner-part
  "Which Runner Mapping a Job used, or that it had none."
  [{:keys [runs-on image tags]}]
  (cond
    (nil? runs-on) ""
    (nil? image) (str "; runs on `" runs-on "` → no Runner Mapping (see Gaps)")
    :else (str "; runs on `" runs-on "` → image `" image "`, "
               (if (seq tags)
                 (str "tags " (str/join ", " (map #(str "`" % "`") tags)))
                 "no tags"))))

(defn- action-line [{:keys [step action outcome gap-count]}]
  (str "  - Step `" step "`: Action `" action "` " outcome
       (case gap-count
         0 ""
         1 "; 1 Gap"
         (str "; " gap-count " Gaps"))))

(defn- job-lines
  "The Job's line, then one nested line per Action it converted."
  [{:keys [id script actions] :as job}]
  (let [target-name (gitlab/job-name id)
        n (count script)]
    (cons (format "- `%s`%s: %d script line%s%s"
                  id
                  (if (= id target-name) "" (str " → `" target-name "` (renamed: `" id "` is a GitLab keyword)"))
                  n
                  (if (= 1 n) "" "s")
                  (runner-part job))
          (map action-line actions))))

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
    (mapcat job-lines (:jobs model))
    ["" "## Gaps" ""]
    (if-let [gaps (seq (:gaps model))]
      (concat [(if (= 1 (count gaps))
                 "1 Gap needs a person to finish it:"
                 (str (count gaps) " Gaps need a person to finish them:"))
               ""]
              (map gap-line gaps))
      ["No Gaps: nothing needs a person to finish it."])
    [""])))
