# ODD task: angular-build-hygiene

**Status**: merged — PR #133 (`chore/angular-build-hygiene` @ `28c077175`), 2026-09-21. No work left.
**Feature**: `angular-build-hygiene`
**Type**: build hygiene + source deduplication (tooling/quality, not product behavior)
**Branch**: `chore/angular-build-hygiene`, in the worktree `~/workspace/LifeControl-worktrees/chore-angular-build-hygiene` (the anchor stays on `main`)
**Base**: `main` @ `3aabfd0`
**Local doc**: `odd/tasks/angular-build-hygiene.md` (versioned)
**Requested by**: the user, after the dev image rebuild surfaced the Angular budget warnings: "como puedo solventar esas 3 cosas?".
**Decision**: locked with the user, 2026-09-20 — the four recommended options: recalibrate `initial` and measure the `Home` lazy route; extract the list-page SCSS into a shared partial and raise the component-style warning; clean Docker images plus build cache; document the `pgrep` anti-self-match note in `references/worktrees.md`.

## Problem

The dev rebuild (`./docker/scripts/deploy.sh dev start`) exposed five build warnings that had been firing on every build and in CI without anyone acting on them:

```text
bundle initial exceeded maximum budget. Budget 500.00 kB was not met by 350.07 kB with a total of 850.07 kB
company-list.scss    exceeded maximum budget. Budget 4.00 kB was not met by 658 bytes with a total of 4.66 kB
product-list.scss    exceeded maximum budget. Budget 4.00 kB was not met by 654 bytes with a total of 4.65 kB
supplier-list.scss   exceeded maximum budget. Budget 4.00 kB was not met by 328 bytes with a total of 4.33 kB
receipt-create.scss  exceeded maximum budget. Budget 4.00 kB was not met by  77 bytes with a total of 4.08 kB
```

**Root cause 1 — the budgets were never calibrated.** `life-control-app-angular/angular.json` still carries the Angular CLI scaffold defaults (`initial` warn 500 kB / error 1 MB, `anyComponentStyle` warn 4 kB / error 8 kB), untouched since the initial import commit `7533a8e` (2025-12-01). They are not a decision anyone made about this application.

**Root cause 2 — the `initial` metric is raw, not transferred.** The shipped production artifact is 850.07 kB raw but 194.66 kB of estimated transfer (gzip 4.4x on vendor JS/CSS). The budget measures raw bytes, so the number reads worse than the payload the browser actually downloads.

**Root cause 3 — three list pages carry duplicated stylesheets.** `company-list.scss` (287 lines) and `product-list.scss` (287 lines) are byte-identical except the `@use` depth, one page class, one grid class (3 occurrences) and two comments. `supplier-list.scss` (272 lines) is the same file minus a `.skeleton-placeholder` block. A shared-partial precedent already exists in this repository: `src/shared/styles/_store-entity-page.scss` (used by the three store pages, with the rationale written in its own header) and `_form-layout.scss` (used by ~10 features).

**Root cause 4 — dead CSS.** `.skeleton-placeholder` is rendered by nobody: `grep -rn 'skeleton-placeholder' src/` matches only the two `.scss` files that declare it, never a template. Both pages pay for it in their emitted component CSS.

**Operational finding, separate from the code:** `docker/scripts/cleanup.sh docker <env>` starts with `compose down --remove-orphans`, so it tears down the running stack. `AGENTS.md` describes it as "Clean Docker resources for this project (containers, images, networks)", which reads as non-disruptive. The label-scoped prune itself is correct and safe: 147 dangling images dropped to 6, and the 6 leftovers belong to other projects.

## Scope

1. `life-control-app-angular/angular.json` — recalibrate the `production` budgets so they act as a regression tripwire instead of permanent noise.
2. `life-control-app-angular/src/shared/styles/_entity-list-page.scss` (new) — the parameterized mixin for entity list pages, carrying the rationale in its header like `_store-entity-page.scss` does.
3. The three list pages — reduced to a `@use` plus an `@include`, with the dead `.skeleton-placeholder` block dropped.
4. `life-control-app-angular/src/app/app.routes.ts` — `Home` lazy, only if measurement proves it reduces the initial total.
5. `.agents/skills/project-conventions/references/worktrees.md` — a short note on verifying background processes without the `pgrep` self-match.

## Non-goals

1. **Reducing the initial payload structurally.** The 850 kB is Angular + Material + CDK + Keycloak + app shell; the vendor chunk alone is 312.81 kB (753 `mat-mdc`, 179 `cdk`, 11 `keycloak` hits). Deferring Keycloak means redesigning the auth bootstrap; with 194.66 kB of real transfer, that risk buys almost nothing. Not in scope.
2. **Changing any rendered style.** The extraction is behavior-preserving: only provably dead CSS is removed. No component gets new or different declarations.
3. **Aligning the three pages' `.error-state` with `shared/ui/error-banner`.** The store pages made that move; these three still define their own error chrome. It is a real follow-up, but it changes rendered UI and belongs in its own change.
4. **`inlineCritical`.** `production.optimization.styles.inlineCritical` is explicitly `false`. Changing it alters rendering behavior and is not covered by "fix the warnings".
5. **Touching the `receipt-create.scss` source.** It is 77 bytes over a default budget; raising the warning is the honest call at this size.
6. **Touching Docker volumes.** 53 volumes report 2.246 GB "reclaimable" with 0 active; they may hold data and `AGENTS.md` marks the volume path destructive.

## Tasks

| # | Task | Status | Evidence |
| --- | --- | --- | --- |
| 1 | Capture the production build baseline: initial total and the four per-component CSS sizes | done | `npm run build` → initial `850.07 kB`; company-list `4.66 kB`, product-list `4.65 kB`, supplier-list `4.33 kB`, receipt-create `4.08 kB`; identical to the container build (`850.07 kB`) |
| 2 | Extract `_entity-list-page.scss`, rewrite the three list pages, drop dead `.skeleton-placeholder` | done | New `src/shared/styles/_entity-list-page.scss`; the three pages are now a `@use` plus an `@include`. Tracked diff: 849 deletions, 21 insertions |
| 3 | Prove the extraction is behavior-preserving via the emitted per-component CSS size | done | With the warning temporarily at 1 kB so every size prints: `company-list` 4.66 → **4.34 kB**, `product-list` 4.65 → **4.34 kB**, `supplier-list` 4.33 → **4.34 kB**. All three converge on the size of `supplier-list`, the only file with no dead block — the mixin emits the same rules and the entire delta is the dead CSS |
| 4 | Recalibrate the `production` budgets and confirm a clean build | done | `initial` 500 kB/1 MB → **900 kB/1.2 MB**; `anyComponentStyle` 4 kB/8 kB → **6 kB/10 kB**. `npm run build` → **0 warnings, 0 errors**, initial `850.07 kB` / `194.66 kB` |
| 5 | Measure `Home` lazy against the baseline | done — reverted | initial `850.07` → **`846.46 kB`** (−3.61 kB, −0.42%); transfer `194.66` → **`193.64 kB`** (−1.02 kB). The lazy chunk is fetched immediately on `/`, so this is a reshuffle with one extra request, not a win. Judged noise and **reverted**: `app.routes.ts` is unchanged |
| 6 | Document the background-process verification note in `references/worktrees.md` | done | New `## Verifying a background process` section (+10 lines) with the capture-`$!`, bracket-trick and `ps` table |
| 7 | Run the repository gates | done | `npm run lint` → "All files pass linting"; `npm run test:coverage:check` → **113 files / 2131 tests passed**, statements 92.30%, branches 74.64%, functions 87.92%, lines 92.30%, all OK. `npx prettier --check` clean on the five touched source files |
| 8 | Docker cleanup: images plus build cache, with the stack restored afterwards | done | images 182 → 41, dangling 147 → 6 (leftovers belong to other projects, so the label scope held); build cache 13.31 GB → 0 B; `deploy.sh dev up` restored all 11 services healthy, images still stamped `3aabfd0f3a4c` |
| 9 | Confirm branch and work-unit commit plan with the user | done | Worktree `~/workspace/LifeControl-worktrees/chore-angular-build-hygiene`, branch `chore/angular-build-hygiene`, four work-unit commits: `6d161e3` budgets, `29c0814` shared style partial, `ec690a1` operational docs, plus this document |

## Verification plan

1. **Equivalence of the style extraction, proved by the budget report itself.** Build with the *old* 4 kB warning still in place, before recalibrating. The emitted per-component sizes are the check: after dropping the provably dead `.skeleton-placeholder`, `company-list` and `product-list` must land at ≈ `supplier-list` (4.33 kB), because the three files then differ only in class-name lengths. A larger drop means the extraction removed live rules and must be rejected.
2. **Clean build after recalibration.** Zero budget warnings and zero errors from `npm run build`.
3. **`Home` lazy is a measured decision, not an assumption.** Compare the initial total after the change against the 850.07 kB baseline; if it does not drop, revert the route.
4. **Repository gates.** `npm run lint` and `npm run test:coverage:check` both green.
5. **Docker stack is not collateral damage.** `docker ps` shows the 11 services healthy after cleanup, with the three application images still carrying `org.opencontainers.image.revision=3aabfd0...`.

## Risks

| Risk | Mitigation |
| --- | --- |
| The SCSS refactor silently changes a rendered style | The equivalence check in step 1 above compares the emitted CSS size per component against `supplier-list`, the file with no dead block. A drop larger than the dead block's share fails the check |
| Raising the initial warning hides a future regression | 900 kB leaves 50 kB (≈6%) of headroom over today's 850.07 kB; the error at 1.2 MB keeps a hard ceiling |
| `Home` lazy changes first-paint behavior | Settled by measurement, not by opinion: the route was made lazy, measured at −0.42% of initial with one extra request on the landing path, and **reverted**. No runtime change ships |
| Cleaning Docker images breaks the running stack | Already exercised: the cleanup tore the stack down and `deploy.sh dev up` restored it healthy. Commit identities are stamped in the images, so the running revision is verifiable |

## Gaps found

1. **`angular.json` is not prettier-clean and was not before this change.** `npx prettier --check angular.json` fails on the current file, and it already failed on `HEAD` (`git show HEAD:... | npx prettier --check --stdin-filepath angular.json` → exit 1). `lint-staged` only globs `src/**`, so nothing enforces it. Left untouched on purpose: reformatting would add a whole-file diff unrelated to the budget change.
2. **`AGENTS.md` understated what `cleanup.sh docker <env>` does — resolved here.** The script's first step is `compose down --remove-orphans`, so it stops the running stack; the documented description only mentioned cleaning containers, images and networks. `AGENTS.md` now records the teardown, the label scope, and the `deploy.sh <env> up` restore step.
3. **The three list pages still own their `.error-state` chrome** while the store pages delegate it to `shared/ui/error-banner`. Aligning them is a rendered-UI change and belongs in its own unit of work.
4. **The initial payload has no structural headroom left.** The vendor chunk is 312.81 kB of Angular + Material + CDK + Keycloak. Any future reduction means deferring the auth bootstrap, not trimming components.

## Outcome

Five warnings on every build and in CI went to zero, and three duplicated stylesheets collapsed into one parameterized partial: **849 lines deleted, 21 inserted** across the tracked pages, plus the new partial. The per-component CSS size proves the equivalence, because the three pages now emit the same size as the one file that never carried the dead block.

The `Home` lazy experiment is the other half of the record: it was measured rather than assumed, it moved a real number in the right direction, and it was still correctly rejected, because a 0.42% initial saving paid for with an extra request on the landing path is noise. Measurement is only worth something when it is allowed to say no.

The Docker hygiene is operational rather than source: 141 dangling images and 13.31 GB of build cache reclaimed, with the label-scoped prune leaving other projects' images alone and the stack restored healthy on the same revision.

## Landing

Four work-unit commits on `chore/angular-build-hygiene`, never on `main`: `6d161e3` recalibrates the budgets, `29c0814` carries the style partial, `ec690a1` records the operational findings, and the commit holding this document closes the set. The anchor stayed on `main` and was returned to a clean tree.

One link in the evidence chain is worth stating explicitly, because it is what makes the gate results apply to the commits rather than to a working copy that could drift afterwards: every committed source blob was compared against the hash of the bytes that passed `npm run lint`, `npm run test:coverage:check` and the equivalence build. All four style blobs matched, with `prettier --write` from the pre-commit hook having run in between.
