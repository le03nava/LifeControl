# ODD feature: pr-label-guard-cancelled-check-runs

**Repository**: LifeControl — `.github`: the `concurrency` block of `pr-label-guard.yml`, the
comments that justify it, and the parity suite that freezes the workflow's triggers. No component
source is touched.
**Issue**: **#410** (`status:approved`), whose body carries the full measurement.
**Branch**: `fix/cancelled-required-check-runs`, cut from `main` @ `ee1ca8f`.
**Test runner**: `.github/scripts/tests/*.test.sh` — offline bash, no network, no `gh`, no token —
plus `shellcheck --severity=warning` on the modified script.
**Status**: **merged** — PR **#412** (`fix/cancelled-required-check-runs` @ `144592d`), merge commit
`58cec5e`, 2026-10-18, closing issue **#410**: the guard queues a newer event instead of cancelling
the running run, so no check run is cancelled and the rollup can no longer read `FAILURE` for a
commit whose protected contexts passed. One limit stays unmeasured and is declared in the workflow —
`L4` states the same limit in its own words — and closing it is not this unit's work.
**Created**: 2026-10-18 · **Risk**: **item 5** (delivery pipeline: this workflow carries the two
protected status contexts that gate every pull request)

## Specs

S1. A pull request whose two required contexts have succeeded is mergeable. Creating one with
    `gh pr create --label …` — the procedure `references/labels.md` documents — must leave **no**
    check run with `conclusion: cancelled` on its head commit, and `mergeStateStatus` must read
    `CLEAN` once both contexts pass.
S2. The reason the `concurrency` block exists survives: two label events in a row still leave one
    verdict per pull request, because the verdict is read from the pull request through the API at
    run time — the last run to complete reports the current label set.
S3. A pull request labelled **after** it was opened is still re-evaluated: `labeled` stays in
    `on.pull_request.types`.
S4. A genuine failure of either gate still leaves the pull request blocked; the offline suites
    keep asserting the gate scripts' own failure modes.

## Tasks

| ID | Spec | Route | Commit |
| --- | --- | --- | --- |
| T1 | S1, S2, S3 | inline worktree + `gentle-ai-worker` for the edit and the checks; the RED observed before the fix | `cancel-in-progress: false`, the two comment rewrites, and two new assertions in `workflow-trigger-parity.test.sh` |

## Evidence log

**L1** *(the decision, verbatim from the user, 2026-10-18)*: «Recomendado: cancel-in-progress false
+ test» — over the two alternatives I priced: documenting `gh pr edit --add-label` as the way to
apply the label (cheap, leaves the trap alive for anyone who labels within seconds of opening), and
doing both. The defect itself is issue **#410**, opened and approved in the same turn.

**L2** *(the defect, measured on pull request #409, 2026-10-18)*: `gh pr create --label type:docs …`
fired two `pull_request` runs of this workflow in the same second — `39089758364` cancelled,
`39089758365` success. The trigger list includes `labeled` and the branch had been pushed before the
pull request existed, so the only pair that fits is `opened` + `labeled`. Because
`cancel-in-progress: true` kills the *running* run, the head commit `959df52` ended with **four**
check runs, `2 cancelled + 2 success` (the cancelled pair had a real `started_at`), and GitHub
reported `statusCheckRollup: FAILURE` / `mergeStateStatus: BLOCKED` while `gh pr checks` showed both
protected contexts `pass`. Pushing any new commit cleared it — measured on `c7c5b5c`: one run, two
check runs, both success, rollup `SUCCESS`, `CLEAN`. The base branch had not moved, so `strict` was
not the cause. **Stated as a limit rather than explained**: pull request #407 was created the same
way and read `CLEAN` with the identical 2 + 2 structure; which of two same-second check suites
GitHub counts is not measured by this unit, and no text in this record or in the workflow claims to
explain it.

**L3** *(the fix and its RED, 2026-10-18)*: the new section of
`.github/scripts/tests/workflow-trigger-parity.test.sh` — *the label guard queues instead of
cancelling* — was observed **RED first**, on the workflow as it stood: `24 passed, 1 failed`,
`exit 1`, the failing case being
`pr-label-guard.yml does not cancel its own run (…)`, with the sibling assertion
`pr-label-guard.yml still triggers on labeled` already passing, which is what proves the parser
reads the list instead of returning nothing. `cancel-in-progress: true` → `false` (the `group:` is
unchanged) turns it **GREEN**: `25 passed, 0 failed`, `exit 0`, and the 23 pre-existing assertions
are byte-identical across the baseline, RED and GREEN runs. The `cancel-in-progress` search is
scoped to the `concurrency:` block by an awk state machine that skips comment lines, because the
key is explained in a comment right above itself and a whole-file substring match would read the
explanation as a declaration. The other six suites exit `0`, and
`shellcheck --severity=warning` over the modified script — run through the image CI pins,
`koalaman/shellcheck:stable` — exits `0`.

**L4** *(the one limit this unit does not close)*: with `cancel-in-progress: false` a newer event
**queues** behind the running one, so the running run is never killed. A pending run replaced by a
newer pending run in the same group is GitHub's own behaviour and is **not** measured here; the
workflow comment says so in the same words. Closing it would need a run of its own, not an
assertion this suite can make offline.

## Header repair (2026-10-19)

The `**Status**:` line above was rewritten on 2026-10-19 by `odd/tasks/odd-stale-header-reconciliation.md` (issue **#434**), where the inventory and the verification live. It used to decline the delivery state: «This header makes no claim about push or PR state; see the evidence log.». That state became known when the work merged, so the line now carries it — PR **#412**, merge `58cec5e`, 2026-10-18 — and it names the issue the unit closed (**#410**). Nothing else in this record was touched.
