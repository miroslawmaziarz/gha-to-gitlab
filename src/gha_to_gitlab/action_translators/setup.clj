(ns gha-to-gitlab.action-translators.setup
  "What the setup Action Translators (`actions/setup-node`,
  `actions/setup-python`, `actions/setup-java`) share. Instead of installing
  a tool, the Job runs in an image that has it: the translator returns
  `:job-settings {:image ..}`, which overrides the Runner Mapping image
  (ADR-0002). The Step itself is dropped. An input the image can't honour is
  a Gap naming the input, and when no image can be chosen the Job keeps its
  Runner Mapping image and the Gaps say why. The `cache` input becomes a Job
  cache inside the project directory, keyed on the package manager's lock
  file (or `cache-dependency-path`), plus the Variables that point the tool
  at it (see `package-cache`)."
  (:require [clojure.string :as str]
            [gha-to-gitlab.action-translators.inputs :as inputs]
            [gha-to-gitlab.action-translators.paths :as paths]
            [gha-to-gitlab.ci-model :as ci-model]))

(def common-inputs
  "Inputs several setup Actions share: `:honoured` is a predicate on the
  value (as a string) that is true when the image honours it or it makes no
  difference there; `:hint` is what a person should do otherwise."
  {"check-latest" {:honoured any?} ; image tags already float to the latest patch
   "token" {:honoured any?} ; only used to download the tool on GitHub
   ;; `cache` and `cache-dependency-path` are handled by `package-cache`
   "architecture" {:hint "an image runs on the runner's architecture; choose the runner (tags) for it by hand"}})

(def ^:private default-hint
  "the Job image has no equivalent; check the Job works without it")

(defn- set-image-by-hand
  "The end of a Gap hint: set the image by hand, with `example` as a guide."
  [example]
  (str "set the Job's image (e.g. `" example "`) by hand"))

(defn missing-input-gap
  "The Gap for a required input the Step doesn't give."
  [input example]
  {:input input
   :message (str "has no `" input "` input; " (set-image-by-hand example) ".")})

(defn version-gap
  "The Gap for a version input whose value is not one exact version; `spec`
  is the `translate-setup` spec."
  [{:keys [version-input example]} version]
  (inputs/input-gap version-input version
                    (if (str/includes? (str version) "${{")
                      (str "the version is only known when the Workflow runs; resolve it and "
                           (set-image-by-hand example))
                      (str "an image needs one exact version; " (set-image-by-hand example)))))

(defn- unquoted-number-gap
  "The Gap for a version YAML read as a non-integer number, which may have
  lost digits (`3.10` is read as 3.1)."
  [{:keys [version-input example]} version]
  {:input version-input
   :message (str "input `" version-input ": " version "` was read as a number, which can drop digits "
                 "(`3.10` becomes `3.1`); quote the version in the Workflow (e.g. `'3.10'`) and start "
                 "a new Migration Run, or " (set-image-by-hand example) ".")})

(defn exact-version
  "`version` as an image tag when it names one version: `20`, `20.11.1`, or
  `20.x`/`3.12.x` (the latest of that line, which the image tag also means),
  or a whole Variable reference `${X}` (a Matrix Variable, whose values
  the Source reader checks one by one); nil otherwise (ranges,
  expressions, lists)."
  [version]
  (let [v (str/trim (str version))]
    (or (re-matches #"\d+(?:\.\d+){0,2}" v)
        (re-matches #"\$\{[A-Za-z0-9_]+\}" v)
        (second (re-matches #"(\d+(?:\.\d+)?)\.x" v)))))

(def ^:private by-hand-cache-hint
  "cache the package manager's directory with a Job cache by hand (as `actions/cache` would)")

(defn- dependency-files
  "The `cache-dependency-path` lines as cache key files, or `{:hint ..}`."
  [value]
  (let [files (paths/lines value)]
    (cond
      (some paths/expression? files) {:hint "the files are only known when the Workflow runs; key the Job cache on them by hand"}
      (some paths/exclusion? files) {:hint "a GitLab cache key can't exclude files; key the Job cache on the files by hand"}
      (< ci-model/cache-key-files-limit (count files))
      {:hint (str "a GitLab cache key can depend on at most " ci-model/cache-key-files-limit
                  " files; key the Job cache on them by hand")}
      :else {:files files})))

(defn- package-cache
  "The Job cache for the `cache` input: `{:manager m :job-settings {:cache [..]
  :variables {..}}}` from the spec's `:caches`, the known package managers
  (see `gha-to-gitlab.action-translators.package-caches`), or `{:gaps
  [..]}`; nil without a `cache` input."
  [{:keys [caches]} with]
  (when-let [manager (some-> (get with "cache") str str/trim not-empty)]
    (let [{:keys [files paths variables] :as known} (get caches (str/lower-case manager))
          dependency-path (get with "cache-dependency-path")
          {dep-files :files dep-hint :hint} (when dependency-path (dependency-files dependency-path))]
      (cond
        (nil? known) {:gaps [(inputs/input-gap "cache" manager
                                               (str "not one of the known package managers ("
                                                    (str/join ", " (map #(str "`" % "`") (sort (keys caches))))
                                                    "); " by-hand-cache-hint))]}
        dep-hint {:gaps [(inputs/input-gap "cache-dependency-path" dependency-path dep-hint)]}
        :else {:manager (str/lower-case manager)
               :job-settings {:cache [{:key {:files (or dep-files files) :prefix (str/lower-case manager)}
                                       :paths paths}]
                              :variables variables}}))))

(defn translate-setup
  "The translation of a setup Action Step. `spec` holds:

    :version-input   name of the version input, e.g. \"node-version\"
    :version-file    name of the version-file input, e.g. \"node-version-file\"
    :image           (fn [spec with version]) → `{:image .. :gaps [..]}`
                     (either key optional); `version` is the version
                     input's value, never a YAML non-integer number
    :default-image   image when neither version input is given, with
                     :default-note for the outcome; nil makes it a Gap
    :inputs          per-input `{:honoured :hint}` beyond `common-inputs`
    :caches          per package manager the `cache` input names, the
                     Job cache that replaces it (see `package-cache`)
    :example         an example image for Gap hints"
  [{:keys [with]} {:keys [version-input version-file image default-image default-note inputs example] :as spec}]
  (let [with (update-keys with name)
        version (get with version-input)
        file (get with version-file)
        inputs (merge common-inputs inputs)
        {image-name :image image-gaps :gaps}
        (cond
          (and (number? version) (not (integer? version))) {:gaps [(unquoted-number-gap spec version)]}
          (some? version) (image spec with version)
          file {:gaps [(inputs/input-gap version-file file
                                         (str "read the version from that file and " (set-image-by-hand example)))]}
          default-image {:image default-image}
          :else {:gaps [(missing-input-gap version-input example)]})
        {:keys [manager job-settings] cache-gaps :gaps} (package-cache spec with)
        input-gaps (inputs/unhonoured-gaps with inputs
                                           #{version-input version-file "cache" "cache-dependency-path"}
                                           default-hint)]
    (cond-> {:script []
             :outcome (str (if image-name
                             (str "converted to the Job image `" image-name "`"
                                  (when (and (nil? version) (nil? file) default-note)
                                    (str " (" default-note ")"))
                                  (when manager (str " and a Job cache for " manager)))
                             (str (if manager
                                    (str "converted to a Job cache for " manager)
                                    "dropped")
                                  "; set the Job's image by hand (see Gaps)")))
             :gaps (-> (vec image-gaps) (into cache-gaps) (into input-gaps))}
      image-name (assoc-in [:job-settings :image] image-name)
      job-settings (update :job-settings merge job-settings))))

(defn versioned-image
  "An `:image` fn for `translate-setup`: `<repository>:<tag>` where `tag-for`
  turns the version into a tag (nil when it can't, which is a Gap)."
  [repository tag-for]
  (fn [spec _with version]
    (if-let [tag (tag-for version)]
      {:image (str repository ":" tag)}
      {:gaps [(version-gap spec version)]})))
