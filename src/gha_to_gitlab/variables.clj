(ns gha-to-gitlab.variables
  "Variables: a Workflow's, Job's or Step's `env:` as CI Model Variables.
  GitLab has no Step, so a Step's `env:` is lifted into its Job where no
  other value of the same name could be lost, and is a Gap otherwise."
  (:require [clojure.string :as str]
            [gha-to-gitlab.gap :as gap]))

(def ^:private reference
  "A whole `${{ env.X }}` or `${{ secrets.X }}` expression; group 1 is the
  context, group 2 the name."
  #"\$\{\{\s*(env|secrets)\.([A-Za-z_][A-Za-z0-9_]*)\s*\}\}")

(defn rewrite-references
  "`s` with every `${{ env.X }}` and `${{ secrets.X }}` as the Variable
  reference `${X}`, which GitLab expands in Variables and the shell in
  script lines; other expressions are left as they are."
  [s]
  (str/replace s reference (fn [[_ _ n]] (str "${" n "}"))))

(defn- variable-value
  "An `env:` value as the string a Job reads: YAML numbers and booleans as
  GitHub turns them into text, an empty value as \"\", and references
  rewritten (`rewrite-references`)."
  [v]
  (if (nil? v) "" (rewrite-references (str v))))

(defn env->variables
  "The Variables of an `env:` map, name → string value."
  [env]
  (into {} (map (fn [[k v]] [(name k) (variable-value v)])) env))

(defn- conflict
  "What else gives Variable `n` a value other than `v` for the Step at
  `index`, as text (\"the Job's `env:` sets it to `x`\"); nil when nothing
  does. The value the Job inherits (its own `env:`, else the Workflow's)
  decides alone, as lifting a Step's entry would replace it; only a name
  the Job does not inherit is compared with the other Steps."
  [{:keys [workflow job]} steps index n v]
  (let [differs (fn [w] (when (and (some? w) (not= w v)) w))]
    (if-let [[level vars] (first (filter #(contains? (second %) n) [["Job" job] ["Workflow" workflow]]))]
      (some->> (differs (get vars n)) (format "the %s's `env:` sets it to `%s`" level))
      (first (for [[j {:keys [location variables]}] (map-indexed vector steps)
                   :when (not= j index)
                   :let [w (differs (get variables n))]
                   :when w]
               (format "Step `%s` sets it to `%s`" (:step location) w))))))

(defn- conflict-gap [location n v text]
  (gap/at location (str "its `env:` sets `" n "` to `" v "`, but " text
                        "; a GitLab Job has one value per Variable, so set it for this Step's script lines by hand.")))

(defn job-variables
  "A Job's Variables from its own `env:` Variables (`:job`) and its Steps',
  given the Workflow's (`:workflow`). `steps` are `{:location .. :variables
  {..}}`, the Step's place in the Source as a Gap location. A Step's entry is
  lifted into the Job unless the Job, the Workflow (when the Job does not
  set the name) or another Step gives the name another value; then it is a
  Gap at the Step. An entry the Job already inherits with the same value is
  not repeated. Returns `{:variables {..} :gaps [..]}`."
  [{:keys [workflow job] :as levels} steps]
  (let [inherited (merge workflow job)
        outcomes (for [[index {:keys [location variables]}] (map-indexed vector steps)
                       [n v] (sort variables)]
                   (if-let [text (conflict levels steps index n v)]
                     {:gap (conflict-gap location n v text)}
                     {:lifted [n v]}))]
    {:variables (into (or job {})
                      (comp (keep :lifted) (remove (fn [[n v]] (= v (get inherited n)))))
                      outcomes)
     :gaps (into [] (keep :gap) outcomes)}))

(defn secret-names
  "The names of the Secrets `s` references (`${{ secrets.X }}`), in order."
  [s]
  (into [] (comp (filter #(= "secrets" (second %))) (map #(nth % 2))) (re-seq reference (str s))))

(defn- backticked [xs]
  (str/join ", " (map #(str "`" % "`") xs)))

(defn- users-text
  "Where a Secret is used: the Workflow's `env:` and/or Jobs `a`, `b`."
  [users]
  (let [jobs (into [] (comp (keep :job) (distinct)) users)]
    (str/join " and " (cond-> []
                        (some (comp nil? :job) users) (conj "the Workflow's `env:`")
                        (seq jobs) (conj (str (if (= 1 (count jobs)) "Job " "Jobs ") (backticked jobs)))))))

(defn- secret-gap [workflow-path n users]
  (gap/at {:workflow workflow-path :secret n}
          (str "Secret `" n "`, used by " (users-text users) ", "
               (if (= "GITHUB_TOKEN" n)
                 (str "is the token GitHub creates for each run, and GitLab has none by that name;"
                      " create a masked Variable `GITHUB_TOKEN` on the Target holding a GitHub token"
                      " with the access the Jobs need, or switch them to `CI_JOB_TOKEN`.")
                 (str "cannot be read from the Source; create it on the Target as a masked Variable `" n "`.")))))

(defn secret-gaps
  "One Gap per Secret referenced in the Workflow at `workflow-path`, in
  order of first use. `uses` are `{:job id :texts [..]}`, the Source texts
  (commands, `env:` values) of one Job, or of the Workflow's `env:` when
  `:job` is nil."
  [workflow-path uses]
  (let [refs (for [{:keys [texts] :as source} uses
                   text texts
                   n (secret-names text)]
               [n (select-keys source [:job])])
        users (group-by first refs)]
    (into []
          (comp (map first) (distinct) (map #(secret-gap workflow-path % (map second (users %)))))
          refs)))
