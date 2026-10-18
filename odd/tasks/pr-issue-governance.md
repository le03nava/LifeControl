# ODD feature: pr-issue-governance

**Repository**: LifeControl — repository governance: issue templates, an issue-reference gate, the `type:*`
vocabulary single source, Dependabot labels, `CONTRIBUTING.md`, `CODEOWNERS`, and the CI trigger alignment.
No `life-control-api/` or `life-control-app-angular/` runtime source is touched.
**Branch**: `chore/pr-issue-governance`, cut from `main` @ `5b2c7ee`; every commit it holds is an ancestor
of `main` after the chain merged, verified with `git merge-base --is-ancestor` over all thirteen of them.
**Delivery**: `chain_strategy=feature-branch-chain` — six pull requests, each retargeted to `main` as its
parent landed: #385 (`S1`) | #386 (`S2`) | #387 (`S4+S5`) | #388 (`S6+S3`) | #389 (`S7`) | #390 (the
verification corrections).
**Test runner**: `.github/scripts/tests/*.test.sh` and `docker/scripts/tests/*.test.sh` — offline bash
suites, run from the worktree; `shellcheck --severity=warning` on modified scripts.
**Status**: merged — PRs **#385–#390** (`de408b3`, `ba56674`, `199155b`, `e64d9f0`, `55ad71f`, `9c7c4c9`),
2026-10-17, plus the follow-ups the work produced: the record slice #394, the Dependabot-exemption fix #396, the
retroactivity note #399, the closing-keyword boundary #402 and the label-set fix #403. Branch protection is
applied (`L20`) and the native review of the merged range ran (`L19`). No work left in the repository.
**(Repair, 2026-10-17: that last sentence was false the moment it was written — this chain's own `S7` left
`AGENTS.md` and the parity suite's header comment asserting the trigger asymmetry `S7` had just removed, and
nothing read either against the workflows. Repaired by the unit recorded in `L21`; the sentence is kept as
written and superseded here rather than erased.)**
**Created**: 2026-10-17
**Risk**: **high.** Two slices change what CI does at merge time (Task Size item 5: `S2` adds a merge-blocking
gate and an `issues: read` token scope; `S7` changes workflow triggers) and `S3` decides who must review what.
`S1`, `S4`, `S5`, `S6` are repository metadata and documentation only.

## Specs

Measured baseline this feature acts on (2026-10-17, remote reads only):
`main` is **not protected** (`gh api repos/le03nava/LifeControl/branches/main/protection` → `404 Branch not
protected`); of the last **60 merged PRs, 55 carry no issue reference** (1 `Refs`, 4 closing keywords); the
repository has **10 issues, 0 open, 9 with `status:approved`**; all six `type:*` labels exist and 60/60 merged
PRs carry exactly one; no `.github/ISSUE_TEMPLATE` exists locally or remotely; `squash` and `rebase` merge
strategies are enabled beside `merge_commit`; `delete_branch_on_merge: false`; Dependabot security updates are
disabled; `secret_scanning` and `push_protection` are enabled.

- **S1 — Every issue has a form with observable acceptance criteria.** `.github/ISSUE_TEMPLATE/` gains
  `bug.yml`, `feature.yml` and `chore.yml` (GitHub issue forms) plus `config.yml` with
  `blank_issues_enabled: false`. Each form is `required: true` on: the problem, the expected outcome, and the
  acceptance criteria. Accept: a test asserts each form exists, declares `name`/`description`/`body`, marks
  those three fields required, and that `config.yml` disables blank issues.
  Check: `bash .github/scripts/tests/issue-templates.test.sh`.
- **S2 — A pull request whose body does not reference an approved issue fails.** New
  `.github/scripts/check-issue-reference.sh` (`--body`/`--body-file`, `--base-branch`, `--default-branch`,
  `--issue-json`, plus `--print-ref`) is wired into `.github/workflows/pr-label-guard.yml`. Rules: on the
  default branch, a closing reference (`Closes|Fixes|Resolves #N`, case-insensitive, colon optional) to an
  **open** issue carrying `status:approved` is required; on a non-default base (chain) `Refs #N` is also
  accepted, because GitHub ignores closing keywords off the default branch; a reference to a number that is a
  pull request, to another repository, or to an issue that is closed or not `status:approved` fails; an
  unreadable body, malformed JSON, missing arguments or an unknown argument all fail closed.
  Accept: the offline suite proves each of those cases; the workflow grants `issues: read` and no write scope.
  Check: `bash .github/scripts/tests/check-issue-reference.test.sh`.
- **S3 — Review ownership is declared and enforceable.** `.github/CODEOWNERS` assigns the four component areas
  (`life-control-api/`, `life-control-app-angular/`, `docker/` + `k8s/`, `.github/`) to `@le03nava`, and
  `CONTRIBUTING.md` carries the exact branch-protection runbook (required checks named, require a pull request,
  dismiss stale approvals, require conversation resolution, strict up-to-date, disable squash and rebase).
  Applying those settings in GitHub is the user's action; the repository states them.
  Check: `git check-attr`-free structural read-back that each listed area is covered by a pattern, plus the
  runbook's command block present in `CONTRIBUTING.md`.
- **S4 — The `type:*` vocabulary has one machine-readable source.** `.github/label-vocabulary.txt` holds the
  six names; `check-pr-type-labels.sh` reads it instead of an inline string; a test cross-checks the file
  against `.agents/skills/project-conventions/references/labels.md` and against the six checkboxes of
  `.github/PULL_REQUEST_TEMPLATE.md`, so a seventh name cannot be added in one place only.
  Check: `bash .github/scripts/tests/vocabulary-source.test.sh`.
- **S5 — Dependabot's own pull requests satisfy the label gate.** Every `updates[]` entry in
  `.github/dependabot.yml` declares `labels:` with `dependencies` and exactly one `type:*` name from S4's
  vocabulary. Accept: a test parses the entries and fails on a missing, doubled or off-vocabulary label.
  Check: `bash .github/scripts/tests/label-artifacts.test.sh`.
- **S6 — One entry point documents the flow.** `CONTRIBUTING.md` exists at the repository root — the path
  `project-conventions` Hard Rule 1 already names in its precedence order — and states the issue→worktree→
  branch→PR→merge flow, the six `type:*` labels, the ~400 authored-line review budget, the worktree
  convention, the chain rule, and the gates; `AGENTS.md` links it.
  Check: structural read-back that every referenced path exists and that `AGENTS.md` links the file.
- **S7 — A component workflow filters the same paths on `push` and on `pull_request`.** Each of
  `angular-ci.yml`, `api-ci.yml`, `docker-build-integrity.yml` and `gateway-ci.yml` carries the `paths:` filter
  it uses under `pull_request` also under `push` (a paths-filtered `push` job that no changed path matches
  cannot start, so the filter cannot skip a real build). Accept: a test asserts both triggers declare the same
  path list per workflow, and the workflow commands are unchanged.
  Check: `bash .github/scripts/tests/workflow-trigger-parity.test.sh`.

**Assumptions** (not user requirements): issue forms are YAML issue forms rather than markdown templates, so a
required field is machine-checkable; the guard reads its issue through the workflow token rather than a new
PAT; `@le03nava` is the only code owner until the user names others.

## Tasks

| ID | `S#` | Route | Work | Commit |
| --- | --- | --- | --- | --- |
| T1 | S1 | inline | issue forms + `config.yml` + `issue-templates.test.sh` (RED 4/30 → GREEN 34/34) | `b560378` |
| T2 | S2 | inline | `check-issue-reference.sh` + its suite + workflow wiring | — |
| T3 | S4 | delegated (`gentle-ai-worker`) | `label-vocabulary.txt` + the guard reads it + `vocabulary-source.test.sh` (RED 8/16 → GREEN 17/17) | `b7a034a` |
| T4 | S5 | delegated | Dependabot `labels:` + `label-artifacts.test.sh` extension (RED on the real config → GREEN 19/19) | `b7a034a` |
| T5 | S6 | delegated | `CONTRIBUTING.md` + `AGENTS.md` link + the `index.md` registry | `5c382e6` |
| T6 | S3 | delegated | `.github/CODEOWNERS` + the branch-protection runbook | `5c382e6` |
| T7 | S7 | delegated | the four `push:` filters + `workflow-trigger-parity.test.sh` (RED 13/21 → GREEN 21/21) | `b385f35` |

Delegated units arrived as one bounded writer each, with disjoint edit surfaces, and no writer committed or
touched this record: the parent re-ran every suite, the shellcheck step and the differential before committing.

## Slice boundaries

| Slice | Specs | Commits on the tracker branch | Pull request | Merged as | Diff |
| --- | --- | --- | --- | --- | --- |
| 1 | `S1` | `cd58c1e` (this record), `b560378` (forms + suite) | #385 → `main` | `de408b3` | 458 lines |
| 2 | `S2` | `9fa68f3` (gate + suite + wiring) | #386, from `s1` | `ba56674` | 586 |
| 3 | `S4+S5` | `b7a034a` (vocabulary source, guard, Dependabot labels) | #387, from `s2` | `199155b` | 553 |
| 4 | `S6+S3` | `5c382e6` (CONTRIBUTING.md, AGENTS link, CODEOWNERS, registry) | #388, from `s3` | `e64d9f0` | 243 |
| 5 | `S7` | `b385f35` (the four `push:` filters + the parity suite) | #389, from `s4` | `55ad71f` | 207 |
| 6 | the verification corrections and the evidence | `2086aea`, `788fb45`, `7a890be`, `66fa69d`, `f630328`, `3f5b387`, `4a1b452` | #390, from `s5` | `9c7c4c9` | 245 |

The record itself is touched by more than one slice, so the last slice carries the completed table. The tracker
branch is never the review candidate — each pull request is. Slice 6 exists because the two corrections were
found by the verification that ran *after* the five slices were committed: joining them to the slices they
belong to would rewrite refs that were already pushed, and the alternative — one 2109-line pull request — is
what the chained delivery exists to avoid.

## Log

**L1** *(the user's original request, 2026-10-17, verbatim)*: «como deberian de funcionar los pull request y
los issues? todo cambio deberia entrar por un issue?»

**L2** *(verbatim)*: «ok quiero ver cuales son las mejores practicas, y que se tiene que adaptar, mejorar en el
repositorio para seguir las reglas»

**L3** *(the two authorizations, verbatim)*: «Todo, como feature ODD por porciones» and «Sí, solo lectura» —
the second authorizes read-only `gh` inspection of `le03nava/LifeControl`; every remote write stays a separate
user decision.

**L4** *(the delivery decision, 2026-10-17)*: «Cadena con rama tracker (recomendada)» —
`chain_strategy=feature-branch-chain`, tracker `chore/pr-issue-governance`, slices `S1+S2`, `S4+S5+S6`,
`S3+S7`.

**L5** *(the ownership decision, 2026-10-17)*: «Sí, con @le03nava por ahora» — `CODEOWNERS` ships with
`@le03nava` for the four component areas; branch protection, where it starts to bind, stays the user's action.

**L6** *(isolation and baseline evidence, 2026-10-17)*: the anchor was 7 commits behind `origin/main`; it was
fast-forwarded to `5b2c7ee` and the tracker branch was fast-forwarded to the same commit (no reset, no force).
Worktree `chore-pr-issue-governance` created with `herdr worktree create --base main --no-focus`, workspace
`w3V`, and registered in the session. Baseline before any change: all **8** existing suites pass —
`.github/scripts/tests/{check-pr-type-labels,label-artifacts,retro-label-prs}.test.sh` and
`docker/scripts/tests/{compose-profile,freshness-guard,image-pins,keycloak-setup,version-consistency}.test.sh`.

**L7** *(the slice re-cut, 2026-10-17)*: the first slice measured **458 authored changed lines** — the
issue forms, their suite and this record — against a forecast of 458 for `S1` alone and about 890 for the
feature. The approved three-slice composition (`S1+S2`, `S4+S5+S6`, `S3+S7`) would have put about 840 lines
in PR 1, so the slices were re-cut to five, each inside the ~400-line review budget: `S1` | `S2` | `S4+S5` |
`S6+S3` | `S7`. Same work, same order, smaller review units; the decision to chain on a tracker branch is
unchanged.

**L8** *(the defect the functional check found, 2026-10-17)*: the gate's first version compared the issue
state literally against `OPEN`. All 26 offline fixtures used upper case and agreed with the bug; the live
payload read back through the same path — `gh api repos/le03nava/LifeControl/issues/366` — returned
`"state":"closed"` in lower case, so a genuinely open issue (`"state":"open"`) would have been rejected on
every pull request, and the gate would have been removed within a week. The fixtures now use the API's own
shape, one case keeps an upper-case state to pin the normalisation, and the suite is at **27/27** with the
wiring proven end to end against real payloads: PR #367 (`Closes #366`) fails with `state=closed`, PR #372
fails with `no issue reference`, and the same #366 payload patched to `open` + `status:approved` passes.

**L9** *(verification of the three delegated units, 2026-10-17)*: the parent re-ran what each writer
reported instead of trusting the report. All **7** suites pass (27 + 26 + 34 + 19 + 33 + 17 + 21 assertions) and
`shellcheck --severity=warning` through the workflow's pinned image is clean over every script. The differential
check that the change to `check-pr-type-labels.sh` requires was run against the base commit's copy in a scratch
directory outside the repository, over nine inputs plus stdin mode: stdout, stderr and exit code are **identical**
in every behavioural case, and the only two differences are the two intended ones — the usage text gained the
vocabulary-source lines, and bash's own error line names a different file path and line number. The guard's new
fail-closed branch was proven on a copy outside the repository: with the vocabulary source present it passes,
with it removed it exits 1 naming the file.

**L10** *(decisions this feature deliberately leaves to the user, 2026-10-17)*: three, each recorded rather than
silently taken.

1. **The required-check set is the two unfiltered gates** (`Exactly one type:* label`, `A linked, approved issue`),
   not all six jobs. A required check whose workflow filters by `paths:` is reported as **expected** on every pull
   request that does not touch those paths, so requiring the four component jobs would block every unrelated pull
   request instead of protecting `main`. The four names are quoted in the runbook with that reason.
2. **`api-ci.yml` and `gateway-ci.yml` both name their job `Check`**, so a required check cannot tell them apart.
   Renaming a job changes the check name that reviewers and any existing protection rule reference, so it stays a
   follow-up decision rather than a silent rename inside this feature.
3. **`S7` changes what CI does at merge time.** Filtering `push` saves four component builds on every
   markdown-only or unrelated merge, and costs the previous behaviour where every merge to `main` built every
   component regardless of the diff. The filters cannot hide a real build — a component whose own paths did not
   change is the only build that stops running — but the trade-off is real and this line is what makes it
   reversible. `Dependabot` pull requests are exempt from the issue gate for the same reason their label is now
   declared in `dependabot.yml`: a machine cannot author an approved issue, and the weekly dependency policy is
   its approval model.

**L11** *(independent verification, and the two defects it found, 2026-10-17)*: a read-only `gentle-ai-verify`
pass re-ran every suite, validated all six workflow files with `actionlint` 1.7.12 (exit 0), replayed the
workflow's run block against a mock `gh` for the fail-closed paths (unreadable payload, non-JSON, a pull
request, a wrong number, a valid approved issue), and proved the `S7` diff adds only `paths:` lines under
`push:` (`3/3/7/4` inserted, nothing removed). Verdicts: **verified** for both units, with no blocker. It also
found two defects, both fixed and both pinned by a case that was red first:

1. **The gate matched three of GitHub's nine closing keywords** (`788fb45`). A body saying `Fix #12` was
   rejected as having no reference at all — a valid pull request blocked over a conjugation, by the gate whose
   purpose is to make linking issues easy. The suite now pins one case per family plus a keyword GitHub does
   not honor.
2. **The parity suite could report a false pass** (`7a890be`). Its key rule matched only `[A-Za-z_]+` before the
   colon, so `paths-ignore:` fell through and its entries were read as `paths:` entries: a push of `one/**` plus
   `paths-ignore: two/**` compared equal to a pull-request list of both. A test that can pass wrongly is the
   same defect class this repository's other suites exist to catch, which is why it is fixed rather than noted.

Advisories accepted and documented, not fixed: a reference quoted inside a code fence is still read as a
reference; the cross-repository rule also rejects a self-repository `owner/repo#N` reference; and the `issue`
job checks out and runs the guard from the pull request itself, so a pull request that edits the guard decides
its own verdict — inherent to the `pull_request` trigger, mitigated by `.github/CODEOWNERS` and by branch
protection once it is applied.

**L12** *(the incident this turn caused and repaired, 2026-10-17)*: cleaning up the leftover scratch of the
delegated writers removed `/tmp/gpfNX1J8`, which was the session's `TMPDIR`. `mktemp -d` then failed inside
every suite, and because the suites did not check it the result read as **all 7 suites failing**, including
tests this feature never touched. The directory was recreated and every suite re-run green. The two suites
this feature adds now fail loudly when their scratch directory cannot be created, and the lesson is recorded
here because the same cleanup would mislead the next session in the same way.

**L13** *(the receipt-driven review, 2026-10-17)*: `gentle_review` with `assess` over the committed range from
the branch point reports `nativeRisk: high`, `reviewDue: true` (`high_risk`), writer profile `large` from the
runtime evidence, 24 paths and 2109 changed lines; the risk signals are the new executable shell script
(`executable_mode`, `process_boundary`, `shell_source`). The first attempt reported `unassessable` because the
call ran with the session's own directory — `main`, clean — instead of the worktree; naming the worktree as the
workspace root produced the assessment above. `inspect` then reports `status: ready`, `action: start`,
`fresh_target_ready`, and offers exactly one route: `review.start` with `--base-ref=5b2c7ee --committed-only`
over the whole tracker range.

**The review was deliberately not started.** The offered candidate is the accumulated feature branch, and the
lifecycle that governs this call states the candidate is a work-unit commit or a pull request slice and never
the accumulated feature branch. The five slices exist as commits on the tracker, not yet as the branches the
chain needs, and materializing those branches is part of the delivery the user has not authorized. The decision
returned to the user with the outcome: which slice to review first (recommendation: `S2`'s gate, which is where
the high-risk signal is), and whether to start the review at all.

**L14** *(the delivery, 2026-10-17)*: authorized by the user («sigue con el 1»), after the outcome, the
numbers and the four open decisions were reported to them. Six issues were opened through the repository's own
flow — **#379–#384**, each stating the problem, the expected outcome and the acceptance criteria of its slice
— and each was labelled `enhancement` and `status:approved`, the approval being the user's instruction
recorded here rather than a conclusion of mine. Seven refs were pushed (`chore/pr-issue-governance` and the six
slice branches), and six chained pull requests were opened: **#385** (`s1` → `main`, the only one on the
default branch, so the only one that closes its issue from the body) followed by **#386–#390**, each targeting
the previous slice and carrying `Refs #<issue>`, which is exactly the non-default-base case
`check-issue-reference.sh` supports. The pull requests are the first live exercise of the gate this feature
adds. What remains, and is deliberately not done here: the chain's retarget-and-merge protocol
(`references/pr-chains.md`), the review, and applying branch protection.

**L15** *(delivery evidence, 2026-10-17)*: the chain merged in order, each pull request retargeted to `main`
with its `Refs` swapped for `Closes` in the same step, and every merge is a real merge commit: **#385**
`de408b3`, **#386** `ba56674`, **#387** `199155b`, **#388** `e64d9f0`, **#389** `55ad71f`, **#390** `9c7c4c9`.
All six issues (#379–#384) closed as `COMPLETED`, and the six slice branches were deleted only after the last
merge landed, so no base of an open pull request was ever removed. The record's own promise was checked, not
assumed: all **thirteen** cited commit ids are ancestors of `main` (`git merge-base --is-ancestor`, one line
each), which is what makes the table above checkable after the tracker branch is gone.

**L16** *(the second defect the delivery itself found, 2026-10-17)*: the chain stopped one merge from the end.
Pull request #390 explains the corrected `Fix #12` case in its description, the gate's raw scan read that
quotation as a closing reference, and #12 is a pull request in this repository — so `A linked, approved issue`
failed the pull request that fixes the gate. The advisory the independent verification had recorded as accepted
behaviour (see `L11`) was the defect. The gate now removes fenced blocks and inline code spans before scanning,
so a reference must appear in prose; the rule cuts both ways, since a reference buried in code no longer counts
as the pull request's own reference either. Four cases pin it, one of them the real body that failed, and the
chain resumed and completed. The worktree is removed after this slice through `herdr workspace close w3V` →
`git worktree remove` → `git worktree prune`, in that order, now that every commit is in `main`.

**L17** *(the third defect, found while applying branch protection, 2026-10-17)*: the `issue` job's Dependabot
exemption tested `github.actor`, which is whoever **triggered the event**, not who **authored the pull
request**. Measured on pull request #392: toggling one label made the actor a human, the job ran, and a
dependency bump that cannot author an approved issue failed the gate it is exempt from — a false red on a
legitimate pull request, produced by touching it. The predicate is now
`github.event.pull_request.user.login != 'dependabot[bot]'`, with two cases in `label-artifacts.test.sh` so it
cannot be simplified back to the actor. Two facts from the same investigation are worth keeping: a **skipped**
job still registers as a check run with conclusion `SKIPPED`, which GitHub accepts for a required status check
(so the exemption and the protection coexist, verified on #392 before requiring it), and the failing run was
reported by `gh pr checks` as a clean `fail` while the run list called it a `failure` of the whole workflow —
both readings agree here, but only the rollup names the job. This fix is a follow-up pull request rather than a
rewrite of the merged slices.

**L18** *(a workflow change is not retroactive, verified on #392, 2026-10-17)*: after the exemption fix landed
in `main`, a label toggle on the Dependabot pull request **still failed the issue job**. The tree that ran was
not `main`: the run's `head_sha` carried the *old* `if:` line, because pull request #392 was opened before the
fix and its merge ref had not been recomputed — `git ls-remote origin refs/pull/392/merge` pointed at a commit
older than the fix, and the pull request reported `BEHIND`. So a gate is only as current as the tree the run
uses, and a pull request opened before a workflow change keeps running the old workflow until its branch is
updated. The remedy is to update the branch — `@dependabot rebase` for a machine-opened one — which is also what
`strict` already demands: after the rebase the head moved `8ee42c64` → `3f3c334d`, `A linked, approved issue`
turned into `skipping`, `Exactly one type:* label` stayed `pass`, and the pull request went from `BEHIND` to
`MERGEABLE` with `UNSTABLE`, which is the correct reading of a pending check that is **not** required.
`CONTRIBUTING.md` §7 now records the rule and the remedy.

**L19** *(the native review of the merged range, 2026-10-17)*: lineage `review-34f9d631b3e17f3f`, tier
**high**, 29 changed paths and 2740 lines over `5b2c7ee..45ce36a` — a range that also contains two merges from a
sibling session (`redis-cache-activation`, `redis-outage-failure-policy`), so the candidate is wider than this
feature. The provider selected all four lenses; the four reviewers and the refuter ran, and the refuter closed
with two CRITICAL findings, both marked `causal_disposition: introduced`.

- **R3-002, real and mine.** The closing-keyword pattern had no word boundary, so `fix` inside `prefix`,
  `resolve` inside `unresolved` and `close` inside `enclosed` were read as closing keywords; the same held for
  `prefs #12` against the `Refs` pattern. Reproduced before the fix: `--body "This stays unresolved #12 for
  now."` printed `12`. Fixed in **#402** with five cases that were red first, and the boundary is a character
  class because `\b` is not portable across greps (measured: the local grep matched nothing at all with it).
- **R3-001, refuted.** The lens claimed the redis test's assertion fails at runtime because
  `LettuceConnectionFactory.getTimeout()` returns `Duration`. Running it refutes the claim:
  `./gradlew test --tests '*RedisOutagePolicyTest*' --no-daemon` reports **BUILD SUCCESSFUL**. Two green
  full-suite `API CI` runs on `main` said the same before that. Nothing was changed for it, and a passing test
  is not made worse to satisfy an inference.

**The provider's correction route is broken, and nothing was burnt.** The lineage closed in
`correction_required` with a bounded plan request. The plan was submitted twice with `correctionLines=26`
(measured: 24 insertions and 2 deletions), and both times the provider's own validator answered
`collectBinding is missing or stale for current STATUS` — the binding it had just issued, taken verbatim from a
fresh STATUS, including as an unformatted serialized string. No mutation, no lineage burn, no invented value:
submitting was stopped and the state left consistent. The documented continuations are maintainer inspection or
`D`; the correction itself never depended on that route, because it landed as **#402** through the ordinary
pull-request path.

**#400 was closed as redundant.** Before the review finished, a sibling session opened **#403**
(`fix/pr-label-guard-stale-payload`) for the same defect, and it merged first — the label set is read from the
pull request through the API at run time, with `pull-requests: read`, and `label-artifacts.test.sh` in `main`
already asserts the three things my branch asserted. Diffing the branch against `main` left only a duplicate
implementation and duplicate coverage, and the issue it referenced was already closed by #403, so the pull
request was closed with that reason instead of merged twice.

**L20** *(branch protection applied, 2026-10-17)*: the settings `L10` left to the user were applied to `main`
and read back from the API: `enforce_admins` **true**, `strict` **true**, required status checks
`Exactly one type:* label` and `A linked, approved issue` bound to the GitHub Actions app, 0 required approving
reviews with stale-review dismissal, required conversation resolution, force pushes and branch deletions off,
and only merge commits allowed (`squash` and `rebase` disabled). Two verifications came with it: **#399** was
the first pull request to merge through it (`CLEAN/MERGEABLE`, both required checks `pass`), and a **skipped**
job registers as a check run with conclusion `SKIPPED`, which GitHub accepts for a required check — so
Dependabot's exemption and the protection coexist, measured on #392. The operational cost is real and stated:
with `strict`, every merge to `main` leaves the other open pull requests `BEHIND` until their branches are
updated.

**L21** *(the prose asymmetry this chain left behind, issue **#406**, 2026-10-17)*: the header's last sentence — *no work left
in the repository* — was false the moment it was written, and the artifact that falsified it is this chain's own
`S7`. `S7` (`b385f35`, merged by **#389**) gave `push` and `pull_request` the same `paths:` list in all four
component workflows, and the suite written for it, `.github/scripts/tests/workflow-trigger-parity.test.sh`,
asserted that parity from the day it landed. Two artifacts went on saying the opposite, and nothing read either
against the workflows:

- `AGENTS.md`'s CI note stated *"el de `push` a `main` **no** filtra, así que todo merge a `main` corre el
  workflow completo aunque el diff sea un solo `.md`"* — the pre-`S7` behaviour, measured false on all four
  workflows: `angular-ci.yml:4-13` and its three siblings each carry a `paths:` block under both triggers.
- the parity suite's own header comment described the asymmetry as current — *"(AGENTS.md documents the
  asymmetry)"* — nine lines above the assertions that had already made it stale.

Measured before the fix: `bash .github/scripts/tests/workflow-trigger-parity.test.sh` answered **22 passed,
1 failed**, and the failing case did not exist yet — which is the point. The suite froze the workflows and never
the prose, so a green run said nothing about the sentence a contributor reads first.

The repair is one reviewable slice of three edits: the note in `AGENTS.md` now states the invariant, names the
suite as its source and inverts its practical consequence (an empty `gh pr checks` *does* mean the merge will not
run a component workflow; the label guard, deliberately unfiltered, is the only check that always runs), the
suite's header comment describes the asymmetry as the behaviour the suite removed, and the suite gains the reader
that was missing — one case that fails while `AGENTS.md` still carries the `**no** filtra` claim. That case was
observed **RED** first (**22 passed, 1 failed**, `exit 1`) and the suite answers **23 passed, 0 failed**
(`exit 0`) once the note was corrected. Only the absence of the claim is asserted, because the workflows — never
the prose — are the source: a later rewrite passes without pinning its own wording here.
