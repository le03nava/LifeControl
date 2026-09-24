# ODD feature: mobile-breakpoint-single-source

**Repository**: LifeControl — Angular frontend (`life-control-app-angular/**`). 6 TypeScript
consumers, 2 SCSS consumers, 1 new constant module and its spec. No template, no contract, no build
config, no route.
**Status**: implemented — 4 commits on `fix/mobile-breakpoint-single-source` off `main` @ `4469cf7`,
worktree `~/workspace/LifeControl-worktrees/fix-mobile-breakpoint-single-source`: `152db88` (constant
+ guard), `4f82b06` (three list pages), `53a7ac6` (detail-table, receipt-detail, spec), `639d0b4` (two
stylesheets). 10 files, +71 −17. Lint, build and the CI coverage gate pass on the committed tree; the
falsification control was reproduced twice. Independently verified read-only — see `## Findings`.
**Merged as PR #165 (`7f8ba11b9`), 2026-09-24.**
**Created**: 2026-09-24
**Risk**: **low, but not zero — and explicitly not a pure refactor.** Two of the eight call sites
change the value they compare against (575px → 575.98px). Unlike the sibling feature
`breakpoint-vars`, the compiled output **must change**, so a byte-identical-`dist` gate is the wrong
gate here. The right gate is a *divergence* gate: after this slice, the mobile maximum must resolve
to exactly one number everywhere, and that number must be the one `_variables.scss` documents.

## Why this exists

This closes items 1 and 2 of the pending-work inventory (2026-09-24), the last functional residue of
the `breakpoint-vars` feature. That feature fixed the SCSS side by introducing
`$bp-mobile-max: 575.98px` and substituting ten literals — and then deliberately left two things
alone, both recorded as follow-ups (F7 and D2's tail):

1. **The TypeScript side of the same breakpoint hardcodes it, with two different values.**
   `company-list.ts:100`, `product-list.ts:100` and `supplier-list.ts:100` compare against
   `'(max-width: 575.98px)'`; `detail-table.ts:29` and `receipt-detail.ts:20` compare against
   `'(max-width: 575px)'`. A TS string cannot import a Sass variable, so there was no obvious fix at
   the time and the split was left standing.
2. **Two SCSS files express the same mobile maximum a third way.** `detail-table.scss:206` and
   `purchase-order-edit.scss:68` use `(max-width: ($bp-sm - 1px))`, which evaluates to **575px**, not
   575.98px.

So the codebase states one boundary four ways: `575.98px` (3 TS files), `575px` (3 TS files),
`($bp-sm - 1px)` → 575px (2 SCSS files), and `$bp-mobile-max` → 575.98px (10 SCSS files).
**The value question is already answered by the repository itself**, three times, with a rationale:

- `src/shared/styles/_variables.scss:13-14`: *"El máximo de la franja "mobile": 575.98px y no 575px,
  para no dejar el hueco 575.01–575.99 sin cubrir contra min-width: $bp-sm."*
- `odd/tasks/breakpoint-vars.md:71-79` (decision D2): adopting `($bp-sm - 1px)` *"would therefore
  have introduced a real one-pixel hole while pretending to be a pure refactor."*
- `openspec/changes/archive/2026-09-24-modify-form-layout-breakpoint/archive-report.md`: same
  conclusion, and it names the TS unification as the explicitly open follow-up.

The rationale is measurable and correct: the other side of the boundary is `min-width: 576px`, so
`max-width: 575px` leaves **575.01–575.99px matching neither** the mobile nor the desktop rule. This
slice therefore moves the two divergent *values* to `575.98px`, and does not invent a new number.

**Measured at `4469cf7`**, each with the command that reproduces it:

- `grep -rn "max-width: 575" src --include=*.ts` → **6** hits in 6 files: three `575.98px`, three
  `575px` (the third being `detail-table.spec.ts:44`, a spec that redeclares the component's literal
  instead of importing it).
- `grep -rn '\$bp-sm - 1px' src --include=*.scss` → **2** hits, both 575px.
- `grep -rn "max-width: \$bp-sm" src --include=*.scss` → **10** hits. Out of scope here (see
  *Follow-ups*); recorded so the count is not silently lost.
- `grep -rn "var(--bp-" src | wc -l` → **0**: the CSS custom properties in `styles.scss:123-127`
  still have no reader, so they are not a viable source of truth for a TS comparison.

## Decisions

- **D1 — 575.98px is the mobile maximum; 575px is the defect.** Not a preference: it is what
  `_variables.scss`, `breakpoint-vars.md` D2 and the archived change all say, and it is the only one
  of the two that does not leave a fractional-width hole against `min-width: 576px`. This slice
  obeys that decision rather than reopening it. Consequence, stated plainly: the mobile layout of
  `detail-table` and `receipt-detail` will now also apply in the 575.01–575.99px band. That is the
  intended behaviour, not a side effect.
- **D2 — one shared TypeScript constant, imported by deep path.** `MOBILE_MAX_WIDTH_QUERY` in
  `src/shared/constants/breakpoints.ts`. Deep alias import (`@shared/constants/breakpoints`), which
  is the existing precedent for exactly this shape of module: `@core/security/roles.ts` holds the
  app's other shared literal constants and is imported the same way, without a barrel. No new
  barrel is created, so `src/shared/index.ts` is not touched.
- **D3 — a TS constant cannot be *derived* from the Sass variable, so the drift risk is made
  testable instead of being hidden.** This is the one real weakness of the design: the value now
  lives in two files by necessity. Mitigation: `breakpoints.spec.ts` carries two assertions with two
  different jobs. The first reads `$bp-mobile-max` out of `_variables.scss` and asserts the constant
  equals it — that one fails when either side moves alone. The second asserts the parsed value is
  exactly `575.98px`, and it is the only thing that catches **both sides moving together** to a wrong
  value. Consequence stated rather than hidden: the literal therefore appears in **three** files — the
  SCSS declaration, the TS constant, and that second assertion. The assertion is a deliberate
  restatement of the decided contract, and this record's first draft wrongly described it as
  tautological (corrected per F2).
- **D4 — the spec that redeclared the literal now imports it.** `detail-table.spec.ts:44` carried
  its own copy of `'(max-width: 575px)'`. Two declarations of one query is precisely the defect
  class this slice is removing, so the spec imports `MOBILE_MAX_WIDTH_QUERY`. Its value is
  behaviourally irrelevant in that mock (`observe` returns `{ matches: true, breakpoints: {...} }`
  directly), which is *why* the copy could drift unnoticed — so removing the copy is the fix, not
  a cosmetic tidy.
- **D5 — no helper, no deduplication, no touch to the ten `max-width: $bp-sm`.** The three identical
  `matchMedia` blocks in the list pages (lines 100-102) stay duplicated: extracting a viewport
  helper is a design change, not the single-source fix, and the user explicitly chose the minimal
  scope. The `max-width: $bp-sm` (576px) set is a *separate, unproven* inconsistency — see
  *Follow-ups*.

## Scope

| # | File | Line | From | To | Behaviour |
|---|------|------|------|----|-----------|
| 1 | `src/shared/constants/breakpoints.ts` | — | *(does not exist)* | `MOBILE_MAX_WIDTH_QUERY = '(max-width: 575.98px)'` | — |
| 2 | `src/shared/constants/breakpoints.spec.ts` | — | *(does not exist)* | drift guard vs `_variables.scss` | — |
| 3 | `features/companies/companies/pages/company-list/company-list.ts` | 100 | `'(max-width: 575.98px)'` | `MOBILE_MAX_WIDTH_QUERY` | none |
| 4 | `features/products/pages/product-list/product-list.ts` | 100 | `'(max-width: 575.98px)'` | `MOBILE_MAX_WIDTH_QUERY` | none |
| 5 | `features/products/suppliers/pages/supplier-list/supplier-list.ts` | 100 | `'(max-width: 575.98px)'` | `MOBILE_MAX_WIDTH_QUERY` | none |
| 6 | `features/purchases/purchase-orders/components/detail-table/detail-table.ts` | 29 | `'(max-width: 575px)'` | `MOBILE_MAX_WIDTH_QUERY` | **575 → 575.98** |
| 7 | `features/purchases/receipts/pages/receipt-detail/receipt-detail.ts` | 20 | `'(max-width: 575px)'` | `MOBILE_MAX_WIDTH_QUERY` | **575 → 575.98** |
| 8 | `features/purchases/purchase-orders/components/detail-table/detail-table.spec.ts` | 44 | local `MOBILE_QUERY` | import of `MOBILE_MAX_WIDTH_QUERY` | none |
| 9 | `features/purchases/purchase-orders/components/detail-table/detail-table.scss` | 206 | `(max-width: ($bp-sm - 1px))` | `(max-width: $bp-mobile-max)` | **575 → 575.98** |
| 10 | `features/purchases/purchase-orders/pages/purchase-order-edit/purchase-order-edit.scss` | 68 | `(max-width: ($bp-sm - 1px))` | `(max-width: $bp-mobile-max)` | **575 → 575.98** |

**Out**: the ten `@media (max-width: $bp-sm)` files; the four off-scale hardcoded breakpoints
(`header.scss:184`, `home.scss:128`, `user-profile.component.scss:110`,
`notification-toast.scss:87`); the dead `zones-list`/`stores-list` components;
`var(--bp-*)` reachability; and the `matchMedia` triplication.

## Plan

| # | Task | Commit | Gate |
|---|------|--------|------|
| T1 | Add `breakpoints.ts` + `breakpoints.spec.ts` (the drift guard) | `refactor(angular): add a shared constant for the mobile breakpoint` | spec green |
| T2 | Repoint the three list pages (value unchanged) | `refactor(angular): read the mobile breakpoint from the shared constant` | lint + build |
| T3 | Repoint `detail-table`, `receipt-detail`, and the spec; 575 → 575.98 | `fix(angular): align the detail and receipt tables with the shared mobile breakpoint` | lint + build + tests |
| T4 | `($bp-sm - 1px)` → `$bp-mobile-max` in both stylesheets | `refactor(angular): express the mobile max through $bp-mobile-max in SCSS` | build; compiled CSS shows 575.98 |
| T5 | Full gate + independent read-only verification + record the evidence | `docs(odd): record the mobile-breakpoint single-source slice` | the five checks below |

## Gate

Unlike `breakpoint-vars`, byte-identical `dist` is **not** applicable: T3 and T4 change the compiled
output on purpose. The gate is that the change is *visible and singular*:

1. `npm run lint` — clean.
2. `npm run build` — succeeds. The built output no longer contains `max-width:575px` (or `)-1px)`)
   for these components, and the TS bundle resolves the query from the one constant.
3. `npm run test:coverage:check` — the CI runner, all specs.
4. **Divergence assertion (the real gate):**
   - `grep -rn "max-width: 575" src --include=*.ts` → exactly **1** hit, inside
     `src/shared/constants/breakpoints.ts` (all six consumers gone).
   - `grep -rn '\$bp-sm - 1px' src --include=*.scss` → **0**.
   - `grep -rn "max-width: 575\.98px" src --include=*.scss` → **0** (the SCSS side reads the
     variable, never the literal).
5. **Falsification control**: the drift guard must fail when one side is mutated alone. Verified by
   mutating `$bp-mobile-max` in `_variables.scss` and confirming `breakpoints.spec.ts` goes red, then
   restoring the file. A guard that cannot fail is not a guard.

## Evidence log

Measured on the committed range `4469cf7..639d0b4`, with the working tree equal to `HEAD`
(`git status --porcelain` clean apart from this record).

**Range**: `git diff --shortstat 4469cf7..HEAD` → **10 files changed, 71 insertions(+), 17
deletions(-)** in **4 commits**, no merge commit. Well inside the 400-line review budget.

| Commit | Files | +/− |
|--------|-------|-----|
| `152db88` constant + guard | 2 | +55 −0 |
| `4f82b06` three list pages | 3 | +6 −3 |
| `53a7ac6` detail-table, receipt-detail, spec | 3 | +8 −12 |
| `639d0b4` two stylesheets | 2 | +2 −2 |

**Gate (T5)**

| Check | Command | Result |
|-------|---------|--------|
| lint | `npm run lint` | **PASS** — `All files pass linting.` |
| build | `npm run build` | **PASS** — `Application bundle generation complete` |
| CI suite | `npm run test:coverage:check` | **PASS** — statements 94.07 / branches 75.93 / functions 89.23 / lines 94.07, thresholds 80 / 60 / 75 / 80 |
| divergence, TS | `grep -rn "max-width: 575" src --include=*.ts` | **1** hit — `shared/constants/breakpoints.ts:14` |
| divergence, SCSS arithmetic | `grep -rn '\$bp-sm - 1px' src --include=*.scss` | **0** |
| divergence, SCSS literal | `grep -rn 'max-width: 575\.98px' src --include=*.scss` | **0** |
| artifact | `grep -roh "max-width: *575[.0-9]*px" dist/life-control-app-angular \| sort \| uniq -c` | `1 max-width: 575.98px`, `10 max-width:575.98px`, **zero `575px` rows** |
| falsification control | mutate `$bp-mobile-max`, run the guard | **RED — 2 of 2 failed** (reproduced twice, with `575px` and again with `574px`); restored with `cp` → **GREEN — 2 of 2 passed**, `breakpoints.ts` at 100% coverage |

**Artifact baseline**, measured on `4469cf7` before any edit:
`3 max-width: 575.98px`, `2 max-width: 575px`, `8 max-width:575.98px`, `2 max-width:575px` — **15**, of
which **4 were `575px`**. After the change the spaced count drops 5 → 1 because the five inlined TS
literals collapse into the one shared module (esbuild emits it once as `var t="(max-width: 575.98px)"`),
while the two unspaced SCSS 575px queries change value in place: **11 total, 0 `575px`**. The drop was
traced specifically because a drop is suspicious by default — a lost media query would be a real
regression no test would catch (verified under C3) — and no media query was lost.

**Deliberately not the gate here:** byte-identical `dist`. Two call sites change the number on purpose,
so the `sha256`-identical gate the sibling feature used would be the wrong instrument.

**Environment note.** The first full-suite runs were red on this machine — 26 / 13 / 32 failures across
three runs, every one `Test timed out in 5000ms` in unrelated specs, load average 37–52 on 20 cores.
They were environmental, not caused by this change: the affected specs passed in a focused 7-file run
(130 tests) and the final `test:coverage:check` on the committed tree at load 2.16 passed cleanly.
Recorded because a red suite silently attributed to "load" is exactly how a real regression gets
waved through.

## Findings

Independent read-only verification (`gentle-ai-verify`) over the committed range. The behaviour claims
(C2), the artifact reconciliation (C3), scope (C5), the reproduced checks (C6) and the commit-message
accuracy (C7) were **UPHELD**; this record was not.

- **F1 (medium, mine, fixed here) — the record was incomplete.** The verifier found `**Status**:
  planned` and an empty `## Evidence log`: the artifact described a plan, not an executed result, and
the mutation-control evidence cited in the delivery report was absent from the artifact. Corrected —
  this log and the status line are that fix. The lesson is the one this repository keeps re-learning:
  a record that is not updated is not evidence, it is a claim.
- **F2 (low, mine, corrected) — an over-claim in D3.** The first draft said the guard exists *"not to
  restate the literal, which would be tautological"*. Misleading: the second assertion does restate
  it, deliberately, and it is load-bearing. D3 now states that the value lives in three files and why
  the third one is intentional.
- **F3 (low, recorded, deliberately NOT corrected) — commit `53a7ac6` says *"the rest of the app used
  575.98px"*, which was not true of the SCSS at that commit**: `detail-table.scss` and
  `purchase-order-edit.scss` still held `($bp-sm - 1px)` until `639d0b4`. Left as-is on purpose. The
  branch is unpushed, so a reword is technically possible, but it rewrites SHAs and would invalidate
  the verification just completed over those exact SHAs. Correcting an adjective is not worth that
  trade. Recorded instead.
- **F4 (nitpick) — commit `152db88` says *"`'(max-width: 575px)'` in two"***: there were three TS
  occurrences, the third being `detail-table.spec.ts:44`.
- **F5 (nitpick, verified not a bug) — commit `53a7ac6` says the spec "imports the component's
  constant"**: it imports the *shared* module the component also uses.
- **F6 (limitation, accepted) — the guard's mechanism is a compromise.** `breakpoints.spec.ts` reads
  the SCSS through `process.getBuiltinModule('fs')` and a `process.cwd()`-relative path rather than a
  static `node:fs` import. The deviation was forced, not preferred, and was reproduced independently:
  a static `node:fs` import fails the build with `Could not resolve "node:fs"`, because
  `@angular/build:unit-test` bundles specs for the browser platform. `import.meta.url` is equally
  unusable — the runner emits bundles under `dist/test-out/<timestamp>-<uuid>/`, so a URL-relative path
  would resolve inside that throwaway directory. Consequence: running the suite from a directory other
  than `life-control-app-angular/` fails the guard. That failure is loud and actionable (`Cannot read
  … from the working directory …`, hardened from a bare `ENOENT` during this slice) and never a silent
  pass, which is why it is accepted rather than redesigned.
- **F7 (limitation, unavoidable, accepted) — the verifier could not rebuild the baseline `dist`**
  without mutating the worktree, so the 15-item baseline above is this record's measurement and not
  independently reproduced. The verifier reconciled it from source instead (10 `$bp-mobile-max`
  conditions − 2 dead components = 8 live, + 2 `$bp-sm - 1px` = 10 unspaced) and found it internally
  consistent.
- **F8 (confirmed by the verifier, worth keeping visible) — prettier reformatted the writer's output
  inside the pre-commit hook.** `detail-table.ts`'s three-line `observe(...)` chain collapsed to one
  line at `:121`. Re-verified on the committed range, never on the pre-commit tree.

## Follow-ups

- **The ten `@media (max-width: $bp-sm)` (576px) rules.** Verified as a *consistency* inconsistency,
  **not** as a proven defect: each pair inspected styles different properties of different selectors
  (`countries-page.scss:111` touches `.filters-section .company-selector` while its `min-width:
  $bp-sm` sibling at `:33` sets `grid-template-columns` on the container; `_form-layout.scss:82`
  sets `min-height` on `textarea, button` while its sibling at `:23` sets `max-width: 720px` on the
  container). At exactly 576px both fire; whether that is wrong is **not established** and was not
  investigated further. If the repository adopts "mobile max is 575.98px" as a rule, these ten are
  formally the wrong expression regardless of whether any given pair conflicts. Reported to the user
  as option 3 and deliberately not executed.
- **The `matchMedia` block is triplicated** verbatim in `company-list.ts:100-102`,
  `product-list.ts:100-102` and `supplier-list.ts:100-102`. A shared
  `observeMobileViewport(): Signal<boolean>` with `DestroyRef` cleanup would remove it; offered to
  the user as option 2 and deliberately not executed.
- **`supplier-list.spec.ts` has no mobile test at all** — `grep isMobile` returns nothing in it,
  unlike its two siblings. It is the only one of the three list pages whose `isMobile` path is
  covered solely by compilation.
- The four off-scale hardcoded breakpoints and the two dead components remain from the previous
  inventory, untouched by this slice.
