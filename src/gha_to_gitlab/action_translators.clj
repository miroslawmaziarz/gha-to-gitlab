(ns gha-to-gitlab.action-translators
  "Loads every Action Translator. `gha-to-gitlab.github` requires this
  namespace once, so adding an Action Translator means writing its own
  namespace (one `defmethod` on `gha-to-gitlab.action-translator/translate`)
  and adding one require line here. Nothing else in the conversion changes."
  (:require [gha-to-gitlab.action-translators.cache]
            [gha-to-gitlab.action-translators.checkout]
            [gha-to-gitlab.action-translators.setup-node]
            [gha-to-gitlab.action-translators.setup-python]
            [gha-to-gitlab.action-translators.setup-java]
            [gha-to-gitlab.action-translators.upload-artifact]))
