(ns gha-to-gitlab.triggers
  "Triggers: a Workflow's `on:` as GitLab workflow rules, so the Pipeline
  starts on the same events, plus a Gap for each part GitLab rules cannot
  say (ADR-0008)."
  (:require [clojure.string :as str]
            [gha-to-gitlab.gap :as gap]
            [gha-to-gitlab.wording :as wording]))

(def ^:private regex-operators
  (set "\\.^$|?*+()[]{}/"))

(defn- literal
  "Character `c` as it matches itself in a GitLab regex: regex operators
  and `/`, which ends the regex, are escaped."
  [c]
  (if (regex-operators c) (str "\\" c) (str c)))

(defn- glob-tokens
  "The tokens of GitHub filter glob `glob`: `[:atom regex]` for what matches
  one character, `[:wildcard regex]` for `*` and `**`, `[:repeat c]` for `?`
  (the previous character zero or one time) and `+` (one or more times),
  or `[:gap reason]` for a `[` with no `]`."
  [glob]
  (loop [cs (seq glob) tokens []]
    (if-let [[c & more] cs]
      (case c
        \\ (recur (rest more) (conj tokens [:atom (literal (or (first more) \\))]))
        \* (if (= \* (first more))
             (recur (rest more) (conj tokens [:wildcard ".*"]))
             (recur more (conj tokens [:wildcard "[^\\/]*"])))
        (\? \+) (recur more (conj tokens [:repeat c]))
        \[ (let [[inside after] (split-with #(not= \] %) more)]
             (if (seq after)
               (recur (rest after) (conj tokens [:atom (str "[" (str/replace (str/join inside) #"[/\\]" "\\\\$0") "]")]))
               [[:gap "`[` has no closing `]`"]]))
        (recur more (conj tokens [:atom (literal c)])))
      tokens)))

(defn glob->regex
  "GitHub branch or tag filter glob `glob` as an anchored GitLab regex
  literal, `{:regex \"/^...$/\"}`, or `{:gap reason}` when GitLab cannot
  match it the same way. `*` matches anything but `/`, `**` anything, `?`
  and `+` repeat the character before them, `[..]` is a character class
  and `\\` escapes the next character, as in GitHub's filter patterns."
  [glob]
  (let [tokens (glob-tokens glob)]
    (or (some (fn [[kind x]] (when (= :gap kind) {:gap x})) tokens)
        (some (fn [[[before] [kind c]]]
                (when (= :repeat kind)
                  (case before
                    :atom nil
                    :wildcard {:gap (str "`" c "` follows a wildcard")}
                    {:gap (str "`" c "` repeats nothing")})))
              (map vector (cons nil tokens) tokens))
        {:regex (str "/^" (str/join (map second tokens)) "$/")})))

(def ^:private push-source "$CI_PIPELINE_SOURCE == \"push\"")
(def ^:private merge-request-source "$CI_PIPELINE_SOURCE == \"merge_request_event\"")

(defn- trigger-gap [workflow-path event text]
  (gap/at {:workflow workflow-path :trigger event} (str "Trigger `" event "` " text)))

(defn- pattern-rules
  "The rules for a filter list `patterns` (such as `branches:`) on ref
  Variable `ref-var`, for Pipelines matching `source`. GitHub lets the last
  matching pattern decide and a `!` pattern exclude, and a GitLab rule
  list stops at the first match, so the rules come in reverse order, with
  `when: never` for `!` patterns. A pattern GitLab cannot match is left
  out with a Gap."
  [{:keys [workflow-path event source]} filter-key ref-var patterns]
  (let [outcomes (for [pattern (reverse (map str patterns))
                       :let [negated? (str/starts-with? pattern "!")
                             {:keys [regex gap]} (glob->regex (cond-> pattern negated? (subs 1)))]]
                   (if gap
                     {:gap (trigger-gap workflow-path event
                                        (str "has the `" filter-key "` pattern `" pattern "`, which GitLab cannot match ("
                                             gap "); the rules leave it out, so add it by hand."))}
                     {:rule (cond-> {:if (str source " && " ref-var " =~ " regex)}
                              negated? (assoc :when "never"))}))]
    {:rules (into [] (keep :rule) outcomes)
     :gaps (into [] (keep :gap) outcomes)}))

(defn- ref-rules
  "The rules for one kind of ref (branches or tags) of an event's filters
  `config`: `include-key` (such as `branches`) lists the refs that start
  the Pipeline, `ignore-key` (`branches-ignore`) those that do not; nil
  when the event filters neither."
  [context config include-key ignore-key ref-var]
  (let [{:keys [source]} context]
    (cond
      (contains? config include-key)
      (pattern-rules context (name include-key) ref-var (get config include-key))
      (contains? config ignore-key)
      (let [{:keys [rules gaps]} (pattern-rules context (name ignore-key) ref-var (get config ignore-key))]
        {:rules (conj (mapv #(assoc % :when "never") rules) {:if (str source " && " ref-var)})
         :gaps gaps}))))

(defn- combine [& parts]
  {:rules (into [] (mapcat :rules) parts)
   :gaps (into [] (mapcat :gaps) parts)})

(defn- push-rules
  "`push`: any push, or, with branch or tag filters, only the refs they
  let through; GitHub starts no Pipeline for the kind of ref (branch or
  tag) that has no filter when the other has one."
  [context config]
  (let [parts (keep identity [(ref-rules context config :branches :branches-ignore "$CI_COMMIT_BRANCH")
                              (ref-rules context config :tags :tags-ignore "$CI_COMMIT_TAG")])]
    (if (seq parts)
      (apply combine parts)
      {:rules [{:if push-source}] :gaps []})))

(defn- pull-request-rules
  "`pull_request`: any merge request, or those whose target branch the
  branch filters let through."
  [context config]
  (or (ref-rules context config :branches :branches-ignore "$CI_MERGE_REQUEST_TARGET_BRANCH_NAME")
      {:rules [{:if merge-request-source}] :gaps []}))

(def ^:private default-pull-request-types
  "The `pull_request` activity types GitHub starts a Workflow for when
  `types:` is left out; a GitLab merge request Pipeline starts for all of
  them."
  #{"opened" "synchronize" "reopened"})

(defn- filter-gaps
  "Gaps for the filters of `event` that its rules ignore: path filters,
  and `pull_request` activity types other than the default ones."
  [{:keys [workflow-path event]} config]
  (concat
   (for [k [:paths :paths-ignore]
         :when (contains? config k)]
     (trigger-gap workflow-path event
                  (str "filters on `" (name k) "` `" (wording/yaml-ish (get config k))
                       "`; the Pipeline starts whatever a change touches, so add `rules: changes` to the Jobs by hand.")))
   (when (and (= "pull_request" event)
              (contains? config :types)
              (not= default-pull-request-types (set (map str (:types config)))))
     [(trigger-gap workflow-path event
                   (str "runs only for `types` `" (wording/yaml-ish (:types config))
                        "`; GitLab starts a merge request Pipeline whenever a merge request is opened or updated,"
                        " so limit the Jobs by hand if that matters."))])))

(defn- schedule-rules
  "`schedule`: a rule for scheduled Pipelines, and a Gap per cron, as each
  becomes a Pipeline Schedule a person creates on the Target."
  [{:keys [workflow-path event]} crons]
  {:rules [{:if "$CI_PIPELINE_SOURCE == \"schedule\""}]
   :gaps (vec (for [{:keys [cron]} (when (sequential? crons) crons)]
                (trigger-gap workflow-path event
                             (str "runs on cron `" cron "` (UTC); create a Pipeline Schedule with it on the Target."))))})

(defn- dispatch-rules
  "`workflow_dispatch`: a rule for Pipelines started with Run pipeline, and
  a Gap saying so, naming the inputs a person sets there as Variables."
  [{:keys [workflow-path event]} config]
  (let [inputs (map name (keys (:inputs config)))]
    {:rules [{:if "$CI_PIPELINE_SOURCE == \"web\""}]
     :gaps [(trigger-gap workflow-path event
                         (str "starts the Workflow by hand; on the Target that is Run pipeline"
                              (when (seq inputs)
                                (str ", and its inputs " (wording/backticked inputs) " are Variables to set there"))
                              "."))]}))

(defn- events
  "The events of `on:` (one event, a list of them, or a map of event →
  filters) as `[event-name filters]` pairs, in Source order."
  [on]
  (cond
    (map? on) (map (fn [[k v]] [(name k) v]) on)
    (sequential? on) (map (fn [e] [(str e) nil]) on)
    (some? on) [[(str on) nil]]))

(defn- trigger-rules
  "The rules and Gaps of the Trigger `event` with filters `raw-config`."
  [workflow-path [event raw-config]]
  (let [context {:workflow-path workflow-path :event event}
        config (if (map? raw-config) raw-config {})
        filtered (fn [rules] (update rules :gaps into (filter-gaps context config)))]
    (case event
      "push" (filtered (push-rules (assoc context :source push-source) config))
      "pull_request" (filtered (pull-request-rules (assoc context :source merge-request-source) config))
      "schedule" (schedule-rules context raw-config)
      "workflow_dispatch" (dispatch-rules context config)
      {:rules []
       :gaps [(trigger-gap workflow-path event "has no GitLab workflow rule; start the Pipeline for it by hand.")]})))

(defn workflow-rules
  "The GitLab workflow rules for the Workflow at `workflow-path` with
  Triggers `on` (the `on:` value), so the Pipeline starts on the same
  events: `{:rules [{:if .. :when ..}] :gaps [..]}`. When `on:` has
  Triggers but none becomes a rule, the one rule `{:when \"never\"}` keeps
  every Pipeline from starting, as GitLab starts all of them when there
  are no rules."
  [workflow-path on]
  (let [triggers (events on)
        {:keys [rules] :as converted} (apply combine (map #(trigger-rules workflow-path %) triggers))]
    (cond-> converted
      (and (empty? rules) (seq triggers)) (assoc :rules [{:when "never"}]))))
