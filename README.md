# gha-to-gitlab

Convert GitHub Actions workflows and repository settings to GitLab CI, with a report of everything that needs a human. Written in Clojure.

> **Status:** walking skeleton. It converts one Workflow whose Jobs contain only `run:` Steps. See `GLOSSARY.md` for the domain language and `docs/adr/` for key decisions.

## What it does

- Reads GitHub Actions workflows (and later, repository settings) from a local clone or the GitHub API.
- Converts them through a platform-neutral CI Model into a `.gitlab-ci.yml`.
- Writes a Migration Report listing every Gap: anything it could not convert faithfully, such as unknown Actions or Secret values (which GitHub never exposes).

Repositories, issues and pull requests are out of scope; GitLab's built-in GitHub importer already handles those.

## Requirements

- Java 11 or newer
- [Clojure CLI](https://clojure.org/guides/install_clojure) 1.12+

## Usage

Point the tool at a local clone of the Source repository and an output directory:

```sh
clojure -M:run path/to/github-clone path/to/output
```

This performs one Migration Run and writes two files into the output directory:

- `.gitlab-ci.yml`: one GitLab Job per GitHub Job, with each `run:` Step as a script line.
- `MIGRATION_REPORT.md`: what was converted, and every Gap that needs a person.

Try it against the bundled fixture:

```sh
clojure -M:run test/fixtures/plain-workflow /tmp/migration-out
```

Current limits: the clone must contain exactly one Workflow under `.github/workflows/`, and every Step must be a `run:` Step.

A GitHub Job whose id is a GitLab global keyword (such as `image`, `variables` or `workflow`) is renamed with a `_job` suffix, and the Migration Report lists the rename.

## Development

```sh
clojure -M:test   # run all tests
clojure -M:lint   # lint with clj-kondo
```

Conversion always goes Source → CI Model → Target (ADR-0001):

- `gha-to-gitlab.github`: reads a Workflow into the CI Model
- `gha-to-gitlab.ci-model`: the CI Model's Malli schema
- `gha-to-gitlab.gitlab`: writes the CI Model as `.gitlab-ci.yml`
- `gha-to-gitlab.report`: renders the Migration Report
- `gha-to-gitlab.migration`: one Migration Run, end to end
- `gha-to-gitlab.cli`: command-line entry point

## License

MIT. See `LICENSE`.

---

Not affiliated with or endorsed by GitHub, Inc. or GitLab Inc.
