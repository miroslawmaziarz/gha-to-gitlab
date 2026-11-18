(ns gha-to-gitlab.gitlab
  "Target side: writes the CI Model as a GitLab `.gitlab-ci.yml`."
  (:require [clj-yaml.core :as yaml]
            [flatland.ordered.map :refer [ordered-map]]
            [gha-to-gitlab.ci-model :as ci-model]))

(def ci-file-name ".gitlab-ci.yml")

(def ^:private global-keywords
  "Top-level `.gitlab-ci.yml` keys that GitLab reads as settings, not Jobs."
  #{"default" "include" "stages" "variables" "workflow" "image" "services"
    "cache" "before_script" "after_script" "types"})

(defn job-name
  "The GitLab Job name for a CI Model Job id; ids that collide with a
  global keyword get a `_job` suffix so they stay Jobs."
  [id]
  (if (global-keywords id) (str id "_job") id))

(defn job-stages
  "The Stage each Job runs in, Job id → Stage name (`stage-1`, `stage-2`,
  ...): a Job with no `:needs` runs in the first Stage, any other Job in
  the Stage after the latest Stage of a Job it needs. Nil when every Job
  runs in the first Stage, as then GitLab's default Stage will do. The
  Source reader refuses Jobs that need each other in a cycle."
  [{:keys [jobs]}]
  (when (some :needs jobs)
    (let [needs (into {} (map (juxt :id :needs)) jobs)
          ;; Jobs come in Source order, not dependency order, so settle
          ;; each Job's Stage number once all the Jobs it needs have one
          numbers (loop [numbers {}]
                    (let [settled (for [[id ns] needs
                                        :when (and (not (numbers id)) (every? numbers ns))]
                                    [id (inc (reduce max 0 (map numbers ns)))])]
                      (cond
                        (= (count numbers) (count needs)) numbers
                        (empty? settled) (throw (ex-info "Jobs need each other in a cycle or need a missing Job"
                                                         {:jobs (remove numbers (keys needs))}))
                        :else (recur (into numbers settled)))))]
      (update-vals numbers #(str "stage-" %)))))

(defn- stage-names
  "The Stage names of `job-stages` in run order."
  [job-stages]
  (mapv #(str "stage-" %) (range 1 (inc (count (distinct (vals job-stages)))))))

(defn- in-order
  "`m` as an ordered map with `ks` first, in that order, under their GitLab
  names (`rename`, else the key itself); keys `m` lacks are left out."
  ([m ks] (in-order m ks {}))
  ([m ks rename]
   (into (ordered-map)
         (for [k ks :when (contains? m k)]
           [(get rename k k) (get m k)]))))

(defn- gitlab-cache [{:keys [key] :as cache}]
  (in-order (cond-> cache (map? key) (update :key in-order [:files :prefix]))
            [:key :paths :policy :when]))

(defn- gitlab-job
  "One GitLab Job, its keys in reading order: when it runs (stage, needs),
  where it runs (image, tags), its environment (variables), what it
  restores (cache), what it runs (script), then what it keeps (artifacts)."
  [stage {:keys [needs image tags variables script cache artifacts]}]
  (cond-> (ordered-map)
    stage (assoc :stage stage)
    (seq needs) (assoc :needs (mapv job-name needs))
    image (assoc :image image)
    (seq tags) (assoc :tags tags)
    (seq variables) (assoc :variables (into (ordered-map) (sort variables)))
    (= 1 (count cache)) (assoc :cache (gitlab-cache (first cache)))
    (< 1 (count cache)) (assoc :cache (mapv gitlab-cache cache))
    true (assoc :script script)
    artifacts (assoc :artifacts (in-order artifacts [:name :paths :exclude :expire-in]
                                          {:expire-in :expire_in}))))

(defn- ci-model->ci-file-map
  "The `.gitlab-ci.yml` content: the Stages, when there is more than one,
  then one entry per Job."
  [model]
  (let [stages (job-stages model)]
    (into (if stages
            (ordered-map :stages (stage-names stages))
            (ordered-map))
          (map (fn [job] [(job-name (:id job)) (gitlab-job (get stages (:id job)) job)]))
          (:jobs model))))

(defn ci-model->gitlab-ci-yaml
  "Renders a CI Model as the text of a `.gitlab-ci.yml`."
  [model]
  (yaml/generate-string (ci-model->ci-file-map (ci-model/validate! model))
                        :dumper-options {:flow-style :block}))
