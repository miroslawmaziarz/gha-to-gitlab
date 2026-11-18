(ns gha-to-gitlab.action-translators.setup-java
  "Action Translator for `actions/setup-java`: the Job runs in the JDK image
  of the requested `distribution` and `java-version` (see
  `gha-to-gitlab.action-translators.setup`). Both inputs are required, as
  they are on GitHub, so a missing one is a Gap; so is a distribution with
  no image template below. `1.N` means Java N; a version below the feature
  release (`21.0.2`) uses the feature release's image plus a Gap, since not
  every image publishes such tags."
  (:require [clojure.string :as str]
            [gha-to-gitlab.action-translator :as action-translator]
            [gha-to-gitlab.action-translators.inputs :as inputs]
            [gha-to-gitlab.action-translators.package-caches :as package-caches]
            [gha-to-gitlab.action-translators.setup :as setup]))

(def ^:private image-templates
  "Per `distribution`: the JDK image for a feature-release tag (`21`)."
  {"temurin" #(str "eclipse-temurin:" %)
   "zulu" #(str "azul/zulu-openjdk:" %)
   "corretto" #(str "amazoncorretto:" %)
   "microsoft" #(str "mcr.microsoft.com/openjdk/jdk:" % "-ubuntu")
   "semeru" #(str "ibm-semeru-runtimes:open-" % "-jdk")
   "sapmachine" #(str "sapmachine:" %)})

(def ^:private example "eclipse-temurin:21")

(defn- feature-release
  "The Java feature release a version names, and whether the version was
  more precise than that: `21` → [\"21\" false], `1.8` → [\"8\" false],
  `17.x` → [\"17\" false], `21.0.2` → [\"21\" true]; nil for anything
  else. Not every image publishes tags below the feature release, so the
  image uses that tag only."
  [version]
  (when-let [v (setup/exact-version version)]
    (let [[major & more] (str/split v #"\.")
          [major more] (if (and (= "1" major) (seq more)) [(first more) (rest more)] [major more])]
      [major (boolean (and (seq more) (not (str/includes? (str version) ".x"))))])))

(defn- java-image [spec with version]
  (let [distribution (get with "distribution")
        image-for (get image-templates (some-> distribution str str/lower-case))
        [tag more-precise?] (feature-release version)]
    (cond
      (nil? distribution)
      {:gaps [(setup/missing-input-gap "distribution" example)]}
      (nil? image-for)
      {:gaps [(inputs/input-gap "distribution" distribution
                                (str "no image template for this distribution (templates exist for "
                                     (str/join ", " (map #(str "`" % "`") (sort (keys image-templates))))
                                     "); set the Job's image by hand"))]}
      (nil? tag)
      {:gaps [(setup/version-gap spec version)]}
      more-precise?
      {:image (image-for tag)
       :gaps [(inputs/input-gap "java-version" version
                                (str "JDK image tags name the feature release, so the Job uses `"
                                     (image-for tag) "` (its latest update); pin an exact image tag by hand"
                                     " if the Job needs " version))]}
      :else {:image (image-for tag)})))

(def ^:private maven-settings-hint
  "write Maven's `settings.xml` in a script line, with secrets as masked Variables")

(def ^:private gpg-hint
  "import the key in a script line from a masked Variable")

(defmethod action-translator/translate "actions/setup-java"
  [step]
  (setup/translate-setup
   step
   {:version-input "java-version"
    :version-file "java-version-file"
    :image java-image
    :example example
    :caches (select-keys package-caches/package-managers ["maven" "gradle"])
    :inputs {"distribution" {:honoured any?} ; checked by java-image
             "java-package" {:honoured #{"jdk"}
                             :hint "choose an image with that package (such as a `-jre` tag) by hand"}
             "jdkFile" {:hint "install the JDK from that file in a script line"}
             "server-id" {:hint maven-settings-hint}
             "server-username" {:hint maven-settings-hint}
             "server-password" {:hint maven-settings-hint}
             "settings-path" {:hint maven-settings-hint}
             "overwrite-settings" {:hint maven-settings-hint}
             "gpg-private-key" {:hint gpg-hint}
             "gpg-passphrase" {:hint gpg-hint}
             "mvn-toolchain-id" {:hint "write Maven's `toolchains.xml` in a script line"}
             "mvn-toolchain-vendor" {:hint "write Maven's `toolchains.xml` in a script line"}}}))
