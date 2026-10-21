# ODD feature: anchor-is-never-an-edit-target

**Repository**: LifeControl — the four artifacts that state the worktree rule (`AGENTS.md`,
`CONTRIBUTING.md`, `.agents/skills/project-conventions/SKILL.md` and
`references/worktrees.md`), a versioned `pre-commit` hook that makes the rule bite locally, a portable
worktree script that removes the reason to skip it, the suite that keeps the four texts and the hook from
drifting, and **one line of `pr-label-guard.yml`** so the hook is linted like the rest of the shell in this
repository. **No production code, no dependency, no `.env*` file, and no change to branch protection.**
**Status**: implemented — the rule without its conditional, the hook, the script and the parity suite are
written and measured, and this record travels in the pull request that carries them. Nothing is deferred.
**Created**: 2026-10-21 · **Base**: `main` @ `e44e7d7` · **Branch**: `chore/anchor-not-an-edit-target` ·
**Worktree**: `~/workspace/LifeControl-worktrees/chore-anchor-not-an-edit-target` (herdr workspace `w5B`)
**Risk**: **item 3 of the task-size list, and item 6 argued down by measurement.** Item 3 because the
worktree rule is a contract four artifacts state and every future unit is written against; item 6 because
a hook that refuses commits is a new gate whose own logic nothing covered — which is why the suite drives
the hook as a program in a scratch repository instead of asserting that it exists. The hook is local and
per-clone, so nothing here can break CI or another contributor's checkout: a clone that never runs
`git config core.hooksPath .githooks` is exactly where it was before.

## Objective

Stop an agent or a person from doing a unit's work **in the anchor**. The rule is not missing — it is
stated three times — but all three state it as a benefit of *parallelism*, so a single sequential writer
reads each of them as not applying to it, and nothing verifies the rule either way.

## The measurement this change answers

| Fact | Value | Command |
| --- | --- | --- |
| Places that state the rule | **4** (`AGENTS.md`, `CONTRIBUTING.md:66`, `SKILL.md:36`, `worktrees.md` invariant 3) | `grep -rn 'worktree'` |
| Of those, places that frame it as *parallel work* | **2** (`CONTRIBUTING.md` «Parallel work needs isolation», `SKILL.md` «When work is parallelized») | same |
| Artifacts that verify the rule | **0** | `grep -rln 'hooksPath\|anchor' .github/scripts/` |
| Hooks installed in this clone | **0** (`core.hooksPath` unset, no executable hooks) | `git config --get core.hooksPath` |
| What the server already blocks | a direct push to `main`: `required_pull_request_reviews` + `enforce_admins: true` + `strict: true` | `gh api repos/…/branches/main/protection` |
| Shell files the guard workflow lints before this change | **14** of 15: `.github/scripts/**/*.sh` only, so a hook — which carries no extension because git requires the bare name — was linted **never** | the `ShellCheck` step of `pr-label-guard.yml` |
| Incident that opened this unit | the anchor of this very repository was found with **5 uncommitted files** (`life-control-app-angular/.../regions-form/*`) that are not part of any pull request | `git status --short` |

The server half is already closed, and that matters for the shape of this change: a unit done in the
anchor can never be *published* from there. What is not closed is the local half — work left uncommitted
on top of the reference, which then either cannot be committed at all or is committed on `main` and can
never be pushed, so it ends as divergence and a manual rebase. That is the half a hook catches and a CI
guard cannot: the server cannot see a dirty checkout.

## Specs

- **S1 — The rule loses its conditional.** Every artifact states that a unit starts in its own worktree
  *without exception*, and that the anchor is never an edit target, only a read-only reference for
  `fetch`, `log` and `rebase`. The phrase «Parallel work needs isolation» and the trigger «When work is
  parallelized» are removed, not reworded around. The user's decision, verbatim: *«alcance completo:
  1+2+3+4»*, choosing *«Suma el script portable con git worktree add, para no depender de herdr»*.
- **S2 — A versioned hook refuses a commit on the default branch.** `.githooks/pre-commit`, installed with
  one command (`git config core.hooksPath .githooks`), refuses the commit, says why, and prints the exact
  way out: the worktree script, the `stash`/`pop` sequence to move existing work off the anchor, and the
  `ALLOW_MAIN_COMMIT=1` escape for a commit the human decides is deliberate.
- **S3 — The default branch is resolved, never hardcoded.** From `refs/remotes/origin/HEAD`, falling back
  to `init.defaultBranch` and then `main`. A repository whose default branch is not called `main` is
  guarded just as well, and the suite proves it by renaming it in a fixture.
- **S4 — Detached HEAD and every other branch are untouched.** The hook is a guard for exactly one
  situation; anything else commits as it always did, so the hook cannot become the reason a legitimate
  workflow stops.
- **S5 — The command stops depending on `herdr`.** `.github/scripts/new-unit.sh <branch>`
  creates the worktree with plain `git worktree add`, in the sibling directory the convention names,
  deriving the slug from the branch so the two cannot disagree, and refusing a branch whose directory
  already exists. Whoever has `herdr` keeps using it; whoever does not
  now has one command instead of a temptation to stay on `main`.
- **S6 — The four texts and the hook cannot drift.** `.github/scripts/tests/worktree-anchor.test.sh` fails
  when any of the four artifacts loses the rule or the command, when either conditional phrase comes back,
  when the hook is missing or not executable, or when the hook's behaviour changes — it is driven as a
  program in scratch repositories, not asserted by existence.
- **S8 — The hook is linted like every other shell file in this repository.** Its file carries no `.sh`
  extension, so the workflow's `-name '*.sh'` glob could not reach it: a hook that runs on every commit in
  every clone would have been the one shell file in the repository that nothing lints. The `ShellCheck`
  step grows to `.githooks`, and the suite fails if that coverage is dropped.
- **S7 — Nothing else changes.** No workflow, no branch protection, no `main` worktree layout, and no
  other suite's expectations. The nine existing suites stay green.

## Tasks

| ID | What | Specs | Route | Commit |
| --- | --- | --- | --- | --- |
| T1 | `.githooks/pre-commit`: refuse the default branch, resolve it from `origin/HEAD`, allow every other branch and a detached `HEAD`, escape with `ALLOW_MAIN_COMMIT=1` | S2, S3, S4 | inline | `f4e762e` |
| T2 | `.github/scripts/new-unit.sh`, portable, deriving the slug and refusing a second creation | S5 | inline | `f4e762e` |
| T3 | `.github/scripts/tests/worktree-anchor.test.sh`: drives the hook as a program in scratch repositories and asserts the four texts and the workflow glob (23 assertions) | S6, S8 | inline | `f4e762e` |
| T4 | The four artifacts: the rule without its conditional, the hook's one-line install, the script as the canonical command | S1, S5 | inline | `f4e762e` |
| T5 | `pr-label-guard.yml`: the `ShellCheck` step also reaches `.githooks` | S8 | inline | `f4e762e` |
| T6 | All ten suites, run and measured (335 assertions, 0 red) | S6, S7 | inline | `f4e762e` |
| T7 | `PR Label Guard` on pull request **#506**: `ShellCheck: success` — and its log prints `Checking 15 script(s)`, up from 14, which is the hook inside the glob — plus `Self-tests: success` (run `39552748637`) | S6, S8 | — | — |

## Log

- **L1** — *«que se puede hacer para reforzar que cuando se haga un cambio no se haga sobre main, para
  que se tenga que crear su propio worktree? hay algo que agregar en el AGENTS del poyecto o en las
  skills del prject convetions»*. The diagnosis: the rule is stated three times and all three frame it as
  a parallelism benefit, so a single sequential writer reads it as inapplicable; and no artifact verifies
  it. The anchor of this same repository was carrying 5 uncommitted Angular files when the question was
  asked, which is the failure mode in the wild.
- **L3** — Found while wiring T5, and not in the plan: the hook would have been the one shell file in this
  repository that nothing lints. `ShellCheck` globs `.github/scripts/**/*.sh`; a git hook cannot carry an
  extension (`pre-commit`, not `pre-commit.sh`), so the file that runs on every commit in every clone that
  installs it was outside every glob. Measured: 14 of 15 shell files linted. Fixed in the same step, and
  the suite now fails if the coverage is dropped — the same class as the rest of this unit, a rule that
  nothing reads.
- **L4** — Slice boundary, recorded because the slice is larger than the ~400-line guidance: the mechanism
  commit is **503 inserted lines across 8 files**, and it cannot be split further without leaving a red tree --
  `worktree-anchor.test.sh` asserts the four artifacts carry the rule, so a commit with the hook and the
  suite but not the docs fails by construction, and the reverse order leaves the suite's own coverage
  unasserted until the suite exists. The unit is therefore one mechanism slice plus this record, and the
  size is declared in the pull request instead of being hidden behind a split that only looks smaller.
- **L5** — A defect of this unit's own making, found before delivery: both commits were first built with
  `git add -A`, and that swept in **six files this unit does not own** — five
  `life-control-app-angular/src/features/companies/regions/` files and the **deletion** of
  `odd/tasks/fix-regions-form-enabled-400.md`, which were already modified in the checkout (mtime
  2026-10-20T23:43 in the anchor and 23:51 in this worktree) and belong to no pull request. Nothing was
  pushed, so the fix was to rebuild both commits staging the eight paths explicitly; the tree diff against
  `main` now contains exactly the nine files of this unit. Two consequences recorded rather than tidied
  away: `git add -A` is an unowned mutation in a checkout that can be shared, and the check that catches it
  is `git diff --cached --name-only` before every commit, not after. The foreign modifications are left
  exactly where they were, untouched — the same files that opened this unit are still uncommitted in the
  anchor, and the record deletion is not this unit's decision to make.
- **L6** — Delivered as issue **#505** (`status:approved`) and pull request **#506**, one pull request.
  `PR Label Guard` run `39552748637` answered `ShellCheck: success` and `Self-tests: success`, and the
  shellcheck log prints **`Checking 15 script(s)`** where it printed 14 before: that number is the evidence
  that the hook is inside the glob, which is stronger than the suite's assertion on the workflow text. The
  record is final in this pull request, so no follow-up `docs(odd)` pull request moves it.
- **L2** — Scope decision, verbatim: *«alcance completo: 1+2+3+4»* — the four texts, the local hook, the
  portable script and the parity suite. The earlier observation that a CI guard cannot see a dirty
  checkout, and that binding `checkout main` to a failure would break the anchor's own purpose, shaped the
  design: the local half is a hook, the repo-wide half is the parity suite, and nothing touches the
  branch-protection settings or the layout of any existing checkout.
