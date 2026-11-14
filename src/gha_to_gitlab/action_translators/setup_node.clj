(ns gha-to-gitlab.action-translators.setup-node
  "Action Translator for `actions/setup-node`: the Job runs in the `node:`
  image for the requested version (see `gha-to-gitlab.action-translators.setup`).
  With no `node-version` the Job gets `node:lts`."
  (:require [clojure.string :as str]
            [gha-to-gitlab.action-translator :as action-translator]
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
    :caches {"npm" {:files ["package-lock.json"] :paths [".npm"]
                    :variables {"npm_config_cache" "$CI_PROJECT_DIR/.npm"}}
             "yarn" {:files ["yarn.lock"] :paths [".yarn-cache"]
                     ;; Yarn 2+ only uses the cache folder without its global cache
                     :variables {"YARN_CACHE_FOLDER" "$CI_PROJECT_DIR/.yarn-cache"
                                 "YARN_ENABLE_GLOBAL_CACHE" "false"}}}
    :inputs {"registry-url" {:hint registry-hint}
             "scope" {:hint registry-hint}
             "always-auth" {:hint registry-hint}}}))
