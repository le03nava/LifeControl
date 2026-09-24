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
- **Its Phase 3 names the wrong paths.** The change points at
  `features/companies/pages/company-list/company-list.scss`; the file that exists is
  `features/companies/companies/pages/company-list/company-list.scss` (3 lines: a `@use` of
  `_entity-list-page.scss` plus one `@include`). `product-list.scss` exists but is 15 lines of `@use`,
  `@include` and rules — not the file the change describes either. The conclusion survives: in both
  cases the breakpoints live in the shared partials, which already use the variables, so there is
  nothing left to replace. *(Corrected in the findings round: the first version of this record reused
  the change's path and then asserted that `company-list.scss` does not exist. It does — at a different
  path. The wrong path was the change's, and repeating it as a fact was this record's error.)*
- **The repository already made the right call, in the file the change wanted to alter.**
  `src/shared/styles/_variables.scss` says so in its own header: *"Estas variables son la fuente de
  verdad para @media queries. Las CSS custom properties en styles.scss se mantienen para uso en runtime
  (var() en propiedades CSS que no son media)."* The pattern is already implemented and 23 files use it.

So the OpenSpec change is **superseded: it must be archived, not implemented**. *(Corrected in the
findings round: the first version said it "gets archived", in the present tense, as though that had
happened. It had not and still has not — the archive step is deliberately deferred until after this
branch merges, because the change lives in `openspec/` in the anchor repo, which is gitignored and does
not travel with this branch.)* What is left is the real, smaller debt the change was pointing at:
`@media` queries that hardcode a number the scale already defines.

**Measured**, each number with the command that reproduces it, taken at `218f4b0`:

- `grep -rn "@media" src --include=*.scss | wc -l` → **70 lines**, one of which is a comment
  (`_variables.scss:4`), so **69 `@media` rules**. *(The first version said "70 rules" and silently
  counted the comment.)*
- `grep -rnE "@media[^{]*(min|max)-width[[:space:]]*:[[:space:]]*[0-9.]+px" src --include=*.scss | wc -l`
  → **16** rules whose width is a literal `px`: **the twelve substituted here** (ten `575.98px`, one
  `576px`, one `1024px`) and **the four left alone** (D3). After this slice the same command returns
  **4**.
- `grep -rnE "@media[^{]*\$bp" src --include=*.scss | wc -l` → **51** rules already written against a
  variable, *including* the two that express the mobile maximum as `($bp-sm - 1px)`. After this slice it
  returns **63**. *(The first version reported "52 already use a variable / 18 hardcode a number"; that
  split double-counted those two files, which are both a variable expression and a `px` literal to a
  naive grep. Replaced with the three commands above, which reproduce.)*

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
- **D3 — the four breakpoints outside the substitutions stay hardcoded.** `header.scss:184`
  (`min-width: 767px`), `home.scss:128` (`max-width: 768px`), `user-profile.component.scss:110`
  (`max-width: 576px`) and `notification-toast.scss:87` (`max-width: 480px`).
  **Corrected in the findings round:** the first version claimed none of the four equals a scale value,
  and that is **false for `user-profile.component.scss:110`** — `max-width: 576px` *is* exactly
  `$bp-sm`, so it could have been substituted number-for-number like the other twelve. It is left alone
  here on purpose rather than out of necessity: `max-width: $bp-sm` reuses the same value the `min-width`
  side uses, so it does not close the mobile range before it starts — the same one-pixel question D2
  answers for the other ten files, and it deserves the same explicit decision instead of a silent
  substitution buried in a refactor. The other three are genuinely off-scale (`--bp-tablet` is 767.98,
  not 767; `480px` is off-scale entirely).
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
| 2026-09-24 | pre-work (read-only) | — | The planned work was the OpenSpec change `modify-form-layout-breakpoint`. Reading the code first invalidated it: `styles.scss:123-127` already exposes the custom properties (Phase 1 done), `_form-layout.scss` already uses `$bp-sm/$bp-md/$bp-lg` so Phase 2 has nothing left to replace, and Phase 3's two paths are wrong (`company-list.scss` exists at `features/companies/**companies**/pages/company-list/`, and `product-list.scss` is 15 lines, not a one-line `@use`). The prescription `@media (min-width: var(--bp-sm))` was rejected from the spec's own grammar (`mediaqueries-4` §2.4: "Media features only accept single values"; a custom property is resolved by the cascade and needs an element). Measured the remaining debt with three commands: 69 `@media` rules (70 lines minus one comment), 16 of them carrying a literal `px` width (12 substituted here, 4 left alone) and 51 already written against a variable. The relative import depth for the 11 files was computed with `realpath --relative-to`, not counted by hand — `products-card.scss` is the one four-level case. *(This cell was rewritten in the findings round; its first version repeated the change's wrong path as a fact and reported a "70 / 52 / 18" split that does not reproduce.)* |
| 2026-09-24 | **W1 (T1+T2)** | `36ac3d3` | 12 files, **38 insertions / 12 deletions** (50 changed lines), delegated to one `gentle-ai-worker` with all 12 allowed edit surfaces and the exact `@use` path per file; **nothing was left to the writer's judgement**. The first delegation attempt was **rejected before launching** by the writer contract (the task lacked the canonical `## Allowed edit surfaces` heading) and was relaunched — no work was lost and nothing ran twice. Its report confirms all 12 substitutions matched exactly once, the 11 imports resolved, and it flagged one imprecision in my own instructions (the line numbers shift by two, not one) instead of silently working around it. **Gates**: `npm run build` succeeded (6.8s — a wrong depth fails here with "no module named variables"), `npm run lint` green. |
| 2026-09-24 | **gate (T3)** | `36ac3d3` | **Compiled CSS identical.** Two labelled methods, over `dist/` (and note that Angular 20 inlines component styles into the JS chunks, so the three `.css` files alone see nothing). **Method A — rule-level counts**, `grep -o` of `@media[^{]*`: **16** distinct rule texts, identical in both trees and with identical counts (`min-width:576px` ×49, `min-width:1024px` ×45, `max-width:576px` ×27, `min-width:1440px` ×13, `max-width:575.98px` ×8, `max-width:575px` ×2, plus the single `767px`, `768px` and `480px` rules). **Method B — raw substring counts** over the whole `dist/`: 575.98px 11→11, 576px 77→77, 1024px 46→46, 575px 4→4, 767px 1→1, 768px 1→1, 480px 5→5. *(Both methods were unlabelled and the distinct-rule count was stated as 17 in the first version. The two methods measure different things: Method B's 575.98px count includes three TypeScript `matchMedia` string literals, so it does not measure the ten SCSS substitutions at all.)* |
| 2026-09-24 | **independent verification (T4)** | `dd90b68` | One `gentle-ai-verify` pass, read-only and adversarial, re-ran the gate at the **whole-dist** level rather than the `@media` level: it built HEAD, copied the tree, checked out `218f4b0 -- life-control-app-angular/src`, rebuilt, restored the tree, and compared — **identical file lists, identical trees, all 117 sha256 hashes equal**. It added two controls of its own: a rebuild from a wiped `dist/` (still byte-identical, so the equality is not a stale-cache artifact) and a falsification attempt that deleted `$bp-mobile-max` while keeping its consumers — the build **failed** with `Undefined variable` at three call sites (`products-card.scss`, `countries-card.scss`, `stores-list.scss`), proving the styles are genuinely recompiled from source. It found **8 issues, 6 of them errors in this record** — see `## Findings`, all corrected here. It correctly declined to call `npm run lint` verified because it was not authorized to run it; the parent ran it afterwards: **green**. |

## Findings

One independent read-only `gentle-ai-verify` round over `218f4b0..dd90b68`, instructed to falsify the
record rather than confirm it. It upheld all eight claims it was given, and found **eight issues, six of
them errors in this record** — which is the point of the round: a record that only says what went well is
the same defect this repository spent a slice repairing in 14 status headers.

| # | Severity | Finding | State |
|---|----------|---------|-------|
| **F1** | MEDIUM | **The whole-dist gate does not reach 2 of the 12 substitutions.** `zones-list.scss` and `stores-list.scss` belong to components that no route or template references, so they are tree-shaken out of the bundle — their styles are **compile-verified but not dist-verified**. The risk is low (the source diff is number-identical and the falsification control errored on `stores-list.scss:53`), but "the compiled CSS is identical" covers 8 of the 10 substituted components, not 10. | **Open** — declared, not fixable inside this slice |
| **F2** | MEDIUM | **A false statement in this record.** "`company-list.scss` does not exist" is wrong: it exists at `features/companies/companies/pages/company-list/`. The wrong path was the *change's*; repeating it as a fact was this record's error. "`product-list.scss` is a one-line `@use`" was also wrong — it is 15 lines. | **Closed** — corrected in `## Why this exists` |
| **F3** | MEDIUM | **An asserted action that never happened.** The record said the OpenSpec change "gets archived as superseded", in the present tense. It had not been, and the archive is deliberately deferred to after this branch merges. | **Closed** — corrected in `## Why this exists` |
| **F4** | LOW | **A wrong justification in D3.** "None equals a scale value" is false for `user-profile.component.scss:110`: `max-width: 576px` *is* exactly `$bp-sm` and could have been substituted number-for-number. | **Closed** — corrected in D3 |
| **F5** | LOW | **Numbers that do not reproduce.** "70 rules / 52 variable / 18 hardcoded" fits no single definition; the split double-counted the two files that are both a variable expression and a `px` literal. "17 distinct rules" was 16. | **Closed** — replaced with three labelled commands |
| **F6** | LOW | **A misleading evidence cell.** The gate row mixed rule-level counts with raw substring counts without labelling them, so "575.98px 11" read as a count of SCSS substitutions when it includes three TS string literals. | **Closed** — both methods now labelled |
| **F7** | LOW | **A missed follow-up: the same breakpoint hardcoded in TypeScript.** Five files carry `matchMedia` strings — `company-list.ts:100`, `product-list.ts:100`, `supplier-list.ts:100` use `'(max-width: 575.98px)'` while `detail-table.ts:29` and `receipt-detail.ts:20` use `'(max-width: 575px)'`, and `detail-table.spec.ts:44` mirrors the latter. So the mobile breakpoint lives in three places and with two different values. | **Open** — recorded in `## Follow-ups` |
| **F8** | INFO | **The custom properties have no reader.** D1 keeps `--bp-*` for "runtime use (reading them from JS)", but nothing reads them: zero occurrences in `src/**` and zero in the compiled output. Pre-existing, not caused by this slice. | **Open** — informational |

## Follow-ups

- The 4 breakpoints in D3, with the decision each one needs (`--bp-tablet` is 767.98, not 767; `480px` is
  off-scale entirely; and `user-profile.component.scss:110` is exactly `$bp-sm`, so it is the one case
  where "leave it" is a choice rather than a necessity — see F4).
- **The TypeScript side of the same breakpoint (F7).** Five `matchMedia` strings hardcode it, with two
  different values: `'(max-width: 575.98px)'` in `company-list.ts:100`, `product-list.ts:100` and
  `supplier-list.ts:100`, and `'(max-width: 575px)'` in `detail-table.ts:29` and `receipt-detail.ts:20`
  (mirrored by `detail-table.spec.ts:44`). A TS string cannot import a Sass variable, so the fix is a
  shared TS constant — and the 575 vs 575.98 divergence is the same threshold question D2 answered for
  the SCSS, now in a second language.
- **`zones-list` and `stores-list` (F1).** The two components this slice edited are not referenced by any
  route or template and are tree-shaken out of the bundle. Either accept that their styles are
  compile-verified only, or delete the dead components — which is a separate decision, not a refactor.
- `($bp-sm - 1px)` in `detail-table.scss:206` and `purchase-order-edit.scss:68` expresses the same intent
  as `$bp-mobile-max` but with a different value. Unifying them is a behavior decision (which threshold is
  right), not a refactor — and it is the one that would remove the 575.01–575.99px hole for good.
- `styles.scss` exports `--bp-tablet` and `--bp-desktop` as custom properties but `_variables.scss` has no
  `$bp-tablet`/`$bp-desktop` Sass counterpart; the scales are not quite parallel.
- **The OpenSpec change `modify-form-layout-breakpoint` still needs archiving** (F3) — as superseded, with
  this record as the reason. It lives in the anchor repo's gitignored `openspec/`, so it cannot travel
  with this branch and the step is deferred until after the merge.
