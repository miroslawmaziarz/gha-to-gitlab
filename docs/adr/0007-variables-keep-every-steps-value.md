# Variables keep every Step's value, as GitHub reads it

`env:` becomes Variables at the matching level: a Workflow's become the Pipeline's `variables:` and a Job's the Job's. GitLab has no Step, and a Job's Variables reach all its script lines, so a Step's `env:` entry is lifted into its Job only when that changes no Step's value. That means the Job inherits the name with the same value, or every Step in the Job (Actions included) sets it to the same value. Any other entry is a Gap at its Step, which names the Job's or Workflow's value, a Step with another value, or a Step that does not set the name. This rule is stricter than "no other Step sets it differently", because a Step that does not set a name would otherwise start seeing it, and tools read Variables they were never told about (`CI`, `NODE_ENV`, tokens).

Values are the text GitHub reads, which is not what the YAML library gives back. clj-yaml reads YAML 1.1 (`010` is 8, `yes` is true, `2024-01-01` a date), and GitHub reads YAML 1.2's core schema. So the Source reader reads `env:` a second time with positions, keeps quoted values as they are, and resolves each unquoted one from its source text (`variables/plain-scalar-text`): numbers in canonical form (`3.10` → `3.1`), booleans as `true`/`false`, everything else as written.

GitHub's `env:` values are literal, but GitLab expands Variables inside Variable values. So every `$` in a value is escaped as `$$`, except the references the tool writes: `${{ env.X }}` and `${{ secrets.X }}` become `${X}` in values and in commands. Each referenced Secret is one Gap at its Workflow (location key `:secret`), naming the Jobs that use it, so a person creates it once on the Target as a masked Variable.

A Variable an Action asks for (such as setup-node's npm cache directory) never overrides `env:`. A contradicting one is left out with a Gap at its Step (`action-job-settings/settle` takes the Job's `env:` Variables), so the Source's own values win, as they do on GitHub.

## Considered Options

- **Lift a Step's entry unless another Step sets it differently**: rejected; Steps that do not set the name would see it, which can change what they do.
- **Wrap a Step's script lines in `export`/`unset`**: rejected for now; script lines share one shell, and restoring a value the Job inherits needs more than `unset`. A Gap is honest until a Step's lines are emitted as their own block.
- **`expand: false` on Variables containing `$`**: rejected; a value can mix a literal `$` with a rewritten reference, which needs expansion. `$$` handles both.
- **Not converted here**: references inside larger expressions (`${{ secrets.A || secrets.B }}`) and in Action `with:` inputs are left to the expression work (#9).
