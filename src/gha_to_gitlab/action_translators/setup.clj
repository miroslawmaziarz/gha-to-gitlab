(ns gha-to-gitlab.action-translators.setup
  "What the setup Action Translators (`actions/setup-node`,
  `actions/setup-python`, `actions/setup-java`) share. Instead of installing
  a tool, the Job runs in an image that has it: the translator returns
  `:job-settings {:image ..}`, which overrides the Runner Mapping image
  (ADR-0002). The Step itself is dropped. An input the image can't honour is
  a Gap naming the input, and when no image can be chosen the Job keeps its
  Runner Mapping image and the Gaps say why."
  (:require [clojure.string :as str]))

(defn one-line
  "`value` as text on one line, so it stays inside one Migration Report bullet."
  [value]
  (str/join " " (str/split (str/trim (str value)) #"\s*\n\s*")))

(defn input-gap [input value hint]
  {:input input
   :message (str "input `" input ": " (one-line value) "` has no GitLab equivalent: " hint ".")})

(def common-inputs
  "Inputs several setup Actions share: `:honoured` is a predicate on the
  value (as a string) that is true when the image honours it or it makes no
  difference there; `:hint` is what a person should do otherwise."
  {"check-latest" {:honoured any?} ; image tags already float to the latest patch
   "token" {:honoured any?} ; only used to download the tool on GitHub
   "cache" {:hint "cache the package manager's directory with a Job cache by hand (as `actions/cache` would)"}
   "cache-dependency-path" {:hint "key the Job cache on these files by hand (as `actions/cache` would)"}
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
  (input-gap version-input version
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
  `20.x`/`3.12.x` (the latest of that line, which the image tag also means);
  nil otherwise (ranges, expressions, lists)."
  [version]
  (let [v (str/trim (str version))]
    (or (re-matches #"\d+(?:\.\d+){0,2}" v)
        (second (re-matches #"(\d+(?:\.\d+)?)\.x" v)))))

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
          file {:gaps [(input-gap version-file file
                                  (str "read the version from that file and " (set-image-by-hand example)))]}
          default-image {:image default-image}
          :else {:gaps [(missing-input-gap version-input example)]})
        input-gaps (for [[input v] with
                         :when (not (#{version-input version-file} input))
                         :let [honoured? (get-in inputs [input :honoured])]
                         :when (not (and honoured? (honoured? (str v))))]
                     (input-gap input v (get-in inputs [input :hint] default-hint)))]
    (cond-> {:script []
             :outcome (if image-name
                        (str "converted to the Job image `" image-name "`"
                             (when (and (nil? version) (nil? file) default-note)
                               (str " (" default-note ")")))
                        "dropped; set the Job's image by hand (see Gaps)")
             :gaps (into (vec image-gaps) input-gaps)}
      image-name (assoc :job-settings {:image image-name}))))

(defn versioned-image
  "An `:image` fn for `translate-setup`: `<repository>:<tag>` where `tag-for`
  turns the version into a tag (nil when it can't, which is a Gap)."
  [repository tag-for]
  (fn [spec _with version]
    (if-let [tag (tag-for version)]
      {:image (str repository ":" tag)}
      {:gaps [(version-gap spec version)]})))
