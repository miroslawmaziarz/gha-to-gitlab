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

(defn validate-config
  "Checks the Migration Config value `config` and returns it with defaults
  filled in (a Runner Mapping without `:tags` has none); nil is the empty
  Migration Config. Throws ex-info naming the problem when invalid;
  `path`, when given, names the file it came from in that message."
  ([config] (validate-config config nil))
  ([config path]
   (cond
     (nil? config) empty-config
     (m/validate Config config) (with-defaults config)
     :else (throw (ex-info (str "Invalid Migration Config" (when path (str " " path)) ": "
                                (pr-str (me/humanize (m/explain Config config))))
                           (cond-> {:errors (me/humanize (m/explain Config config))}
                             path (assoc :path path)))))))

(defn load-config
  "Reads and validates the Migration Config at `path` (see
  `validate-config`); with no `path`, returns the empty Migration Config."
  [path]
  (if path
    (validate-config (edn/read-string (slurp path)) path)
    empty-config))
