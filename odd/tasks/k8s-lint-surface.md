# ODD feature: k8s-lint-surface

**Repository**: LifeControl — `k8s/kind/`, `.github/workflows/pr-label-guard.yml`, `.github/scripts/tests/workflow-pins.test.sh`
**Branch**: `chore/k8s-shellcheck-surface` · **Worktree**: `~/workspace/LifeControl-worktrees/chore-k8s-shellcheck-surface`
**Issue**: #955 — `chore(ci): no shellcheck glob covers k8s/, and two of its three scripts have no shebang`
**Status**: implemented — the two shebangs, the widened glob and its freeze are the branch's content, and the three shell files under `k8s/kind/` are clean under the pinned linter. Nothing is deferred. The documentation half of the same measurement is issue #957 item 5 and is not this unit's.
**Created**: 2026-11-17
**Risk**: item 5 (CI). The lint surface is delivery infrastructure, and the failure mode is silent: a glob that stops naming a root leaves its files unlinted while the job reports green. That is what #955 measured across two scripts, and it is why the glob is frozen in a suite rather than left as a line in a step. What bounds it: no image, dependency or trigger changes, and the freeze fails on the narrow shape — the pre-change glob is a fixture that must be rejected. Not item 1 (no stored data), not item 2 (no role, guard or permission), not item 3 (no route, flag or export changes; the guard's `paths:` absence is asserted by a suite and stays), not item 4 (no concurrency), not item 6 (the freeze is the test that would catch the regression).

## Problem

`k8s/kind/` holds three shell scripts and no lint glob names any of them. The two surfaces stop short of it: the integrity job globs `docker/scripts`, and the guard globs `.github/scripts` plus `.githooks`. The scripts are executed — `create-kind-cluster.sh` runs `./kind-load.sh` — so a defect in them is a defect in a clone, and the two missing shebangs had no reporter.

## Specs

- **S1 — both scripts carry a shebang, and all three are clean under the linter the pipeline pins.** `create-kind-cluster.sh` and `delete-kind-cluster.sh` open with `#!/usr/bin/env bash`, the line `kind-load.sh` already carried, and `docker run … koalaman/shellcheck:v0.11.0 --severity=warning` over the three files exits 0. The version and severity are the pipeline's, not a choice of this unit: a clean verdict under a different linter is not the claim.
- **S2 — `k8s/` is covered by a glob.** One of the two steps names it. The guard is the one chosen, and the reason is in the workflow: it declares no `paths:` filter, so it runs on **every** pull request including one that touches only `k8s/`; the integrity job filters on `docker/**` and two build files, so widening its glob alone would cover `k8s/` only on pull requests that already touch docker.
- **S3 — the glob is frozen, and the freeze rejects the shape that was wrong.** `.github/scripts/tests/workflow-pins.test.sh` reads the `mapfile -t scripts` line of every workflow and fails naming each frozen surface — `.github/scripts`, `.githooks`, `docker/scripts`, `k8s` — that no glob covers. Which step covers a root is not the contract; that some step does is. The pre-change glob is a fixture the freeze must reject, so a reader that matched nothing cannot print the same green line as one that matched everything. A root named only in a comment does not count, because a comment does not run.

The issue's own boundary: **no image is upgraded and no behavior changes.** No `FROM`, no `uses:`, no `run:` gains a step.

## Tasks

| ID | Specs | Task | Evidence |
| --- | --- | --- | --- |
| T1 | S1 | The shebang on the two scripts, with `delete-kind-cluster.sh` also gaining the newline it never had | pid `5a116e74770b` |
| T2 | S3 | The freeze in `workflow-pins.test.sh`, observed RED first: `no shellcheck glob covers k8s` against the unmodified workflow | pid `5a116e74770b` |
| T3 | S2 | The glob widened in the guard's step, with the comment stating why this step and not the other; T2 turns green | pid `5a116e74770b` |
| T4 | S1–S3 | The three k8s scripts under the pinned linter, the sixteen guard suites, and this record | this record |

T1–T3 are one commit: the shebang is what the widened glob would have reported, so shipping either half alone leaves the tree in a state the other half exists to prevent.

## Log

**L1 — the request.** «puedes hacer el issue 957 y 955»

**L2 — the glob choice, and why it is not a coin flip.** The issue leaves it open — «one of the two globs widened». The guard wins on a measured property of the two workflows: `pr-label-guard.yml` declares no `paths:` filter, so it runs on every pull request, while `docker-build-integrity.yml` filters on `docker/**`, `api-gateway/Dockerfile`, `api-gateway/build.gradle`, `life-control-api/Dockerfile`, `life-control-api/build.gradle` and its own file. Widening the integrity job would have meant adding `k8s/**` to both of its `paths:` blocks and would still leave the lint conditional on a path a k8s-only change never touches. The alternative was also rejected because the freeze already lives in the guard's job (`workflow-pins.test.sh`), and a freeze in one job over a glob in the other is a suite that reports on a file CI does not use.

**L3 — the freeze is a surface list, not a per-step assertion.** The first shape considered asserted that *both* workflows name the same roots, which is false: the integrity job names `docker/scripts` and nothing else, and the guard names the rest. The invariant that is actually true — and that #955 is an instance of — is that **every** root is named by **some** glob. So the vocabulary is the list of roots, the reader unions every workflow's `mapfile` line, and the finding is per uncovered root. `docker/scripts` is in the frozen list for the same reason `k8s` is: until now nothing would have caught either being dropped.

## Evidence log

Measured on 2026-11-17 in `~/workspace/LifeControl-worktrees/chore-k8s-shellcheck-surface`, branched from `origin/main` at `0b733e54`.

| # | Spec | Measurement | Result |
| --- | --- | --- | --- |
| E1 | S3 | `./.github/scripts/tests/workflow-pins.test.sh` **before** the glob widened | 15 passed, **1 failed**: `FAIL: no shellcheck glob covers k8s, so a shell file under it runs unlinted`. The narrow-glob fixture was already rejected on the same run, so the reader was reading. |
| E2 | S2, S3 | the same suite **after** the glob widened | 16 passed, 0 failed, including `PASS: every frozen lint surface is named by a shellcheck glob (.github/scripts .githooks docker/scripts k8s)` |
| E3 | S1 | `docker run --rm -v "$PWD:/mnt" -w /mnt koalaman/shellcheck:v0.11.0 --severity=warning k8s/kind/create-kind-cluster.sh k8s/kind/delete-kind-cluster.sh k8s/kind/kind-load.sh` | exit 0, no finding. The same command over the **base** versions of the three files (read out of `origin/main` into a scratch directory) exits 1 with `SC2148` on `create-kind-cluster.sh` and on `delete-kind-cluster.sh` and nothing on `kind-load.sh`, which is the measurement #955 recorded. |
| E4 | S2 | every suite under `.github/scripts/tests/` | 16 suites, all exit 0: `check-issue-reference` 72, `odd-status-headers` 78, `worktree-anchor` 38, `issue-templates` 34, `retro-label-prs` 33, `odd-citations` 29, `workflow-trigger-parity` 28, `check-pr-type-labels` 26, `label-artifacts` 24, `contributing-job-names` 21, `vocabulary-source` 17, `adr-migration-citations` 14, `angular-formatting-gate` 14, `gradle-pins` 13, `adr-line-citations` 11, `workflow-pins` 16 |

Provenance by **content**, per rule 8 — no branch SHA stands as evidence here:

| Subject | `git patch-id --stable` |
| --- | --- |
| the shebangs, the freeze and the widened glob | `5a116e74770bc6f614589a8417991313f98aa66e` |

**What these checks do not prove.** `docker-build-integrity.yml` was not run: it is untouched, and the freeze reads it from the tree. No stack was started, so nothing here proves the kind scripts still create a cluster — T1 added a line above the first command and changed no command, and the claim is the linter's verdict, not a live cluster.
