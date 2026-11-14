# Converted Actions live on the CI Model Job

A CI Model Job carries the Converted Actions of its Steps (`:actions`: Step, `uses:` value, the Action Translator's outcome, its Gap count and `:sets`, the names of the Job settings it supplied that the Job kept), so the Migration Report can list per Job what each Action became. This is report provenance, not CI behaviour, and the Target writer ignores it: the values of an Action's Job settings live on the Job (`:action-job-settings`, ADR-0002), never on the Converted Action. Like ADR-0002, it puts a Source-shaped value on the CI Model, and a future Target writer would ignore it the same way.

## Considered Options

- **Derive the list in the Migration Report from Gaps or by re-running the Action Translators**: rejected; Gaps only cover what failed, and re-running translators repeats the conversion outside the CI Model (against ADR-0001).
- **A separate side table keyed by Job id**: rejected for the same reason as in ADR-0002; two structures to keep in sync for no gain.
