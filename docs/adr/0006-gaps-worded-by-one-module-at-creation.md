# Gaps are worded by one module when they are raised

Every module that raises a Gap gives `gha-to-gitlab.gap/at` the Gap's location (Workflow, Job, Step, Action, input) and the text of what went wrong, and that one module writes the message, so every Gap names its place the same way ("Workflow `ci.yml`, Job `build`, Step `Node`: Action `actions/setup-node@v4` …"). The Gap keeps its location keys beside the message, for grouping and de-duplicating on structure. The message is written when the Gap is raised, not when the Migration Report is rendered: the Gap module also owns the placeholder script line that points to `MIGRATION_REPORT.md`, so it depends on the report module, and the report only prints messages.

So that the Runner Mapping can name a Job's Workflow, a CI Model Job carries the path of the Workflow it comes from (`:workflow`). Like ADR-0002 and ADR-0003, this is a Source-shaped value on the CI Model; the GitLab writer ignores it.

## Considered Options

- **Gaps keep only location and text, and the Migration Report writes the sentence**: rejected for now; it needs the report's file name to move out of the report module to avoid a dependency cycle with the placeholder line, and nothing yet needs Gaps worded differently in different places.
- **Pass the Workflow path to the Runner Mapping** instead of putting it on the Job: rejected; a Migration Run with several Workflows (#10) needs each Job to know its own.
