# ODD feature: unify-mobile-viewport-consumers

**Repository**: LifeControl — Angular frontend (`life-control-app-angular/**`). Two production
components migrated onto an existing shared helper, plus two spec files. No new module, no template
change, no route, no contract, no build config, no CI change.
**Status**: **implemented — 3 code commits plus this record on `refactor/unify-mobile-viewport-consumers`
off `main` @ `0725237`**, worktree `~/workspace/LifeControl-worktrees/refactor-unify-mobile-viewport-consumers`
(herdr workspace `w13`): `dc0152a` (the two consumers), `91f4d61` (the detail-table re-stub),
`e0f6c33` (the receipt-detail mobile case). **4 files, 84 insertions / 33 deletions** (production: 7 / 22;
specs: 77 / 11). Lint, build and the CI coverage gate pass at the tip. This header carries only dated
local facts and makes no claim about push or PR state — see F11.
**Created**: 2026-09-24
**Risk**: **low.** Nothing changes for a user: both idioms read the same constant, and the initial value
they expose is provably identical (D2). The one item that could have grown the slice did not: the
receipt-detail mobile path renders correctly.

## Why this exists

This is **F1** of `odd/tasks/mobile-viewport-observer.md`, the follow-up that PR #167 deliberately left
open. That slice closed the leaked-listener defect for the three list pages and, in doing so, made the
boundary's split visible: **five consumers, two idioms.**

| Idiom | Consumers | How it cleans up |
|---|---|---|
| Shared helper `observeMobileViewport()` (`window.matchMedia` + `signal` + `inject(DestroyRef)`) | `company-list.ts:52`, `product-list.ts:52`, `supplier-list.ts:52` | its own `onDestroy`, one place, pinned by a mutation-controlled test |
| CDK `BreakpointObserver` + `toSignal` | `receipt-detail.ts:105–110`, `detail-table.ts:118–123` | inside `BreakpointObserver`, invisible to this repo |

Measured on `main` @ `0725237` (read-only map, `gentle-ai-explore`): both CDK consumers used the
observer for **nothing else** — one `inject`, one `observe`, no second query, no other subscription —
and `toSignal` and the rxjs `map` likewise had no other use in those files.

Two idioms for one boundary is the defect. A sixth consumer would copy whichever one it happened to
read first, and the copy that picks CDK inherits an unaudited cleanup path while the copy that picks
the helper inherits an audited one. The boundary constant's own doc comment names both as intended
consumers (`breakpoints.ts:8–9`: "for JavaScript consumers (`window.matchMedia`, `BreakpointObserver`)"),
which is precisely the ambiguity to remove.

## Decisions

- **D1 — The helper wins; the two CDK consumers migrate onto it. The helper itself is not touched.**
  The decision is not "raw beats CDK" — it is that `observeMobileViewport()` is the **abstraction
  point**. After this slice all five consumers speak one API and the cleanup exists in exactly one
  place, the place that already has a mutation-controlled regression test
  (`mobile-viewport.spec.ts:80`). If this repo ever prefers CDK, the helper's body changes and all five
  migrate at once, with no consumer edits. Option B (rewrite the helper to wrap
  `BreakpointObserver` + `toSignal` and leave the two purchases files alone) was rejected on two
  grounds: it leaves the cleanup split across two owners, one of which this repo cannot audit or test,
  and it is far larger — **4 spec files and 5 spec blocks rewritten** against **1 spec file and 2
  blocks** here, with 8 further tests at residual risk from the re-stub.
- **D2 — Behaviour does not change, and this is provable, not assumed.** `BreakpointObserver.observe()`
  emits the current state **synchronously** on subscribe (`breakpoints-observer.mjs:154` builds the
  per-query observable with `startWith(mql)`, and `:122` passes the first emission straight through
  `concat(..., take(1))`), and `toSignal` subscribes synchronously inside the component field
  initializer (`rxjs-interop.mjs:226–227`). So `initialValue: false` was only ever a fallback; the
  consumer reads the real match state either way. The helper does the same
  (`mobile-viewport.ts:24–25`). **Both idioms expose the same initial value.** The only real difference
  is timing on subsequent changes: the helper sets the signal synchronously in its `change` handler,
  while CDK routes later emissions through `debounceTime(0)`. Nothing in either consumer reads that
  tick. Independently re-verified against the installed CDK and core sources, quoting those lines.
- **D3 — The two consumers keep their exact field shape.** `readonly isMobile = observeMobileViewport();`
  replaced the `toSignal(...)` field. The signals stayed `Signal<boolean>`, so no template and no
  downstream read changed. Imports that became unused were removed: `BreakpointObserver`, rxjs `map`,
  and `toSignal` — but `toSignal` only where genuinely unused (`receipt-detail.ts:2` also imports
  `rxResource`, which stays; `detail-table.ts:11` imported only `toSignal`, so that line went). The
  injected `breakpointObserver` field was deleted in both, and `inject` itself went from
  `detail-table.ts`'s `@angular/core` import because the file no longer uses it anywhere.
- **D4 — `detail-table.spec.ts` got a real re-stub, not a deleted one.** Its mobile describe block had
  overridden the `BreakpointObserver` provider. Once production stopped injecting it, that override
  became **inert** and the two tests would have asserted the mobile card against `isMobile === false`
  while claiming to test mobile. **A test that passes for the wrong reason is worse than a deleted
  test**, because it reads as coverage. The override was replaced with the same `window.matchMedia`
  stub the four other matchMedia specs share, installed before the component is created. Verified
  falsifiable: with the stub flipped to `matches: false`, both tests go red at the card-count assertion
  (`expected +0 to be 1`); restored, both pass.
- **D5 — `receipt-detail.spec.ts:249` was a test whose name overclaimed, and this slice fixed it rather
  than inheriting it.** It was called *"should render the mobile cards instead of the table on a narrow
  viewport"* but asserted `isMobile()` was `false` and that a `table` existed: it only ever exercised
  the desktop path, because no test in that file made the observer report `true`. (The same defect
  class the independent verification caught in PR #166's reload test.) The desktop test was renamed to
  say what it does, and **its assertion was repaired**: `queryAll(By.css('table')).toBeTruthy()` is
  vacuously true for any array, so it proved nothing; it now asserts a non-empty table and zero cards.
  A genuine mobile case was added asserting the mirror image. The implementing agent had been told to
  leave the desktop assertions intact and correctly flagged the vacuous one instead of silently
  changing it; the parent then repaired it, and re-ran the focused spec (23/23).

## Scope

**In:** `receipt-detail.ts`, `detail-table.ts`, `detail-table.spec.ts`, `receipt-detail.spec.ts`.

**Out, deliberately:**
- `src/shared/responsive/mobile-viewport.ts` and its spec — the helper is the thing this slice
  standardises *on*; changing it here would put the abstraction and its adoption in one diff. Verified
  untouched: `git diff main...HEAD -- src/shared/` is empty.
- The three list pages — they already use the helper and needed no edit. Verified untouched.
- `src/core/layout/header/header.ts:155–160` — `BreakpointObserver.observe([Breakpoints.Small,
  Breakpoints.XSmall])` (600px / 959.98px) is a **different tier**, not this boundary, and it is the
  only remaining `BreakpointObserver` consumer. Its spec never asserts `isSmallScreen` (F2).
- Removing `LayoutModule` from `src/app/app.ts:6,18` — an empty NgModule providing nothing (F3).
- Any provisioning change: `BreakpointObserver` is `providedIn: 'root'` in CDK 20.2.10 and
  `provideBreakpointObserver` does not exist in that version, so nothing needs providing even now that
  only one consumer remains.

## Plan

| ID | Task | Surfaces | Risk | Status | Commit |
|---|---|---|---|---|---|
| T1 | Migrate the two CDK consumers onto the helper; delete the injected observer and the imports that became unused; no template or other behaviour change. | `receipt-detail.ts`, `detail-table.ts` | Low | **done** | `dc0152a` |
| T2 | Re-stub `detail-table.spec.ts`'s mobile block onto `window.matchMedia` so both tests genuinely test mobile; add a card-presence assertion to the second test. | `detail-table.spec.ts` | Low | **done** | `91f4d61` |
| T2b | `receipt-detail.spec.ts`: rename the overclaiming desktop test, repair its vacuous assertion, correct the stale comment, and add the real mobile case the name promised. | `receipt-detail.spec.ts` | Medium | **done** | `e0f6c33` |
| T3 | Gates: lint, build, `test:coverage:check`. | — | Low | **done** | evidence below |
| T4 | Independent adversarial verification, then commit per work unit. | — | Low | **done** | this record |

## Gate

Run in `life-control-app-angular/`, installed with `npm ci --legacy-peer-deps`
(`.github/workflows/angular-ci.yml:36`). All measured at the tip `e0f6c33`, and re-measured by the
independent verification round.

1. `npm run lint` → exit 0, *All files pass linting* (13.7s). No unused-import residue.
2. `npm run build` → exit 0, *Application bundle generation complete* (9.5s).
3. `npm run test:coverage:check` → exit 0 on clean runs: **129 files / 2530 tests**. Aggregate
   `statements 94.07% / branches 75.98% / functions 89.32% / lines 94.07%` against the enforced
   80 / 60 / 75 / 80 (`scripts/check-coverage.mjs`).
4. **Count reconciliation.** Baseline at `0725237`: **129 files / 2529 tests** (measured twice). Tip:
   **129 / 2530**. Delta **+1**, and the single added test is the new `receipt-detail` mobile case.
   `detail-table.spec.ts` is 23 → 23; `receipt-detail.spec.ts` is 22 → 23. The only removed `it(` is the
   renamed one and the only removed `expect(` is the vacuous `toBeTruthy`. No
   `it.only`/`describe.only`/`fit(`/`fdescribe(`/`xit(`/`xdescribe(`/`.skip` was added anywhere.
5. **Anti-vacuity probes, reproduced independently in a throwaway copy under `/tmp`:**
   - `detail-table.spec.ts`: flipping the stub to `matches: false` → `2 failed | 21 passed`, both at
     `expected +0 to be 1`; restored → 23 passed. The second test's added card assertion is
     **load-bearing**: it is the first to fire under the mutation, and `expect(text).not.toContain(...)`
     would not have caught the desktop branch on its own.
   - `receipt-detail.spec.ts`: flipping the mobile test's stub to `false` → fails at
     `expect(component.isMobile()).toBe(true)`. Flipping the global stub to `true` → the desktop test
     fails at `expected true to be false`, and a pre-existing row-count test fails because the table is
     gone. Both directions confirmed.
6. **Whole-repo idiom sweep.** `BreakpointObserver` survives only in `header.ts:1,40,155–156` (the
   different tier) and as a doc mention in `breakpoints.ts:9`. `observe(MOBILE_MAX_WIDTH_QUERY)` → **no
   hits**. `observeMobileViewport` is referenced by exactly the helper, its spec, and the **five**
   consumers. No sixth consumer exists.

## Evidence log

| Date | Event | Evidence | Notes |
|---|---|---|---|
| 2026-09-24 | Slice opened, idiom decided | worktree `w13`, branch `refactor/unify-mobile-viewport-consumers` off `main` @ `0725237` | D1 recorded before any code was written |
| 2026-09-24 | Read-only map taken | `gentle-ai-explore` at `0725237`: both CDK consumers use the observer for nothing else; `BreakpointObserver` is `providedIn: 'root'`; `provideBreakpointObserver` absent in CDK 20.2.10; `observe()` sync-emits via `startWith(mql)`; CDK cleans up via `removeListener`, the helper via `removeEventListener`; `receipt-detail.spec.ts:249` never exercised the mobile branch | D1, D2 and D5 come from this map |
| 2026-09-24 | T1–T2b implemented | `dc0152a`, `91f4d61`, `e0f6c33`; 4 files, 84 ins / 33 del; forbidden-path probe clean | DELEGATED to `gentle-ai-worker` with narrow edit surfaces |
| 2026-09-24 | Vacuous assertion repaired by the parent | `receipt-detail.spec.ts` desktop test now asserts non-empty table + zero cards; focused re-run 23/23 | The worker flagged it and left it, per instruction |
| 2026-09-24 | Gates green | lint / build / `test:coverage:check` → 129 files, 2530 tests; both mutation controls red→green | Evidence in `## Gate` |
| 2026-09-24 | Independent verification | DELEGATED to `gentle-ai-verify`, adversarial, read-only: **13 of 13 claims `VERIFIED`**, every mutation control reproduced in a throwaway copy | Corrected one factual error in this parent's launch brief (see Findings) and one overstated wording in a commit message (F7) |

## Findings

From the independent verification round (adversarial, read-only). All 13 claims verified; these are the
things it found that were not claimed:

- **F1 — THE BIG ONE: the suite has a pre-existing, load-dependent timeout flake, and it is now proven
  not to come from this branch.** This closes the `UNKNOWN` left open by `mobile-viewport-observer.md`
  F4 (where PR #167's agent reported 13 failures in unrelated files and a verification round could not
  reproduce them in six runs). The verification round ran the full gate repeatedly under contention and
  **reproduced it on both sides**:

  | Run | Side | Result | Load metrics |
  |---|---|---|---|
  | tip 1–2 | tip | pass, 2530 | setup 318s / 286s |
  | tip 3 | tip | **FAIL** — 2 tests | `setup 503s`, load average 23.18 |
  | tip 4 | tip | killed, exit 137 | resource kill, not a test failure |
  | anchor 1–2 | anchor @ `0725237` | pass, 2529 | setup 302s / 284s |
  | anchor 3 | anchor @ `0725237` | **FAIL** — 1 test | `setup 1063s`, load average 22.49 |

  **Every failure was `Error: Test timed out in 5000ms`; not one was an assertion failure.** The failing
  files were `product-edit.spec.ts`, `product-variant-list.spec.ts` (tip) and `receipt-create.spec.ts`
  (anchor) — **the anchor failed with none of this slice's changes present**, which is the control that
  settles causality. **Conclusion: pre-existing and load-dependent, not caused by this slice.** Running
  two coverage suites concurrently killed one outright, so the contention is real. The exact trigger is
  **not** established, and this record does not claim one: the honest statement is that the 5000ms
  per-test timeout is too tight for this suite under a loaded machine (20 cores, load average ~23, with
  several agents running suites in parallel on the same host). Practically: **do not run this suite
  concurrently with itself**, and expect a first-run failure under load to be a timeout, not a defect.
- **F2 — `header.ts:155–160` is untested.** It injects CDK `BreakpointObserver` on the
  `Breakpoints.Small`/`XSmall` tier and `header.spec.ts` never asserts `isSmallScreen`. A different
  boundary from this one, and a real coverage gap. It is also now the only remaining
  `BreakpointObserver` consumer, so if this repo ever retires that dependency, this is the file.
- **F3 — `LayoutModule` in `src/app/app.ts:6,18` provides nothing.** It is an empty NgModule in
  CDK 20.2.10 (services are `providedIn: 'root'`), so importing it is dead weight unrelated to this
  boundary.
- **F4 — a latent behavioural difference the commit message does not mention.** CDK's
  `BreakpointObserver` wraps its media-query listener in `NgZone.run` (`breakpoints-observer.mjs:149`);
  the helper's `addEventListener` callback is plain. Under zone.js `addEventListener` is patched so the
  callback still runs in the registering zone, and with `OnPush` + signals no functional consequence
  was found — but it was **not** disproven either. Recorded so the next person weighing a migration to
  CDK knows the difference exists.
- **F5 — the "mobile path works" wording overstates what a jsdom test can prove.** The new mobile test
  proves branch selection and DOM shape (`isMobile() === true`, exactly 2 `.line-card` for the 2 mock
  lines, 0 `<table>`, against the template's single `@if (isMobile()) ... @else` chain). It cannot prove
  visual layout, because jsdom computes no CSS. The commit message says "the mobile path works"; the
  accurate statement is "the mobile branch renders". Left uncorrected in the commit deliberately —
  rewording it would rewrite the SHA that the verification was performed against (the same call PR #165
  made and recorded).
- **F6 — a factual error in the parent's own launch brief.** It described the ODD record as "tracked by
  the fourth, later commit"; at the time of the verification there were 3 commits and the record was
  untracked. The exclusion of the record from the surface list was still correct, but the reason given
  was wrong. (The record, with this commit, becomes the fourth.)
- **F7 — `setupMatchMedia` is now copy-pasted into six specs.** Identical boilerplate, maintenance risk
  only. A shared test helper would remove it; not done here because it would touch specs outside this
  slice's surface.
- **F8 — a silent-default hazard in `DetailTable (mobile)`.** Each test installs its own stub, so a
  future test added to that describe block that forgets `setupMatchMedia` would silently run with the
  global `matches: false` mock and assert the wrong branch — the exact failure mode this slice fixed.
  Not currently a defect.
- **F9 — cleanup is asserted in only one place.** `detail-table.spec.ts` never asserts listener
  cleanup; only `mobile-viewport.spec.ts:80` does. That is the point of D1 — one audited place — but it
  is worth stating plainly that the other five consumers' cleanup is covered by inference, not by their
  own tests.
- **F10 — the SSR branch remains uncovered** (`mobile-viewport.ts:21`, `typeof window === 'undefined'`),
  unchanged from `mobile-viewport-observer.md` F6.
- **F11 — the ODD header drift** (`mobile-breakpoint-guard.md:11` and `mobile-viewport-observer.md:11`
  both asserting "Not pushed; no PR is open" for merged work). This record deliberately makes no
  live-state claim. The correction was applied by `odd/tasks/odd-header-hygiene.md`.

## Follow-ups

- **F1 is the one that matters**: the 5000ms per-test timeout under load. Options worth evaluating in
  their own slice: raise the runner's test timeout, cap `maxWorkers`, or stop running suites
  concurrently in this repo's multi-agent workflow. Each has a cost and none was decided here.
- F2 (`header.ts` untested) · F3 (dead `LayoutModule`) · F3-adjacent: the three off-scale breakpoint
  literals (`header.scss:184` 767, `home.scss:128` 768, `notification-toast.scss:87` 480) still need
  behaviour decisions · F4 (`NgZone` difference, unproven) · F7 (six copies of `setupMatchMedia`) ·
  F8 (silent-default hazard) · F10 (SSR branch) · F11 (header drift).
- Unchanged from the previous inventories: the dead `zones-list`/`stores-list` components,
  `var(--bp-*)` having no consumer, and `docker/scripts/_common.sh::verify_artifact_freshness` comparing
  mtimes.
