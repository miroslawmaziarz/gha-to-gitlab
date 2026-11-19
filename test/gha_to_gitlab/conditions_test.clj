(ns gha-to-gitlab.conditions-test
  "Conditions (`if:`): GitHub expressions as GitLab rules, and whole
  Migration Runs."
  (:require [clj-yaml.core :as yaml]
            [clojure.test :refer [are deftest is testing]]
            [gha-to-gitlab.conditions :as conditions]
            [gha-to-gitlab.migration :as migration]))

(deftest comparison-test
  (testing "ref, branch and Trigger comparisons become the matching GitLab rule"
    (are [expression rule] (= rule (conditions/condition-rule expression))
      "github.ref == 'refs/heads/main'" {:if "$CI_COMMIT_BRANCH == \"main\""}
      "${{ github.ref != 'refs/tags/v1' }}" {:if "$CI_COMMIT_TAG != \"v1\""}
      "'main' == github.ref_name" {:if "$CI_COMMIT_REF_NAME == \"main\""}
      "github.event_name == 'pull_request'" {:if "$CI_PIPELINE_SOURCE == \"merge_request_event\""}
      "github.event_name != 'workflow_dispatch'" {:if "$CI_PIPELINE_SOURCE != \"web\""}
      "github.head_ref == 'feature'" {:if "$CI_MERGE_REQUEST_SOURCE_BRANCH_NAME == \"feature\""}
      "github.base_ref == 'main'" {:if "$CI_MERGE_REQUEST_TARGET_BRANCH_NAME == \"main\""}
      "github.ref_type == 'tag'" {:if "$CI_COMMIT_TAG"}
      "github.ref_type != 'tag'" {:if "$CI_COMMIT_TAG == null"}
      "github.ref_type == 'branch'" {:if "$CI_COMMIT_BRANCH"})))

(deftest combination-test
  (testing "&& and || combine conditions, with parentheses where GitLab needs them"
    (are [expression rule] (= rule (conditions/condition-rule expression))
      "github.event_name == 'push' && github.ref == 'refs/heads/main'"
      {:if "$CI_PIPELINE_SOURCE == \"push\" && $CI_COMMIT_BRANCH == \"main\""}
      "github.ref_name == 'main' || github.ref_name == 'dev'"
      {:if "$CI_COMMIT_REF_NAME == \"main\" || $CI_COMMIT_REF_NAME == \"dev\""}
      "github.event_name == 'push' && (github.ref_name == 'main' || github.ref_type == 'tag')"
      {:if "$CI_PIPELINE_SOURCE == \"push\" && ($CI_COMMIT_REF_NAME == \"main\" || $CI_COMMIT_TAG)"}))
  (testing "GitLab rules have no `!`, so a negation turns its comparisons around"
    (are [expression rule] (= rule (conditions/condition-rule expression))
      "!(github.ref_name == 'main' || github.event_name == 'schedule')"
      {:if "$CI_COMMIT_REF_NAME != \"main\" && $CI_PIPELINE_SOURCE != \"schedule\""}
      "!(github.ref_type == 'tag')" {:if "$CI_COMMIT_TAG == null"}))
  (testing "a branch name alone is true when it is set"
    (are [expression rule] (= rule (conditions/condition-rule expression))
      "github.head_ref" {:if "$CI_MERGE_REQUEST_SOURCE_BRANCH_NAME"}
      "!github.head_ref" {:if "$CI_MERGE_REQUEST_SOURCE_BRANCH_NAME == null"})))

(deftest starts-with-test
  (testing "startsWith on a ref or branch name becomes an anchored regex, or a set tag or branch"
    (are [expression rule] (= rule (conditions/condition-rule expression))
      "startsWith(github.ref, 'refs/tags/')" {:if "$CI_COMMIT_TAG"}
      "startsWith(github.ref, 'refs/tags/v1.')" {:if "$CI_COMMIT_TAG =~ /^v1\\./"}
      "startsWith(github.ref, 'refs/heads/release/')" {:if "$CI_COMMIT_BRANCH =~ /^release\\//"}
      "!startsWith(github.ref_name, 'wip-')" {:if "$CI_COMMIT_REF_NAME !~ /^wip-/"}
      "StartsWith(github.head_ref, 'dependabot/')" {:if "$CI_MERGE_REQUEST_SOURCE_BRANCH_NAME =~ /^dependabot\\//"})))

(deftest status-test
  (testing "a status function beside the whole condition becomes the rule's `when`"
    (are [expression rule] (= rule (conditions/condition-rule expression))
      "always()" {:when "always"}
      "${{ always() && github.ref_name == 'main' }}" {:if "$CI_COMMIT_REF_NAME == \"main\"" :when "always"}
      "failure()" {:when "on_failure"}
      "github.ref_name == 'main' && failure()" {:if "$CI_COMMIT_REF_NAME == \"main\"" :when "on_failure"}
      "success() && github.ref_name == 'main'" {:if "$CI_COMMIT_REF_NAME == \"main\""}
      "success()" {}
      "!cancelled()" {:when "always"})))

(deftest unsupported-test
  (testing "what GitLab rules cannot say is a Gap reason"
    (are [expression reason] (= {:gap reason} (conditions/condition-rule expression))
      "cancelled()" "GitLab rules cannot run a Job only when the Pipeline is cancelled"
      "always() || github.ref_name == 'main'"
      "`always()` decides when the Job runs, which a GitLab rule says only beside the whole condition (`always() && ..`)"
      "contains(github.event.head_commit.message, 'skip')" "`contains()` has no GitLab rule"
      "needs.build.outputs.ok == 'true'" "`needs.build.outputs.ok` has no GitLab Variable"
      "github.ref == 'refs/pull/1/merge'"
      "`github.ref` is compared with `refs/pull/1/merge`, which names no branch (`refs/heads/`) or tag (`refs/tags/`)"
      "github.run_number > 5" "GitLab rules cannot compare with `>`"
      "github.event_name == 'release'" "the Trigger `release` has no GitLab Pipeline source")))

(defn- convert-jobs [jobs-yaml]
  (migration/convert {:workflows {".github/workflows/ci.yml" (str "on: push\njobs:\n" jobs-yaml)}}))

(defn- ci-file [jobs-yaml]
  (dissoc (yaml/parse-string (:gitlab-ci-yaml (convert-jobs jobs-yaml)) :keywords false) "workflow"))

(defn- gap-messages [jobs-yaml]
  (mapv :message (:gaps (:model (convert-jobs jobs-yaml)))))

(def ^:private prefix "Workflow `.github/workflows/ci.yml`, ")

(deftest job-rules-test
  (testing "a Job's if: becomes its rules"
    (is (= {"deploy" {"rules" [{"if" "$CI_COMMIT_BRANCH == \"main\""}] "script" ["make deploy"]}}
           (ci-file "  deploy:\n    if: github.ref == 'refs/heads/main'\n    steps:\n      - run: make deploy\n"))))
  (testing "a Job is left out when a Job it needs is, as GitHub skips it, unless it runs always()"
    (is (= {"stages" ["stage-1" "stage-2" "stage-3"]
            "build" {"stage" "stage-1"
                     "rules" [{"if" "$CI_PIPELINE_SOURCE == \"push\""}]
                     "script" ["make"]}
            "deploy" {"stage" "stage-2"
                      "rules" [{"if" "$CI_COMMIT_REF_NAME == \"main\" && $CI_PIPELINE_SOURCE == \"push\""}]
                      "needs" ["build"]
                      "script" ["make deploy"]}
            "notify" {"stage" "stage-3"
                      "rules" [{"when" "always"}]
                      "needs" [{"job" "deploy" "optional" true}]
                      "script" ["make notify"]}}
           (ci-file (str "  build:\n    if: github.event_name == 'push'\n    steps:\n      - run: make\n"
                         "  deploy:\n    needs: build\n    if: github.ref_name == 'main'\n    steps:\n      - run: make deploy\n"
                         "  notify:\n    needs: deploy\n    if: always()\n    steps:\n      - run: make notify\n")))))
  (testing "if: false leaves the Job out"
    (is (= [{"when" "never"}]
           (get-in (ci-file "  disabled:\n    if: false\n    steps:\n      - run: make\n") ["disabled" "rules"])))))

(deftest job-condition-gaps-test
  (testing "a Job if: GitLab rules cannot say is a Gap quoting it; the Job runs, and so do the Jobs that need it"
    (is (= [(str prefix "Job `a`: its `if:` `contains(github.event.head_commit.message, 'go')` has no GitLab rule"
                 " (`contains()` has no GitLab rule); the Job runs whenever the Pipeline does, so add its rules by hand.")
            (str prefix "Job `b`: needs Job `a`, whose `if:` has no GitLab rule; GitHub skips this Job whenever it skips `a`,"
                 " so add `a`'s condition to its rules by hand.")]
           (gap-messages (str "  a:\n    if: contains(github.event.head_commit.message, 'go')\n    steps:\n      - run: make\n"
                              "  b:\n    needs: a\n    steps:\n      - run: make b\n"))))))

(deftest step-condition-test
  (testing "an upload-artifact Step that runs always() or on failure() keeps its artifacts then"
    (is (= "always"
           (get-in (ci-file (str "  test:\n    steps:\n      - run: make test\n"
                                 "      - uses: actions/upload-artifact@v4\n        if: ${{ always() }}\n"
                                 "        with:\n          path: report.xml\n"))
                   ["test" "artifacts" "when"])))
    (is (= "on_failure"
           (get-in (ci-file (str "  test:\n    steps:\n      - run: make test\n"
                                 "      - uses: actions/upload-artifact@v4\n        if: failure()\n"
                                 "        with:\n          path: report.xml\n"))
                   ["test" "artifacts" "when"]))))
  (testing "any other Step if: is a Gap at the Step quoting it"
    (is (= [(str prefix "Job `test`, Step `Ship`: its `if:` `github.ref_name == 'main'` is left out; GitLab runs every"
                 " script line of the Job, so guard the Step's lines by hand.")]
           (gap-messages (str "  test:\n    steps:\n      - name: Ship\n        if: github.ref_name == 'main'\n"
                              "        run: make ship\n"))))))
