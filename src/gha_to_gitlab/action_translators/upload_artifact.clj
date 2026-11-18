(ns gha-to-gitlab.action-translators.upload-artifact
  "Action Translator for `actions/upload-artifact`: the Step becomes the
  Job's artifacts. `path` lines become `paths` (lines starting with `!`
  become `exclude`), `name` the artifacts' name and `retention-days` their
  `expire_in`. From v4.4 upload-artifact leaves hidden files out unless
  `include-hidden-files` is true, and GitLab keeps them, so they get
  `exclude` patterns. GitLab keeps one artifacts archive per Job, so a second
  upload in a Job is a Gap (see `gha-to-gitlab.github`)."
  (:require [clojure.string :as str]
            [gha-to-gitlab.action-translator :as action-translator]
            [gha-to-gitlab.action-translators.inputs :as inputs]
            [gha-to-gitlab.action-translators.paths :as paths]))

(defn- path-problem
  "Why GitLab can't keep `path` as an artifact, as a Gap hint; nil when it can."
  [path]
  (cond
    (paths/expression? path)
    "the path is only known when the Workflow runs; resolve it and add it to the Job artifacts by hand"
    (paths/outside-project? path)
    "GitLab keeps only paths inside the project directory; copy the files into it in a script line and add that path by hand"))

(defn- retention->expire-in
  "`expire_in` for a `retention-days` value: `{:expire-in \"N days\"}`, `{}`
  for 0 (the repository's default, as GitLab's default is the instance's),
  or `{:hint ..}`."
  [days]
  (let [n (some-> (re-matches #"\s*(\d+)\s*" (str days)) second parse-long)]
    (cond
      (nil? n) {:hint "set the Job artifacts' `expire_in` by hand"}
      (zero? n) {}
      :else {:expire-in (str n (if (= 1 n) " day" " days"))})))

(def ^:private inputs
  "Inputs beyond `path`, `name` and `retention-days`: `:honoured` is a
  predicate on the value (as a string) that is true when the Job artifacts
  honour it or it makes no difference there; `:hint` is what a person
  should do otherwise."
  {"if-no-files-found" {:honoured #(#{"warn" "ignore"} (str/lower-case %))
                        :hint "GitLab only warns when an artifacts path matches no files; add a script line that fails when they are missing by hand"}
   "compression-level" {:honoured any?}
   "overwrite" {:honoured any?} ; each GitLab Job has its own artifacts
   "include-hidden-files" {:honoured any?}}) ; see `hidden-file-excludes`

(def ^:private hidden-file-excludes
  "`exclude` patterns for hidden files and directories (names starting with
  `.`) at any depth."
  ["**/.*" "**/.*/**"])

(defn- leaves-out-hidden-files?
  "True when the upload leaves hidden files out: from v4.4 on (and for a
  version this can't read, such as a commit SHA, taken as the latest)
  unless `include-hidden-files` is true. GitLab keeps them, so they need
  `exclude` patterns."
  [uses with]
  (let [[_ major minor] (re-find #"@v(\d+)(?:\.(\d+))?" uses)
        major (some-> major parse-long)
        minor (some-> minor parse-long)]
    (and (not= "true" (str/lower-case (str (get with "include-hidden-files"))))
         (or (nil? major) (< 4 major) (and (= 4 major) (or (nil? minor) (<= 4 minor)))))))

(def ^:private default-hint
  "the Job artifacts have no equivalent; check the Job works without it")

(defmethod action-translator/translate "actions/upload-artifact"
  [{:keys [uses with]}]
  (let [with (update-keys with name)
        lines (paths/lines (get with "path"))
        line-path #(if (paths/exclusion? %) (subs % 1) %)
        convertible-lines (remove (comp path-problem line-path) lines)
        includes (vec (remove paths/exclusion? convertible-lines))
        hidden-excluded? (leaves-out-hidden-files? uses with)
        excludes (cond-> (vec (keep #(when (paths/exclusion? %) (subs % 1)) convertible-lines))
                   hidden-excluded? (into hidden-file-excludes))
        artifact-name (get with "name")
        name-hint (when (and artifact-name (paths/expression? (str artifact-name)))
                    "the name is only known when the Workflow runs; write the Job artifacts' name (it may use GitLab Variables) by hand")
        {:keys [expire-in] days-hint :hint} (some-> (get with "retention-days") retention->expire-in)
        gaps (concat
              (when name-hint [(inputs/input-gap "name" artifact-name name-hint)])
              (for [line lines :let [hint (path-problem (line-path line))] :when hint]
                (inputs/input-gap "path" line hint))
              (when (empty? lines)
                [{:input "path" :message "has no `path` input; add the Job artifacts by hand."}])
              (when days-hint [(inputs/input-gap "retention-days" (get with "retention-days") days-hint)])
              (inputs/unhonoured-gaps with inputs #{"path" "name" "retention-days"} default-hint))]
    (cond-> {:script []
             :outcome (if (seq includes)
                        (str "converted to the Job artifacts"
                             (when hidden-excluded?
                               " (hidden files left out with `exclude` patterns, as upload-artifact v4.4 and later does)"))
                        "dropped; add the Job artifacts by hand (see Gaps)")
             :gaps (vec gaps)}
      (seq includes)
      ;; built in the order GitLab writes them, so a conflict Gap reads the same
      (assoc :job-settings {:artifacts (cond-> {}
                                         (and artifact-name (not name-hint)) (assoc :name (str artifact-name))
                                         true (assoc :paths includes)
                                         (seq excludes) (assoc :exclude excludes)
                                         expire-in (assoc :expire-in expire-in))}))))
