# ODD feature: pr-label-guard-stale-payload

**Repository**: LifeControl — `.github/workflows/pr-label-guard.yml` (where the label set is read from, and one
read-only scope), `.github/scripts/tests/label-artifacts.test.sh` (three new cases and the suite's fourth declared
failure mode), and the two documentation artifacts that describe the gate:
`.agents/skills/project-conventions/references/labels.md` and the record this defect falsified,
`odd/tasks/pr-label-enforcement.md`. No application code, no schema, no endpoint, no dependency, no consumed
contract, no stored data.
**Status**: **merged** — PR **#403** (`fix/pr-label-guard-stale-payload` @ `b2d2ec6`), merge commit
`198b416`, 2026-10-18, closing issue **#397**. The fix: the guard reads the label set from the pull
request itself, through the API, at the moment the job runs, and the workflow declares the read-only
`pull-requests: read` scope that read needs. The offline suite that freezes this answers **24 passed, 0 failed**
(`exit 0`), and its three new cases answer **3 failed** against the previous workflow (**RED observed first**,
`21 passed, 3 failed`, `exit 1`). No script changed, so `shellcheck --severity=warning` over
`.github/scripts/*.sh` is untouched. What this record leaves declared rather than closed is `S7`: the
guard is still not a branch-protection required check. The clause in `pr-label-enforcement.md` that said
a Dependabot pull request still needs a human to add `type:chore` is kept as written there and
superseded by the repair that record carries.
**Created**: 2026-10-17 · **Base**: `main` @ `e7ba78c` · **Branch**: `fix/pr-label-guard-stale-payload` ·
**Worktree**: `~/workspace/LifeControl-worktrees/fix-pr-label-guard-stale-payload` (herdr workspace `w3Y`) ·
**Issue**: **#397**
**Risk**: **item 5** (delivery pipeline: this is a workflow that gates every pull request, and the change decides
what input its verdict is computed from) and, in the narrow item 3 sense, it changes what every future pull request
is judged by — a requested change to shared code, not an unrequested break of it. Rollback is one commit: the
workflow file reverts to the payload read and the three test cases revert with it. No stored data, no secret, no
concurrency primitive of the application, no application contract.

## Specs

**S1** — The user's authorization, verbatim: *«ok si dale al 1»*.

**S2** — The unit that string picks, verbatim as it was offered: *"el fallo de la guard en #392 y el header stale de
`pr-label-enforcement` son la misma unidad — arreglar el falso negativo y reconciliar el header, y recién ahí mergear
#392"*. Both halves are in scope, and the merge of `#392` is the last step, not a separate authorization.

**S3** — The user's layout decision, verbatim: *«en un worktree separado»* — one branch, one worktree, per
`CONTRIBUTING.md` §4 and `.agents/skills/project-conventions/references/worktrees.md`.

**S4** — The flow that governs the delivery is this repository's own, not this record's: an issue before the code
(`CONTRIBUTING.md` §1 — created as **#397**, and `status:approved` is a human decision that must exist **before** the
pull request is opened), one `type:*` label (§2), the ~400-line review budget (§3), the worktree (§4), this record
(§5), the gates (§6), and a merge commit, never a squash (§7–§8).

**S5** — The defect is defined by measurement, not by inference. The guard's verdict was computed from
`github.event.pull_request.labels.*.name` — the **snapshot** the triggering event carried. On pull request **#392**
(Dependabot, `chore(deps): bump com.github.spotbugs from 6.5.12 to 6.5.13`) that snapshot was empty while the pull
request was mergeable:

- `opened` 2026-10-17T22:39:58Z, `labeled dependencies` 22:39:59Z, `labeled type:chore` 22:40:00Z — three events,
  three runs, and `.github/dependabot.yml` declares both labels itself, so the beat between them is the machine's
  normal behaviour and not an anomaly.
- Runs `39075999612` and `39076000058` — the two `labeled` events — are **`cancelled`**, and
  `concurrency: cancel-in-progress: true` is why: the surviving run was the one carrying the pre-label snapshot.
- The surviving run, `39076000874`, failed at 22:40:14Z with `PR_LABELS: []` and the guard's message `FAIL: no
  type:* label on this pull request`, **14 seconds after the labels existed** — the state it needed was available
  and it did not read it.
- `gh run rerun` cannot clear it: a re-run replays the stored payload. No further event is coming for a Dependabot
  pull request that is already labelled, so the red verdict was permanent.

**S6** — The fix is where the truth comes from, not a retry and not a longer trigger list: the job reads the pull
request's current labels (`gh api repos/${GITHUB_REPOSITORY}/pulls/${PR_NUMBER} --jq '[.labels[].name]'`) with the
read-only `pull-requests: read` scope, so every run of every event computes the same verdict from the same state and
`cancel-in-progress` can no longer make a snapshot outlive the events that carried it. The failure modes the gate
must keep refusing — zero `type:*`, two, off-vocabulary, an unreadable set — are unchanged and stay frozen by
`check-pr-type-labels.test.sh`.

**S7** — What this unit deliberately does **not** do: it does not make the guard a branch-protection required check
(the other gap `references/labels.md` declares, and a repository-admin action); it does not change the `issue` job's
body read, which is event data whose own `edited` trigger makes it current; it does not touch a script.

## Tasks

| ID | Spec | Task | Commit |
| --- | --- | --- | --- |
| T1 | S1, S4 | Open the issue that states the defect and its acceptance criteria (**#397**) | — (issue, not a commit) |
| T2 | S3, S4 | Branch and worktree `fix/pr-label-guard-stale-payload` off `main` @ `e7ba78c` | — |
| T3 | S5, S6 | RED: three cases in `label-artifacts.test.sh` pinning the label source from the pull request, the API read, and the `pull-requests: read` scope | `b2d2ec6` (`fix(pr-labels)`, one commit with T4) |
| T4 | S6 | GREEN: the workflow reads the label set through the API and declares the scope | `b2d2ec6` (`fix(pr-labels)`, one commit with T3) |
| T5 | S2, S4 | Repair the falsified clause in `pr-label-enforcement.md` and describe the read in `references/labels.md`, this record included | `docs(odd)`, this commit |
| T6 | S2, S4 | Delivery: pull request against `main`, the two gate jobs green, merge commit, and then the fresh event that clears #392 | — |

## Log

**L1** *(the user, verbatim)*: «que esta pendiente=?» — the inventory that produced this unit's opening; the guard
reading a stale payload was one of its rows, measured before it was offered.

**L2** *(the user, verbatim — `S1`, `S2`)*: «ok si dale al 1» — the fix and the header repair as one unit, and the
merge of #392 last.

**L3** *(the user, verbatim — `S3`)*: «en un worktree separado».

**L4** *(why the issue is not optional, and why it is not this record's to approve — `S4`)*: `CONTRIBUTING.md` §1 and
`.github/ISSUE_TEMPLATE/bug.yml` require the problem, the expected outcome and observable acceptance criteria before
any code, and `status:approved` is applied by a human, not by the author of the request. The issue's own acceptance
criteria are the ones this record's tasks execute, including the one that can only be verified on the pull request
that carries them: a fresh event on #392 answering `success` with no commit added to that branch.

**L5** *(RED before GREEN, and what it can and cannot prove — `S5`, `S6`)*: the three new cases are structural
assertions over the workflow file — the guard's input is not the payload, it is read through the API, and the
workflow declares the read-only scope that read needs — and they answered **21 passed, 3 failed, exit 1** against the
untouched workflow. A workflow's runtime behaviour cannot be exercised offline by a shell suite, so the *behavioural*
half of this fix is proven where it can only be proven: on the pull request's own run, in `T6`. What the offline
cases do prove is the invariant that was violated and that a future edit could violate again, which is the same
division of labour `label-artifacts.test.sh` already uses for the `paths:` filter.

**L6** *(the fix, in the diff's own terms — `S6`)*: two additions and one replacement of input in
`.github/workflows/pr-label-guard.yml`. The step now takes `GH_TOKEN` and `PR_NUMBER` and reads
`repos/${GITHUB_REPOSITORY}/pulls/${PR_NUMBER}` through `gh api --jq '[.labels[].name]'`, feeding the same
`--labels-json` interface of the unchanged guard script. `permissions` gains `pull-requests: read` and keeps
`contents: read` and `issues: read` — read-only, no write scope. `cancel-in-progress: true` stays, with the reason
recorded in the file: one verdict per pull request is safe exactly because the verdict now reads the pull request
instead of the event that started the run.

**L7** *(the header this unit repairs, and the shape of the repair — `S2`)*: `odd/tasks/pr-label-enforcement.md`
declared, as one of two remaining gaps, that *"a Dependabot pull request still needs a human to add `type:chore`"*.
That clause is false in the landed tree twice over: `.github/dependabot.yml` declares `labels:` with `dependencies`
and `type:chore` for every `updates[]` entry (and `label-artifacts.test.sh` fails if one drops them), and Dependabot
applied them by itself on #392 — `gh api repos/le03nava/LifeControl/issues/392/timeline` shows both `labeled` events
by `dependabot[bot]`. What actually failed was the guard's read. The repair follows the shape this repository already
uses for a frozen narrative: the sentence is **kept as written and superseded in place**, with the repair recorded in
that record's own log rather than the earlier text erased, because the falsified sentence is itself evidence of the
drift class this repository keeps measuring. The record's second declared gap — the guard not being a
branch-protection required check — is untouched and stands as declared.

**L8** *(verification, 2026-10-17 — every number measured, not asserted)*: after the workflow change,
`.github/scripts/tests/label-artifacts.test.sh` answers **24 passed, 0 failed** (`exit 0`), and its four cases about
the workflow — no `paths:` filter, runs the script, `labeled` is a trigger, reads the pull request rather than the
payload — all pass. `check-pr-type-labels.test.sh` and the other suites are unchanged and re-run in `T6` by the
guard's own `Self-tests` step. `shellcheck --severity=warning` over `.github/scripts/*.sh` is unaffected: no `.sh`
file changed. The behavioural acceptance criterion is the last one to be measured, on the pull request that carries
this change and then on #392.

## Header repair (2026-10-19)

The `**Status**:` line above was rewritten on 2026-10-19 by `odd/tasks/odd-stale-header-reconciliation.md` (issue **#434**), where the inventory and the verification live. It used to decline the delivery state: «This header makes no claim about push, PR or merge state». That state became known when the work merged, so the line now carries it — PR **#403**, merge `198b416`, 2026-10-18 — and it names the issue the unit closed (**#397**). The rest of the sentence the clause belonged to survives in place: the clause in `pr-label-enforcement.md` is still kept as written there and superseded by the repair that record carries. Nothing else in this record was touched.
