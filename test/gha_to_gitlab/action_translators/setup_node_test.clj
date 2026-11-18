(ns gha-to-gitlab.action-translators.setup-node-test
  (:require [clojure.test :refer [deftest is testing]]
            [gha-to-gitlab.test-support :refer [action-settings converted-model]]))

(defn- convert [step-yaml]
  (converted-model
   (str "on: push\njobs:\n  build:\n    steps:\n"
        step-yaml
        "      - run: npm test\n")))

(deftest node-version-sets-image-test
  (testing "setup-node is dropped and its node-version becomes the Job image, with no Gap"
    (let [model (convert "      - uses: actions/setup-node@v4\n        with:\n          node-version: 20\n")]
      (is (= [{:image "node:20"} ["npm test"]]
             (-> model :jobs first ((juxt action-settings :script)))))
      (is (= [] (:gaps model))))))

(defn- image-for [with-yaml]
  (-> (convert (str "      - uses: actions/setup-node@v4\n" with-yaml)) :jobs first action-settings :image))

(deftest version-forms-test
  (testing "exact versions, N.x, LTS aliases and latest map to node image tags"
    (is (= ["node:20.11.1" "node:18" "node:lts" "node:iron" "node:current"]
           (map #(image-for (str "        with:\n          node-version: '" % "'\n"))
                ["20.11.1" "18.x" "lts/*" "lts/Iron" "latest"]))))
  (testing "with no node-version the Job gets the current LTS image and no Gap"
    (let [model (convert "      - uses: actions/setup-node@v4\n")]
      (is (= "node:lts" (-> model :jobs first action-settings :image)))
      (is (= [] (:gaps model)))
      (is (= "converted to the Job image `node:lts` (no `node-version` given, so the current LTS)"
             (-> model :jobs first :actions first :outcome))))))

(deftest unconvertible-version-test
  (testing "a version range or expression is a Gap, and the Action sets no image (the Job keeps any Runner Mapping image)"
    (let [model (convert (str "      - name: Node\n        uses: actions/setup-node@v4\n"
                              "        with:\n          node-version: ${{ matrix.node }}\n"))]
      (is (nil? (-> model :jobs first action-settings :image)))
      (is (= [(str "Workflow `.github/workflows/ci.yml`, Job `build`, Step `Node`: Action `actions/setup-node@v4` "
                   "input `node-version: ${{ matrix.node }}` has no GitLab equivalent: "
                   "the version is only known when the Workflow runs; resolve it and set the Job's image (e.g. `node:20`) by hand.")]
             (map :message (:gaps model))))
      (is (= "dropped; set the Job's image by hand (see Gaps)"
             (-> model :jobs first :actions first :outcome)))))
  (testing "a range is a Gap naming node-version"
    (is (= ["node-version"]
           (->> (convert "      - uses: actions/setup-node@v4\n        with:\n          node-version: '>=18'\n")
                :gaps (map :input))))))

(deftest unhonoured-inputs-test
  (let [model (convert (str "      - uses: actions/setup-node@v4\n        with:\n"
                            "          node-version: 20\n          check-latest: true\n"
                            "          cache: npm\n          registry-url: https://npm.pkg.github.com\n"
                            "          architecture: x64\n          new-input: x\n"))]
    (testing "the version still sets the image"
      (is (= "node:20" (-> model :jobs first action-settings :image))))
    (testing "each input the image can't honour is a Gap naming it"
      (is (= ["registry-url" "architecture" "new-input"] (map :input (:gaps model))))))
  (testing "node-version-file is a Gap, and no image is set"
    (let [model (convert "      - uses: actions/setup-node@v4\n        with:\n          node-version-file: .nvmrc\n")]
      (is (nil? (-> model :jobs first action-settings :image)))
      (is (= ["node-version-file"] (map :input (:gaps model)))))))

(deftest unquoted-decimal-version-test
  (testing "an unquoted non-integer node-version is a Gap asking to quote it; an integer is fine"
    (let [model (convert "      - uses: actions/setup-node@v4\n        with:\n          node-version: 20.10\n")]
      (is (nil? (-> model :jobs first action-settings)))
      (is (= ["node-version"] (map :input (:gaps model))))
      (is (re-find #"quote the version" (-> model :gaps first :message))))))
