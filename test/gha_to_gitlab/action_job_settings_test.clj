(ns gha-to-gitlab.action-job-settings-test
  (:require [clojure.test :refer [deftest is testing]]
            [gha-to-gitlab.action-job-settings :as action-job-settings]
            [gha-to-gitlab.runner-mapping :as runner-mapping]))

(defn- model [job]
  {:jobs [(merge {:id "build" :runs-on "ubuntu-latest" :script ["npm test"]} job)] :gaps []})

(deftest action-settings-override-runner-mapping-test
  (let [job (-> (model {:action-job-settings {:image "node:20"}})
                (runner-mapping/apply-runner-mappings {"ubuntu-latest" {:image "ubuntu:24.04" :tags ["docker"]}})
                action-job-settings/apply-action-job-settings
                :jobs first)]
    (testing "applied after the Runner Mapping, an Action's image replaces the mapped image; tags stay"
      (is (= {:image "node:20" :tags ["docker"]} (select-keys job [:image :tags]))))
    (testing "the mapped image is kept for the Migration Report, and the pending settings are gone"
      (is (= "ubuntu:24.04" (:runner-mapping-image job)))
      (is (not (contains? job :action-job-settings))))))

(deftest no-runner-mapping-test
  (testing "a Job with no Runner Mapping image gets the Action's image and no :runner-mapping-image"
    (is (= {:id "build" :runs-on "macos-latest" :script ["npm test"] :image "node:20"}
           (-> (model {:runs-on "macos-latest" :action-job-settings {:image "node:20"}})
               action-job-settings/apply-action-job-settings
               :jobs first)))))

(deftest no-action-settings-test
  (testing "a Job without Action settings is unchanged"
    (is (= (model {:image "ubuntu:24.04" :tags []})
           (action-job-settings/apply-action-job-settings (model {:image "ubuntu:24.04" :tags []}))))))
