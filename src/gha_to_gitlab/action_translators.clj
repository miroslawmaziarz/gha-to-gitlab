(ns gha-to-gitlab.action-translators
  "Loads every Action Translator. `gha-to-gitlab.github` requires this
  namespace once, so adding an Action Translator means writing its own
  namespace (one `defmethod` on `gha-to-gitlab.action-translator/translate`)
  and adding one require line here, for example
  `(:require [gha-to-gitlab.action-translators.checkout])`.
  Nothing else in the conversion changes.")
