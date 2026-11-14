(ns gha-to-gitlab.config
  "The optional EDN config file a Migration Run reads, e.g.

    {:runner-mappings {\"macos-latest\" {:image \"macos-14-xcode-15\"
                                         :tags [\"saas-macos-medium-m1\"]}}}"
  (:require [clojure.edn :as edn]
            [malli.core :as m]
            [malli.error :as me]))

(def RunnerMapping
  [:map {:closed true}
   [:image [:string {:min 1}]]
   [:tags {:optional true} [:vector :string]]])

(def Config
  [:map {:closed true}
   [:runner-mappings {:optional true} [:map-of :string RunnerMapping]]])

(def empty-config {:runner-mappings {}})

(defn- with-defaults [config]
  (update config :runner-mappings
          #(update-vals (or % {}) (fn [mapping] (merge {:tags []} mapping)))))

(defn load-config
  "Reads and validates the EDN config file at `path`; with no `path`,
  returns the empty config. Throws ex-info naming the problem when invalid."
  [path]
  (if (nil? path)
    empty-config
    (let [config (edn/read-string (slurp path))]
      (when-not (m/validate Config config)
        (throw (ex-info (str "Invalid config file " path ": "
                             (pr-str (me/humanize (m/explain Config config))))
                        {:path path})))
      (with-defaults config))))
