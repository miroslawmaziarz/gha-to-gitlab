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
      (is (= {:jobs [{:id "build" :script ["echo hello world" "echo hello GitLab"]}]
              :gaps []}
             (github/workflow->ci-model greet-workflow)))
      (finally
        (remove-method action-translator/translate "example/greet")))))
