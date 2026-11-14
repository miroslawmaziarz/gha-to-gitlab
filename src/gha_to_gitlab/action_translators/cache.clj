(ns gha-to-gitlab.action-translators.cache
  "Action Translators for `actions/cache`, `actions/cache/restore` and
  `actions/cache/save`: the Step becomes one Job cache (restore only is
  `policy: pull`, save only `policy: push`). `path` lines become its paths
  and `key` its key; `hashFiles(..)` in the key becomes a key on those files
  (at most two), with the rest of the key as its prefix. Paths outside the
  project directory, key expressions GitLab has no Variable for, and
  `restore-keys` (GitLab's `fallback_keys` are whole keys, not prefixes) are
  Gaps. A Job keeps up to 4 caches (`ci-model/list-settings`)."
  (:require [clojure.string :as str]
            [gha-to-gitlab.action-translator :as action-translator]
            [gha-to-gitlab.action-translators.paths :as paths]
            [gha-to-gitlab.action-translators.setup :as setup]))

(def ^:private home-examples
  "For a cache path under the home directory: how to point that tool at a
  directory inside the project instead."
  [[#"^~/\.npm\b" "for npm, set the Variable `npm_config_cache` to `$CI_PROJECT_DIR/.npm` and cache `.npm`"]
   [#"^~/\.cache/pip\b" "for pip, set the Variable `PIP_CACHE_DIR` to `$CI_PROJECT_DIR/.cache/pip` and cache `.cache/pip`"]
   [#"^~/\.m2\b" "for Maven, set the Variable `MAVEN_OPTS` to `-Dmaven.repo.local=$CI_PROJECT_DIR/.m2/repository` and cache `.m2/repository`"]
   [#"^~/\.gradle\b" "for Gradle, set the Variable `GRADLE_USER_HOME` to `$CI_PROJECT_DIR/.gradle` and cache `.gradle/caches`"]])

(defn- path-problem
  "Why GitLab can't cache `path`, as a Gap hint; nil when it can."
  [path]
  (cond
    (paths/expression? path)
    "the path is only known when the Workflow runs; resolve it and add it to the Job cache by hand"
    (str/starts-with? path "!")
    "a GitLab cache can't exclude paths; list only the paths to cache by hand"
    (paths/outside-project? path)
    (str "GitLab caches only paths inside the project directory; point the tool at a directory inside it and cache that"
         (when-let [example (some (fn [[re text]] (when (re-find re path) text)) home-examples)]
           (str " (" example ")"))
         " by hand")))

(def ^:private key-variables
  "GitHub expressions in a cache key that a GitLab Variable can stand for."
  {"github.sha" "$CI_COMMIT_SHA"
   "github.ref_name" "$CI_COMMIT_REF_SLUG" ; a cache key can't contain `/`
   "github.job" "$CI_JOB_NAME_SLUG"})

(def ^:private left-out
  "GitHub expressions a cache key can leave out: GitLab has no Variable for
  them, and leaving them out only lets Jobs on different runners share a key."
  #{"runner.os" "runner.arch"})

(defn- hash-files
  "The file patterns of a `hashFiles(...)` expression; nil for any other."
  [expr]
  (when-let [[_ args] (re-matches #"hashFiles\((.*)\)" expr)]
    (mapv (fn [[_ single double]] (or single double))
          (re-seq #"'([^']*)'|\"([^\"]*)\"" args))))

(defn- key-parts
  "`key` as a sequence of literal strings and `[:expr \"...\"]` expressions."
  [key]
  (let [m (re-matcher #"\$\{\{\s*(.*?)\s*\}\}" key)]
    (loop [start 0 parts []]
      (if (.find m)
        (recur (.end m) (conj parts (subs key start (.start m)) [:expr (.group m 1)]))
        (conj parts (subs key start))))))

(def ^:private by-hand "; write the Job cache's key by hand")

(defn- gitlab-key
  "The GitLab cache key for a GitHub cache `key`: `{:key k :left-out [expr ..]}`,
  where `k` is a string or `{:files [..] :prefix ..}`; or `{:hint ..}` when
  GitLab can't express it."
  [key]
  (let [parts (key-parts key)
        exprs (keep #(when (vector? %) (second %)) parts)
        files (into [] (mapcat hash-files) exprs)
        unknown (first (remove #(or (hash-files %) (key-variables %) (left-out %)) exprs))
        text (-> (apply str (map #(if (vector? %) (get key-variables (second %) "") %) parts))
                 (str/replace #"-{2,}" "-")
                 (str/replace #"^[-_.]+|[-_.]+$" ""))]
    (cond
      unknown {:hint (str "`" unknown "` is only known when the Workflow runs" by-hand)}
      (< 2 (count files)) {:hint (str "a GitLab cache key can depend on at most two files" by-hand)}
      (some #(str/starts-with? % "!") files) {:hint (str "a GitLab cache key can't exclude files" by-hand)}
      (str/includes? text "/") {:hint (str "a GitLab cache key can't contain `/`" by-hand)}
      (and (empty? files) (str/blank? text)) {:hint (str "nothing GitLab can use is left of the key" by-hand)}
      :else {:key (if (seq files)
                    (cond-> {:files files} (not (str/blank? text)) (assoc :prefix text))
                    text)
             :left-out (filter left-out exprs)})))

(defn- key-result [with]
  (if-let [key (get with "key")]
    (let [{:keys [hint] :as result} (gitlab-key (str key))]
      (if hint
        {:gaps [(setup/input-gap "key" key hint)]}
        result))
    {:gaps [{:input "key" :message "has no `key` input; add the Job cache by hand."}]}))

(defn- outcome-note [left-out]
  (when (seq left-out)
    (str " (its key leaves out " (str/join ", " (map #(str "`" % "`") (distinct left-out)))
         ", which GitLab has no Variable for)")))

(def ^:private inputs
  "Inputs beyond `path` and `key`: `:honoured` is a predicate on the value
  (as a string) that is true when the Job cache honours it or it makes no
  difference there; `:hint` is what a person should do otherwise."
  {"enableCrossOsArchive" {:honoured any?} ; GitLab cache archives don't depend on the OS
   "upload-chunk-size" {:honoured any?}
   "save-always" {:honoured any?} ; `true` becomes `when: always`
   "fail-on-cache-miss" {:honoured #{"false"}
                         :hint "GitLab carries on without the cache; add a script line that fails when the cached paths are missing by hand"}
   "lookup-only" {:honoured #{"false"}
                  :hint "GitLab always downloads a cache it finds; check the Job works without this by hand"}
   "restore-keys" {:hint (str "GitLab's `fallback_keys` match whole keys, not prefixes, so a new key starts with an "
                              "empty cache; add `fallback_keys` naming whole keys (such as the default branch's) by hand")}})

(def ^:private default-hint
  "the Job cache has no equivalent; check the Job works without it")

(def ^:private policies
  "Per Action: the cache `:policy` (nil: restore and save) and the outcome."
  {"actions/cache" [nil "converted to a Job cache"]
   "actions/cache/restore" ["pull" "converted to a Job cache that is only restored (`policy: pull`)"]
   "actions/cache/save" ["push" "converted to a Job cache that is only saved (`policy: push`)"]})

(defn- translate-cache [{:keys [uses with]}]
  (let [[policy converted] (get policies (action-translator/action-name uses))
        with (update-keys with name)
        lines (paths/lines (get with "path"))
        paths (vec (remove path-problem lines))
        {:keys [key left-out] key-gaps :gaps} (key-result with)
        input-gaps (for [[input v] with
                         :when (not (#{"path" "key"} input))
                         :let [honoured? (get-in inputs [input :honoured])]
                         :when (not (and honoured? (honoured? (str v))))]
                     (setup/input-gap input v (get-in inputs [input :hint] default-hint)))
        gaps (cond-> (vec (for [line lines :let [hint (path-problem line)] :when hint]
                            (setup/input-gap "path" line hint)))
               (empty? lines) (conj {:input "path"
                                     :message "has no `path` input; add the Job cache by hand."})
               true (-> (into key-gaps) (into input-gaps)))
        cache? (and (seq paths) key)]
    (cond-> {:script []
             :outcome (if cache?
                        (str converted (outcome-note left-out))
                        "dropped; add the Job cache by hand (see Gaps)")
             :gaps gaps}
      cache? (assoc :job-settings {:cache [(cond-> {:key key :paths paths}
                                    policy (assoc :policy policy)
                                    (= "true" (str (get with "save-always"))) (assoc :when "always"))]}))))

(defmethod action-translator/translate "actions/cache" [step] (translate-cache step))
(defmethod action-translator/translate "actions/cache/restore" [step] (translate-cache step))
(defmethod action-translator/translate "actions/cache/save" [step] (translate-cache step))
