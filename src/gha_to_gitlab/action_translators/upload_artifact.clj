(ns gha-to-gitlab.action-translators.upload-artifact
  "Action Translator for `actions/upload-artifact`: the Step becomes the
  Job's artifacts. `path` lines become `paths` (lines starting with `!`
  become `exclude`), `name` the artifacts' name and `retention-days` their
  `expire_in`. GitLab keeps one artifacts archive per Job, so a second
  upload in a Job is a Gap (see `gha-to-gitlab.github`)."
  (:require [clojure.string :as str]
            [gha-to-gitlab.action-translator :as action-translator]
            [gha-to-gitlab.action-translators.paths :as paths]
            [gha-to-gitlab.action-translators.setup :as setup]))

(defn- path-problem
  "Why GitLab can't keep `path` as an artifact, as a Gap hint; nil when it can."
  [path]
  (cond
    (paths/expression? path)
    "the path is only known when the Workflow runs; resolve it and add it to the Job artifacts by hand"
    (paths/outside-project? path)
    "GitLab keeps only paths inside the project directory; copy the files into it in a script line and add that path by hand"))

(defn- expire-in
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
   "include-hidden-files" {:honoured #{"true"}
                           :hint "GitLab keeps hidden files too; add `exclude` patterns to the Job artifacts for any that must not be kept by hand"}})

(def ^:private default-hint
  "the Job artifacts have no equivalent; check the Job works without it")

(defmethod action-translator/translate "actions/upload-artifact"
  [{:keys [with]}]
  (let [with (update-keys with name)
        lines (paths/lines (get with "path"))
        line-path #(if (str/starts-with? % "!") (subs % 1) %)
        ok (remove (comp path-problem line-path) lines)
        includes (vec (remove #(str/starts-with? % "!") ok))
        excludes (vec (keep #(when (str/starts-with? % "!") (subs % 1)) ok))
        artifact-name (get with "name")
        name-hint (when (and artifact-name (paths/expression? (str artifact-name)))
                    "the name is only known when the Workflow runs; write the Job artifacts' name (it may use GitLab Variables) by hand")
        {:keys [expire-in] days-hint :hint} (some-> (get with "retention-days") expire-in)
        gaps (concat
              (when name-hint [(setup/input-gap "name" artifact-name name-hint)])
              (for [line lines :let [hint (path-problem (line-path line))] :when hint]
                (setup/input-gap "path" line hint))
              (when (empty? lines)
                [{:input "path" :message "has no `path` input; add the Job artifacts by hand."}])
              (when days-hint [(setup/input-gap "retention-days" (get with "retention-days") days-hint)])
              (for [[input v] with
                    :when (not (#{"path" "name" "retention-days"} input))
                    :let [honoured? (get-in inputs [input :honoured])]
                    :when (not (and honoured? (honoured? (str v))))]
                (setup/input-gap input v (get-in inputs [input :hint] default-hint))))]
    (cond-> {:script []
             :outcome (if (seq includes)
                        "converted to the Job artifacts"
                        "dropped; add the Job artifacts by hand (see Gaps)")
             :gaps (vec gaps)}
      (seq includes)
      ;; built in the order GitLab writes them, so a conflict Gap reads the same
      (assoc :job-settings {:artifacts (cond-> {}
                                         (and artifact-name (not name-hint)) (assoc :name (str artifact-name))
                                         true (assoc :paths includes)
                                         (seq excludes) (assoc :exclude excludes)
                                         expire-in (assoc :expire-in expire-in))}))))
