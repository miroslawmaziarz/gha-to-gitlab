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

(def Cache
  "One Job cache: the paths it holds and the key it is stored under."
  [:map {:closed true}
   ;; a fixed key, or one GitLab derives from up to two files' contents
   [:key [:or [:string {:min 1}]
          [:map {:closed true}
           [:files [:vector {:min 1 :max 2} :string]]
           [:prefix {:optional true} [:string {:min 1}]]]]]
   [:paths [:vector {:min 1} :string]]
   ;; restore only (pull) or save only (push); missing: both
   [:policy {:optional true} [:enum "pull" "push"]]
   ;; save even when the Job fails; missing: only when it succeeds
   [:when {:optional true} [:enum "always"]]])

(def Artifacts
  "The Job's artifacts: the paths kept after the Job, minus `:exclude`."
  [:map {:closed true}
   [:name {:optional true} [:string {:min 1}]]
   [:paths [:vector {:min 1} :string]]
   [:exclude {:optional true} [:vector {:min 1} :string]]
   ;; how long GitLab keeps them, e.g. "5 days"; missing: the instance default
   [:expire-in {:optional true} [:string {:min 1}]]])

(def list-settings
  "Job settings that hold a list each Action adds its entries to, instead of
  one value; per setting, the most entries a Job can hold (GitLab allows 4
  caches per Job)."
  {:cache 4})

(def map-settings
  "Job settings that hold a map each Action adds its entries to, instead of
  one value (such as `:variables`); an entry two Actions set differently
  keeps the earlier Action's value."
  #{:variables})

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
   ;; Variables the Job's scripts and tools read, name → value
   [:variables {:optional true} [:map-of [:string {:min 1}] :string]]
   [:script [:vector {:min 1} :string]]
   [:cache {:optional true} [:vector {:min 1 :max (:cache list-settings)} Cache]]
   [:artifacts {:optional true} Artifacts]
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
