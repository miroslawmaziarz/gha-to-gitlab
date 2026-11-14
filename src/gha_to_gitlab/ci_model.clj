(ns gha-to-gitlab.ci-model
  "The platform-independent CI Model that every conversion passes through
  (ADR-0001). The Source reader produces it and the Target writer consumes it."
  (:require [malli.core :as m]
            [malli.error :as me]))

(def ConvertedAction
  "An Action that an Action Translator converted, as the Migration Report
  lists it: the Step and `uses:` value, the translator's `:outcome` (such as
  \"dropped (GitLab checks out the repository automatically)\") and how many
  Gaps the translator raised for it. `:sets` names the Job settings the
  Action supplied and the Job kept (such as `[:image]`), so the Migration
  Report can say where a setting came from; the values themselves are in
  the Job's `:action-job-settings` (ADR-0003)."
  [:map {:closed true}
   [:step :string]
   [:action :string]
   [:outcome [:string {:min 1}]]
   [:gap-count nat-int?]
   [:sets {:optional true} [:vector :keyword]]])

(def Job
  [:map {:closed true}
   [:id [:string {:min 1}]]
   [:runs-on {:optional true} :string]
   [:image {:optional true} [:string {:min 1}]]
   ;; the Runner Mapping's image, kept when an Action's image overrides it
   [:runner-mapping-image {:optional true} [:string {:min 1}]]
   [:tags {:optional true} [:vector :string]]
   ;; Job settings the Job's Actions asked for (one value per setting), not
   ;; yet applied; `gha-to-gitlab.action-job-settings` applies them after
   ;; the Runner Mapping and removes this key
   [:action-job-settings {:optional true} [:map-of :keyword any?]]
   [:script [:vector {:min 1} :string]]
   [:actions {:optional true} [:vector ConvertedAction]]])

(def Gap
  "One Gap. `:message` is what the Migration Report shows; the optional keys
  say where in the Source the Gap is (`:input` names the Action input an
  Action Translator could not convert)."
  [:map
   [:message :string]
   [:workflow {:optional true} :string]
   [:job {:optional true} :string]
   [:step {:optional true} :string]
   [:action {:optional true} :string]
   [:input {:optional true} :string]])

(def CIModel
  [:map {:closed true}
   [:jobs [:vector {:min 1} Job]]
   [:gaps [:vector Gap]]])

(defn validate!
  "Returns `model` when it matches the CI Model schema, throws ex-info otherwise."
  [model]
  (if (m/validate CIModel model)
    model
    (throw (ex-info "Invalid CI Model"
                    {:errors (me/humanize (m/explain CIModel model))}))))
