(ns gha-to-gitlab.gitlab-test
  (:require [clj-yaml.core :as yaml]
            [clojure.test :refer [deftest is testing]]
            [gha-to-gitlab.gitlab :as gitlab]))

(def model
  {:jobs [{:id "build" :script ["npm ci" "npm run build"]}
          {:id "lint" :script ["npm run lint"]}]
   :gaps []})

(deftest ci-model->gitlab-ci-yaml-test
  (let [yaml-text (gitlab/ci-model->gitlab-ci-yaml model)]
    (testing "one GitLab Job per CI Model Job, with the commands as script lines"
      (is (= {:build {:script ["npm ci" "npm run build"]}
              :lint {:script ["npm run lint"]}}
             (yaml/parse-string yaml-text :keywords true))))
    (testing "Jobs keep the order of the Source"
      (is (= [:build :lint] (keys (yaml/parse-string yaml-text)))))))

(deftest multi-line-run-test
  (testing "a multi-line command stays one script line"
    (is (= ["echo one\necho two\n"]
           (-> {:jobs [{:id "j" :script ["echo one\necho two\n"]}] :gaps []}
               gitlab/ci-model->gitlab-ci-yaml
               yaml/parse-string
               :j :script)))))

(deftest reserved-keyword-job-test
  (testing "a Job whose id is a GitLab global keyword is renamed so it stays a Job"
    (is (= {:image_job {:script ["make image"]}
            :variables_job {:script ["env"]}
            :build {:script ["make"]}}
           (-> {:jobs [{:id "image" :script ["make image"]}
                       {:id "variables" :script ["env"]}
                       {:id "build" :script ["make"]}]
                :gaps []}
               gitlab/ci-model->gitlab-ci-yaml
               yaml/parse-string)))))
