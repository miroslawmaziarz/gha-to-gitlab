# Convert through a neutral CI Model

Every Migration Run converts the Source into the platform-independent CI Model first, and only then into the Target format; there is no direct GitHub-to-GitLab conversion. A direct conversion would be less code today, but the CI Model lets each half (Source → CI Model, CI Model → Target) be tested in isolation, gives Gaps a single place to be recorded, and means adding another Target later is one new converter rather than a rewrite.

## Considered Options

- **Direct GitHub → GitLab conversion**: rejected; couples both platforms' quirks in every function and would have to be rewritten for any additional Target.
