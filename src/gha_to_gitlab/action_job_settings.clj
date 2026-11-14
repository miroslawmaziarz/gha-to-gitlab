(ns gha-to-gitlab.action-job-settings
  "Applies the Job settings that Action Translators asked for (such as a
  setup Action's image) to each Job of the CI Model. It runs once, after the
  Runner Mapping, so an Action's image replaces the Runner Mapping image
  (ADR-0002). The Source reader has already settled conflicting settings
  into one `:action-job-settings` map per Job.")

(defn- apply-to-job [{settings :action-job-settings :as job}]
  (if (seq settings)
    (cond-> (merge (dissoc job :action-job-settings) settings)
      (and (:image settings) (:image job)) (assoc :runner-mapping-image (:image job)))
    (dissoc job :action-job-settings)))

(defn apply-action-job-settings
  "Merges every Job's `:action-job-settings` onto the Job and removes them.
  When an Action's image replaces an image the Job already had (from the
  Runner Mapping), that image is kept as `:runner-mapping-image` so the
  Migration Report can show the override."
  [model]
  (update model :jobs (partial mapv apply-to-job)))
