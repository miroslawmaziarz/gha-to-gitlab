(ns gha-to-gitlab.action-translator
  "The Action Translator extension point. An Action Translator converts one
  Action into script lines, Gaps and Job settings in the CI Model.
  Supporting a new Action means adding one `defmethod` on `translate`, keyed
  by the Action's name in lower case without its version suffix (GitHub
  owner and repository names are case-insensitive):

    (defmethod action-translator/translate \"actions/setup-node\"
      [step]
      {:script [\"node --version\"]
       :outcome \"converted to script lines\"})

  `step` is the GitHub Step map as read from the Workflow (`:uses`, `:with`,
  `:name`, ...). A translator returns a map with these keys, all optional:

    :script   [line ...]  script lines that replace the Step, in order;
                          empty or missing drops the Step.
    :outcome  string      what became of the Action, as the Migration Report
                          shows it after the Action's name, e.g.
                          \"dropped (GitLab checks out the repository
                          automatically)\"; defaults to \"converted\".
    :gaps     [gap ...]   what the translator could not convert, such as an
                          unsupported input. Each gap is `{:message ..}` plus
                          optional `:input` (the input's name); any other
                          key is dropped. `:message` continues a sentence
                          that `gha-to-gitlab.gap/at` starts with the Step's
                          location and \"Action `<uses>` \",
                          e.g. \"input `ref: main` has no GitLab equivalent: ...\".
                          An input a translator doesn't support must be a
                          Gap, never silently dropped.
    :job-settings {k v}   Job settings for the Step's Job, e.g.
                          `{:image \"node:20\"}` from a setup Action; keys
                          are CI Model Job keys (`gha-to-gitlab.ci-model/Job`).
                          `gha-to-gitlab.action-job-settings` settles them
                          per Job (the earliest Action wins; list settings
                          such as `:cache` and map settings such as
                          `:variables` collect every Action's entries; a
                          conflict is a Gap) and applies them after the
                          Runner Mapping, so they win over it (ADR-0002).

  An Action with no translator falls through to the default method, which
  returns nil, and the Migration Run records it as a Gap.

  Each translator lives in its own namespace; add one require line for it
  in `gha-to-gitlab.action-translators` so the Migration Run loads it."
  (:require [clojure.string :as str]))

(defn action-name
  "The Action a `uses:` value names, in lower case and without its
  `@version` suffix (`Actions/Checkout@v4` → `actions/checkout`)."
  [uses]
  (str/lower-case (first (str/split uses #"@" 2))))

(defmulti translate
  "Converts a Step that uses an Action into `{:script :outcome :gaps :job-settings}` (see
  the namespace docstring), or returns nil when no Action Translator exists
  for that Action."
  (fn [step] (action-name (:uses step))))

(defmethod translate :default [_step] nil)
