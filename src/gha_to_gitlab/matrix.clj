(ns gha-to-gitlab.matrix
  "Matrices: a Job's `strategy.matrix` as a GitLab parallel matrix, whose
  Matrix Variables the Job's `${{ matrix.x }}` references become
  (ADR-0009)."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [flatland.ordered.map :refer [ordered-map]]
            [gha-to-gitlab.ci-model :as ci-model]
            [gha-to-gitlab.gap :as gap]
            [gha-to-gitlab.variables :as variables]
            [gha-to-gitlab.wording :as wording]))

(def ^:private combination-keys
  "The Matrix keys that are not Matrix dimensions, with the Gap text of
  each: they add or remove combinations, which a GitLab parallel matrix
  cannot."
  {:include "GitLab runs only the combinations of the dimensions, so add the included runs by hand."
   :exclude "GitLab runs every combination of the dimensions, so remove the excluded runs by hand."})

(defn- dimension
  "Matrix dimension `k` with `values` as `{:name .. :key .. :values [..]}`,
  or `{:gap text}` when its values are not a list of texts."
  [k values]
  (let [left-out "the Pipeline leaves it out, so "]
    (cond
      (not (sequential? values))
      {:gap (str "its Matrix dimension `" (name k) "` is `" (wording/yaml-ish values)
                 "`, not a list of values; " left-out "add it to the parallel matrix by hand.")}
      (empty? values)
      {:gap (str "its Matrix dimension `" (name k) "` has no values, which GitHub refuses; " left-out
                 "give it values by hand.")}
      (not-every? string? values)
      {:gap (str "its Matrix dimension `" (name k) "` has values that are not text (`" (wording/yaml-ish values)
                 "`); " left-out "add it to the parallel matrix by hand.")}
      :else {:key (name k) :name (variables/variable-name (name k)) :values (vec values)})))

(defn- distinct-names
  "`dimensions` with each Matrix Variable name once: a dimension whose
  name an earlier one already has becomes `{:gap text}`."
  [dimensions]
  (let [first-keys (reduce (fn [m {n :name k :key}] (cond-> m (and n (not (m n))) (assoc n k))) {} dimensions)]
    (for [{n :name k :key :as d} dimensions]
      (if (and n (not= k (first-keys n)))
        {:gap (str "its Matrix keys `" (first-keys n) "` and `" k "` are both the GitLab Variable `" n
                   "`; the Pipeline keeps `" (first-keys n) "`, so rename one by hand.")}
        d))))

(defn- run-gaps
  "Gap texts for what `fail-fast` and `max-parallel` ask of a Matrix's
  runs: GitLab never cancels the other runs when one fails, and cannot
  limit how many go at once."
  [{:keys [fail-fast] :as settings}]
  (cond-> []
    (not= "false" fail-fast)
    (conj (str "its Matrix cancels the other runs when one fails (`fail-fast` is true unless set to false);"
               " GitLab runs them all, so cancel them by hand if that matters."))
    (contains? settings :max-parallel)
    (conj (str "its Matrix runs at most `" (wording/yaml-ish (:max-parallel settings))
               "` at once (`max-parallel`); GitLab starts as many as runners allow, so limit them by hand if that matters."))))

(defn job-matrix
  "The GitLab parallel matrix of a Job's `strategy:` (read as the texts
  GitHub reads) at Gap location `location`: `{:matrix [{name [value ..]}]
  :values {key [value ..]} :gaps [..]}`. `:matrix` is nil when the Job
  has no Matrix or none of its dimensions converts; `:values` are the
  Matrix values of the dimensions it keeps, by Matrix key. Each dimension
  becomes the Matrix Variable `variables/variable-name` names, with the
  same values, so GitLab runs the same combinations; what changes them is
  a Gap."
  [location {:keys [matrix] :as settings}]
  (if (and (some? matrix) (not (map? matrix)))
    {:matrix nil
     :values {}
     :gaps [(gap/at location (str "its Matrix is the expression `" (wording/yaml-ish matrix)
                                  "`; set the Job's parallel matrix by hand."))]}
    (let [outcomes (distinct-names (for [[k values] matrix
                                         :when (not (combination-keys k))]
                                     (dimension k values)))
          dimensions (remove :gap outcomes)
          combinations (reduce * (map (comp count :values) dimensions))
          too-many? (< ci-model/jobs-per-matrix-limit combinations)
          texts (concat (keep :gap outcomes)
                        (for [[k text] combination-keys
                              :when (contains? matrix k)]
                          (str "its Matrix has `" (name k) "` `" (wording/yaml-ish (get matrix k)) "`; " text))
                        (when too-many?
                          [(str "its Matrix has " combinations " combinations, and GitLab allows at most "
                                ci-model/jobs-per-matrix-limit "; the Pipeline leaves the Matrix out, so split the Job by hand.")])
                        (when (some? matrix) (run-gaps settings)))
          kept (when-not too-many? dimensions)]
      {:matrix (when (seq kept)
                 [(into (ordered-map) (map (juxt :name :values)) kept)])
       :values (into {} (map (juxt :key :values)) kept)
       :gaps (mapv #(gap/at location %) texts)})))

(defn runs-on-gap
  "The Gap for a Job at `location` whose `runs-on` label references the
  Matrix with `values` (see `job-matrix`): GitLab picks one runner per
  Job. Nil when it references none of them."
  [location runs-on values]
  (let [referenced (filter #(contains? values %) (variables/matrix-keys runs-on))]
    (when (seq referenced)
      (gap/at location (str "its `runs-on` `" runs-on "` comes from the Matrix ("
                            (str/join ", " (map #(str "`" % "`: `" (wording/yaml-ish (get values %)) "`") referenced))
                            "); GitLab picks one runner per Job, so set the image and tags per value by hand.")))))

(defn variable-clash-gaps
  "The Gaps of a Job at `location` whose Matrix Variables have the names
  of its `env:` Variables `variables`: GitLab gives the Job one value."
  [location matrix variables]
  (for [n (mapcat keys matrix)
        :when (contains? variables n)]
    (gap/at location (str "its Matrix Variable `" n "` has the name of an `env:` Variable; GitLab gives the Job"
                          " one value, so rename one by hand."))))

(defn- matrix-reference
  "The pattern of a whole `${{ matrix.k }}` for Matrix key `k`."
  [k]
  (re-pattern (str "\\$\\{\\{\\s*matrix\\." (java.util.regex.Pattern/quote k) "\\s*\\}\\}")))

(defn- variable-reference
  "The reference `${name}` to the Matrix Variable of Matrix key `k`."
  [k]
  (str "${" (variables/variable-name k) "}"))

(defn- with-matrix
  "Action Step `step` with each `${{ matrix.k }}` in its inputs, for the
  keys of Matrix `values`, as its Matrix Variable reference, or, for the
  one key `literal-key`, as the Matrix value `literal-value`. References
  to other keys stay expressions."
  ([step values] (with-matrix step values nil nil))
  ([step values literal-key literal-value]
   (let [rewrite (fn [text]
                   (reduce (fn [text k]
                             (str/replace text (matrix-reference k)
                                          (str/re-quote-replacement
                                           (if (= k literal-key) literal-value (variable-reference k)))))
                           text
                           (keys values)))]
     (cond-> step
       (map? (:with step)) (update :with update-vals #(if (string? %) (rewrite %) %))))))

(defn- with-reference-as-value
  "Job settings `settings` with every Matrix Variable reference `reference`
  in their texts as `value`."
  [settings reference value]
  (walk/postwalk #(if (string? %) (str/replace % reference value) %) settings))

(defn- first-differing-setting
  "The first Job setting whose value differs between `expected` and
  `actual`, nil when none does."
  [expected actual]
  (first (filter #(not= (get expected %) (get actual %))
                 (distinct (concat (keys expected) (keys actual))))))

(defn- value-mismatch
  "The first Matrix value for which `translation` (of Action Step `step`
  with its Matrix Variables) is not what the value alone gives:
  `{:input .. :setting .. :message ..}`, nil when every value agrees."
  [translate step values translation]
  (first (for [[input text] (:with step)
               k (variables/matrix-keys text)
               value (get values k)
               :let [expected (with-reference-as-value (:job-settings translation) (variable-reference k) value)
                     actual (:job-settings (translate (with-matrix step values k value)))
                     setting (first-differing-setting expected actual)]
               :when setting]
           {:input (name input)
            :setting setting
            :message (str "input `" (name input) ": " text "` is the Matrix Variable `" (variables/variable-name k)
                          "`, and its value `" value "` makes the Job's `" (name setting) "` `"
                          (wording/yaml-ish (get actual setting)) "`, not `" (wording/yaml-ish (get expected setting))
                          "`; the Job's `" (name setting) "` is left out, so set it per value by hand.")})))

(defn translate-action
  "The translation (by `translate`, such as `action-translator/translate`)
  of Action Step `step`, whose inputs may reference the Matrix with
  `values` (see `job-matrix`): the inputs hold the Matrix Variable
  references, so the Job settings do too (`node:${node_version}`), and
  the Step carries those references as `:matrix-variables`, so a
  translator can tell them from text the Source wrote. The Job settings
  must be what each value alone gives; for the first value whose are not,
  the setting is left out with a Gap `{:input .. :message ..}`. Nil when
  no Action Translator converts the Step."
  [translate step values]
  (let [step (assoc step :matrix-variables (into #{} (map variable-reference) (keys values)))]
    (when-let [translation (translate (with-matrix step values))]
      (if-let [{:keys [input setting message]} (value-mismatch translate step values translation)]
        (-> translation
            (update :job-settings dissoc setting)
            (update :outcome #(str (or % "converted") "; its `" (name setting) "` is left out (see Gaps)"))
            (update :gaps (fnil conj []) {:input input :message message}))
        translation))))
