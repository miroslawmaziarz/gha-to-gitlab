(ns gha-to-gitlab.variables-test
  "Variables (`env:`) and Secret references, through a whole Migration Run."
  (:require [clj-yaml.core :as yaml]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [gha-to-gitlab.migration :as migration]))

(defn- convert-workflow [workflow-yaml]
  (migration/convert {:workflows {".github/workflows/ci.yml" workflow-yaml}}))

(defn- ci-file [workflow-yaml]
  (yaml/parse-string (:gitlab-ci-yaml (convert-workflow workflow-yaml)) :keywords false))

(deftest workflow-and-job-env-test
  (testing "Workflow-level env: becomes Pipeline variables and Job-level env: becomes Job variables, as strings"
    (is (= {"variables" {"CI_MODE" "strict" "RETRIES" "3"}
            "build" {"variables" {"DEBUG" "true" "NODE_ENV" "production"}
                     "script" ["npm run build"]}}
           (ci-file (str "on: push\n"
                         "env:\n  RETRIES: 3\n  CI_MODE: strict\n"
                         "jobs:\n  build:\n"
                         "    env:\n      NODE_ENV: production\n      DEBUG: true\n"
                         "    steps:\n      - run: npm run build\n")))))
  (testing "the Pipeline variables come before the Jobs"
    (is (str/starts-with? (:gitlab-ci-yaml (convert-workflow "on: push\nenv:\n  A: x\njobs:\n  b:\n    steps:\n      - run: make\n"))
                          "variables:"))))

(defn- gap-messages [workflow-yaml]
  (mapv :message (:gaps (:model (convert-workflow workflow-yaml)))))

(def ^:private step-env-workflow
  (str "on: push\n"
       "env:\n  REGION: eu\n"
       "jobs:\n  build:\n"
       "    env:\n      MODE: fast\n"
       "    steps:\n"
       "      - name: Build\n        run: make\n        env:\n          TARGET: web\n          MODE: fast\n          REGION: eu\n"
       "      - name: Test\n        run: make test\n        env:\n          TARGET: web\n          LEVEL: 1\n"
       "      - name: Lint\n        run: make lint\n        env:\n          LEVEL: 2\n          MODE: slow\n"
       "      - name: Ship\n        run: make ship\n        env:\n          REGION: us\n"))

(deftest step-env-test
  (testing "a Step's env: entry is lifted into the Job when no other level or Step gives the name another value"
    (is (= {"variables" {"REGION" "eu"}
            "build" {"variables" {"MODE" "fast" "TARGET" "web"}
                     "script" ["make" "make test" "make lint" "make ship"]}}
           (ci-file step-env-workflow))))
  (testing "an entry that conflicts with another Step, the Job's env: or the Workflow's env: is a Gap at its Step"
    (is (= ["Workflow `.github/workflows/ci.yml`, Job `build`, Step `Test`: its `env:` sets `LEVEL` to `1`, but Step `Lint` sets it to `2`; a GitLab Job has one value per Variable, so set it for this Step's script lines by hand."
            "Workflow `.github/workflows/ci.yml`, Job `build`, Step `Lint`: its `env:` sets `LEVEL` to `2`, but Step `Test` sets it to `1`; a GitLab Job has one value per Variable, so set it for this Step's script lines by hand."
            "Workflow `.github/workflows/ci.yml`, Job `build`, Step `Lint`: its `env:` sets `MODE` to `slow`, but the Job's `env:` sets it to `fast`; a GitLab Job has one value per Variable, so set it for this Step's script lines by hand."
            "Workflow `.github/workflows/ci.yml`, Job `build`, Step `Ship`: its `env:` sets `REGION` to `us`, but the Workflow's `env:` sets it to `eu`; a GitLab Job has one value per Variable, so set it for this Step's script lines by hand."]
           (gap-messages step-env-workflow)))))

(deftest reference-rewriting-test
  (testing "env and secrets references in commands and env: values become Variable references; other expressions stay"
    (is (= {"variables" {"AUTH" "Bearer ${API_TOKEN}"}
            "build" {"variables" {"OUT" "dist-${MODE}"}
                     "script" ["echo ${MODE}_x ${NPM_TOKEN} ${{ github.sha }}"]}}
           (ci-file (str "on: push\n"
                         "env:\n  AUTH: Bearer ${{ secrets.API_TOKEN }}\n"
                         "jobs:\n  build:\n"
                         "    env:\n      OUT: dist-${{ env.MODE }}\n"
                         "    steps:\n      - run: echo ${{ env.MODE }}_x ${{secrets.NPM_TOKEN}} ${{ github.sha }}\n"))))))

(deftest secret-gaps-test
  (let [{:keys [model report]}
        (convert-workflow (str "on: push\n"
                               "env:\n  AUTH: ${{ secrets.API_TOKEN }}\n"
                               "jobs:\n"
                               "  build:\n    steps:\n"
                               "      - run: npm publish --token ${{ secrets.NPM_TOKEN }} ${{ secrets.NPM_TOKEN }}\n"
                               "      - run: gh release create\n        env:\n          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}\n"
                               "  deploy:\n    env:\n      KEY: ${{ secrets.NPM_TOKEN }}\n    steps:\n      - run: make deploy\n"))]
    (testing "each referenced Secret is one Gap at its Workflow, naming where it is used, in order of first use"
      (is (= [{:workflow ".github/workflows/ci.yml"
               :secret "API_TOKEN"
               :message "Workflow `.github/workflows/ci.yml`: Secret `API_TOKEN`, used by the Workflow's `env:`, cannot be read from the Source; create it on the Target as a masked Variable `API_TOKEN`."}
              {:workflow ".github/workflows/ci.yml"
               :secret "NPM_TOKEN"
               :message "Workflow `.github/workflows/ci.yml`: Secret `NPM_TOKEN`, used by Jobs `build`, `deploy`, cannot be read from the Source; create it on the Target as a masked Variable `NPM_TOKEN`."}
              {:workflow ".github/workflows/ci.yml"
               :secret "GITHUB_TOKEN"
               :message "Workflow `.github/workflows/ci.yml`: Secret `GITHUB_TOKEN`, used by Job `build`, is the token GitHub creates for each run, and GitLab has none by that name; create a masked Variable `GITHUB_TOKEN` on the Target holding a GitHub token with the access the Jobs need, or switch them to `CI_JOB_TOKEN`."}]
             (:gaps model))))
    (testing "the Migration Report lists each Secret once"
      (is (= 1 (count (re-seq #"Secret `NPM_TOKEN`" report)))))))

(deftest env-and-action-variables-test
  (testing "a Job keeps both its env: Variables and those its Actions set"
    (is (= {"A" "1" "npm_config_cache" "$CI_PROJECT_DIR/.npm"}
           (get-in (ci-file (str "on: push\njobs:\n  build:\n    env:\n      A: 1\n    steps:\n"
                                 "      - uses: actions/setup-node@v4\n        with:\n          cache: npm\n"
                                 "      - run: npm ci\n"))
                   ["build" "variables"])))))
