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
  "One GitLab Job, its keys in reading order: where it runs (image, tags),
  its environment (variables), what it restores (cache), what it runs
  (script), then what it keeps (artifacts)."
  [{:keys [image tags variables script cache artifacts]}]
  (cond-> (ordered-map)
    image (assoc :image image)
    (seq tags) (assoc :tags tags)
    (seq variables) (assoc :variables (into (ordered-map) (sort variables)))
    (= 1 (count cache)) (assoc :cache (gitlab-cache (first cache)))
    (< 1 (count cache)) (assoc :cache (mapv gitlab-cache cache))
    true (assoc :script script)
    artifacts (assoc :artifacts (in-order artifacts [:name :paths :exclude :expire-in]
                                          {:expire-in :expire_in}))))

(defn- ci-model->job-map [model]
  (into (ordered-map)
        (map (fn [job] [(job-name (:id job)) (gitlab-job job)]))
        (:jobs model)))

(defn ci-model->gitlab-ci-yaml
  "Renders a CI Model as the text of a `.gitlab-ci.yml`."
  [model]
  (yaml/generate-string (ci-model->job-map (ci-model/validate! model))
                        :dumper-options {:flow-style :block}))
