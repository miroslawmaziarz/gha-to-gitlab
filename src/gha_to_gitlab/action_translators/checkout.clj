(ns gha-to-gitlab.action-translators.checkout
  "Action Translator for `actions/checkout`: GitLab checks out the
  repository before every Job, so the Step is dropped. An input GitLab's
  automatic checkout can't honour is a Gap instead of being lost."
  (:require [clojure.string :as str]
            [gha-to-gitlab.action-translator :as action-translator]))

(def ^:private inputs
  "Per input: `:honoured`, a predicate on the value (as a string) that is
  true when GitLab's automatic checkout honours it or it makes no difference
  there, and `:hint`, what a person should do otherwise. An input missing
  here, or a value `:honoured` rejects, is a Gap."
  {"fetch-depth" {:honoured #{"1"} ; GitLab's default depth (20) is at least as deep
                  :hint "set the Job's `GIT_DEPTH` variable (0 means full history) by hand"}
   "submodules" {:honoured #{"false"}
                 :hint "set the Job's `GIT_SUBMODULE_STRATEGY` variable (`normal` or `recursive`) by hand"}
   "persist-credentials" {:honoured any?}
   "clean" {:honoured any?}
   "lfs" {:honoured any?} ; the GitLab runner fetches LFS objects itself
   "fetch-tags" {:honoured any?}
   "show-progress" {:honoured any?}
   "set-safe-directory" {:honoured any?}
   "repository" {:hint "check out the other repository in a script line"}
   "ref" {:hint "GitLab checks out the commit the Pipeline runs for; check out another ref in a script line if the Job needs it"}
   "path" {:hint "GitLab always checks out into `$CI_PROJECT_DIR`; move files or adjust paths by hand"}
   "token" {:hint "store it as a masked Variable and use it in a script line"}
   "ssh-key" {:hint "store it as a masked Variable and use it in a script line"}})

(def ^:private default-hint
  "GitLab's automatic checkout can't do this; check the Job works without it")

(defn- one-line
  "`value` as text on one line, so it stays inside one Migration Report bullet."
  [value]
  (str/join " " (str/split (str/trim (str value)) #"\s*\n\s*")))

(defn- input-gap [input value]
  {:input input
   :message (str "input `" input ": " (one-line value) "` has no GitLab equivalent: "
                 (get-in inputs [input :hint] default-hint) ".")})

(defmethod action-translator/translate "actions/checkout"
  [{:keys [with]}]
  {:script []
   :outcome "dropped (GitLab checks out the repository automatically)"
   :gaps (vec (for [[k v] with
                    :let [input (name k)
                          honoured? (get-in inputs [input :honoured])]
                    :when (not (and honoured? (honoured? (str v))))]
                (input-gap input v)))})
