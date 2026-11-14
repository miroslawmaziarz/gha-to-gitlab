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

(deftest image-and-tags-test
  (let [yaml-text (gitlab/ci-model->gitlab-ci-yaml
                   {:jobs [{:id "build" :image "ubuntu:24.04" :tags [] :script ["make"]}
                           {:id "mac" :image "macos-14" :tags ["saas-macos-medium-m1"] :script ["xcodebuild"]}
                           {:id "bare" :script ["true"]}]
                    :gaps []})]
    (testing "a Job's image and non-empty tags are written; empty tags and a missing image are left out"
      (is (= {:build {:image "ubuntu:24.04" :script ["make"]}
              :mac {:image "macos-14" :tags ["saas-macos-medium-m1"] :script ["xcodebuild"]}
              :bare {:script ["true"]}}
             (yaml/parse-string yaml-text :keywords true))))
    (testing "image and tags come before script"
      (is (= [:image :tags :script] (keys (:mac (yaml/parse-string yaml-text))))))))
