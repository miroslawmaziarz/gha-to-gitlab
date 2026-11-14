# Runner settings live on the CI Model Job

A CI Model Job carries the Source's runner label (`:runs-on`) and the image and runner tags (`:image`, `:tags`) that the Runner Mapping chose for it, even though ADR-0001 calls the CI Model platform-independent. The Runner Mapping runs as its own step on the CI Model, between reading the Source and writing the Target, so later steps can still change a Job's image and the Migration Report can show which Runner Mapping each Job used. The trade-off is that the CI Model now holds container-image and runner-tag concepts and one Source-shaped value (`runs-on`); a future Target without images or tags would have to ignore or translate them.

Job settings that Action Translators ask for (for example a setup Action such as `actions/setup-node` choosing a `node:` image) are such a later step. The Source reader settles them per Job into `:action-job-settings` (the earliest Action wins; a conflicting later Action is a Gap), and a separate step applies them once, after the Runner Mapping, so they replace the Runner Mapping's image. The image they replace stays on the Job as `:runner-mapping-image` for the Migration Report.

## Considered Options

- **Apply the Runner Mapping inside the GitLab writer**: rejected; the image would be decided too late for Action Translators to override it, and the Migration Report could not see the result without re-running the mapping.
- **A separate side table of runner settings keyed by Job id**: rejected; every step would have to keep two structures in sync for no gain today.
