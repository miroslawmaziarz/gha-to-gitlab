(ns gha-to-gitlab.config
  "The Migration Config: the optional EDN file a Migration Run reads, e.g.

    {:runner-mappings {\"macos-latest\" {:image \"macos-14-xcode-15\"
                                         :tags [\"saas-macos-medium-m1\"]}}}"
  (:require [clojure.edn :as edn]
            [malli.core :as m]
            [malli.error :as me]))

(def RunnerMapping
  "One Runner Mapping in the Migration Config: the GitLab image a runner
  label becomes and, optionally, the runner tags."
  [:map {:closed true}
   [:image [:string {:min 1}]]
   [:tags {:optional true} [:vector :string]]])

(def Config
  "The Migration Config schema."
  [:map {:closed true}
   [:runner-mappings {:optional true} [:map-of :string RunnerMapping]]])

(def empty-config
  "The Migration Config used when none is given."
  {:runner-mappings {}})

(defn- with-defaults [config]
  (update config :runner-mappings
          #(update-vals (or % {}) (fn [mapping] (merge {:tags []} mapping)))))

(defn load-config
  "Reads and validates the Migration Config at `path`; with no `path`,
  returns the empty Migration Config. Throws ex-info naming the problem when invalid."
  [path]
  (if (nil? path)
    empty-config
    (let [config (edn/read-string (slurp path))]
      (when-not (m/validate Config config)
        (throw (ex-info (str "Invalid Migration Config " path ": "
                             (pr-str (me/humanize (m/explain Config config))))
                        {:path path})))
      (with-defaults config))))
