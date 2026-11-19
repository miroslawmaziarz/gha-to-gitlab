(ns gha-to-gitlab.matrix
  "Matrices: a Job's `strategy.matrix` as a GitLab parallel matrix, whose
  Variables the Job's `${{ matrix.x }}` references become (ADR-0009)."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [flatland.ordered.map :refer [ordered-map]]
            [gha-to-gitlab.gap :as gap]
            [gha-to-gitlab.variables :as variables]
            [gha-to-gitlab.wording :as wording]))

(def jobs-per-matrix-limit
  "The most Jobs one GitLab parallel matrix can make."
  200)

(def ^:private combination-keys
  "The Matrix keys that are not dimensions, with the Gap text of each:
  they add or remove combinations, which a GitLab parallel matrix cannot."
  {:include "GitLab runs only the combinations of the dimensions, so add the included runs by hand."
   :exclude "GitLab runs every combination of the dimensions, so remove the excluded runs by hand."})

(defn- dimension
  "Matrix dimension `k` with `values` as `{:name .. :key .. :values [..]}`,
  or `{:gap text}` when its values are not a list of texts."
  [k values]
  (cond
    (not (sequential? values))
    {:gap (str "its Matrix dimension `" (name k) "` is `" (wording/yaml-ish values)
               "`, not a list of values; the Pipeline leaves it out, so add it to the parallel matrix by hand.")}
    (not-every? string? values)
    {:gap (str "its Matrix dimension `" (name k) "` has values that are not text (`" (wording/yaml-ish values)
               "`); the Pipeline leaves it out, so add it to the parallel matrix by hand.")}
    :else {:key (name k) :name (variables/variable-name (name k)) :values (vec values)}))

(defn- distinct-names
  "`dimensions` with each Variable name once: a dimension whose name an
  earlier one already has becomes `{:gap text}`."
  [dimensions]
  (let [first-keys (reduce (fn [m {n :name k :key}] (cond-> m (and n (not (m n))) (assoc n k))) {} dimensions)]
    (for [{n :name k :key :as d} dimensions]
      (if (and n (not= k (first-keys n)))
        {:gap (str "its Matrix keys `" (first-keys n) "` and `" k "` are both the GitLab Variable `" n
                   "`; the Pipeline keeps `" (first-keys n) "`, so rename one by hand.")}
        d))))

(defn- strategy-gaps
  "Gap texts for what `strategy` asks of the runs beyond their
  combinations: GitLab never cancels the other runs when one fails, and
  cannot limit how many go at once."
  [strategy]
  (cond-> []
    (not= "false" (:fail-fast strategy))
    (conj (str "its Matrix cancels the other runs when one fails (`fail-fast` is true unless set to false);"
               " GitLab runs them all, so cancel them by hand if that matters."))
    (contains? strategy :max-parallel)
    (conj (str "its Matrix runs at most `" (wording/yaml-ish (:max-parallel strategy))
               "` at once (`max-parallel`); GitLab starts as many as runners allow, so limit them by hand if that matters."))))

(defn job-matrix
  "The GitLab parallel matrix of a Job's `strategy` (read as the texts
  GitHub reads) at Gap location `location`: `{:matrix [{name [value ..]}]
  :gaps [..]}`, `:matrix` nil when the Job has no Matrix or none of its
  dimensions converts. Each dimension becomes the Variable
  `variables/variable-name` names, with the same values, so GitLab runs
  the same combinations; what changes them is a Gap."
  [location {:keys [matrix] :as strategy}]
  (if (and (some? matrix) (not (map? matrix)))
    {:matrix nil
     :gaps [(gap/at location (str "its Matrix is the expression `" (wording/yaml-ish matrix)
                                  "`; set the Job's parallel matrix by hand."))]}
    (let [outcomes (distinct-names (for [[k values] matrix
                                         :when (not (combination-keys k))]
                                     (dimension k values)))
          dimensions (remove :gap outcomes)
          combinations (reduce * (map (comp count :values) dimensions))
          texts (concat (keep :gap outcomes)
                        (for [[k text] combination-keys
                              :when (contains? matrix k)]
                          (str "its Matrix has `" (name k) "` `" (wording/yaml-ish (get matrix k)) "`; " text))
                        (when (< jobs-per-matrix-limit combinations)
                          [(str "its Matrix has " combinations " combinations, and GitLab allows at most "
                                jobs-per-matrix-limit "; split the Job by hand.")])
                        (when (some? matrix) (strategy-gaps strategy)))]
      {:matrix (when (seq dimensions)
                 [(into (ordered-map) (map (juxt :name :values)) dimensions)])
       :gaps (mapv #(gap/at location %) texts)})))

(defn dimension-values
  "The values of each Matrix dimension of `strategy` that converts, Matrix
  key → values."
  [{:keys [matrix]}]
  (into {}
        (keep (fn [[k values]]
                (when (and (not (combination-keys k)) (sequential? values) (every? string? values))
                  [(name k) values])))
        (when (map? matrix) matrix)))

(defn- whole-reference
  "The Matrix key when `s` is exactly one `${{ matrix.x }}`, else nil."
  [s]
  (let [ks (variables/matrix-keys s)]
    (when (and (= 1 (count ks)) (re-matches #"\$\{\{[^}]*\}\}" (str/trim (str s))))
      (first ks))))

(defn runs-on-gap
  "The Gap for a Job at `location` whose `runs-on` is one Matrix reference
  to a dimension with `values` (see `dimension-values`): GitLab picks one
  runner per Job. Nil when `runs-on` is not one."
  [location runs-on values]
  (when-let [vs (some->> (whole-reference runs-on) (get values))]
    (gap/at location (str "runs on the Matrix values `" (wording/yaml-ish vs) "` (`" runs-on
                          "`); GitLab picks one runner per Job, so set the image and tags per value by hand."))))

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

(defn- with-matrix
  "Action Step `step` with each `${{ matrix.k }}` in its inputs, for the
  keys of Matrix `values`, as the Variable reference, or, for key `only`,
  as `value`. References to other keys stay expressions."
  ([step values] (with-matrix step values nil nil))
  ([step values only value]
   (let [rewrite (fn [v]
                   (reduce (fn [text k]
                             (str/replace text (matrix-reference k)
                                          (str/re-quote-replacement
                                           (if (= k only) value (str "${" (variables/variable-name k) "}")))))
                           v
                           (keys values)))]
     (cond-> step
       (map? (:with step)) (update :with update-vals #(if (string? %) (rewrite %) %))))))

(defn- with-value
  "`settings` with Variable reference `${var-name}` as `value`."
  [settings var-name value]
  (walk/postwalk #(if (string? %) (str/replace % (str "${" var-name "}") value) %) settings))

(defn translate-action
  "The translation (by `translate`, such as `action-translator/translate`)
  of Action Step `step`, whose inputs may reference the Matrix with
  `values` (see `dimension-values`): the inputs hold the Matrix Variables,
  so the Job settings do too (`node:${node_version}`). The Job settings
  must be what each value alone gives; the first value for which they are
  not adds a Gap `{:input .. :message ..}` to the translation. Nil when no
  Action Translator converts the Step."
  [translate step values]
  (when-let [translation (translate (with-matrix step values))]
    (let [gap (first (for [[input text] (:with step)
                           k (variables/matrix-keys text)
                           value (get values k)
                           :let [var-name (variables/variable-name k)
                                 expected (with-value (:job-settings translation) var-name value)
                                 actual (:job-settings (translate (with-matrix step values k value)))
                                 setting (first (filter #(not= (get expected %) (get actual %))
                                                        (distinct (concat (keys expected) (keys actual)))))]
                           :when setting]
                       {:input (name input)
                        :message (str "input `" (name input) ": " text "` is the Matrix Variable `" var-name
                                      "`, and its value `" value "` makes the Job's `" (name setting) "` `"
                                      (wording/yaml-ish (get actual setting)) "`, not `" (wording/yaml-ish (get expected setting))
                                      "`; set the " (name setting) " for that value by hand.")}))]
      (cond-> translation gap (update :gaps (fnil conj []) gap)))))
