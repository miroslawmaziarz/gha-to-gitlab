(ns gha-to-gitlab.gitlab
  "Target side: writes the CI Model as a GitLab `.gitlab-ci.yml`."
  (:require [clj-yaml.core :as yaml]
            [flatland.ordered.map :refer [ordered-map]]
            [gha-to-gitlab.ci-model :as ci-model]))

(def ci-file-name
  "The name of the CI file the GitLab writer produces."
  ".gitlab-ci.yml")

(def ^:private global-keywords
  "Top-level `.gitlab-ci.yml` keys that GitLab reads as settings, not Jobs."
  #{"default" "include" "stages" "variables" "workflow" "image" "services"
    "cache" "before_script" "after_script" "types"})

(defn job-name
  "The GitLab Job name for a CI Model Job id; ids that collide with a
  global keyword get a `_job` suffix so they stay Jobs."
  [id]
  (if (global-keywords id) (str id "_job") id))

(defn- stage-name [level] (str "stage-" level))

(defn job-stages
  "The Stage each Job runs in, Job id → Stage name (`stage-1`, `stage-2`,
  ...): the Job's level in the dependency order (`ci-model/dependency-levels`),
  so Jobs with no `:needs` run in the first Stage and every other Job in the
  Stage after the latest Job it needs. Nil when no Job has `:needs`, as then
  GitLab's default Stage runs them all together (ADR-0005)."
  [{:keys [jobs]}]
  (when (some :needs jobs)
    (update-vals (ci-model/dependency-levels jobs) stage-name)))

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
  "The `.gitlab-ci.yml` content: the Stages, when any Job has `:needs`,
  the Pipeline's Variables, when it has any, then one entry per Job."
  [{:keys [variables] :as model}]
  (let [stages (job-stages model)]
    (into (cond-> (ordered-map)
            stages (assoc :stages (mapv stage-name (range 1 (inc (count (set (vals stages)))))))
            (seq variables) (assoc :variables (into (ordered-map) (sort variables))))
          (map (fn [job] [(job-name (:id job)) (gitlab-job (get stages (:id job)) job)]))
          (:jobs model))))

(defn ci-model->gitlab-ci-yaml
  "Renders a CI Model as the text of a `.gitlab-ci.yml`."
  [model]
  (yaml/generate-string (ci-model->ci-file-map (ci-model/validate! model))
                        :dumper-options {:flow-style :block}))
