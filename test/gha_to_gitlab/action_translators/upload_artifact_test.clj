(ns gha-to-gitlab.action-translators.upload-artifact-test
  (:require [clojure.test :refer [deftest is testing]]
            [gha-to-gitlab.github :as github]))

(defn- convert [steps-yaml]
  (github/workflow->ci-model
   (str "on: push\njobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n      - run: make\n" steps-yaml)
   {:workflow-path ".github/workflows/ci.yml"}))

(defn- artifacts [model] (-> model :jobs first :action-job-settings :artifacts))

(deftest common-inputs-test
  (let [model (convert (str "      - uses: actions/upload-artifact@v4\n        with:\n          name: dist\n"
                            "          path: |\n            dist/\n            !dist/**/*.map\n"
                            "          retention-days: 5\n          if-no-files-found: warn\n"
                            "          compression-level: 9\n          overwrite: true\n"))]
    (testing "name, paths (with ! lines as exclusions) and retention-days become the Job artifacts, with no Gap"
      (is (= {:name "dist" :paths ["dist/"] :exclude ["dist/**/*.map" "**/.*" "**/.*/**"] :expire-in "5 days"}
             (artifacts model)))
      (is (= [] (:gaps model)))
      (is (= (str "converted to the Job artifacts (hidden files left out with `exclude` patterns, "
                  "as upload-artifact v4.4 and later does)")
             (-> model :jobs first :actions first :outcome)))
      (is (= ["make"] (-> model :jobs first :script)))))
  (testing "one retention day is singular, and with only a path there is no name or expiry"
    (is (= {:paths ["out"] :expire-in "1 day"}
           (artifacts (convert "      - uses: actions/upload-artifact@v4\n        with:\n          path: out\n          retention-days: 1\n          include-hidden-files: true\n"))))
    (is (= {:paths ["out"]}
           (artifacts (convert "      - uses: actions/upload-artifact@v4\n        with:\n          path: out\n          if-no-files-found: ignore\n          include-hidden-files: true\n"))))))

(deftest gaps-test
  (let [model (convert (str "      - name: Upload\n        uses: actions/upload-artifact@v4\n        with:\n"
                            "          name: report-${{ matrix.os }}\n"
                            "          path: |\n            report.xml\n            ~/logs\n"
                            "          retention-days: ${{ env.DAYS }}\n          if-no-files-found: error\n"
                            "          include-hidden-files: true\n          new-input: x\n"))]
    (testing "inputs GitLab can't express are Gaps naming them; the rest still becomes the Job artifacts"
      (is (= {:paths ["report.xml"]} (artifacts model)))
      (is (= ["name" "path" "retention-days" "if-no-files-found" "new-input"]
             (map :input (:gaps model)))))
    (testing "the Gaps say what to do"
      (is (= (str "Workflow `.github/workflows/ci.yml`, Job `build`, Step `Upload`: Action `actions/upload-artifact@v4` "
                  "input `if-no-files-found: error` has no GitLab equivalent: GitLab only warns when an artifacts path "
                  "matches no files; add a script line that fails when they are missing by hand.")
             (:message (nth (:gaps model) 3))))
      (is (re-find #"input `path: ~/logs` has no GitLab equivalent: GitLab keeps only paths inside the project directory"
                   (:message (nth (:gaps model) 1))))))
  (testing "with no path the Step is dropped with a Gap"
    (let [model (convert "      - uses: actions/upload-artifact@v4\n        with:\n          name: x\n")]
      (is (nil? (artifacts model)))
      (is (= ["path"] (map :input (:gaps model))))
      (is (= "dropped; add the Job artifacts by hand (see Gaps)" (-> model :jobs first :actions first :outcome))))))

(deftest several-uploads-test
  (testing "a second upload in one Job is a Gap: GitHub keeps two artifacts, GitLab one archive per Job"
    (let [model (convert (str "      - name: Dist\n        uses: actions/upload-artifact@v4\n        with:\n          name: dist\n          path: dist\n          include-hidden-files: true\n"
                              "      - name: Logs\n        uses: actions/upload-artifact@v4\n        with:\n          name: logs\n          path: logs\n          include-hidden-files: true\n"))]
      (is (= {:name "dist" :paths ["dist"]} (artifacts model)))
      (is (= [(str "Workflow `.github/workflows/ci.yml`, Job `build`, Step `Logs`: Action `actions/upload-artifact@v4` "
                   "sets the Job's `artifacts` to `{name: logs, paths: [logs]}`, but Step `Dist` "
                   "(Action `actions/upload-artifact@v4`) already set it to `{name: dist, paths: [dist]}`, which the Job keeps; "
                   "GitLab keeps one artifacts archive per Job, so add these paths to it or upload them from another Job by hand.")]
             (map :message (:gaps model)))))))

(deftest hidden-files-test
  (testing "v4.4 and later leave hidden files out unless include-hidden-files is true, so GitLab excludes them too"
    (doseq [uses ["actions/upload-artifact@v4" "actions/upload-artifact@v4.4.0" "actions/upload-artifact@v5"
                  "actions/upload-artifact@65c4c4a1ddee5b72f698fdd19549f0f0fb45cf08"]
            with ["" "          include-hidden-files: false\n"]]
      (let [model (convert (str "      - uses: " uses "\n        with:\n          path: out\n" with))]
        (is (= {:paths ["out"] :exclude ["**/.*" "**/.*/**"]} (artifacts model)) uses)
        (is (= [] (:gaps model)) uses))))
  (testing "before v4.4, upload-artifact kept hidden files, as GitLab does"
    (doseq [uses ["actions/upload-artifact@v3" "actions/upload-artifact@v4.3.6"]]
      (is (= {:paths ["out"]}
             (artifacts (convert (str "      - uses: " uses "\n        with:\n          path: out\n"))))
          uses))))
