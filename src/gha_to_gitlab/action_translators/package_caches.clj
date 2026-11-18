(ns gha-to-gitlab.action-translators.package-caches
  "The known package managers' caches, shared by the setup Actions' `cache`
  input and `actions/cache`. GitLab caches only paths inside the project
  directory, so each cache lives there, and Variables point the tool at it
  instead of its home-directory default."
  (:require [clojure.string :as str]))

(def package-managers
  "Per package manager: `:files`, the lock files a setup Action keys its
  cache on; `:paths`, the cache's paths inside the project; `:home`, the
  well-known home-directory caches (as `actions/cache` names them) and the
  path inside the project each moves to; and `:variables`, which point the
  tool at those paths."
  {"npm" {:files ["package-lock.json"]
          :paths [".npm"]
          :home {"~/.npm" ".npm"}
          :variables {"npm_config_cache" "$CI_PROJECT_DIR/.npm"}}
   "yarn" {:files ["yarn.lock"]
           :paths [".yarn-cache"]
           :home {"~/.cache/yarn" ".yarn-cache"}
           ;; Yarn 2+ only uses the cache folder without its global cache
           :variables {"YARN_CACHE_FOLDER" "$CI_PROJECT_DIR/.yarn-cache"
                       "YARN_ENABLE_GLOBAL_CACHE" "false"}}
   "pip" {:files ["**/requirements.txt"]
          :paths [".cache/pip"]
          :home {"~/.cache/pip" ".cache/pip"}
          :variables {"PIP_CACHE_DIR" "$CI_PROJECT_DIR/.cache/pip"}}
   ;; setup-python caches the virtualenv; keep it in the project as `.venv`
   "pipenv" {:files ["Pipfile.lock"]
             :paths [".venv"]
             :variables {"PIPENV_VENV_IN_PROJECT" "1"}}
   "poetry" {:files ["poetry.lock"]
             :paths [".venv"]
             :variables {"POETRY_VIRTUALENVS_IN_PROJECT" "true"}}
   "maven" {:files ["**/pom.xml"]
            :paths [".m2/repository"]
            :home {"~/.m2" ".m2/repository"
                   "~/.m2/repository" ".m2/repository"}
            :variables {"MAVEN_OPTS" "-Dmaven.repo.local=$CI_PROJECT_DIR/.m2/repository"}}
   ;; the files setup-java keys its Gradle cache on
   "gradle" {:files ["**/*.gradle*" "**/gradle-wrapper.properties" "buildSrc/**/Versions.kt"
                     "buildSrc/**/Dependencies.kt" "gradle/*.versions.toml" "**/versions.properties"]
             :paths [".gradle/caches" ".gradle/wrapper"]
             :home {"~/.gradle/caches" ".gradle/caches"
                    "~/.gradle/wrapper" ".gradle/wrapper"}
             :variables {"GRADLE_USER_HOME" "$CI_PROJECT_DIR/.gradle"}}})

(defn home-cache
  "For a well-known home-directory cache `path` (a trailing `/` is
  ignored): `{:manager .. :path <inside the project> :variables {..}}`;
  nil for any other path."
  [path]
  (let [path (if (and (< 2 (count path)) (str/ends-with? path "/")) (subs path 0 (dec (count path))) path)]
    (some (fn [[manager {:keys [home variables]}]]
            (when-let [in-project (get home path)]
              {:manager manager :path in-project :variables variables}))
          package-managers)))
