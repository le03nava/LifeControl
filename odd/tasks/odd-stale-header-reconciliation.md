# ODD feature: odd-stale-header-reconciliation

**Repository**: LifeControl — `odd/tasks/` only: eight `**Status**:` paragraphs, their eight dated
header-repair blocks, and this record. No code, no test, no script, no workflow, no dependency, no
configuration.
**Status**: **merged** — PR **#436** (`docs/odd-stale-header-reconciliation` @ `0322b49`), merge commit
`a6207fd`, 2026-10-19, closing issue **#434**, so `main` carries the eight header repairs, their eight dated
blocks and this record. Nothing in this unit is open. Its own `**Status**:` line was the one repair the commit
could not perform on itself, and the unit that carries issue **#454** performs it now, with the block appended
below.
**Created**: 2026-10-19 · **Issue**: **#434** (`status:approved`) · **Base**: `main` @ `18de07d`
**Branch**: `docs/odd-stale-header-reconciliation` · **Worktree**:
`~/workspace/LifeControl-worktrees/docs-odd-stale-header-reconciliation` (herdr workspace `w4E`); the
anchor stays on `main`.
**Risk**: none in the sense of `AGENTS.md`'s list — no stored data, no credential, no consumed contract,
no concurrency, no pipeline. The documented risk of *not* doing it is the one this repository has
measured three times (`odd/tasks/odd-status-reconciliation.md`, `odd/tasks/odd-header-hygiene.md`,
`odd/tasks/odd-merged-header-reconciliation.md`): the header is the only artifact every clone, every
future session and every reviewer read, and nothing — no workflow filters `odd/tasks/**` — will catch a
header that lies.

## Specs

**S1** — The request, verbatim: «que esta pendiente?», answered from a read-only measurement of the
repository (`main` @ `61fb69e`: one open pull request, one open issue, no other branch unmerged into
`main`, a clean anchor).

**S2** — The authorizing decision, the two options selected from that inventory, verbatim as they were
offered: *"Mergear #426 (actualizar rama + merge)"* and *"Reconciliar los 5 headers stale"*, the second
described as *"Unidad aparte, con su issue: 5 registros dicen 'implemented' con la entrega ya en main"*.

**S3** — Exactly what S2 authorizes, and the reason the count moved: **five was the offer, eight is the
measurement.** The rule is `AGENTS.md`'s — the header carries the terminal state with its evidence, never
a live or pending state — and the count is whatever a sweep of every record under that rule returns, not
what the offer happened to list.

**S4** — The repair has the shape this repository already uses, not a new one: `**Status**:` carries the
terminal state with the PR number, the merge commit and the date **copied from `gh`/`git`**, and each
repaired record gains one dated `## Header repair (2026-10-19)` block that **quotes verbatim the sentence
that same commit deleted**, because the convention asks to record the repair and not only to perform it
(`odd/tasks/odd-merged-header-reconciliation.md`, T4).

**S5** — Frozen narrative is not rewritten: no `L`-entry, no dated evidence row, no historical section
heading and no quoted sentence is edited, because a record's frozen narrative is itself evidence. Where
the code falsified what an older sentence said, the header **supersedes** it and says so.

**S6** — The delivery flow is this repository's own: an approved issue before the pull request
(`CONTRIBUTING.md` §1), exactly one `type:*` label (§2), the worktree convention (§4), this record (§5),
and a real merge commit (§7–§8). No automatic guard for record headers is invented here — that non-goal
is #423's and it stays.

## Tasks

| # | Task | S# | Route | Commit |
| --- | --- | --- | --- | --- |
| T1 | Measure the inventory from `gh`/`git` instead of from the records themselves | S1, S3 | Inline — read-only sweep of the 70 records' `**Status**:` lines plus the delivery identity of each | `docs(odd)`, this commit |
| T2 | Repair the seven headers the first sweep returned | S3, S4, S5 | Inline — one deliberate writer per file would cost more than the edit | `docs(odd)`, this commit |
| T3 | Re-sweep **all** records after the repair, not only the repaired set | S3 | Inline | the eighth record, `angular-22-upgrade.md` — see `## Decision` |
| T4 | Repair the eighth header and write its block | S3, S4 | Inline | `docs(odd)`, this commit |
| T5 | Verify: ancestry of every commit cited, the sweep, and the diff's own shape | S4, S5 | Inline | `## Verification` below |

## Evidence — the inventory, each row measured, none assumed

Measured on `main` @ `18de07d`, each merge commit checked with
`git merge-base --is-ancestor <commit> origin/main` (12 of 12 ancestors, `manifest`'s `e671799` included) and each PR checked with
`gh pr view <n> --json number,headRefName,state,mergedAt,mergeCommit`.

| # | Record | The clause it carried | The measured state |
| --- | --- | --- | --- |
| 1 | `e2e-keycloak-mock-ordering.md` | `What remains is the delivery of this unit, which is its own PR; the record makes no claim about push, PR or merge state.` | delivered — PR **#417** (`fix/e2e-role-mock-auth` @ `b579d69`), merge `8514610`, 2026-10-18 |
| 2 | `pr-label-guard-cancelled-check-runs.md` | `This header makes no claim about push or PR state; see the evidence log.` | delivered — PR **#412** (`fix/cancelled-required-check-runs` @ `144592d`), merge `58cec5e`, 2026-10-18, closing **#410** |
| 3 | `pr-label-guard-stale-payload.md` | `This header makes no claim about push, PR or merge state; the delivery detail belongs to the evidence log` | delivered — PR **#403** (`fix/pr-label-guard-stale-payload` @ `b2d2ec6`), merge `198b416`, 2026-10-18, closing **#397** |
| 4 | `unmapped-path-404.md` | `what remains is the delivery of this unit as its own PR. This header makes no claim about push or PR state.` | delivered — PR **#428** (`fix/unmapped-path-404` @ `1b49985`), merge `5f27d23`, 2026-10-19, closing **#424** |
| 5 | `tempo-receiver-endpoints.md` | `what remains is the delivery of this unit as its own PR and the review-authority decision recorded in L11.` | delivered — PR **#427** (`fix/tempo-receiver-endpoints` @ `bc2984b`), merge `61fb69ed`, 2026-10-19, closing **#425**; the `L13` decision stays open and is *not* this unit's |
| 6 | `spring-boot-4-migration.md` | `What remains is the whole migration, unstarted; it is a major-version migration, not a dependency bump.` | **falsified** — the migration landed as `odd/tasks/spring-boot-4-1-migration.md`: PR **#348** (`a67d1cb`, API) + PR **#349** (`43b1a7e`, gateway), 2026-10-17; `main` runs Boot `4.1.1` in both services |
| 7 | `sales-location-aware-stock.md` | `This header makes no claim about push, pull-request or merge state: the dated evidence rows are in the task log below, and each unit's delivery is a separate and later row.` | its own follow-ups delivered — `F-1`/`F-2` PR **#338** (`08d4a0b`), `F-3` PR **#341** (`dec82d9`), `F-4`…`F-7` PR **#343** (`8a80418`), `F-8` PR **#344** (`fd2eba9`), all 2026-10-16 |
| 8 | `angular-22-upgrade.md` | `This header makes no claim about push or PR state.` | **the sentence PR #426 wrote on 2026-10-18 and left behind**: the line it closes already names merge `e671799a53445f61e9f7019ad177b9960bb2908b` and closed issue #418 — see `## Decision` |

## Decision: why eight rows and not five

The offer in S2 said five, and the first sweep returned exactly those five — because it filtered for
`**Status**:` lines that *start* with a declining verb. That filter is the failure this unit exists to
avoid, and it failed again in the same pass: rows 6 and 7 never use the word, they decline by clause and
by a deferred state the code has since falsified; row 8 appeared only when the sweep was re-run **after**
the repair, and it is the freshest of the eight: written by PR #426 on 2026-10-18 (`ab79bea`, 15:16:15-06:00)
in the session that had just closed a record about header hygiene, merged the same evening (`18de07d`,
20:38:11-06:00) a few hours before this sweep found it.

That is the whole reason this unit re-sweeps every record before finishing rather than the candidate list
it was handed, and the measured cost of skipping it is three records. `odd/tasks/odd-merged-header-reconciliation.md`
L3 recorded the same lesson from the other direction (three of the seven it was handed were already
repaired). Two independent passes finding the same class of drift is the argument for treating the sweep,
not the list, as the artifact.

## Verification

| Claim | Measurement |
| --- | --- |
| Every merge commit a repaired header names is real and on `main` | `git merge-base --is-ancestor <commit> origin/main` over all 12 commits the eight headers cite → **12/12 ancestors** of `origin/main` @ `18de07d` |
| Every PR number named is the one whose branch carried that work | `gh pr view <n> --json number,headRefName,state,mergedAt,mergeCommit` for #403, #412, #417, #427, #428, #338, #341, #343, #344, #348, #349, #426 → every head branch matches the record it is cited in |
| No repaired `**Status**:` line still declines or defers a known state | `grep -n -E '^\*\*Status\*\*\|^\*\*No slice work remains' odd/tasks/*.md \| grep -i -E 'what remains is the delivery\|makes no claim about push\|Nothing is pushed\|no PR is open\|not merged yet\|yet to land\|awaiting review\|pending review\|in progress\|unstarted\|not pushed'` → **one hit**: `odd-record-convention.md:152`, the prescribed template, listed under `## Checked and deliberately not rewritten`. **One blind spot, stated rather than left implied**: the grep is line-oriented, and this record's own `**Status**:` line carries the same declining shape with the phrase split by a line wrap (`What` ends one line, `remains is the delivery` starts the next), so it does not match. It is the second instance, it is intended, and `## Delivery` says why. |
| The sweep covered every record, not the candidate list | the same command over `odd/tasks/*.md` — **70 records** on `main` @ `18de07d`, 71 with this one — before and after the repair; the "after" run is what produced row 8 |
| Each repaired record carries exactly one dated block, and it quotes the sentence the diff deleted | `grep -c '^## Header repair (2026-10-19)$'` → `1` in each of the eight; the quoted clause in each block is read against the `-` lines of its own hunk with `git show -U0` |
| Only `odd/tasks/**` changed, and no frozen narrative was touched | `git diff --numstat` over the commit → nine paths, all under `odd/tasks/`; `git show -U0 --format= \| grep -E '^@@'` → each repaired record's two hunks are the `**Status**:` paragraph and the appended block, and no hunk falls inside an `L`-entry, a dated evidence row or a section heading |
| CI expectation, stated not assumed | `odd/tasks/**` appears in no workflow's `paths:` filter, so `Angular CI`, `API CI`, `Gateway CI` and `Docker build integrity` do not run on this pull request; `PR Label Guard` and its issue-reference job are the checks that do |

## Checked and deliberately not rewritten

| Where | Why it is left as it is |
| --- | --- |
| `odd/tasks/odd-record-convention.md:152` | The **prescribed template** of an undelivered header (`**Status**: implemented — <what remains>. This header makes no claim about push or PR state; see the evidence log.`), quoted inside the convention that defines the rule. Rewriting it would delete the specification. |
| `odd/tasks/purchase-order-goods-receipt.md:378` | A dated section heading from 2026-09-20 (`W2d3 — Angular UI (W2d1 + W2d2 merged; W2d3 delivered, unpushed — 2026-09-20)`). It is frozen narrative and was already ruled out of scope by `odd-merged-header-reconciliation.md` T2/T5. |
| `odd/tasks/redis-cache-activation.md:4` | The line keeps its live sentence and then supersedes it in place with a dated `(Repair, …)` clause — the shape `odd/tasks/pr-label-guard-stale-payload.md` L7 prescribes for a falsified sentence. |
| `odd/tasks/tempo-receiver-endpoints.md`, `L4` and `L13` | Follow-ups that genuinely remain: the k8s manifests still declare the same receivers without endpoints (`k8s/manifests/{infra,infrastructure}/tempo.yml:65-66`), and the parked review authority has no decision. Both are named **inside** the repaired header instead of being declared closed by it. |

## Log

**L1** *(the user, 2026-10-19, verbatim)*: «que esta pendiente?» — answered read-only, and the answer is
where this unit came from: one open pull request (#426, green and `BEHIND`), one open issue (#423, the one
#426 closes), and five records whose delivery had landed while their header still described it as
outstanding.

**L2** *(the scope decision, 2026-10-19)*: the two selected options of S2. #426 was taken first and
separately, because it is a delivery of someone else's unit and not this one's: the branch was brought up
to date with `git merge --no-ff origin/main` (never a rebase — this repository's records cite hashes, and
a rebase would falsify them), merged as **`18de07d`** with a real merge commit, issue **#423** closed, and
the branch, its worktree and its herdr workspace cleaned in the order `references/worktrees.md` fixes.

**L3** *(the inventory, 2026-10-19, measured not inferred)*: the table under `## Evidence`, each row
checked with `gh` and `git` against `main` @ `18de07d` — never against the record being repaired, because
a header is what is being doubted. Five rows came from the `**Status**:` filter, two from re-reading the
lines the filter could not see, and the eighth from the post-repair re-sweep.

**L4** *(what this unit deliberately does not decide)*: the parked review authority of
`odd/tasks/tempo-receiver-endpoints.md` L13 — lineage `review-13c5e43039050a37`, frozen on the
pre-correction tree and left in `reviewing`. Its header now says the delivery landed **and** that the
authority decision is still open, which is the honest terminal state; releasing, quarantining or retiring
that lineage is an audited native operation and a maintainer's decision, and no part of this unit performs
it.

**L5** *(the eighth record, and the reason it is in scope rather than an exception)*: `angular-22-upgrade.md`
was rewritten on 2026-10-18 by PR #426 (written `ab79bea`, merged as `18de07d` the same evening, a few
hours before this sweep) — and its new line ends with `This header makes no claim about push or PR state.` while the same
paragraph already names the merge commit and the closed issue. Repairing it is not tidiness: leaving it
would have meant this unit closing the class of drift while publishing a fresh instance of it, in the
record that PR #426 had just closed for exactly this reason.

## Delivery

| Commit | Paths | What it carries |
| --- | --- | --- |
| `docs(odd)`, this commit | 9 files under `odd/tasks/` | The eight `**Status**:` repairs, their eight dated `## Header repair (2026-10-19)` blocks, and this record |

Delivered as its own pull request against `main` (issue **#434**, `type:docs`), with a real merge commit
and never a squash. This record's own `**Status**:` line is the one repair it cannot perform on itself;
the repository's answer is the one it has used twice already (`odd/tasks/angular-22-upgrade.md` for
#418, `odd/tasks/hr-menu-entry-navigates-to-hr-landing.md` for #430): the merge identity is recorded
afterwards, in the record that the delivery belongs to, by the next slice that touches it.

## Header repair (2026-10-19)

The `**Status**:` line above was rewritten on 2026-10-19 by the unit that carries issue **#454**, where the
inventory, the normalized sweep and the verification live. It used to decline the delivery state: «What remains
is the delivery of this unit as its own pull request (issue **#434**), and this record deliberately does not
name a PR number it cannot know before that pull request exists. Its own header is the one line this unit
cannot repair in the commit that carries it, because the state it would carry does not exist yet; the shape for
that is recorded in `## Delivery`.» That state became known when the work merged — PR **#436**, merge
`a6207fd`, 2026-10-19, closing **#434** — so the line now carries it. `## Delivery` is left exactly as
written: it is the frozen narrative that prescribed this repair, and its closing sentence is the instruction
this block obeys. Nothing else in this record was touched.
