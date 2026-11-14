(ns gha-to-gitlab.ci-model
  "The platform-independent CI Model that every conversion passes through
  (ADR-0001). The Source reader produces it and the Target writer consumes it."
  (:require [malli.core :as m]
            [malli.error :as me]))

(def Job
  [:map {:closed true}
   [:id [:string {:min 1}]]
   [:script [:vector {:min 1} :string]]])

(def Gap
  [:map
   [:message :string]])

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
