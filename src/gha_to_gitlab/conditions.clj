(ns gha-to-gitlab.conditions
  "Conditions: a Job's `if:` expression as GitLab rules, when it only
  compares the ref, branch or Trigger, with the status functions as the
  rule's `when` (ADR-0010). A Job also gets the conditions of the Jobs it
  needs, as GitHub skips it when it skips them. Anything else is a Gap."
  (:require [clojure.string :as str]
            [gha-to-gitlab.ci-model :as ci-model]
            [gha-to-gitlab.gap :as gap]
            [gha-to-gitlab.triggers :as triggers]
            [gha-to-gitlab.wording :as wording]))

(defn- gap-reason!
  "Stops the conversion of a condition with Gap reason `reason`."
  [reason]
  (throw (ex-info reason {::reason reason})))

;; Tokens

(def ^:private token-pattern
  "One token of a GitHub expression: whitespace, a quoted string, an
  operator, a number or a name (`github.ref`, `always`)."
  #"\s+|'(?:[^']|'')*'|==|!=|&&|\|\||<=|>=|[!()<>,\[\]]|-?\d+(?:\.\d+)?|[A-Za-z_][A-Za-z0-9_.\-*]*")

(defn- tokens
  "The tokens of expression `text`: `[:string s]`, `[:op s]`,
  `[:number s]` or `[:name s]`."
  [text]
  (loop [s text found []]
    (if (str/blank? s)
      found
      (let [m (re-find (re-pattern (str "^(?:" token-pattern ")")) s)]
        (when-not m (gap-reason! (str "`" (first s) "` is not part of an expression this can read")))
        (recur (subs s (count m))
               (cond
                 (str/blank? m) found
                 (str/starts-with? m "'") (conj found [:string (str/replace (subs m 1 (dec (count m))) "''" "'")])
                 (re-matches #"-?\d.*" m) (conj found [:number m])
                 (re-matches #"[A-Za-z_].*" m) (conj found [:name m])
                 :else (conj found [:op m])))))))

;; Parsing: or → and → not → comparison → primary

(declare ^:private parse-or)

(defn- expect
  "The tokens after operator `op`, which must come first."
  [[[kind value] & more] op]
  (when-not (and (= :op kind) (= op value))
    (gap-reason! (str "`" op "` is missing")))
  more)

(defn- parse-arguments
  "The arguments of a function call after its `(`: `[args rest]`."
  [ts]
  (if (= [:op ")"] (first ts))
    [[] (rest ts)]
    (loop [ts ts args []]
      (let [[arg more] (parse-or ts)]
        (if (= [:op ","] (first more))
          (recur (rest more) (conj args arg))
          [(conj args arg) (expect more ")")])))))

(defn- parse-primary
  "A string, number, name, call or parenthesised expression: `[tree rest]`."
  [[[kind value :as token] & more]]
  (case kind
    :string [[:string value] more]
    :number [[:number value] more]
    :name (if (= [:op "("] (first more))
            (let [[args after] (parse-arguments (rest more))]
              [[:call (str/lower-case value) args] after])
            [[:name value] more])
    :op (if (= "(" value)
          (let [[tree after] (parse-or more)]
            [tree (expect after ")")])
          (gap-reason! (str "`" value "` is not where this can read it")))
    (gap-reason! (if token (str "`" value "` is not where this can read it") "the expression ends too early"))))

(defn- parse-comparison
  "An operand, or two compared with `==` or `!=`: `[tree rest]`."
  [ts]
  (let [[left more] (parse-primary ts)
        [kind op] (first more)]
    (cond
      (and (= :op kind) (#{"==" "!="} op))
      (let [[right after] (parse-primary (rest more))]
        [[:compare op left right] after])
      (and (= :op kind) (#{"<" ">" "<=" ">="} op))
      (gap-reason! (str "GitLab rules cannot compare with `" op "`"))
      :else [left more])))

(defn- parse-not
  "A comparison, or `!` before one: `[tree rest]`."
  [ts]
  (if (= [:op "!"] (first ts))
    (let [[tree more] (parse-not (rest ts))]
      [[:not tree] more])
    (parse-comparison ts)))

(defn- parse-chain
  "`parse-operand` operands joined by `op` as `[kind operands..]`."
  [ts op kind parse-operand]
  (loop [[tree more] (parse-operand ts) operands []]
    (let [operands (conj operands tree)]
      (if (= [:op op] (first more))
        (recur (parse-operand (rest more)) operands)
        [(if (= 1 (count operands)) tree (into [kind] operands)) more]))))

(defn- parse-and
  "Operands joined by `&&`: `[tree rest]`."
  [ts]
  (parse-chain ts "&&" :and parse-not))

(defn- parse-or
  "Operands joined by `||`, the whole of an expression: `[tree rest]`."
  [ts]
  (parse-chain ts "||" :or parse-and))

(defn- parse
  "The syntax tree of GitHub expression `text`, with or without `${{ }}`."
  [text]
  (let [inner (or (second (re-matches #"(?s)\s*\$\{\{(.*)\}\}\s*" text)) text)
        [tree more] (parse-or (tokens inner))]
    (when (seq more) (gap-reason! (str "`" (second (first more)) "` is not where this can read it")))
    tree))

;; GitLab conditions: [:compare var op value], [:set var], [:unset var],
;; [:and ..], [:or ..]

(def ^:private name-variables
  "GitHub context values that are a GitLab predefined Variable as they
  are."
  {"github.head_ref" "$CI_MERGE_REQUEST_SOURCE_BRANCH_NAME"
   "github.base_ref" "$CI_MERGE_REQUEST_TARGET_BRANCH_NAME"})

(def ^:private ref-name-variables
  "The Variables `github.ref_name` is one of: a branch or tag name. Both
  are unset in a merge request Pipeline, where GitHub's `ref_name` is
  `N/merge` and matches no branch name either."
  ["$CI_COMMIT_BRANCH" "$CI_COMMIT_TAG"])

(def ^:private ref-prefixes
  "The `github.ref` prefixes that name a branch or tag, and the Variable
  holding the rest."
  [["refs/heads/" "$CI_COMMIT_BRANCH"]
   ["refs/tags/" "$CI_COMMIT_TAG"]])

(defn- split-ref
  "`github.ref` text `ref-text` as `[variable rest]` (see `ref-prefixes`),
  or a Gap reason naming `what`."
  [ref-text what]
  (or (some (fn [[prefix variable]]
              (when (str/starts-with? ref-text prefix) [variable (subs ref-text (count prefix))]))
            ref-prefixes)
      (gap-reason! (str what " names no branch (`refs/heads/`) or tag (`refs/tags/`)"))))

(defn- either
  "`make` applied to each Variable of `variables`, joined by `||`."
  [variables make]
  (into [:or] (map make) variables))

(defn- negate
  "GitLab condition `condition` negated; GitLab rules have no `!`, so the
  negation is pushed into the comparisons."
  [[kind & args :as condition]]
  (case kind
    :compare (let [[variable op value] args]
               [:compare variable ({"==" "!=" "!=" "==" "=~" "!~" "!~" "=~"} op) value])
    :set (into [:unset] args)
    :unset (into [:set] args)
    :and (into [:or] (map negate args))
    :or (into [:and] (map negate args))
    (throw (ex-info "not a condition" {:condition condition}))))

(defn- quoted
  "`s` as a GitLab rule string."
  [s]
  (when (re-find #"[\"\\]" s) (gap-reason! (str "`" s "` has a quote or backslash")))
  (str "\"" s "\""))

(defn- compare-value
  "Context value `context` (such as `github.ref`) compared (`op`) with the
  string `value`."
  [op context value]
  (let [equal (case context
                "github.ref" (let [[variable rest-text] (split-ref value (str "`github.ref` is compared with `" value "`, which"))]
                               [:compare variable "==" (quoted rest-text)])
                "github.ref_name" (either ref-name-variables #(vector :compare % "==" (quoted value)))
                "github.event_name" (if-let [source (triggers/pipeline-sources value)]
                                      [:compare "$CI_PIPELINE_SOURCE" "==" (quoted source)]
                                      (gap-reason! (str "the Trigger `" value "` has no GitLab Pipeline source")))
                ;; GitHub's `ref_type` is `branch` in a pull request too,
                ;; where GitLab sets no `$CI_COMMIT_BRANCH`
                "github.ref_type" (case value
                                    "tag" [:set "$CI_COMMIT_TAG"]
                                    "branch" [:unset "$CI_COMMIT_TAG"]
                                    (gap-reason! (str "`github.ref_type` is never `" value "`")))
                (if-let [variable (name-variables context)]
                  [:compare variable "==" (quoted value)]
                  (gap-reason! (str "`" context "` has no GitLab Variable"))))]
    (cond-> equal (= "!=" op) negate)))

(def ^:private status-functions
  "GitHub's status functions → the GitLab rule `when` they become, nil
  for `success()`, which a Job without one already means."
  {"success" nil
   "always" "always"
   "failure" "on_failure"
   "cancelled" ::cancelled})

(defn- prefix-match
  "`variable` matching the regex of everything starting with `prefix`,
  ignoring case as GitHub's `startsWith` does; a `variable` that is set
  when `prefix` is empty."
  [variable prefix]
  (if (= "" prefix)
    [:set variable]
    [:compare variable "=~" (str "/^" (wording/regex-literal prefix) "/i")]))

(defn- starts-with
  "`startsWith(context, prefix)`: a branch or tag regex for `github.ref`
  and `github.ref_name`, a regex on the Variable of a branch name."
  [[[context-kind context] [prefix-kind prefix] :as args]]
  (when-not (and (= 2 (count args)) (= :name context-kind) (= :string prefix-kind))
    (gap-reason! "GitLab rules can say `startsWith` only of a ref or branch name and a string"))
  (case context
    "github.ref" (apply prefix-match (split-ref prefix (str "`" prefix "`")))
    "github.ref_name" (either ref-name-variables #(prefix-match % prefix))
    (if-let [variable (name-variables context)]
      (prefix-match variable prefix)
      (gap-reason! (str "`" context "` has no GitLab Variable")))))

(defn- condition
  "The GitLab condition of syntax tree `tree`."
  [[kind & args :as tree]]
  (case kind
    :compare (let [[op left right] args
                   [[name-kind context] [value-kind value]] (if (= :name (first left)) [left right] [right left])]
               (if (and (= :name name-kind) (= :string value-kind))
                 (compare-value op context value)
                 (gap-reason! "GitLab rules compare a Variable with a string only")))
    :and (into [:and] (map condition args))
    :or (into [:or] (map condition args))
    :not (negate (condition (first args)))
    :call (cond
            (= "startswith" (first args)) (starts-with (second args))
            (status-functions (first args))
            (gap-reason! (str "`" (first args) "()` decides when the Job runs, which a GitLab rule says only"
                              " beside the whole condition (`" (first args) "() && ..`)"))
            :else (gap-reason! (str "`" (first args) "()` has no GitLab rule")))
    :name (if-let [variable (name-variables (first args))]
            [:set variable]
            (gap-reason! (str "`" (first args) "` has no GitLab Variable")))
    (gap-reason! (str "`" (or (second tree) (name kind)) "` is not a comparison GitLab rules can make"))))

(defn- status
  "The rule `when` of one top-level `&&` operand `tree` that is a status
  function call (`!cancelled()` counts as `always()`): `[:status when]`,
  or nil when `tree` is no status function."
  [tree]
  (let [cancelled [:call "cancelled" []]]
    (cond
      (= [:not cancelled] tree) [:status "always"]
      (and (= :call (first tree)) (contains? status-functions (second tree)) (empty? (nth tree 2)))
      (let [rule-when (status-functions (second tree))]
        (when (= ::cancelled rule-when)
          (gap-reason! "GitLab rules cannot run a Job only when the Pipeline is cancelled"))
        [:status rule-when]))))

(defn- status-and-condition
  "Syntax tree `tree` as `{:condition c :when w}`: the GitLab condition of
  everything but the status functions beside the whole condition, which
  become the rule's `when`; either may be nil. A literal `true` beside
  it changes nothing, and a literal `false` beside it leaves the Job out
  (`when: never`)."
  [tree]
  (let [operands (remove #{[:name "true"]} (if (= :and (first tree)) (rest tree) [tree]))
        {statuses true others false} (group-by (comp boolean status) operands)
        whens (into #{} (keep (comp second status)) statuses)]
    (cond
      (some #{[:name "false"]} operands) {:condition nil :when "never"}
      (< 1 (count whens)) (gap-reason! "the condition has more than one status function")
      :else {:condition (case (count others)
                          0 nil
                          1 (condition (first others))
                          (into [:and] (map condition others)))
             :when (first whens)})))

(defn parse-if
  "GitHub `if:` expression `text` as `{:condition c :when w}`: `c` the
  GitLab condition (nil when there is none), `w` the rule's `when` from
  the status functions (`\"always\"`, `\"on_failure\"`, `\"never\"`
  for `false`, nil); or `{:gap reason}` when GitLab rules cannot say it."
  [text]
  (try
    (status-and-condition (parse text))
    (catch clojure.lang.ExceptionInfo ex
      (if-let [reason (::reason (ex-data ex))]
        {:gap reason}
        (throw ex)))))

(defn step-when
  "What the `if:` `text` of a Step (nil when it has none) asks of it, when
  it is only a status function: `{:when nil}` when it changes nothing
  (none, `success()`), `{:when \"always\"}` or `{:when \"on_failure\"}`;
  nil for any other `if:`, which GitLab cannot say of a Step."
  [text]
  (if (nil? text)
    {:when nil}
    (let [{:keys [gap] rule-if :condition rule-when :when} (parse-if text)]
      (when (and (not gap) (nil? rule-if) (contains? #{nil "always" "on_failure"} rule-when))
        {:when rule-when}))))

(defn- render
  "GitLab condition `c` as rule text; `nested?` when it is an operand of
  `&&`, where `||` needs parentheses."
  ([c] (render c false))
  ([[kind & args] nested?]
   (case kind
     :compare (str/join " " args)
     :set (first args)
     :unset (str (first args) " == null")
     :and (str/join " && " (map #(render % true) args))
     :or (cond-> (str/join " || " (map render args)) nested? (#(str "(" % ")"))))))

(defn- rule
  "The GitLab rule of condition `c` and `rule-when`, nil when it has
  neither."
  [c rule-when]
  (not-empty (cond-> {}
               c (assoc :if (render c))
               rule-when (assoc :when rule-when))))

(defn condition-rule
  "GitHub `if:` expression `text` as a GitLab rule `{:if .. :when ..}`
  (either key left out when it has none, `{}` when it has neither), or
  `{:gap reason}` when GitLab rules cannot say it."
  [text]
  (let [{:keys [gap] :as parsed} (parse-if text)]
    (if gap
      {:gap gap}
      (or (rule (:condition parsed) (:when parsed)) {}))))

(defn- conjoin
  "GitLab conditions `cs` (nils left out) joined by `&&`, each operand
  once; nil for none."
  [cs]
  (let [operands (into [] (comp (remove nil?) (mapcat #(if (= :and (first %)) (rest %) [%])) (distinct)) cs)]
    (case (count operands)
      0 nil
      1 (first operands)
      (into [:and] operands))))

(defn- settle-job
  "Job `id`'s rules, given `settled`, Job id → what was settled for each
  Job it needs: see `job-rules`. `:condition` and `:never?` are what keeps
  the Job out of the Pipeline, and `:unconverted` the Jobs (itself or
  those it needs, transitively) whose `if:` has no GitLab rule."
  [workflow-path settled {:keys [id needs] :as job}]
  (let [{:keys [gap] own-when :when own :condition} (some-> (:if job) parse-if)
        always? (= "always" own-when)
        sometimes-left-out? (fn [need] (let [{need-condition :condition :keys [never?]} (settled need)]
                                         (boolean (or need-condition never?))))
        inherited (when-not always? (map settled needs))
        never? (or (= "never" own-when) (some :never? inherited))
        combined (when-not never? (conjoin (cons own (map :condition inherited))))
        rule-when (cond never? "never" (#{"always" "on_failure"} own-when) own-when)
        unconverted (into (sorted-set) (mapcat :unconverted) inherited)
        location {:workflow workflow-path :job id}]
    {:condition combined
     :never? never?
     :unconverted (cond-> unconverted gap (conj id))
     :rules (some-> (if never? {:when "never"} (rule combined rule-when)) vector)
     :optional-needs (when always? (filterv sometimes-left-out? needs))
     :gaps (cond-> []
             gap (conj (gap/at location (str "its `if:` `" (:if job) "` has no GitLab rule (" gap
                                             "); the Job runs whenever the Pipeline does, so add its rules by hand.")))
             true (into (for [other unconverted]
                          (gap/at location (str "needs Job `" other "`, directly or through the Jobs it needs, and the `if:`"
                                                " of `" other "` has no GitLab rule; GitHub skips this Job whenever it skips `"
                                                other "`, so add `" other "`'s condition to its rules by hand.")))))}))

(defn job-rules
  "The GitLab rules of the Jobs of the Workflow at `workflow-path`: `jobs`
  are `{:id .. :needs [..] :if text}` (`:if` nil when the Job has none).
  A Job's rule is its own condition `&&` the conditions of the Jobs it
  needs, as GitHub skips a Job when it skips a Job it needs and GitLab
  refuses a Pipeline whose Job needs a Job that is left out; an
  `always()` Job inherits none and needs those Jobs optionally instead.
  Returns Job id → `{:rules [..] :optional-needs [..] :gaps [..]}`, each
  possibly empty."
  [workflow-path jobs]
  (let [levels (ci-model/dependency-levels jobs)
        settled (reduce (fn [settled job] (assoc settled (:id job) (settle-job workflow-path settled job)))
                        {}
                        (sort-by (comp levels :id) jobs))]
    (update-vals settled #(select-keys % [:rules :optional-needs :gaps]))))
