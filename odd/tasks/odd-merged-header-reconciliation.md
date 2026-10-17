# ODD feature: odd-merged-header-reconciliation

**Status**: merged — PR **#364** (`docs/odd-merged-header-reconciliation` @ merge commit `364f0ae`), 2026-10-17. The eight header repairs are applied and each one is recorded in its own record, so **nothing remains in this slice**: the two items below are separate decisions. This header carries the merge identity, which does not change.
**Created**: 2026-10-17 · **Base**: `main` @ `fc20a15` · **Branch**: `docs/odd-merged-header-reconciliation` · **Worktree**: `~/workspace/LifeControl-worktrees/docs-odd-merged-header-reconciliation` (herdr workspace `w3E`)
**Risk**: **low.** Documentation only: nine `**Status**:` lines (eight records plus one evidence row), the repair block those records carry, and this record. No source, no test, no CI, no build config, no runtime behaviour. The risk is not breakage, it is writing a merge fact that is wrong, or repairing a header without recording the repair.

## Specs

S1 — the user's two exact strings, in order: «que esta pendiente?», which produced the pending-work inventory this record acts on; then «continua con la b», which picks option **b** of that inventory, whose own text was: «¿tiro el stash viejo de logbook y reconcilios los headers stale?». Two deliverables, and only these two: **drop the old `logbook-logging` stash**, and **reconcile the stale ODD headers**.

S2 — "reconcile" is defined by this repository's own written rule, not by this record: `.agents/skills/project-conventions/references/feature-records.md`, rules 1–4 and its "Header shape" tables. A `**Status**:` line states **durable state plus its evidence** (PR number, merge commit, date) or **what remains**; it never asserts a live or pending delivery state; delivery detail lives in the dated evidence log; and a repair is recorded rather than left to age. The root `AGENTS.md` states the same invariant for `odd/tasks/` in "Registros ODD".

This is a repair of records whose delivery state became **known** after the record was written. It adds no requirement to any record and rewrites no record's narrative.

## Why these eight, and not the ones the first sweep flagged

A keyword sweep over `odd/tasks/*.md` for the forbidden phrasings (`not pushed`, `no PR is open`, `in progress`, `pending review`, the "makes no claim about push or PR state" hedge) returns hits in 24 files. Almost all of them are **quotations or frozen narrative**, which the reference protects: the audit record that documents the drift, the convention's own prohibited-examples tables, and dated log rows that stay true as of their date. Those are listed under "Checked and deliberately not rewritten" below, with the reason for each.

What is left is headers whose work **has landed** and whose `**Status**:` line never stopped describing the pre-merge state. That is the failure mode the reference names in its first section — the header is written while the delivery state is unknown and is never revisited when it becomes known — and the repair is exactly what rule 1 asks for once the state is known.

## Evidence — the inventory, each row verified, none assumed

Every PR number, merge commit and unit commit in this table was verified against `gh` and `git` on 2026-10-17, and **every named commit was confirmed to be an ancestor of `main` @ `fc20a15`** (`git merge-base --is-ancestor`), so every fact written into a header is checkable with one command.

| # | Record | The header said | The durable truth | Merge evidence | Repair |
| --- | --- | --- | --- | --- | --- |
| 1 | `inventory-settings-conflict-ux.md` | `implemented — … This header makes no claim about push or PR state.` | The slice landed; follow-ups 1–5 stay open | PR **#172**, merge `167a6ce`, 2026-09-25 (`2b7368e` ⊂ `167a6ce`) | T4 |
| 2 | `sales-scan-ux.md` | `implemented — … No claim is made here about push or PR state.` | The three work units landed one PR each, plus the record; the manual scan-coherence pass stays owed | PRs **#189** `0d58a8a`, **#190** `a4c698c`, **#191** `299837f`, record **#192** `879ae2a`, 2026-09-27 | T4 |
| 3 | `test-timeout-headroom.md` | `implemented — … makes no claim about push or PR state — see … F11.` | The slice landed | PR **#169**, merge `c49d296`, 2026-09-24 (`7fe3729`, `cdfc9d4` ⊂ `c49d296`) | T4 |
| 4 | `unify-mobile-viewport-consumers.md` | `implemented — … makes no claim about push or PR state — see F11.` | The slice landed; F11 and the other follow-ups stay open | PR **#168**, merge `12cef90`, 2026-09-24 (`dc0152a`, `91f4d61`, `e0f6c33` ⊂ `12cef90`) | T4 |
| 5 | `odd-header-hygiene.md` | `implemented — 15 prescribed edits … makes no claim about push or PR state — see D2.` | The slice landed | PR **#170**, merge `0106380`, 2026-09-25 (`1a75908` ⊂ `0106380`) | T4 |
| 6 | `odd-record-convention.md` | `implemented — the header invariant is now documented … makes no claim about push or PR state` | The slice landed; it is enforced by nothing, deliberately (`## Gaps`) | PR **#171**, merge `6423256`, 2026-09-25 (`2ccddc8` ⊂ `6423256`) | T4 |
| 7 | `employee-registry.md` | `W1a, W1b, W2a, W2b, W3a and W3b are implemented … with W4 complete and merged to main as 17bd1e8 (PR #234)` | **All six units landed three to four weeks before this repair**; the header named only W4's merge and closed with "makes no claim about branch, push or PR state" | PRs **#229** `e66d5a4`, **#230** `a7ac439`, **#231** `afe9df1`, **#232** `67f7543`, **#233** `77f7387`, **#234** `17bd1e8`, 2026-10-03/04 | T4 |
| 8 | `keycloak-adapter-error-contract.md` | `implemented and verified … by its parent record's convention this header claims no push, pull-request or merge state` | The unit landed; `T6` closed as retired; the gaps X1–X3 are declared limits, not unfinished work | PR **#347**, merge `3a19856`, 2026-10-16 (`1588261`, `142ec68` ⊂ `3a19856`) | T4 |

Two measurements worth keeping from the sweep, because they are what makes the repair list trustworthy rather than a guess:

- **Not one of the eight claims something false.** Each declines the delivery state rather than asserting it, which is why none of them was caught by the two prior repairs (#162, #170) — those hunted live assertions ("Not pushed; no PR is open") and these had none. What is stale is the **omission**: the durable state is now known and the header does not carry it, which is precisely what rule 1 asks the line to carry.
- **The hedged form was prescribed at the time.** Rule 4 exists because the neutral shape cannot be falsified by a later merge. It was written on 2026-09-24/25 (`docs/odd-record-convention` PR #171 merged `6423256` at 2026-09-25T03:33Z) and four of the eight above predate it (`test-timeout-headroom`, `unify-mobile-viewport-consumers`, `odd-header-hygiene`, `odd-record-convention`).

## Checked and deliberately not rewritten

| File and site | Why it stays as written |
| --- | --- |
| `angular-22-upgrade.md:4` | `planned` with the work that remains, which rule 1 permits, and it is accurate today: no branch exists and no source line has been touched. Its tracking decision is the user's, and it was **not** part of option (b) — see "Needs your decision". |
| `mobile-breakpoint-guard.md:10`, `mobile-breakpoint-single-source.md:12`, `mobile-viewport-observer.md:12` | Already terminal: each carries `**Merged as PR #166 / #165 / #167**` with its commit. Nothing to reconcile. |
| `odd-status-reconciliation.md` (tables, `:49`–`:69`, `:155`–`:205`) | The audit record that documents the drift it repaired. Its hits are quotations of the faulty headers; the reference's `## Gaps` names this allowlist explicitly. |
| `odd-record-convention.md:94`, `:116`, `:158`–`:161` | The convention's own rule, its prohibited-examples table and the history table. Quotations by construction; rewriting them would delete the rule. |
| `odd-header-hygiene.md:18`, `:19`, `:40`, `:61`, `:112`, `:125`, `:137`, `:218`, `:243`, `:250`, `:286` | The same audit family: the edit contract and the findings that quote the 2026-09 hedges they repaired. Frozen. |
| `purchase-order-goods-receipt.md:378` | A section heading from 2026-09-20 (`W2d3 … unpushed`); the reference's `## Gaps` records that its own D4 protects this narrative, so it is not rewritten. |
| `employee-access-provisioning.md:666` | A **dated log row** (2026-10-14). Rule 3 keeps it: a dated row with a commit hash stays true as of its date, and the merge that followed it is recorded in a later row. |
| `mobile-breakpoint-single-source.md:209` | Inside finding F3, quoted as an allowlist item by `odd-header-hygiene.md:40`. Not a header. |
| `store-locations-frontend.md:523` | A body sentence ("Nothing is in progress and no follow-up blocks anything"), not a `**Status**:` line, and it is true. |
| `sales-location-aware-stock.md:11` | Its header **does** state its own delivery — all four PRs with their merge commits — and the trailing sentence declines the state of the **follow-ups** that landed in sibling branches (`fix/sales-order-line-guard-followups`, `fix/sales-order-409-openapi`). That is a claim about other units, not about this record, so it is not the same defect. Left as written. |

## Decision: how far rule 4 reaches

Rule 4 says records written before the rule are not rewritten, because their frozen narrative is itself evidence, and adds that the repair targets claims **false now** and must be **recorded**. Read together with rules 1–3, the operative line is: **the narrative and the dated log stay as written; the `**Status**:` line is the field that must state the durable state, and repairing it is what the rule prescribes.**

That is the reading this slice applies, and it is checkable in the diff: eight `**Status**:` paragraphs change, one dated `## Header repair (2026-10-17)` block is appended to each of the eight (which is how each repair is recorded in the record it repairs, in addition to the table above), and **not one body line, table row or log entry is removed or altered**. A reviewer who reads rule 4 differently should reject this slice and say so; the alternative reading — leave all eight — is defensible, and it is why the four pre-rule records are named as such above rather than presented as defects.

## Tasks

| # | Spec | Route | Task | Commit |
| --- | --- | --- | --- | --- |
| T1 | S1 | inline | Inventory: every `odd/tasks/*.md` `**Status**:` line measured against `gh`/`git` truth, and every named merge commit confirmed an ancestor of `main` | `141cae8` (this record) |
| T2 | S1 | inline | Drop the `logbook-logging` stash after proving `main` supersedes it, and record its SHA so it stays recoverable | `141cae8` / `6e166ec` |
| T3 | — | inline | Incident: another agent was writing in the anchor and the anchor's HEAD was moved by the first attempt; restore `main`, delete the empty branch, and do the work in its own worktree (`w3E`) | `141cae8` |
| T4 | S2 | inline | Apply the eight `**Status**:` repairs and append the dated repair block to each record | `141cae8` |
| T5 | S1 | inline | Record the stash drop, its SHA and why nothing was lost in `logbook-http-logging.md`'s evidence log | `6e166ec` |
| T6 | S2 | inline | Verification: ancestry of every named commit, a forbidden-phrase sweep, and a diff review that only `**Status**:` lines and appended blocks changed | `141cae8` / `6e166ec` |

## The dropped stash (T2)

`stash@{0}` was `On main: logbook-logging (reverted 2026-10-16: no compatible con la migracion a spring-boot-4 en paralelo)`. Before dropping it, its content was compared against `main` @ `fc20a15`, because a stash that still holds unique work must not be dropped:

| Path (from `git stash show --stat`, 5 files, +79 −12) | Versus `main` |
| --- | --- |
| `life-control-api/src/main/java/…/logbook/LogbookConfig.java` | **byte-identical** (`sha256` of the blob: `aca2b6fc27a2…` both sides) |
| `life-control-api/src/main/resources/logback-spring.xml` | **byte-identical** (blob `cf4f5892cb71…` both sides) |
| `life-control-api/src/test/resources/logback-test.xml` | unchanged between the two (the stash and `main` agree) |
| `life-control-api/src/main/resources/application.properties` | `main` is **later and larger**: the same Logbook block plus the `LogbookProfileGuard` fail-closed notes, `spring.jackson.deserialization.fail-on-unknown-properties=true`, the renamed `management.opentelemetry.tracing.export.otlp.endpoint` and the two OTLP exporter switches |
| `life-control-api/AGENTS.md` | `main` is later: Spring Boot 4.1.1, the body-redacted-outside-`dev` rule, the profile guard, `JsonMapper` |

So the stash was an earlier draft of exactly the lines `main` now carries more completely (`logbook-log-safety` PR #354 → `11275c4`, and `logbook-http-logging` PR #351 → `0e0bc69`), and dropping it loses no unique work. **Dropped SHA: `d87bc54e21b3212509d3399332f2905e661201c3`** (2026-10-16T18:39:24-06:00). A dropped stash stays in the object store until `git gc` prunes it, so if this judgement is ever disputed the commit is recoverable by that SHA — which is why it is written down here and in `logbook-http-logging.md`, and not only dropped.

## Verification

| Claim | How it was checked | Result |
| --- | --- | --- |
| Every merge commit named in a repaired header is real and on `main` | `git merge-base --is-ancestor <commit> main` for all 16 merge/unit commits | 18/18 ancestors of `main` @ `fc20a15` (T1, T6) |
| Every PR number named is the one whose branch carried that work | `gh pr list --state all --json number,headRefName,state,mergedAt,mergeCommit`, matched by branch name | matched for all eight; `sales-scan-ux` resolved as three fix branches plus its record branch, which is why its header names three PRs |
| No repaired header asserts a live or pending state | the sweep for `not pushed` / `unpushed` / `no PR is open` / `PR is open` / `not merged yet` / `yet to land` / `in progress` / `awaiting review` / `pending review` over `odd/tasks/*.md` | zero new hits: every remaining hit is a quotation, a dated row or a prohibited-examples table, listed above |
| Only headers changed | `git diff --stat` plus a hunk-by-hunk read of the diff | eight `**Status**:` paragraphs replaced, eight blocks appended, one evidence row added; no other line touched |
| The stash held nothing unique | blob-level comparison of the four differing paths plus the `--stat` listing | two paths byte-identical to `main`, two superseded by later `main` content |
| The merged bytes are the gated bytes | `git diff --stat c337c45 364f0ae -- <the ten files>` | **empty**: the ten files are byte-identical between the branch tip and the merge commit. The merge **tree** differs from the branch tree, and that is expected rather than a discrepancy: `main` advanced to `8727627` (PR #362) before the merge, so the merge tree is the branch plus those five files |
| The merged commit is green | `gh run list --commit 364f0ae` | the four workflows the push to `main` triggers are **`success`** — Docker Build Integrity `39049858799`, Gateway CI `39049858795`, API CI `39049858796`, Angular CI `39049858814` |

## Log

- **L1** — *the user, 2026-10-17, verbatim*: «que esta pendiente?» — answered from a read-only measurement of the tree (`main` @ `fc20a15`: 0 open PRs, 0 open issues, review authority `clean`, one untracked ODD record), which is where "reconcile the stale headers" came from as a pending item: seven `odd/tasks/` records still read `implemented` while their branch no longer exists.
- **L2** — *the user, 2026-10-17, verbatim*: «continua con la b» — authorizing option (b) exactly as offered: drop the stale stash, reconcile the stale headers. Option (a) (track `odd/tasks/angular-22-upgrade.md` and start its slice A) was **not** chosen and is not touched here.
- **L3** — *2026-10-17, this pass*: **the reconciliation turned out to be smaller and the incident larger than the offer assumed, and both are recorded rather than smoothed over.** The first sweep's list was wrong in one direction and right in another: three of its seven candidate records already carried `**Merged as PR #…**` and needed nothing, while two records the sweep had not flagged (`employee-registry`, `keycloak-adapter-error-contract`) belonged in the repair list, and the truth for every row had to come from `gh`/`git` rather than from the header being repaired. The incident: the anchor `~/workspace/LifeControl` is **not clean** — another session (advertised as `01a14a86-…`, same workspace and clone) was mid-implementation on the PR-type labels at 09:55, with `AGENTS.md`, `.agents/skills/project-conventions/SKILL.md`, `references/index.md`, a new `references/labels.md` and a new `odd/tasks/pr-type-labels.md` uncommitted. Creating the branch in the anchor **moved that session's HEAD** for about a minute; it was restored to `main` the moment `git status` showed the foreign changes, no commit was made in the window (`docs/odd-merged-header-reconciliation` pointed at `fc20a15` and had zero commits of its own), and this slice now runs in its own worktree per `references/worktrees.md` invariants 1–5. **No file outside `odd/tasks/` is touched by this slice**, and no file belonging to the other session was modified, staged or committed.
- **L4** — *2026-10-17, the commits that carry it*: **`141cae8`** carries the eight repairs plus this record (9 files, **+160 −28**), **`6e166ec`** the one dated row that records the dropped stash (1 file, **+1**), and **`c337c45`** the task table's real hashes plus the Delivery section (1 file, **+17 −7**). Measured by the runtime: **189 authored changed lines on the branch since `origin/main`**, inside the 400-line budget.
- **L5** — *2026-10-17, the delivery, written after the merge because that is the only moment at which it can be written*: **PR #364 merged to `main` as `364f0ae` with a real merge commit** (parents `8727627` + `c337c45`, never squash), and **the four push-to-`main` workflows ran `success`** on that commit. **Two things moved underneath this slice and both are recorded rather than tidied away**: `main` advanced from `fc20a15` to `8727627` (PR #362, the `type:*` label vocabulary) while the pull request was being opened, which is why the merge tree is not the branch tree — the ten files are byte-identical, proven above — and `main` advanced again to `b05b525` (PR #363) before this row was written. **No rebase was performed, deliberately**: this record and the pull request body name `141cae8`, `6e166ec` and `c337c45`, and a rebase would rewrite those hashes and falsify the evidence this slice exists to make true. **Two self-corrections in this row**: L4's last sentence — *nothing was pushed and no pull request was opened* — was true when written and false minutes later, and the state it declined is now stated here instead; and **L4 was written before L3**, so the two are in order now, which is itself the failure mode this slice repairs, committed once more by the slice that repairs it.

## Needs your decision

- **`odd/tasks/angular-22-upgrade.md` is untracked.** It is the only record in `odd/tasks/` that Git does not track, which makes it invisible to any clone, to CI and to the next session that reads the directory — and it is also the untracked path that made a native `assess` answer `nativeRisk: unassessable` on 2026-10-17. Tracking it is a one-line commit; starting its slice A is the separate decision option (a) left open.
- *(Closed 2026-10-17: this slice's delivery. Push, pull request and merge were the user's decision, taken one step at a time — see `L5` for the merge identity and the green workflows.)*

## Delivery

| Commit | Lines | What it carries |
| --- | --- | --- |
| `141cae8` | 160 (+)/28 (−) over 9 files | The eight `**Status**:` repairs, their eight dated `## Header repair (2026-10-17)` blocks, and this record |
| `6e166ec` | 1 (+) over 1 file | The dated row recording the dropped stash in `logbook-http-logging.md` |
| `c337c45` | 17 (+)/7 (−) over 1 file | The task table's real commit hashes and this record's Delivery section |

The slice measures **189 authored changed lines on the branch since `origin/main`**, against the repository's 400-line review budget, so no size exception is owed. It was pushed as PR **#364** and merged to `main` as **`364f0ae`** on 2026-10-17, with a real merge commit (parents `8727627` + `c337c45`, never squash), and **the four workflows the push to `main` triggers ran green on it** (`L5`). The ten files are byte-identical between the branch tip and the merge commit.
