(ns gha-to-gitlab.triggers
  "Triggers: a Workflow's Triggers as GitLab workflow rules, so the Pipeline
  starts on the same Triggers, plus a Gap for each part GitLab rules cannot
  say (ADR-0008)."
  (:require [clojure.string :as str]
            [gha-to-gitlab.gap :as gap]
            [gha-to-gitlab.wording :as wording]))

(defn- literal
  "Character `c` as it matches itself in a GitLab regex (see
  `wording/regex-literal`)."
  [c]
  (wording/regex-literal (str c)))

(defn- class-token
  "The token of character class `[inside]`: `[:atom regex]`, or `[:gap
  reason]` when GitHub's meaning is unsure (a leading `^`) or the class
  holds `/`, which GitHub's refs keep apart."
  [inside]
  (cond
    (= \^ (first inside)) [:gap "`[^` may negate the class on GitHub"]
    (some #{\/} inside) [:gap "`[..]` holds `/`"]
    :else [:atom (str "[" (str/replace (str/join inside) #"\\" "\\\\\\\\") "]")]))

(defn- glob-tokens
  "The tokens of GitHub filter glob `glob`: `[:atom regex]` for what matches
  one character, `[:wildcard regex]` for `*` and `**`, `[:repeat c]` for `?`
  (the previous character zero or one time) and `+` (one or more times),
  or `[:gap reason]` for what has no regex form."
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
               (recur (rest after) (conj tokens (class-token inside)))
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
    (or (some (fn [[kind reason]] (when (= :gap kind) {:gap reason})) tokens)
        (some (fn [[[before] [kind c]]]
                (when (= :repeat kind)
                  (case before
                    :atom nil
                    :wildcard {:gap (str "`" c "` follows a wildcard")}
                    {:gap (str "`" c "` repeats nothing")})))
              (map vector (cons nil tokens) tokens))
        {:regex (str "/^" (str/join (map second tokens)) "$/")})))

(def ^:private push-source
  "The rule condition of Pipelines started by a push."
  "$CI_PIPELINE_SOURCE == \"push\"")

(def ^:private merge-request-source
  "The rule condition of merge request Pipelines."
  "$CI_PIPELINE_SOURCE == \"merge_request_event\"")

(def ^:private schedule-source
  "The rule condition of Pipelines a Pipeline Schedule starts."
  "$CI_PIPELINE_SOURCE == \"schedule\"")

(def ^:private web-source
  "The rule condition of Pipelines started with Run pipeline."
  "$CI_PIPELINE_SOURCE == \"web\"")

(defn- trigger-gap
  "The Gap at Trigger `trigger` of the Workflow at `workflow-path`."
  [workflow-path trigger text]
  (gap/at {:workflow workflow-path :trigger trigger} text))

(defn- pattern-rules
  "The rules for the filter `filter-key` (such as `branches`) with
  `patterns`, on ref Variable `ref-var`, for Pipelines matching `source`.
  GitHub lets the last matching pattern decide and a `!` pattern exclude,
  and a GitLab rule list stops at the first match, so the rules come in
  reverse order, with `when: never` for `!` patterns. A pattern GitLab
  cannot match is left out with a Gap. One pattern written alone counts
  as a list of it."
  [{:keys [workflow-path trigger source]} filter-key ref-var patterns]
  (let [outcomes (for [pattern (reverse (map str (if (sequential? patterns) patterns [patterns])))
                       :let [negated? (str/starts-with? pattern "!")
                             {:keys [regex gap]} (glob->regex (cond-> pattern negated? (subs 1)))]]
                   (if gap
                     {:gap (trigger-gap workflow-path trigger
                                        (str "has the `" (name filter-key) "` pattern `" pattern "`, which GitLab cannot match ("
                                             gap "); the rules leave it out, so add it by hand."))}
                     {:rule (cond-> {:if (str source " && " ref-var " =~ " regex)}
                              negated? (assoc :when "never"))}))]
    {:rules (into [] (keep :rule) outcomes)
     :gaps (into [] (keep :gap) outcomes)}))

(defn- ref-rules
  "The rules for one kind of ref (branches or tags) of a Trigger's filters
  `config`: its `:include` filter (such as `branches`) lists the refs that
  start the Pipeline, its `:ignore` filter (`branches-ignore`) those that
  do not, both tested on Variable `:ref-var`; nil when the Trigger filters
  neither."
  [context config {:keys [include ignore ref-var]}]
  (cond
    (contains? config include)
    (pattern-rules context include ref-var (get config include))
    (contains? config ignore)
    (let [{:keys [rules gaps]} (pattern-rules context ignore ref-var (get config ignore))]
      {:rules (conj (mapv #(assoc % :when "never") rules) {:if (str (:source context) " && " ref-var)})
       :gaps gaps})))

(defn- combine
  "One `{:rules .. :gaps ..}` of all of `parts`, in order."
  [& parts]
  {:rules (into [] (mapcat :rules) parts)
   :gaps (into [] (mapcat :gaps) parts)})

(def ^:private ref-triggers
  "The Triggers whose filters pick refs: the condition of the Pipelines they
  become, the kinds of ref they filter, and for `pull_request` the
  activity types GitHub starts a Workflow for when `types:` is left out (a
  GitLab merge request Pipeline starts for all of them)."
  {"push" {:source push-source
           :ref-kinds [{:include :branches :ignore :branches-ignore :ref-var "$CI_COMMIT_BRANCH"}
                       {:include :tags :ignore :tags-ignore :ref-var "$CI_COMMIT_TAG"}]}
   "pull_request" {:source merge-request-source
                   :ref-kinds [{:include :branches :ignore :branches-ignore
                                :ref-var "$CI_MERGE_REQUEST_TARGET_BRANCH_NAME"}]
                   :default-types #{"opened" "synchronize" "reopened"}}})

(defn- filter-gaps
  "Gaps for the filters of a ref Trigger that its rules ignore: path
  filters, and activity types other than the default ones."
  [{:keys [workflow-path trigger]} {:keys [default-types]} config]
  (concat
   (for [k [:paths :paths-ignore]
         :when (contains? config k)]
     (trigger-gap workflow-path trigger
                  (str "filters on `" (name k) "` `" (wording/yaml-ish (get config k))
                       "`; the Pipeline starts whatever a change touches, so add `rules: changes` to the Jobs by hand.")))
   (when (and default-types
              (contains? config :types)
              (not= default-types (set (map str (:types config)))))
     [(trigger-gap workflow-path trigger
                   (str "runs only for `types` `" (wording/yaml-ish (:types config))
                        "`; GitLab starts a merge request Pipeline whenever a merge request is opened or updated,"
                        " so limit the Jobs by hand if that matters."))])))

(defn- ref-trigger-rules
  "A ref Trigger (see `ref-triggers`): any Pipeline of its source, or, with
  ref filters, only the refs they let through. GitHub starts nothing for
  the kind of ref (branch or tag) that has no filter when the other has
  one, so neither do the rules."
  [context {:keys [source ref-kinds] :as ref-trigger} config]
  (let [context (assoc context :source source)
        parts (keep #(ref-rules context config %) ref-kinds)]
    (-> (if (seq parts)
          (apply combine parts)
          {:rules [{:if source}] :gaps []})
        (update :gaps into (filter-gaps context ref-trigger config)))))

(defn- schedule-rules
  "`schedule`: a rule for scheduled Pipelines, and a Gap per cron, as each
  becomes a Pipeline Schedule a person creates on the Target; one Gap
  when `crons` lists none."
  [{:keys [workflow-path trigger]} crons]
  (let [crons (when (sequential? crons) (keep #(when (map? %) (:cron %)) crons))]
    {:rules [{:if schedule-source}]
     :gaps (if (seq crons)
             (mapv #(trigger-gap workflow-path trigger
                                 (str "runs on cron `" % "` (UTC); create a Pipeline Schedule with it on the Target."))
                   crons)
             [(trigger-gap workflow-path trigger
                           "lists no `cron`; create the Pipeline Schedules it means on the Target by hand.")])}))

(defn- dispatch-rules
  "`workflow_dispatch`: a rule for Pipelines started with Run pipeline, and
  a Gap saying so, naming the inputs a person sets there as Variables."
  [{:keys [workflow-path trigger]} config]
  (let [inputs (map name (keys (:inputs config)))]
    {:rules [{:if web-source}]
     :gaps [(trigger-gap workflow-path trigger
                         (str "starts the Workflow by hand; on the Target that is Run pipeline"
                              (when (seq inputs)
                                (str ", and its inputs " (wording/backticked inputs) " are Variables to set there"))
                              "."))]}))

(defn- triggers
  "The Triggers in `on` (one Trigger, a list of them, or a map of Trigger →
  filters) as `[trigger-name filters]` pairs, in Source order."
  [on]
  (cond
    (map? on) (map (fn [[k v]] [(name k) v]) on)
    (sequential? on) (map (fn [t] [(str t) nil]) on)
    (some? on) [[(str on) nil]]))

(defn- trigger-rules
  "The rules and Gaps of Trigger `trigger` with filters `filters`."
  [workflow-path [trigger filters]]
  (let [context {:workflow-path workflow-path :trigger trigger}
        config (if (map? filters) filters {})]
    (if-let [ref-trigger (ref-triggers trigger)]
      (ref-trigger-rules context ref-trigger config)
      (case trigger
        "schedule" (schedule-rules context filters)
        "workflow_dispatch" (dispatch-rules context config)
        {:rules []
         :gaps [(trigger-gap workflow-path trigger "has no GitLab workflow rule; start the Pipeline for it by hand.")]}))))

(defn workflow-rules
  "The GitLab workflow rules for the Workflow at `workflow-path` with
  Triggers `on` (as written under `on:`), so the Pipeline starts on the
  same Triggers: `{:rules [{:if .. :when ..}] :gaps [..]}`. When there are
  Triggers but none becomes a rule, the one rule `{:when \"never\"}` keeps
  every Pipeline from starting, as GitLab starts all of them when there
  are no rules."
  [workflow-path on]
  (let [all (triggers on)
        {:keys [rules] :as converted} (apply combine (map #(trigger-rules workflow-path %) all))]
    (cond-> converted
      (and (empty? rules) (seq all)) (assoc :rules [{:when "never"}]))))
