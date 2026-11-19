# Triggers become GitLab workflow rules on the CI Model

A Workflow's Triggers (`on:`) become GitLab workflow rules, which the CI Model holds in GitLab's shape (`:workflow-rules`, `[{:if .. :when "never"}]`) like ADR-0004's Job settings. `gha-to-gitlab.triggers` builds them in the Source reader, where a Gap can still name the Trigger and its filter (location key `:trigger`). The GitLab writer only prints them under `workflow: rules:`.

How the Triggers map:

- `push` → `$CI_PIPELINE_SOURCE == "push"`, and `pull_request` → `"merge_request_event"`. Branch filters test `$CI_COMMIT_BRANCH` (for `pull_request`, the target branch `$CI_MERGE_REQUEST_TARGET_BRANCH_NAME`), and tag filters test `$CI_COMMIT_TAG`. As on GitHub, filtering only branches means tag pushes start nothing, and the other way round.
- GitHub lets the last matching pattern decide, while GitLab stops at the first matching rule. So a pattern list becomes rules in reverse order, and `!` patterns and `*-ignore` lists become `when: never` rules.
- Globs become anchored regexes (`triggers/glob->regex`): `*` → `[^/]*`, `**` → `.*`, while `?`, `+` and `[..]` keep their meaning. A glob with no regex form, or whose GitHub meaning is unsure (`[^..]`, `/` in `[..]`), is left out with a Gap. One pattern written alone counts as a list of it.
- Filters are read as the text GitHub reads (YAML 1.2), as ADR-0007 reads `env:`, so `yes` and `2020-01-01` stay as written.
- `schedule` and `workflow_dispatch` get a rule (`"schedule"`, `"web"`) and a Gap. The person creates the Pipeline Schedule or uses Run pipeline, and the rules already let those Pipelines start. A `schedule` that lists no `cron` still gets its rule, with one Gap.
- Path filters, `pull_request` `types` other than the defaults, and every other Trigger are Gaps.
- When `on:` has Triggers but none becomes a rule, the rules are `[{when: never}]`. Without them, GitLab would start a Pipeline for every push, merge request and schedule.

## Considered Options

- **Platform-neutral Triggers on the CI Model, turned into rules by the GitLab writer**: rejected for the same reason as ADR-0004: the writer would have to raise Gaps (inexpressible globs), and only the Source reader knows the Trigger.
- **Path filters as `rules: changes`**: deferred. Workflow-level `changes` behaves differently for new branches and tags, so it needs its own design.
- **Workflow rules with several Workflows**: one `workflow: rules:` serves the whole Pipeline. #10 will need per-Workflow Job rules once Workflows with different Triggers share a Pipeline.
