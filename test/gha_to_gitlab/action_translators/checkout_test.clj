(ns gha-to-gitlab.action-translators.checkout-test
  (:require [clojure.test :refer [deftest is testing]]
            [gha-to-gitlab.github :as github]))

(defn- convert [checkout-step-yaml]
  (github/workflow->ci-model
   (str "on: push\njobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n"
        checkout-step-yaml
        "      - run: make\n")
   {:workflow-path ".github/workflows/ci.yml"}))

(deftest checkout-dropped-test
  (testing "actions/checkout is dropped, since GitLab checks out automatically, with no Gap"
    (let [model (convert "      - uses: actions/checkout@v4\n")]
      (is (= ["make"] (-> model :jobs first :script)))
      (is (= [] (:gaps model)))))
  (testing "its common inputs convert without a Gap"
    (let [model (convert (str "      - uses: actions/checkout@v4\n        with:\n"
                              "          fetch-depth: 1\n          submodules: false\n"
                              "          persist-credentials: false\n          clean: true\n"
                              "          lfs: true\n          fetch-tags: true\n"
                              "          show-progress: false\n          set-safe-directory: true\n"))]
      (is (= ["make"] (-> model :jobs first :script)))
      (is (= [] (:gaps model))))))

(deftest unsupported-input-test
  (let [model (convert (str "      - name: Check out tools\n        uses: actions/checkout@v4\n        with:\n"
                            "          repository: acme/tools\n          ref: main\n"))]
    (testing "the Step is still dropped"
      (is (= ["make"] (-> model :jobs first :script))))
    (testing "each input GitLab's automatic checkout can't honour is a Gap naming the Workflow, Job, Step, Action and input"
      (is (= [{:workflow ".github/workflows/ci.yml" :job "build" :step "Check out tools"
               :action "actions/checkout@v4" :input "repository"}
              {:workflow ".github/workflows/ci.yml" :job "build" :step "Check out tools"
               :action "actions/checkout@v4" :input "ref"}]
             (mapv #(select-keys % [:workflow :job :step :action :input]) (:gaps model))))
      (is (= (str "Workflow `.github/workflows/ci.yml`, Job `build`, Step `Check out tools`: "
                  "Action `actions/checkout@v4` input `ref: main` has no GitLab equivalent: "
                  "GitLab checks out the commit the Pipeline runs for; "
                  "check out another ref in a script line if the Job needs it.")
             (-> model :gaps second :message))))))

(deftest unsupported-values-test
  (testing "fetch-depth other than 1 and submodules other than false are Gaps pointing at the GitLab variable"
    (is (= [(str "Workflow `.github/workflows/ci.yml`, Job `build`, Step `step 1`: "
                 "Action `actions/checkout@v4` input `fetch-depth: 0` has no GitLab equivalent: "
                 "set the Job's `GIT_DEPTH` variable (0 means full history) by hand.")
            (str "Workflow `.github/workflows/ci.yml`, Job `build`, Step `step 1`: "
                 "Action `actions/checkout@v4` input `submodules: recursive` has no GitLab equivalent: "
                 "set the Job's `GIT_SUBMODULE_STRATEGY` variable (`normal` or `recursive`) by hand.")]
           (->> (convert (str "      - uses: actions/checkout@v4\n        with:\n"
                              "          fetch-depth: 0\n          submodules: recursive\n"))
                :gaps (map :message)))))
  (testing "an input this translator doesn't know is a Gap rather than silently dropped"
    (is (= ["new-input"]
           (->> (convert "      - uses: actions/checkout@v4\n        with:\n          new-input: x\n")
                :gaps (map :input))))))

(deftest multi-line-value-test
  (testing "a multi-line input value is collapsed onto one line in the Gap message"
    (is (= (str "Workflow `.github/workflows/ci.yml`, Job `build`, Step `step 1`: "
                "Action `actions/checkout@v4` input `sparse-checkout: src docs` has no GitLab equivalent: "
                "GitLab's automatic checkout can't do this; check the Job works without it.")
           (-> (convert (str "      - uses: actions/checkout@v4\n        with:\n"
                             "          sparse-checkout: |\n            src\n            docs\n"))
               :gaps first :message)))))

(deftest case-insensitive-action-test
  (testing "the Action name matches whatever its case, as GitHub owner/repo names do"
    (let [model (convert "      - uses: ACTIONS/Checkout@v4\n")]
      (is (= ["make"] (-> model :jobs first :script)))
      (is (= [] (:gaps model)))
      (is (= "ACTIONS/Checkout@v4" (-> model :jobs first :actions first :action))))))
