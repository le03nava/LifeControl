# ODD Feature Records

Read this before creating or updating a record under `odd/tasks/`, and before writing any
`**Status**:` line.

## The failure mode

A record's header is written **before** the merge it describes, and nothing revisits the file
afterwards. A header that asserts a *live* delivery state — "Not pushed; no PR is open" — is
therefore falsified by the next merge, silently, and by a commit that never touches the file that
made the claim.

This repository has reproduced it in four consecutive generations:

| Where | What happened |
| --- | --- |
| PR #162 (`odd-status-reconciliation`) | Repaired **14** headers that asserted a live or pending delivery state. Three of them had to be amended again **inside the same slice**, because the slice's own prescribed replacements still carried "No PR is open" (its decision D7). |
| PRs #164–#167 | The very next cycle wrote **four** fresh headers with "Not pushed; no PR is open" — the exact claim the previous slice had just deleted from 14 others. The last two PRs of that same cycle (#168, #169) wrote neutral headers on their own initiative, with no rule telling them to. |
| PR #426 → PR #436 | The remedy of that generation was a *neutral* header, `implemented — … This header makes no claim about push or PR state`. #436 measured it: with a named merge commit beside it the sentence was already false one day later, and 8 headers plus #364's and #455's 12 had to be repaired by hand. |
| PR #480 → PR #491/#492 | The remedy of *that* generation was to require a merge commit in the header — and a merge commit cannot exist inside the pull request that creates it, so every unit grew a second `docs(odd)` pull request whose only job was to move the header. Measured: **51 of the last 150 merged pull requests** (34%) touched nothing but `odd/tasks/*.md`, **40** of them rewriting exactly one record, and each one also needed its own `status:approved` issue (#434, #454, #470, #491). Eleven further sweeps (up to 10 records at a time: #364, #436, #455, #471) exist only to repair the units whose per-unit rewrite was skipped. |

The pattern is structural, not careless: the header is written while the delivery state is still
*unknown*, so the fix cannot be to write the state later. It is to **not write the state at all**.

## The invariant

| # | Rule | Why |
| --- | --- | --- |
| 1 | The `**Status**:` line states **what the work is and what remains** (nothing / deferred / blocked, with the reason). It never states whether that work was pushed, opened, reviewed or merged. | The only claims that survive are the ones a later merge cannot change. A deferral decision and a completed body of work do not change when a branch is deleted. |
| 2 | Delivery detail belongs in the record's **dated evidence log** (`## Evidence log`, task log), not in the header. | A dated row with a commit hash stays true forever and is checkable with one command; the header is the one place nobody revisits. |
| 3 | Naming the delivery identity in the header is **optional, and never required** — neither a pull-request number nor a merge commit. | Both are written after the record is, so demanding one forces a second pull request. That cost is the fourth generation above, and it bought nothing rule 1 does not already give. |
| 4 | **One pull request per unit.** The record ships in the pull request that carries the work, and its header is final there. No follow-up pull request exists to move a header to a merged shape. | A header that needs a second pull request to become true is a header that was written to need one. |
| 5 | Records written before this rule are **not rewritten**, and 75 headers that name a merge commit stay legal. | Their frozen narrative is itself evidence; rewriting it destroys the audit trail of the drift. Repair only claims that are false *now*, and record the repair. |

## Header shape

The canonical shape, and the one a pull request should arrive with — it is true before the merge and
after it:

```markdown
**Status**: implemented — the guard, its fixtures and this record are the branch's content, and the
sweep of the two contradicting headers is the last task. Nothing is deferred.
**Created**: 2026-10-20 · **Risk**: <level> — <why>
```

A record whose work is deferred or blocked says so, with the reason, and nothing about the transport:

```markdown
**Status**: implemented — U1 and U2 are written; U3 is deferred to its own issue because the design
hinge needs a measurement only the runner can produce.
```

The optional landed shape, for a later session that touches the record for another reason — legal,
never mandatory:

```markdown
**Status**: merged — PR #164 (`refactor/breakpoint-vars` @ `4469cf717`), 2026-09-24. No work left.
```

| Permitted in the header | Forbidden in the header |
| --- | --- |
| `implemented — <what remains>` | `in progress` / `en progreso` |
| `No work left` / `Nothing is deferred` | `not pushed` / `sin pushear` |
| `deferred` / `blocked on <external decision>` | `no PR is open` / `no hay PR abierto` |
| `nothing open in this slice; the follow-ups are separate work` | `awaiting review` / `esperando review` |
| `merged — PR #<n> (<branch> @ <merge-commit>), <date>` (optional) | `not merged` / `sin mergear` / `yet to land` |
| a date, a risk level, a scope statement | `pending review` / `unstarted` / `what remains is the delivery` |
| `<what remains>`, with the reason | `This header makes no claim about push or PR state` **beside** a pull-request number or a merge commit |

The last row is the only place where a *disclaimer* is illegal: on its own it states nothing and stays
permitted, but next to the delivery identity it disclaims it is a contradiction, and it is the exact
sentence #436 had to repair. The vocabulary is bilingual because the records are: `#492` replaced a
header reading `implementado y entregado para revisión … el merge es una decisión del mantenedor`,
which is a live claim in the same class.

## Anti-patterns

| Anti-pattern | Consequence |
| --- | --- |
| Writing the live state because it is true *right now* | It becomes false at the merge, and the file is never touched again |
| Scheduling a revisit to fix the header after the merge | The revisit is a second pull request, a second issue and a second review per unit — 34% of the merges, measured |
| Weakening the claim instead of removing it ("probably merged by now") | Worse than the original: now it is unverifiable too |
| Keeping the delivery detail in the header *and* the log | Two sources of truth, and the header is the one nobody revisits |
| Writing a PR number before the PR is opened | Invents an identifier that may never exist |
| Rewriting an old record's frozen narrative to match this rule | Destroys the evidence that the drift existed |
| Repairing a stale header without recording the repair | Repeats the repair next cycle instead of closing it |

## Gaps

**Nothing updates the header at merge time, and by design nothing needs to.** That sentence used to
appear here as the reason a header had to be written so it would not need updating; the fourth
generation in the table above is what happened when the requirement drifted away from it. The merge
identity is one command away — `git log -1 --format='%h' -- odd/tasks/<slug>.md`, or the pull request
itself — so it is derived rather than stored, and stored only when a session already has the file open.

**What exists now.**

- **`odd-status-headers.test.sh` enforces the invariant over every record on the tree.**
  `.github/scripts/tests/odd-status-headers.test.sh` audits every `odd/tasks/*.md`: a record must carry
  a `**Status**:` paragraph, and that paragraph must not assert the transport — `not merged`, `not
  pushed`, `in progress`, `unstarted`, `awaiting review`, `what remains is the delivery`, `sin
  mergear`, `en progreso`, and the rest. The class fails **unconditionally**: a merge commit beside it
  does not rescue it. A transport disclaimer beside delivery evidence fails too. Where a header
  *chooses* to name a merge commit, the hash must resolve and be an ancestor of `origin/main`, and
  where `origin/main` does not resolve — a shallow CI checkout has no such ref — the run says
  `ancestry: skipped` out loud instead of passing silently. The suite also asserts that every phrase
  `AGENTS.md`'s forbidden row names is actually caught, so the two sources cannot drift.
- **The audit reads the first `**Status**:` paragraph and nothing else.** That scoping is what
  replaces the allowlist this section used to say a guard would need: the records that legitimately
  quote the forbidden phrases (the audit record, the template tables in `odd-record-convention.md`, the
  BEFORE blocks of the reconciliation records, and the frozen narrative
  `purchase-order-goods-receipt.md` D4 protects) keep those quotations outside the first paragraph, so
  no allowlist has to name them.
- **`pr-label-guard.yml` is what makes a records-only change get audited.** It carries **no `paths:`
  filter**, deliberately — the other workflows nest theirs under `pull_request` and scope them to their
  own component, so a markdown-only pull request would run zero jobs — and its `guard` job runs
  shellcheck over every `.github/scripts/**/*.sh` and then every suite under `.github/scripts/tests/`,
  this one included.
- **What the guard does not do.** It does not read past the first paragraph, so the rest of a record's
  narrative stays unverified; and it is a denylist, so a live claim phrased in words the list does not
  carry still passes — the doctrine, not the regex, is what binds the writer.
