(ns gha-to-gitlab.action-translators.inputs
  "What every Action Translator does with a Step's inputs: an input it can't
  convert becomes a Gap naming the input, never silently dropped (see
  `gha-to-gitlab.action-translator`)."
  (:require [clojure.string :as str]))

(defn one-line
  "`value` as text on one line, so it stays inside one Migration Report bullet."
  [value]
  (str/join " " (str/split (str/trim (str value)) #"\s*\n\s*")))

(defn input-gap
  "The Gap for input `input` with `value`, which has no GitLab equivalent;
  `hint` says what a person should do instead."
  [input value hint]
  {:input input
   :message (str "input `" input ": " (one-line value) "` has no GitLab equivalent: " hint ".")})

(defn unhonoured-gaps
  "One Gap per input of `with` (input name → value) that is not in
  `handled` (a set of names the translator converts itself) and that
  `inputs` doesn't honour. `inputs` maps an input name to `:honoured`, a
  predicate on the value as a string that is true when GitLab honours it or
  it makes no difference there, and `:hint`, what a person should do
  otherwise; an input missing from `inputs` gets `default-hint`."
  [with inputs handled default-hint]
  (vec (for [[input v] with
             :when (not (contains? handled input))
             :let [honoured? (get-in inputs [input :honoured])]
             :when (not (and honoured? (honoured? (str v))))]
         (input-gap input v (get-in inputs [input :hint] default-hint)))))
