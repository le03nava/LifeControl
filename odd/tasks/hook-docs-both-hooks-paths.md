# ODD feature: hook-docs-both-hooks-paths

**Repository**: LifeControl — `AGENTS.md`, `CONTRIBUTING.md`, `.agents/skills/project-conventions/references/angular.md`
**Branch**: `docs/hook-docs-core-hookspath` · **Worktree**: `~/workspace/LifeControl-worktrees/docs-hook-docs-core-hookspath`
**Issue**: #964 — `docs(conventions): the hook docs answer neither which core.hooksPath a clone has nor what each one guarantees`
**Status**: implemented — the three documents are the branch's content, and each value of `core.hooksPath` now carries the sentence that says what runs behind it, including the one sentence the issue asks for: the guarantees are the same for both. Nothing is deferred and nothing is blocked; no hook, `prepare` script or configuration changed, so what is left for the maintainer is the delivery step the record does not perform.
**Created**: 2026-11-18
**Risk**: none of the six. The diff is three documentation paragraphs: no stored data, no role, guard or permission, no route, flag, config key or export, no concurrency, no installer, CI workflow or dependency, and no behavior to regress. Item 3 was considered and rejected on the specific point that could have applied — the documented command `git config core.hooksPath .githooks` is *kept* as written, because `.github/scripts/tests/worktree-anchor.test.sh` reads it, so the instruction this unit consumes does not move.

## Specs

1. **S1** — `grep -n 'core.hooksPath' AGENTS.md CONTRIBUTING.md` shows **both** values — the one the documented command sets and the one `npm install` sets — each with the sentence that says what runs behind it.
2. **S2** — `grep -n 'husky' AGENTS.md CONTRIBUTING.md .agents/skills/project-conventions/references/angular.md` returns a description of the entry point that matches its current body: it delegates to `.githooks/pre-commit` and stops on its failure, and it does not claim to run `npx lint-staged` on that path.
3. **S3** — the guard question is answerable from the docs with no read of the hook: for each of the two values, one sentence says which guarantees a commit gets, and the fact that catches the two drifting apart is that the answer is the **same** for both, stated as one sentence rather than describing each path separately.
4. **S4** — `bash .github/scripts/tests/worktree-anchor.test.sh` exits 0.
5. **S5** — `bash .github/scripts/tests/angular-formatting-gate.test.sh` exits 0, untouched.

Non-goals carried from the issue verbatim: not changing behavior (`core.hooksPath`, `package.json`'s `prepare`, `.husky/pre-commit` and `.githooks/pre-commit` all stay as they are); not reinstating the documented command as a requirement; not reopening #515's decision and adding no suite; not a claim that the two hooks are equivalent line by line.

## Tasks

| ID | Spec | Route | Commit |
|----|------|-------|--------|
| T1 | S1, S3 | inline — one row added to the worktree table in `AGENTS.md` | `959991c4` |
| T2 | S1, S3 | inline — one paragraph after the worktree commands in `CONTRIBUTING.md`, the `git config core.hooksPath .githooks` line left as written for S4 | `959991c4` |
| T3 | S2 | inline — the `.husky/pre-commit` bullet in `references/angular.md` rewritten to the delegation it performs today | `959991c4` |
| T4 | S1–S5 | inline — the two suites run green and the three greps re-read against the diff | `959991c4` |

## Log

**L1** — user, verbatim: «ok dale con 964».

**L2** — the measurement the issue carries, re-read on this tree rather than trusted: `git config --show-origin --get core.hooksPath` answers `.githooks` in the anchor, and `life-control-app-angular/package.json`'s `prepare` writes `life-control-app-angular/.husky/_` on every `npm install`. `.githooks/pre-commit:78-81` runs `npx lint-staged` from the app's directory when `node_modules` exists, and `life-control-app-angular/.husky/pre-commit` runs `bash "$REPO_ROOT/.githooks/pre-commit"` and exits with its code — so the "same guarantees for both values" sentence S3 demands is a statement about what the tree does, not about what the docs wish it did. The two values are therefore documented as equal in guarantees and unequal as files, which is the issue's own boundary.

**L3** — `bash .github/scripts/tests/worktree-anchor.test.sh` → **38 passed, 0 failed**; `bash .github/scripts/tests/angular-formatting-gate.test.sh` → **14 passed, 0 failed**. Neither suite was edited, so S4 and S5 hold on the pre-fix suites.

**L4** — `CONTRIBUTING.md:15` records that `status:approved` is a human decision and not a label the author applies to its own request; #964 carried no labels when this branch was cut, and this record does not claim otherwise. The approval belongs to the maintainer and it gates the pull request, not this branch.

**L5** — 2026-11-18: the maintainer approved #964 on their instruction in session, and the label was recorded then (the issue's [approval comment](https://github.com/le03nava/LifeControl/issues/964#issuecomment-6996443890) says so in those words). Delivered as pull request **#974**, `type:docs`, closing #964; the branch carries `959991c4` (the three documents) and `dccc06e5` (this record).
