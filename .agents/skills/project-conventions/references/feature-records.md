# ODD Feature Records

Read this before creating or updating a record under `odd/tasks/`, and before writing any
`**Status**:` line.

## The failure mode

A record's header is written **before** the merge it describes, and nothing revisits the file
afterwards. A header that asserts a *live* delivery state — "Not pushed; no PR is open" — is
therefore falsified by the next merge, silently, and by a commit that never touches the file that
made the claim.

This repository has reproduced it in three consecutive generations:

| Where | What happened |
| --- | --- |
| PR #162 (`odd-status-reconciliation`) | Repaired **14** headers that asserted a live or pending delivery state. Three of them had to be amended again **inside the same slice**, because the slice's own prescribed replacements still carried "No PR is open" (its decision D7). |
| PRs #164–#167 | The very next cycle wrote **four** fresh headers with "Not pushed; no PR is open" — the exact claim the previous slice had just deleted from 14 others. The last two PRs of that same cycle (#168, #169) wrote neutral headers on their own initiative, with no rule telling them to. |
| The repair in flight when this was written | Removes those four claims, plus body lines from earlier slices that had drifted the same way. This table deliberately does not assert that repair's state, or its number. |

The pattern is structural, not careless: the header is written while the delivery state is still
*unknown* (pre-merge) and is never revisited when it becomes *known* (post-merge). Removing the
claim is cheaper than scheduling a revisit, and the revisit has already failed twice.

## The invariant

| # | Rule | Why |
| --- | --- | --- |
| 1 | The `**Status**:` line states **durable state plus its evidence** (PR number, merge commit, date) or **what remains** (deferred / blocked, with the reason). | Both halves stay true: a merge commit and a deferral decision do not change when the branch is deleted. |
| 2 | It **never** asserts a live or pending delivery state. | A live claim is falsified by the next merge — silently, with no commit touching the file that made it. |
| 3 | Delivery detail belongs in the record's **dated evidence log** (`## Evidence log`, task log), not in the header. | A dated row with a commit hash stays true forever and is checkable with one command. |
| 4 | Records written before this rule are **not rewritten**. | Their frozen narrative is itself evidence; rewriting it destroys the audit trail of the drift. Repair only claims that are false *now*, and record the repair. |

## Header shape

A record whose work has landed:

```markdown
**Status**: merged — PR #164 (`refactor/breakpoint-vars` @ `4469cf717`), 2026-09-24. <what remains, or "No work left.">
**Created**: 2026-09-24 · **Risk**: <level> — <why>
```

A record whose work is still open, or whose delivery state is not yet known:

```markdown
**Status**: implemented — <what remains>. This header makes no claim about push or PR state; see the evidence log.
```

| Permitted in the header | Forbidden in the header |
| --- | --- |
| `merged — PR #<n> (<branch> @ <merge-commit>), <date>` | `not pushed` / `unpushed` / `Not pushed yet` |
| `No work left` | `no PR is open` / `PR is open` / `Nothing is pushed and no PR is open` |
| `deferred` / `blocked on <external decision>` | `in progress` / `awaiting review` / `pending review` |
| `nothing open in this slice; the follow-ups are separate work` | `not merged yet` / `yet to land` |
| `<what remains>`, with the reason | a PR number written before the PR exists |

## Anti-patterns

| Anti-pattern | Consequence |
| --- | --- |
| Writing the live state because it is true *right now* | It becomes false at the merge, and the file is never touched again |
| Weakening the claim instead of moving it ("probably merged by now") | Worse than the original: now it is unverifiable too |
| Keeping the delivery detail in the header *and* the log | Two sources of truth, and the header is the one nobody revisits |
| Writing a PR number before the PR is opened | Invents an identifier that may never exist |
| Rewriting an old record's frozen narrative to match this rule | Destroys the evidence that the drift existed |
| Repairing a stale header without recording the repair | Repeats the repair next cycle instead of closing it |

## Gaps

- **No automated guard enforces this**, and that is a deliberate choice, not an oversight: the rule
  removes the field that could go stale rather than watching it. A guard would need a stated
  allowlist for the records that legitimately contain the forbidden phrases as quotations — the
  audit record that documents the drift, and the frozen narrative that
  `purchase-order-goods-receipt.md` D4 protects — and it would fire after the merge instead of
  before it.
- **No CI workflow inspects `odd/**` or any markdown.** Each workflow's `paths:` filter is nested
  under `pull_request` and scoped to its own component, so a records-only change runs no automated
  check at all; the four workflows only run on `push` to `main`.
- **Nothing updates the header at merge time.** The merge is a manual `gh pr merge`; no step hooks
  it. Design the header so that it does not need updating.
