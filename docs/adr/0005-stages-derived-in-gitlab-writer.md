# Stages are derived in the GitLab writer from the Jobs' needs

A CI Model Job carries only the Jobs it needs (`:needs`, Job ids in Source order); it has no Stage. The GitLab writer derives the Stages (`stage-1`, `stage-2`, ...) from each Job's level in the dependency order (`ci-model/dependency-levels`), and the Migration Report asks the writer (`gitlab/job-stages`) which Stage each Job runs in. Dependencies are a platform-neutral idea that belongs in the CI Model (ADR-0001), while Stages are a GitLab grouping that GitHub has no equivalent for, and they follow from `:needs` with no choices or Gaps of their own.

The dependency order itself lives in the CI Model: `ci-model/validate!` refuses Jobs that cannot be ordered (a cycle, or a need on a missing Job), so neither the Source reader nor the GitLab writer keeps its own copy. A `needs:` on a Job the Workflow does not define is a Gap raised by the Source reader, which then leaves it out of `:needs`.

When no Job has `:needs`, the writer writes no `stages`: GitLab's default Stage already runs every Job together.

## Considered Options

- **Store each Job's Stage on the CI Model Job**, as ADR-0002 does for the Runner Mapping's image: rejected; unlike a Runner Mapping, deriving Stages is a pure function of `:needs` that cannot fail or be configured, so re-deriving it in the Migration Report repeats no decision, and storing it would put a GitLab-only value on the CI Model for no gain.
