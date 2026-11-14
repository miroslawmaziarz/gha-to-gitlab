(ns gha-to-gitlab.runner-mapping-test
  (:require [clojure.test :refer [deftest is testing]]
            [gha-to-gitlab.runner-mapping :as runner-mapping]))

(defn- model-running-on [& labels]
  {:jobs (vec (map-indexed (fn [i label] {:id (str "job" i) :runs-on label :script ["make"]})
                           labels))
   :gaps []})

(deftest default-ubuntu-mapping-test
  (testing "ubuntu-* labels get a matching Ubuntu image and no runner tags"
    (is (= [{:image "ubuntu:24.04" :tags []}
            {:image "ubuntu:24.04" :tags []}
            {:image "ubuntu:22.04" :tags []}]
           (->> (runner-mapping/apply-runner-mappings
                 (model-running-on "ubuntu-latest" "ubuntu-24.04" "ubuntu-22.04") {})
                :jobs
                (map #(select-keys % [:image :tags])))))))

(deftest unmapped-label-test
  (let [model (runner-mapping/apply-runner-mappings
               (model-running-on "macos-latest" "self-hosted, linux") {})]
    (testing "a Job with an unmapped label gets no image or tags"
      (is (= [nil nil] (map :image (:jobs model))))
      (is (= [nil nil] (map :tags (:jobs model)))))
    (testing "each unmapped label is a Gap naming the Job and the label"
      (is (= [{:message "Job `job0` runs on `macos-latest`, which has no Runner Mapping; add one in the Migration Config or set the Job's image and tags by hand."}
              {:message "Job `job1` runs on `self-hosted, linux`, which has no Runner Mapping; add one in the Migration Config or set the Job's image and tags by hand."}]
             (:gaps model))))))

(deftest missing-runs-on-test
  (testing "a Job with no runs-on is left without an image and adds no Gap"
    (let [model (runner-mapping/apply-runner-mappings
                 {:jobs [{:id "j" :script ["make"]}] :gaps []} {})]
      (is (= {:jobs [{:id "j" :script ["make"]}] :gaps []} model)))))

(deftest override-test
  (let [model (runner-mapping/apply-runner-mappings
               (model-running-on "ubuntu-latest" "macos-latest" "ubuntu-22.04")
               {"ubuntu-latest" {:image "node:22" :tags ["docker"]}
                "macos-latest" {:image "macos-14" :tags ["saas-macos-medium-m1"]}})]
    (testing "an override replaces a built-in Runner Mapping and adds new labels; other defaults stay"
      (is (= [{:image "node:22" :tags ["docker"]}
              {:image "macos-14" :tags ["saas-macos-medium-m1"]}
              {:image "ubuntu:22.04" :tags []}]
             (map #(select-keys % [:image :tags]) (:jobs model)))))
    (testing "a label mapped by an override is not a Gap"
      (is (= [] (:gaps model))))))

(deftest other-ubuntu-labels-test
  (let [model (runner-mapping/apply-runner-mappings
               (model-running-on "ubuntu-22.04-arm" "ubuntu-24.04-arm" "ubuntu-latest-4-cores")
               {})]
    (testing "other ubuntu-* labels use the version in the label, or the ubuntu-latest image, with no tags"
      (is (= [{:image "ubuntu:22.04" :tags []}
              {:image "ubuntu:24.04" :tags []}
              {:image "ubuntu:24.04" :tags []}]
             (map #(select-keys % [:image :tags]) (:jobs model)))))
    (testing "they are not Gaps"
      (is (= [] (:gaps model)))))
  (testing "a Migration Config entry for such a label still wins"
    (is (= "custom:arm"
           (-> (runner-mapping/apply-runner-mappings
                (model-running-on "ubuntu-24.04-arm")
                {"ubuntu-24.04-arm" {:image "custom:arm" :tags []}})
               :jobs first :image)))))

(deftest expression-label-test
  (testing "a runs-on expression is a Gap saying it must be resolved by hand, not mapped"
    (is (= [{:message "Job `job0` runs on the expression `${{ matrix.os }}`, which is only known when the Workflow runs; resolve it and set the Job's image and tags by hand."}]
           (:gaps (runner-mapping/apply-runner-mappings (model-running-on "${{ matrix.os }}") {}))))))
