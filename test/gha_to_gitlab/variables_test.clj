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
       "      - name: Build\n        run: make\n        env:\n          TARGET: web\n          MODE: fast\n          REGION: eu\n          SHARED: 1\n"
       "      - name: Test\n        run: make test\n        env:\n          TARGET: web\n          LEVEL: 1\n          SHARED: 1\n"
       "      - name: Lint\n        uses: acme/lint@v1\n        env:\n          LEVEL: 2\n          MODE: slow\n          SHARED: 1\n"
       "      - name: Ship\n        run: make ship\n        env:\n          REGION: us\n          SHARED: 1\n"))

(defn- step-env-gap [step text]
  (str "Workflow `.github/workflows/ci.yml`, Job `build`, Step `" step "`: its `env:` sets " text
       "; a GitLab Job's Variables reach all its script lines, so set it for this Step's lines by hand."))

(deftest step-env-test
  (testing "a Step's env: entry is lifted into the Job only when every Step sets the name to the same value"
    (is (= {"variables" {"REGION" "eu"}
            "build" {"variables" {"MODE" "fast" "SHARED" "1"}
                     "script" ["make" "make test"
                               "echo 'Gap: Action acme/lint@v1 was not converted; see MIGRATION_REPORT.md'"
                               "make ship"]}}
           (ci-file step-env-workflow))))
  (testing "an entry the Job already inherits with the same value is no Gap; any other entry is a Gap at its Step"
    (is (= [(step-env-gap "Build" "`TARGET` to `web`, but Step `Lint` does not set it")
            (step-env-gap "Test" "`LEVEL` to `1`, but Step `Lint` sets it to `2`")
            (step-env-gap "Test" "`TARGET` to `web`, but Step `Lint` does not set it")
            (step-env-gap "Lint" "`LEVEL` to `2`, but Step `Test` sets it to `1`")
            (step-env-gap "Lint" "`MODE` to `slow`, but the Job's `env:` sets it to `fast`")
            (step-env-gap "Ship" "`REGION` to `us`, but the Workflow's `env:` sets it to `eu`")]
           (remove #(str/includes? % "acme/lint") (gap-messages step-env-workflow))))))

(deftest env-values-test
  (testing "env: values are the text GitHub reads (YAML 1.2): numbers in canonical form, other plain scalars as written"
    (is (= {"variables" {"BOOL" "true" "DATE" "2024-01-01" "EMPTY" "" "FLOAT" "3.1" "INT" "10"
                         "QUOTED" "010" "TIME" "1:30" "YES" "yes"}
            "b" {"script" ["make"]}}
           (ci-file (str "on: push\nenv:\n  INT: 010\n  QUOTED: '010'\n  FLOAT: 3.10\n  BOOL: True\n"
                         "  YES: yes\n  DATE: 2024-01-01\n  TIME: 1:30\n  EMPTY:\n"
                         "jobs:\n  b:\n    steps:\n      - run: make\n")))))
  (testing "a literal `$` in an env: value is escaped, as GitLab expands Variables in Variable values"
    (is (= {"P" "pa$$$$word ${TOKEN} $${{ github.sha }}"}
           (get (ci-file (str "on: push\nenv:\n  P: pa$$word ${{ secrets.TOKEN }} ${{ github.sha }}\n"
                              "jobs:\n  b:\n    steps:\n      - run: make\n"))
                "variables")))))

(deftest expression-env-test
  (testing "an env: that is an expression, not a map, is a Gap at its level and the Migration Run goes on"
    (let [{:keys [model]} (convert-workflow (str "on: push\nenv: ${{ fromJSON(vars.ENV) }}\n"
                                                 "jobs:\n  b:\n    env: ${{ fromJSON(vars.JOB) }}\n    steps:\n"
                                                 "      - run: make\n        env: ${{ fromJSON(vars.STEP) }}\n"))]
      (is (= ["Workflow `.github/workflows/ci.yml`: its `env:` is the expression `${{ fromJSON(vars.ENV) }}`, not a list of Variables; set its Variables by hand."
              "Workflow `.github/workflows/ci.yml`, Job `b`: its `env:` is the expression `${{ fromJSON(vars.JOB) }}`, not a list of Variables; set its Variables by hand."
              "Workflow `.github/workflows/ci.yml`, Job `b`, Step `step 1`: its `env:` is the expression `${{ fromJSON(vars.STEP) }}`, not a list of Variables; set its Variables by hand."]
             (mapv :message (:gaps model))))
      (is (= [{:id "b" :workflow ".github/workflows/ci.yml" :script ["make"]}] (:jobs model))))))

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
             (filter :secret (:gaps model)))))
    (testing "the Migration Report lists each Secret once"
      (is (= 1 (count (re-seq #"Secret `NPM_TOKEN`" report)))))))

(deftest env-and-action-variables-test
  (testing "a Job keeps both its env: Variables and those its Actions set"
    (is (= {"A" "1" "npm_config_cache" "$CI_PROJECT_DIR/.npm"}
           (get-in (ci-file (str "on: push\njobs:\n  build:\n    env:\n      A: 1\n    steps:\n"
                                 "      - uses: actions/setup-node@v4\n        with:\n          cache: npm\n"
                                 "      - run: npm ci\n"))
                   ["build" "variables"])))))
