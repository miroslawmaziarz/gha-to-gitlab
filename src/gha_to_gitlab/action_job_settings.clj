(ns gha-to-gitlab.action-job-settings
  "Action Job settings: the Job settings that Action Translators ask for
  (such as a setup Action's image), settled per Job by one rule and applied
  after the Runner Mapping (ADR-0002, ADR-0004). The Source reader hands
  `settle` each Step's requests in order and keeps the settled settings on
  the Job as `:action-job-settings`; `apply-settings` then puts
  them on the Job."
  (:require [gha-to-gitlab.ci-model :as ci-model]
            [gha-to-gitlab.wording :as wording]))

(def ^:private list-settings
  "Job settings that hold a list each Action adds its entries to, instead of
  one value; per setting, the most entries a Job can hold (the CI Model
  records GitLab's limits)."
  {:cache ci-model/caches-per-job-limit})

(def ^:private map-settings
  "Job settings that hold a map each Action adds its entries to, instead of
  one value; an entry two Actions set differently keeps the earlier
  Action's value."
  #{:variables})

(def ^:private conflict-hints
  "Per Job setting: what to do when two Actions set it to different values."
  {:image "use one image that has everything the Job needs, or split the Job."
   :artifacts (str "GitLab keeps one artifacts archive per Job, so add these paths to it"
                   " or upload them from another Job by hand.")})

(defn- conflict-gap
  "The Gap of an Action asking for `setting` = `value` when the Job keeps
  `kept`, set by the Step `set-by` names or, when it is nil, by `env:`."
  [setting value set-by kept]
  {:message (str "sets the Job's `" (name setting) "` to `" (wording/yaml-ish value) "`, but "
                 (if-let [{:keys [step action]} set-by]
                   (str "Step `" step "` (Action `" action "`) already set it")
                   "`env:` already sets it")
                 " to `" (wording/yaml-ish kept) "`, which the Job keeps; "
                 (get conflict-hints setting "settle on one value by hand."))})

(defn- clashes
  "The entries of map `v` that map `old` holds with another value."
  [old v]
  (into {} (filter (fn [[n x]] (and (contains? old n) (not= x (get old n))))) v))

(defn- too-many-gap [setting limit]
  {:message (str "adds the " (wording/ordinal (inc limit)) " entry to the Job's `" (name setting)
                 "`, but GitLab allows at most " limit
                 "; the Job leaves it out, so merge it into another entry by hand.")})

(defn- add-setting
  "What happens when an Action asks for setting `k` = `v` on top of the
  `settings` settled so far (`set-by`: setting → the request that set it)
  and the Job's `own` settings from `env:`, which a map setting's entries
  may not contradict:
  `{:value v'}` is the setting's new value, or `{:gap ..}` when the Job
  keeps the old one."
  [own settings set-by k v]
  (let [old (get settings k)
        own-clashes (when (map-settings k) (clashes (get own k) v))]
    (if-let [limit (list-settings k)]
      (if (< limit (+ (count old) (count v)))
        {:gap (too-many-gap k limit)}
        {:value (into (vec old) v)})
      (cond
        (seq own-clashes) {:gap (conflict-gap k own-clashes nil (select-keys (get own k) (keys own-clashes)))}
        (not (contains? settings k)) {:value v}
        (map-settings k)
        (let [action-clashes (clashes old v)]
          (if (seq action-clashes)
            {:gap (conflict-gap k action-clashes (get set-by k) (select-keys old (keys action-clashes)))}
            {:value (merge v old)}))
        (= v old) {:value old}
        :else {:gap (conflict-gap k v (get set-by k) old)}))))

(defn settle
  "Settles one Job's Action Job settings. `own` are the settings the Job
  has from the Source itself, such as the `:variables` its `env:` gives it
  (none when left out): an Action's map-setting entry that contradicts one
  of them is left out with a Gap, and they are not part of the returned
  settings. `requests` are the Job's Steps in order, each `{:step label :action uses-value :settings {setting value}}`
  (`:settings` may be empty). The earliest Action to set a setting wins, and
  a later Action asking for a different value gets a Gap; a list setting
  (`list-settings`, such as `:cache`) collects every Action's entries up to
  its limit, and a map setting (such as `:variables`) combines them unless
  an entry clashes. Returns `{:settings {setting value} :results [..]}`, one
  result per request, in order: `{:kept [setting ..] :gaps [{:message ..}]}`,
  the settings the Job kept from it and the Gaps it raised (their messages
  continue a sentence that names the Step and Action)."
  ([requests] (settle {} requests))
  ([own requests]
   (-> (reduce (fn [{:keys [settings set-by] :as acc} {:keys [step action] :as request}]
                 (let [outcomes (for [[k v] (:settings request)] [k (add-setting own settings set-by k v)])
                       kept (vec (for [[k {:keys [gap]}] outcomes :when (not gap)] k))]
                   (-> acc
                       (update :results conj {:kept kept :gaps (into [] (keep (comp :gap second)) outcomes)})
                       (update :settings into (for [[k {:keys [value gap]}] outcomes :when (not gap)] [k value]))
                       (assoc :set-by (merge (zipmap kept (repeat {:step step :action action})) set-by)))))
               {:settings {} :set-by {} :results []}
               requests)
       (dissoc :set-by))))

(defn- apply-to-job
  "Puts the Job's settled Action Job settings on it; Variables are added to
  the Job's own, which win."
  [{settings :action-job-settings :as job}]
  (if (seq settings)
    (cond-> (merge (dissoc job :action-job-settings) (dissoc settings :variables))
      (:variables settings) (assoc :variables (merge (:variables settings) (:variables job)))
      (and (:image settings) (:image job)) (assoc :runner-mapping-image (:image job)))
    (dissoc job :action-job-settings)))

(defn apply-settings
  "Puts every Job's settled `:action-job-settings` on the Job and removes
  them; run after the Runner Mapping, so an Action's image replaces the
  Runner Mapping image. That replaced image is kept as
  `:runner-mapping-image` so the Migration Report can show the override."
  [model]
  (update model :jobs (partial mapv apply-to-job)))
