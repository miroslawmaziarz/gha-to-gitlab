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
  "A YAML value as short flow-style text, e.g. `[linux, x64]` or
  `{name: dist, paths: [dist]}`."
  [v]
  (cond
    (sequential? v) (str "[" (str/join ", " (map yaml-ish v)) "]")
    (map? v) (str "{" (str/join ", " (map (fn [[k x]] (str (name k) ": " (yaml-ish x))) v)) "}")
    :else (str v)))

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

(def ^:private conflict-hints
  "Per Job setting: what to do when two Actions set it to different values."
  {:image "use one image that has everything the Job needs, or split the Job."
   :artifacts (str "GitLab keeps one artifacts archive per Job, so add these paths to it"
                   " or upload them from another Job by hand.")})

(defn- conflict-gap [setting value {:keys [step action]} kept]
  {:message (str "sets the Job's `" (name setting) "` to `" (yaml-ish value) "`, but Step `" step
                 "` (Action `" action "`) already set it to `" (yaml-ish kept) "`, which the Job keeps; "
                 (get conflict-hints setting "settle on one value by hand."))})

(defn- ordinal
  "`n` as an English ordinal: 1st, 2nd, 3rd, 4th, 11th, 12th, 13th, 21st, ..."
  [n]
  (str n (if (#{11 12 13} (mod n 100))
           "th"
           (case (mod n 10) 1 "st" 2 "nd" 3 "rd" "th"))))

(defn- too-many-gap [setting limit]
  {:message (str "adds the " (ordinal (inc limit)) " entry to the Job's `" (name setting) "`, but GitLab allows at most "
                 limit "; the Job leaves it out, so merge it into another entry by hand.")})

(defn- add-setting
  "What happens when an Action asks for setting `k` = `v` on top of the
  `settings` settled so far: `{:value v'}` is the setting's new value, or
  `{:gap ..}` when the Job keeps the old one. A list setting
  (`ci-model/list-settings`) appends the Action's entries up to its limit;
  a map setting (`ci-model/map-settings`) adds the Action's entries unless
  one clashes with an earlier entry; any other setting keeps the earliest
  Action's value."
  [settings set-by k v]
  (let [old (get settings k)]
    (if-let [limit (ci-model/list-settings k)]
      (if (< limit (+ (count old) (count v)))
        {:gap (too-many-gap k limit)}
        {:value (into (vec old) v)})
      (cond
        (not (contains? settings k)) {:value v}
        (ci-model/map-settings k)
        (let [clashes (into {} (filter (fn [[n x]] (and (contains? old n) (not= x (get old n))))) v)]
          (if (seq clashes)
            {:gap (conflict-gap k clashes (get set-by k) (select-keys old (keys clashes)))}
            {:value (merge v old)}))
        (= v old) {:value old}
        :else {:gap (conflict-gap k v (get set-by k) old)}))))

(defn- resolve-job-settings
  "Settles the Job settings the Job's Actions asked for (`:job-settings`)
  into one map, returned as `:settings`, with `:steps` updated. Steps are in
  order and the earliest Action to set a setting wins; a later Action that
  sets it to a different value gets a Gap, counted on its Converted Action.
  A list setting (`ci-model/list-settings`, such as `:cache`) instead
  collects every Action's entries, and an Action whose entries go past the
  limit gets a Gap; a map setting (`ci-model/map-settings`, such as
  `:variables`) combines them, and an Action with an entry that clashes
  gets a Gap. Each Converted Action's `:sets` names the settings it
  supplied that the Job keeps. This is the only place that rule lives."
  [steps]
  (reduce (fn [{:keys [settings set-by] :as acc} {:keys [job-settings action location] :as step}]
            (let [results (for [[k v] job-settings] [k (add-setting settings set-by k v)])
                  conflicts (for [[_ {:keys [gap]}] results :when gap] (located-gap location gap))
                  kept (vec (for [[k {:keys [gap]}] results :when (not gap)] k))]
              (-> acc
                  (update :steps conj
                          (cond-> step
                            (seq kept) (assoc-in [:action :sets] kept)
                            (seq conflicts) (-> (update :gaps into conflicts)
                                                (update-in [:action :gap-count] + (count conflicts)))))
                  (update :settings into (for [[k {:keys [value gap]}] results :when (not gap)] [k value]))
                  (assoc :set-by (merge (zipmap kept (repeat action)) set-by)))))
          {:steps [] :settings {} :set-by {}}
          steps))

(defn- convert-job [workflow-path [job-id job]]
  (let [id (name job-id)
        {:keys [steps settings]} (->> (:steps job)
                                      (map-indexed #(convert-step workflow-path id %1 %2))
                                      resolve-job-settings)]
    {:job (cond-> {:id id}
            (some? (:runs-on job)) (assoc :runs-on (runner-label (:runs-on job)))
            true (assoc :script (job-script steps))
            (some :action steps) (assoc :actions (into [] (keep :action) steps))
            (seq settings) (assoc :action-job-settings settings))
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
