(ns gha-to-gitlab.cli-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [gha-to-gitlab.test-support :refer [temp-dir]]
            [gha-to-gitlab.cli :as cli]))

(deftest run-with-clone-and-output-dir-test
  (testing "a clone path and an output directory run a Migration Run and exit 0"
    (let [out (temp-dir)
          {:keys [exit out-text]} (let [w (java.io.StringWriter.)]
                                    (binding [*out* w]
                                      {:exit (cli/run ["test/fixtures/plain-workflow" out])
                                       :out-text (str w)}))]
      (is (= 0 exit))
      (is (.exists (io/file out ".gitlab-ci.yml")))
      (is (str/includes? out-text "MIGRATION_REPORT.md")))))

(deftest bad-arguments-test
  (testing "missing arguments print usage to stderr and exit non-zero"
    (let [err (java.io.StringWriter.)
          exit (binding [*err* err] (cli/run ["only-one-arg"]))]
      (is (= 2 exit))
      (is (str/includes? (str err) "Usage:"))))
  (testing "a failing Migration Run reports the error and exits non-zero"
    (let [err (java.io.StringWriter.)
          exit (binding [*err* err] (cli/run ["does/not/exist" (temp-dir)]))]
      (is (= 1 exit))
      (is (str/includes? (str err) "Expected exactly one Workflow")))))
