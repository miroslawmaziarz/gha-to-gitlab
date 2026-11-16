(ns gha-to-gitlab.action-translators.setup-node
  "Action Translator for `actions/setup-node`: the Job runs in the `node:`
  image for the requested version (see `gha-to-gitlab.action-translators.setup`).
  With no `node-version` the Job gets `node:lts`."
  (:require [clojure.string :as str]
            [gha-to-gitlab.action-translator :as action-translator]
            [gha-to-gitlab.action-translators.package-caches :as package-caches]
            [gha-to-gitlab.action-translators.setup :as setup]))

(defn- node-tag
  "The `node` image tag for a `node-version` value: an exact version or
  `N.x`, `lts/*` → `lts`, `lts/<codename>` → the codename, and
  `latest`/`current`/`node` → `current`; nil for anything else."
  [version]
  (let [v (str/lower-case (str/trim (str version)))]
    (cond
      (setup/exact-version v) (setup/exact-version v)
      (= "lts/*" v) "lts"
      (re-matches #"lts/[a-z]+" v) (subs v 4)
      (#{"latest" "current" "node"} v) "current")))

(def ^:private registry-hint
  "write the registry settings to `.npmrc` in a script line, with the token as a masked Variable")

(defmethod action-translator/translate "actions/setup-node"
  [step]
  (setup/translate-setup
   step
   {:version-input "node-version"
    :version-file "node-version-file"
    :image (setup/versioned-image "node" node-tag)
    :default-image "node:lts"
    :default-note "no `node-version` given, so the current LTS"
    :example "node:20"
    :caches (select-keys package-caches/package-managers ["npm" "yarn"])
    :inputs {"registry-url" {:hint registry-hint}
             "scope" {:hint registry-hint}
             "always-auth" {:hint registry-hint}}}))
