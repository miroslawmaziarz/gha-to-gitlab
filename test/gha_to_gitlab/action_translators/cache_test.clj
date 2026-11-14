(ns gha-to-gitlab.action-translators.cache-test
  (:require [clojure.test :refer [deftest is testing]]
            [gha-to-gitlab.github :as github]))

(defn- convert [step-yaml]
  (github/workflow->ci-model
   (str "on: push\njobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n"
        step-yaml
        "      - run: npm test\n")
   {:workflow-path ".github/workflows/ci.yml"}))

(deftest literal-key-test
  (testing "actions/cache with a path and a literal key becomes a Job cache, with no Gap"
    (let [model (convert "      - uses: actions/cache@v4\n        with:\n          path: node_modules\n          key: deps-v1\n")]
      (is (= {:action-job-settings {:cache [{:key "deps-v1" :paths ["node_modules"]}]} :script ["npm test"]}
             (-> model :jobs first (select-keys [:action-job-settings :script]))))
      (is (= [] (:gaps model)))
      (is (= "converted to a Job cache" (-> model :jobs first :actions first :outcome))))))

(deftest paths-test
  (testing "a multi-line path becomes one cache path per line"
    (is (= [{:key "k" :paths [".npm" "vendor/**/*.gem"]}]
           (-> (convert "      - uses: actions/cache@v4\n        with:\n          path: |\n            .npm\n\n            vendor/**/*.gem\n          key: k\n")
               :jobs first :action-job-settings :cache))))
  (testing "a path outside the project directory is a Gap naming the input; the other paths are still cached"
    (let [model (convert (str "      - name: Cache\n        uses: actions/cache@v4\n        with:\n"
                              "          path: |\n            ~/.npm\n            node_modules\n            /opt/x\n"
                              "            ../up\n            ${{ env.DIR }}\n            !node_modules/.bin\n"
                              "          key: k\n"))]
      (is (= [{:key "k" :paths ["node_modules"]}] (-> model :jobs first :action-job-settings :cache)))
      (is (= ["path" "path" "path" "path" "path"] (map :input (:gaps model))))
      (is (= (str "Workflow `.github/workflows/ci.yml`, Job `build`, Step `Cache`: Action `actions/cache@v4` "
                  "input `path: ~/.npm` has no GitLab equivalent: GitLab caches only paths inside the project "
                  "directory; point the tool at a directory inside it and cache that (for npm, set the Variable "
                  "`npm_config_cache` to `$CI_PROJECT_DIR/.npm` and cache `.npm`) by hand.")
             (-> model :gaps first :message)))
      (is (re-find #"input `path: \$\{\{ env.DIR \}\}` has no GitLab equivalent: the path is only known when the Workflow runs"
                   (-> model :gaps (nth 3) :message)))
      (is (re-find #"input `path: !node_modules/.bin` has no GitLab equivalent: a GitLab cache can't exclude paths"
                   (-> model :gaps (nth 4) :message)))))
  (testing "with no path left to cache the Step is dropped with no cache"
    (let [model (convert "      - uses: actions/cache@v4\n        with:\n          path: ~/.m2\n          key: k\n")]
      (is (nil? (-> model :jobs first :action-job-settings :cache)))
      (is (= "dropped; add the Job cache by hand (see Gaps)" (-> model :jobs first :actions first :outcome)))))
  (testing "a missing path is a Gap"
    (is (= ["path"] (->> (convert "      - uses: actions/cache@v4\n        with:\n          key: k\n") :gaps (map :input))))))

(defn- cache-for [key-yaml]
  (convert (str "      - uses: actions/cache@v4\n        with:\n          path: .npm\n          key: " key-yaml "\n")))

(deftest key-test
  (testing "hashFiles becomes a key on those files, and the rest of the key its prefix"
    (let [model (cache-for "${{ runner.os }}-node-${{ hashFiles('**/package-lock.json') }}")]
      (is (= [{:key {:files ["**/package-lock.json"] :prefix "node"} :paths [".npm"]}]
             (-> model :jobs first :action-job-settings :cache)))
      (is (= [] (:gaps model)))
      (is (= "converted to a Job cache (its key leaves out `runner.os`, which GitLab has no Variable for)"
             (-> model :jobs first :actions first :outcome)))))
  (testing "a key that is only hashFiles of two files has no prefix"
    (is (= {:files ["Gemfile.lock" "yarn.lock"]}
           (-> (cache-for "${{ hashFiles('Gemfile.lock', \"yarn.lock\") }}") :jobs first :action-job-settings :cache first :key))))
  (testing "commit and branch expressions become GitLab Variables"
    (is (= "build-$CI_COMMIT_REF_SLUG-$CI_COMMIT_SHA"
           (-> (cache-for "build-${{ github.ref_name }}-${{github.sha}}") :jobs first :action-job-settings :cache first :key))))
  (testing "a key GitLab can't express is a Gap naming the input, and no cache is added"
    (doseq [[key-yaml hint] [["deps-${{ matrix.node }}-${{ hashFiles('a.lock') }}"
                              "`matrix.node` is only known when the Workflow runs; write the Job cache's key by hand."]
                             ["${{ hashFiles('a.lock', 'b.lock', 'c.lock') }}"
                              "a GitLab cache key can depend on at most two files; write the Job cache's key by hand."]
                             ["${{ hashFiles('**/*.lock', '!vendor/**') }}"
                              "a GitLab cache key can't exclude files; write the Job cache's key by hand."]
                             ["deps/${{ hashFiles('a.lock') }}"
                              "a GitLab cache key can't contain `/`; write the Job cache's key by hand."]]]
      (let [model (cache-for key-yaml)]
        (is (nil? (-> model :jobs first :action-job-settings :cache)) key-yaml)
        (is (= [(str "input `key: " key-yaml "` has no GitLab equivalent: " hint)]
               (map #(subs (:message %) (count "Workflow `.github/workflows/ci.yml`, Job `build`, Step `step 1`: Action `actions/cache@v4` "))
                    (:gaps model))))
        (is (= ["key"] (map :input (:gaps model)))))))
  (testing "a missing key is a Gap"
    (is (= ["key"] (->> (convert "      - uses: actions/cache@v4\n        with:\n          path: .npm\n") :gaps (map :input))))))

(deftest other-inputs-test
  (let [model (convert (str "      - uses: actions/cache@v4\n        with:\n          path: .npm\n          key: k\n"
                            "          restore-keys: |\n            npm-\n"
                            "          enableCrossOsArchive: true\n          upload-chunk-size: 1000\n"
                            "          fail-on-cache-miss: false\n          lookup-only: false\n"
                            "          save-always: true\n          new-input: x\n"))]
    (testing "save-always becomes a cache saved whatever the Job's result"
      (is (= [{:key "k" :paths [".npm"] :when "always"}] (-> model :jobs first :action-job-settings :cache))))
    (testing "restore-keys and unknown inputs are Gaps naming them; harmless inputs are not"
      (is (= ["restore-keys" "new-input"] (map :input (:gaps model))))
      (is (re-find #"input `restore-keys: npm-` has no GitLab equivalent: GitLab's `fallback_keys` match whole keys, not prefixes"
                   (-> model :gaps first :message)))))
  (testing "fail-on-cache-miss and lookup-only are Gaps when true"
    (is (= ["fail-on-cache-miss" "lookup-only"]
           (->> (convert (str "      - uses: actions/cache@v4\n        with:\n          path: .npm\n          key: k\n"
                              "          fail-on-cache-miss: true\n          lookup-only: true\n"))
                :gaps (map :input))))))

(deftest restore-and-save-test
  (testing "cache/restore only pulls the cache and cache/save only pushes it"
    (doseq [[action policy outcome] [["restore" "pull" "converted to a Job cache that is only restored (`policy: pull`)"]
                                     ["save" "push" "converted to a Job cache that is only saved (`policy: push`)"]]]
      (let [model (convert (str "      - uses: actions/cache/" action "@v4\n        with:\n          path: .npm\n          key: k\n"))]
        (is (= [{:key "k" :paths [".npm"] :policy policy}] (-> model :jobs first :action-job-settings :cache)))
        (is (= [] (:gaps model)))
        (is (= outcome (-> model :jobs first :actions first :outcome)))))))

(defn- cache-step [n]
  (str "      - name: Cache " n "\n        uses: actions/cache@v4\n        with:\n          path: c" n "\n          key: k" n "\n"))

(deftest several-caches-test
  (testing "each cache Step in a Job adds one cache to it, in Step order"
    (let [model (convert (apply str (map cache-step [1 2])))]
      (is (= [{:key "k1" :paths ["c1"]} {:key "k2" :paths ["c2"]}] (-> model :jobs first :action-job-settings :cache)))
      (is (= [] (:gaps model)))))
  (testing "a Job keeps at most 4 caches, as GitLab allows; a 5th is a Gap at its Step"
    (let [model (convert (apply str (map cache-step [1 2 3 4 5])))]
      (is (= ["k1" "k2" "k3" "k4"] (map :key (-> model :jobs first :action-job-settings :cache))))
      (is (= [(str "Workflow `.github/workflows/ci.yml`, Job `build`, Step `Cache 5`: Action `actions/cache@v4` "
                   "adds a 5th entry to the Job's `cache`, but GitLab allows at most 4; "
                   "the Job leaves it out, so merge it into another entry by hand.")]
             (map :message (:gaps model))))
      (is (= [0 0 0 0 1] (map :gap-count (-> model :jobs first :actions)))))))
