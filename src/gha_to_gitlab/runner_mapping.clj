(ns gha-to-gitlab.runner-mapping
  "Runner Mapping: translates each Job's GitHub runner label (`runs-on`) into
  a GitLab image and runner tags on the CI Model."
  (:require [clojure.string :as str]))

(def default-runner-mappings
  "Built-in Runner Mappings for the common GitHub-hosted labels."
  {"ubuntu-latest" {:image "ubuntu:24.04" :tags []}
   "ubuntu-24.04" {:image "ubuntu:24.04" :tags []}
   "ubuntu-22.04" {:image "ubuntu:22.04" :tags []}
   "ubuntu-20.04" {:image "ubuntu:20.04" :tags []}})

(defn- ubuntu-fallback
  "The Runner Mapping for an `ubuntu-*` label missing from the table (such as
  `ubuntu-22.04-arm` or `ubuntu-latest-4-cores`): the Ubuntu version named in
  the label, else the `ubuntu-latest` image, with no tags."
  [label]
  (when (and label (re-find #"^ubuntu-" label))
    {:image (if-let [[_ version] (re-find #"^ubuntu-(\d+\.\d+)" label)]
              (str "ubuntu:" version)
              (:image (get default-runner-mappings "ubuntu-latest")))
     :tags []}))

(defn- unmapped-gap [{:keys [id runs-on]}]
  {:message (if (str/includes? runs-on "${{")
              (str "Job `" id "` runs on the expression `" runs-on "`, which is only known "
                   "when the Workflow runs; resolve it and set the Job's image and tags by hand.")
              (str "Job `" id "` runs on `" runs-on "`, which has no Runner Mapping; "
                   "add one in the Migration Config or set the Job's image and tags by hand."))})

(defn- map-job [mappings model job]
  (let [label (:runs-on job)]
    (if-let [{:keys [image tags]} (or (get mappings label) (ubuntu-fallback label))]
      (update model :jobs conj (assoc job :image image :tags tags))
      (cond-> (update model :jobs conj job)
        (some? label) (update :gaps conj (unmapped-gap job))))))

(defn apply-runner-mappings
  "Sets `:image` and `:tags` on every Job of `model` from its `:runs-on`
  label, using the built-in Runner Mappings overridden by `overrides`
  (label → {:image .. :tags [..]}); any other `ubuntu-*` label falls back to
  an Ubuntu image. A label with no Runner Mapping adds a Gap."
  [model overrides]
  (let [mappings (merge default-runner-mappings overrides)]
    (reduce (partial map-job mappings)
            (assoc model :jobs [])
            (:jobs model))))
