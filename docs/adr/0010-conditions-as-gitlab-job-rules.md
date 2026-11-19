# A Job's `if:` becomes GitLab Job rules, inherited through `needs:`

A Job's `if:` becomes its GitLab `rules:` on the CI Model (`:rules`, GitLab's shape, like ADR-0004's Job settings), built in the Source reader by `gha-to-gitlab.conditions`, where a Gap can still quote the expression. A small parser reads the expression (`${{ }}` or bare; strings, `==`, `!=`, `&&`, `||`, `!`, parentheses, calls), and a condition converts when it only compares:

- `github.ref` with `refs/heads/X` → `$CI_COMMIT_BRANCH`, with `refs/tags/X` → `$CI_COMMIT_TAG`;
- `github.ref_name`, `head_ref`, `base_ref` → `$CI_COMMIT_REF_NAME`, `$CI_MERGE_REQUEST_SOURCE_BRANCH_NAME`, `$CI_MERGE_REQUEST_TARGET_BRANCH_NAME`;
- `github.ref_type` → whether `$CI_COMMIT_TAG` or `$CI_COMMIT_BRANCH` is set;
- `github.event_name` with a Trigger → `$CI_PIPELINE_SOURCE` with the Pipeline source ADR-0008 gives it;
- `startsWith(` one of those `, 'prefix')` → an anchored regex (`=~ /^prefix/`), or a set tag or branch for `refs/tags/`, `refs/heads/`.

GitLab rules have no `!`, so a negation is pushed into the comparisons (`==` ↔ `!=`, `=~` ↔ `!~`, `&&` ↔ `||`). GitHub compares strings ignoring case and GitLab does not; branch and Trigger names rarely differ by case, so this is left as a known difference.

Status functions beside the whole condition (`always() && …`) become the rule's `when`: `always()` and `!cancelled()` → `always`, `failure()` → `on_failure`, `success()` → nothing. `if: false` → `when: never`. A status function anywhere else, `cancelled()`, other functions, other contexts (`needs.*.outputs`, `env`, `matrix`) and `<`/`>` are a Gap at the Job quoting the expression; the Job then runs whenever the Pipeline does.

GitHub skips a Job when it skips a Job it needs, and GitLab refuses to start a Pipeline whose Job needs a Job its rules left out. So a Job's rule is its own condition `&&` the conditions of the Jobs it needs (transitively, each once), and a Job left out by `false` leaves its dependents out too. An `always()` Job runs on GitHub even when those Jobs are skipped, so it inherits nothing, and its needs on conditioned Jobs become `optional: true` (`:optional-needs`). A Job that needs a Job whose `if:` is a Gap gets a Gap too.

A Step's `if:` cannot become a rule, as GitLab has no Step. An `actions/upload-artifact` Step with only `always()` or `failure()` keeps the Job's artifacts `when: always` / `on_failure` (#17); `success()` changes nothing; any other Step `if:` is a Gap at the Step.

## Considered Options

- **`needs: optional: true` for every need on a conditioned Job, plus a Gap**: rejected; the dependent would run when GitHub skips it.
- **Converting functions such as `contains()`**: rejected for now; GitLab rule regexes could say some of them, but each needs its own escaping and case rules.
- **Step `if:` as shell `if` around the Step's lines**: rejected for now; the expression would need evaluating in the shell, as with ADR-0007's per-Step `env:`.
