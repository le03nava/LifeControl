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

**Still absent.**

- **Nothing updates the header at merge time.** The merge is a manual `gh pr merge`; no step hooks
  it, so the header still has to be designed so that it does not need updating. A guard can catch a
  claim that lost its evidence; it cannot write the evidence for you.

**What exists now.**

- **`odd-status-headers.test.sh` enforces the invariant over every record on the tree.**
  `.github/scripts/tests/odd-status-headers.test.sh` audits every `odd/tasks/*.md`: a record must
  carry a `**Status**:` paragraph, and a paragraph asserting the delivery-state class — `not merged`,
  `not pushed`, `what remains is the delivery`, `awaiting review`, `in progress`, `unstarted` — must
  name a **merge commit** behind a delivery marker, which the suite resolves as a commit and checks
  is an ancestor of `origin/main`. A soft phrase such as `implemented` may still rest on a bare
  `#<number>`, because it claims the work exists, not that the unit landed. The run reports
  `checked N record(s); M claimed merge commit(s); ancestry: ran`, and where `origin/main` does not
  resolve — a shallow CI checkout has no such ref — it says `ancestry: skipped` out loud instead of
  passing silently.
- **The audit reads the first `**Status**:` paragraph and nothing else.** That scoping is what
  replaces the allowlist this section used to say a guard would need: the records that legitimately
  quote the forbidden phrases (the audit record, and the frozen narrative
  `purchase-order-goods-receipt.md` D4 protects) keep those quotations outside the first paragraph,
  so no allowlist has to name them.
- **`pr-label-guard.yml` is what makes a records-only change get audited.** It carries **no
  `paths:` filter**, deliberately — the other workflows nest theirs under `pull_request` and scope
  them to their own component, so a markdown-only pull request would run zero jobs — and its `guard`
  job runs shellcheck over every `.github/scripts/**/*.sh` and then every suite under
  `.github/scripts/tests/`, this one included.
- **What the guard does not do.** It does not read past the first paragraph, so the rest of a
  record's narrative stays unverified; and it cannot decide offline whose pull request a `#<number>`
  is, which is why the delivery-state class asks for a merge commit — the narrower identity that is
  at least checkable, not proof of ownership.
