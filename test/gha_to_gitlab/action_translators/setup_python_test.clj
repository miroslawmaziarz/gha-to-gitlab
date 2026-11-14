(ns gha-to-gitlab.action-translators.setup-python-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [gha-to-gitlab.github :as github]))

(defn- convert [step-yaml]
  (github/workflow->ci-model
   (str "on: push\njobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n"
        step-yaml
        "      - run: pytest\n")
   {:workflow-path ".github/workflows/ci.yml"}))

(defn- image-for [version]
  (-> (convert (str "      - uses: actions/setup-python@v5\n        with:\n          python-version: '" version "'\n"))
      :jobs first :image))

(deftest python-version-sets-image-test
  (testing "setup-python is dropped and python-version becomes the Job image, with no Gap"
    (let [model (convert "      - uses: actions/setup-python@v5\n        with:\n          python-version: '3.12'\n")]
      (is (= {:image "python:3.12" :script ["pytest"]}
             (-> model :jobs first (select-keys [:image :script]))))
      (is (= [] (:gaps model)))))
  (testing "exact versions and N.x map to python image tags"
    (is (= ["python:3.12.4" "python:3" "python:3.11"] (map image-for ["3.12.4" "3.x" "3.11.x"]))))
  (testing "with no python-version the Job gets python:3 and no Gap"
    (let [model (convert "      - uses: actions/setup-python@v5\n")]
      (is (= "python:3" (-> model :jobs first :image)))
      (is (= [] (:gaps model))))))

(deftest unconvertible-python-test
  (testing "PyPy, prereleases, ranges and lists of versions are Gaps naming python-version"
    (doseq [version ["pypy3.10" "3.13-dev" ">=3.9" "3.11\n3.12"]]
      (let [model (convert (str "      - uses: actions/setup-python@v5\n        with:\n          python-version: |\n            "
                                (str/replace version "\n" "\n            ") "\n"))]
        (is (nil? (-> model :jobs first :image)) version)
        (is (= ["python-version"] (map :input (:gaps model))) version))))
  (testing "inputs the image can't honour are Gaps naming them"
    (let [model (convert (str "      - uses: actions/setup-python@v5\n        with:\n"
                              "          python-version: '3.12'\n          cache: pip\n"
                              "          architecture: x86\n          check-latest: true\n"
                              "          update-environment: true\n          allow-prereleases: false\n"
                              "          pip-install: -r requirements.txt\n"))]
      (is (= "python:3.12" (-> model :jobs first :image)))
      (is (= ["cache" "architecture" "pip-install"] (map :input (:gaps model))))))
  (testing "python-version-file is a Gap, and no image is set"
    (let [model (convert "      - uses: actions/setup-python@v5\n        with:\n          python-version-file: .python-version\n")]
      (is (nil? (-> model :jobs first :image)))
      (is (= ["python-version-file"] (map :input (:gaps model)))))))
