## Code checks

`bin/check` (cljfmt, clj-kondo, Splint, tests) must pass before a commit; the project's Claude Code hooks run it for you. When a check flags code, change the code; loosen `.clj-kondo/config.edn` or `.splint.edn` only for a rule that misreads this code base, with a comment saying why.

## Agent skills

### Issue tracker

Issues are tracked in this repo's GitHub Issues, managed with the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Uses the five default triage labels (needs-triage, needs-info, ready-for-agent, ready-for-human, wontfix). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `GLOSSARY.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.
