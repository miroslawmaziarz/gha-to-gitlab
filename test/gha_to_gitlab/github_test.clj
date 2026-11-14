(ns gha-to-gitlab.github-test
  (:require [clojure.java.shell :refer [sh]]
            [clojure.test :refer [deftest is testing]]
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

(def unknown-action-workflow
  "on: push
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - run: make
      - name: Publish
        uses: acme/publish@v2
      - uses: acme/notify@0123abc
")

(deftest unknown-action-test
  (let [model (github/workflow->ci-model unknown-action-workflow
                                         {:workflow-path ".github/workflows/ci.yml"})]
    (testing "a Step whose Action has no Action Translator keeps a placeholder script line naming the Action"
      (is (= ["make"
              "echo 'Gap: Action acme/publish@v2 was not converted; see MIGRATION_REPORT.md'"
              "echo 'Gap: Action acme/notify@0123abc was not converted; see MIGRATION_REPORT.md'"]
             (-> model :jobs first :script))))
    (testing "each such Step is a Gap naming the Workflow, Job, Step and Action"
      (is (= [{:workflow ".github/workflows/ci.yml" :job "build" :step "Publish" :action "acme/publish@v2"}
              {:workflow ".github/workflows/ci.yml" :job "build" :step "step 3" :action "acme/notify@0123abc"}]
             (mapv #(select-keys % [:workflow :job :step :action]) (:gaps model))))
      (is (= "Workflow `.github/workflows/ci.yml`, Job `build`, Step `Publish`: Action `acme/publish@v2` has no Action Translator; replace its placeholder script line by hand."
             (-> model :gaps first :message))))))

(deftest placeholder-quoting-test
  (testing "the placeholder script line stays valid shell when the Action contains a single quote"
    (let [line (-> (github/workflow->ci-model
                    "on: push\njobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n      - uses: \"./it's-local\"\n")
                   :jobs first :script first)
          {:keys [exit out]} (sh "bash" "-c" line)]
      (is (= 0 exit))
      (is (= "Gap: Action ./it's-local was not converted; see MIGRATION_REPORT.md\n" out)))))

(deftest non-string-step-name-test
  (testing "a Step whose name: is not a string is still named in its Gap"
    (is (= "123"
           (-> (github/workflow->ci-model
                "on: push\njobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n      - name: 123\n        uses: acme/x@v1\n")
               :gaps first :step)))))

(deftest step-without-run-or-uses-test
  (testing "a Step with neither run: nor uses: is refused rather than silently dropped"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Job 'build' has a Step with neither run: nor uses:"
          (github/workflow->ci-model "on: push\njobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n      - name: nothing\n")))))

(deftest runs-on-test
  (testing "a runs-on list with one label is that label"
    (is (= "macos-latest"
           (-> "on: push\njobs:\n  b:\n    runs-on: [macos-latest]\n    steps:\n      - run: make\n"
               github/workflow->ci-model :jobs first :runs-on))))
  (testing "a runs-on with several labels keeps them joined, so it can still be mapped or reported"
    (is (= "self-hosted, linux"
           (-> "on: push\njobs:\n  b:\n    runs-on: [self-hosted, linux]\n    steps:\n      - run: make\n"
               github/workflow->ci-model :jobs first :runs-on))))
  (testing "a runner group keeps its keys in YAML-like form"
    (is (= ["group: big" "group: big, labels: [linux, x64]"]
           (->> "on: push\njobs:\n  a:\n    runs-on:\n      group: big\n    steps:\n      - run: make\n  b:\n    runs-on:\n      group: big\n      labels: [linux, x64]\n    steps:\n      - run: make\n"
                github/workflow->ci-model :jobs (map :runs-on))))))
