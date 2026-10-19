# ODD feature: mobile-header-repair

**Repository**: LifeControl — documentation only (`odd/tasks/**`, four records). No source file, no test, no
CI, no build config, no runtime behaviour.
**Status**: **merged** — PR **#370** (`docs/mobile-header-repair` @ `0ed5e33`), merge commit `6ee5752`,
2026-10-17, so `main` carries the three repaired `**Status**:` lines, their appended `## Header repair`
sections and this record. Nothing remains open in this slice; the follow-ups the three records declare are
separate work.
**Created**: 2026-10-17 · **Base**: `main` @ `31a15b8` · **Branch**: `docs/mobile-header-repair` ·
**Worktree**: `~/workspace/LifeControl-worktrees/docs-mobile-header-repair` (herdr workspace `w3N`)
**Risk**: **low.** Documentation only: three `**Status**:` lines and one appended section per record, plus
this record. No source, no test, no CI, no runtime. The risk is not breakage, it is writing a merge fact that
is wrong, or repairing a header without recording the repair — the second is the failure mode
`feature-records.md` names by name.
**Origin**: the user's «que esta pendiente?» produced a pending-work inventory over `odd/tasks/**`; the three
`mobile-*` records were flagged in it as header-hygiene candidates rather than as open work, and the user then
instructed «arregla los headers».

## Specs

**S1** — the user's two exact strings, in order: «que esta pendiente?», which produced the inventory this
record acts on; then «has el merge y arregla los headers», of which only the second clause is this slice's
scope (the merge belongs to `pr-label-enforcement`'s bookkeeping slice). One deliverable: **repair the stale
`**Status**:` lines**.

**S2** — "repair" is defined by this repository's own written rule, not by this record:
`.agents/skills/project-conventions/references/feature-records.md`, rules 1–4 and its "Header shape" tables. A
`**Status**:` line states **durable state plus its evidence** (PR number, merge commit, date) or **what
remains**; it **never** asserts a live or pending delivery state; delivery detail belongs in the dated
evidence log rather than the header; and records written before the rule are **not** rewritten — only claims
that are **false now** are repaired. The canonical landed form the table prescribes is
`merged — PR #<n> (<branch> @ <merge-commit>), <date>. <what remains, or "No work left.">`, and the canonical
worktree/herdr detail does not appear in it at all.

**S3** — the repair is **recorded**, not left to age: the anti-pattern table names *"Repairing a stale header
without recording the repair"* as repeating the repair next cycle, and the precedent (`odd-status-
reconciliation`, `odd-merged-header-reconciliation`) records each repair in the repaired record as an appended
`## Header repair (<date>)` section that quotes the before-state verbatim.

**S4** — scope is exactly the records that are **false now**. Rule 4 forbids rewriting frozen narrative to
match the current rule, so a record whose story is stale but whose `**Status**:` line is not a false delivery
claim is **reported, not edited**.

## Tasks

One commit holds all three repairs because they are one unit of behaviour — the same defect, the same rule,
the same repair shape — and no task's risk line names a high-risk item.

| ID | S# | What | Commit |
| --- | --- | --- | --- |
| T1 | S1–S3 | Repair the `**Status**:` line of `odd/tasks/mobile-breakpoint-guard.md` and record the repair in it | `0c9c7a9` |
| T2 | S1–S3 | Repair the `**Status**:` line of `odd/tasks/mobile-breakpoint-single-source.md` and record the repair in it | `0c9c7a9` |
| T3 | S1–S3, S4 | Repair the `**Status**:` line of `odd/tasks/mobile-viewport-observer.md`, record the repair in it, and report the stale `F1` rather than rewriting it | `0c9c7a9` |
| T4 | S1–S4 | Record this slice, its inventory and its evidence | `b94fac4` |
| T5 | S1–S3 | Fix the nested code spans L7 found, in the two records that carried them | `9d7cabd` |

## Inventory

Every record in `odd/tasks/**` (58 files) was read for its `**Status**:` line. A regex over the live-state
vocabulary (`implemented`, `delivered for review`, `in progress`, `awaiting`, …) flagged **nine**; six of them
lead with `merged — PR #…` and merely *use* the word "implemented" to describe the work's content, so the
regex is an index and not a verdict. Three were false now:

| Record | What the header asserted | Why it is false now | Evidence verified |
| --- | --- | --- | --- |
| `mobile-breakpoint-guard.md` | `implemented` — 3 commits on `fix/mobile-breakpoint-guard`, worktree `…/fix-mobile-breakpoint-guard` (`w11`) | The work merged on 2026-09-24; the merge fact sat two lines below in its own `**Merged as PR #166 …**` row; the worktree and herdr workspace no longer exist | `git rev-list --parents 89b4d9b` → `4469cf7` + `9c272ce`; `gh pr view 166` → `MERGED` `89b4d9b` 2026-09-24T19:34:16Z |
| `mobile-breakpoint-single-source.md` | `implemented` — 4 commits on `fix/mobile-breakpoint-single-source`, worktree `…/fix-mobile-breakpoint-single-source` | Same defect; its merge row said **Merged as PR #165** (`7f8ba11b9`), **2026-09-24.** | `git rev-list --parents 7f8ba11` → `4469cf7` + `ac7cc09`; `gh pr view 165` → `MERGED` `7f8ba11` 2026-09-24T17:36:37Z |
| `mobile-viewport-observer.md` | `implemented` — 3 code commits plus this record, worktree `…/refactor-mobile-viewport-observer` (`w12`) | Same defect, worse shaped: the merge fact was parked at the **end** of the same overloaded line | `git rev-list --parents 0725237` → `89b4d9b` + `a6cd059`; `gh pr view 167` → `MERGED` `0725237` 2026-09-24T20:35:56Z |

Two checks make the repairs safe rather than merely plausible:

- **The named commits are the merge's ancestry.** `git merge-base --is-ancestor` confirms `ea709a0`,
  `d437db9`, `c3de16a`, `152db88`, `4f82b06`, `53a7ac6`, `639d0b4`, `eca52b3`, `8b8ede8` and `de5fd62` are all
  in `main`. The second parent of each merge is the branch tip *including* that branch's own record commit
  (`9c272ce`, `ac7cc09`, `a6cd059`), which is why it differs from the code commits the header names — not a
  discrepancy, and worth stating so the next reader does not "fix" it.
- **The ephemeral locations are false now.** `git worktree list` and `herdr workspace list` both show only the
  anchor, so the `worktree ~/…` and `herdr workspace w11`/`w12` clauses were removed from the three headers —
  a deliberate departure from `odd-merged-header-reconciliation`, whose two repaired headers kept that detail.
  Rule 3 puts delivery detail in the log, and a working-copy path that no longer exists is not durable state.

## Log

**L1** *(the request, verbatim)*: «que esta pendiente?» → «si» (authorizing the `pr-label-enforcement`
bookkeeping slice) → «has el merge y arregla los headers». `L1` names the whole turn; **S1** narrows this
slice to «arregla los headers».

**L2** *(inventory, 2026-10-17)*: the scan in `## Inventory` ran over all 58 records. It found the drift
already named by the records themselves — three of the follow-up inventories end with "the ODD record header
drift" as an unchanged item — which is why this slice cites the rule instead of inventing a severity: the
repository had already registered the defect and never closed it.

**L3** *(evidence, 2026-10-17)*: every fact written into the three headers was read from the remote or from
Git, never from memory: `gh pr view {165,166,167}` for state, merge commit and timestamp, and
`git rev-list --parents` plus `git merge-base --is-ancestor` for the parentage and the ancestry of each named
commit.

**L4** *(deviation from precedent, and why)*: `odd-merged-header-reconciliation` repaired its eight headers by
gluing the merge identity onto the existing sentence and keeping the worktree/herdr detail. This slice does
neither: it leads with the canonical `merged — PR #<n> (<branch> @ <commit>), <date>` form from the header
shape table and drops the ephemeral locations, because a path that no longer exists is not durable state and
Rule 3 places that kind of detail in the log. The departure is recorded here so the inconsistency across the
two generations of repairs is auditable rather than accidental.

**L5** *(reported, not rewritten — S4)*: `mobile-viewport-observer.md`'s `F1` names the end-state defect as
*two idioms for one boundary* and asks for all five consumers to move onto the CDK `BreakpointObserver` +
`toSignal` idiom. The boundary was unified the other way: `unify-mobile-viewport-consumers` (**PR #168**,
merge `12cef90`, 2026-09-24) moved `detail-table.ts` and `receipt-detail.ts` **onto this slice's helper**. F1's
premise is stale. It is reported in that record's repair section and **not** rewritten, because its frozen
narrative is evidence; the row should be corrected by whoever owns it.

**L6** *(base)*: this slice sits on `main` @ `31a15b8`, the merge of **PR #369** (the `pr-label-enforcement`
bookkeeping slice), whose push-triggered CI concluded **4/4 `success`** — `Docker Build Integrity`,
`Gateway CI`, `API CI`, `Angular CI`.

**L7** *(a self-inflicted defect the gates could not see, 2026-10-17)*: the first draft of two repair
sections wrapped a quoted line inside a code span that itself contained code spans —
`` `**Merged as PR #166 (`89b4d9b76`), 2026-09-24.**` `` — and a code span ends at the first backtick, so
that quote would have rendered as broken prose. No CI workflow inspects markdown, so nothing but a read would
have caught it. It was found by re-reading the pushed diff. The first repair was an **amend** of `0c9c7a9`, and
the force-push was **blocked by the harness's safety policy** — rewriting a published branch is a destructive
operation, so it was put to the user, who chose **no rewrite**: the branch keeps `0c9c7a9` and `b94fac4` as
published and the fix lands as a further commit (T5). That is why the `## Tasks` table above still names the
original hashes rather than an amended one. The lesson is not about backticks: **this slice's only real gate
is a human read**, so the verification that a markdown change gets has to include one — and a gate that cannot
see the defect is not a gate for it.

## Header repair (2026-10-19)

The `**Status**:` line above was rewritten on 2026-10-19 by the unit that carries issue **#454**, where the
inventory, the normalized sweep and the verification live. It used to decline the delivery state: «This header
makes no claim about push or PR state; see the evidence log.» That state became known when the work merged —
PR **#370**, merge `6ee5752`, 2026-10-17 — so the line now carries it. This record is the one the *previous*
sweep missed while declaring the reason it could miss it: its own verification table names the line-oriented
filter's blind spot, and this sentence is split by a line wrap, so `This header makes no` ends one line and
`claim about push or PR state` starts the next. The follow-up `F1` this record reported rather than rewrote
(`L5`, about `mobile-viewport-observer.md`) stays open and is not this unit's. Nothing else in this record was
touched.
