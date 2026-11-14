(ns gha-to-gitlab.test-support
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn temp-dir
  "Path of a fresh empty temporary directory."
  []
  (str (Files/createTempDirectory "gha-to-gitlab" (make-array FileAttribute 0))))
