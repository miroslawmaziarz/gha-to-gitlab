(ns gha-to-gitlab.github
  "Source side: reads a GitHub Actions Workflow into the CI Model."
  (:require [clj-yaml.core :as yaml]
            [clojure.string :as str]
            [gha-to-gitlab.action-job-settings :as action-job-settings]
            [gha-to-gitlab.action-translator :as action-translator]
            [gha-to-gitlab.action-translators]
            [gha-to-gitlab.ci-model :as ci-model]
            [gha-to-gitlab.report :as report]
            [gha-to-gitlab.wording :as wording]))

(defn- step-label [step index]
  (str (or (:name step) (:id step) (str "step " (inc index)))))

(defn- runner-label
  "The `runs-on` of a Job as one label string: a list of labels is joined
  with \", \"; a runner group is written in YAML-like form
  (`group: big, labels: [linux, x64]`)."
  [runs-on]
  (cond
    (sequential? runs-on) (str/join ", " runs-on)
    (map? runs-on) (str/join ", " (map (fn [[k v]] (str (name k) ": " (wording/yaml-ish v))) runs-on))
    :else (str runs-on)))

(defn- shell-quote
  "`s` as one single-quoted POSIX shell word."
  [s]
  (str "'" (str/replace s "'" "'\\''") "'"))

(defn- step-location
  "Where a Step that uses an Action is in the Source: the Gap keys
  `:workflow :job :step :action`, plus `:prefix`, the start of a Gap message
  naming them all."
  [workflow-path job-id step index]
  (let [label (step-label step index)
        action (:uses step)]
    {:workflow workflow-path
     :job job-id
     :step label
     :action action
     :prefix (str "Workflow `" workflow-path "`, Job `" job-id "`, Step `" label
                  "`: Action `" action "` ")}))

(defn- located-gap
  "A Gap at `location` whose message continues the location's prefix."
  [{:keys [prefix] :as location} {:keys [message] :as gap}]
  (merge gap
         (dissoc location :prefix)
         {:message (str prefix message)}))

(defn- unknown-action [{:keys [action] :as location}]
  {:script [(str "echo " (shell-quote (str "Gap: Action " action " was not converted; see "
                                           report/file-name)))]
   :gaps [(located-gap location {:message (str "has no Action Translator;"
                                               " replace its placeholder script line by hand.")})]})

(defn- translated-action [{:keys [step action] :as location} translation]
  (let [{:keys [script gaps outcome job-settings]} translation]
    {:script (vec script)
     :gaps (mapv #(located-gap location %) gaps)
     :location location
     :job-settings job-settings
     :action {:step step
              :action action
              :outcome (or outcome "converted")
              :gap-count (count gaps)}}))

(defn- convert-step
  "Converts one Step into `{:script [line ...] :gaps [gap ...]}`; `:gaps`
  lists what could not be converted faithfully, and `:action` records an
  Action that an Action Translator converted."
  [workflow-path job-id index step]
  (cond
    (:run step) {:script [(:run step)]}
    (:uses step) (let [location (step-location workflow-path job-id step index)]
                   (if-let [translation (action-translator/translate step)]
                     (translated-action location translation)
                     (unknown-action location)))
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

(defn- settle-job-settings
  "Settles the Job settings the Job's Actions asked for (`:job-settings`)
  with `action-job-settings/settle`, returned as `:settings`, with `:steps`
  updated: each Converted Action's `:sets` names the settings the Job kept
  from it, and a Gap it raised is placed at its Step and counted on it."
  [steps]
  (let [{:keys [settings results]}
        (action-job-settings/settle (for [{:keys [action job-settings]} steps]
                                      {:step (:step action) :action (:action action) :settings job-settings}))]
    {:settings settings
     :steps (mapv (fn [{:keys [location] :as step} {:keys [kept gaps]}]
                    (cond-> step
                      (seq kept) (assoc-in [:action :sets] kept)
                      (seq gaps) (-> (update :gaps into (map #(located-gap location %) gaps))
                                     (update-in [:action :gap-count] + (count gaps)))))
                  steps results)}))

(defn- job-needs
  "The Job ids in a Job's `needs:`, which is one id or a list of them;
  each id once, as GitLab refuses duplicate needs."
  [needs]
  (into [] (comp (map str) (distinct)) (if (sequential? needs) needs [needs])))

(defn- missing-job-gap [workflow-path job-id needed]
  {:workflow workflow-path
   :job job-id
   :message (str "Workflow `" workflow-path "`, Job `" job-id "`: needs Job `" needed
                 "`, which the Workflow does not define; the Job no longer waits for it,"
                 " so add the dependency by hand.")})

(defn- convert-job
  "Converts one Job into `{:job .. :gaps [..]}`. `job-ids` are the ids of
  every Job in the Workflow; a `needs:` entry naming any other Job is a Gap
  and is left out of the Job's `:needs`."
  [workflow-path job-ids [job-id job]]
  (let [id (name job-id)
        {needs true missing false} (group-by (comp boolean job-ids)
                                             (some-> (:needs job) job-needs))
        {:keys [steps settings]} (->> (:steps job)
                                      (map-indexed #(convert-step workflow-path id %1 %2))
                                      settle-job-settings)]
    {:job (cond-> {:id id}
            (some? (:runs-on job)) (assoc :runs-on (runner-label (:runs-on job)))
            (seq needs) (assoc :needs needs)
            true (assoc :script (job-script steps))
            (some :action steps) (assoc :actions (into [] (keep :action) steps))
            (seq settings) (assoc :action-job-settings settings))
     :gaps (into (mapv #(missing-job-gap workflow-path id %) missing)
                 (mapcat :gaps) steps)}))

(defn workflow->ci-model
  "Converts the YAML text of one Workflow into a validated CI Model; Jobs
  that need each other in a cycle are refused, as GitHub refuses them.
  `:workflow-path` (the Workflow's path in the Source) is used to name the
  Workflow in Gaps."
  ([workflow-yaml] (workflow->ci-model workflow-yaml {}))
  ([workflow-yaml {:keys [workflow-path] :or {workflow-path "workflow"}}]
   (let [workflow (yaml/parse-string workflow-yaml)
         job-ids (into #{} (map (comp name key)) (:jobs workflow))
         jobs (mapv #(convert-job workflow-path job-ids %) (:jobs workflow))]
     (ci-model/validate!
      {:jobs (mapv :job jobs)
       :gaps (into [] (mapcat :gaps) jobs)}))))
