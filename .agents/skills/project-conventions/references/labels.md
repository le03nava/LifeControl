# PR Type Labels

Read this before opening a pull request, before applying or creating a `type:*` label, and before
recording which label a merged PR carried.

## The rule

Every pull request carries **exactly one** `type:*` label, taken from the labels that already exist on
the target repository. The vocabulary is declared by the machine-installed `branch-pr` skill —
`gentle-ai-branch-pr`, Critical Rule 2, *"Every PR MUST have exactly one `type:*` label"* — not by this
repository. That skill is refreshed by `gentle-ai sync` on each developer's machine, so this file is
both the mirror for a contributor who runs no gentle-ai skill and the copy the repository can version.

Section 2 of that skill's PR body pairs each checkbox with the label to apply:

| Checkbox in the PR body | Label |
| --- | --- |
| Bug fix | `type:bug` |
| New feature | `type:feature` |
| Documentation only | `type:docs` |
| Code refactoring | `type:refactor` |
| Maintenance/tooling | `type:chore` |
| Breaking change | `type:breaking-change` |

Its commit-type mapping is what a `feat`/`fix`/`docs`-prefixed history makes actionable:

| Commit type | Label |
| --- | --- |
| `feat`, `perf` | `type:feature` |
| `fix`, `revert` | `type:bug` |
| `docs` | `type:docs` |
| `refactor` | `type:refactor` |
| `chore`, `style`, `test`, `build`, `ci` | `type:chore` |
| `feat!`, `fix!` | `type:breaking-change` |

The skill states its own limit: the mapping is *"a suggestion, not proof of label availability or
permission"*. A label the mandate names and the repository lacks cannot be applied honestly — which is
precisely the failure this file records.

## The failure mode

The `type:*` namespace entered this repository at PR #325 (2026-10-15) with **exactly one member**:
`type:chore`. The mandate to apply one `type:*` label came from the tooling; the vocabulary lived in the
repository; neither side could see the other. Every PR that was not a chore then had three exits, and
all three were taken:

| Exit | Cost |
| --- | --- |
| **No label at all** — 62 of the last 120 PRs | The mandate says exactly one. Zero is the same violation as two, and an unlabelled PR answers no filter. |
| **The older GitHub-default vocabulary** (`enhancement`, `documentation`, `bug`, `java`) — 52 PRs | Accurate where it named a type: `documentation` on 16 docs PRs and `bug` on 5 fix PRs was never wrong. But it is the issue-triage namespace, it predates the mandate, and it covers only part of the volume. |
| **`type:chore`, for lack of any other `type:*` label** — 5 of its 8 uses | The label inverted its meaning: it came to read *"no other `type:*` existed"*. |

Measured over the 120 PRs closed before this file was written (2026-10-17), by each PR's own
conventional-commit prefix against the labels it actually carries:

| Prefix | PRs | unlabelled | `type:*` | GitHub defaults | `dependencies` |
| --- | --- | --- | --- | --- | --- |
| `feat` | 49 | 27 | `type:chore` 1 | `enhancement` 4, `enhancement,java` 17 | — |
| `docs` | 46 | 25 | `type:chore` 3 | `documentation` 16, `enhancement` 2 | — |
| `chore` | 10 | 2 | `type:chore` 1, `java,type:chore` 2 | — | `dependencies,java` 5 |
| `fix` | 10 | 5 | — | `bug` 4, `bug,java` 1 | — |
| `refactor` | 5 | 3 | `type:chore` 1 | `enhancement,java` 1 | — |

The `type:*` column is the defect in one column: **5 of its 8 uses name a type the diff is not** — three
`docs`, one `feat`, one `refactor`. A one-member namespace cannot answer a six-valued question, and
forcing it produced a label that lied more often than it told the truth. The failure is not in the
agent that applied it: applying `type:chore` was the only way to satisfy the mandate at all.

The PRs immediately before this file show the conflict being re-litigated one PR at a time:

| PRs | Resolution | Why it is the wrong shape |
| --- | --- | --- |
| #355, #356 | no label | The mandate's own violation, taken twice in a row. |
| #360 | `documentation` | Honest, and precedented by the 16 docs PRs above — but it is the unmandated namespace, so the next docs PR decides again and two docs PRs may land under two labels for one change type. |

Quoted verbatim from #360's review thread:

> Un detalle que vale para el label: el repo no tiene type:docs — entre los type:* sólo existe
> type:chore, y los PRs de docs previos (#355, #356) no llevaban label. Le puse documentation,
> que existe en el repo y dice exactamente lo que es, en vez de forzar un type:chore que
> mentiría sobre el contenido.

Refusing a label that misdescribes the diff was the right instinct, and it is the reason the defect
became visible instead of silent. But the fix is not a per-PR judgement call: it is to mirror in the
repository the vocabulary the tooling mandates, which is what this change did.

## The invariant

| # | Rule | Why |
| --- | --- | --- |
| 1 | Exactly one `type:*` label per PR: never zero, never two. | The mandate is a single-valued decision; two labels answer it twice, zero leaves it unanswered. Area labels (`java`) are orthogonal and may accompany it. |
| 2 | Use the names this file declares, never a synonym (`type:feat`, `type:fix`, `type:doc`). | The mapping lives in the tooling. A synonym looks compliant while the tool's own mapping points at a label that does not exist. |
| 3 | The GitHub defaults (`bug`, `enhancement`, `documentation`, `duplicate`, `invalid`, `question`, `wontfix`, `good first issue`, `help wanted`) stay issue-triage vocabulary. | `bug`/`enhancement` are this repository's issue labels; `type:bug`/`type:feature` are its PR labels. One meaning per namespace, so no PR has to choose between two labels that both fit. |
| 4 | The linked issue carries `status:approved` before the PR is opened. | `status:approved` is the issue-side gate; the skill requires the approval, not merely the link. |
| 5 | `size:exception` is a Gentle-owned repository policy and is **not** this repository's. Never create it, never apply it. | No size policy is documented here, so the label would assert an acceptance gate nobody declared. |
| 6 | A label is review-time metadata, never evidence. | No workflow in this repository reads labels: all four pipelines trigger on `paths:` filters scoped to their own component. The diff, the commit prefix and the linked issue carry the proof. |

## How to apply

```bash
gh label list --limit 200 | grep '^type:'                              # inventory
gh pr create --label type:docs --title "docs(odd): ..." --body "..."   # at creation
gh pr edit 360 --add-label type:docs --remove-label documentation      # retrofit
gh pr view 360 --json labels --jq '[.labels[].name]'                   # read back
```

Every `type:*` label shares one colour (`#bfdadc`) so the namespace reads as one family at a glance.

## Anti-patterns

| Anti-pattern | Consequence |
| --- | --- |
| Applying two `type:*` labels because the PR does two things | Split the PR instead: the label answers one question, and the review budget is per unit of behaviour anyway. |
| Applying `type:chore` to a documentation-only PR | The label's own description misdescribes the diff, and a reader filtering by `type:chore` gets a docs record. This is the historical failure above, repeated. |
| Creating `type:feat` or `type:fix` to avoid the `style`/`test`/`build` fold into `type:chore` | Invents a second vocabulary beside the tool's mapping, so the skill can no longer satisfy its own rule. |
| Keeping `documentation` on a PR now that `type:docs` exists | One change type lands under two labels across PRs and the filter silently lies. |
| Adding `size:exception` to exempt an oversized PR | Asserts an acceptance gate this repository never declared. |
| Treating the label as evidence that a change is documentation-only | Labels are unverified metadata; the diff and the commit prefix are the evidence. |

## Gaps

- No workflow validates that a PR carries exactly one `type:*` label: the rule is review-time only.
- `.github/PULL_REQUEST_TEMPLATE.md` is absent, so the skill's "check exactly ONE in the template" step
  has no in-repo widget; a PR body is written without the checkbox block the label pairs with.
- A contributor who runs no gentle-ai skill has no way to discover the mandate from the repository
  alone: this file is the only local copy, and `gentle-ai sync` refreshes the skill independently of it.
