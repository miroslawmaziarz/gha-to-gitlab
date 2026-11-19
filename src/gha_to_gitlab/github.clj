(ns gha-to-gitlab.github
  "Source side: reads a GitHub Actions Workflow into the CI Model."
  (:require [clj-yaml.core :as yaml]
            [clojure.string :as str]
            [gha-to-gitlab.action-job-settings :as action-job-settings]
            [gha-to-gitlab.action-translator :as action-translator]
            [gha-to-gitlab.action-translators]
            [gha-to-gitlab.ci-model :as ci-model]
            [gha-to-gitlab.gap :as gap]
            [gha-to-gitlab.matrix :as matrix]
            [gha-to-gitlab.triggers :as triggers]
            [gha-to-gitlab.variables :as variables]
            [gha-to-gitlab.wording :as wording]))

(defn- step-label [step index]
  (str (or (:name step) (:id step) (str "step " (inc index)))))

(defn- runner-label
  "The `runs-on` of a Job as one label string: a list of labels is joined
  with \", \"; a runner group is written in YAML-like form
  (`group: big, labels: [linux, x64]`)."
  [runs-on]
  (cond
    (sequential? runs-on) (str/join ", " runs-on)
    (map? runs-on) (str/join ", " (map (fn [[k v]] (str (name k) ": " (wording/yaml-ish v))) runs-on))
    :else (str runs-on)))

(defn- step-location
  "Where a Step that uses an Action is in the Source, as a Gap location
  (`gha-to-gitlab.gap/at`): `:workflow :job :step :action`."
  [workflow-path job-id step index]
  {:workflow workflow-path
   :job job-id
   :step (step-label step index)
   :action (:uses step)})

(defn- located-gap
  "Gap `gap` (`{:message ..}`, optionally the `:input` it is about), raised
  for the Action at `location`, placed there."
  [location {:keys [input message]}]
  (gap/at (assoc location :input input) message))

(defn- unknown-action [{:keys [action] :as location}]
  {:script [(gap/placeholder-line action)]
   :gaps [(gap/at location "has no Action Translator; replace its placeholder script line by hand.")]})

(defn- translated-action [{:keys [step action] :as location} translation]
  (let [{:keys [script gaps outcome job-settings]} translation]
    {:script (vec script)
     :gaps (mapv #(located-gap location %) gaps)
     :location location
     :job-settings job-settings
     :action {:step step
              :action action
              :outcome (or outcome "converted")
              :gap-count (count gaps)}}))

(defn- convert-step
  "Converts one Step into `{:script [line ...] :gaps [gap ...]}`; `:gaps`
  lists what could not be converted faithfully, and `:action` records an
  Action that an Action Translator converted. `matrix-values` are the
  Job's Matrix values (see `matrix/dimension-values`), which an Action's
  inputs may reference."
  [workflow-path job-id matrix-values index step]
  (cond
    (:run step) {:script [(variables/rewrite-references (:run step))]}
    (:uses step) (let [location (step-location workflow-path job-id step index)]
                   (if-let [translation (matrix/translate-action action-translator/translate step matrix-values)]
                     (translated-action location translation)
                     (unknown-action location)))
    :else (throw (ex-info (str "Job '" job-id "' has a Step with neither run: nor uses:")
                          {:job job-id :step step}))))

(def ^:private nothing-left-to-run
  "The script line of a Job whose Steps all converted to no script lines
  (e.g. only `actions/checkout`); GitLab needs at least one."
  (str "echo " (wording/shell-quote "Nothing left to run after conversion")))

(defn- job-script
  "The Job's script lines from its converted Steps. When the Job has Steps
  but none left a script line, it is the single `nothing-left-to-run` line;
  a Job with no Steps stays empty and fails CI Model validation."
  [steps]
  (let [script (into [] (mapcat :script) steps)]
    (if (and (seq steps) (empty? script))
      [nothing-left-to-run]
      script)))

(defn- settle-job-settings
  "Settles the Job settings the Job's Actions asked for (`:job-settings`)
  with `action-job-settings/settle`, against the Variables the Job has from
  `env:` (`env-variables`), returned as `:settings`, with `:steps`
  updated: each Converted Action's `:sets` names the settings the Job kept
  from it, and a Gap it raised is placed at its Step and counted on it."
  [env-variables steps]
  (let [{:keys [settings results]}
        (action-job-settings/settle env-variables (for [{converted :action :keys [job-settings]} steps]
                                                    {:step (:step converted) :action (:action converted) :settings job-settings}))]
    {:settings settings
     :steps (mapv (fn [{:keys [location] :as step} {:keys [kept gaps]}]
                    (cond-> step
                      (seq kept) (assoc-in [:action :sets] kept)
                      (seq gaps) (-> (update :gaps into (map #(located-gap location %) gaps))
                                     (update-in [:action :gap-count] + (count gaps)))))
                  steps results)}))

(defn- job-needs
  "The Job ids in a Job's `needs:`, which is one id or a list of them;
  each id once, as GitLab refuses duplicate needs."
  [needs]
  (into [] (comp (map str) (distinct)) (if (sequential? needs) needs [needs])))

(defn- missing-job-gap [workflow-path job-id needed]
  (gap/at {:workflow workflow-path :job job-id}
          (str "needs Job `" needed "`, which the Workflow does not define; the Job no longer waits for it,"
               " so add the dependency by hand.")))

(defn- env-variables
  "The Variables of the `env:` at `location` (whose values are the texts
  GitHub reads, see `read-workflow`) as `{:variables {..} :gaps [..]}`; an
  `env:` that is an expression has no Variables and is a Gap."
  [location env]
  (if (or (nil? env) (map? env))
    {:variables (variables/env->variables env) :gaps []}
    {:variables {} :gaps [(variables/expression-env-gap location env)]}))

(defn- variables-of-job
  "The Job's Variables and the Gaps raised for them (see
  `variables/job-variables`), given the Workflow's Variables."
  [workflow-path workflow-variables id job]
  (let [own (env-variables {:workflow workflow-path :job id} (:env job))
        steps (map-indexed (fn [index step]
                             (let [location {:workflow workflow-path :job id :step (step-label step index)}]
                               (assoc (env-variables location (:env step)) :location location)))
                           (:steps job))
        lifted (variables/job-variables {:workflow workflow-variables :job (:variables own)}
                                        (map #(select-keys % [:location :variables]) steps))]
    (update lifted :gaps #(-> (:gaps own) (into (mapcat :gaps) steps) (into %)))))

(defn- convert-job
  "Converts one Job into `{:job .. :gaps [..]}`. `job-ids` are the ids of
  every Job in the Workflow; a `needs:` entry naming any other Job is a Gap
  and is left out of the Job's `:needs`."
  [workflow-path workflow-variables job-ids [job-id job]]
  (let [id (name job-id)
        location {:workflow workflow-path :job id}
        job-variables (variables-of-job workflow-path workflow-variables id job)
        {needs true missing false} (group-by (comp boolean job-ids)
                                             (some-> (:needs job) job-needs))
        {:keys [matrix] matrix-gaps :gaps} (matrix/job-matrix location (:strategy job))
        matrix-values (matrix/dimension-values (:strategy job))
        runs-on-gap (matrix/runs-on-gap location (:runs-on job) matrix-values)
        {:keys [steps settings]} (->> (:steps job)
                                      (map-indexed #(convert-step workflow-path id matrix-values %1 %2))
                                      (settle-job-settings
                                       (merge workflow-variables (:variables job-variables))))]
    {:job (cond-> {:id id :workflow workflow-path}
            (and (some? (:runs-on job)) (nil? runs-on-gap)) (assoc :runs-on (runner-label (:runs-on job)))
            (seq needs) (assoc :needs needs)
            matrix (assoc :matrix matrix)
            (seq (:variables job-variables)) (assoc :variables (:variables job-variables))
            true (assoc :script (job-script steps))
            (some :action steps) (assoc :actions (into [] (keep :action) steps))
            (seq settings) (assoc :action-job-settings settings))
     :gaps (-> (mapv #(missing-job-gap workflow-path id %) missing)
               (into matrix-gaps)
               (cond-> runs-on-gap (conj runs-on-gap))
               (into (matrix/variable-clash-gaps location matrix (merge workflow-variables (:variables job-variables))))
               (into (:gaps job-variables))
               (into (mapcat :gaps) steps))}))

(defn- env-texts [env]
  (if (map? env) (vals env) [env]))

(defn- secret-uses
  "The Source texts that can reference Secrets, as `variables/secret-gaps`
  takes them: the Workflow's `env:` values, then per Job its `env:` values
  and each Step's `env:` values and command. These are the texts whose
  references `variables` rewrites."
  [workflow]
  (cons {:texts (env-texts (:env workflow))}
        (for [[job-id job] (:jobs workflow)]
          {:job (name job-id)
           :texts (concat (env-texts (:env job))
                          (mapcat #(concat (env-texts (:env %)) [(:run %)]) (:steps job)))})))

(defn- marked-get
  "The value under key `k` of a map read with clj-yaml's `:mark`."
  [marked k]
  (some (fn [[mk v]] (when (= k (:unmark mk)) v)) (:unmark marked)))

(defn- env-text
  "The text GitHub reads from one marked `env:` value: a string as it is,
  and any other scalar from how it is written in `source` (clj-yaml reads
  YAML 1.1, where `010` is 8 and `yes` is true; GitHub reads YAML 1.2)."
  [source {value :unmark :keys [start end]}]
  (cond
    (string? value) value
    (nil? value) ""
    :else (variables/plain-scalar-text (subs source (:index start) (:index end)))))

(defn- key-text
  "A marked map key as text as written, even one YAML 1.1 reads otherwise
  (`YES`, `on`)."
  [source {k :unmark :keys [start end]}]
  (if (string? k) k (subs source (:index start) (:index end))))

(defn- source-env
  "The `env:` read from `marked` (a map read with `:mark`): a map of name
  → the text GitHub reads, or the `env:` value itself when it is not a map;
  nil when there is none."
  [source marked]
  (when-let [env (marked-get marked "env")]
    (if (map? (:unmark env))
      (into {} (map (fn [[k v]] [(keyword (key-text source k)) (env-text source v)])) (:unmark env))
      (:unmark env))))

(defn- source-value
  "Marked value `marked` as GitHub reads it: maps (in Source order) with
  keys as written, lists as vectors, and scalars as text (see `env-text`)."
  [source {value :unmark :as marked}]
  (cond
    (map? value) (into (empty value) (map (fn [[k v]] [(keyword (key-text source k)) (source-value source v)])) value)
    (sequential? value) (mapv #(source-value source %) value)
    :else (env-text source marked)))

(defn- source-on
  "The Triggers (`on:`) read from the marked Workflow `marked` as GitHub
  reads them (see `source-value`); YAML 1.1 reads the key `on` as true."
  [source marked]
  (when-let [on (or (marked-get marked "on") (marked-get marked true))]
    (source-value source on)))

(defn- read-workflow
  "The Workflow in YAML text `source`, with every `env:` (Workflow, Job,
  Step) and the Triggers (`:on`) holding the texts GitHub reads (see
  `source-env`, `source-on`)."
  [source]
  (let [marked (yaml/parse-string source :mark true)
        workflow (-> (yaml/parse-string source) (dissoc true) (assoc :on (source-on source marked)))
        with-env (fn [m marked] (cond-> m (marked-get marked "env") (assoc :env (source-env source marked))))]
    (cond-> (with-env workflow marked)
      (map? (:jobs workflow))
      (update :jobs (fn [jobs]
                      (let [marked-jobs (marked-get marked "jobs")]
                        (into (empty jobs)
                              (for [[job-id job] jobs
                                    :let [marked-job (marked-get marked-jobs (name job-id))]]
                                [job-id (cond-> (with-env job marked-job)
                                          (marked-get marked-job "strategy")
                                          (assoc :strategy (source-value source (marked-get marked-job "strategy")))
                                          (sequential? (:steps job))
                                          (update :steps #(mapv with-env % (:unmark (marked-get marked-job "steps")))))]))))))))

(defn workflow->ci-model
  "Converts the YAML text of one Workflow into a validated CI Model; Jobs
  that need each other in a cycle are refused, as GitHub refuses them.
  `:workflow-path` (the Workflow's path in the Source) is used to name the
  Workflow in Gaps."
  ([workflow-yaml] (workflow->ci-model workflow-yaml {}))
  ([workflow-yaml {:keys [workflow-path] :or {workflow-path "workflow"}}]
   (let [workflow (read-workflow workflow-yaml)
         job-ids (into #{} (map (comp name key)) (:jobs workflow))
         {workflow-variables :variables workflow-gaps :gaps} (env-variables {:workflow workflow-path} (:env workflow))
         {:keys [rules] trigger-gaps :gaps} (triggers/workflow-rules workflow-path (:on workflow))
         jobs (mapv #(convert-job workflow-path workflow-variables job-ids %) (:jobs workflow))]
     (ci-model/validate!
      (cond-> {:jobs (mapv :job jobs)
               :gaps (-> trigger-gaps
                         (into workflow-gaps)
                         (into (mapcat :gaps) jobs)
                         (into (variables/secret-gaps workflow-path (secret-uses workflow))))}
        (seq rules) (assoc :workflow-rules rules)
        (seq workflow-variables) (assoc :variables workflow-variables))))))
