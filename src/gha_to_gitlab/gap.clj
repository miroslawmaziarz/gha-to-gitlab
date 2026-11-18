(ns gha-to-gitlab.gap
  "Gaps: every module that raises one gives where in the Source it is and
  what went wrong, and this module writes the message the Migration Report
  shows, so every Gap names its place the same way."
  (:require [clojure.string :as str]
            [gha-to-gitlab.report :as report]
            [gha-to-gitlab.wording :as wording]))

(def ^:private location-keys
  "The keys of a Gap's location, from the widest place to the narrowest;
  `:secret` names the Secret a Gap is about."
  [:workflow :job :step :action :input :secret])

(defn- prefix
  "The start of a Gap message naming its location, e.g. \"Workflow `ci.yml`,
  Job `build`, Step `Checkout`: Action `actions/checkout@v4` \"."
  [{:keys [workflow job step action]}]
  (let [places (cond-> []
                 workflow (conj (str "Workflow `" workflow "`"))
                 job (conj (str "Job `" job "`"))
                 step (conj (str "Step `" step "`")))]
    (str (when (seq places) (str (str/join ", " places) ": "))
         (when action (str "Action `" action "` ")))))

(defn at
  "The Gap at `location` (any of `:workflow`, `:job`, `:step`, `:action`,
  `:input`, the Action input it is about, and `:secret`) with `text`, what went wrong
  and what a person should do. Its `:message` names the location first;
  with an `:action`, `text` continues a sentence about the Action (\"has no
  Action Translator; ...\"). Absent location keys are left out."
  [location text]
  (let [location (into {} (filter (comp some? val)) (select-keys location location-keys))]
    (assoc location :message (str (prefix location) text))))

(defn placeholder-line
  "The script line a Job keeps in place of Action `action`, which has no
  Action Translator: it echoes that the Action was not converted and points
  to the Migration Report."
  [action]
  (str "echo " (wording/shell-quote (str "Gap: Action " action " was not converted; see " report/file-name))))
