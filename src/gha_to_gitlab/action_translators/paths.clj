(ns gha-to-gitlab.action-translators.paths
  "What the Action Translators that turn a `path` input into GitLab paths
  (`actions/cache`, `actions/upload-artifact`) share. GitLab caches and
  artifacts only hold paths inside the project directory."
  (:require [clojure.string :as str]))

(defn lines
  "The paths of a single- or multi-line `path` input, trimmed, without blank lines."
  [value]
  (into [] (comp (map str/trim) (remove str/blank?)) (str/split-lines (str value))))

(defn exclusion?
  "True when `path` is a `!` line, which excludes what it matches."
  [path]
  (str/starts-with? path "!"))

(defn expression?
  "True when `path` holds a GitHub expression, only known when the Workflow runs."
  [path]
  (str/includes? path "${{"))

(defn outside-project?
  "True when `path` may point outside the project directory: absolute, under
  the home directory, through a variable, or up through `..`."
  [path]
  (boolean (or (re-find #"^[/~$]" path) (re-find #"(^|/)\.\.(/|$)" path))))
