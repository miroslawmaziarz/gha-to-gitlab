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
  (let [{:keys [script gaps outcome job]} translation]
    {:script (vec script)
     :gaps (mapv #(located-gap location %) gaps)
     :location location
     :action (cond-> {:step step
                      :action action
                      :outcome (or outcome "converted")
                      :gap-count (count gaps)}
               (seq job) (assoc :job job))}))

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

(defn- conflict-gap [setting value {:keys [step action]} kept]
  {:message (str "sets the Job's `" (name setting) "` to `" value "`, but Step `" step
                 "` (Action `" action "`) already set it to `" kept "`, which the Job keeps; "
                 (if (= :image setting)
                   "use one image that has everything the Job needs, or split the Job."
                   "settle on one value by hand."))})

(defn- check-job-settings
  "Adds a Gap to each Step whose Action sets a Job setting that an earlier
  Action in the Job already set to a different value (such as two setup
  Actions choosing different images), and counts it on that Step's
  Converted Action. The earlier value is the one the Job keeps (see
  `ci-model/action-job-settings`)."
  [steps]
  (first
   (reduce (fn [[steps set-by] {:keys [action location] :as step}]
             (let [conflicts (for [[k v] (:job action)
                                   :let [[kept earlier] (get set-by k)]
                                   :when (and earlier (not= v kept))]
                               (located-gap location (conflict-gap k v earlier kept)))]
               [(conj steps (cond-> step
                              (seq conflicts) (-> (update :gaps into conflicts)
                                                  (update-in [:action :gap-count] + (count conflicts)))))
                (merge (update-vals (:job action) #(vector % action)) set-by)]))
           [[] {}]
           steps)))

(defn- convert-job [workflow-path [job-id job]]
  (let [id (name job-id)
        steps (->> (:steps job)
                   (map-indexed #(convert-step workflow-path id %1 %2))
                   check-job-settings)
        ci-job (cond-> {:id id}
                 (some? (:runs-on job)) (assoc :runs-on (runner-label (:runs-on job)))
                 true (assoc :script (job-script steps))
                 (some :action steps) (assoc :actions (into [] (keep :action) steps)))]
    {:job (merge ci-job (ci-model/action-job-settings ci-job))
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
