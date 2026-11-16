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
      :jobs first :action-job-settings :image))

(deftest python-version-sets-image-test
  (testing "setup-python is dropped and python-version becomes the Job image, with no Gap"
    (let [model (convert "      - uses: actions/setup-python@v5\n        with:\n          python-version: '3.12'\n")]
      (is (= {:action-job-settings {:image "python:3.12"} :script ["pytest"]}
             (-> model :jobs first (select-keys [:action-job-settings :script]))))
      (is (= [] (:gaps model)))))
  (testing "exact versions and N.x map to python image tags"
    (is (= ["python:3.12.4" "python:3" "python:3.11"] (map image-for ["3.12.4" "3.x" "3.11.x"]))))
  (testing "with no python-version the Job gets python:3 and no Gap"
    (let [model (convert "      - uses: actions/setup-python@v5\n")]
      (is (= "python:3" (-> model :jobs first :action-job-settings :image)))
      (is (= [] (:gaps model))))))

(deftest unconvertible-python-test
  (testing "PyPy, prereleases, ranges and lists of versions are Gaps naming python-version"
    (doseq [version ["pypy3.10" "3.13-dev" ">=3.9" "3.11\n3.12"]]
      (let [model (convert (str "      - uses: actions/setup-python@v5\n        with:\n          python-version: |\n            "
                                (str/replace version "\n" "\n            ") "\n"))]
        (is (nil? (-> model :jobs first :action-job-settings :image)) version)
        (is (= ["python-version"] (map :input (:gaps model))) version))))
  (testing "inputs the image can't honour are Gaps naming them"
    (let [model (convert (str "      - uses: actions/setup-python@v5\n        with:\n"
                              "          python-version: '3.12'\n          cache: pip\n"
                              "          architecture: x86\n          check-latest: true\n"
                              "          update-environment: true\n          allow-prereleases: false\n"
                              "          pip-install: -r requirements.txt\n"))]
      (is (= "python:3.12" (-> model :jobs first :action-job-settings :image)))
      (is (= ["architecture" "pip-install"] (map :input (:gaps model))))))
  (testing "python-version-file is a Gap, and no image is set"
    (let [model (convert "      - uses: actions/setup-python@v5\n        with:\n          python-version-file: .python-version\n")]
      (is (nil? (-> model :jobs first :action-job-settings :image)))
      (is (= ["python-version-file"] (map :input (:gaps model)))))))

(deftest unquoted-decimal-version-test
  (testing "an unquoted 3.10 is read by YAML as the number 3.1, so it is a Gap asking to quote it, not python:3.1"
    (let [model (convert (str "      - name: Python\n        uses: actions/setup-python@v5\n"
                              "        with:\n          python-version: 3.10\n"))]
      (is (nil? (-> model :jobs first :action-job-settings)))
      (is (= [(str "Workflow `.github/workflows/ci.yml`, Job `build`, Step `Python`: Action `actions/setup-python@v5` "
                   "input `python-version: 3.1` was read as a number, which can drop digits (`3.10` becomes `3.1`); "
                   "quote the version in the Workflow (e.g. `'3.10'`) and start a new Migration Run, "
                   "or set the Job's image (e.g. `python:3.12`) by hand.")]
             (map :message (:gaps model)))))))
