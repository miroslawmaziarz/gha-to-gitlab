# GitLab-shaped cache, artifacts and Variables on the CI Model Job

A CI Model Job carries its caches (`:cache`), artifacts (`:artifacts`) and Variables (`:variables`) in GitLab's shape, even though ADR-0001 calls the CI Model platform-independent. The Action Translators for `actions/cache`, `actions/upload-artifact` and the setup Actions' `cache` input write them, and the GitLab writer only renames keys (`:expire-in` → `expire_in`) and orders them. This follows ADR-0002, which already put images and runner tags on the Job.

What is GitLab-shaped:

- a cache `:policy` (`pull`, `push`) and `:when` (`always`);
- a cache key that is either a string or `{:files [..] :prefix ..}`, GitLab's `cache:key:files`;
- the limits GitLab sets: at most 4 caches per Job (`action-job-settings/list-settings`) and at most 10 files per cache key (`ci-model/cache-key-files-limit`, current GitLab's limit);
- artifacts `:exclude` and `:expire-in` as GitLab's duration text (`"5 days"`);
- one artifacts block per Job, so a second upload in a Job is a Gap instead of a second entry.

The Source reader settles these per Job like any other Action Job setting (ADR-0002): `:cache` collects every Action's entries up to the limit, `:variables` combines them with a clashing Variable as a Gap, and `:artifacts` keeps the earliest Action's value.

The trade-off: checking the limits while converting Actions lets each Gap name the Step that went over, but a future Target with different limits or no cache policy would need its own values or a translation step.

## Considered Options

- **Platform-neutral cache and artifact values, translated in the GitLab writer**: rejected; the writer would have to raise Gaps (over the limit, a second artifacts block), and today Gaps come only from the Source reader and the Runner Mapping, where the Step is still known.
- **Limits checked only in the GitLab writer**: rejected for the same reason; a Gap there could not name the Step that went over.
