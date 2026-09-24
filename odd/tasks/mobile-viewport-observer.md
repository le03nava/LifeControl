# ODD feature: mobile-viewport-observer

**Repository**: LifeControl — Angular frontend (`life-control-app-angular/**`). One new shared module
plus its spec, three list-page components, one existing spec. No template, no route, no contract, no
build config, no CI change.
**Status**: **implemented — 3 code commits plus this record on `refactor/mobile-viewport-observer` off
`main` @ `89b4d9b`**, worktree `~/workspace/LifeControl-worktrees/refactor-mobile-viewport-observer`
(herdr workspace `w12`): `eca52b3` (the helper + its spec), `8b8ede8` (the three consumers),
`de5fd62` (the supplier-list coverage). 6 files, +~250 lines. Lint, build and the CI coverage gate pass
on the committed tree, five times in a row; the leak regression was mutation-controlled twice,
independently. **Not pushed; no PR is open.**
**Created**: 2026-09-24
**Risk**: **low, but not a pure refactor.** The extracted block is byte-identical in all three
consumers, but two things change behaviour on purpose: (a) the listener is now removed on destroy —
today it leaks in all three; and (b) the `isMobile` signal stops being a component-owned `signal(false)`
and becomes the helper's return value, which narrows its type from `WritableSignal<boolean>` to
`Signal<boolean>` (see F5). The observable contract must be preserved exactly: same initial value, same
response to the `change` event, same `pageSizeOptions()` output. Every claim about today's behaviour
below is anchored to a read-only map taken at `89b4d9b`.

## Why this exists

Three list pages duplicate a mobile-detection block verbatim, and the duplicate is wrong in the same
way three times.

Measured on `main` @ `89b4d9b` (read-only map, `gentle-ai-explore`):

| File | import | comment | signal | computed | matchMedia block |
|---|---|---|---|---|---|
| `src/features/companies/companies/pages/company-list/company-list.ts` | 14 | 51 | 52 | 53 | 99–104 |
| `src/features/products/pages/product-list/product-list.ts` | 14 | 51 | 52 | 53 | 99–104 |
| `src/features/products/suppliers/pages/supplier-list/supplier-list.ts` | 14 | 51 | 52 | 53 | 99–104 |

The triplicated region — the `@shared/constants/breakpoints` import, lines 51–53 and lines 99–104 — is
**byte-identical across the three**. The block is:

```ts
    // MatchMedia for mobile paginator adaptation
    if (typeof window !== 'undefined') {
      const mql = window.matchMedia(MOBILE_MAX_WIDTH_QUERY);
      this.isMobile.set(mql.matches);
      mql.addEventListener('change', (e) => this.isMobile.set(e.matches));
    }
```

**The defect this slice exists for.** `addEventListener` is called and `removeEventListener` is never
called, in any of the three. `DestroyRef` is already imported (line 4) and injected (line 45,
`private readonly destroyRef = inject(DestroyRef)`) but is used only inside `confirmDelete(...)`. So
each destroyed list page leaves a live listener on a `MediaQueryList` that outlives it, holding a
closure over a destroyed component. `life-control-app-angular/AGENTS.md` lists "Subscription manual sin
DestroyRef" as an anti-pattern; this is that anti-pattern, three times, and `PR #165`–`#166` were only
about the *value* these queries compare, not about the subscription. Independently re-verified against
`git show 89b4d9b:<file>`: `grep -c addEventListener` → `1` and `grep -c removeEventListener` → `0` in
each of the three.

**The second gap.** `supplier-list.spec.ts` has **no mobile test at all** — `grep -c isMobile` returns
`0`, while `company-list.spec.ts:205–269` and `product-list.spec.ts:172–227` both carry a
`describe('responsive paginator (isMobile signal)')` block. It is the only one of the three whose
`isMobile` path is covered solely by compilation.

## Decisions

- **D1 — The helper wraps raw `window.matchMedia` + `inject(DestroyRef)`, not CDK `BreakpointObserver`.**
  The repository has *two* idioms for this same boundary: raw `matchMedia` in these three files, and CDK
  `BreakpointObserver` + `toSignal` in `receipt-detail.ts:105–109` and `detail-table.ts:118–122`. Five
  consumers in total, not three. Extracting the raw idiom is the minimum-risk consolidation of the
  pattern the three files under edit already use, and it does not rewrite the test stub of two *other*
  specs. Unifying all five on the CDK idiom is the architecturally better end state and is deliberately
  **not** done here (F1): it would drag `purchases` (receipt-detail, detail-table) and two specs whose
  mobile tests stub `BreakpointObserver` instead of `matchMedia` into a "small" slice. Recorded, not
  dropped.
- **D2 — API: `observeMobileViewport(): Signal<boolean>`, no arguments.**
  It calls `inject(DestroyRef)` internally, so it must be invoked in an injection context — a component
  field initializer or constructor. Consumers read `readonly isMobile = observeMobileViewport();`. The
  returned signal is the helper's own `signal(false)` narrowed to `asReadonly()`, so the component can no
  longer `set()` it directly, which is intended: the only writer is the media query.
- **D3 — Location: `src/shared/responsive/mobile-viewport.ts` and `.../mobile-viewport.spec.ts`.**
  `src/shared/` today holds `constants/ data/ models/ styles/ ui/` and an `index.ts` barrel that
  re-exports `models`, `ui` and `data` but **not** `constants`. Consumers reach the breakpoint constant
  by deep alias import (`'@shared/constants/breakpoints'`), and `@shared/*` maps to `src/shared/*` in
  `tsconfig.json:22–27`, so the new module is reachable as `@shared/responsive/mobile-viewport` with no
  config change. **`src/shared/index.ts` is not edited** — adding one folder to a barrel that already
  omits `constants` would be a second convention change hidden inside this one. A new `responsive/`
  folder (rather than `utils/`, which does not exist) groups by subject, which matters because the
  remaining breakpoint debt is all in this subject.
- **D4 — The helper imports `MOBILE_MAX_WIDTH_QUERY`; it must never inline the query string.**
  `src/shared/constants/breakpoints.spec.ts` is a *source guard*: `scanSources` (~224–244) walks every
  non-spec, non-`breakpoints.ts` file under `src/**`, and `TS_QUERY_LITERAL` (~98) rejects a quoted
  literal that contains a parenthesised `(max-width…` / `(min-width…` media clause. (Corrected after the
  verification round: the regex keys on the parenthesised clause, not on the bare words; the guard is
  green on this branch — 12 tests — and the helper passes it by importing the constant.) A helper that
  hardcodes `'(max-width: …)'` fails that guard by design. This is the guard working as intended, and it
  is also why the helper cannot be written with the string duplicated.
- **D5 — `receipt-detail.ts` and `detail-table.ts` are out of scope.** They consume the same boundary
  through CDK and already get cleanup from `toSignal`. Untouched, with their stubs untouched, and
  verified untouched.

## Scope

**In:** the new helper and its spec; the three list pages (`company-list.ts`, `product-list.ts`,
`supplier-list.ts`) migrated to it; the missing mobile block in `supplier-list.spec.ts`; the gates below.

**Out:** `receipt-detail.ts`, `detail-table.ts` (F1); `header.ts:156`'s CDK `Breakpoints.Small`/`XSmall`
(600px), a genuinely different tier; the three off-scale breakpoint literals (`header.scss:184` 767,
`home.scss:128` 768, `notification-toast.scss:87` 480) — each needs its own behaviour decision; the dead
`zones-list`/`stores-list` components; `var(--bp-*)`'s missing consumer.

## Plan

| ID | Task | Surfaces | Risk | Status | Commit |
|---|---|---|---|---|---|
| T1 | New `observeMobileViewport()` in `src/shared/responsive/mobile-viewport.ts`: `signal(false)`, `matchMedia(MOBILE_MAX_WIDTH_QUERY)`, initial set, `change` listener, `inject(DestroyRef).onDestroy(() => mql.removeEventListener(...))`, SSR guard, returns `asReadonly()`. Plus `mobile-viewport.spec.ts`: initial desktop, initial mobile, change event, **and that the listener is removed on destroy** (the regression that motivates the slice). | `src/shared/responsive/mobile-viewport.ts`, `.../mobile-viewport.spec.ts` | Low | **done** | `eca52b3` |
| T2 | Migrate the three list pages: delete the `signal(false)` at line 52 and the matchMedia block at 99–104, replace with `readonly isMobile = observeMobileViewport();`, drop the now-unused `MOBILE_MAX_WIDTH_QUERY` import at line 14, keep `pageSizeOptions` untouched. Behaviour identical. | `company-list.ts`, `product-list.ts`, `supplier-list.ts` | Low | **done** | `8b8ede8` |
| T3 | Add `describe('responsive paginator (isMobile signal)')` to `supplier-list.spec.ts`, matching its siblings' `setupMatchMedia` stub and closing the coverage gap: default desktop `[6,12,24,48]`, mobile `[6,12]` + `isMobile() === true`, and the change event. | `supplier-list.spec.ts` | Low | **done** | `de5fd62` |
| T4 | Gates: `npm run lint`, `npm run build`, `npm run test:coverage:check`. | — | Low | **done** | evidence below |
| T5 | Independent adversarial verification of the claims above, then commit per work unit. | — | Low | **done** | this record |

## Gate

Run in `life-control-app-angular/`. Results measured at the tip `de5fd62`, and re-measured by the
independent verification round:

1. `npm run lint` → exit 0, *All files pass linting.* No unused imports left behind in the three
   components.
2. `npm run build` → exit 0, *Application bundle generation complete* (11.847s).
3. `npm run test:coverage:check` → exit 0. **129 test files, 2529 tests, all passing**, reproduced five
   times at the tip. Aggregate `statements 94.07% / branches 75.99% / functions 89.32% / lines 94.07%`
   against the enforced 80 / 60 / 75 / 80. **Reconciled against the baseline**: the anchor at `89b4d9b`
   runs 128 files / 2522 tests, and `2522 + 4` (new helper spec) `+ 3` (new supplier tests) `= 2529`,
   `128 + 1 = 129`. Nothing was lost, skipped or disabled: `company-list.spec.ts` and
   `product-list.spec.ts` are sha256-identical between anchor and branch, `supplier-list.spec.ts` is
   `57 insertions, 0 deletions`, and a sweep for `it.only`/`describe.only`/`fit(`/`fdescribe(`/`xit(`/
   `xdescribe(`/`.skip` across `src/**` finds none on either side.
4. **Behaviour-preservation probe.** Contract read side by side, anchor vs branch: same initial
   `isMobile` for a given `matches`, identical `pageSizeOptions` expression, identical `set(event.matches)`
   on the change event. No writer to `isMobile` survives anywhere in `src/**`, and templates only read it.
   The two sibling spec blocks that already covered this are byte-identical to the anchor and still pass.
5. **Leak probe (mutation control), run twice, independently.** The destruction test asserts
   `removeEventListener` was called exactly once, with `'change'`, and with the *same function reference*
   that `addEventListener` received. Commenting out the `inject(DestroyRef).onDestroy(...)` line turns it
   red — `expected "spy" to be called 1 times, but got 0 times` — and restoring it turns it green. The
   verification round reproduced this in a throwaway copy under `/tmp` with `node_modules` symlinked, and
   got the same failure string, so the assertion is not vacuous: `setupMatchMedia` mints a fresh `vi.fn()`
   per test and nothing else can call it.

## Evidence log

| Date | Event | Evidence | Notes |
|---|---|---|---|
| 2026-09-24 | Slice authorized and opened | worktree `w12`, branch `refactor/mobile-viewport-observer` off `main` @ `89b4d9b` | User selected the `matchMedia` slice over the off-scale analysis |
| 2026-09-24 | Read-only map taken | `gentle-ai-explore` report at `89b4d9b`: the triplicated region is byte-identical; no `removeEventListener` in any of the three; `receipt-detail.ts:105–109` and `detail-table.ts:118–122` use the same boundary via CDK; `breakpoints.spec.ts` source-guards every non-spec `.ts` | D1 and D5 come from this map, not from the task description |
| 2026-09-24 | Environment incident | `npm ci` fails with `ERESOLVE` — `zone.js@0.16.3` is outside `@angular/core@20.3.31`'s `peerOptional ~0.15.0`. `.github/workflows/angular-ci.yml:36` documents the answer: `npm ci --legacy-peer-deps` (comment on lines 34–35) | The worktree cannot be installed any other way; the CI agrees |
| 2026-09-24 | T1–T3 implemented | `eca52b3`, `8b8ede8`, `de5fd62`; 6 files, forbidden-path probe clean | DELEGATED to `gentle-ai-worker` with narrow edit surfaces |
| 2026-09-24 | Gates green | lint / build / `test:coverage:check` → 129 files, 2529 tests; mutation control red→green | Evidence in `## Gate` |
| 2026-09-24 | Independent verification | DELEGATED to `gentle-ai-verify`, adversarial, read-only: 11 of 11 claims `VERIFIED`, one item `UNKNOWN` (F4) | Found nothing to refute in the implementation; corrected this record's D4 wording |

## Findings

From the independent verification round (adversarial, read-only), all claims verified:

- **API narrowing (F5).** `isMobile` went from `WritableSignal<boolean>` to `Signal<boolean>` because the
  helper returns `asReadonly()`. No writer exists in the repo today, so the contract holds, but it *is* a
  public-surface change on these three page classes. Accepted deliberately: the only legitimate writer is
  the media query.
- **Coverage gap, deliberate (F6).** `mobile-viewport.ts` branch coverage is 75%: the
  `typeof window === 'undefined'` path is never taken under jsdom. Only aggregate thresholds are
  enforced, so there is no gate impact; recorded so it is not mistaken for an unmeasured path.
- **Test-diagnostic nit (F7).** `supplier-list.spec.ts` guards the dispatch with
  `if (listeners['change'])`. A missing registration still fails the test — the following
  `toBe(true)` fails against `false` — but with a less precise message than asserting registration
  directly. Kept as is because the two sibling blocks use the same shape; changing it here would make
  this file diverge from the pattern it was written to match.
- **The dual idiom is confirmed, not fixed (F1).** `receipt-detail.ts:106` and `detail-table.ts:119` still
  use CDK `BreakpointObserver` + `toSignal`. Truthful follow-up, not a defect introduced here.
- **Field-initializer ordering was checked, not assumed.** The move from the constructor body to a field
  initializer shifts *when* `isMobile` is first set relative to the two constructor `effect`s, but neither
  effect reads it and `pageSizeOptions` is a lazy `computed`, so the shift is inert.
- **`UNKNOWN` — one flaky-run report (F4).** The implementing agent reported that ONE intermediate
  full-suite run failed 13 tests across unrelated files (`stores-form`, `product-edit`, `detail-table`, …)
  and that the runs immediately before and after both passed 2529/2529. The verification round ran the
  full gate **five times at the tip plus once in the anchor: zero failures, no failure reproduced**. It
  therefore remains **unreproduced, and explicitly not "proven environmental"**. No evidence ties it to
  the changed code; it is recorded because it is a real observation about this suite's stability, and the
  next person to see it should know it has happened at least once.

## Follow-ups

- **F1 — Unify all five consumers on the CDK `BreakpointObserver` + `toSignal` idiom.** Today
  `receipt-detail.ts:105–109` and `detail-table.ts:118–122` take the same `MOBILE_MAX_WIDTH_QUERY`
  boundary through CDK and get cleanup free, while these three take it through `window.matchMedia`. Two
  idioms for one boundary is the actual end-state defect; this slice only makes the raw one correct and
  shared. Doing it means touching `purchases` and two specs that stub `BreakpointObserver`
  (`detail-table.spec.ts:4,11,430,432`), which is why it is its own slice.
- **F2 — The three off-scale breakpoint literals** (`header.scss:184` `min-width: 767px`, `home.scss:128`
  `max-width: 768px`, `notification-toast.scss:87` `max-width: 480px`). Each needs a behaviour decision
  and none has test coverage. Unchanged by this slice.
- **F3 — `header.ts:156` uses CDK `Breakpoints.Small`/`XSmall` (600px)**, a different tier. Deliberately
  untouched and not covered by the guard.
- **F4 — the suite's one-off 13-test failure** is unreproduced in 6 full runs. If it reappears, run the
  same gate in the anchor at `89b4d9b` and compare file by file before calling it environmental.
- **F5 — `isMobile` is now read-only** on the three page classes. Any future code that wants to write it
  must go through the media query instead; if a legitimate need appears, widen the helper's return type
  deliberately rather than re-adding a local `signal`.
- **F6 — the SSR/no-window branch of the helper is uncovered.** Covering it needs a test that runs with
  `window` undefined, which this jsdom setup does not do.
- Unchanged from the previous inventory: the dead `zones-list`/`stores-list` components,
  `var(--bp-*)` having no consumer, the ODD record header drift, and
  `docker/scripts/_common.sh::verify_artifact_freshness` comparing mtimes.
