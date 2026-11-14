(ns gha-to-gitlab.action-translators.setup-python
  "Action Translator for `actions/setup-python`: the Job runs in the
  `python:` image for the requested CPython version (see
  `gha-to-gitlab.action-translators.setup`). With no `python-version` the
  Job gets `python:3`. PyPy, GraalPy, prereleases, ranges and lists of
  versions are Gaps."
  (:require [gha-to-gitlab.action-translator :as action-translator]
            [gha-to-gitlab.action-translators.setup :as setup]))

(defmethod action-translator/translate "actions/setup-python"
  [step]
  (setup/translate-setup
   step
   {:version-input "python-version"
    :version-file "python-version-file"
    :image (setup/versioned-image "python" setup/exact-version)
    :default-image "python:3"
    :default-note "no `python-version` given, so the latest Python 3"
    :example "python:3.12"
    :inputs {"update-environment" {:honoured #{"true"}
                                   :hint "the image's Python is already on `PATH`; set any variables the Job relied on by hand"}
             "allow-prereleases" {:honoured any?} ; only used when no release matches
             "freethreaded" {:honoured #{"false"}
                             :hint "choose a free-threaded Python image by hand"}
             "pip-version" {:hint "install that pip version in a script line"}
             "pip-install" {:hint "run `pip install` in a script line"}}}))
