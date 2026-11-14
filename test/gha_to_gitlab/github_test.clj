(ns gha-to-gitlab.github-test
  (:require [clojure.test :refer [deftest is testing]]
            [gha-to-gitlab.github :as github]))

(def two-job-workflow
  "name: CI
on: push
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Install
        run: npm ci
      - run: npm run build
  lint:
    runs-on: ubuntu-latest
    steps:
      - run: npm run lint
")

(deftest workflow->ci-model-test
  (testing "each Job with run: Steps becomes a CI Model Job whose script is the commands in order"
    (is (= {:jobs [{:id "build" :runs-on "ubuntu-latest" :script ["npm ci" "npm run build"]}
                   {:id "lint" :runs-on "ubuntu-latest" :script ["npm run lint"]}]
            :gaps []}
           (github/workflow->ci-model two-job-workflow)))))

(deftest invalid-ci-model-test
  (testing "a Job with no Steps fails CI Model validation instead of producing an empty Job"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid CI Model"
          (github/workflow->ci-model "on: push\njobs:\n  empty:\n    runs-on: ubuntu-latest\n    steps: []\n")))))

(deftest unsupported-step-test
  (testing "a Step that uses an Action is refused rather than silently dropped"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Job 'build' has a Step without run:"
          (github/workflow->ci-model "on: push\njobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n      - uses: actions/checkout@v4\n")))))

(deftest runs-on-test
  (testing "a runs-on list with one label is that label"
    (is (= "macos-latest"
           (-> "on: push\njobs:\n  b:\n    runs-on: [macos-latest]\n    steps:\n      - run: make\n"
               github/workflow->ci-model :jobs first :runs-on))))
  (testing "a runs-on with several labels keeps them joined, so it can still be mapped or reported"
    (is (= "self-hosted, linux"
           (-> "on: push\njobs:\n  b:\n    runs-on: [self-hosted, linux]\n    steps:\n      - run: make\n"
               github/workflow->ci-model :jobs first :runs-on)))))
