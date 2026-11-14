# GitHub-to-GitLab CI Migration

A command-line tool that moves a repository's CI/CD from GitHub Actions to GitLab CI, converting what it safely can and reporting the rest for a person to finish.

## Language

### Migration

**Migration Run**:
One run of the tool against one source repository, producing a CI Model, the target output and a Migration Report. A **dry run** produces all of these without writing anything to the Target.
_Avoid_: job, sync, import

**Source**:
The platform and repository a Migration Run reads from (currently always GitHub).
_Avoid_: origin

**Target**:
The platform and project a Migration Run writes to (currently always GitLab).
_Avoid_: sink, destination

**CI Model**:
The tool's own platform-independent description of a project's CI (Jobs, Triggers, Matrices, Variables), which every conversion passes through.
_Avoid_: IR, intermediate representation, pipeline model

**Action Translator**:
The conversion for one specific Action into Job settings or script lines in the CI Model.
_Avoid_: rule, handler, mapping

**Runner Mapping**:
The configured translation from a GitHub runner label (`runs-on`) to a GitLab image and runner tags.
_Avoid_: runs-on mapping, machine mapping

**Gap**:
One element of the Source that the Migration Run could not convert faithfully and that a person must handle (an unknown Action, a Secret value, a complex expression).
_Avoid_: TODO, warning, unsupported item

**Migration Report**:
The human-readable result of a Migration Run, listing what was converted and every Gap.
_Avoid_: log, summary, TODO list

### CI concepts

**Workflow**:
One GitHub Actions YAML file under `.github/workflows/`; the unit the tool reads from the Source.
_Avoid_: action file, pipeline (on the GitHub side)

**Pipeline**:
A GitLab CI run made of Jobs.
_Avoid_: workflow (on the GitLab side)

**Stage**:
A GitLab grouping of Jobs that run in sequence order within a Pipeline; GitHub has no equivalent, so Stages are derived from Job dependencies.
_Avoid_: phase, step

**Pipeline Schedule**:
A GitLab project setting that starts a Pipeline on a cron timetable; it is created on the Target, not written into the CI file.
_Avoid_: cron job, scheduled workflow (on the GitLab side)

**Job**:
A named unit of work that runs on one runner; exists on both platforms and maps one-to-one where possible.
_Avoid_: task, stage

**Step**:
One entry in a GitHub Job's `steps:` list, either a shell command or a use of an Action. GitLab has no equivalent; Steps become script lines or Job settings.
_Avoid_: command, script line (for the GitHub side)

**Action**:
A reusable GitHub component that a Step calls through `uses:` (for example `actions/setup-node`).
_Avoid_: plugin, "GitHub Actions" (the product name, not one Action)

**Trigger**:
An event that starts a Workflow (push, pull request, schedule, manual dispatch); on GitLab it becomes workflow rules or a Pipeline Schedule.
_Avoid_: event, `on:` (in prose)

**Matrix**:
A set of variable combinations that runs one Job once per combination.
_Avoid_: strategy, parallel jobs

### Settings and values

**Repository Settings**:
Project configuration outside Workflows that affects CI/CD (branch protection, Environments, Variables, Secrets, CODEOWNERS).
_Avoid_: "other settings", config, repo metadata

**Environment**:
A named deployment target with its own protection rules and Variables; exists on both platforms with different protection models.
_Avoid_: stage, deployment target

**Variable**:
A named configuration value whose content can be read and migrated (covers `env:`, GitHub Variables and unmasked GitLab variables).
_Avoid_: env var, setting

**Secret**:
A named value whose content cannot be read from the Source; only its name migrates, and it becomes a masked Variable on the Target plus a Gap.
_Avoid_: credential, masked variable (in prose)
