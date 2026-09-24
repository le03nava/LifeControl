# ODD feature: breakpoint-vars

**Repository**: LifeControl — Angular frontend (`life-control-app-angular/**`). SCSS only: no TypeScript,
no template, no contract, no build config.
**Status**: implemented — W1 (`36ac3d3`) on `refactor/breakpoint-vars` off `main` @ `218f4b0`, in the
worktree `~/workspace/LifeControl-worktrees/refactor-breakpoint-vars`. Build, lint and the compiled-CSS
gate pass. Not pushed; no PR is open yet.
**Created**: 2026-09-24
**Risk**: **low** — every replacement is number-for-number identical, so the compiled CSS must not change
by a single byte. That is also the gate: the build either reproduces the same media-query numbers or the
change is wrong.

## Why this exists

This started as "implement the pending OpenSpec change `modify-form-layout-breakpoint`" (the last
remaining item of the pending-work inventory). Reading the code first killed that plan:

- **Its Phase 1 is already done.** `styles.scss:123-127` already exposes `--bp-tablet: 767.98px`,
  `--bp-desktop: 1023.98px`, `--bp-sm: #{$bp-sm}`, `--bp-md: #{$bp-md}`, `--bp-lg: #{$bp-lg}`.
- **Its Phase 2 is technically invalid.** It prescribes `@media (min-width: var(--bp-sm))`. A custom
  property cannot be used in a media-query *condition*: custom properties are ordinary properties
  resolved by the cascade, so they need an element (`css-variables-1`), while a media query "is
  independent of the contents of the document, its styling, or any other internal aspect"
  (`mediaqueries-4` §2). "Media features only accept single values: one keyword, one number, etc."
  (`mediaqueries-4` §2.4) — `var()` is neither. Per §3.2 error handling, a non-conforming media query
  does not match: the style silently never applies. Implementing that phase would have broken the
  responsive layout of every form page with no build error and no lint error.
- **Its Phase 3 names files that are not there.** `company-list.scss` does not exist, and
  `product-list.scss` is a one-line `@use` of a shared partial that already uses the variables.
- **The repository already made the right call, in the file the change wanted to alter.**
  `src/shared/styles/_variables.scss` says so in its own header: *"Estas variables son la fuente de
  verdad para @media queries. Las CSS custom properties en styles.scss se mantienen para uso en runtime
  (var() en propiedades CSS que no son media)."* The pattern is already implemented and 23 files use it.

So the OpenSpec change gets **archived as superseded, not implemented**. What is left is the real,
smaller debt it was pointing at: `@media` queries that hardcode a number the scale already defines.

**Measured**: 70 `@media` rules in `src/**`; 52 already use a variable; **18 hardcode a `px` number**.
Of those 18, twelve map to the scale exactly, two are already written against the variable
(`detail-table.scss:206`, `purchase-order-edit.scss:68`), and **four do not map** (see D3).

## Decisions

- **D1 — no `var()` in any media query, ever, in this repository.** The Sass variables in
  `_variables.scss` are the source of truth for `@media`; the CSS custom properties in `styles.scss`
  stay for runtime use (reading them from JS, or using them in non-media property values). This is the
  invariant `_variables.scss` already documents; this slice only obeys it.
- **D2 — the mobile maximum gets its own exact variable instead of being derived.** The repo has two
  competing ways to express "below the small breakpoint": `(max-width: 575.98px)` (the literal, used by
  ten files) and `(max-width: ($bp-sm - 1px))` (used by two). They are **not equivalent**: the second
  is 575px, which leaves the 575.01–575.99px range matching nothing, since the other side of the
  breakpoint is `min-width: 576px`. Adopting `($bp-sm - 1px)` would therefore have introduced a real
  one-pixel hole while pretending to be a pure refactor. Instead, `$bp-mobile-max: 575.98px` is added to
  `_variables.scss` and the ten literals become that variable: the number stays exactly 575.98, which is
  what makes the compiled-CSS gate meaningful. `($bp-sm - 1px)` in the other two files is left alone —
  changing *it* to `$bp-mobile-max` would move that threshold, and that is a behavior decision, not a
  refactor.
- **D3 — the four non-mapping breakpoints are out of scope and stay hardcoded.** `header.scss:184`
  (`min-width: 767px`), `home.scss:128` (`max-width: 768px`), `user-profile.component.scss:110`
  (`max-width: 576px`) and `notification-toast.scss:87` (`max-width: 480px`). None equals a scale value:
  `--bp-tablet` is 767.98 and `$bp-sm` is 576, so mapping them would shift real behavior by fractions of
  a pixel or change which side of a breakpoint applies. They are recorded here as follow-ups rather than
  folded into a "pure refactor" that would then not be pure.
- **D4 — the ten files that own a literal now import the variables partial explicitly.** `@use` does not
  re-export: `zones-list.scss` already does `@use '.../form-layout' as *`, and although `form-layout.scss`
  itself does `@use 'variables' as *`, that does **not** make `$bp-mobile-max` visible to the consumer.
  Every touched file gets its own `@use '<relative>/shared/styles/variables' as *;` as the first line,
  which is exactly the pattern `detail-table.scss:1` already uses. Relative depth differs per file
  (`../../../../` under `features/products/components/`, `../../../../../` deeper), which is the one place
  this slice can realistically break — and the build fails loudly with "no module named variables" if it
  does, so the error is cheap.

## Scope

**In**: one new variable in `src/shared/styles/_variables.scss`, and 12 number-for-number replacements
across the 11 component stylesheets listed below.

| # | File | Line | From | To |
|---|------|------|------|-----|
| 1 | `src/shared/ui/page-header/page-header.scss` | 58 | `(max-width: 575.98px)` | `(max-width: $bp-mobile-max)` |
| 2 | `src/features/companies/countries/components/countries-card/countries-card.scss` | 79 | `(max-width: 575.98px)` | `(max-width: $bp-mobile-max)` |
| 3 | `src/features/companies/companies/components/companies-card/companies-card.scss` | 101 | `(max-width: 575.98px)` | `(max-width: $bp-mobile-max)` |
| 4 | `src/features/companies/zones/components/zones-card/zones-card.scss` | 95 | `(max-width: 575.98px)` | `(max-width: $bp-mobile-max)` |
| 5 | `src/features/companies/zones/components/zones-list/zones-list.scss` | 51 | `(max-width: 575.98px)` | `(max-width: $bp-mobile-max)` |
| 6 | `src/features/companies/regions/components/regions-card/regions-card.scss` | 81 | `(max-width: 575.98px)` | `(max-width: $bp-mobile-max)` |
| 7 | `src/features/companies/stores/components/stores-card/stores-card.scss` | 103 | `(max-width: 575.98px)` | `(max-width: $bp-mobile-max)` |
| 8 | `src/features/companies/stores/components/stores-list/stores-list.scss` | 51 | `(max-width: 575.98px)` | `(max-width: $bp-mobile-max)` |
| 9 | `src/features/products/components/products-card/products-card.scss` | 87 | `(max-width: 575.98px)` | `(max-width: $bp-mobile-max)` |
| 10 | `src/features/products/suppliers/components/suppliers-card/suppliers-card.scss` | 91 | `(max-width: 575.98px)` | `(max-width: $bp-mobile-max)` |
| 11 | `src/features/sales/sales-orders/components/order-header-form/order-header-form.scss` | 23 | `(min-width: 576px)` | `(min-width: $bp-sm)` |
| 12 | `src/features/sales/sales-orders/components/order-header-form/order-header-form.scss` | 27 | `(min-width: 1024px)` | `(min-width: $bp-md)` |

Plus the `@use` line in each of the 11 files, and:

```scss
// El máximo de la franja "mobile": 575.98px y no 575px, para no dejar
// el hueco 575.01–575.99 sin cubrir contra min-width: $bp-sm.
$bp-mobile-max: 575.98px;
```

**Out, explicitly**: the 4 breakpoints in D3; the 2 files already using `($bp-sm - 1px)`; the other 52
`@media` rules that already use a variable; every `max-width` on a container (a layout width, not a
breakpoint); anything in TypeScript, templates, the OpenSpec archive, or the build config.

## Review workload

**Forecast**: 12 files, ~25 changed lines (1 variable + 11 `@use` lines + 12 substitutions). All
mechanical, all at a uniform shape, and every one of them is *verifiable by diffing the compiled CSS* —
which is what makes this reviewable in minutes rather than by reading 12 stylesheets. **Far below the
~400-line guardrail; single PR, no chain.**

**Measured**: `git diff --numstat 218f4b0 36ac3d3`.

| Bucket | Files | Changed lines |
|---|---|---|
| The 10 card/page stylesheets (`575.98px` → `$bp-mobile-max`) | 10 | **40** (30 insertions / 10 deletions) |
| `order-header-form.scss` (two substitutions) | 1 | **6** (4 / 2) |
| `_variables.scss` (the new variable) | 1 | **4** (4 / 0) |
| **W1 total** (`36ac3d3`) | **12** | **50** (38 / 12) |

The forecast said ~25 changed lines across 12 files. The file count was exact; the line count came in at
50, because the forecast counted one `@use` line per file and every insertion carries the blank line
after it, and `order-header-form.scss` takes two substitutions. The reviewer's real load is unchanged:
12 one-line substitutions plus 11 identical imports.

## Task list

| Id | Task | Evidence |
|----|------|----------|
| **T1** | Add `$bp-mobile-max: 575.98px` to `_variables.scss` | `36ac3d3` — 4 insertions |
| **T2** | Add the `@use` line and apply the 12 substitutions in the 11 files | `36ac3d3` + `npm run build` (6.8s) and `npm run lint` green |
| **T3** | Prove the compiled CSS did not change: same media-query numbers before and after | the compiled-CSS diff below — **identical** |
| **T4** | Close: measured workload, evidence log | this record + the commits + gate output |

**Work units**: **W1** = T1+T2 (one commit: the variable and its consumers are one atomic change; a commit
with only the variable would be dead code, and a commit with only the substitutions would not compile).
**W2** = T3+T4 (verification and closure).

## Verification plan

1. `npm run build` in `life-control-app-angular/` — hard gate, and the only one that can catch a wrong
   `@use` depth.
2. **Compiled-CSS equality**: extract every `@media` line from the previous and the new build output and
   compare. The set of numbers must be identical, with the substitutions having resolved to the same
   literals (`575.98px`, `576px`, `1024px`). This is the gate that makes "pure refactor" a measurable
   claim instead of an assertion.
3. `npm run lint` — ESLint + Prettier, which also covers the SCSS formatting.
4. Sanity check that no `@media` in `src/**` uses `var(--bp-*)` (D1's invariant holds after the change).

## Evidence log

| Date | Slice | Commit | Evidence |
|------|-------|--------|----------|
| 2026-09-24 | pre-work (read-only) | — | The planned work was the OpenSpec change `modify-form-layout-breakpoint`. Reading the code first invalidated it: `styles.scss:123-127` already exposes the custom properties (Phase 1 done), `_form-layout.scss` already uses `$bp-sm/$bp-md/$bp-lg` so Phase 2 has nothing left to replace, and `company-list.scss` does not exist while `product-list.scss` is a one-line `@use` of a shared partial — Phase 3 names files that are not there. The prescription `@media (min-width: var(--bp-sm))` was rejected from the spec's own grammar (`mediaqueries-4` §2.4: "Media features only accept single values"; a custom property is resolved by the cascade and needs an element). Measured the remaining debt: 70 `@media` rules, 52 already variable-driven, **18 hardcoding a number**, of which 12 map to the scale exactly, 2 already use `($bp-sm - 1px)`, and 4 do not map (D3). The relative import depth for the 11 files was computed with `realpath --relative-to`, not counted by hand — `products-card.scss` is the one four-level case. |
| 2026-09-24 | **W1 (T1+T2)** | `36ac3d3` | 12 files, **38 insertions / 12 deletions** (50 changed lines), delegated to one `gentle-ai-worker` with all 12 allowed edit surfaces and the exact `@use` path per file; **nothing was left to the writer's judgement**. The first delegation attempt was **rejected before launching** by the writer contract (the task lacked the canonical `## Allowed edit surfaces` heading) and was relaunched — no work was lost and nothing ran twice. Its report confirms all 12 substitutions matched exactly once, the 11 imports resolved, and it flagged one imprecision in my own instructions (the line numbers shift by two, not one) instead of silently working around it. **Gates**: `npm run build` succeeded (6.8s — a wrong depth fails here with "no module named variables"), `npm run lint` green. |
| 2026-09-24 | **gate (T3)** | `36ac3d3` | **Compiled CSS identical.** Every `@media` rule in `dist/` (extracted from the JS chunks — Angular 20 inlines component styles there, so the three `.css` files alone see nothing) was captured **before** the change and compared **after**: the same 17 distinct rules with the same counts each (`min-width:576px` ×49, `min-width:1024px` ×45, `max-width:576px` ×27, `min-width:1440px` ×13, `max-width:575.98px` ×8, `max-width:575px` ×2, and the single out-of-scope `767px`/`768px`/`480px` rules), and the per-number counts unchanged (575.98px 11→11, 576px 77→77, 1024px 46→46, 575px 4→4, 767px 1→1, 768px 1→1, 480px 5→5). This is what makes "pure refactor" a measurement instead of a claim. Plus: no `@media` in `src/**` uses `var(--bp-*)`. |

## Follow-ups

- The 4 breakpoints in D3, with the decision each one needs (`--bp-tablet` is 767.98, not 767; `480px` is
  off-scale entirely).
- `($bp-sm - 1px)` in `detail-table.scss:206` and `purchase-order-edit.scss:68` expresses the same intent
  as `$bp-mobile-max` but with a different value. Unifying them is a behavior decision (which threshold is
  right), not a refactor — and it is the one that would remove the 575.01–575.99px hole for good.
- `styles.scss` exports `--bp-tablet` and `--bp-desktop` as custom properties but `_variables.scss` has no
  `$bp-tablet`/`$bp-desktop` Sass counterpart; the scales are not quite parallel.
