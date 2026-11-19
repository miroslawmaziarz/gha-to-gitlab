# github-2-gitlab

Convert a GitHub Actions Workflow to GitLab CI, with a Migration Report of everything that still needs a person. Written in Clojure.

> **Status:** early. It converts one Workflow per Migration Run, including the most common Actions and each Job's runner. Matrices and `if:` conditions are not converted yet (see the open issues). See `GLOSSARY.md` for the domain language and `docs/adr/` for key decisions.

## What it does

- Reads the Workflow under `.github/workflows/` in a local clone of the Source repository.
- Converts it through a platform-independent CI Model into a `.gitlab-ci.yml` (ADR-0001).
- Writes a Migration Report listing what was converted and every Gap: anything it could not convert faithfully. A Gap never stops the Migration Run.

Repositories, issues and pull requests are out of scope; GitLab's built-in GitHub importer already handles those.

### Features

| GitHub | GitLab |
|---|---|
| `run:` Step | a script line |
| `runs-on` | the Job's `image` and runner `tags` (Runner Mapping) |
| `on: push`, `pull_request` (with `branches`, `tags`, `-ignore` filters) | `workflow: rules` on the Pipeline source and branch/tag regexes (ADR-0008) |
| `on: schedule`, `workflow_dispatch` | a rule for scheduled and Run pipeline Pipelines, plus a Gap: create the Pipeline Schedule by hand |
| path filters, other events | a Gap |
| Workflow / Job `env:` | Pipeline / Job `variables` |
| Step `env:` | Job `variables`, when every Step agrees on the value or the Job already has it; a Gap otherwise (ADR-0007) |
| `${{ env.X }}`, `${{ secrets.X }}` in commands and `env:` | `${X}`, plus one Gap per Secret to create it as a masked Variable |
| `needs:` | the Job's `needs:`, plus derived `stages` (`stage-1`, `stage-2`, …) so every Job runs after the Jobs it needs |
| `actions/checkout` | dropped: GitLab checks out the repository itself |
| `actions/setup-node`, `setup-python`, `setup-java` | the Job's `image` (`node:20`, `python:3.12`, `eclipse-temurin:21`, …), overriding the Runner Mapping image |
| setup Actions' `cache:` (npm, yarn, pip, pipenv, poetry, maven, gradle) | a Job `cache` inside the project, plus the Variable that points the tool at it |
| `actions/cache` (also `/restore`, `/save`) | a Job `cache`; `hashFiles(...)` keys become `key: files` |
| `actions/upload-artifact` | Job `artifacts` (`name`, `paths`, `exclude`, `expire_in`) |
| any other Action | a placeholder script line and a Gap |

The Migration Report gives each Action's outcome. An input that GitLab can't honour (for example checkout's `ref`, or a version given as an expression) becomes a Gap naming that input, never a silent drop. Action versions (`@v4`, `@<sha>`) are ignored when choosing an Action Translator.

## Requirements

- Java 11 or newer
- [Clojure CLI](https://clojure.org/guides/install_clojure) 1.12+

## Usage

```sh
clojure -M:run [--config migration.edn] path/to/github-clone path/to/output
```

One Migration Run writes two files into the output directory:

- `.gitlab-ci.yml`: the converted Pipeline, with one GitLab Job per GitHub Job
- `MIGRATION_REPORT.md`: what was converted, and every Gap that needs a person

The run exits `0` even when there are Gaps. It exits `1` when the clone can't be converted at all (for example, no Workflow, or an invalid Migration Config) and `2` on bad arguments.

Try it against the bundled fixture:

```sh
clojure -M:run test/fixtures/plain-workflow /tmp/migration-out
```

### Example

```yaml
# .github/workflows/ci.yml
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Node
        uses: actions/setup-node@v4
        with:
          node-version: 20
          cache: npm
      - run: npm ci
      - run: npm run build
      - name: Upload dist
        uses: actions/upload-artifact@v4
        with:
          name: dist
          path: dist/
          retention-days: 7
  deploy:
    runs-on: macos-latest
    steps:
      - uses: acme/deploy@v2
```

becomes

```yaml
# .gitlab-ci.yml
build:
  image: node:20
  variables:
    npm_config_cache: $CI_PROJECT_DIR/.npm
  cache:
    key:
      files:
      - package-lock.json
      prefix: npm
    paths:
    - .npm
  script:
  - npm ci
  - npm run build
  artifacts:
    name: dist
    paths:
    - dist/
    exclude:
    - '**/.*'
    - '**/.*/**'
    expire_in: 7 days
deploy:
  script:
  - 'echo ''Gap: Action acme/deploy@v2 was not converted; see MIGRATION_REPORT.md'''
```

with this Migration Report:

```markdown
- `build`: 2 script lines; runs on `ubuntu-latest` → image `node:20` from Step `Node` (Action `actions/setup-node@v4`, overriding the Runner Mapping image `ubuntu:24.04`), no tags
  - Step `step 1`: Action `actions/checkout@v4` dropped (GitLab checks out the repository automatically)
  - Step `Node`: Action `actions/setup-node@v4` converted to the Job image `node:20` and a Job cache for npm
  - Step `Upload dist`: Action `actions/upload-artifact@v4` converted to the Job artifacts (hidden files left out with `exclude` patterns, as upload-artifact v4.4 and later does)
- `deploy`: 1 script line; runs on `macos-latest` → no Runner Mapping (see Gaps)

## Gaps

2 Gaps need a person to finish them:

- Workflow `.github/workflows/ci.yml`, Job `deploy`, Step `step 1`: Action `acme/deploy@v2` has no Action Translator; replace its placeholder script line by hand.
- Workflow `.github/workflows/ci.yml`, Job `deploy`: runs on `macos-latest`, which has no Runner Mapping; add one in the Migration Config or set the Job's image and tags by hand.
```

### Runner Mapping

Each Job's `runs-on` label is translated to a GitLab image and runner tags. Built-in Runner Mappings cover `ubuntu-latest`, `ubuntu-24.04`, `ubuntu-22.04` and `ubuntu-20.04` (the matching `ubuntu:` image, no tags). Any other `ubuntu-*` label uses the Ubuntu version in the label (`ubuntu-22.04-arm` → `ubuntu:22.04`), or the `ubuntu-latest` image when it names none (`ubuntu-latest-4-cores` → `ubuntu:24.04`), with no tags.

A label with no Runner Mapping (such as `macos-latest` or a self-hosted label) leaves the Job without an image and becomes a Gap. A `runs-on` list of several labels is looked up as one label joined with `, ` (e.g. `self-hosted, linux`), and a runner group as `group: big, labels: [linux, x64]`. A `runs-on` expression such as `${{ matrix.os }}` is a Gap to resolve by hand. An image set by a setup Action still wins over the Runner Mapping image.

### Migration Config

To override or extend the Runner Mappings, pass a Migration Config (an EDN file) with `--config`, before the two paths. Its entries win over the built-in ones:

```clojure
;; migration.edn
{:runner-mappings {"ubuntu-latest" {:image "node:22" :tags ["docker"]}
                   "macos-latest"  {:image "macos-14-xcode-15" :tags ["saas-macos-medium-m1"]}}}
```

```sh
clojure -M:run --config migration.edn path/to/github-clone path/to/output
```

`:tags` is optional and defaults to none.

### Current limits

- The clone must contain exactly one Workflow under `.github/workflows/` (#10).
- Not converted yet: Matrices (#8), and `if:` conditions on Jobs and Steps (#9, #17). A Step's `if:` is ignored without a Gap.
- `${{ secrets.X }}` and `${{ env.X }}` are rewritten only as whole expressions in commands and `env:` values; inside larger expressions or Action `with:` inputs they are left as they are (#9). A rewritten reference inside single quotes in a command no longer expands, and `shell:` other than the default is not considered.
- `cache: pnpm` and `cache: sbt` on setup Actions, and cache paths outside the project, have no faithful GitLab form, so they become Gaps. `restore-keys` is dropped with a note in the Action's outcome.
- Generated settings follow current GitLab (for example, up to 10 files in `cache:key:files`). Older self-managed instances may need adjustments.

Jobs with no `needs:` run in the first Stage, and every other Job in the Stage after the latest Job it needs; the Migration Report shows each Job's Stage. A Workflow without `needs:` gets no `stages`, as GitLab's default Stage runs all its Jobs together. A `needs:` naming a Job the Workflow does not define is left out and becomes a Gap.

A GitHub Job whose id is a GitLab global keyword (such as `image`, `variables` or `workflow`) is renamed with a `_job` suffix, and the Migration Report lists the rename.

## Development

```sh
bin/check                  # everything below; what CI and the pre-commit hook run
clojure -M:test            # run all tests
clojure -M:lint            # lint with clj-kondo (stricter rules in .clj-kondo/config.edn)
clojure -M:fmt check       # check formatting with cljfmt; `fix` rewrites (.cljfmt.edn)
clojure -M:splint src test # idiomatic-Clojure suggestions with Splint (.splint.edn)
clojure -M:coverage        # test coverage report in target/coverage
git config core.hooksPath .githooks   # once per clone: run bin/check before each commit
```

CI (`.github/workflows/ci.yml`) runs `bin/check` and the coverage report on every pull request and push to `main`. With Claude Code, the project's hooks (`.claude/settings.json`) check each Clojure file Claude writes (`bin/check-file`) and run `bin/check` before Claude finishes a turn that changed Clojure files (`bin/stop-check`).

Conversion always goes Source → CI Model → Target (ADR-0001). The code still uses the original `gha-to-gitlab` namespace prefix.

- `gha-to-gitlab.github`: reads a Workflow into the CI Model
- `gha-to-gitlab.ci-model`: the CI Model's Malli schema
- `gha-to-gitlab.action-translator`: the Action Translator extension point (`translate`, keyed by lower-case Action name)
- `gha-to-gitlab.action-translators`: requires every Action Translator; adding one means a new namespace under `action-translators/` plus one require here
- `gha-to-gitlab.runner-mapping`: applies Runner Mappings to the CI Model's Jobs
- `gha-to-gitlab.action-job-settings`: Action Job settings (image, cache, artifacts, Variables): settles them per Job (the earliest Action wins, GitLab limits, conflict Gaps) and applies them after the Runner Mapping (ADR-0002)
- `gha-to-gitlab.triggers`: `on:` as GitLab workflow rules (glob → regex, filter order) and Trigger Gaps
- `gha-to-gitlab.variables`: `env:` as Variables (lifting a Step's into its Job), `${{ env.X }}`/`${{ secrets.X }}` rewriting and Secret Gaps
- `gha-to-gitlab.config`: reads and validates the optional Migration Config
- `gha-to-gitlab.gitlab`: writes the CI Model as `.gitlab-ci.yml`
- `gha-to-gitlab.report`: renders the Migration Report
- `gha-to-gitlab.gap`: makes every Gap from its location and text, so all Gaps name their place the same way (ADR-0006)
- `gha-to-gitlab.wording`: small text helpers shared by the conversion (Source values as short text, ordinals, shell quoting)
- `gha-to-gitlab.migration`: one Migration Run, end to end: `convert` turns Workflow text and a Migration Config into the CI Model and output texts with no file access; `run!` reads the clone and writes the files around it
- `gha-to-gitlab.cli`: command-line entry point

## License

MIT. See `LICENSE`.

---

Not affiliated with or endorsed by GitHub, Inc. or GitLab Inc.
