(ns gha-to-gitlab.triggers-test
  "Triggers (`on:`): GitHub filter globs as GitLab regexes, and whole
  Migration Runs."
  (:require [clj-yaml.core :as yaml]
            [clojure.string :as str]
            [clojure.test :refer [are deftest is testing]]
            [gha-to-gitlab.migration :as migration]
            [gha-to-gitlab.triggers :as triggers]))

(deftest glob->regex-test
  (testing "a GitHub branch or tag glob becomes an anchored GitLab regex"
    (are [glob regex] (= {:regex regex} (triggers/glob->regex glob))
      "main" "/^main$/"
      "release-1_x" "/^release-1_x$/"
      "releases/**" "/^releases\\/.*$/"
      "feature/*" "/^feature\\/[^\\/]*$/"
      "v[0-9]+.*" "/^v[0-9]+\\.[^\\/]*$/"
      "v1.0?" "/^v1\\.0?$/"
      "[a-cx]*" "/^[a-cx][^\\/]*$/"
      "\\*star" "/^\\*star$/"))
  (testing "a glob GitLab cannot match the same way is a reason, not a regex"
    (are [glob reason] (= {:gap reason} (triggers/glob->regex glob))
      "+main" "`+` repeats nothing"
      "**?" "`?` follows a wildcard"
      "[abc" "`[` has no closing `]`"
      "[^a]x" "`[^` may negate the class on GitHub"
      "a[/-]b" "`[..]` holds `/`")))

(defn- convert-workflow [on-yaml]
  (migration/convert {:workflows {".github/workflows/ci.yml"
                                  (str on-yaml "jobs:\n  b:\n    steps:\n      - run: make\n")}}))

(defn- workflow-rules [on-yaml]
  (get-in (yaml/parse-string (:gitlab-ci-yaml (convert-workflow on-yaml)) :keywords false)
          ["workflow" "rules"]))

(def ^:private push "$CI_PIPELINE_SOURCE == \"push\"")
(def ^:private merge-request "$CI_PIPELINE_SOURCE == \"merge_request_event\"")

(deftest push-and-pull-request-test
  (testing "push and pull_request without filters start the Pipeline on any push and any merge request"
    (is (= [{"if" push} {"if" merge-request}]
           (workflow-rules "on: [push, pull_request]\n")))
    (is (= [{"if" push}] (workflow-rules "on: push\n"))))
  (testing "branch and tag filters: the last matching pattern decides, so negated patterns come first as `never`"
    (is (= [{"if" (str push " && $CI_COMMIT_BRANCH =~ /^releases\\/.*-alpha$/") "when" "never"}
            {"if" (str push " && $CI_COMMIT_BRANCH =~ /^releases\\/.*$/")}
            {"if" (str push " && $CI_COMMIT_BRANCH =~ /^main$/")}
            {"if" (str push " && $CI_COMMIT_TAG =~ /^v[^\\/]*$/")}]
           (workflow-rules (str "on:\n  push:\n    branches: [main, 'releases/**', '!releases/**-alpha']\n"
                                "    tags: ['v*']\n")))))
  (testing "ignore filters exclude refs; without tag filters, tag pushes do not start the Pipeline"
    (is (= [{"if" (str push " && $CI_COMMIT_BRANCH =~ /^wip\\/[^\\/]*$/") "when" "never"}
            {"if" (str push " && $CI_COMMIT_BRANCH")}]
           (workflow-rules "on:\n  push:\n    branches-ignore: ['wip/*']\n"))))
  (testing "pull_request branch filters match the merge request's target branch"
    (is (= [{"if" (str merge-request " && $CI_MERGE_REQUEST_TARGET_BRANCH_NAME =~ /^main$/")}]
           (workflow-rules "on:\n  pull_request:\n    branches: [main]\n"))))
  (testing "one pattern written alone is a list of that pattern"
    (is (= [{"if" (str push " && $CI_COMMIT_BRANCH =~ /^main$/")}]
           (workflow-rules "on:\n  push:\n    branches: main\n"))))
  (testing "patterns are the text GitHub reads, not YAML 1.1's booleans and dates"
    (is (= [{"if" (str push " && $CI_COMMIT_BRANCH =~ /^2020-01-01$/")}
            {"if" (str push " && $CI_COMMIT_BRANCH =~ /^yes$/")}]
           (workflow-rules "on:\n  push:\n    branches: [yes, 2020-01-01]\n"))))
  (testing "workflow: comes first in the CI file"
    (is (str/starts-with? (:gitlab-ci-yaml (convert-workflow "on: push\nenv:\n  A: x\n")) "workflow:"))))

(def ^:private gap-workflow
  (str "on:\n"
       "  push:\n    branches: ['+main']\n    paths: ['src/**']\n"
       "  pull_request:\n    types: [opened, labeled]\n"
       "  schedule:\n    - cron: '0 1 * * *'\n    - cron: '30 5 * * 1'\n"
       "  workflow_dispatch:\n    inputs:\n      target:\n        required: true\n"
       "  release:\n    types: [published]\n"))

(deftest trigger-gaps-test
  (let [{:keys [model report]} (convert-workflow gap-workflow)
        prefix "Workflow `.github/workflows/ci.yml`: Trigger "]
    (testing "schedule and workflow_dispatch get rules, so Pipeline Schedules and Run pipeline start the Pipeline"
      (is (= [{"if" merge-request}
              {"if" "$CI_PIPELINE_SOURCE == \"schedule\""}
              {"if" "$CI_PIPELINE_SOURCE == \"web\""}]
             (workflow-rules gap-workflow))))
    (testing "each part of a Trigger that the rules cannot say is a Gap at its Workflow, in Source order"
      (is (= [{:trigger "push" :message (str prefix "`push` has the `branches` pattern `+main`, which GitLab cannot match (`+` repeats nothing); the rules leave it out, so add it by hand.")}
              {:trigger "push" :message (str prefix "`push` filters on `paths` `[src/**]`; the Pipeline starts whatever a change touches, so add `rules: changes` to the Jobs by hand.")}
              {:trigger "pull_request" :message (str prefix "`pull_request` runs only for `types` `[opened, labeled]`; GitLab starts a merge request Pipeline whenever a merge request is opened or updated, so limit the Jobs by hand if that matters.")}
              {:trigger "schedule" :message (str prefix "`schedule` runs on cron `0 1 * * *` (UTC); create a Pipeline Schedule with it on the Target.")}
              {:trigger "schedule" :message (str prefix "`schedule` runs on cron `30 5 * * 1` (UTC); create a Pipeline Schedule with it on the Target.")}
              {:trigger "workflow_dispatch" :message (str prefix "`workflow_dispatch` starts the Workflow by hand; on the Target that is Run pipeline, and its inputs `target` are Variables to set there.")}
              {:trigger "release" :message (str prefix "`release` has no GitLab workflow rule; start the Pipeline for it by hand.")}]
             (mapv #(select-keys % [:trigger :message]) (:gaps model)))))
    (testing "the Migration Report lists them"
      (is (str/includes? report "Trigger `release`")))))

(deftest schedule-without-cron-test
  (testing "a schedule that lists no cron still gets its rule, and a Gap"
    (doseq [on-yaml ["on:\n  schedule:\n" "on:\n  schedule:\n    cron: '0 1 * * *'\n"]]
      (is (= [{:trigger "schedule"
               :message "Workflow `.github/workflows/ci.yml`: Trigger `schedule` lists no `cron`; create the Pipeline Schedules it means on the Target by hand."}]
             (mapv #(select-keys % [:trigger :message]) (:gaps (:model (convert-workflow on-yaml))))))
      (is (= [{"if" "$CI_PIPELINE_SOURCE == \"schedule\""}] (workflow-rules on-yaml))))))

(deftest no-convertible-trigger-test
  (testing "when no Trigger becomes a rule, no Pipeline starts on its own, instead of every one"
    (is (= [{"when" "never"}] (workflow-rules "on: release\n")))
    (is (= [{"when" "never"}] (workflow-rules "on:\n  push:\n    branches: ['+x']\n")))))
