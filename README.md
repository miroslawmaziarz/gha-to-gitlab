# gha-to-gitlab

Convert GitHub Actions workflows and repository settings to GitLab CI, with a report of everything that needs a human. Written in Clojure.

> **Status:** early design, no working code yet. See `GLOSSARY.md` for the domain language and `docs/adr/` for key decisions.

## What it does

- Reads GitHub Actions workflows (and later, repository settings) from a local clone or the GitHub API.
- Converts them through a platform-neutral CI Model into a `.gitlab-ci.yml`.
- Writes a Migration Report listing every Gap: anything it could not convert faithfully, such as unknown Actions or Secret values (which GitHub never exposes).

Repositories, issues and pull requests are out of scope; GitLab's built-in GitHub importer already handles those.

## License

MIT. See `LICENSE`.

---

Not affiliated with or endorsed by GitHub, Inc. or GitLab Inc.
