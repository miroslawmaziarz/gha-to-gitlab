(ns gha-to-gitlab.migration-test
  (:require [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [gha-to-gitlab.test-support :refer [temp-dir]]
            [gha-to-gitlab.migration :as migration]))

(deftest migration-run-against-fixture-test
  (let [out (temp-dir)]
    (migration/run! {:source-dir "test/fixtures/plain-workflow" :output-dir out})
    (testing "writes a .gitlab-ci.yml with one Job per GitHub Job and the commands as script lines"
      (is (= {:build {:script ["npm ci" "npm run build"]}
              :test {:script ["npm ci" "npm test\nnpm run coverage\n"]}
              :lint {:script ["npm run lint"]}}
             (yaml/parse-string (slurp (io/file out ".gitlab-ci.yml"))))))
    (testing "writes a Migration Report listing the converted Workflow and Jobs and no Gaps"
      (let [report (slurp (io/file out "MIGRATION_REPORT.md"))]
        (is (str/includes? report ".github/workflows/ci.yml"))
        (doseq [job ["build" "test" "lint"]]
          (is (str/includes? report (str "`" job "`"))))
        (is (str/includes? report "No Gaps"))))))

(deftest reserved-keyword-job-report-test
  (testing "the Migration Report tells the person a Job was renamed"
    (let [src (temp-dir)
          out (temp-dir)
          wf (io/file src ".github/workflows/ci.yml")]
      (io/make-parents wf)
      (spit wf "on: push\njobs:\n  image:\n    runs-on: ubuntu-latest\n    steps:\n      - run: docker build .\n")
      (migration/run! {:source-dir src :output-dir out})
      (is (str/includes? (slurp (io/file out "MIGRATION_REPORT.md"))
                         "`image` → `image_job`")))))

(deftest unknown-action-migration-run-test
  (let [src (temp-dir)
        out (temp-dir)
        wf (io/file src ".github/workflows/deploy.yml")]
    (io/make-parents wf)
    (spit wf (str "on: push\njobs:\n  deploy:\n    runs-on: ubuntu-latest\n    steps:\n"
                  "      - uses: acme/login@v1\n      - run: make deploy\n"
                  "      - name: Notify\n        uses: acme/notify@v3\n"))
    (migration/run! {:source-dir src :output-dir out})
    (testing "the Migration Run still writes the Pipeline, with placeholder script lines for the Gaps"
      (is (= {:deploy {:script ["echo 'Gap: Action acme/login@v1 was not converted; see MIGRATION_REPORT.md'"
                                "make deploy"
                                "echo 'Gap: Action acme/notify@v3 was not converted; see MIGRATION_REPORT.md'"]}}
             (yaml/parse-string (slurp (io/file out ".gitlab-ci.yml"))))))
    (testing "the Migration Report shows the Gap count and lists each Gap"
      (let [report (slurp (io/file out "MIGRATION_REPORT.md"))]
        (is (str/includes? report "2 Gaps need a person to finish them:"))
        (is (str/includes? report "Step `step 1`: Action `acme/login@v1`"))
        (is (str/includes? report "Step `Notify`: Action `acme/notify@v3`"))))))
