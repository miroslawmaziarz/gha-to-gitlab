(ns gha-to-gitlab.variables
  "Variables: a Workflow's, Job's or Step's `env:` as CI Model Variables,
  `${{ env.X }}` and `${{ secrets.X }}` references as Variable references,
  and one Gap per referenced Secret (ADR-0007). GitLab has no Step, so a
  Step's `env:` entry is lifted into its Job only when that changes no
  Step's value, and is a Gap otherwise."
  (:require [clojure.string :as str]
            [gha-to-gitlab.gap :as gap]
            [gha-to-gitlab.wording :as wording])
  (:import (java.math BigDecimal BigInteger)))

(def ^:private reference
  "A whole `${{ env.X }}`, `${{ secrets.X }}` or `${{ matrix.x }}`
  expression; group 1 is the context, group 2 the name."
  #"\$\{\{\s*(env|secrets|matrix)\.([A-Za-z_][A-Za-z0-9_-]*)\s*\}\}")

(defn variable-name
  "Name `n` (an `env:` name or a Matrix key) as a GitLab Variable name,
  which allows only letters, digits and `_`: every other character becomes
  `_` (`node-version` → `node_version`)."
  [n]
  (str/replace n #"[^A-Za-z0-9_]" "_"))

(defn- variable-reference [[_ _ n]]
  (str "${" (variable-name n) "}"))

(defn rewrite-references
  "`s` with every `${{ env.X }}`, `${{ secrets.X }}` and `${{ matrix.x }}`
  as the Variable reference `${X}` (see `variable-name`), which the shell
  expands in script lines; other expressions are left as they are."
  [s]
  (str/replace s reference variable-reference))

(defn matrix-keys
  "The Matrix keys `s` references (`${{ matrix.x }}`), each once, in order."
  [s]
  (into [] (comp (filter #(= "matrix" (second %))) (map #(nth % 2)) (distinct)) (re-seq reference (str s))))

(def ^:private reference-or-dollar
  (re-pattern (str reference "|\\$")))

(defn- variable-value
  "An `env:` value as a GitLab Variable value: references rewritten as in
  `rewrite-references`, and every other `$` escaped as `$$`, since GitLab
  expands Variables in Variable values and GitHub does not."
  [s]
  (str/replace s reference-or-dollar #(if (second %) (variable-reference %) "$$")))

(defn plain-scalar-text
  "The text GitHub reads from an unquoted YAML scalar written as `raw`,
  resolving it by YAML 1.2's core schema: a null is \"\", a boolean
  \"true\" or \"false\", a number its canonical form (`010` → \"10\",
  `3.10` → \"3.1\"), and anything else (`yes`, `2024-01-01`, `1:30`) the
  text as written."
  [raw]
  (let [s (str/trim raw)]
    (condp re-matches s
      #"null|Null|NULL|~" ""
      #"true|True|TRUE" "true"
      #"false|False|FALSE" "false"
      #"[-+]?[0-9]+" (str (BigInteger. (str/replace s #"^\+" "")))
      #"0o([0-7]+)" :>> #(str (BigInteger. ^String (second %) 8))
      #"0x([0-9a-fA-F]+)" :>> #(str (BigInteger. ^String (second %) 16))
      #"[-+]?(\.[0-9]+|[0-9]+(\.[0-9]*)?)([eE][-+]?[0-9]+)?"
      (.toPlainString (.stripTrailingZeros (BigDecimal. (str/replace s #"^\+|\.$" ""))))
      s)))

(defn env->variables
  "The Variables of an `env:` map whose values are the texts GitHub reads,
  name → GitLab Variable value."
  [env]
  (into {} (map (fn [[k v]] [(name k) (variable-value v)])) env))

(defn expression-env-gap
  "The Gap for an `env:` at `location` that is an expression, not a map."
  [location env]
  (gap/at location (str "its `env:` is the expression `" env "`, not a list of Variables; set its Variables by hand.")))

(defn- conflict
  "What else gives Variable `var-name` a value other than `value` for the
  Step at `index`, as text (\"the Job's `env:` sets it to `x`\"); nil when
  nothing does. When the Job inherits the name (its own `env:`, else the
  Workflow's), that value decides alone, as lifting the entry would replace
  it. Otherwise every other Step must set the name to the same value, as
  lifting the entry would also give it to them; a Step that sets another
  value is named before one that does not set it."
  [{:keys [workflow job]} steps index var-name value]
  (if-let [[level vars] (first (filter #(contains? (second %) var-name) [["Job" job] ["Workflow" workflow]]))]
    (let [inherited (get vars var-name)]
      (when (not= inherited value)
        (format "the %s's `env:` sets it to `%s`" level inherited)))
    (let [others (keep-indexed (fn [i step] (when (not= i index) step)) steps)
          label (comp :step :location)]
      (or (first (for [step others
                       :let [other (get (:variables step) var-name)]
                       :when (and (some? other) (not= other value))]
                   (format "Step `%s` sets it to `%s`" (label step) other)))
          (first (for [step others
                       :when (not (contains? (:variables step) var-name))]
                   (format "Step `%s` does not set it" (label step))))))))

(defn- conflict-gap
  "The Gap of a Step entry `var-name` = `value` that cannot be lifted,
  because of `text` (see `conflict`)."
  [location var-name value text]
  (gap/at location (str "its `env:` sets `" var-name "` to `" value "`, but " text
                        "; a GitLab Job's Variables reach all its script lines, so set it for this Step's lines by hand.")))

(defn job-variables
  "A Job's Variables from its own `env:` Variables (`:job`) and its Steps',
  given the Workflow's (`:workflow`). `steps` are every Step of the Job, in
  order, as `{:location .. :variables {..}}`: the Step's place in the Source
  as a Gap location and its `env:` Variables. A Step's entry is lifted into
  the Job only when no Step's value changes by it (see `conflict`), and is a
  Gap at the Step otherwise; an entry the Job already inherits with the same
  value is not repeated. Returns `{:variables {..} :gaps [..]}`."
  [{:keys [workflow job] :as levels} steps]
  (let [inherited (merge workflow job)
        outcomes (for [[index {:keys [location variables]}] (map-indexed vector steps)
                       [var-name value] (sort variables)]
                   (if-let [text (conflict levels steps index var-name value)]
                     {:gap (conflict-gap location var-name value text)}
                     {:lifted [var-name value]}))]
    {:variables (into (or job {})
                      (comp (keep :lifted) (remove (fn [[var-name value]] (= value (get inherited var-name)))))
                      outcomes)
     :gaps (into [] (keep :gap) outcomes)}))

(defn- secret-names
  "The names of the Secrets `s` references (`${{ secrets.X }}`), in order."
  [s]
  (into [] (comp (filter #(= "secrets" (second %))) (map #(nth % 2))) (re-seq reference (str s))))

(defn- users-text
  "Where a Secret is used, from `users` (`{:job id}`, or `{}` for the
  Workflow's `env:`): \"the Workflow's `env:` and Jobs `a`, `b`\"."
  [users]
  (let [jobs (into [] (comp (keep :job) (distinct)) users)]
    (str/join " and " (cond-> []
                        (some (comp nil? :job) users) (conj "the Workflow's `env:`")
                        (seq jobs) (conj (str (if (= 1 (count jobs)) "Job " "Jobs ") (wording/backticked jobs)))))))

(defn- secret-gap
  "The Gap telling a person to create Secret `secret-name` on the Target;
  `GITHUB_TOKEN`, which GitHub makes for each run, gets its own advice."
  [workflow-path secret-name users]
  (gap/at {:workflow workflow-path :secret secret-name}
          (str "Secret `" secret-name "`, used by " (users-text users) ", "
               (if (= "GITHUB_TOKEN" secret-name)
                 (str "is the token GitHub creates for each run, and GitLab has none by that name;"
                      " create a masked Variable `GITHUB_TOKEN` on the Target holding a GitHub token"
                      " with the access the Jobs need, or switch them to `CI_JOB_TOKEN`.")
                 (str "cannot be read from the Source; create it on the Target as a masked Variable `"
                      secret-name "`.")))))

(defn secret-gaps
  "One Gap per Secret referenced in the Workflow at `workflow-path`, in
  order of first use. `uses` are `{:job id :texts [..]}`, the Source texts
  (commands, `env:` values) of one Job, or of the Workflow's `env:` when
  `:job` is nil."
  [workflow-path uses]
  (let [refs (for [{:keys [texts] :as source} uses
                   text texts
                   secret-name (secret-names text)]
               [secret-name (select-keys source [:job])])
        users (group-by first refs)]
    (into []
          (comp (map first) (distinct) (map #(secret-gap workflow-path % (map second (users %)))))
          refs)))
