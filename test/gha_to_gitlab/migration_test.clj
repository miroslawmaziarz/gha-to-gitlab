(ns gha-to-gitlab.migration-test
  (:require [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [gha-to-gitlab.migration :as migration]
            [gha-to-gitlab.test-support :refer [temp-dir]]))

(defn- convert-workflow
  "The Migration Run of one Workflow at `.github/workflows/ci.yml`."
  ([workflow-yaml] (convert-workflow workflow-yaml nil))
  ([workflow-yaml config]
   (migration/convert {:workflows {".github/workflows/ci.yml" workflow-yaml} :config config})))

(defn- run-and-read!
  "Runs `migration/run!` against a clone, either a folder or a map of
  Workflow path → YAML text written to a fresh one, and returns the texts of
  the files it wrote."
  [clone]
  (let [src (if (string? clone)
              clone
              (let [dir (temp-dir)]
                (doseq [[path text] clone]
                  (io/make-parents (io/file dir path))
                  (spit (io/file dir path) text))
                dir))
        out (temp-dir)]
    (migration/run! {:source-dir src :output-dir out})
    {:gitlab-ci-yaml (slurp (io/file out ".gitlab-ci.yml"))
     :report (slurp (io/file out "MIGRATION_REPORT.md"))}))

(deftest migration-run-against-fixture-test
  (let [{:keys [gitlab-ci-yaml report]} (run-and-read! "test/fixtures/plain-workflow")]
    (testing "writes a .gitlab-ci.yml with one Job per GitHub Job, its mapped image, and the commands as script lines"
      (is (= {:build {:image "ubuntu:24.04" :script ["npm ci" "npm run build"]}
              :test {:image "ubuntu:24.04" :script ["npm ci" "npm test\nnpm run coverage\n"]}
              :lint {:image "ubuntu:24.04" :script ["npm run lint"]}}
             (dissoc (yaml/parse-string gitlab-ci-yaml) :workflow))))
    (testing "writes a Migration Report listing the converted Workflow and Jobs and no Gaps"
      (is (str/includes? report ".github/workflows/ci.yml"))
      (doseq [job ["build" "test" "lint"]]
        (is (str/includes? report (str "`" job "`"))))
      (is (str/includes? report "No Gaps")))
    (testing "the Migration Report lists the Runner Mapping each Job used"
      (is (str/includes? report
                         "- `build`: 2 script lines; runs on `ubuntu-latest` → image `ubuntu:24.04`, no tags")))))

(deftest reserved-keyword-job-report-test
  (testing "the Migration Report tells the person a Job was renamed"
    (is (str/includes? (:report (convert-workflow "on: push\njobs:\n  image:\n    runs-on: ubuntu-latest\n    steps:\n      - run: docker build .\n"))
                       "`image` → `image_job`"))))

(deftest unknown-action-migration-run-test
  (let [{:keys [gitlab-ci-yaml report]}
        (run-and-read! {".github/workflows/deploy.yml"
                        (str "on: push\njobs:\n  deploy:\n    runs-on: ubuntu-latest\n    steps:\n"
                             "      - uses: acme/login@v1\n      - run: make deploy\n"
                             "      - name: Notify\n        uses: acme/notify@v3\n")})]
    (testing "the Migration Run still writes the Pipeline, with placeholder script lines for the Gaps"
      (is (= {:deploy {:image "ubuntu:24.04"
                       :script ["echo 'Gap: Action acme/login@v1 was not converted; see MIGRATION_REPORT.md'"
                                "make deploy"
                                "echo 'Gap: Action acme/notify@v3 was not converted; see MIGRATION_REPORT.md'"]}}
             (dissoc (yaml/parse-string gitlab-ci-yaml) :workflow))))
    (testing "the Migration Report names the Workflow file it read, shows the Gap count and lists each Gap"
      (is (str/includes? report "Workflow `.github/workflows/deploy.yml` became `.gitlab-ci.yml`"))
      (is (str/includes? report "2 Gaps need a person to finish them:"))
      (is (str/includes? report "Step `step 1`: Action `acme/login@v1`"))
      (is (str/includes? report "Step `Notify`: Action `acme/notify@v3`")))))

(deftest unmapped-runner-test
  (let [{:keys [gitlab-ci-yaml report]} (convert-workflow "on: push\njobs:\n  mac:\n    runs-on: macos-latest\n    steps:\n      - run: xcodebuild\n")]
    (testing "the Job is still written, without an image"
      (is (= {:mac {:script ["xcodebuild"]}}
             (dissoc (yaml/parse-string gitlab-ci-yaml) :workflow))))
    (testing "the Migration Report shows the Job has no Runner Mapping and lists the Gap"
      (is (str/includes? report "- `mac`: 1 script line; runs on `macos-latest` → no Runner Mapping (see Gaps)"))
      (is (str/includes? report "- Workflow `.github/workflows/ci.yml`, Job `mac`: runs on `macos-latest`, which has no Runner Mapping")))))

(deftest converted-actions-report-test
  (let [{:keys [report]}
        (convert-workflow
         (str "on: push\njobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n"
              "      - uses: actions/checkout@v4\n"
              "      - name: Tools\n        uses: actions/checkout@v4\n"
              "        with:\n          repository: acme/tools\n"
              "      - uses: acme/unknown@v1\n"
              "      - run: make\n"
              "  lint:\n    runs-on: ubuntu-latest\n    steps:\n      - run: make lint\n"))]
    (testing "the Migration Report lists under each Job the Actions it converted and the translator's outcome"
      (is (str/includes? report
                         (str "- `build`: 2 script lines; runs on `ubuntu-latest` → image `ubuntu:24.04`, no tags\n"
                              "  - Step `step 1`: Action `actions/checkout@v4` dropped (GitLab checks out the repository automatically)\n"
                              "  - Step `Tools`: Action `actions/checkout@v4` dropped (GitLab checks out the repository automatically); 1 Gap\n"
                              "- `lint`: 1 script line"))))
    (testing "an Action with no Action Translator is not listed as converted"
      (is (not (str/includes? report "Step `step 3`: Action `acme/unknown@v1` dropped")))
      (is (not (re-find #"(?m)^  - Step `step 3`" report))))))

(deftest setup-action-image-migration-run-test
  (let [{:keys [gitlab-ci-yaml report]}
        (convert-workflow
         (str "on: push\njobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n"
              "      - uses: actions/checkout@v4\n"
              "      - name: Node\n        uses: actions/setup-node@v4\n"
              "        with:\n          node-version: 20\n"
              "      - run: npm test\n"
              "  lint:\n    runs-on: ubuntu-latest\n    steps:\n      - run: make lint\n"))]
    (testing "the setup Action's image replaces the Runner Mapping image for its Job only"
      (is (= {:build {:image "node:20" :script ["npm test"]}
              :lint {:image "ubuntu:24.04" :script ["make lint"]}}
             (dissoc (yaml/parse-string gitlab-ci-yaml) :workflow))))
    (testing "the Migration Report's Job line shows the override and where it came from"
      (is (str/includes? report
                         (str "- `build`: 1 script line; runs on `ubuntu-latest` → image `node:20` from Step `Node` "
                              "(Action `actions/setup-node@v4`, overriding the Runner Mapping image `ubuntu:24.04`), no tags\n"
                              "  - Step `step 1`: Action `actions/checkout@v4` dropped (GitLab checks out the repository automatically)\n"
                              "  - Step `Node`: Action `actions/setup-node@v4` converted to the Job image `node:20`\n"
                              "- `lint`: 1 script line; runs on `ubuntu-latest` → image `ubuntu:24.04`, no tags"))))))

(deftest setup-action-image-without-runner-mapping-test
  (let [{:keys [model report]}
        (convert-workflow
         (str "on: push\njobs:\n  mac:\n    runs-on: macos-latest\n    steps:\n"
              "      - uses: actions/setup-node@v4\n        with:\n          node-version: 22\n"
              "      - run: npm test\n"
              "  lint:\n    runs-on: ubuntu-latest\n    steps:\n      - run: make lint\n"))
        [mac lint] (:jobs model)]
    (testing "a Job with no Runner Mapping gets its Action's image, and no Runner Mapping image is recorded"
      (is (= {:id "mac" :workflow ".github/workflows/ci.yml" :runs-on "macos-latest" :image "node:22" :script ["npm test"]}
             (dissoc mac :actions))))
    (testing "a Job without Action Job settings keeps its Runner Mapping image"
      (is (= {:id "lint" :workflow ".github/workflows/ci.yml" :runs-on "ubuntu-latest" :image "ubuntu:24.04" :tags [] :script ["make lint"]}
             lint)))
    (testing "the unmapped label's Gap asks only for tags, since the Action sets the image"
      (is (str/includes? report
                         (str "- Workflow `.github/workflows/ci.yml`, Job `mac`: runs on `macos-latest`, which has no Runner Mapping; add one in the "
                              "Migration Config or set the Job's tags by hand (a setup Action sets its image)."))))
    (testing "the Migration Report still shows the image the Action set"
      (is (str/includes? report
                         (str "- `mac`: 1 script line; runs on `macos-latest` → no Runner Mapping (see Gaps); "
                              "image `node:22` from Step `step 1` (Action `actions/setup-node@v4`)\n"))))))

(deftest cache-and-artifacts-migration-run-test
  (let [{:keys [gitlab-ci-yaml report]}
        (convert-workflow
         (str "on: push\njobs:\n  web:\n    runs-on: ubuntu-latest\n    steps:\n"
              "      - name: Node\n        uses: actions/setup-node@v4\n"
              "        with:\n          node-version: 20\n          cache: npm\n"
              "      - name: Build cache\n        uses: actions/cache@v4\n        with:\n"
              "          path: .next/cache\n"
              "          key: ${{ runner.os }}-next-${{ hashFiles('package-lock.json') }}\n"
              "      - run: npm ci && npm run build\n"
              "      - name: Upload\n        uses: actions/upload-artifact@v4\n        with:\n"
              "          name: dist\n          path: dist/\n          retention-days: 7\n          include-hidden-files: true\n"))]
    (testing "the setup Action's cache, actions/cache and actions/upload-artifact become the Job's variables, caches and artifacts"
      (is (= (str "workflow:\n  rules:\n  - if: $CI_PIPELINE_SOURCE == \"push\"\n"
                  "web:\n"
                  "  image: node:20\n"
                  "  variables:\n    npm_config_cache: $CI_PROJECT_DIR/.npm\n"
                  "  cache:\n"
                  "  - key:\n      files:\n      - package-lock.json\n      prefix: npm\n    paths:\n    - .npm\n"
                  "  - key:\n      files:\n      - package-lock.json\n      prefix: next\n    paths:\n    - .next/cache\n"
                  "  script:\n  - npm ci && npm run build\n"
                  "  artifacts:\n    name: dist\n    paths:\n    - dist/\n    expire_in: 7 days\n")
             gitlab-ci-yaml)))
    (testing "the Migration Report lists each Action's outcome, and there are no Gaps"
      (is (str/includes? report
                         (str "  - Step `Node`: Action `actions/setup-node@v4` converted to the Job image `node:20` and a Job cache for npm\n"
                              "  - Step `Build cache`: Action `actions/cache@v4` converted to a Job cache "
                              "(its key leaves out `runner.os`, which GitLab has no Variable for)\n"
                              "  - Step `Upload`: Action `actions/upload-artifact@v4` converted to the Job artifacts\n")))
      (is (str/includes? report "No Gaps")))))

(deftest job-dependencies-migration-run-test
  (let [{:keys [gitlab-ci-yaml report]}
        (convert-workflow
         (str "on: push\njobs:\n"
              "  build:\n    runs-on: ubuntu-latest\n    steps:\n      - run: make\n"
              "  test:\n    needs: build\n    runs-on: ubuntu-latest\n    steps:\n      - run: make test\n"
              "  deploy:\n    needs: [test, bild]\n    runs-on: ubuntu-latest\n    steps:\n      - run: make deploy\n"))]
    (testing "the Pipeline runs the Jobs in Stages in dependency order, keeping needs:"
      (is (= {:stages ["stage-1" "stage-2" "stage-3"]
              :build {:stage "stage-1" :image "ubuntu:24.04" :script ["make"]}
              :test {:stage "stage-2" :needs ["build"] :image "ubuntu:24.04" :script ["make test"]}
              :deploy {:stage "stage-3" :needs ["test"] :image "ubuntu:24.04" :script ["make deploy"]}}
             (dissoc (yaml/parse-string gitlab-ci-yaml) :workflow))))
    (testing "the Migration Report shows each Job's Stage and the Jobs it waits for, and the missing Job as a Gap"
      (is (str/includes? report "- `build`: 1 script line; runs on `ubuntu-latest` → image `ubuntu:24.04`, no tags; Stage `stage-1`\n"))
      (is (str/includes? report "- `deploy`: 1 script line; runs on `ubuntu-latest` → image `ubuntu:24.04`, no tags; Stage `stage-3`, after `test`\n"))
      (is (str/includes? report "1 Gap needs a person to finish it:"))
      (is (str/includes? report "Job `deploy`: needs Job `bild`, which the Workflow does not define")))))

(deftest in-memory-migration-run-test
  (let [{:keys [model gitlab-ci-yaml report]}
        (convert-workflow
         (str "on: push\njobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n"
              "      - uses: actions/setup-node@v4\n        with:\n          node-version: 20\n"
              "      - run: npm test\n")
         {:runner-mappings {"ubuntu-latest" {:image "ubuntu:22.04" :tags ["docker"]}}})]
    (testing "a Migration Run from Workflow text and a Migration Config, with no files, gives the finished CI Model"
      (is (= [{:id "build" :workflow ".github/workflows/ci.yml" :runs-on "ubuntu-latest" :image "node:20" :runner-mapping-image "ubuntu:22.04"
               :tags ["docker"] :script ["npm test"]}]
             (mapv #(dissoc % :actions) (:jobs model)))))
    (testing "and the .gitlab-ci.yml text, where the Action's image replaces the Runner Mapping image and the tags stay"
      (is (= {:build {:image "node:20" :tags ["docker"] :script ["npm test"]}}
             (dissoc (yaml/parse-string gitlab-ci-yaml) :workflow))))
    (testing "and the Migration Report text, naming the Workflow it converted"
      (is (str/includes? report "Workflow `.github/workflows/ci.yml` became `.gitlab-ci.yml`")))))

(deftest migration-config-test
  (testing "a Runner Mapping in the Migration Config without :tags maps to no tags"
    (is (= {:build {:image "node:22" :script ["make"]}}
           (-> (convert-workflow "on: push\njobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n      - run: make\n"
                                 {:runner-mappings {"ubuntu-latest" {:image "node:22"}}})
               :gitlab-ci-yaml yaml/parse-string (#(dissoc % :workflow))))))
  (testing "an invalid Migration Config is refused, naming the problem"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid Migration Config.*image"
          (convert-workflow "on: push\njobs:\n  b:\n    runs-on: x\n    steps:\n      - run: make\n"
                            {:runner-mappings {"x" {:tags ["a"]}}})))))

(deftest workflow-count-test
  (testing "a Migration Run needs exactly one Workflow, and names the ones it found"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Expected exactly one Workflow, found 0$"
          (migration/convert {:workflows {}})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Expected exactly one Workflow, found 2: .github/workflows/a.yml, .github/workflows/b.yml"
          (migration/convert {:workflows {".github/workflows/b.yml" "" ".github/workflows/a.yml" ""}})))))

(deftest clone-without-workflows-test
  (testing "a Migration Run against a clone with no Workflow names the folder it looked in"
    (let [src (temp-dir)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            (re-pattern (str "No Workflow in \\Q" src "/.github/workflows\\E"))
            (migration/run! {:source-dir src :output-dir (temp-dir)}))))))

(deftest runner-mapping-gap-location-test
  (let [{:keys [model]}
        (migration/convert {:workflows {".github/workflows/mac.yml"
                                        "on: push\njobs:\n  mac:\n    runs-on: macos-latest\n    steps:\n      - run: xcodebuild\n"}})]
    (testing "a Runner Mapping Gap names the Workflow and the Job, like every other Gap"
      (is (= [{:workflow ".github/workflows/mac.yml" :job "mac"
               :message (str "Workflow `.github/workflows/mac.yml`, Job `mac`: runs on `macos-latest`, which has no "
                             "Runner Mapping; add one in the Migration Config or set the Job's image and tags by hand.")}]
             (:gaps model))))))
