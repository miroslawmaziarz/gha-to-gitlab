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

(deftest nothing-left-to-run-test
  (let [model (github/workflow->ci-model
               "on: push\njobs:\n  only:\n    runs-on: ubuntu-latest\n    steps:\n      - uses: actions/checkout@v4\n")]
    (testing "a Job whose Steps all convert to no script lines gets one shell-safe line saying so"
      (is (= ["echo 'Nothing left to run after conversion'"] (-> model :jobs first :script)))
      (is (= {:exit 0 :out "Nothing left to run after conversion\n" :err ""}
             (sh "bash" "-c" (-> model :jobs first :script first)))))
    (testing "and no Gap, since dropping those Steps is faithful"
      (is (= [] (:gaps model))))))

(def needs-workflow
  "on: push
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - run: make
  lint:
    runs-on: ubuntu-latest
    steps:
      - run: make lint
  test:
    needs: build
    runs-on: ubuntu-latest
    steps:
      - run: make test
  deploy:
    needs: [test, lint]
    runs-on: ubuntu-latest
    steps:
      - run: make deploy
")

(deftest needs-test
  (let [model (github/workflow->ci-model needs-workflow)]
    (testing "a Job's needs: in single and list form is carried as a list of Job ids, in Source order"
      (is (= {"build" nil "lint" nil "test" ["build"] "deploy" ["test" "lint"]}
             (into {} (map (juxt :id :needs)) (:jobs model)))))
    (testing "and raises no Gap"
      (is (= [] (:gaps model)))))
  (testing "a Job needed twice is listed once, as GitLab refuses duplicate needs"
    (is (= ["build"]
           (-> (github/workflow->ci-model
                (str "on: push\njobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n      - run: make\n"
                     "  test:\n    needs: [build, build]\n    runs-on: ubuntu-latest\n    steps:\n      - run: make test\n"))
               :jobs second :needs)))))

(deftest missing-needed-job-test
  (let [model (github/workflow->ci-model
               (str "on: push\njobs:\n"
                    "  build:\n    runs-on: ubuntu-latest\n    steps:\n      - run: make\n"
                    "  deploy:\n    needs: [build, bild]\n    runs-on: ubuntu-latest\n    steps:\n      - run: make deploy\n")
               {:workflow-path ".github/workflows/ci.yml"})]
    (testing "a needed Job the Workflow does not define is left out of the Job's needs"
      (is (= ["build"] (-> model :jobs second :needs))))
    (testing "and is a Gap naming the Workflow, the Job and the missing Job"
      (is (= [{:workflow ".github/workflows/ci.yml"
               :job "deploy"
               :message (str "Workflow `.github/workflows/ci.yml`, Job `deploy`: needs Job `bild`,"
                             " which the Workflow does not define; the Job no longer waits for it,"
                             " so add the dependency by hand.")}]
             (:gaps model))))))

(deftest needs-cycle-test
  (testing "Jobs that need each other in a cycle, and Jobs that need them, are refused, as GitHub refuses them"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Jobs `a`, `b`, `c` cannot be ordered: each needs a Job in a cycle"
          (github/workflow->ci-model
           (str "on: push\njobs:\n"
                "  a:\n    needs: b\n    runs-on: ubuntu-latest\n    steps:\n      - run: make\n"
                "  b:\n    needs: a\n    runs-on: ubuntu-latest\n    steps:\n      - run: make\n"
                "  c:\n    needs: a\n    runs-on: ubuntu-latest\n    steps:\n      - run: make\n"))))))
