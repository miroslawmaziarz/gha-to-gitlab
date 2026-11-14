(ns gha-to-gitlab.migration-test
  (:require [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [gha-to-gitlab.migration :as migration])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir []
  (str (Files/createTempDirectory "migration-run" (make-array FileAttribute 0))))

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
