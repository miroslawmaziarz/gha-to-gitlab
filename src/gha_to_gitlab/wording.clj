(ns gha-to-gitlab.wording
  "Small text helpers shared by the conversion: Source values as short
  text (for Gap messages and labels such as a runner group's `runs-on`),
  lists of code spans, English ordinals, shell quoting for script lines,
  and text escaped for GitLab regex literals in rules."
  (:require [clojure.string :as str]))

(defn yaml-ish
  "A YAML value as short flow-style text, e.g. `[linux, x64]` or
  `{name: dist, paths: [dist]}`."
  [v]
  (cond
    (sequential? v) (str "[" (str/join ", " (map yaml-ish v)) "]")
    (map? v) (str "{" (str/join ", " (map (fn [[k x]] (str (name k) ": " (yaml-ish x))) v)) "}")
    :else (str v)))

(defn backticked
  "`xs` as a comma-separated list of code spans: `a`, `b`."
  [xs]
  (str/join ", " (map #(str "`" % "`") xs)))

(defn ordinal
  "`n` as an English ordinal: 1st, 2nd, 3rd, 4th, 11th, 12th, 13th, 21st, ..."
  [n]
  (str n (if (#{11 12 13} (mod n 100))
           "th"
           (case (mod n 10) 1 "st" 2 "nd" 3 "rd" "th"))))

(defn shell-quote
  "`s` as one single-quoted POSIX shell word."
  [s]
  (str "'" (str/replace s "'" "'\\''") "'"))

(defn regex-literal
  "Text `s` as it matches itself inside a GitLab regex literal (`/../`, RE2):
  regex operators and `/`, which ends the literal, are escaped."
  [s]
  (str/replace s #"[\\.^$|?*+()\[\]{}/]" "\\\\$0"))
