# ODD feature: odd-status-headers-guard

**Repository**: LifeControl — one new file, `.github/scripts/tests/odd-status-headers.test.sh`, plus this record. **No production code, no workflow change, no edit to any existing record.** The suite is wired by the existing loop over `.github/scripts/tests/*.test.sh` inside `.github/workflows/pr-label-guard.yml`, so nothing had to be edited to activate it.
**Status**: **merged** — PR **#461** (`chore/odd-status-headers` @ `8e0eadd`, merge commit `8a53938`), 2026-10-19, closing issue **#456**, so `main` carries the suite, its ten fixtures and its wiring by the existing glob, which nothing had to edit. **No work left in this unit.**
**Created**: 2026-10-19 · **Base**: `main` @ `ab649a5` · **Branch**: `chore/odd-status-headers` · **Worktree**: `~/workspace/LifeControl-worktrees/chore-odd-status-headers` (herdr `w4V`)
**Issue**: **#456** (`status:approved` by the human on 2026-10-19), whose body carries the measurement this record implements.
**Risk**: **low.** The file is a test suite that nothing else imports and that mutates nothing: it reads `odd/tasks/*.md` and local `git`, and writes only inside its own `mktemp -d`. Its failure mode is a false *negative* (a drift it does not catch); the one real risk is the opposite, a false *positive* that fails every pull request, which is why the real-tree audit is asserted to pass before any fixture runs (`Si`).

## Why this exists

`odd/tasks/*.md` is a versioned audit trail and its `**Status**:` line is the only artifact every clone, every future session and every reviewer read. Nothing read it back: no workflow filters `odd/tasks/**` or any markdown, so a header that declines a delivery that already landed was falsified by the next merge, silently, with no commit touching the sentence that lied.

The class recurred five times, each repaired by hand: PR **#162** (14 headers), **#170**, **#364** (8), **#436** (8) and **#455** (4, issue #454). The fifth episode carries the decisive measurement: the sweep that fed #436 was **line-oriented**, and `odd/tasks/mobile-header-repair.md`'s decline was split by a line wrap — `This header makes no` ended one line and `claim about push or PR state` started the next — so the filter could not see it. A guard that reads the **paragraph** closes exactly that hole, and that failure mode is fixture 3 below.

The convention had declined to write this guard on purpose, and its stated reason is now measurably obsolete: `references/feature-records.md` says a guard *"would need a stated allowlist for the records that legitimately contain the forbidden phrases as quotations"*. Measured on `main` @ `ab649a5`: those quotations all live **outside** the first `**Status**:` paragraph — the template tables in `odd-record-convention.md`, the `BEFORE` blocks of `odd-header-hygiene.md`, `odd-status-reconciliation.md` and `odd-merged-header-reconciliation.md`, and the appended repair blocks of every repaired record. Scoping to the first paragraph removes the need for that allowlist, and `S6` is the assertion that it does.

## Specs

S1. `bash .github/scripts/tests/odd-status-headers.test.sh` exits 0 on the tree, and prints `<n> passed, <m> failed` with `m = 0`, the record count it read, and whether the ancestry half ran.

S2. For every `odd/tasks/*.md` the suite extracts the **first** `**Status**:` paragraph, collapses its whitespace, and fails when that paragraph carries declining vocabulary (`what remains is the delivery`, `makes no claim about … push`, `not pushed`, `no PR is open`, `not merged yet`, `yet to land`, `awaiting review`, `pending review`, `in progress`, `unstarted`) **and** no delivery evidence in the same paragraph (`#<number>`, or the tokens `merged`/`delivered`/`closed`/`done`/`superseded` with a commit or PR identity).

S3. It fails when a merge commit named in that paragraph is not an ancestor of `origin/main`.

S4. It says **`ancestry: skipped`** out loud when `origin/main` does not resolve — a shallow CI checkout has no such ref, and a silent skip would be indistinguishable from a pass.

S5. The suite asserts these failure modes **as its own fixtures**, each in a scratch directory, and its score states how many it asserts: (a) a first `**Status**:` paragraph that declines with no delivery evidence → **fail**; (b) the `ci-speed.md` shape — declining vocabulary *plus* `#435` in the same paragraph → **pass**; (c) a decline split across a line wrap → **fail**; (d) `implemented — …` with no terminal evidence → **fail**; (e) a paragraph naming a merge commit that is not an ancestor of `origin/main` → **fail**; (f) a paragraph naming a commit when `origin/main` does not resolve → **skipped and reported**, never silently green.

S6. The four known-quotation files stay green — `odd-record-convention.md` (template and prohibited-examples tables), `odd-header-hygiene.md`, `odd-status-reconciliation.md` and `odd-merged-header-reconciliation.md` — which is the evidence that the paragraph scoping, not an allowlist, is what makes those quotations legal.

S7. The real-tree audit passes **before** any fixture runs, the invariant `contributing-job-names.test.sh` states as Si: a false positive on `main` must not be reportable as a caught drift.

S8. The fixtures are synthetic in a scratch directory: the suite must not depend on the four records #455 repaired, or the next repair would silently weaken it.

S9. It is wired only by the existing glob: `.github/workflows/pr-label-guard.yml` is not edited. `shellcheck --severity=warning` passes on the new script, in the same pinned `koalaman/shellcheck:stable` container the guard job uses, and the suite is offline: no network, no `gh`, no token; it reads `odd/tasks/*.md` and local `git` only, and writes nothing inside the repository.

## Decisions

D1. **`implemented` is declining vocabulary, so this record's own header must carry its PR identity before the merge.** S5(d) requires `implemented — …` with no terminal evidence to fail, and this record was written while its delivery was still unknown, exactly like every other record in the directory. The first commit on the branch therefore carries a header the guard would reject, and the header is updated with `PR #<n>` in a second commit once the pull request exists — the shape `ci-speed.md` already uses and #456 itself sanctions as the legitimate hedge (S5(b)). Nothing is invented: a PR number is written only after the PR exists, which is the one thing `references/feature-records.md` forbids writing early.

D2. **The parse is scoped to the first `**Status**:` paragraph, with whitespace collapsed.** The paragraph ends at a blank line, at a Markdown heading, or at the next `**Campo**:` field line (`**Created**`, `**Risk**`, `**Branch**`, …). Collapsing is what makes fixture 3 — the line-wrapped decline #436's line-oriented filter missed — decidable at all.

D3. **The ancestry half reads only hashes a delivery marker introduces** (`merge commit`, `merged as`, `merged by`, `@`, `→`), never every backticked hex token. Measured on `main` @ `ab649a5`: the directory backticks **patch-ids** (`5ef49972f608…`) and **tree** objects (`54c5a75fc41…`, `8896dcb02c9…`, `ca4ceb38`) that no repository can resolve as commits, and it documents one commit that legitimately **is not** an ancestor — `project-conventions-skill.md` names `cec4578` as *"PR #129 was closed without merging and its commit `cec4578` never reached `main`"*. A rule that failed on any non-ancestor hash would fire on that honest sentence and on four object identities that are not commits at all. Scoping to the delivery marker keeps the rule aimed at what S3 actually says: the merge commit a header **claims**.

D4. **A delivery hash that resolves but is not an ancestor fails; one that does not resolve as a commit also fails.** Both are false claims about the same sentence. The only case that does not fail is the one S4 names: `origin/main` itself does not resolve, where the whole half is reported as skipped instead of pretending to a verdict.

D5. **The suite fails when it reads zero records.** A parser that finds no record would agree with everything vacuously, so the record count is asserted, not just printed.

D6. **One commit, not a chain — over the advisory review budget, disclosed.** The slice is 549 authored lines against the repository's advisory ~400 (`CONTRIBUTING.md` §3): 483 in the suite and 66 in this record. It stays one unit because the artifact is a **single new file with one concern**, and the ten fixtures that push it over are the issue's **own acceptance criteria** — trimming them to fit the budget would trade a stated requirement for a line count. The reviewer is told the number here rather than discovering it in the diff.

D7. **The count of claimed merge commits is asserted, not just printed.** A marker regex that matched nothing — a typo in `merge commit`, a stray backtick — would make the ancestry half report `ran` while having verified nothing, which is the same silent green S4 exists to prevent. `DELIVERY <n>` is therefore a third number in the audit's own score, and `> 0` is an assertion when the half ran.

## Tasks

| ID | Spec | Route | Unit |
| --- | --- | --- | --- |
| T1 | S1, S2, S4, S5, S9 | inline | The suite, its ten fixtures and its parser. |
| T2 | S3, S6, S7, S8 | inline | The ancestry half, the real-tree green run on the four quotation files, and the out-of-fixture probe on a scratch copy. |
| T3 | D1 | inline | Update this header with `PR #<n>` before the merge, and record the delivery in the evidence log. Landed: `8e0eadd` named `PR #461`, and `8a53938` carried it to `main`. |

## Evidence log

| Date | Evidence |
| --- | --- |
| 2026-10-19 | Calibration before writing the suite, on `main` @ `ab649a5`: **75** records read; **one** carries declining vocabulary in its first `**Status**:` paragraph (`ci-speed.md`, `makes no claim about live push`) and that one carries `#435`/`#443`/`#445` in the same paragraph, which is S5(b)'s pass shape. The other 74 carry none, so the guard is born green for the right reason. |
| 2026-10-19 | Same pass, ancestry: every hash behind a delivery marker resolves as a commit and is an ancestor of `origin/main`. The tokens that do **not** resolve are exactly the four object identities D3 names, none of them behind a delivery marker. |
| 2026-10-19 | **S7/Si run on the anchor (`main` @ `ab649a5`, 75 records, this record absent): `20 passed, 0 failed`, `checked 75 record(s); 111 claimed merge commit(s); ancestry: ran`, `10 fixture(s) asserted`.** The real-tree audit is green before any fixture runs, so a false positive cannot be reported as a caught drift. |
| 2026-10-19 | Run on this branch (76 records, this one included and still pre-PR): `19 passed, 1 failed`, and the only `PROBLEM` line is **this record** — `declines the delivery ... (phrases: implemented)`. That is D1 happening, not a defect: the guard caught a header written before its delivery exists. |
| 2026-10-19 | Out-of-fixture probe, on a `mktemp -d` copy of the real `odd/tasks/` (no fixtures, no synthetic tree): the original decline of `mobile-header-repair.md` — *"the three header repairs are applied and nothing in this slice is open. This header makes no / claim about push or PR state"*, split by a line wrap exactly as #436 left it — was reintroduced, and the guard reported that record **by name**. Replacing this record's header with one naming `PR #9999` cleared it. The scratch tree was removed. |
| 2026-10-19 | `docker run --rm -v "$PWD:/mnt" -w /mnt koalaman/shellcheck:stable --severity=warning .github/scripts/tests/odd-status-headers.test.sh` → exit 0. `shellcheck` is not installed on the host; this is the same pinned container `.github/workflows/pr-label-guard.yml` uses. |
| 2026-10-19 | **A defect in the rule's own first draft, found only by the real tree.** `declining_phrase` piped `grep` into `head`, so the condition tested `head`'s status and entered the branch on **every** record; two real records (`docker-images-rebuild.md`, `odd-stale-header-reconciliation.md`) were reported with an empty phrase. Every fixture passed while that was true, because each fixture is either clearly declining or clearly hedged. Fixed with an explicit non-emptiness test, and the two records are green now. This is the measured argument for S7: the fixtures could not see it. |
| 2026-10-19 | `b5dc080` holds the suite (483 lines, mode 0755) and this record (66 lines). |
| 2026-10-19 | **Delivered: PR #461 merged to `main` as `8a53938` at 16:44:11Z, and issue #456 closed as completed at 16:44:13Z.** Both required contexts of `main`'s protection — `Exactly one type:* label` and `A linked, approved issue`, the two jobs of `pr-label-guard.yml` — reported `completed/success` on the head `8e0eadd`, and the audit ran as part of that job's `Self-tests` step, because the existing glob is the wiring. |
| 2026-10-19 | Post-merge run on `main` @ `8a53938`, the guard now auditing its own record: **`20 passed, 0 failed`**, `checked 76 record(s); 111 claimed merge commit(s); ancestry: ran`, `10 fixture(s) asserted`. The delivery did not change the numbers the guard reports except the record count, which is what S7 asks of it. |
| 2026-10-19 | One platform defect, recorded because it cost a re-run and is not this diff's: the head's first successful run left the `A linked, approved issue` **check-run stuck `in_progress`** although the workflow run itself had completed `success`, so the pull request read `BLOCKED` with the check at `pending`. `gh run rerun` recreated both check-runs and they closed `completed/success`, after which the pull request read `MERGEABLE CLEAN`. No protection was bypassed. |

## Log

L1. *"si"* — the human's authorization, answering the question *"¿Arranco #456 en un worktree propio?"* after the pending-work survey of the session. The unit is the implementation of issue **#456**, which the human had opened and approved earlier the same day; its body is the specification quoted in `## Specs`.

L2. The delivery route was decided by the human in the same exchange: **push, pull request and merge while every check is green**, so the merge is authorized rather than assumed. T3 is the consequence — the header names `PR #461` in a second commit, because D1 makes a header written before its pull request exists unrepresentable in a green run.

L3. The delivery closed on 2026-10-19: `8a53938` on `main`, issue #456 completed, and this header carrying the merge identity. It used to read *"implemented — PR #461 carries the guard … the delivery evidence belongs to the evidence log"*, which was true while the delivery was unknown and became a stale state the moment the merge commit existed. Moving it is the same repair #455 performed for four other records, and leaving it would be this guard's own failure mode applied to its own record.
