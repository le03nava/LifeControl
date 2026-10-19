# ODD feature: e2e-keycloak-mock-ordering

**Repository**: LifeControl — `life-control-app-angular/e2e/fixtures/app.ts` (the shared e2e fixture module), with the seven specs it silently starved treated as the symptom.
**Status**: **merged** — one root cause, one file: PR **#417** (`fix/e2e-role-mock-auth` @ `b579d69`), merge commit `8514610`, 2026-10-18, so `main` carries the fix. The mechanism, the RED and the two GREEN runs are in L2. No work is left in this unit: the seven specs were never the defect and no spec was edited.
**Created**: 2026-10-18 · **Risk**: item 6 (no test would have caught this regression: the harness is what the tests run on, and the failure mode was "tests pass because they cannot reach the mock during the run", not a failing assertion in the touched file)
**Antecedent**: `odd/tasks/angular-22-upgrade.md` (L11 measured the seven failures as pre-existing and out of that migration's scope)

## Specs

S1 — the request, verbatim (the option the user picked from a two-option question about the seven pre-existing `test:e2e` failures):

> Arreglarlos ahora, como unidad aparte

Pinned reading: **fix them now**, in their **own unit** — not folded into the Angular upgrade, and not deferred to a follow-up record that nobody opens. "Now" is this session; "unidad aparte" is this branch and PR.

S2 — the definition of done is the suite itself: `E2E_PORT=4201 npx playwright test` reports **19 passed / 0 failed**, twice, and every one of the twelve previously passing specs keeps passing with its assertions untouched. A fix that makes the seven pass by weakening an assertion is a failure of this spec, not a pass.

S3 — no production change. The seven failures are a harness defect; the app's behaviour, guards and templates are out of scope, and a production change found along the way is reported, never made here.

## Evidence (measured 2026-10-18, in `~/workspace/LifeControl-worktrees/fix-e2e-role-mock-auth` off `main`)

| Fact | Measurement |
| --- | --- |
| Baseline | `E2E_PORT=4201 npx playwright test --reporter=list` → exit 1, **12 passed / 7 failed**, on `main` before any change |
| The seven | `navigation.spec.ts:98`, `:121`, `purchases-receipts.spec.ts:60`, `:102`, `:115`, `scheduling-calendar.spec.ts:57`, `:77` |
| Not role-shaped, as it looked | `lc-admin` failed in `purchases-receipts.spec.ts` while passing in `navigation.spec.ts`; the two `lc-company` tests `navigation.spec.ts:111` and `:121` landed on opposite sides |
| The real predictor | file-load order: a test passed **iff its spec file was the first file its worker process loaded** |
| Mechanism, read in Playwright | `TestTypeImpl._hook` pushes the hook into the suite returned by `currentlyLoadingFileSuite()` (`node_modules/playwright/lib/common/index.js:2312-2318`), which `testLoader.loadTestFile` sets to the spec file it is loading (`:2862`); a helper module is evaluated once per worker process, so a `beforeEach` declared there binds to whichever spec file loaded it first |
| Mechanism, measured | an `appendFileSync` trace keyed by worker PID over the 19 tests: `MODULE-LOADED` exactly once per PID (10 PIDs), `HOOK-START` for only the 12 tests that passed |
| Consequence | with no Keycloak mock, `check-sso` never completes, the app renders `src/features/home/home.html`, and `keycloakRoleGuard` (`src/core/guards/auth-keycloak-guard.ts:35-38`) sends the browser to the **real** `localhost:8181`, which answers `Invalid parameter: redirect_uri` because port 4201 is not registered for `life-control-client` |
| Fix | install both mocks from an **overridden `page` fixture** instead of a module-level hook: a fixture resolves from each test's own fixture set, so its registration cannot depend on file-load order. 17 insertions, 5 deletions, one file, no change to `installKeycloakMock`/`installApiMock` and none to `playwright.config.ts` |
| GREEN | the same command, twice: exit 0, **19 passed / 0 failed** (16.7s and 17.5s) |
| Differential | all 12 previously passing specs pass with identical assertions: no `.skip`, no deleted test, no widened timeout, no loosened assertion |
| Environment trap, worth knowing | the host has a docker container on port **4200** serving this app, and `playwright.config.ts` sets `reuseExistingServer: !CI`, so `npm run test:e2e` silently tests that container instead of the worktree. `E2E_PORT=4201` is what makes the run local and real |

## Tasks

| # | Task | S# | Route | Evidence |
| --- | --- | --- | --- | --- |
| T1 | Reproduce the seven on `main` and stop calling them role-shaped | S1 | delegated writer | 12/7 baseline; the `lc-admin` and `lc-company` counter-examples above |
| T2 | Establish the mechanism before changing anything | S1 | delegated writer | the per-PID trace (`HOOK-START` on exactly the 12 passing tests) plus Playwright's `_hook`/`currentlyLoadingFileSuite` source |
| T3 | Move the mocks into a `page` fixture override and document the hazard in the module | S1, S3 | delegated writer | `e2e/fixtures/app.ts`, 17/5 |
| T4 | Prove the differential: same suite, same assertions, pre-fix vs post-fix | S2 | delegated writer + parent | 12/7 → 19/0, twice |

## Risk

`Risk: item 6` — nothing in this repository would catch a regression of this kind: the harness is the thing the tests stand on, and the original defect made tests fail, not pass silently, only because the app happened to render an unauthenticated page. The comment left in the module is the guard, and the honest mitigation is that the differential in S2 is what proves the fix did not buy its greens by weakening a spec.

## Log

**L1** (2026-10-18, the user's decision, verbatim): the option label quoted in S1. Context, so the scope is not over-read: it was chosen from a question stating that the seven failures are **pre-existing** — 12 passed / 7 failed on the pristine Angular 20 tree and the same seven names after the Angular 21 slice — and that `odd/tasks/angular-22-upgrade.md` S2 defines the upgrade as finished with `test:e2e` green. The answer, therefore, is "make that clause true as its own unit", not "fold it into the migration".

**L2** (2026-10-18, delivery evidence). Everything in `## Evidence` was measured in the worktree named there, on Node v24.10.0 with `npm ci --legacy-peer-deps` (777 packages, `package-lock.json` byte-identical afterwards — verified, because this branch must not carry a lockfile change). No spec file was touched, no production file was touched, and the diff is one file. The two GREEN runs and the pre-fix baseline used the same command, `E2E_PORT=4201 npx playwright test --reporter=list`, three times in a row on the same host.

## Header repair (2026-10-19)

The `**Status**:` line above was rewritten on 2026-10-19 by `odd/tasks/odd-stale-header-reconciliation.md` (issue **#434**), the unit that repaired six other records in the same pass and records the inventory and the verification there. It used to decline the delivery state: «What remains is the delivery of this unit, which is its own PR; the record makes no claim about push, PR or merge state.». That state became known when the work merged, so the line now carries it — PR **#417**, merge `8514610`, 2026-10-18. Nothing else in this record was touched.
