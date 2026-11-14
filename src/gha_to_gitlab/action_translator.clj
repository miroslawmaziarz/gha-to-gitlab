(ns gha-to-gitlab.action-translator
  "The Action Translator extension point. An Action Translator converts one
  Action into script lines, Gaps and (later) Job settings in the CI Model.
  Supporting a new Action means adding one `defmethod` on `translate`, keyed
  by the Action's name without its version suffix:

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
                          optional `:input` (the input's name); `:message`
                          continues a sentence that the Migration Run starts
                          with the Step's location and \"Action `<uses>` \",
                          e.g. \"input `ref: main` is not supported: ...\".
                          An input a translator doesn't support must be a
                          Gap, never silently dropped.

  Planned, not yet read by the Migration Run: `:job` for Job settings, e.g.
  `{:image \"node:20\"}` from a setup Action, which overrides the Runner
  Mapping image (ADR-0002), and cache and artifacts settings.

  An Action with no translator falls through to the default method, which
  returns nil, and the Migration Run records it as a Gap.

  Each translator lives in its own namespace; add one require line for it
  in `gha-to-gitlab.action-translators` so the Migration Run loads it."
  (:require [clojure.string :as str]))

(defn action-name
  "The Action a `uses:` value names, without its `@version` suffix
  (`actions/checkout@v4` → `actions/checkout`)."
  [uses]
  (first (str/split uses #"@" 2)))

(defmulti translate
  "Converts a Step that uses an Action into `{:script :outcome :gaps}` (see
  the namespace docstring), or returns nil when no Action Translator exists
  for that Action."
  (fn [step] (action-name (:uses step))))

(defmethod translate :default [_step] nil)
