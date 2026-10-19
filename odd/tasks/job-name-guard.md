# ODD feature: job-name-guard

**Repository**: LifeControl — one new file, `.github/scripts/tests/contributing-job-names.test.sh`, plus this record. **No production code, no workflow change, no `CONTRIBUTING.md` change.** The suite is wired by the existing loop over `.github/scripts/tests/*.test.sh` inside `.github/workflows/pr-label-guard.yml`, so nothing had to be edited to activate it.
**Status**: **merged** — PR **#443** (`chore/job-name-guard` @ `b475231`), merge commit `e461af7`,
2026-10-19, closing issue **#437**, so `main` carries the suite and its fixtures, wired by the existing loop
over `.github/scripts/tests/*.test.sh`. No work left in this unit.
**Created**: 2026-10-19
**Risk**: **low.** The file is a test suite that nothing else imports and that mutates nothing: it reads `CONTRIBUTING.md` and `.github/workflows/*.yml` from the working tree and exits. Its failure mode is a false *negative* (a drift it does not catch), which is bounded by its fixtures, not by anything it can break. The one real risk is the opposite: a false *positive* that fails every pull request, which is why the real-tree audit is asserted to pass on `main` before the fixtures run.

## Why this exists

`CONTRIBUTING.md` §6 quotes each workflow job's `name:` **verbatim**, and states why: *"Each job's `name:` is quoted verbatim because the branch-protection runbook below names required checks by exactly those strings."*

On 2026-10-19, PR #435 turned `api-ci.yml`'s single `Check` job into `Static analysis` and `Test`, and `CONTRIBUTING.md` §7 kept asserting that `api-ci.yml` and `gateway-ci.yml` *"share the job name `Check`"*. Nothing could see it: every component workflow filters by `paths:` and **no workflow reads markdown**. A human reader caught it in review, not a gate.

The `paths:` half of that same problem already has a reader — `.github/scripts/tests/workflow-trigger-parity.test.sh` — and the documentation half had none. This is the documentation half.

## Specs

S1. `bash .github/scripts/tests/contributing-job-names.test.sh` exits 0 on the tree, and reports how many suites (here: workflows and jobs) it compared.

S2. The four required failure modes are asserted **as fixtures**: a job renamed in a workflow with §6 untouched; a name in §6 that no workflow declares; a workflow added or removed while the table stays put; and a row whose names differ from the workflow's jobs. The suite's own score states how many it asserts.

S3. Renaming one job `name:` in a workflow **without** touching `CONTRIBUTING.md` makes the suite fail, proven out-of-fixture against a copy, with the probe not committed.

S4. It is **not** wired by editing `.github/workflows/pr-label-guard.yml`; the existing loop over `.github/scripts/tests/*.test.sh` runs it. `shellcheck --severity=warning` passes on the new script, and the suite is offline: no network, no `gh`, no token.

## Tasks

| ID | Spec | Route | Unit |
| --- | --- | --- | --- |
| T1 | S1, S2, S4 | `gentle-ai-worker` (delegated under the context backstop) | The suite and its 11 fixtures. Landed as `d8d50c1` |
| T2 | S1, S3, S4 | inline | Independent verification on the parent: the suite's real-tree audit, `shellcheck` in the same pinned container CI uses, and a scratch-copy rename probe |

## Decisions

D1. **One pull request, not a chain — over the advisory review budget, disclosed.** The slice is 562 authored lines against the repository's advisory ~400 (`CONTRIBUTING.md` §3). Measured: 410 lines of code, 100 comment lines and 52 blank against the largest existing sibling suite (`workflow-trigger-parity.test.sh`) at 233 code / 333 total. It stays one slice for three reasons, and the reviewer is told all three: the artifact is a **single new file** with one concern; the fixtures that push it over are the issue's **own acceptance criteria**, so trimming them to fit would trade a stated requirement for a line count; and a chain of "the guard" + "its fixtures" would not reduce what the reviewer reads, since both halves are the same file's structure. The alternative — dropping fixtures — was rejected as a requirement change disguised as formatting.

D2. **Two parser traps are fixtures, not comments.** A §6 cell splits on **backticked segments**, never on commas: `Lint, Build & Test` is one job whose name carries commas, while "`Static analysis`, `Test`" is two. Splitting on commas would read the first as two names and pass on a lie. And a job that declares no `name:` takes its id. Both have fixtures, because a parser this guard gets wrong passes silently.

D3. **The parse is scoped to §6's table alone** — from the `## 6.` heading to the next Markdown heading. §7 quotes the same strings again, and reading it as the contract would make the guard compare the rule against its own restatement.

D4. **The comparison is symmetric.** `workflow -> §6` and `§6 -> workflow` are both reported, so a rename fails in both directions instead of one. A duplicate name in §6 is a count mismatch and fails; order is ignored.

Si. **The guard is not allowed to fail for the wrong reason.** Its real-tree audit must pass on the base commit before any fixture runs, so a false positive cannot be mistaken for a caught drift.

## Evidence log

| Date | Evidence |
| --- | --- |
| 2026-10-19 | Suite on the real tree: exit 0, `21 passed, 0 failed`, `11 fixture(s) asserted`, and `checked 5 workflow(s) and 7 job(s); every pair compared in both directions`. |
| 2026-10-19 | `docker run --rm -v "$PWD:/mnt" -w /mnt koalaman/shellcheck:stable --severity=warning .github/scripts/tests/contributing-job-names.test.sh` → exit 0. Run by the parent because `shellcheck` is not installed on the host; this is the same pinned container `.github/workflows/pr-label-guard.yml` uses. |
| 2026-10-19 | Out-of-fixture probe, run by the parent against a `mktemp -d` copy of `CONTRIBUTING.md` + `.github/workflows/*.yml` with `name: Static analysis` renamed to `name: Static analysis renamed`: **exit 1**, reporting both directions (`declares job name 'Static analysis renamed' that §6 does not name` and `does not declare job name 'Static analysis' that §6 names`). The scratch tree was removed and `git status --porcelain` showed only the new untracked file. |
| 2026-10-19 | `d8d50c1` holds T1 (1 file, 562 insertions, mode 0755). |

## Log

L1. *"apruebalos y continua"* — approval of issue #437 among the four, and the go-ahead to continue with the agreed order (this guard first, because it then binds U3's changes to `angular-ci.yml`'s job structure).

L2. The unit was delegated under the context backstop; the writer returned the suite, the parent ran the checks that need `docker`, and the two are recorded separately above.

L3. Slice-boundary decision recorded as D1 after the commit tripped the review-budget notice.

## Header repair (2026-10-19)

The `**Status**:` line above was rewritten on 2026-10-19 by the unit that carries issue **#454**, where the
inventory, the normalized sweep and the verification live. It used to decline the delivery state: «the guard
and its fixtures are in `d8d50c1` on `chore/job-name-guard`, and nothing in this unit is deferred. This header
makes no claim about push or PR state; see the evidence log.» That state became known when the work merged —
PR **#443**, merge `e461af7`, 2026-10-19, closing **#437** — so the line now carries it. `d8d50c1` is not
lost: the evidence log already holds it as the commit that landed T1, which is what it was, while the branch's
head at the merge was `b475231`. Nothing else in this record was touched.
