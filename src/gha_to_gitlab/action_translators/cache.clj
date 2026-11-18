(ns gha-to-gitlab.action-translators.cache
  "Action Translators for `actions/cache`, `actions/cache/restore` and
  `actions/cache/save`: the Step becomes one Job cache (restore only is
  `policy: pull`, save only `policy: push`). `path` lines become its paths
  and `key` its key; `hashFiles(..)` in the key becomes a key on those files
  (up to `ci-model/cache-key-files-limit`), with the rest of the key as its
  prefix. A well-known home-directory cache (`~/.npm`, `~/.m2`, ...) moves
  into the project with the Variables that point its tool there, as the
  setup Actions' `cache` input does. `restore-keys` is dropped and the
  outcome says so: GitLab's `fallback_keys` match whole keys, not prefixes,
  and losing it only costs speed. Other paths outside the project directory
  and key expressions GitLab has no Variable for are Gaps. A Job keeps up to 4 caches (`ci-model/caches-per-job-limit`)."
  (:require [clojure.string :as str]
            [gha-to-gitlab.action-translator :as action-translator]
            [gha-to-gitlab.action-translators.inputs :as inputs]
            [gha-to-gitlab.action-translators.package-caches :as package-caches]
            [gha-to-gitlab.action-translators.paths :as paths]
            [gha-to-gitlab.ci-model :as ci-model]))

(defn- path-problem
  "Why GitLab can't cache `path`, as a Gap hint; nil when it can."
  [path]
  (cond
    (paths/expression? path)
    "the path is only known when the Workflow runs; resolve it and add it to the Job cache by hand"
    (paths/exclusion? path)
    "a GitLab cache can't exclude paths; list only the paths to cache by hand"
    (paths/outside-project? path)
    "GitLab caches only paths inside the project directory; point the tool at a directory inside it and cache that by hand"))

(defn- cache-path
  "Where GitLab caches the `path` line: `{:path p}` for a path inside the
  project, `{:path p :moved-from line :manager m :variables {..}}` for a
  well-known home-directory cache that moves into the project (see
  `gha-to-gitlab.action-translators.package-caches`), or `{:hint ..}`."
  [line]
  (if-let [{:keys [path] :as home} (package-caches/home-cache line)]
    (assoc home :path path :moved-from line)
    (if-let [hint (path-problem line)]
      {:hint hint}
      {:path line})))

(def ^:private key-variables
  "GitHub expressions in a cache key that a GitLab Variable can stand for."
  {"github.sha" "$CI_COMMIT_SHA"
   "github.ref_name" "$CI_COMMIT_REF_SLUG" ; a cache key can't contain `/`
   "github.job" "$CI_JOB_NAME_SLUG"})

(def ^:private runner-expressions
  "GitHub expressions a cache key can leave out: GitLab has no Variable for
  them, and leaving them out only lets Jobs on different runners share a key."
  #{"runner.os" "runner.arch"})

(defn- hash-files
  "The file patterns of a `hashFiles(...)` expression; nil for any other."
  [expr]
  (when-let [[_ args] (re-matches #"hashFiles\((.*)\)" expr)]
    (mapv (fn [[_ single-quoted double-quoted]] (or single-quoted double-quoted))
          (re-seq #"'([^']*)'|\"([^\"]*)\"" args))))

(defn- key-parts
  "`key` as a sequence of literal strings and `[:expr \"...\"]` expressions."
  [key]
  (let [m (re-matcher #"\$\{\{\s*(.*?)\s*\}\}" key)]
    (loop [start 0 parts []]
      (if (.find m)
        (recur (.end m) (conj parts (subs key start (.start m)) [:expr (.group m 1)]))
        (conj parts (subs key start))))))

(def ^:private write-key-by-hand "; write the Job cache's key by hand")

(defn- gitlab-key
  "The GitLab cache key for a GitHub cache `key`: `{:key k :left-out-expressions [expr ..]}`,
  where `k` is a string or `{:files [..] :prefix ..}`; or `{:hint ..}` when
  GitLab can't express it."
  [key]
  (let [parts (key-parts key)
        exprs (keep #(when (vector? %) (second %)) parts)
        files (into [] (mapcat hash-files) exprs)
        unknown (first (remove #(or (hash-files %) (key-variables %) (runner-expressions %)) exprs))
        text (-> (str/join (map #(if (vector? %) (get key-variables (second %) "") %) parts))
                 (str/replace #"-{2,}" "-")
                 (str/replace #"^[-_.]+|[-_.]+$" ""))]
    (cond
      unknown {:hint (str "`" unknown "` is only known when the Workflow runs" write-key-by-hand)}
      (< ci-model/cache-key-files-limit (count files)) {:hint (str "a GitLab cache key can depend on at most " ci-model/cache-key-files-limit " files" write-key-by-hand)}
      (some paths/exclusion? files) {:hint (str "a GitLab cache key can't exclude files" write-key-by-hand)}
      (str/includes? text "/") {:hint (str "a GitLab cache key can't contain `/`" write-key-by-hand)}
      (and (empty? files) (str/blank? text)) {:hint (str "nothing GitLab can use is left of the key" write-key-by-hand)}
      :else {:key (if (seq files)
                    (cond-> {:files files} (not (str/blank? text)) (assoc :prefix text))
                    text)
             :left-out-expressions (filter runner-expressions exprs)})))

(defn- key-result [with]
  (if-let [key (get with "key")]
    (let [{:keys [hint] :as result} (gitlab-key (str key))]
      (if hint
        {:gaps [(inputs/input-gap "key" key hint)]}
        result))
    {:gaps [{:input "key" :message "has no `key` input; add the Job cache by hand."}]}))

(def ^:private restore-keys-note
  (str "restore-keys dropped: GitLab's `fallback_keys` match whole keys, not prefixes, "
       "so a new key starts with an empty cache"))

(defn- moved-note [{:keys [moved-from path manager variables]}]
  (str "`" moved-from "` cached as `" path "` in the project, with the Variable"
       (when (< 1 (count variables)) "s") " "
       (str/join " and " (map #(str "`" % "`") (sort (keys variables))))
       " pointing " manager " there"))

(defn- outcome-notes
  "What the outcome adds in parentheses: home-directory caches moved into
  the project, runner expressions left out of the key, dropped restore-keys."
  [moved left-out-expressions restore-keys?]
  (let [notes (concat (map moved-note moved)
                      (when (seq left-out-expressions)
                        [(str "its key leaves out " (str/join ", " (map #(str "`" % "`") (distinct left-out-expressions)))
                              ", which GitLab has no Variable for")])
                      (when restore-keys? [restore-keys-note]))]
    (when (seq notes) (str " (" (str/join "; " notes) ")"))))

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
   ;; only costs speed; the outcome says it was dropped (`restore-keys-note`)
   "restore-keys" {:honoured any?}})

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
        cache-paths (map cache-path lines)
        paths (into [] (comp (remove :hint) (map :path) (distinct)) cache-paths)
        moved (filter :moved-from cache-paths)
        {:keys [key left-out-expressions] key-gaps :gaps} (key-result with)
        input-gaps (inputs/unhonoured-gaps with inputs #{"path" "key"} default-hint)
        gaps (cond-> (vec (for [[line {:keys [hint]}] (map vector lines cache-paths) :when hint]
                            (inputs/input-gap "path" line hint)))
               (empty? lines) (conj {:input "path"
                                     :message "has no `path` input; add the Job cache by hand."})
               true (-> (into key-gaps) (into input-gaps)))
        cache? (and (seq paths) key)]
    (cond-> {:script []
             :outcome (if cache?
                        (str converted (outcome-notes moved left-out-expressions (contains? with "restore-keys")))
                        "dropped; add the Job cache by hand (see Gaps)")
             :gaps gaps}
      cache? (assoc :job-settings
                    (cond-> {:cache [(cond-> {:key key :paths paths}
                                       policy (assoc :policy policy)
                                       (= "true" (str (get with "save-always"))) (assoc :when "always"))]}
                      (seq moved) (assoc :variables (apply merge (map :variables moved))))))))

(defmethod action-translator/translate "actions/cache" [step] (translate-cache step))
(defmethod action-translator/translate "actions/cache/restore" [step] (translate-cache step))
(defmethod action-translator/translate "actions/cache/save" [step] (translate-cache step))
