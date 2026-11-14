(ns gha-to-gitlab.action-translators.checkout
  "Action Translator for `actions/checkout`: GitLab checks out the
  repository before every Job, so the Step is dropped. An input GitLab's
  automatic checkout can't honour is a Gap instead of being lost."
  (:require [gha-to-gitlab.action-translator :as action-translator]))

(def ^:private honoured
  "Inputs GitLab's automatic checkout honours, or that make no difference
  there, given their value. Any other input or value is a Gap."
  {"fetch-depth" #{"1"} ; GitLab's default depth (20) is at least as deep
   "submodules" #{"false"}
   "persist-credentials" any?
   "clean" any?
   "lfs" any? ; the GitLab runner fetches LFS objects itself
   "fetch-tags" any?
   "show-progress" any?
   "set-safe-directory" any?})

(def ^:private hints
  {"repository" "check out the other repository in a script line"
   "ref" "GitLab checks out the commit the Pipeline runs for; check out another ref in a script line if the Job needs it"
   "path" "GitLab always checks out into `$CI_PROJECT_DIR`; move files or adjust paths by hand"
   "token" "store it as a masked Variable and use it in a script line"
   "ssh-key" "store it as a masked Variable and use it in a script line"
   "fetch-depth" "set the Job's `GIT_DEPTH` variable (0 means full history) by hand"
   "submodules" "set the Job's `GIT_SUBMODULE_STRATEGY` variable (`normal` or `recursive`) by hand"})

(def ^:private default-hint
  "GitLab's automatic checkout has no equivalent; check the Job works without it")

(defn- unsupported-gap [input value]
  {:input input
   :message (str "input `" input ": " value "` is not supported: "
                 (get hints input default-hint) ".")})

(defmethod action-translator/translate "actions/checkout"
  [{:keys [with]}]
  {:script []
   :outcome "dropped (GitLab checks out the repository automatically)"
   :gaps (vec (for [[k v] with
                    :let [input (name k)
                          ok? (get honoured input)]
                    :when (not (and ok? (ok? (str v))))]
                (unsupported-gap input v)))})
