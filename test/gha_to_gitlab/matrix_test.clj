(ns gha-to-gitlab.matrix-test
  "Matrices (`strategy.matrix`): the GitLab parallel matrix and its Gaps,
  and whole Migration Runs."
  (:require [clj-yaml.core :as yaml]
            [clojure.test :refer [deftest is testing]]
            [gha-to-gitlab.matrix :as matrix]
            [gha-to-gitlab.migration :as migration]))

(def ^:private location {:workflow "ci.yml" :job "test"})

(deftest job-matrix-test
  (testing "each dimension becomes a Variable with the same values, so GitLab runs the same combinations"
    (is (= {:matrix [{"os" ["linux" "mac"] "node_version" ["18" "20"]}]
            :values {"os" ["linux" "mac"] "node-version" ["18" "20"]}
            :gaps []}
           (matrix/job-matrix location {:matrix {:os ["linux" "mac"] :node-version ["18" "20"]}
                                        :fail-fast "false"})))))

(defn- gaps [strategy]
  (mapv :message (:gaps (matrix/job-matrix location strategy))))

(def ^:private prefix "Workflow `ci.yml`, Job `test`: ")

(deftest matrix-gaps-test
  (testing "include and exclude change the combinations, which a GitLab parallel matrix cannot"
    (is (= [(str prefix "its Matrix has `include` `[{os: win, node: 20}]`; GitLab runs only the combinations of the"
                 " dimensions, so add the included runs by hand.")
            (str prefix "its Matrix has `exclude` `[{os: mac}]`; GitLab runs every combination of the dimensions,"
                 " so remove the excluded runs by hand.")]
           (gaps {:matrix {:os ["linux" "mac"] :include [{:os "win" :node "20"}] :exclude [{:os "mac"}]}
                  :fail-fast "false"}))))
  (testing "GitLab never cancels the other runs when one fails, so only `fail-fast: false` matches"
    (is (= [(str prefix "its Matrix cancels the other runs when one fails (`fail-fast` is true unless set to false);"
                 " GitLab runs them all, so cancel them by hand if that matters.")]
           (gaps {:matrix {:os ["linux"]}})
           (gaps {:matrix {:os ["linux"]} :fail-fast "true"}))))
  (testing "GitLab cannot limit how many runs go at once"
    (is (= [(str prefix "its Matrix runs at most `2` at once (`max-parallel`); GitLab starts as many as runners allow,"
                 " so limit them by hand if that matters.")]
           (gaps {:matrix {:os ["linux"]} :fail-fast "false" :max-parallel "2"}))))
  (testing "keys that become the same Variable name are a Gap, and only the first is kept"
    (is (= {:matrix [{"node_version" ["18"]}]
            :values {"node-version" ["18"]}
            :gaps [{:workflow "ci.yml" :job "test"
                    :message (str prefix "its Matrix keys `node-version` and `node_version` are both the GitLab Variable"
                                  " `node_version`; the Pipeline keeps `node-version`, so rename one by hand.")}]}
           (matrix/job-matrix location {:matrix {:node-version ["18"] :node_version ["20"]} :fail-fast "false"}))))
  (testing "a Matrix or a dimension given as an expression is only known when the Workflow runs"
    (is (= {:matrix nil
            :values {}
            :gaps [{:workflow "ci.yml" :job "test"
                    :message (str prefix "its Matrix is the expression `${{ fromJSON(needs.a.outputs.m) }}`;"
                                  " set the Job's parallel matrix by hand.")}]}
           (matrix/job-matrix location {:matrix "${{ fromJSON(needs.a.outputs.m) }}" :fail-fast "false"})))
    (is (= {:matrix [{"os" ["linux"]}]
            :values {"os" ["linux"]}
            :gaps [{:workflow "ci.yml" :job "test"
                    :message (str prefix "its Matrix dimension `node` is `${{ fromJSON(x) }}`, not a list of values;"
                                  " the Pipeline leaves it out, so add it to the parallel matrix by hand.")}]}
           (matrix/job-matrix location {:matrix {:os ["linux"] :node "${{ fromJSON(x) }}"} :fail-fast "false"}))))
  (testing "a dimension with no values is left out"
    (is (= [(str prefix "its Matrix dimension `os` has no values, which GitHub refuses; the Pipeline leaves it out,"
                 " so give it values by hand.")]
           (gaps {:matrix {:os [] :node ["20"]} :fail-fast "false"}))))
  (testing "a value that is a map or list cannot be a Variable value"
    (is (= [(str prefix "its Matrix dimension `node` has values that are not text (`[{version: 20}]`);"
                 " the Pipeline leaves it out, so add it to the parallel matrix by hand.")]
           (gaps {:matrix {:os ["linux"] :node [{:version "20"}]} :fail-fast "false"}))))
  (testing "GitLab runs at most 200 Jobs from one parallel matrix, so a larger Matrix is left out"
    (is (= [(str prefix "its Matrix has 210 combinations, and GitLab allows at most 200; the Pipeline leaves the Matrix out,"
                 " so split the Job by hand.")]
           (gaps {:matrix {:a (mapv str (range 21)) :b (mapv str (range 10))} :fail-fast "false"})))))

(defn- convert-job [job-yaml]
  (migration/convert {:workflows {".github/workflows/ci.yml" (str "on: push\njobs:\n  test:\n" job-yaml)}}))

(defn- ci-job [job-yaml]
  (get (yaml/parse-string (:gitlab-ci-yaml (convert-job job-yaml)) :keywords false) "test"))

(defn- gap-messages [job-yaml]
  (mapv :message (:gaps (:model (convert-job job-yaml)))))

(def ^:private node-matrix-job
  (str "    runs-on: ubuntu-latest\n"
       "    strategy:\n      fail-fast: false\n      matrix:\n        node-version: [18, 20.10]\n        os: [linux]\n"
       "    env:\n      LABEL: node-${{ matrix.node-version }}\n"
       "    steps:\n"
       "      - run: echo ${{ matrix.node-version }} on ${{matrix.os}}\n"
       "      - uses: actions/setup-node@v4\n        with:\n          node-version: ${{ matrix.node-version }}\n"))

(deftest matrix-migration-test
  (testing "the Matrix becomes the Job's parallel matrix, and references become its Variables"
    (is (= {"parallel" {"matrix" [{"node_version" ["18" "20.1"] "os" ["linux"]}]}
            "image" "node:${node_version}"
            "variables" {"LABEL" "node-${node_version}"}
            "script" ["echo ${node_version} on ${os}"]}
           (ci-job node-matrix-job)))
    (is (= [] (gap-messages node-matrix-job))))
  (testing "an Action input from the Matrix converts only when every value converts the same way; otherwise the setting is left out"
    (let [job-yaml (str "    runs-on: ubuntu-latest\n"
                        "    strategy:\n      fail-fast: false\n      matrix:\n        node-version: [20, 'lts/*']\n"
                        "    steps:\n      - uses: actions/setup-node@v4\n        with:\n"
                        "          node-version: ${{ matrix.node-version }}\n")]
      (is (= [(str "Workflow `.github/workflows/ci.yml`, Job `test`, Step `step 1`: Action `actions/setup-node@v4` input"
                   " `node-version: ${{ matrix.node-version }}` is the Matrix Variable `node_version`, and its value"
                   " `lts/*` makes the Job's `image` `node:lts`, not `node:lts/*`; the Job's `image` is left out,"
                   " so set it per value by hand.")]
             (gap-messages job-yaml)))
      (is (= "ubuntu:24.04" (get (ci-job job-yaml) "image")))))
  (testing "runs-on from the Matrix, alone or in a list, has no single Runner Mapping"
    (is (= [(str "Workflow `.github/workflows/ci.yml`, Job `test`: its `runs-on` `${{ matrix.os }}` comes from the Matrix"
                 " (`os`: `[ubuntu-latest, macos-latest]`); GitLab picks one runner per Job, so set the image and tags"
                 " per value by hand.")]
           (gap-messages (str "    runs-on: ${{ matrix.os }}\n"
                              "    strategy:\n      fail-fast: false\n      matrix:\n        os: [ubuntu-latest, macos-latest]\n"
                              "    steps:\n      - run: make\n"))))
    (is (= [(str "Workflow `.github/workflows/ci.yml`, Job `test`: its `runs-on` `self-hosted, ${{ matrix.os }}` comes"
                 " from the Matrix (`os`: `[linux, mac]`); GitLab picks one runner per Job, so set the image and tags"
                 " per value by hand.")]
           (gap-messages (str "    runs-on: [self-hosted, '${{ matrix.os }}']\n"
                              "    strategy:\n      fail-fast: false\n      matrix:\n        os: [linux, mac]\n"
                              "    steps:\n      - run: make\n")))))
  (testing "a version written as a Variable reference in the Source is not a Matrix Variable"
    (is (= [(str "Workflow `.github/workflows/ci.yml`, Job `test`, Step `step 1`: Action `actions/setup-node@v4` input"
                 " `node-version: ${FOO}` has no GitLab equivalent: an image needs one exact version;"
                 " set the Job's image (e.g. `node:20`) by hand.")]
           (gap-messages (str "    strategy:\n      fail-fast: false\n      matrix:\n        os: [linux]\n"
                              "    steps:\n      - uses: actions/setup-node@v4\n        with:\n"
                              "          node-version: ${FOO}\n")))))
  (testing "a Matrix Variable with the name of an env: Variable is a Gap"
    (is (= [(str "Workflow `.github/workflows/ci.yml`, Job `test`: its Matrix Variable `os` has the name of an `env:`"
                 " Variable; GitLab gives the Job one value, so rename one by hand.")]
           (gap-messages (str "    env:\n      os: fixed\n"
                              "    strategy:\n      fail-fast: false\n      matrix:\n        os: [linux]\n"
                              "    steps:\n      - run: make\n"))))))
