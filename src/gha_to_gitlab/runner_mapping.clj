(ns gha-to-gitlab.runner-mapping
  "Runner Mapping: translates each Job's GitHub runner label (`runs-on`) into
  a GitLab image and runner tags on the CI Model.")

(def default-runner-mappings
  "Built-in Runner Mappings for the common GitHub-hosted labels."
  {"ubuntu-latest" {:image "ubuntu:24.04" :tags []}
   "ubuntu-24.04" {:image "ubuntu:24.04" :tags []}
   "ubuntu-22.04" {:image "ubuntu:22.04" :tags []}
   "ubuntu-20.04" {:image "ubuntu:20.04" :tags []}})

(defn- unmapped-gap [{:keys [id runs-on]}]
  {:message (str "Job `" id "` runs on `" runs-on "`, which has no Runner Mapping; "
                 "add one in the config file or set the Job's image and tags by hand.")})

(defn- map-job [mappings model job]
  (let [label (:runs-on job)]
    (if-let [{:keys [image tags]} (get mappings label)]
      (update model :jobs conj (assoc job :image image :tags tags))
      (cond-> (update model :jobs conj job)
        (some? label) (update :gaps conj (unmapped-gap job))))))

(defn apply-runner-mappings
  "Sets `:image` and `:tags` on every Job of `model` from its `:runs-on`
  label, using the built-in Runner Mappings overridden by `overrides`
  (label → {:image .. :tags [..]}). A label with no Runner Mapping adds a Gap."
  [model overrides]
  (let [mappings (merge default-runner-mappings overrides)]
    (reduce (partial map-job mappings)
            (assoc model :jobs [])
            (:jobs model))))
