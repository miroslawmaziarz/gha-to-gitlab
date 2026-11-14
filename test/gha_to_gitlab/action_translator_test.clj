(ns gha-to-gitlab.action-translator-test
  (:require [clojure.test :refer [deftest is testing]]
            [gha-to-gitlab.action-translator :as action-translator]
            [gha-to-gitlab.github :as github]))

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
      (is (= {:jobs [{:id "build" :runs-on "ubuntu-latest" :script ["echo hello world" "echo hello GitLab"]}]
              :gaps []}
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
