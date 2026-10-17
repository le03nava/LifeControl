# ODD feature: pr-issue-governance

**Repository**: LifeControl — repository governance: issue templates, an issue-reference gate, the `type:*`
vocabulary single source, Dependabot labels, `CONTRIBUTING.md`, `CODEOWNERS`, and the CI trigger alignment.
No `life-control-api/` or `life-control-app-angular/` runtime source is touched.
**Branch**: `chore/pr-issue-governance`, worktree `~/workspace/LifeControl-worktrees/chore-pr-issue-governance`
(herdr workspace `w3V`), cut from `main` @ `5b2c7ee`; the anchor stays on `main` and clean.
**Delivery**: `chain_strategy=feature-branch-chain` — one tracker branch, three PR slices: PR 1 = `S1+S2`,
PR 2 = `S4+S5+S6`, PR 3 = `S3+S7`. Push, issue creation and merge are the user's decisions.
**Test runner**: `.github/scripts/tests/*.test.sh` and `docker/scripts/tests/*.test.sh` — offline bash
suites, run from the worktree; `shellcheck --severity=warning` on modified scripts.
**Status**: no slice has landed yet — `## Tasks` states what remains, and this header makes no claim about
push, PR, or merge state; delivery detail belongs in the dated evidence log below.
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
| T1 | S1 | inline | issue forms + `config.yml` + `issue-templates.test.sh` (RED → GREEN) | — |
| T2 | S2 | inline | `check-issue-reference.sh` + its suite + workflow wiring | — |
| T3 | S4 | inline | `label-vocabulary.txt` + guard reads it + `vocabulary-source.test.sh` | — |
| T4 | S5 | inline | Dependabot `labels:` + `label-artifacts.test.sh` extension | — |
| T5 | S6 | inline | `CONTRIBUTING.md` + `AGENTS.md` link | — |
| T6 | S3 | inline | `.github/CODEOWNERS` + branch-protection runbook in `CONTRIBUTING.md` | — |
| T7 | S7 | inline | the four `push:` filters + `workflow-trigger-parity.test.sh` | — |

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
