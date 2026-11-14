(ns gha-to-gitlab.action-translators.setup-java-test
  (:require [clojure.test :refer [deftest is testing]]
            [gha-to-gitlab.github :as github]))

(defn- convert [with-yaml]
  (github/workflow->ci-model
   (str "on: push\njobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n"
        "      - uses: actions/setup-java@v4\n" with-yaml
        "      - run: ./mvnw verify\n")
   {:workflow-path ".github/workflows/ci.yml"}))

(defn- java [distribution version]
  (convert (str "        with:\n          distribution: " distribution "\n          java-version: '" version "'\n")))

(deftest java-version-and-distribution-set-image-test
  (testing "setup-java is dropped and java-version plus distribution become the Job image, with no Gap"
    (let [model (java "temurin" "21")]
      (is (= {:image "eclipse-temurin:21" :script ["./mvnw verify"]}
             (-> model :jobs first (select-keys [:image :script]))))
      (is (= [] (:gaps model)))))
  (testing "each supported distribution has its own image"
    (is (= ["eclipse-temurin:17" "azul/zulu-openjdk:17" "amazoncorretto:17"
            "mcr.microsoft.com/openjdk/jdk:17-ubuntu" "ibm-semeru-runtimes:open-17-jdk" "sapmachine:17"]
           (map #(-> (java % "17") :jobs first :image)
                ["temurin" "zulu" "corretto" "microsoft" "semeru" "sapmachine"]))))
  (testing "N.x is the latest of that line"
    (is (= "eclipse-temurin:17" (-> (java "temurin" "17.x") :jobs first :image)))))

(deftest unconvertible-java-test
  (testing "a distribution with no image rule is a Gap naming distribution"
    (let [model (java "oracle" "21")]
      (is (nil? (-> model :jobs first :image)))
      (is (= ["distribution"] (map :input (:gaps model))))))
  (testing "missing java-version or distribution is a Gap naming the input"
    (is (= ["java-version"] (map :input (:gaps (convert "        with:\n          distribution: temurin\n")))))
    (is (= ["distribution"] (map :input (:gaps (convert "        with:\n          java-version: 21\n"))))))
  (testing "a version range is a Gap naming java-version"
    (is (= ["java-version"] (map :input (:gaps (java "temurin" "[17,21)"))))))
  (testing "inputs the image can't honour are Gaps naming them"
    (let [model (convert (str "        with:\n          distribution: temurin\n          java-version: 21\n"
                              "          java-package: jdk\n          check-latest: true\n"
                              "          cache: maven\n          server-id: central\n"
                              "          gpg-private-key: ${{ secrets.GPG }}\n          java-package2: x\n"))]
      (is (= "eclipse-temurin:21" (-> model :jobs first :image)))
      (is (= ["cache" "server-id" "gpg-private-key" "java-package2"] (map :input (:gaps model))))))
  (testing "java-package other than jdk is a Gap"
    (is (= ["java-package"]
           (map :input (:gaps (convert (str "        with:\n          distribution: temurin\n"
                                            "          java-version: 21\n          java-package: jre\n"))))))))
