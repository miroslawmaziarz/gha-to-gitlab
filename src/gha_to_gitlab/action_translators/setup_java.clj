(ns gha-to-gitlab.action-translators.setup-java
  "Action Translator for `actions/setup-java`: the Job runs in the JDK image
  of the requested `distribution` and `java-version` (see
  `gha-to-gitlab.action-translators.setup`). Both inputs are required, as
  they are on GitHub, so a missing one is a Gap; so is a distribution with
  no image rule below."
  (:require [clojure.string :as str]
            [gha-to-gitlab.action-translator :as action-translator]
            [gha-to-gitlab.action-translators.setup :as setup]))

(def ^:private images
  "Per `distribution`: the JDK image for a version tag."
  {"temurin" #(str "eclipse-temurin:" %)
   "zulu" #(str "azul/zulu-openjdk:" %)
   "corretto" #(str "amazoncorretto:" %)
   "microsoft" #(str "mcr.microsoft.com/openjdk/jdk:" % "-ubuntu")
   "semeru" #(str "ibm-semeru-runtimes:open-" % "-jdk")
   "sapmachine" #(str "sapmachine:" %)})

(def ^:private example "eclipse-temurin:21")

(defn- java-image [with version]
  (let [distribution (get with "distribution")
        image-for (get images (some-> distribution str str/lower-case))
        tag (setup/exact-version version)]
    (cond
      (nil? distribution)
      {:gaps [{:input "distribution"
               :message (str "has no `distribution` input; set the Job's image (e.g. `" example "`) by hand.")}]}
      (nil? image-for)
      {:gaps [(setup/input-gap "distribution" distribution
                               (str "no image rule for this distribution (rules exist for "
                                    (str/join ", " (map #(str "`" % "`") (sort (keys images))))
                                    "); set the Job's image by hand"))]}
      (nil? tag)
      {:gaps [(setup/version-gap "java-version" version example)]}
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
