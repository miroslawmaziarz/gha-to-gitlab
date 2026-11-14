(ns gha-to-gitlab.action-translator
  "The Action Translator extension point. An Action Translator converts one
  Action into script lines in the CI Model. Supporting a new Action means
  adding one `defmethod` on `translate`, keyed by the Action's name without
  its version suffix:

    (defmethod action-translator/translate \"actions/setup-node\"
      [step]
      {:script [\"node --version\"]})

  `step` is the GitHub Step map as read from the Workflow (`:uses`, `:with`,
  `:name`, ...). A translator returns `{:script [line ...]}`. An Action with
  no translator falls through to the default method, which returns nil, and
  the Migration Run records it as a Gap.

  Each translator lives in its own namespace; add one require line for it
  in `gha-to-gitlab.action-translators` so the Migration Run loads it."
  (:require [clojure.string :as str]))

(defn action-name
  "The Action a `uses:` value names, without its `@version` suffix
  (`actions/checkout@v4` → `actions/checkout`)."
  [uses]
  (first (str/split uses #"@" 2)))

(defmulti translate
  "Converts a Step that uses an Action into `{:script [line ...]}`, or
  returns nil when no Action Translator exists for that Action."
  (fn [step] (action-name (:uses step))))

(defmethod translate :default [_step] nil)
