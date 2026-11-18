(ns gha-to-gitlab.ci-model
  "The platform-independent CI Model that every conversion passes through
  (ADR-0001). The Source reader produces it and the Target writer consumes it."
  (:require [clojure.string :as str]
            [gha-to-gitlab.action-job-settings :as action-job-settings]
            [malli.core :as m]
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

(def cache-key-files-limit
  "The most files a Job cache key can depend on (`cache:key:files`), as
  current GitLab allows."
  10)

(def Cache
  "One Job cache: the paths it holds and the key it is stored under, in
  GitLab's shape (ADR-0004)."
  [:map {:closed true}
   ;; a fixed key, or one GitLab derives from the contents of up to
   ;; `cache-key-files-limit` files
   [:key [:or [:string {:min 1}]
          [:map {:closed true}
           [:files [:vector {:min 1 :max cache-key-files-limit} :string]]
           [:prefix {:optional true} [:string {:min 1}]]]]]
   [:paths [:vector {:min 1} :string]]
   ;; restore only (pull) or save only (push); missing: both
   [:policy {:optional true} [:enum "pull" "push"]]
   ;; save even when the Job fails; missing: only when it succeeds
   [:when {:optional true} [:enum "always"]]])

(def Artifacts
  "The Job's artifacts: the paths kept after the Job, minus `:exclude`, in
  GitLab's shape (ADR-0004)."
  [:map {:closed true}
   [:name {:optional true} [:string {:min 1}]]
   [:paths [:vector {:min 1} :string]]
   [:exclude {:optional true} [:vector {:min 1} :string]]
   ;; how long GitLab keeps them, e.g. "5 days"; missing: the instance default
   [:expire-in {:optional true} [:string {:min 1}]]])

(def Job
  "One Job: its id, the Jobs it needs, where it runs (runner label, image,
  tags), its settings and script lines, and the Actions it converted."
  [:map {:closed true}
   [:id [:string {:min 1}]]
   [:runs-on {:optional true} :string]
   ;; ids of the Jobs this Job runs after, in Source order
   [:needs {:optional true} [:vector {:min 1} [:string {:min 1}]]]
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
   [:cache {:optional true} [:vector {:min 1 :max (:cache action-job-settings/list-settings)} Cache]]
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
  "The CI Model of one Migration Run: its Jobs, in Source order, and every
  Gap raised so far."
  [:map {:closed true}
   [:jobs [:vector {:min 1} Job]]
   [:gaps [:vector Gap]]])

(defn dependency-levels
  "Job id → the Job's level in the dependency order: 1 for a Job with no
  `:needs`, otherwise one more than the highest level of the Jobs it needs.
  Throws ex-info naming the Jobs that cannot be ordered, because they need
  each other in a cycle or need a Job that is missing or in one."
  [jobs]
  (let [needs (into {} (map (juxt :id :needs)) jobs)]
    ;; Jobs come in Source order, not dependency order: each round settles
    ;; the Jobs whose needed Jobs all have a level
    (loop [levels {}]
      (let [settled (for [[id needed] needs
                          :when (and (not (levels id)) (every? levels needed))]
                      [id (inc (reduce max 0 (map levels needed)))])]
        (cond
          (= (count levels) (count needs)) levels
          (seq settled) (recur (into levels settled))
          :else (let [ids (into [] (comp (map :id) (remove levels)) jobs)]
                  (throw (ex-info (str "Jobs " (str/join ", " (map #(str "`" % "`") ids))
                                       " cannot be ordered: each needs a Job in a cycle"
                                       " or a Job that does not exist")
                                  {:jobs ids}))))))))

(defn validate!
  "Returns `model` when it matches the CI Model schema and its Jobs can be
  ordered by their `:needs` (see `dependency-levels`), throws ex-info
  otherwise."
  [model]
  (when-not (m/validate CIModel model)
    (throw (ex-info "Invalid CI Model"
                    {:errors (me/humanize (m/explain CIModel model))})))
  (dependency-levels (:jobs model))
  model)
