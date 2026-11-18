(ns gha-to-gitlab.gap-test
  (:require [clojure.java.shell :refer [sh]]
            [clojure.test :refer [deftest is testing]]
            [gha-to-gitlab.gap :as gap]))

(deftest at-test
  (testing "a Gap at a Workflow names it, then gives the text"
    (is (= {:workflow "ci.yml"
            :message "Workflow `ci.yml`: has no Trigger GitLab can run."}
           (gap/at {:workflow "ci.yml"} "has no Trigger GitLab can run."))))
  (testing "a Gap at a Job names the Workflow and the Job"
    (is (= {:workflow "ci.yml" :job "deploy"
            :message "Workflow `ci.yml`, Job `deploy`: needs Job `bild`."}
           (gap/at {:workflow "ci.yml" :job "deploy"} "needs Job `bild`."))))
  (testing "a Gap at a Step that uses an Action names the Step, and the text continues a sentence about the Action"
    (is (= {:workflow "ci.yml" :job "build" :step "Checkout" :action "actions/checkout@v4" :input "ref"
            :message (str "Workflow `ci.yml`, Job `build`, Step `Checkout`: Action `actions/checkout@v4` "
                          "input `ref: main` has no GitLab equivalent.")}
           (gap/at {:workflow "ci.yml" :job "build" :step "Checkout" :action "actions/checkout@v4" :input "ref"}
                   "input `ref: main` has no GitLab equivalent."))))
  (testing "location keys that are absent are left out"
    (is (= {:job "j" :message "Job `j`: runs on `x`."}
           (gap/at {:job "j" :workflow nil} "runs on `x`.")))))

(deftest placeholder-line-test
  (testing "an Action with no Action Translator leaves a shell-safe script line naming it and the Migration Report"
    (let [line (gap/placeholder-line "./it's-local")]
      (is (= "echo 'Gap: Action ./it'\\''s-local was not converted; see MIGRATION_REPORT.md'" line))
      (is (= {:exit 0 :out "Gap: Action ./it's-local was not converted; see MIGRATION_REPORT.md\n" :err ""}
             (sh "bash" "-c" line))))))
