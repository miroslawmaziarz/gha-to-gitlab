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

(defn- yaml-ish
  "A YAML value as short flow-style text, e.g. `[linux, x64]`."
  [v]
  (if (sequential? v)
    (str "[" (str/join ", " (map yaml-ish v)) "]")
    (str v)))

(defn- runner-label
  "The `runs-on` of a Job as one label string: a list of labels is joined
  with \", \"; a runner group is written in YAML-like form
  (`group: big, labels: [linux, x64]`)."
  [runs-on]
  (cond
    (sequential? runs-on) (str/join ", " runs-on)
    (map? runs-on) (str/join ", " (map (fn [[k v]] (str (name k) ": " (yaml-ish v))) runs-on))
    :else (str runs-on)))

(defn- shell-quote
  "`s` as one single-quoted POSIX shell word."
  [s]
  (str "'" (str/replace s "'" "'\\''") "'"))

(defn- unknown-action [workflow-path job-id step index]
  (let [action (:uses step)
        label (step-label step index)]
    {:script [(str "echo " (shell-quote (str "Gap: Action " action " was not converted; see "
                                             report/file-name)))]
     :gaps [{:message (str "Workflow `" workflow-path "`, Job `" job-id "`, Step `" label
                         "`: Action `" action "` has no Action Translator;"
                         " replace its placeholder script line by hand.")
           :workflow workflow-path
           :job job-id
           :step label
           :action action}]}))

(defn- located-gap
  "A Gap an Action Translator returned, placed at its Step in the Source."
  [workflow-path job-id step index {:keys [message] :as gap}]
  (let [label (step-label step index)]
    (assoc gap
           :message (str "Workflow `" workflow-path "`, Job `" job-id "`, Step `" label
                         "`: Action `" (:uses step) "` " message)
           :workflow workflow-path
           :job job-id
           :step label
           :action (:uses step))))

(defn- translated-action [workflow-path job-id step index]
  (when-let [{:keys [script gaps outcome]} (action-translator/translate step)]
    {:script (vec script)
     :gaps (mapv #(located-gap workflow-path job-id step index %) gaps)
     :action {:step (step-label step index)
              :action (:uses step)
              :outcome (or outcome "converted")
              :gap-count (count gaps)}}))

(defn- convert-step
  "Converts one Step into `{:script [line ...] :gaps [gap ...]}`; `:gaps`
  lists what could not be converted faithfully, and `:action` records an
  Action that an Action Translator converted."
  [workflow-path job-id index step]
  (cond
    (:run step) {:script [(:run step)]}
    (:uses step) (or (translated-action workflow-path job-id step index)
                     (unknown-action workflow-path job-id step index))
    :else (throw (ex-info (str "Job '" job-id "' has a Step with neither run: nor uses:")
                          {:job job-id :step step}))))

(def ^:private nothing-left-to-run
  "The script line of a Job whose Steps all converted to no script lines
  (e.g. only `actions/checkout`); GitLab needs at least one."
  (str "echo " (shell-quote "Nothing left to run after conversion")))

(defn- job-script
  "The Job's script lines from its converted Steps. When the Job has Steps
  but none left a script line, it is the single `nothing-left-to-run` line;
  a Job with no Steps stays empty and fails CI Model validation."
  [steps]
  (let [script (into [] (mapcat :script) steps)]
    (if (and (seq steps) (empty? script))
      [nothing-left-to-run]
      script)))

(defn- convert-job [workflow-path [job-id job]]
  (let [id (name job-id)
        steps (map-indexed #(convert-step workflow-path id %1 %2) (:steps job))]
    {:job (cond-> {:id id}
            (some? (:runs-on job)) (assoc :runs-on (runner-label (:runs-on job)))
            true (assoc :script (job-script steps))
            (some :action steps) (assoc :actions (into [] (keep :action) steps)))
     :gaps (into [] (mapcat :gaps) steps)}))

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
