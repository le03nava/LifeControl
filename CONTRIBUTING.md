# Contributing to LifeControl

This is the entry point for contributing to this repository. It states the flow and links the file
that owns each rule instead of restating it. The precedence order is
`AGENTS.md` > `CONTRIBUTING.md` > `docs/**` > conventions implied by the code (`project-conventions`
Hard Rule 1), so `AGENTS.md` still wins where the two overlap. Component detail lives in
`life-control-api/AGENTS.md` and `life-control-app-angular/AGENTS.md`.

## 1. Every change starts with an approved issue

Open an issue before writing code. The issue states the problem, the expected outcome and its
**acceptance criteria** — the observable conditions that decide whether the change is done. The
issue forms in `.github/ISSUE_TEMPLATE/` mark those three fields required.

The issue must carry `status:approved` **before the pull request is opened**. Approval is a human
decision recorded on the issue, not a label the author applies to their own request. This is
invariant 4 of `.agents/skills/project-conventions/references/labels.md`.

Link the issue from the pull request body (`.github/PULL_REQUEST_TEMPLATE.md`):

- On the **default branch** (`main`) use a closing keyword: `Closes #123`.
- On a **chained slice** — a pull request whose base is another branch — use `Refs #123`. GitHub
  interprets closing keywords only against the default branch, so a `Closes` on a child slice would
  reference the issue without ever closing it.

`.github/workflows/pr-label-guard.yml` enforces this for every pull request. Its `A linked, approved
issue` job fails when the body has no usable reference, or references a closed issue, a pull request,
another repository, or an issue without `status:approved`. The rules live in
`.github/scripts/check-issue-reference.sh`; the guard's own failure modes are covered offline by
`.github/scripts/tests/check-issue-reference.test.sh`.

## 2. One `type:*` label per pull request

Every pull request carries **exactly one** `type:*` label — never zero, never two — chosen from the
six names `.agents/skills/project-conventions/references/labels.md` declares. `.github/PULL_REQUEST_TEMPLATE.md`
renders the same six names as checkboxes beside the label to apply; ticking the box is not enough,
because the guard reads labels, not the body.

`.github/workflows/pr-label-guard.yml` fails the pull request from its `Exactly one type:* label`
job when the label set is empty, doubled or off-vocabulary. The guard's script is
`.github/scripts/check-pr-type-labels.sh`, and the name-by-name vocabulary it accepts is declared in
`.agents/skills/project-conventions/references/labels.md`.

A pull request that is genuinely two change types is two pull requests.

## 3. Keep the review small: the ~400-line budget and chained slices

This repository reviews against an advisory budget of about **400 authored changed lines** per pull
request. A slice that cannot fit inside the budget without losing a cohesive unit is split into a
**chain**: each slice branches from the previous slice, and each pull request is read on its own.
The mechanics — the base-branch deletion failure mode, the pre-merge guard, the order of operations
and the anti-patterns — belong to
`.agents/skills/project-conventions/references/pr-chains.md`, and that file is the source of truth.

The short version for a contributor, whose detail is in that reference:

- Before merging a parent slice, run its pre-merge guard and retarget any open dependents
  (`gh pr edit <n> --base main`); a base branch with open dependents must not be deleted.
- Keep automatic head-branch deletion off while a chain is open — it is a repository setting, not a
  repository fact (see the branch-protection runbook below).
- Record the intended merge order before the first merge, because each child's diff still carries
  its parent's work.

## 4. Work in a worktree

Parallel work needs isolation: one branch, one worktree, one unit of work, one agent per worktree.
The worktree directory is `~/workspace/<repo>-worktrees/<slug>`, a sibling of the repository, with
`<slug>` equal to the branch name with `/` replaced by `-`. The anchor stays on `main` and clean.
The full model — creation, verification, cleanup order, the Pi trust requirement and the
anti-patterns — is in `.agents/skills/project-conventions/references/worktrees.md`.

## 5. Leave an evidence record

A substantial unit of work leaves a versioned record in `odd/tasks/`, named after its slug. The
record's `**Status**:` line states durable state plus its evidence, or what remains — never a live
delivery state such as "not pushed" or "awaiting review"; delivery detail belongs in the record's
dated evidence log. The header shape and the failure mode that produced the rule are in
`.agents/skills/project-conventions/references/feature-records.md`, and `AGENTS.md` summarises the
same convention under `## Registros ODD`.

## 6. Gates CI runs

Five workflows run from `.github/workflows/`. Each job's `name:` is quoted verbatim because the
branch-protection runbook below names required checks by exactly those strings.

| Workflow | Runs on | Job `name:` |
| --- | --- | --- |
| `.github/workflows/pr-label-guard.yml` | every pull request (no `paths:` filter) | `Exactly one type:* label`, `A linked, approved issue` |
| `.github/workflows/api-ci.yml` | `life-control-api/**`, `.github/workflows/api-ci.yml` | `Static analysis`, `Test` |
| `.github/workflows/gateway-ci.yml` | `api-gateway/**`, controllers under `life-control-api/**`, `.github/workflows/gateway-ci.yml` | `Check` |
| `.github/workflows/angular-ci.yml` | `life-control-app-angular/**`, `.github/workflows/angular-ci.yml` | `Lint, Build & Test` |
| `.github/workflows/docker-build-integrity.yml` | `docker/**`, the two `Dockerfile`s and `build.gradle`s, `.github/workflows/docker-build-integrity.yml` | `Shell & Artifact Integrity` |

The label guard's self-tests run the offline suites in `.github/scripts/tests/` and `shellcheck
--severity=warning` over `.github/scripts/*.sh`. The four component workflows run their component's
lint, build and test gate; the exact commands are in `AGENTS.md` under `## Development Commands` and
in the `Dispatch` table of `.agents/skills/project-conventions/references/index.md`. Every workflow
also runs on `push` to `main`, so a merge re-runs the gate even when the pull request ran none of it.

## 7. Branch protection

**Applying these settings is a repository-admin action.** Branch protection, required status checks
and merge strategies are provider-side settings: this repository can state them, and a maintainer
with admin access to `le03nava/LifeControl` must apply them. Nothing in the repository can enforce
its own protection rules, and a fresh checkout cannot prove the settings are in place — verify them
with the command at the end of this section.

Measured on 2026-10-17, `main` was **not protected** at all:

```console
$ gh api repos/le03nava/LifeControl/branches/main/protection
gh: Branch not protected (HTTP 404)
```

So today the gate jobs report on every pull request, but nothing makes the merge wait for them.

### Settings to apply to `main`

| Setting | Value | Why |
| --- | --- | --- |
| Require a pull request before merging | on | No direct push to `main`: every change is read as a pull request. |
| Required approving reviews | 1 | One approval is the review floor. `.github/CODEOWNERS` routes it to the owner of the touched area. |
| Dismiss stale pull request approvals when new commits are pushed | on | An approval applies to the diff it saw; a later push invalidates it. |
| Require conversation resolution before merging | on | An unresolved review thread is a pending decision, not a note. |
| Require branches to be up to date before merging (strict) | on | The checks run against the merge result, not against a stale base. |
| Required status checks | `Exactly one type:* label`, `A linked, approved issue` | The two jobs of `.github/workflows/pr-label-guard.yml` — the only workflow here with no `paths:` filter, so they report on every pull request. |
| Allow merge commits | on | The two-parent merge commit is the delivery evidence this repository records (`.agents/skills/project-conventions/references/feature-records.md`). |
| Allow squash merging | off | A squash rewrites the history a record cites. |
| Allow rebasing | off | A rebase does the same. |
| Automatically delete head branches (`delete_branch_on_merge`) | off while any chain is open | Deleting the base branch of an open pull request closes it with no retarget (`.agents/skills/project-conventions/references/pr-chains.md`). |

**Why the four component checks are not required.** `.github/workflows/api-ci.yml` (`Static analysis`,
`Test`), `.github/workflows/gateway-ci.yml` (`Check`),
`.github/workflows/angular-ci.yml` (`Lint, Build & Test`) and
`.github/workflows/docker-build-integrity.yml` (`Shell & Artifact Integrity`) filter by
`paths:` under `pull_request`, so each check context reports only when a matching path changed. A
required check that never reports stays *Pending* and blocks the pull request, so requiring a
path-filtered job would block every pull request that does not touch its component — a
markdown-only change would never merge. They still run when their paths match and on `push` to
`main`. After the split, no two component workflows report a job with the same `name:` — `Static
analysis` and `Test` for `api-ci.yml`, `Check` for `gateway-ci.yml`, `Lint, Build & Test` for
`angular-ci.yml`, `Shell & Artifact Integrity` for `docker-build-integrity.yml` — and none of them
is in the required set as long as the path filters stand.

**A workflow change is not retroactive.** A check runs the workflow file from the tree of *that*
run, so a pull request opened before a workflow changed keeps running the old one until its branch
is updated. Measured on pull request #392 (2026-10-17): after the Dependabot exemption was fixed in
`main`, toggling one label still failed the issue job, because the run's `head_sha` carried the
pre-fix `if:` line and the pull request's merge ref was older than the fix. `strict` already
requires an up-to-date branch, so the remedy is the same one that unblocks the merge: update the
branch (`@dependabot rebase` for a machine-opened pull request), then read `gh pr checks` again
instead of trusting a verdict produced before the change.

### Apply it in the GitHub UI

- **Branch protection**: repository **Settings** → **Branches** → **Branch protection rules** → **Add
  branch protection rule** (or edit the rule whose branch name pattern is `main`) → tick the rows in
  the table above → **Save changes**. Under **Require status checks to pass**, type each of
  `Exactly one type:* label` and `A linked, approved issue` and pick it from the list; a check
  appears there only after it has run at least once.
- **Merge strategies and branch deletion**: repository **Settings** → **General** → **Pull Requests**
  → clear **Allow squash merging** and **Allow rebase merging**, keep **Allow merge commits** ticked,
  and leave **Automatically delete head branches** unticked while a chain is open.

### Apply it with `gh`

`gh` 2.101.0. The two blocks touch different settings: branch protection first, then the
repository-level merge and branch-deletion settings.

```bash
# 1. Protect `main`: pull request required, one approving review, stale approvals dismissed,
#    conversations resolved, branches required to be up to date, and the two always-reporting
#    gate checks required.
gh api --method PUT repos/le03nava/LifeControl/branches/main/protection \
  -H "Accept: application/vnd.github+json" \
  --input - <<'JSON'
{
  "required_status_checks": {
    "strict": true,
    "contexts": [
      "Exactly one type:* label",
      "A linked, approved issue"
    ]
  },
  "required_pull_request_reviews": {
    "dismiss_stale_reviews": true,
    "required_approving_review_count": 1
  },
  "required_conversation_resolution": true,
  "restrictions": null
}
JSON

# 2. Repository settings: keep merge commits, disable squash and rebase, and keep automatic
#    head-branch deletion off while a chain is open.
gh repo edit le03nava/LifeControl \
  --enable-merge-commit=true \
  --enable-squash-merge=false \
  --enable-rebase-merge=false \
  --delete-branch-on-merge=false
```

### Verify it was applied

Read the settings back rather than trusting the write:

```bash
# The protection object; a 404 means `main` is not protected.
gh api repos/le03nava/LifeControl/branches/main/protection

# The three rows that decide a merge, in one shot.
gh api repos/le03nava/LifeControl/branches/main/protection \
  --jq '{strict: .required_status_checks.strict, checks: .required_status_checks.contexts, approvals: .required_pull_request_reviews.required_approving_review_count, dismiss_stale: .required_pull_request_reviews.dismiss_stale_reviews, conversation_resolution: .required_conversation_resolution}'

# The repository-level merge strategies and branch deletion.
gh api repos/le03nava/LifeControl \
  --jq '{merge: .allow_merge_commit, squash: .allow_squash_merge, rebase: .allow_rebase_merge, delete_branch_on_merge: .delete_branch_on_merge}'
```

## 8. Commits

Follow conventional-commit style, `<type>[scope]: <description>`, with the types listed in
`AGENTS.md` under `## Commit & Pull Request Guidelines`. Keep tests and documentation in the same
work unit as the code they describe, and leave `Co-Authored-By` trailers out.
