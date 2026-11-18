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

(deftest cache-test
  (let [yaml-text (gitlab/ci-model->gitlab-ci-yaml
                   {:jobs [{:id "one" :image "node:20" :script ["npm ci"]
                            :cache [{:paths [".npm"] :key {:prefix "node" :files ["package-lock.json"]}}]}
                           {:id "two" :script ["make"]
                            :cache [{:when "always" :policy "pull" :paths ["a"] :key "k1"}
                                    {:paths ["b"] :key "k2"}]}]
                    :gaps []})]
    (testing "one cache is written as a map; several as a list"
      (is (= {:one {:image "node:20"
                    :cache {:key {:files ["package-lock.json"] :prefix "node"} :paths [".npm"]}
                    :script ["npm ci"]}
              :two {:cache [{:key "k1" :paths ["a"] :policy "pull" :when "always"}
                            {:key "k2" :paths ["b"]}]
                    :script ["make"]}}
             (yaml/parse-string yaml-text :keywords true))))
    (testing "cache comes before script, and its keys read key, paths, policy, when; key files before prefix"
      (let [parsed (yaml/parse-string yaml-text)]
        (is (= [:image :cache :script] (keys (:one parsed))))
        (is (= [:files :prefix] (keys (get-in parsed [:one :cache :key]))))
        (is (= [:key :paths :policy :when] (keys (first (get-in parsed [:two :cache])))))))))

(deftest artifacts-test
  (let [yaml-text (gitlab/ci-model->gitlab-ci-yaml
                   {:jobs [{:id "build" :script ["make"] :cache [{:key "k" :paths ["c"]}]
                            :artifacts {:expire-in "5 days" :exclude ["dist/*.map"] :paths ["dist/"] :name "dist"}}]
                    :gaps []})
        job (:build (yaml/parse-string yaml-text))]
    (testing "artifacts are written with GitLab's names, after script, as name, paths, exclude, expire_in"
      (is (= [:cache :script :artifacts] (keys job)))
      (is (= {:name "dist" :paths ["dist/"] :exclude ["dist/*.map"] :expire_in "5 days"} (:artifacts job)))
      (is (= [:name :paths :exclude :expire_in] (keys (:artifacts job)))))))

(deftest variables-test
  (testing "a Job's Variables are written, sorted by name, between tags and cache"
    (let [job (-> {:jobs [{:id "build" :image "node:20" :script ["npm ci"]
                           :variables {"npm_config_cache" "$CI_PROJECT_DIR/.npm" "A" "1"}
                           :cache [{:key "k" :paths [".npm"]}]}]
                   :gaps []}
                  gitlab/ci-model->gitlab-ci-yaml
                  yaml/parse-string
                  :build)]
      (is (= [:image :variables :cache :script] (keys job)))
      (is (= [:A :npm_config_cache] (keys (:variables job))))
      (is (= "$CI_PROJECT_DIR/.npm" (get-in job [:variables :npm_config_cache]))))))

(deftest stages-test
  (let [yaml-text (gitlab/ci-model->gitlab-ci-yaml
                   {:jobs [{:id "deploy" :needs ["test" "image"] :script ["make deploy"]}
                           {:id "image" :script ["docker build ."]}
                           {:id "test" :needs ["build"] :script ["make test"]}
                           {:id "build" :script ["make"]}
                           {:id "lint" :script ["make lint"]}]
                    :gaps []})
        parsed (yaml/parse-string yaml-text)]
    (testing "Stages are listed first, one per level of the dependency order"
      (is (= ["stage-1" "stage-2" "stage-3"] (:stages parsed)))
      (is (= :stages (first (keys parsed)))))
    (testing "Jobs with no needs run in the first Stage, every other Job in the Stage after the last Job it needs"
      (is (= {:deploy "stage-3" :image_job "stage-1" :test "stage-2" :build "stage-1" :lint "stage-1"}
             (update-vals (dissoc parsed :stages) :stage))))
    (testing "needs: names the needed Jobs by their GitLab names, in Source order"
      (is (= ["test" "image_job"] (-> parsed :deploy :needs)))
      (is (= ["build"] (-> parsed :test :needs)))
      (is (not (contains? (:build parsed) :needs))))
    (testing "stage and needs come before script"
      (is (= [:stage :needs :script] (keys (:deploy parsed)))))))

(deftest unorderable-jobs-test
  (testing "a CI Model whose Jobs need a missing Job is refused rather than written"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Jobs `deploy` cannot be ordered"
          (gitlab/ci-model->gitlab-ci-yaml {:jobs [{:id "deploy" :needs ["bild"] :script ["make deploy"]}]
                                            :gaps []})))))

(deftest no-needs-no-stages-test
  (testing "without dependencies there is one Stage, so stages are left to GitLab's default"
    (is (= {:build {:script ["npm ci" "npm run build"]}
            :lint {:script ["npm run lint"]}}
           (yaml/parse-string (gitlab/ci-model->gitlab-ci-yaml model) :keywords true)))))
