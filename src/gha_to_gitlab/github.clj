(ns gha-to-gitlab.github
  "Source side: reads a GitHub Actions Workflow into the CI Model."
  (:require [clj-yaml.core :as yaml]
            [clojure.string :as str]
            [gha-to-gitlab.ci-model :as ci-model]))

(defn- step->script-line [job-id step]
  (or (:run step)
      (throw (ex-info (str "Job '" job-id "' has a Step without run:, which is not supported yet")
                      {:job job-id :step step}))))

(defn- yaml-ish
  "A YAML value as short flow-style text, e.g. `[linux, x64]`."
  [v]
  (if (sequential? v)
    (str "[" (str/join ", " (map yaml-ish v)) "]")
    (str v)))

(defn- runner-label
  "The `runs-on` of a Job as one label string: a list of labels is joined
  with \", \"; a runner group is written in YAML-like form
  (`group: big, labels: [linux, x64]`)."
  [runs-on]
  (cond
    (sequential? runs-on) (str/join ", " runs-on)
    (map? runs-on) (str/join ", " (map (fn [[k v]] (str (name k) ": " (yaml-ish v))) runs-on))
    :else (str runs-on)))

(defn- job->ci-model [[job-id job]]
  (let [id (name job-id)]
    (cond-> {:id id}
      (some? (:runs-on job)) (assoc :runs-on (runner-label (:runs-on job)))
      true (assoc :script (mapv #(step->script-line id %) (:steps job))))))

(defn workflow->ci-model
  "Converts the YAML text of one Workflow into a validated CI Model."
  [workflow-yaml]
  (let [workflow (yaml/parse-string workflow-yaml)]
    (ci-model/validate!
     {:jobs (mapv job->ci-model (:jobs workflow))
      :gaps []})))
