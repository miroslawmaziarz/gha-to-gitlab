(ns gha-to-gitlab.action-translators.setup-cache-test
  "The `cache` and `cache-dependency-path` inputs of the setup Actions."
  (:require [clojure.test :refer [deftest is testing]]
            [gha-to-gitlab.github :as github]))

(defn- convert [steps-yaml]
  (github/workflow->ci-model
   (str "on: push\njobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n" steps-yaml "      - run: make\n")
   {:workflow-path ".github/workflows/ci.yml"}))

(defn- settings [model] (-> model :jobs first :action-job-settings))

(deftest package-manager-caches-test
  (testing "each supported package manager becomes a Job cache inside the project, keyed on its lock file, plus the Variables that point the tool there"
    (doseq [[action version-input manager expected]
            [["setup-node@v4" "node-version: 20" "npm"
              {:cache [{:key {:files ["package-lock.json"] :prefix "npm"} :paths [".npm"]}]
               :variables {"npm_config_cache" "$CI_PROJECT_DIR/.npm"}}]
             ["setup-node@v4" "node-version: 20" "yarn"
              {:cache [{:key {:files ["yarn.lock"] :prefix "yarn"} :paths [".yarn-cache"]}]
               :variables {"YARN_CACHE_FOLDER" "$CI_PROJECT_DIR/.yarn-cache"
                           "YARN_ENABLE_GLOBAL_CACHE" "false"}}]
             ["setup-python@v5" "python-version: '3.12'" "pip"
              {:cache [{:key {:files ["**/requirements.txt"] :prefix "pip"} :paths [".cache/pip"]}]
               :variables {"PIP_CACHE_DIR" "$CI_PROJECT_DIR/.cache/pip"}}]
             ["setup-python@v5" "python-version: '3.12'" "pipenv"
              {:cache [{:key {:files ["Pipfile.lock"] :prefix "pipenv"} :paths [".venv"]}]
               :variables {"PIPENV_VENV_IN_PROJECT" "1"}}]
             ["setup-python@v5" "python-version: '3.12'" "poetry"
              {:cache [{:key {:files ["poetry.lock"] :prefix "poetry"} :paths [".venv"]}]
               :variables {"POETRY_VIRTUALENVS_IN_PROJECT" "true"}}]
             ["setup-java@v4" "distribution: temurin\n          java-version: 21" "maven"
              {:cache [{:key {:files ["**/pom.xml"] :prefix "maven"} :paths [".m2/repository"]}]
               :variables {"MAVEN_OPTS" "-Dmaven.repo.local=$CI_PROJECT_DIR/.m2/repository"}}]
             ["setup-java@v4" "distribution: temurin\n          java-version: 21" "gradle"
              {:cache [{:key {:files ["**/*.gradle*" "**/gradle-wrapper.properties"] :prefix "gradle"}
                        :paths [".gradle/caches" ".gradle/wrapper"]}]
               :variables {"GRADLE_USER_HOME" "$CI_PROJECT_DIR/.gradle"}}]]]
      (let [model (convert (str "      - uses: actions/" action "\n        with:\n          " version-input
                                "\n          cache: " manager "\n"))]
        (is (= expected (dissoc (settings model) :image)) manager)
        (is (= [] (:gaps model)) manager))))
  (testing "the outcome names the cache as well as the image"
    (is (= "converted to the Job image `node:20` and a Job cache for npm"
           (-> (convert "      - uses: actions/setup-node@v4\n        with:\n          node-version: 20\n          cache: npm\n")
               :jobs first :actions first :outcome)))))

(deftest cache-dependency-path-test
  (testing "cache-dependency-path replaces the lock file the cache is keyed on"
    (is (= {:files ["web/package-lock.json" "api/package-lock.json"] :prefix "npm"}
           (-> (convert (str "      - uses: actions/setup-node@v4\n        with:\n          node-version: 20\n          cache: npm\n"
                             "          cache-dependency-path: |\n            web/package-lock.json\n            api/package-lock.json\n"))
               settings :cache first :key))))
  (testing "more than two files, an expression or an exclusion is a Gap naming it, and no cache is added"
    (doseq [paths ["a.lock\n            b.lock\n            c.lock" "${{ env.LOCK }}" "**/x.lock\n            !vendor/**"]]
      (let [model (convert (str "      - uses: actions/setup-node@v4\n        with:\n          node-version: 20\n          cache: npm\n"
                                "          cache-dependency-path: |\n            " paths "\n"))]
        (is (nil? (:cache (settings model))) paths)
        (is (= ["cache-dependency-path"] (map :input (:gaps model))) paths))))
  (testing "cache-dependency-path without cache is harmless (setup-node only caches when asked)"
    (is (= [] (:gaps (convert "      - uses: actions/setup-node@v4\n        with:\n          node-version: 20\n          cache-dependency-path: a.lock\n"))))))

(deftest unsupported-managers-test
  (testing "pnpm, sbt and unknown package managers stay Gaps naming `cache`; the image is still set"
    (doseq [[action version-input manager] [["setup-node@v4" "node-version: 20" "pnpm"]
                                            ["setup-java@v4" "distribution: temurin\n          java-version: 21" "sbt"]
                                            ["setup-python@v5" "python-version: '3.12'" "conda"]]]
      (let [model (convert (str "      - uses: actions/" action "\n        with:\n          " version-input
                                "\n          cache: " manager "\n"))]
        (is (some? (:image (settings model))) manager)
        (is (nil? (:cache (settings model))) manager)
        (is (= ["cache"] (map :input (:gaps model))) manager)))))

(deftest variables-across-actions-test
  (testing "Variables from several Actions in one Job are combined, and caches collected"
    (let [model (convert (str "      - uses: actions/setup-node@v4\n        with:\n          node-version: 20\n          cache: npm\n"
                              "      - uses: actions/setup-python@v5\n        with:\n          python-version: '3.12'\n          cache: pip\n"))]
      (is (= {"npm_config_cache" "$CI_PROJECT_DIR/.npm" "PIP_CACHE_DIR" "$CI_PROJECT_DIR/.cache/pip"}
             (:variables (settings model))))
      (is (= ["npm" "pip"] (map (comp :prefix :key) (:cache (settings model)))))
      (is (= ["image"] (map #(re-find #"image" (:message %)) (:gaps model)))))))
