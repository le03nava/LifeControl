# PR Type Labels

Read this before opening a pull request, before applying or creating a `type:*` label, and before
recording which label a merged PR carried.

## The rule

Every pull request carries **exactly one** `type:*` label, taken from the labels that already exist on
the target repository. The vocabulary originates in the machine-installed `branch-pr` skill —
`gentle-ai-branch-pr`, Critical Rule 2, *"Every PR MUST have exactly one `type:*` label"*. This
repository mirrors it in `.github/label-vocabulary.txt`, the one machine-readable source: the guard
reads that file, this document's vocabulary table and the PR template carry the same six names, and
`vocabulary-source.test.sh` fails when the three disagree. The skill is refreshed by `gentle-ai sync`
on each developer's machine, so this file also serves the contributor who runs no gentle-ai skill.

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
| 6 | A label is metadata, never evidence of what the diff does. | The guard reads labels, but it can only see names: it cannot see whether the diff deserves the name it carries. The diff, the commit prefix and the linked issue carry the proof. |

## How to apply

```bash
gh label list --limit 200 | grep '^type:'                              # inventory
gh pr create --label type:docs --title "docs(odd): ..." --body "..."   # at creation
gh pr edit 360 --add-label type:docs                                      # retrofit a missing type
gh pr edit 339 --remove-label type:chore --add-label type:docs           # correct a wrong one
gh pr view 360 --json labels --jq '[.labels[].name]'                   # read back
```

The PR template carries the same six options as checkboxes, each naming its label, so the pair is decided
once and in one place. The label is still applied separately: the guard reads labels, not checkboxes.

> The retrofit commands above touch `type:*` names only. The triage vocabulary (`documentation`, `bug`,
> `enhancement`, `java`, `dependencies`) stays where it is: on a merged PR it records how the change was
> triaged at the time, and removing it would destroy information to make a filter look tidy.

Every `type:*` label shares one colour (`#bfdadc`) so the namespace reads as one family at a glance.

## Enforcement

Four pieces, added 2026-10-17, close the gaps this file used to carry:

| Piece | Path | What it does |
| --- | --- | --- |
| Vocabulary | `.github/label-vocabulary.txt` | The one machine-readable source: the six names, one per line. The guard reads it, and `vocabulary-source.test.sh` freezes it against the template and this file's table. |
| Template | `.github/PULL_REQUEST_TEMPLATE.md` | Carries the six checkboxes, each paired with its label, plus the linked issue, summary, changes table and test plan. It is the in-repo widget the `branch-pr` skill's "check exactly ONE in the template" step needs. |
| Guard | `.github/workflows/pr-label-guard.yml` -> `.github/scripts/check-pr-type-labels.sh` | Fails the pull request when the label set — read from the pull request itself when the job runs, never from the triggering event's snapshot — has zero `type:*` labels, more than one, or one whose name is not in the vocabulary. |
| Planner | `.github/scripts/retro-label-prs.sh` | Applies the commit-type mapping to an existing history, dry-run by default and idempotent. |

The four suites under `.github/scripts/tests/` run inside the guard workflow, all offline:
`check-pr-type-labels.test.sh` (the guard's own failure modes — zero labels, two labels, a synonym,
an unreadable or malformed set), `vocabulary-source.test.sh` (the source file, the template and this
file's table declare the same six names, and a missing or empty source fails closed),
`retro-label-prs.test.sh` (the whole mapping, the `!` marker, the fail-closed case and idempotency)
and `label-artifacts.test.sh` (the template offers exactly the vocabulary the guard accepts, every
Dependabot `updates[]` entry carries `dependencies` and one `type:*`, and the workflow keeps no
`paths:` filter, reads the label set from the pull request rather than from the event payload, and
declares the read-only scope that read needs).

The workflow carries **no `paths:` filter**, and that is the point: every other workflow here filters
by component, so a markdown-only pull request runs no job at all — which is precisely the pull request
where this rule is easiest to forget. `docs` is the second largest prefix in the history (80 of 341
merged PRs), so a filtered guard would have been blind to 23% of the volume.

Running the planner on 2026-10-17 reconciled the history that predates the rule: of **341 merged pull requests**,
**307 carried no `type:*` label** and **21 carried one that named a type the diff was not** (only 13 were correct).
It planned and applied **328 mutations** — 307 additions and 21 corrections — and a second run plans **zero**,
which is the idempotency the tests assert offline. The one title carrying `!` (`refactor(product)!`, #134) took
`type:breaking-change`: the `!` is the conventional-commits breaking marker whatever the type, and the skill's table
(enumerating only `feat!`/`fix!`) is a subset of that rule, not a different one.

## Anti-patterns

| Anti-pattern | Consequence |
| --- | --- |
| Applying two `type:*` labels because the PR does two things | Split the PR instead: the label answers one question, and the review budget is per unit of behaviour anyway. |
| Applying `type:chore` to a documentation-only PR | The label's own description misdescribes the diff, and a reader filtering by `type:chore` gets a docs record. This is the historical failure above, repeated. |
| Creating `type:feat` or `type:fix` to avoid the `style`/`test`/`build` fold into `type:chore` | Invents a second vocabulary beside the tool's mapping, so the skill can no longer satisfy its own rule. |
| Keeping `documentation` as the answer to the mandate, now that `type:docs` exists | One change type lands under two labels across PRs and the filter silently lies. The historical triage label on an already-merged PR is a different thing: it stays, beside the `type:*` label. |
| Adding `size:exception` to exempt an oversized PR | Asserts an acceptance gate this repository never declared. |
| Treating the label as evidence that a change is documentation-only | Labels are unverified metadata; the diff and the commit prefix are the evidence. |
| Ticking the template's checkbox and skipping the label | The checkbox is a declaration for the human reader; the guard reads labels, so the check still fails. |

## Gaps

- The guard is **not a required check**: it reports on every pull request, but nothing forces a merge to wait
  for a green verdict until the branch protection rules name it. Its verdict can be ignored.
- A pull request opened through the API or the CLI does not necessarily use the template: the label guard covers
  the label, not the body, so the issue link and the contributor checklist remain review-time conventions.
- The vocabulary has one machine-readable source, `.github/label-vocabulary.txt`, which the guard reads
  instead of an inline string. The `branch-pr` skill still declares the vocabulary independently, and this file
  and the PR template repeat it for a human reader, so a seventh `type:*` label must still be added to the source
  and to both documentation artifacts in the same commit. `vocabulary-source.test.sh` freezes the source, the
  template and this file's table together so the drift is caught offline; the skill's copy stays out of reach
  because it lives outside the repository.
- A contributor who runs no gentle-ai skill still has to find this file; the template links to it, which is one
  hop closer than before.
- Dependabot's pull requests satisfy the gate at open time: every `updates[]` entry in
  `.github/dependabot.yml` declares `labels:` with `dependencies` and `type:chore`, and
  `label-artifacts.test.sh` parses the file and fails on a missing, doubled or off-vocabulary label.
  Dependabot applies those labels itself a beat **after** `opened`, which is why the guard reads the
  pull request's current labels instead of the event's snapshot: on 2026-10-17 pull request #392
  carried `type:chore` and failed the check on an empty snapshot, and the failure was permanent
  because a re-run replays that snapshot. Auto-labelling a hand-authored PR is still out of scope: it
  would need a token with `issues: write`, and the gate's token is read-only.
