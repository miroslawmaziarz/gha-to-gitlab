(ns gha-to-gitlab.conditions
  "Conditions: a Job's `if:` expression as GitLab rules, when it only
  compares the ref, branch or Trigger, with the status functions as the
  rule's `when` (ADR-0010). A Job also gets the conditions of the Jobs it
  needs, as GitHub skips it when it skips them. Anything else is a Gap."
  (:require [clojure.string :as str]
            [gha-to-gitlab.ci-model :as ci-model]
            [gha-to-gitlab.gap :as gap]
            [gha-to-gitlab.wording :as wording]))

(defn- unsupported
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
        (when-not m (unsupported (str "`" (first s) "` is not part of an expression this can read")))
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
    (unsupported (str "`" op "` is missing")))
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
  "A string, number, name, call or parenthesised expression: `[e rest]`."
  [[[kind value :as token] & more]]
  (case kind
    :string [[:string value] more]
    :number [[:number value] more]
    :name (if (= [:op "("] (first more))
            (let [[args after] (parse-arguments (rest more))]
              [[:call (str/lower-case value) args] after])
            [[:name value] more])
    :op (if (= "(" value)
          (let [[e after] (parse-or more)]
            [e (expect after ")")])
          (unsupported (str "`" value "` is not where this can read it")))
    (unsupported (if token (str "`" value "` is not where this can read it") "the expression ends too early"))))

(defn- parse-comparison
  "An operand, or two compared with `==` or `!=`: `[e rest]`."
  [ts]
  (let [[left more] (parse-primary ts)
        [kind op] (first more)]
    (cond
      (and (= :op kind) (#{"==" "!="} op))
      (let [[right after] (parse-primary (rest more))]
        [[:compare op left right] after])
      (and (= :op kind) (#{"<" ">" "<=" ">="} op))
      (unsupported (str "GitLab rules cannot compare with `" op "`"))
      :else [left more])))

(defn- parse-not
  "A comparison, or `!` before one: `[e rest]`."
  [ts]
  (if (= [:op "!"] (first ts))
    (let [[e more] (parse-not (rest ts))]
      [[:not e] more])
    (parse-comparison ts)))

(defn- parse-chain
  "`parse-operand` operands joined by `op` as `[kind operands..]`."
  [ts op kind parse-operand]
  (loop [[e more] (parse-operand ts) operands []]
    (let [operands (conj operands e)]
      (if (= [:op op] (first more))
        (recur (parse-operand (rest more)) operands)
        [(if (= 1 (count operands)) e (into [kind] operands)) more]))))

(defn- parse-and [ts] (parse-chain ts "&&" :and parse-not))

(defn- parse-or [ts] (parse-chain ts "||" :or parse-and))

(defn- parse
  "The syntax tree of GitHub expression `text`, with or without `${{ }}`."
  [text]
  (let [inner (or (second (re-matches #"(?s)\s*\$\{\{(.*)\}\}\s*" text)) text)
        [e more] (parse-or (tokens inner))]
    (when (seq more) (unsupported (str "`" (second (first more)) "` is not where this can read it")))
    e))

;; GitLab conditions: [:compare var op value], [:set var], [:unset var],
;; [:and ..], [:or ..]

(def ^:private trigger-sources
  "GitHub Trigger name → the GitLab Pipeline source of the Pipelines it
  becomes (ADR-0008)."
  {"push" "push"
   "pull_request" "merge_request_event"
   "schedule" "schedule"
   "workflow_dispatch" "web"})

(def ^:private name-variables
  "GitHub context values that are a GitLab predefined Variable as they
  are."
  {"github.ref_name" "$CI_COMMIT_REF_NAME"
   "github.head_ref" "$CI_MERGE_REQUEST_SOURCE_BRANCH_NAME"
   "github.base_ref" "$CI_MERGE_REQUEST_TARGET_BRANCH_NAME"})

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
  (when (re-find #"[\"\\]" s) (unsupported (str "`" s "` has a quote or backslash")))
  (str "\"" s "\""))

(defn- ref-comparison
  "`github.ref` compared (`op`) with `ref-text`: a branch or tag comparison."
  [op ref-text]
  (cond
    (str/starts-with? ref-text "refs/heads/") [:compare "$CI_COMMIT_BRANCH" op (quoted (subs ref-text 11))]
    (str/starts-with? ref-text "refs/tags/") [:compare "$CI_COMMIT_TAG" op (quoted (subs ref-text 10))]
    :else (unsupported (str "`github.ref` is compared with `" ref-text "`, which names no branch (`refs/heads/`) or tag (`refs/tags/`)"))))

(defn- compare-value
  "Context value `context` (such as `github.ref`) compared (`op`) with the
  string `value`."
  [op context value]
  (let [condition (case context
                    "github.ref" (ref-comparison "==" value)
                    "github.event_name" (if-let [source (trigger-sources value)]
                                          [:compare "$CI_PIPELINE_SOURCE" "==" (quoted source)]
                                          (unsupported (str "the Trigger `" value "` has no GitLab Pipeline source")))
                    "github.ref_type" (case value
                                        "tag" [:set "$CI_COMMIT_TAG"]
                                        "branch" [:set "$CI_COMMIT_BRANCH"]
                                        (unsupported (str "`github.ref_type` is never `" value "`")))
                    (if-let [variable (name-variables context)]
                      [:compare variable "==" (quoted value)]
                      (unsupported (str "`" context "` has no GitLab Variable"))))]
    (cond-> condition (= "!=" op) negate)))

(def ^:private status-functions
  "GitHub's status functions → the GitLab rule `when` they become, nil
  for `success()`, which a Job without one already means."
  {"success" nil
   "always" "always"
   "failure" "on_failure"
   "cancelled" ::cancelled})

(defn- prefix-match
  "`variable` matching the regex of everything starting with `prefix`; a
  `variable` that is set when `prefix` is empty."
  [variable prefix]
  (if (= "" prefix)
    [:set variable]
    [:compare variable "=~" (str "/^" (wording/regex-literal prefix) "/")]))

(defn- starts-with
  "`startsWith(context, prefix)`: a branch or tag regex for `github.ref`,
  a regex on the Variable of a branch name."
  [[[context-kind context] [prefix-kind prefix] :as args]]
  (when-not (and (= 2 (count args)) (= :name context-kind) (= :string prefix-kind))
    (unsupported "GitLab rules can say `startsWith` only of a ref or branch name and a string"))
  (cond
    (not= "github.ref" context)
    (if-let [variable (name-variables context)]
      (prefix-match variable prefix)
      (unsupported (str "`" context "` has no GitLab Variable")))
    (str/starts-with? prefix "refs/heads/") (prefix-match "$CI_COMMIT_BRANCH" (subs prefix 11))
    (str/starts-with? prefix "refs/tags/") (prefix-match "$CI_COMMIT_TAG" (subs prefix 10))
    :else (unsupported (str "`" prefix "` names no branch (`refs/heads/`) or tag (`refs/tags/`) prefix"))))

(defn- condition
  "The GitLab condition of syntax tree `e`."
  [[kind & args :as e]]
  (case kind
    :compare (let [[op left right] args
                   [[name-kind context] [value-kind value]] (if (= :name (first left)) [left right] [right left])]
               (if (and (= :name name-kind) (= :string value-kind))
                 (compare-value op context value)
                 (unsupported "GitLab rules compare a Variable with a string only")))
    :and (into [:and] (map condition args))
    :or (into [:or] (map condition args))
    :not (negate (condition (first args)))
    :call (cond
            (= "startswith" (first args)) (starts-with (second args))
            (status-functions (first args))
            (unsupported (str "`" (first args) "()` decides when the Job runs, which a GitLab rule says only"
                              " beside the whole condition (`" (first args) "() && ..`)"))
            :else (unsupported (str "`" (first args) "()` has no GitLab rule")))
    :name (if-let [variable (name-variables (first args))]
            [:set variable]
            (unsupported (str "`" (first args) "` has no GitLab Variable")))
    (unsupported (str "`" (or (second e) (name kind)) "` is not a comparison GitLab rules can make"))))

(defn- status
  "The rule `when` of one top-level `&&` operand `e` that is a status
  function call (`!cancelled()` counts as `always()`): `[:status when]`,
  or nil when `e` is no status function."
  [e]
  (let [cancelled [:call "cancelled" []]]
    (cond
      (= [:not cancelled] e) [:status "always"]
      (and (= :call (first e)) (contains? status-functions (second e)) (empty? (nth e 2)))
      (let [rule-when (status-functions (second e))]
        (when (= ::cancelled rule-when)
          (unsupported "GitLab rules cannot run a Job only when the Pipeline is cancelled"))
        [:status rule-when]))))

(defn- status-and-condition
  "Syntax tree `e` as `{:condition c :when w}`: the GitLab condition of
  everything but the status functions beside the whole condition, which
  become the rule's `when`; either may be nil."
  [e]
  (let [operands (if (= :and (first e)) (rest e) [e])
        {statuses true others false} (group-by (comp boolean status) operands)
        whens (into #{} (keep (comp second status)) statuses)]
    (when (< 1 (count whens))
      (unsupported "the condition has more than one status function"))
    {:condition (case (count others)
                  0 nil
                  1 (condition (first others))
                  (into [:and] (map condition others)))
     :when (first whens)}))

(defn parse-if
  "GitHub `if:` expression `text` as `{:condition c :when w}`: `c` the
  GitLab condition (nil when there is none), `w` the rule's `when` from
  the status functions (`\"always\"`, `\"on_failure\"`, `\"never\"`
  for `false`, nil); or `{:gap reason}` when GitLab rules cannot say it."
  [text]
  (try
    (let [e (parse text)]
      (case e
        [:name "true"] {:condition nil :when nil}
        [:name "false"] {:condition nil :when "never"}
        (status-and-condition e)))
    (catch clojure.lang.ExceptionInfo e
      (if-let [reason (::reason (ex-data e))]
        {:gap reason}
        (throw e)))))

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
  "Job `id`'s rules, given `settled` (Job id → `{:condition c :never? b}`,
  what keeps each Job it needs out of the Pipeline): see `job-rules`."
  [workflow-path settled {:keys [id needs] :as job}]
  (let [{:keys [gap] own-when :when own :condition} (some-> (:if job) parse-if)
        always? (= "always" own-when)
        excluding (fn [need] (let [{inherited-condition :condition :keys [never?]} (settled need)]
                               (or inherited-condition never?)))
        inherited (when-not always? (map settled needs))
        never? (or (= "never" own-when) (some :never? inherited))
        combined (when-not never? (conjoin (cons own (map :condition inherited))))
        rule-when (cond never? "never" (#{"always" "on_failure"} own-when) own-when)
        location {:workflow workflow-path :job id}]
    {:condition combined
     :never? never?
     :rules (some-> (if never? {:when "never"} (rule combined rule-when)) vector)
     :optional-needs (when always? (filterv excluding needs))
     :gaps (cond-> []
             gap (conj (gap/at location (str "its `if:` `" (:if job) "` has no GitLab rule (" gap
                                             "); the Job runs whenever the Pipeline does, so add its rules by hand.")))
             (not always?)
             (into (for [need needs
                         :when (:gap? (settled need))]
                     (gap/at location (str "needs Job `" need "`, whose `if:` has no GitLab rule; GitHub skips this Job"
                                           " whenever it skips `" need "`, so add `" need "`'s condition to its rules by hand.")))))
     :gap? (boolean gap)}))

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
