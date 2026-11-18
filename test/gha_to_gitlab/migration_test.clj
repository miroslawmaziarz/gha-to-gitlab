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
    (testing "writes a .gitlab-ci.yml with one Job per GitHub Job, its mapped image, and the commands as script lines"
      (is (= {:build {:image "ubuntu:24.04" :script ["npm ci" "npm run build"]}
              :test {:image "ubuntu:24.04" :script ["npm ci" "npm test\nnpm run coverage\n"]}
              :lint {:image "ubuntu:24.04" :script ["npm run lint"]}}
             (yaml/parse-string (slurp (io/file out ".gitlab-ci.yml"))))))
    (testing "writes a Migration Report listing the converted Workflow and Jobs and no Gaps"
      (let [report (slurp (io/file out "MIGRATION_REPORT.md"))]
        (is (str/includes? report ".github/workflows/ci.yml"))
        (doseq [job ["build" "test" "lint"]]
          (is (str/includes? report (str "`" job "`"))))
        (is (str/includes? report "No Gaps"))))
    (testing "the Migration Report lists the Runner Mapping each Job used"
      (is (str/includes? (slurp (io/file out "MIGRATION_REPORT.md"))
                         "- `build`: 2 script lines; runs on `ubuntu-latest` → image `ubuntu:24.04`, no tags")))))

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
      (is (= {:deploy {:image "ubuntu:24.04"
                       :script ["echo 'Gap: Action acme/login@v1 was not converted; see MIGRATION_REPORT.md'"
                                "make deploy"
                                "echo 'Gap: Action acme/notify@v3 was not converted; see MIGRATION_REPORT.md'"]}}
             (yaml/parse-string (slurp (io/file out ".gitlab-ci.yml"))))))
    (testing "the Migration Report shows the Gap count and lists each Gap"
      (let [report (slurp (io/file out "MIGRATION_REPORT.md"))]
        (is (str/includes? report "2 Gaps need a person to finish them:"))
        (is (str/includes? report "Step `step 1`: Action `acme/login@v1`"))
        (is (str/includes? report "Step `Notify`: Action `acme/notify@v3`"))))))

(defn- source-with-workflow [workflow-yaml]
  (let [src (temp-dir)
        wf (io/file src ".github/workflows/ci.yml")]
    (io/make-parents wf)
    (spit wf workflow-yaml)
    src))

(deftest unmapped-runner-test
  (let [out (temp-dir)]
    (migration/run! {:source-dir (source-with-workflow "on: push\njobs:\n  mac:\n    runs-on: macos-latest\n    steps:\n      - run: xcodebuild\n")
                     :output-dir out})
    (testing "the Job is still written, without an image"
      (is (= {:mac {:script ["xcodebuild"]}}
             (yaml/parse-string (slurp (io/file out ".gitlab-ci.yml"))))))
    (testing "the Migration Report shows the Job has no Runner Mapping and lists the Gap"
      (let [report (slurp (io/file out "MIGRATION_REPORT.md"))]
        (is (str/includes? report "- `mac`: 1 script line; runs on `macos-latest` → no Runner Mapping (see Gaps)"))
        (is (str/includes? report "- Job `mac` runs on `macos-latest`, which has no Runner Mapping"))))))

(deftest converted-actions-report-test
  (let [out (temp-dir)]
    (migration/run! {:source-dir (source-with-workflow
                                  (str "on: push\njobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n"
                                       "      - uses: actions/checkout@v4\n"
                                       "      - name: Tools\n        uses: actions/checkout@v4\n"
                                       "        with:\n          repository: acme/tools\n"
                                       "      - uses: acme/unknown@v1\n"
                                       "      - run: make\n"
                                       "  lint:\n    runs-on: ubuntu-latest\n    steps:\n      - run: make lint\n"))
                     :output-dir out})
    (let [report (slurp (io/file out "MIGRATION_REPORT.md"))]
      (testing "the Migration Report lists under each Job the Actions it converted and the translator's outcome"
        (is (str/includes? report
                           (str "- `build`: 2 script lines; runs on `ubuntu-latest` → image `ubuntu:24.04`, no tags\n"
                                "  - Step `step 1`: Action `actions/checkout@v4` dropped (GitLab checks out the repository automatically)\n"
                                "  - Step `Tools`: Action `actions/checkout@v4` dropped (GitLab checks out the repository automatically); 1 Gap\n"
                                "- `lint`: 1 script line"))))
      (testing "an Action with no Action Translator is not listed as converted"
        (is (not (str/includes? report "Step `step 3`: Action `acme/unknown@v1` dropped")))
        (is (not (re-find #"(?m)^  - Step `step 3`" report)))))))

(deftest setup-action-image-migration-run-test
  (let [out (temp-dir)]
    (migration/run! {:source-dir (source-with-workflow
                                  (str "on: push\njobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n"
                                       "      - uses: actions/checkout@v4\n"
                                       "      - name: Node\n        uses: actions/setup-node@v4\n"
                                       "        with:\n          node-version: 20\n"
                                       "      - run: npm test\n"
                                       "  lint:\n    runs-on: ubuntu-latest\n    steps:\n      - run: make lint\n"))
                     :output-dir out})
    (testing "the setup Action's image replaces the Runner Mapping image for its Job only"
      (is (= {:build {:image "node:20" :script ["npm test"]}
              :lint {:image "ubuntu:24.04" :script ["make lint"]}}
             (yaml/parse-string (slurp (io/file out ".gitlab-ci.yml"))))))
    (testing "the Migration Report's Job line shows the override and where it came from"
      (is (str/includes? (slurp (io/file out "MIGRATION_REPORT.md"))
                         (str "- `build`: 1 script line; runs on `ubuntu-latest` → image `node:20` from Step `Node` "
                              "(Action `actions/setup-node@v4`, overriding the Runner Mapping image `ubuntu:24.04`), no tags\n"
                              "  - Step `step 1`: Action `actions/checkout@v4` dropped (GitLab checks out the repository automatically)\n"
                              "  - Step `Node`: Action `actions/setup-node@v4` converted to the Job image `node:20`\n"
                              "- `lint`: 1 script line; runs on `ubuntu-latest` → image `ubuntu:24.04`, no tags"))))))

(deftest setup-action-image-without-runner-mapping-report-test
  (let [out (temp-dir)]
    (migration/run! {:source-dir (source-with-workflow
                                  (str "on: push\njobs:\n  mac:\n    runs-on: macos-latest\n    steps:\n"
                                       "      - uses: actions/setup-node@v4\n        with:\n          node-version: 22\n"
                                       "      - run: npm test\n"))
                     :output-dir out})
    (testing "a Job with no Runner Mapping still shows the image its Action set"
      (is (str/includes? (slurp (io/file out "MIGRATION_REPORT.md"))
                         (str "- `mac`: 1 script line; runs on `macos-latest` → no Runner Mapping (see Gaps); "
                              "image `node:22` from Step `step 1` (Action `actions/setup-node@v4`)\n"))))))

(deftest cache-and-artifacts-migration-run-test
  (let [out (temp-dir)]
    (migration/run! {:source-dir (source-with-workflow
                                  (str "on: push\njobs:\n  web:\n    runs-on: ubuntu-latest\n    steps:\n"
                                       "      - name: Node\n        uses: actions/setup-node@v4\n"
                                       "        with:\n          node-version: 20\n          cache: npm\n"
                                       "      - name: Build cache\n        uses: actions/cache@v4\n        with:\n"
                                       "          path: .next/cache\n"
                                       "          key: ${{ runner.os }}-next-${{ hashFiles('package-lock.json') }}\n"
                                       "      - run: npm ci && npm run build\n"
                                       "      - name: Upload\n        uses: actions/upload-artifact@v4\n        with:\n"
                                       "          name: dist\n          path: dist/\n          retention-days: 7\n          include-hidden-files: true\n"))
                     :output-dir out})
    (testing "the setup Action's cache, actions/cache and actions/upload-artifact become the Job's variables, caches and artifacts"
      (is (= (str "web:\n"
                  "  image: node:20\n"
                  "  variables:\n    npm_config_cache: $CI_PROJECT_DIR/.npm\n"
                  "  cache:\n"
                  "  - key:\n      files:\n      - package-lock.json\n      prefix: npm\n    paths:\n    - .npm\n"
                  "  - key:\n      files:\n      - package-lock.json\n      prefix: next\n    paths:\n    - .next/cache\n"
                  "  script:\n  - npm ci && npm run build\n"
                  "  artifacts:\n    name: dist\n    paths:\n    - dist/\n    expire_in: 7 days\n")
             (slurp (io/file out ".gitlab-ci.yml")))))
    (testing "the Migration Report lists each Action's outcome, and there are no Gaps"
      (let [report (slurp (io/file out "MIGRATION_REPORT.md"))]
        (is (str/includes? report
                           (str "  - Step `Node`: Action `actions/setup-node@v4` converted to the Job image `node:20` and a Job cache for npm\n"
                                "  - Step `Build cache`: Action `actions/cache@v4` converted to a Job cache "
                                "(its key leaves out `runner.os`, which GitLab has no Variable for)\n"
                                "  - Step `Upload`: Action `actions/upload-artifact@v4` converted to the Job artifacts\n")))
        (is (str/includes? report "No Gaps"))))))

(deftest job-dependencies-migration-run-test
  (let [out (temp-dir)]
    (migration/run! {:source-dir (source-with-workflow
                                  (str "on: push\njobs:\n"
                                       "  build:\n    runs-on: ubuntu-latest\n    steps:\n      - run: make\n"
                                       "  test:\n    needs: build\n    runs-on: ubuntu-latest\n    steps:\n      - run: make test\n"
                                       "  deploy:\n    needs: [test, bild]\n    runs-on: ubuntu-latest\n    steps:\n      - run: make deploy\n"))
                     :output-dir out})
    (testing "the Pipeline runs the Jobs in Stages in dependency order, keeping needs:"
      (is (= {:stages ["stage-1" "stage-2" "stage-3"]
              :build {:stage "stage-1" :image "ubuntu:24.04" :script ["make"]}
              :test {:stage "stage-2" :needs ["build"] :image "ubuntu:24.04" :script ["make test"]}
              :deploy {:stage "stage-3" :needs ["test"] :image "ubuntu:24.04" :script ["make deploy"]}}
             (yaml/parse-string (slurp (io/file out ".gitlab-ci.yml"))))))
    (testing "the Migration Report shows each Job's Stage and the Jobs it waits for, and the missing Job as a Gap"
      (let [report (slurp (io/file out "MIGRATION_REPORT.md"))]
        (is (str/includes? report "- `build`: 1 script line; runs on `ubuntu-latest` → image `ubuntu:24.04`, no tags; Stage `stage-1`\n"))
        (is (str/includes? report "- `deploy`: 1 script line; runs on `ubuntu-latest` → image `ubuntu:24.04`, no tags; Stage `stage-3`, after `test`\n"))
        (is (str/includes? report "1 Gap needs a person to finish it:"))
        (is (str/includes? report "Job `deploy`: needs Job `bild`, which the Workflow does not define"))))))
