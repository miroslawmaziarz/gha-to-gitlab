(ns gha-to-gitlab.cli-test
  (:require [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [gha-to-gitlab.cli :as cli]
            [gha-to-gitlab.test-support :refer [temp-dir]]))

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

(defn- run-cli [args]
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)
        exit (binding [*out* out *err* err] (cli/run args))]
    {:exit exit :out (str out) :err (str err)}))

(deftest config-runner-mapping-test
  (testing "--config points at a Migration Config whose Runner Mappings override the built-in ones"
    (let [out (temp-dir)
          config (io/file (temp-dir) "migration.edn")]
      (spit config (pr-str {:runner-mappings {"ubuntu-latest" {:image "node:22" :tags ["docker"]}}}))
      (is (= 0 (:exit (run-cli ["--config" (str config) "test/fixtures/plain-workflow" out]))))
      (is (= {:image "node:22" :tags ["docker"] :script ["npm run lint"]}
             (:lint (yaml/parse-string (slurp (io/file out ".gitlab-ci.yml"))))))))
  (testing "tags may be left out of a Runner Mapping in the Migration Config"
    (let [out (temp-dir)
          config (io/file (temp-dir) "migration.edn")]
      (spit config (pr-str {:runner-mappings {"ubuntu-latest" {:image "node:22"}}}))
      (is (= 0 (:exit (run-cli ["--config" (str config) "test/fixtures/plain-workflow" out]))))
      (is (= {:image "node:22" :script ["npm run lint"]}
             (:lint (yaml/parse-string (slurp (io/file out ".gitlab-ci.yml"))))))))
  (testing "an invalid Migration Config fails the Migration Run with a message naming the problem"
    (let [config (io/file (temp-dir) "migration.edn")]
      (spit config (pr-str {:runner-mappings {"ubuntu-latest" {:tags ["docker"]}}}))
      (let [{:keys [exit err]} (run-cli ["--config" (str config) "test/fixtures/plain-workflow" (temp-dir)])]
        (is (= 1 exit))
        (is (str/includes? err "Invalid Migration Config"))))))

(deftest bad-arguments-test
  (testing "missing arguments print usage to stderr and exit non-zero"
    (let [err (java.io.StringWriter.)
          exit (binding [*err* err] (cli/run ["only-one-arg"]))]
      (is (= 2 exit))
      (is (str/includes? (str err) "Usage:"))))
  (testing "--config without a path prints usage"
    (is (= 2 (:exit (run-cli ["--config"])))))
  (testing "a failing Migration Run reports the error and exits non-zero"
    (let [err (java.io.StringWriter.)
          exit (binding [*err* err] (cli/run ["does/not/exist" (temp-dir)]))]
      (is (= 1 exit))
      (is (str/includes? (str err) "Expected exactly one Workflow")))))

(deftest gaps-still-succeed-test
  (testing "a Migration Run that has Gaps still exits 0"
    (let [src (temp-dir)
          wf (io/file src ".github/workflows/ci.yml")]
      (io/make-parents wf)
      (spit wf "on: push\njobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n      - uses: acme/unknown@v1\n")
      (is (= 0 (binding [*out* (java.io.StringWriter.)]
                 (cli/run [src (temp-dir)])))))))
