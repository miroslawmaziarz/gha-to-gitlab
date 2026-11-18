(ns gha-to-gitlab.test-support
  (:require [gha-to-gitlab.migration :as migration])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn temp-dir
  "Path of a fresh empty temporary directory."
  []
  (str (Files/createTempDirectory "gha-to-gitlab" (make-array FileAttribute 0))))

(defn converted-model
  "The finished CI Model of a Migration Run on one Workflow, at
  `.github/workflows/ci.yml`, with no Migration Config."
  [workflow-yaml]
  (:model (migration/convert {:workflows {".github/workflows/ci.yml" workflow-yaml}})))

(defn action-settings
  "The Job settings an Action can set, as the finished `job` has them; nil
  when it has none. For Jobs without `runs-on`, which no Runner Mapping
  touches, these come only from the Job's Actions."
  [job]
  (not-empty (select-keys job [:image :tags :variables :cache :artifacts])))
