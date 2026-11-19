(ns gha-to-gitlab.action-translator-test
  (:require [clojure.test :refer [deftest is testing]]
            [gha-to-gitlab.action-translator :as action-translator]
            [gha-to-gitlab.github :as github]
            [gha-to-gitlab.test-support :refer [converted-model]]))

(def greet-workflow
  "on: push
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: example/greet@v4
        with:
          who: world
      - uses: example/greet@8f4b7f84864484a7bf31766abe9204da3cbe65b3
        with:
          who: GitLab
")

(deftest new-action-translator-test
  (testing "adding one Action Translator converts that Action, ignoring its version suffix, with no Gap"
    (defmethod action-translator/translate "example/greet" [step]
      {:script [(str "echo hello " (get-in step [:with :who]))]})
    (try
      (is (= {:jobs [{:id "build" :workflow "workflow" :runs-on "ubuntu-latest" :script ["echo hello world" "echo hello GitLab"]}]
              :gaps []
              :workflow-rules [{:if "$CI_PIPELINE_SOURCE == \"push\""}]}
             (update (github/workflow->ci-model greet-workflow) :jobs
                     (partial mapv #(dissoc % :actions)))))
      (finally
        (remove-method action-translator/translate "example/greet")))))

(deftest translator-gaps-and-outcome-test
  (defmethod action-translator/translate "example/greet" [step]
    {:script [(str "echo hello " (get-in step [:with :who]))]
     :gaps (when (= "GitLab" (get-in step [:with :who]))
             [{:input "who" :message "input `who` is too polite."}])})
  (try
    (let [model (github/workflow->ci-model greet-workflow {:workflow-path "ci.yml"})]
      (testing "a Gap the translator returns is placed at its Step, with the Action named in its message"
        (is (= [{:message "Workflow `ci.yml`, Job `build`, Step `step 2`: Action `example/greet@8f4b7f84864484a7bf31766abe9204da3cbe65b3` input `who` is too polite."
                 :workflow "ci.yml" :job "build" :step "step 2"
                 :action "example/greet@8f4b7f84864484a7bf31766abe9204da3cbe65b3" :input "who"}]
               (:gaps model))))
      (testing "the Job records each converted Action; with no :outcome it is \"converted\""
        (is (= [{:step "step 1" :action "example/greet@v4" :outcome "converted" :gap-count 0}
                {:step "step 2" :action "example/greet@8f4b7f84864484a7bf31766abe9204da3cbe65b3"
                 :outcome "converted" :gap-count 1}]
               (-> model :jobs first :actions)))))
    (finally
      (remove-method action-translator/translate "example/greet"))))

(deftest job-settings-test
  (testing "a translator's :job-settings end up on the finished Job, and its Converted Action says which it set"
    (defmethod action-translator/translate "example/tagger" [_step]
      {:script [] :job-settings {:tags ["gpu"]}})
    (try
      (let [job (-> (converted-model
                     "on: push\njobs:\n  build:\n    steps:\n      - uses: example/tagger@v1\n      - run: make\n")
                    :jobs first)]
        (is (= ["gpu"] (:tags job)))
        (is (= [[:tags]] (map :sets (:actions job)))))
      (finally
        (remove-method action-translator/translate "example/tagger")))))

(deftest conflicting-job-settings-test
  (let [model (converted-model
               (str "on: push\njobs:\n  build:\n    steps:\n"
                    "      - name: Node\n        uses: actions/setup-node@v4\n        with:\n          node-version: 20\n"
                    "      - name: Python\n        uses: actions/setup-python@v5\n        with:\n          python-version: '3.12'\n"
                    "      - uses: actions/setup-node@v4\n        with:\n          node-version: 20\n"
                    "      - run: make\n"))
        job (-> model :jobs first)]
    (testing "when two Actions in one Job set different images, the first Action's image stays"
      (is (= "node:20" (:image job))))
    (testing "the later Action's conflicting image is a Gap at its Step; an identical image is not"
      (is (= [{:workflow ".github/workflows/ci.yml" :job "build" :step "Python"
               :action "actions/setup-python@v5"
               :message (str "Workflow `.github/workflows/ci.yml`, Job `build`, Step `Python`: "
                             "Action `actions/setup-python@v5` sets the Job's `image` to `python:3.12`, "
                             "but Step `Node` (Action `actions/setup-node@v4`) already set it to `node:20`, "
                             "which the Job keeps; use one image that has everything the Job needs, or split the Job.")}]
             (:gaps model))))
    (testing "the Converted Actions say which set the kept image, and count the Gap against the later Action"
      (is (= [[:image] nil [:image]] (map :sets (:actions job))))
      (is (= [0 1 0] (map :gap-count (:actions job)))))))
