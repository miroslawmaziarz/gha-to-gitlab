(ns gha-to-gitlab.action-job-settings-test
  (:require [clojure.test :refer [deftest is testing]]
            [gha-to-gitlab.action-job-settings :as action-job-settings]))

(defn- request [step action settings]
  {:step step :action action :settings settings})

(deftest single-value-setting-test
  (let [{:keys [settings results]}
        (action-job-settings/settle
         [(request "Node" "actions/setup-node@v4" {:image "node:20"})
          (request "Python" "actions/setup-python@v5" {:image "python:3.12"})
          (request "step 3" "actions/setup-node@v4" {:image "node:20"})
          (request "step 4" "acme/nothing@v1" nil)])]
    (testing "the earliest Action to set a setting wins"
      (is (= {:image "node:20"} settings)))
    (testing "a later Action asking for a different value keeps nothing and gets a Gap naming the Action that won"
      (is (= [{:kept [:image] :gaps []}
              {:kept []
               :gaps [{:message (str "sets the Job's `image` to `python:3.12`, but Step `Node` (Action "
                                     "`actions/setup-node@v4`) already set it to `node:20`, which the Job keeps; "
                                     "use one image that has everything the Job needs, or split the Job.")}]}
              {:kept [:image] :gaps []}
              {:kept [] :gaps []}]
             results)))))

(deftest list-setting-test
  (let [cache (fn [k] [{:key k :paths [k]}])
        {:keys [settings results]}
        (action-job-settings/settle
         (for [k ["k1" "k2" "k3" "k4" "k5"]]
           (request k "actions/cache@v4" {:cache (cache k)})))]
    (testing "a list setting collects every Action's entries, up to GitLab's limit of 4 caches"
      (is (= ["k1" "k2" "k3" "k4"] (map :key (:cache settings)))))
    (testing "the Action that would go past the limit keeps nothing and gets a Gap"
      (is (= {:kept []
              :gaps [{:message (str "adds the 5th entry to the Job's `cache`, but GitLab allows at most 4; "
                                    "the Job leaves it out, so merge it into another entry by hand.")}]}
             (last results))))))

(deftest map-setting-test
  (let [{:keys [settings results]}
        (action-job-settings/settle
         [(request "Node" "actions/setup-node@v4" {:variables {"npm_config_cache" "$CI_PROJECT_DIR/.npm"}})
          (request "Other" "acme/env@v1" {:variables {"A" "1"}})
          (request "Clash" "acme/env@v1" {:variables {"npm_config_cache" "/tmp" "B" "2"}})])]
    (testing "a map setting combines the Actions' entries; an Action with a clashing entry adds none of them"
      (is (= {:variables {"npm_config_cache" "$CI_PROJECT_DIR/.npm" "A" "1"}} settings)))
    (testing "the clashing Action gets a Gap naming only the clashing entry"
      (is (= [{:kept [:variables] :gaps []}
              {:kept [:variables] :gaps []}
              {:kept []
               :gaps [{:message (str "sets the Job's `variables` to `{npm_config_cache: /tmp}`, but Step `Node` "
                                     "(Action `actions/setup-node@v4`) already set it to "
                                     "`{npm_config_cache: $CI_PROJECT_DIR/.npm}`, which the Job keeps; "
                                     "settle on one value by hand.")}]}]
             results)))))

(deftest own-variables-test
  (let [{:keys [settings results]}
        (action-job-settings/settle
         {:variables {"npm_config_cache" "/cache" "A" "1"}}
         [(request "Node" "actions/setup-node@v4" {:variables {"npm_config_cache" "$CI_PROJECT_DIR/.npm"}})
          (request "Same" "acme/env@v1" {:variables {"A" "1" "B" "2"}})])]
    (testing "an Action's Variable that the Job's own env: sets differently is left out; the settled settings exclude the Job's own"
      (is (= {:variables {"A" "1" "B" "2"}} settings)))
    (testing "the Action gets a Gap naming the env: value the Job keeps"
      (is (= [{:kept []
               :gaps [{:message (str "sets the Job's `variables` to `{npm_config_cache: $CI_PROJECT_DIR/.npm}`, "
                                     "but `env:` already sets it to `{npm_config_cache: /cache}`, which the Job keeps; "
                                     "settle on one value by hand.")}]}
              {:kept [:variables] :gaps []}]
             results)))))
