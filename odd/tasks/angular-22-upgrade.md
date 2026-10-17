# ODD feature: angular-22-upgrade

**Repository**: LifeControl — `life-control-app-angular/` (Angular + Material front), plus `.github/workflows/angular-ci.yml`, `life-control-app-angular/Dockerfile` and `playwright.config.ts` as far as the toolchain reaches them.
**Status**: planned — nothing has been implemented and no source file has been changed; the work is scoped below and starts at T1 on an explicit go-ahead, with D1–D4 locked in L5. This header makes no claim about push or PR state; see the log.
**Created**: 2026-10-16 · **Base**: `main` @ `8cde31a`
**Branch**: slice A `chore/angular-21-upgrade`, slice B `chore/angular-22-upgrade` (created after A merges and rebased on `main`). One worktree per branch, per `references/worktrees.md`; the anchor stays on `main`.
**Origin**: the user asked for an analysis ("puedes analizar que se tiene que hacer para actualizar el front a angular 2 a angular 22") and then authorized this record ("ok genera el odd"). The analysis itself is read-only work already done; its measured evidence is in L3 and in `## Evidence`, not re-derived here.

## Specs

S1 — **Destination**: "puedes analizar que se tiene que hacer para actualizar el front a angular 2 a angular 22".

Pinned interpretation, stated so it is falsifiable: the front ends on **Angular 22** (`@angular/core` 22.2.3 is the published `latest`). The "2" in the request is the typo for the major the project is on today (**20**, installed `20.3.31`), which is the only reading that describes a forward upgrade. Nothing in S1 asks for a product feature, a refactor or a UI change.

S2 — **"actualizar el front" means the front keeps working**: the upgrade is done when `npm run lint`, `npm run build`, `npm run test:coverage:check` and `npm run test:e2e` are green on the upgraded tree, and the authenticated app still works end to end (Keycloak login, role-gated menus, tenancy claims). Stated as a spec because the honest definition of "upgrade" is not "the version string changed".

S3 — **No behaviour change is requested**: this is a version migration, not a redesign. Any observable difference the upgrade forces (hydration of the change-detection default, router parameter inheritance, host-style removal, `withFetch` removal) is a *side effect to be verified*, never an invitation to restructure. Refactors that the migration does not force belong to their own record.

The two real choices this migration opens (Node line, deprecated `@angular/animations`) are **not** specs: they are D1 and D2 below, and they are the user's to make.

## Evidence (measured 2026-10-16, read from the tree and from the published packages)

Confirmed with `satisfies`, matching how this repository treats measured claims.

| Fact | Measurement |
| --- | --- |
| Today | `@angular/core` **20.3.31** installed, declared `^20.3.0`; `@angular/material` and `@angular/cdk` pinned exact at `20.2.10`; TypeScript `~5.9.2`; Vitest `3.1.4`; `keycloak-angular` `^20.0.0`; `zone.js` `^0.16.2` (tests only); the app is **already zoneless** (`provideZonelessChangeDetection()` in `src/app/app.config.ts`) |
| Destination | `@angular/core` `latest` = **22.2.3**; `v21-lts` = 21.2.25; Material `latest` = 22.2.3, `v21-lts` = 21.2.14. Angular 21.0.0 shipped 2025-11-19, 22.0.0 on 2026-06-03 |
| `ng update` granularity | one major per invocation, so 20 → 21 → 22 is two landings, not one |
| Engine, Angular 21 | `^20.19.0 \|\| ^22.12.0 \|\| >=24.0.0` — the local Node **v24.10.0 already satisfies 21** |
| Engine, Angular 22 | `^22.22.3 \|\| ^24.15.0 \|\| >=26.0.0` — the local Node **v24.10.0 does not satisfy 22**; `@angular/cli` 22 states a minimum of v22.22.0 / v24.13.1 |
| TypeScript | `@angular/compiler-cli@22` peer = `>=6.0 <6.1` (TS **6.0.3**; `latest` is 7.0.2 and is **out of range**). `@angular/compiler-cli@21` peer = `>=5.9 <6.1`, but `@angular/build@21` peer = `>=5.9 <6.0`, so slice A stays on 5.9 |
| Vitest | `@angular/build@21` peer = **`^4.0.8`** (today's 3.1.4 is unsupported); `@angular/build@22` peer = **`^4.0.8 \|\| ^5.0.0`**. So **vitest 4 is forced in slice A and vitest 5 is optional in slice B** — dropping to the minimum avoids the v5 breakages |
| Lint toolchain | `typescript-eslint@8.46.4` declares `typescript >=4.8.4 <6.0.0`, so it **cannot run under TS 6**; the first 8.x that supports TS 6 is 8.60.0 (use `^8.71.2`). `angular-eslint@22.6.0` accepts eslint `^9 \|\| ^10` |
| TestBed init | `@angular/build` **generates its own `initTestEnvironment`** in 20.3.36, 21.2.25 and 22.2.3 (`[BrowserTestingModule, TestModule]` + `platformBrowserTesting()`, with `errorOnUnknownElements: true` and `errorOnUnknownProperties: true`). In 21/22 it injects `provideZoneChangeDetection()` **only when the build target's `polyfills` contain `zone.js`**, and guards the init with `Symbol.for('@angular/cli/testbed-setup')`. The Karma→Vitest migration text in `@schematics/angular@22` states the same requirement directly: *"ensure you remove any TestBed.initTestEnvironment calls from this file as the builder now handles test environment initialization automatically"* |
| Components | **126** component files, **all 126** declare `changeDetection`; **zero** use `ChangeDetectionStrategy.Default` |
| Templates | no `*ngIf`/`*ngFor`, no `[values]`, no `[data-*]` bindings, no duplicate `selector:` strings |
| Removed APIs not in use | `ComponentFactoryResolver`/`ComponentFactory`, `createNgModuleRef`, `ChangeDetectorRef.checkNoChanges`, `provideRoutes()`, Hammer.js, `TitleStrategy` overrides, `CanMatch` classes, `paramsInheritanceStrategy` override, `Material` animation symbols/`MatCommonModule`/`MATERIAL_SANITY_CHECKS`, `MatListOption.checkboxPosition` |
| Specs | **179** spec files, ~2529 tests; **0** `fakeAsync`, **0** `waitForAsync` (which is what makes the zoneless test path viable); 79 files use `compileComponents`; 1044 `vi.fn` call sites; the only `vi.mock` occurrence is inside a comment |
| Node use in specs | spec files call `process.getBuiltinModule('fs')` and `process.cwd()` (`breakpoints.spec.ts`, `employee-detail.spec.ts`, `test-timeouts.spec.ts`) — these need `node` in `types` once TS 6 defaults `types` to `[]` |
| Coverage read path | `scripts/check-coverage.mjs` reads `coverage/coverage-summary.json`, produced by the `json-summary` reporter declared in `angular.json` |
| Deprecated in 22 | `withFetch()` (fetch is the default backend); `reportProgress`; the whole **`@angular/animations`** package (`npm view @angular/animations@22.2.3 deprecated` returns the message pointing at `animate.enter`/`animate.leave`) |
| Lockfiles | `life-control-app-angular/` carries **both** `package-lock.json` and `bun.lock` (stale since 2026-09-12); CI and the Dockerfile use `npm ci --legacy-peer-deps` |
| Repo guards that touch this work | `src/test-timeouts.spec.ts` reads `src/test-setup.ts` as source and asserts only the `vi.setConfig` wiring and the absence of numeric timeouts — it does **not** assert the `zone.js` imports, so the setup rewrite is compatible with it |

## Scope

1. **Slice A — Angular 20 → 21** (`chore/angular-21-upgrade`): dependencies, Node line, lint toolchain, the test environment rewrite (zone.js out, builder-owned TestBed init), Vitest 4, and the suite fallout that comes with them.
2. **Slice B — Angular 21 → 22** (`chore/angular-22-upgrade`): dependencies, TypeScript 6 + `typescript-eslint`, the `withFetch`/router/forms/compiler fallout, host-style visual verification, the deprecated-animations decision, and CI/Docker/e2e hardening.
3. The delivery surface the toolchain reaches: `.github/workflows/angular-ci.yml`, `life-control-app-angular/Dockerfile`, `playwright.config.ts`, `.nvmrc` (new).

## Non-goals

1. **Adopting anything new that Angular 22 offers.** Signal forms, `@Service`, `provideWebMcpTools`, the new ARIA primitives, `animate.enter` as a *design* choice, new `@defer` triggers: none of it is requested by S1 and none of it is needed to make the migration land. The animations migration (T15) is the exception, and only because the package it uses is deprecated (D2).
2. **The `PORT`-priority behaviour itself**: T17 pins the e2e command so `ng serve` is deterministic. Redesigning how the dev server is launched is not requested.
3. **Un-pinning Material/CDK.** They are exact pins today (`20.2.10`); the upgrade moves the pin, it does not change the pinning convention (these two must always match each other exactly).
4. **A single-PR migration.** `ng update` cannot cross two majors and each landing needs its own green CI run to be diagnosable.
5. **Rewriting spec structure.** Zoneless/`errorOnUnknown*` fallout in specs is fixed minimally; test-quality refactors are separate work.
6. **Backend or gateway versions.** This record is the front only.

## Open decisions (the user's, not mine)

**Locked 2026-10-16** (L5): the user accepted all four recommendations — **D1 = (b) Node 24.15+**, **D2 = (a) migrate the animations in slice B**, **D3 = (a) stay on Vitest 4**, **D4 = (a) `package-lock.json`**. Locked is not started: T1 waits for an explicit go-ahead, and the rows below keep the alternatives on record because a later slice may need to revisit one.

| # | Decision | Options | Recommendation |
| --- | --- | --- | --- |
| **D1** | Node line to standardise on in `.nvmrc`, `engines`, CI and the Dockerfile | (a) `22.22+` LTS (b) `24.15+` LTS (c) `>=26` | **(b) 24.15+**: it already satisfies Angular 21 and 22, and the local machine is on 24.10 — the smallest movement. Pinning the *destination* line in slice A avoids migrating Node twice. (a) also works and is the more conservative LTS. |
| **D2** | What to do with `@angular/animations`, deprecated in 22 (used only by the notification toast via `provideAnimationsAsync()` + `[@notificationAnimation]`) | (a) migrate in slice B to `animate.enter`/`animate.leave` (b) keep the deprecation as a follow-up record | **(a)**: it is one component (`shared/ui/notification-toast/`) and leaving a deprecation behind means re-opening the toolchain context later. (b) is defensible if slice B is already at review capacity. |
| **D3** | Vitest in slice B | (a) stay on 4 (b) bump to 5 | **(a) stay on 4**: `@angular/build@22` accepts `^4.0.8`, and staying skips the v5 breakages (`clearMocks` default `true` against 1044 `vi.fn` sites, class mocks keeping the prototype chain, un-awaited async assertions failing). Bumping to 5 is a deliberate separate unit, not a side effect of the Angular upgrade. |
| **D4** | Which lockfile survives | (a) `package-lock.json` (b) `bun.lock` | **(a)**: CI and the Dockerfile already use `npm ci`; the `bun.lock` is dated 2026-09-12 and unused by any pipeline step. |

## Tasks

Route legend: `inline` = parent session; `worker`/`gentle-ai-verify` = subagent. `S#` links each task to a spec; `D#` marks the decision a task consumes.

### Slice A — Angular 20 → 21 (`chore/angular-21-upgrade`)

| ID | Task | S# | Route | Commit |
| --- | --- | --- | --- | --- |
| **T1** | Dependencies to 21: `@angular/{core,common,compiler,compiler-cli,forms,platform-browser,platform-browser-dynamic,router,animations}` 21.2.25, `@angular/{cdk,material}` pinned **21.2.14**, `@angular/build` + `@angular/cli` 21.2.25, `angular-eslint` 21.x, `keycloak-angular ^21`. TypeScript **stays 5.9** (`@angular/build@21` peer is `>=5.9 <6.0`). Lockfile decision applied (D4). | S1, D4 | inline | tbd |
| **T2** | The Node line, once: `.nvmrc` (new), `engines.node` in `package.json`, `actions/setup-node` in `angular-ci.yml`, the `FROM node:` tag in the Dockerfile — all pinned to the D1 line. Verified by running the whole gate on that Node, not by reading the range. | S1, D1 | inline | tbd |
| **T3** | The test environment stops being hand-rolled: `src/test-setup.ts` keeps `import '@angular/compiler'`, `vi.setConfig(...)` and the `matchMedia` stub, and **drops** the `zone.js`/`zone.js/testing`/`zone.js/plugins/vitest-patch` imports, the `getTestBed().initTestEnvironment(...)` call and the `@angular/platform-browser-dynamic/testing` import (the builder owns it now, with `errorOnUnknownElements/Properties: true`). `zone.js` leaves `devDependencies`, `@angular/platform-browser-dynamic` leaves `dependencies` (its only consumer was this file), and **`--legacy-peer-deps` disappears from the Dockerfile and the CI workflow** — that flag existed solely for zone.js's peer range. **RED first**: confirm the suite actually fails with the duplicate init / zone-based path before removing it, so the removal is evidence and not a guess. | S1 | inline | tbd |
| **T4** | Vitest 3.1.4 → **4.1.12** + `@vitest/coverage-v8` 4.1.12 (jsdom only if its peer demands it). Read the v4 migration guide against the APIs this repo actually uses (`vi.fn` 1044x, `vi.spyOn`, `vi.useFakeTimers`/`advanceTimersByTime`, `vi.setConfig`) and fix the fallout. Confirm `coverage/coverage-summary.json` still lands where `scripts/check-coverage.mjs` reads it. | S1, S2 | inline | tbd |
| **T5** | Spec fallout from the stricter test environment: `errorOnUnknownElements`/`errorOnUnknownProperties` are now on, and the TestBed is zoneless by default in 21+ even if zone.js were loaded. Fix minimally (missing imports; `await fixture.whenStable()` where change detection is now scheduled rather than forced) — same shape as the CLI's own zoneless migration. No spec restructuring. | S2, S3 | inline/worker | tbd |
| **T6** | Router timing review, per the v21 note that navigations may take extra microtasks and that `TestBed` now supplies a fake `PlatformLocation` backed by the Navigation API. The specs that assert on navigation are the ones to look at; this is a review, not a rewrite. | S2 | inline | tbd |
| **T7** | Slice A gate on the committed bytes: `npm run lint`, `npm run build`, `npm run test:coverage:check`, `npm run test:e2e`, plus the manual smoke of the authenticated flows (login, role-gated menu, a tenancy-scoped page). CI green on the slice A PR before slice B starts. | S1, S2 | inline | tbd |

### Slice B — Angular 21 → 22 (`chore/angular-22-upgrade`)

| ID | Task | S# | Route | Commit |
| --- | --- | --- | --- | --- |
| **T8** | Dependencies to 22: `@angular/*` **22.2.3**, `@angular/{cdk,material}` pinned 22.2.3, `@angular/build` + `@angular/cli` 22.2.3, `angular-eslint` **22.6.0**, `keycloak-angular ^22` (its v21/v22 releases are Angular-version support only — no API change), `eslint` bumped only if `angular-eslint@22` requires it. | S1 | inline | tbd |
| **T9** | TypeScript **6.0.3** + `typescript-eslint ^8.71.2` (8.46.4 declares `typescript <6.0.0` and breaks under TS 6). Then the TS 6 config fallout, each item verified rather than blanket-applied: `tsconfig.spec.json` needs `"node"` in `types` because TS 6 defaults `types` to `[]` and the specs use `process.getBuiltinModule('fs')`/`process.cwd()`; `baseUrl` is deprecated, so it comes out of `tsconfig.json` and the `@shared/*`/`@features/*`/`@core/*`/`@app/*` `paths` get their `./src/...` prefix; `rootDir` now defaults to the tsconfig directory; review `target`/`module`/`noUncheckedSideEffectImports` against the explicit values already present. | S1, S2 | inline | tbd |
| **T10** | HTTP: drop `withFetch()` from `src/app/app.config.ts` (deprecated in 22 — fetch is the default backend now). `withXhr` is **not** needed: no `reportProgress`, no `HttpEventType`, no upload-progress consumer anywhere in `src/`. | S1, S3 | inline | tbd |
| **T11** | Change detection: in 22 a component with an undefined `changeDetection` becomes `OnPush`. All 126 components declare it and none uses `Default`, so the expectation is *no fallout* — but the claim gets checked, not assumed: run the `ChangeDetectionStrategy.Eager` migration and diff the result against the expectation. | S2, S3 | inline | tbd |
| **T12** | Router/forms/compiler fallout: `paramsInheritanceStrategy` now defaults to `'always'`, so verify the nested route trees that read `params`; the two new compiler diagnostics (`nullishCoalescingNotNullable`, `optionalChainNotNullable`) will fire on the `?.`/`??` templates — resolve them, or disable them in the tsconfig **with the reason recorded in the log** if resolving is out of scope. The v22 `min`/`max` string rule does not apply (numeric `Validators` + static HTML attributes only). | S2, S3 | inline | tbd |
| **T13** | Visual verification of the one change no test can catch: v22 removes host styles that appear unused, which can silently unstyle DOM outside the component. Compare the rendered app against the pre-upgrade build (the pages that own shared SCSS, plus the toast), and record what was compared. | S2, S3 | inline | tbd |
| **T14** | Independent verification of the named high-risk claims (see `## Risk`): that slice B's toolchain/runtime changes cannot emit a broken production build, and that the shared-style surface has no unverified regression. | S1, S2 | `gentle-ai-verify` | tbd |
| **T15** | The deprecated animations package, per D2: migrate `shared/ui/notification-toast/notification-animation.ts` + `notification-toast.html` to `animate.enter`/`animate.leave` and drop `provideAnimationsAsync()` — or record the deferral explicitly if the user picks D2(b). Note that Angular Material 21+ no longer uses `@angular/animations` at all, so this component is the package's only consumer. | S1, D2 | inline | tbd |
| **T16** | Vitest in slice B, per D3: stay on 4 (no work) or bump to 5 with its three known breakages read against the real usage (default `clearMocks: true` vs 1044 `vi.fn` sites; class mocks keeping the prototype chain; un-awaited async assertions failing the test). | S1, D3 | inline | tbd |
| **T17** | Delivery surface: `@angular/cli@22` gives the `PORT` environment variable priority over `ng serve --port`, and `playwright.config.ts` launches the server as `npm start -- --port $E2E_PORT`. Make the e2e launch deterministic (set/clear the variable explicitly) instead of depending on the ambient environment. Confirm the Dockerfile still builds and serves on the D1 Node line without `--legacy-peer-deps`. | S1, S2 | inline | tbd |
| **T18** | Slice B gate on the committed bytes: the full gate plus the manual smoke again on 22. This is the last slice, so it also closes the feature: the record's log gets the dated delivery evidence, the Engram mirror is updated, and the `Risk:` line for the diff is written. | S1, S2 | inline | tbd |

## Risk

`Risk: item 5` (delivery/environment — Node engine, dependency graph, CI workflow and Dockerfile all move), `Risk: item 3` (the upgrade changes a shared contract: every component, route and style in the front is affected by the framework's new defaults) and `Risk: item 6` for the host-style surface specifically (no test in this suite would catch an unstyled page; it is found by looking at it).

Per the Verification rule, the named high-risk items are what T14's independent verification is for. T13 is deliberately *not* delegated: a visual comparison needs the human-visible artifact.

## Gaps (known, carried openly)

1. **The duplicate TestBed init is currently benign and becomes strict.** The builder generates its own init in 20.3.36 as well, and the suite is green today with both present. The mechanism (order of the builder's virtual entry vs. `setupFiles`) was **not** fully established read-only. T3 therefore observes the failure before removing it, and the log records what was actually seen instead of what was predicted.
2. **The Node decision cannot be validated from here.** D1's options were checked against the published engine ranges; whether the team's machines, the CI runner image and the Docker base tag all resolve to the chosen patch level is verified in T2.
3. **The `ChangeDetectionStrategy.Eager` migration's own behaviour** (what it rewrites in a codebase where every component already declares a strategy) is not knowable without running it; T11 treats "no diff" as a *result to confirm*, not as an assumption.
4. **Slice A's Vitest 4 fallout is unmeasured.** The v4 migration guide's relevant items were read, but which of the 179 specs actually fail is only knowable by running them (T4/T5).
5. **Both slices are large.** Slice A is a new toolchain and slice B is a language-version bump; if review capacity forces it, slice B can be split again at T10 into "framework 22" and "TypeScript 6", since TS 6 is the only item in the slice with its own independent failure mode.

## Log

**L1** (2026-10-16, the user's original request, verbatim):

> puedes analizar que se tiene que hacer para actualizar el front a angular 2 a angular 22

**L2** (2026-10-16, authorization, verbatim):

> ok genera el odd

Scope of this authorization, stated so it is not over-read: it authorizes **this record**. It is not authorization to start editing source. T1 begins when the user says so, and D1–D4 are the user's calls, not the parent session's.

**L3** (2026-10-16, evidence gathered read-only before the first write — measured, not inferred).

The analysis behind this record read the published CHANGELOGs of `@angular/core` (v21.0.0, v22.0.0 breaking changes and deprecations), `@angular/components` (v21.0.0, v22.0.0) and `@angular/cli` (v21.0.0, v22.0.0), the TypeScript 6.0 release notes, the Vitest 5 migration guide, the npm registry (`dist-tags`, `engines`, `peerDependencies`, `deprecated`), and the packages themselves (`@angular/build@21.2.25`, `@angular/build@22.2.3`, `@schematics/angular@22.2.3`) unpacked to read the generated TestBed init. Everything in `## Evidence` was read from the tree or from those artifacts.

Three findings changed the shape of the plan relative to the first pass, and they are why the measurements are in the record rather than in a summary:

- **Vitest 5 is not required.** `@angular/build@22` accepts `^4.0.8 || ^5.0.0`, so slice B can stay on 4 and skip the v5 breakages entirely (D3).
- **The test-environment rewrite belongs to slice A, not B.** `@angular/build@21.2.25` already generates its own `initTestEnvironment` and already injects `provideZoneChangeDetection()` when the build target's `polyfills` contain `zone.js` — with no `polyfills` declared in `angular.json`, leaving `zone.js` in the setup is what keeps the tests *out* of the zoneless path production actually runs.
- **The `that flag existed for zone.js` connection is real**: `--legacy-peer-deps` appears in the Dockerfile and in the CI workflow with a comment naming zone.js's peer range as its only reason, so T3 removes the flag instead of preserving cargo.

Two things were checked specifically to avoid making work: `src/test-timeouts.spec.ts` reads `src/test-setup.ts` as source but asserts only the `vi.setConfig` wiring and the absence of hardcoded numeric timeouts, so the setup rewrite does not break the guard; and the 126 components all declare `changeDetection` with none using `Default`, so the v22 default-OnPush change is expected to be a no-op that T11 confirms rather than a migration.

**L4** (2026-10-16, scope decision recorded rather than hidden): this record plans two sequential branches where one might have been expected. `ng update` cannot cross two majors in one run, and each landing needs its own green CI to be diagnosable — a single 20→22 diff would mix an unsupported-toolchain failure with a language-version failure in one red run.

**L5** (2026-10-16, decisions locked and the go-ahead withheld, verbatim):

> estan bien las recomendaciones, pero no arranques aun

Read exactly as written: **D1–D4 are accepted** (24.15+, animations migrated in slice B, Vitest 4, `package-lock.json`) and **work has not started**. No branch was created, no file under `life-control-app-angular/` was touched, and no dependency was installed — the whole record is still an empty plan plus its evidence. T1 begins on the user's explicit go-ahead, not on this entry.

**L6** (2026-10-16, session closed for a later one, verbatim):

> lo dejamos para otra sesion solo dame el resumen de lo que se tiene que hacer y las unidades de trabajo

Handoff state, so the next session does not reconstruct it from chat: **nothing was implemented**, `main` is still at `8cde31a`, and the working tree was left as it was found. Resume order is `mem_context` → feature-scoped `mem_search` for `odd/angular-22-upgrade/tasks` (Engram observation 2747 carries the whole plan and D1–D4) → `mem_get_observation` → **this file, read to the end of `## Log`** → T1. Step 0 of the next session is reading the RDD switch (`gentle-ai review mode status`, read-only): its state was never read here and it decides whether the work units below get a native review candidate.

The work units that group the 18 tasks, in order, are: A1 (`chore(deps): pin Node 24.15 and keep a single lockfile` = T2+D4), A2 (`chore(deps): upgrade Angular and Material to 21` = T1), A3 (`test(build): let the unit-test builder own the TestBed` + `test(deps): upgrade Vitest to 4` = T3+T4 — this unit carries `Risk: item 5` and gets its own commits so a review can take it alone), A4 (`test(specs): satisfy the stricter test environment` = T5+T6), then the slice-A gate T7; B1 (`chore(deps): upgrade Angular and Material to 22` = T8), B2 (`chore(deps): move to TypeScript 6` = T9), B3 (`refactor: absorb the v22 runtime defaults` = T10+T11+T12), B4 (`refactor(ui): animate the toast natively` = T15), B5 (`chore(ci): make the e2e launch and the Docker build deterministic` = T17 — `Risk: item 5`, own commits), then the slice-B gate and close T18. T13 (visual comparison), T14 (`gentle-ai-verify`) and T16 (Vitest stays at 4) are checks, not units: they produce evidence, not commits.

**L7** (2026-10-17, the record is tracked, and only the record).

This file spent a day **untracked** in the anchor: invisible to every clone, to CI, and to the next session that read `odd/tasks/` — and it is also the untracked path that made a native `assess` answer `nativeRisk: unassessable` on 2026-10-17. It is now committed and pushed on `docs/angular-22-upgrade-record`, which **tracks the record and nothing else**: nothing was implemented, no dependency was installed, and T1 still waits for the explicit go-ahead `L5` withheld.

Two things changed in the same commit, both recorded rather than silent. **The header's clause *no branch exists* is gone**: this record now travels on a branch, so the clause would have been false on arrival, and the durable replacement states what remains instead. And **the five destination measurements the plan rests on were re-measured before publishing** — `@angular/core@latest` **22.2.3** with engines `^22.22.3 || ^24.15.0 || >=26.0.0`, `@angular/material@latest` **22.2.3**, `@angular/build@22.2.3` peer `vitest ^4.0.8 || ^5.0.0`, and the local Node **v24.10.0** that does not satisfy the v22 engine — **all five unchanged**, so `## Evidence` is current as of today and not only as of 2026-10-16. A later measurement that contradicts a row above belongs in a new dated entry here, never in a rewrite of that row.
