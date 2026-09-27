# ODD feature: loading-overlay-flash

**Repository**: LifeControl — Angular frontend (`life-control-app-angular/**`) only. One shared HTTP
interceptor, its spec, and the two records that describe the diagnosis. No backend edit, no template
edit, no route, no contract change, no build config, no CI change.
**Status**: implemented and committed as two work units on `fix/loading-overlay-flash` —
`d445425` (the fix: 2 files, +124 −28) and `ebc0f79` (the spec tightening that makes the threshold
load-bearing, +4) — with the gates measured on those exact committed bytes: `test:coverage:check`
130 files / 2575 tests / 0 failures, coverage 94.11 / 75.98 / 89.28 / 94.11 against floors
80/60/75/80, and `lint` clean. `npm run test:e2e` was **not** run (see `## Gaps`). This line makes no
claim about push or PR state. The `row-arrival` initial-render defect found while diagnosing this is
**deferred**, not fixed here; see `## Follow-ups`.
**Created**: 2026-09-27
**Risk**: **medium.** The blast radius is app-wide: `loadingInterceptor` is registered globally in
`app.config.ts` and runs on **every** HTTP request of every feature, so the changed behaviour is not
scoped to sales. The failure mode is bounded and non-destructive — no auth, no role, no schema, no
contract, no data path, no persisted state. The two ways this can go wrong are (a) the overlay stops
appearing for genuinely slow requests, and (b) a leaked timer paints the overlay **after** a request
already finished, which would be a new, worse flash than the one being fixed. (b) is the reason the
spec asserts cancellation, not just the happy path.
**Branch**: `fix/loading-overlay-flash` · **Base**: `main` @ `879ae2a` · **Worktree**: none (anchor)
**Requested by**: the user, reporting "sigo viendo el loading en el sales el flashaso" after the
`web-app` image was rebuilt with the #189–#192 code, then choosing "retrasar el overlay global" from
three offered fix shapes.

## Why this exists

`#189` (`3f2b96f`) fixed a **real** defect: the add-item path called `loadOrder()`, which set the
page-level gate, so every scan tore down and rebuilt the form subtree — blanking the screen and
dropping the scan input's focus. That fix is correct and is running.

It did not remove the symptom, because there are **two independent mechanisms** painting over the
screen on a scan, and #189 only addressed one of them:

| Mechanism | Scope | Fixed by #189? |
| --- | --- | --- |
| Page-level `loading` gate inside `sales-order-edit` | one page, one gate | yes |
| `LoadingIndicator` overlay driven by `loadingInterceptor` | **every request, every feature** | **no** |

The second one is why the symptom survived a rebuild, a hard refresh, and a container recreate.

## Problem (verified in the code at `879ae2a`, not inferred)

| # | Fact | Evidence |
| --- | --- | --- |
| P1 | The interceptor starts a loading state for **every** request, with no URL filter, no exclusion list and no delay. | `life-control-app-angular/src/shared/data/loading-interceptor.ts:14` — `loadingService.startLoading(loadingKey)` on the request path; `:16` — `finalize(() => loadingService.stopLoading(loadingKey))` |
| P2 | It is registered globally, first in the chain. | `life-control-app-angular/src/app/app.config.ts:42` — `withInterceptors([loadingInterceptor, bearerTokenInterceptor, errorInterceptor])` |
| P3 | The indicator is mounted at the app root, so it is present on every route. | `life-control-app-angular/src/app/app.html:8` — `<app-loading-indicator />` |
| P4 | It renders a **full-screen** overlay, not a subtle bar: fixed, viewport-filling, white at 80%, blurred, above everything. | `life-control-app-angular/src/shared/ui/loading-indicator.ts` — the `.loading-overlay` style block: `position: fixed`, `inset: 0`, `background-color: rgba(255,255,255,0.8)`, `backdrop-filter: blur(2px)`, `z-index: 9999` |
| P5 | It shows a spinner and the literal text "Loading...". | same file `:14-19` — the `@if` template block |
| P6 | Any single active key turns it on. | `life-control-app-angular/src/shared/data/loading.ts:16` — `isLoading = computed(() => this.loadingStates().size > 0)` |
| P7 | One scan fires several requests, so it repaints the overlay several times. | `.../pages/sales-order-edit/sales-order-edit.ts:481` — `onVariantSelected` issues `addItem` (POST) then `syncOrder` (GET); initialisation adds profile, payment-methods and store-name requests |
| P8 | `startLoading` has exactly one production caller: the interceptor. | grep across `src/` — the only non-spec call site is `loading-interceptor.ts:14`; every other hit is a spec |
| P9 | The pages already own their loading UI, so the global overlay is redundant as well as harmful. | `.../pages/sales-order-list/sales-order-list.html:36` (`loading()`) and `.../sales-order-edit/sales-order-edit.html:48` (`initialLoading()`) |

**Why this reads as a "flash" and not as "loading".** The overlay's own styling (P4) is a full-screen
white wash, and on localhost the request it waits for resolves in tens of milliseconds. So the overlay
is painted and unpainted within a frame or two: an instantaneous white blank, repeated once per
request — which is exactly the "flashaso" the user described, while the component it covers had
already been fixed by #189.

**Hypothesis checked and rejected (recorded because it was the first one).** The `row-arrival`
animation added in `273b97d` was a plausible suspect, and a second one — that a new row's `trackBy`
key changes from its index to its server id on reconciliation, replaying the highlight — was tested
and **is false in the real path**: `addItem` returns the created item and the row is appended already
carrying its id (`toItemTableRow(created)`), so the key is stable. The `row-arrival` finding that did
survive verification is recorded under `## Follow-ups` as a separate defect.

## Scope

**In**: the delay/cancellation behaviour of `loadingInterceptor`, its spec, and this record plus the
diagnosis update in `odd/tasks/sales-scan-ux.md`.

**Out**: removing the global overlay, changing `LoadingService` semantics, fixing the key-collision
defect (see `## Follow-ups`), the `row-arrival` initial-render defect, and any sales-page edit. The
sales page is the **reporter** here, not the subject.

## Decisions

- **D1 — the delay lives in the interceptor, not in `LoadingService`.** P8 shows the interceptor is
  the service's only production caller, so a service-level timer would change the meaning of
  `startLoading` for every caller and every existing `loading.spec.ts` assertion, to serve one
  consumer. The interceptor is also where the request lifetime is known, which is what the decision
  actually depends on.
- **D2 — the threshold is 200 ms**, exported as `LOADING_INDICATOR_DELAY_MS`. Below it, no indicator
  at all; above it, today's unchanged behaviour. A single request-local `timer` per request keeps the
  keys untouched, so `isLoadingKey` and the existing tests keep their meaning.
- **D3 — the timer is cancelled in `finalize`.** Without this, a request that completes under the
  threshold would still fire its timer afterwards and paint the overlay on an idle screen — a new bug
  strictly worse than the flash. This is the risk named in the header, and it is the assertion the
  spec exists to protect.
- **D4 — `stopLoading` on a key that was never started stays a deliberate, documented no-op.** The
  delayed path relies on it: the common case is a fast request that never called `startLoading`.
- **D5 — the key-collision defect is deferred, not fixed here.** Two concurrent requests with the
  same `method:url` share a key, so the first `finalize` clears it while the other is still open. It
  is pre-existing, it needs a reference count or unique keys, and folding it in would widen an
  app-wide change past what was asked for.

## Tasks

| # | Task | Status |
| --- | --- | --- |
| T1 | Write the spec: no overlay under the threshold, overlay above it, cancelled on completion, no late paint | done — 7 tests in `loading-interceptor.spec.ts`, on fake timers. Written in one pass together with T2, so **no red phase was observed**; falsifiability is established by the mutation experiment below instead. |
| T2 | Implement the delayed start with cancellation in `finalize` | done — `d445425` |
| T3 | Run the focused spec | done — 7/7, exit 0 |
| T4 | Run the repo coverage gate (`npm run test:coverage:check`) | done — 130 files / 2575 tests / 0 failures, exit 0 |
| T5 | Run `npm run lint` | done — clean, exit 0 |
| T6 | Commit the work units and record their identity | done — `d445425` (fix) and `ebc0f79` (spec) |

## Verification

All three gates were run twice by an independent read-only verifier: once on the working-tree content
that became `d445425`, and once after `ebc0f79`, on the committed bytes. Both runs: focused spec 7/7;
`test:coverage:check` **130 test files / 2575 tests / 0 failures / 0 errors / 0 skipped**, coverage
**94.11 statements / 75.98 branches / 89.28 functions / 94.11 lines** against floors 80/60/75/80, exit
0; `npm run lint` clean, exit 0. `loading-interceptor.ts` itself measures 100/100/100/100.

**The hook did not invalidate the measurement.** `eslint --fix` and `prettier --write` run on
`src/**/*.ts` at `pre-commit` and re-stage their own output, so a gate measured before a commit can be
falsified by that commit. Both commits were diffed against the backup `lint-staged` takes before it
runs, and both diffs are **empty**: the gates were measured on the bytes that got committed.

**Mutation evidence — the spec's falsifiability, one mutation at a time, restored after each.**
Because T1 and T2 were written in one pass, the spec was never observed red, so its hold on the two
decisions it documents was proven by breaking those decisions on purpose:

| Mutation | Expected | Measured |
| --- | --- | --- |
| `LOADING_INDICATOR_DELAY_MS` set to `0` — the threshold collapsed, i.e. D2 regressed | the fast-path tests fail | **before** `ebc0f79`: 1 failed / 6 passed, only `…completes before the delay`. **After**: 2 failed / 5 passed, now including `…still under the delay` |
| `delayedStart.unsubscribe()` deleted from `finalize` — D3 regressed | the no-late-paint tests fail | 3 failed / 4 passed: exactly the three late-paint tests |

Both mutations were reverted; the interceptor is byte-identical to `d445425` (`git diff` empty).

The first mutation is why this record has a second commit at all. The spec documented the threshold
but did not pin it: three of the four fast-path tests stayed green with the delay gone, because they
never advanced the clock, so the `timer(0)` never flushed. `ebc0f79` adds the one millisecond advance
that makes the constant load-bearing — far short of the threshold, so a correct delay is still
pending, while a threshold collapsed to zero fires inside it. Three further findings came out of this
verification and were acted on instead of being declared as gaps: the fast-cancel test asserted only
`isLoading()` and not its key (closed; that is the path where a leaked timer would surface), the delay
constant was not pinned (closed, above), and the range `main...HEAD` is **2 files, +128 −28** — the
`+124 −28` in the header is `d445425` alone.

## Evidence log

| Date | Evidence |
| --- | --- |
| 2026-09-27 | Root cause established by reading, not inferred: P1–P9 above, each cited. Confirmed the running container serves the committed code by grepping five commit markers (`row-arrival`, `isRowSaving`, `savingRowIds`, `trackRow`, `scanInput`) inside `/usr/share/nginx/html` of `lifecontrol-dev-life-control-app-angular`, and that the served `index.html` matches the image's — so the surviving symptom is not a stale bundle. |
| 2026-09-27 | Branch `fix/loading-overlay-flash` created from `main` @ `879ae2a`. |
| 2026-09-27 | Fix committed as `d445425` (2 files, +124 −28) and the spec tightening as `ebc0f79` (+4). The `pre-commit` hook re-staged nothing in either commit: `git diff <lint-staged backup> <commit> -- <the two files>` is empty for both. |
| 2026-09-27 | Gates measured on the committed bytes by an independent read-only verifier: focused spec 7/7; coverage gate 130 files / 2575 tests / 0 failures; coverage 94.11/75.98/89.28/94.11; lint clean. `git status` at gate time showed only the untracked record, so the measured bytes were the committed ones. |
| 2026-09-27 | Mutation experiment, each mutation reverted and verified by an empty `git diff` against `d445425`: the delay constant at `0` fails the fast-path tests (1 before `ebc0f79`, 2 after), and removing `delayedStart.unsubscribe()` fails the three late-paint tests. |

## Follow-ups

- **`row-arrival` fires on every row at first paint** (found while diagnosing this; **not** fixed
  here). `sales-order-item-table.scss:29-31` applies `animation: row-arrival 500ms ease-out` to
  `tr.mat-mdc-row` unconditionally, so when the table is first created — which is what happens when
  `initialLoading` flips and the content replaces the skeleton — every existing row is new DOM and
  every row plays the highlight at once. The file's own comment claims `[trackBy]` makes it "play for
  the new row only"; that is true for a row added to a live table and false for the table's first
  render. Needs a marker that distinguishes an arrival from a first paint.
- **Key collision in `LoadingService`**: `method:url` is not unique under concurrency, so a second
  in-flight request with the same key is cancelled visually by the first one's `finalize` (P1, D5).
- **`prefers-reduced-motion` relies on a global rule**, not on this component: `src/styles.scss:272`
  neutralises animation durations app-wide, so the `row-arrival` follow-up is invisible to users with
  reduced motion and cannot be reproduced through that setting.

## Gaps

- The required evidence for a **medium** change per
  `.agents/skills/project-conventions/assets/risk-classification-matrix.md` includes
  `npm run test:e2e`. It was **not** run for this slice; the matrix's own escalation rule makes an
  unavailable control a `GAP` rather than a `PASS`, so it is recorded here as unresolved rather than
  claimed.
- The delay is **app-wide, not per-feature**: a request that genuinely takes longer than 200 ms still
  paints the full-screen overlay, including in a feature whose page already owns a skeleton (P9). That
  is the unchanged behaviour above the threshold, per D2, not an oversight.
- Repo convention deviation, unresolved and deliberate: the work was done **in the anchor**
  (`~/workspace/LifeControl`), which is now on `fix/loading-overlay-flash` rather than sitting clean on
  `main`. Nothing is lost — the commits are on the branch and the untracked record survives
  `git checkout main` — but the anchor returns to the documented state only when that checkout happens.
- No automated check inspects `odd/**` (see `references/feature-records.md`), so nothing in CI
  validates this record.
