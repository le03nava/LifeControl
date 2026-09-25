# ODD feature: mobile-breakpoint-guard

**Repository**: LifeControl — Angular frontend (`life-control-app-angular/**`). 11 SCSS media
conditions across 11 files, plus one spec. No TypeScript production code, no template, no contract, no
build config, no route, no CI change.
**Status**: implemented — 3 commits on `fix/mobile-breakpoint-guard` off `main` @ `7f8ba11`, worktree
`~/workspace/LifeControl-worktrees/fix-mobile-breakpoint-guard` (herdr workspace `w11`): `ea709a0` (the
Eleven conditions), `d437db9` (the guard), `c3de16a` (the guard's two holes, found by the independent
verification). 12 files, +295 −11. Lint, build and the CI coverage gate pass on the committed tree;
eight external probes behave as specified. Independently verified read-only — see `## Findings`.
**Merged as PR #166 (`89b4d9b76`), 2026-09-24.**
**Created**: 2026-09-24
**Risk**: **low but not zero, and deliberately not a pure refactor.** Eleven rules move from
`max-width: 576px` to `max-width: 575.98px`, so the compiled output must change and no byte-identical
gate applies. The reason it is safe is the same reason it is worth doing: those rules sit in the
device band `(575.98, 576)` today and stop doing so after the change — a **0.02px** sliver, the same
order of magnitude as the hole the repository already accepted when it chose 575.98 over 575.

## Why this exists

This is the fourth time the same defect class has entered this repository, and it is the slice that
closes it.

| PR | What it fixed | What it left behind |
|----|---------------|---------------------|
| #164 | 10 SCSS literals → `$bp-mobile-max` | 6 TS literals, 2 SCSS arithmetic expressions |
| #165 (this session) | those 8 | 10 `max-width: $bp-sm`, 1 literal `576px` |

Each fix removed instances and left the class standing. The investigation that produced this slice
(`gentle-ai-explore`, read-only, recorded in memory as `lifecontrol/breakpoint-576-investigation`)
established the two facts that make the decision:

**Fact 1 — the eleven are an expressive inconsistency, NOT a demonstrated bug.** Per-site analysis of
selectors and properties: **0 CONFLICT**, 7 NO-CONFLICT, 3 NO-MIN-SIBLING. Where a
`min-width: $bp-sm` sibling exists in the same file, the two blocks touch disjoint selectors and
disjoint properties (filters vs grid, `.empty-svg` vs grid, form controls vs `.form-card`), so nothing
is overridden visibly at 576px. **This record does not claim a bug, and no commit message may claim
one.**

*(Corrected after the independent verification, finding F6: the classification above sums to **ten**,
not eleven — it covers the ten `$bp-sm` sites. The eleventh, `user-profile.component.scss:110`, was
characterised separately and is also NO-MIN-SIBLING. Measured directly: 7 of the 11 files contain a
`min-width: $bp-sm` sibling and 4 do not (`store-inventory-settings`, `product-supplier-form`,
`product-supplier-list`, `user-profile`).)*

**Fact 2 — the eleven are `CONFLICT`-free but semantically "mobile only".** No site carries a comment
supporting a deliberate "up to and including the small tier" reading; the only adjacent comments are
`/* Responsive */`. And sites 2–7 pair `min-width: $bp-sm` with `max-width: $bp-sm` **in the same
file**, claiming 576 from both sides at once — which reads as not having reasoned about the half-open
band, not as intent.

The inventory is **closed**: no fifth expression exists. No `min-width: $bp-mobile-max` (the reverse
mistake), no `$bp-sm`/`$bp-mobile-max` inside `calc()`/`clamp()`, no `@media` width in `em`/`rem` —
every width condition in the app is px.

**Measured at `7f8ba11`**, each with the command that reproduces it:

- `grep -rn 'max-width: \$bp-sm' src --include=*.scss | wc -l` → **10**.
- `grep -rnE '@media[^{]*max-width: *576px' src --include=*.scss | wc -l` → **1**
  (`user-profile.component.scss:110`, the only *literal*).
- `grep -rn 'min-width: \$bp-sm' src --include=*.scss | wc -l` → **23**. The min side stays.
- `grep -rn 'min-width: \$bp-mobile-max' src --include=*.scss | wc -l` → **0**.
- **Non-token width expressions → 0.** Every width value in an `@media` condition is a single token
  (`$bp-sm`, `$bp-mobile-max`, `$bp-md`, `$bp-lg`) or a plain numeric literal. The two
  `($bp-sm - 1px)` arithmetic expressions were closed by #165.
- **Literal-px `@media` widths → 4**: `user-profile.component.scss:110` (576), `home.scss:128` (768),
  `notification-toast.scss:87` (480), `header.scss:184` (767, min side).
- **No stylelint, no `eslint-plugin-css`** in `devDependencies`; `scripts/` holds only
  `check-coverage.mjs`, which reads a report rather than scanning sources.

## Decisions

- **D1 — `max-width: $bp-mobile-max` is the only correct expression for "mobile" in this repository,
  and it applies to all eleven.** The zero-conflict result does not weaken this; it *isolates* it.
  Because nothing conflicts, the change is not a fix for a rendering bug — it is the removal of a
  second, unratified way of naming the same boundary. Stated plainly, because it is the whole
  justification: **we are buying one expression at the price of a 0.02px sliver.**
- **D2 — the overlap at exactly 576px is removed as a side effect, and that is a second, independent
  argument.** Today, at precisely 576px, both the mobile rule and the `min-width: $bp-sm` rule apply.
  Afterwards, 576px is exactly the small tier and nothing else. Sites 2–7 stop claiming a boundary
  that belongs to `$bp-sm`.
- **D3 — the guard is a spec, not a script and not a CI step.** The repository already has the exact
  pattern: `breakpoints.spec.ts` reads `_variables.scss` from TypeScript via
  `process.getBuiltinModule('fs')`. A guard built the same way runs inside `npm test`, which CI
  already runs — **zero new npm scripts, zero new CI steps, zero changes to
  `.github/workflows/angular-ci.yml`**. Adding stylelint for one rule would be a new toolchain for a
  single invariant, and the absence of any stylesheet linter is itself the evidence not to.
- **D4 — the guard has ZERO exceptions, and that is the acceptance criterion.** An allowlist of eleven
  entries would be debt disguised as configuration, and this repository's own history shows allowlists
  of this shape do not get burned down. The guard must pass on the fixed tree with no allowlist. If a
  rule cannot pass without one, the rule is wrong and gets narrowed — **not** exempted.
- **D5 — the three genuinely off-scale literals are explicitly OUT of the guard's scope.** `480px`,
  `768px` and `767px` are a different, undecided question; the guard names the **575/576**
  neighbourhood only. A guard that forbade all px literals would force a decision that has not been
  made, and would be disabled the first time it blocked legitimate work.
- **D6 — the guard is written FIRST and run against the unfixed tree.** Not TDD ceremony: it is the
  only way to prove the guard detects the *real* defect rather than a hypothetical one. The expected
  red is **exactly eleven violations**, at eleven named locations. A guard whose first run does not
  reproduce the defect it was written for is unproven, however green it goes afterwards.
- **D7 — a single-token rule, not an operator blacklist, and the reason is a bug this slice already
  made once.** The first attempt at measuring arithmetic used an operator class containing `-`, which
  matched the hyphens inside `$bp-mobile-max` and reported **12 false positives** where the true
  answer was 0. Any guard built the same way would inherit that bug. So the rule is positive, not
  negative: a width value must be **either** a single variable token (`$[a-zA-Z0-9_-]+`) **or** a plain
  numeric literal (`\d+(\.\d+)?px`); anything else — arithmetic, `calc()`, multiple tokens — is
  rejected by construction, with no operator scanning at all.

## Scope

| # | File | Line | From | To |
|---|------|------|------|-----|
| 1 | `src/shared/styles/_form-layout.scss` | 82 | `(max-width: $bp-sm)` | `(max-width: $bp-mobile-max)` |
| 2 | `src/shared/styles/_entity-list-page.scss` | 293 | `(max-width: $bp-sm)` | `(max-width: $bp-mobile-max)` |
| 3 | `src/shared/styles/_store-entity-page.scss` | 203 | `(max-width: $bp-sm)` | `(max-width: $bp-mobile-max)` |
| 4 | `features/companies/countries/pages/countries-page/countries-page.scss` | 111 | `(max-width: $bp-sm)` | `(max-width: $bp-mobile-max)` |
| 5 | `features/companies/regions/pages/regions-page/regions-page.scss` | 114 | `(max-width: $bp-sm)` | `(max-width: $bp-mobile-max)` |
| 6 | `features/companies/stores/pages/stores-page/stores-page.scss` | 117 | `(max-width: $bp-sm)` | `(max-width: $bp-mobile-max)` |
| 7 | `features/companies/stores/pages/store-inventory-settings/store-inventory-settings.scss` | 83 | `(max-width: $bp-sm)` | `(max-width: $bp-mobile-max)` |
| 8 | `features/companies/zones/pages/zones-page/zones-page.scss` | 116 | `(max-width: $bp-sm)` | `(max-width: $bp-mobile-max)` |
| 9 | `features/products/components/product-supplier-form/product-supplier-form.scss` | 11 | `(max-width: $bp-sm)` | `(max-width: $bp-mobile-max)` |
| 10 | `features/products/pages/product-supplier-list/product-supplier-list.scss` | 182 | `(max-width: $bp-sm)` | `(max-width: $bp-mobile-max)` |
| 11 | `features/user/profile/user-profile.component.scss` | 110 | `(max-width: 576px)` + **no `@use`** | `(max-width: $bp-mobile-max)` + a new `@use` |
| 12 | `src/shared/constants/breakpoints.spec.ts` | — | drift guard only | drift guard **+ source guard** |

**Number 11 is the only one needing a new import.** It is the sole site without a `@use` of
`variables` — verified, it has no `@use` at all. Relative depth was computed with
`realpath --relative-to`, never by hand: from `src/features/user/profile/` the path is
`../../../shared/styles/variables`. The other ten already import the partial, since they already use
`$bp-sm`; **none of their `@use` lines may change**.

**Out of scope:** the three off-scale literals; the dead `zones-list`/`stores-list` components; the
triplicated `matchMedia` block; `var(--bp-*)`; and any change to the `min-width: $bp-sm` side.

## Plan

| # | Task | Commit | Gate |
|---|------|--------|------|
| T1 | Write the source guard **first**; run it against the unfixed tree | *(committed with T2's spec, after)* | **RED with exactly 11 violations at the 11 named sites** |
| T2 | The 10 `max-width: $bp-sm` → `$bp-mobile-max` | `fix(angular): express the mobile maximum through $bp-mobile-max everywhere` | guard RED with 1 left; lint + build |
| T3 | `user-profile.component.scss`: add the `@use`, change the literal | *(same commit as T2)* | **guard GREEN, zero exceptions** |
| T4 | Mutation controls: one injected violation per rule | — | each rule **RED**, restored with `cp` → GREEN |
| T5 | Full gate on the committed tree | — | the five checks below |
| T6 | Independent read-only verification, then record | `docs(odd): record the mobile-breakpoint guard slice` | verifier report |

Commits stay green at every step: the spec is written first to capture the red evidence but is
**staged second**, so `T2+T3`'s commit is green on its own and the spec commit adds enforcement
afterwards.

## Gate

1. `npm run lint` — clean.
2. `npm run build` — succeeds.
3. `npm run test:coverage:check` — the CI runner, all specs.
4. **Source assertions:**
   - `grep -rn 'max-width: \$bp-sm' src --include=*.scss` → **0**
   - `grep -rn 'min-width: \$bp-mobile-max' src --include=*.scss` → **0**
   - `grep -rnE '@media[^{]*(575|576)(\.[0-9]+)?px' src --include=*.scss` → **0**
   - `grep -rn 'min-width: \$bp-sm' src --include=*.scss | wc -l` → **23**, unchanged
   - non-token width expressions → **0**
5. **Artifact assertion, with exact expected counts.** Baseline measured on this worktree at
   `7f8ba11`: `(max-width:575.98px` = **11**, `(max-width:576px` = **27**, `(min-width:576px` = **49**.
   After the change: `(max-width:576px` must be **0**, `(max-width:575.98px` must be **38**
   (11 + 27), and `(min-width:576px` must still be **49** — the min side must not move.
   Command:
   `grep -rohE '\((max|min)-width: *(575|576)(\.[0-9]+)?px' dist/life-control-app-angular | sed 's/ *//g' | sort | uniq -c`
   The 27-vs-10 gap between source and artifact is the shared partials being emitted once per
   includer, which is why the artifact count is the meaningful one. **If the numbers do not land
   exactly, that is a finding to investigate, not a rounding error.**

*(Corrected after the independent verification, finding F1: the first draft attributed that gap to
`_form-layout.scss` being emitted 17 times — its includer count. That is wrong; it is emitted **13**
times. The four non-emitting includers are the unreferenced components `app-zones-list`,
`app-stores-list`, `app-regions-list` and `app-country-selector`, which are tree-shaken out. The
measured arithmetic is 13 + 3 + 3 + 7 + 1 = **27**, where the 7 are the component-local conditions and
the 1 is the `user-profile` literal. The final number 27 was right and the reason for it was not,
which is exactly the kind of thing this record exists to be checkable for.)*

*(Second correction, finding F5: the `38` is not a pure CSS count. It includes one occurrence of
`MOBILE_MAX_WIDTH_QUERY` inlined into a JavaScript chunk, so the number of CSS media blocks is really
**37**. The gate states 38 because that is what the command returns and it reproduces exactly — but
the honest reading is 37 CSS blocks plus one JS string.)*
6. **Falsification controls (T4)** — each guard rule must fail when violated:
   - a literal `max-width: 576px` injected into any SCSS → red
   - a `max-width: $bp-sm` injected → red
   - a `min-width: $bp-mobile-max` injected → red
   - an arithmetic condition `($bp-sm - 1px)` injected → red
   - a `matchMedia('(min-width: 768px)')` literal injected into a non-spec TS file → red
   Each restored with `cp` from a backup, never with `git checkout`.

## Evidence log

Measured on the committed range `7f8ba11..c3de16a`, working tree equal to `HEAD`.

**Range**: 12 files, **+295 −11**, in **3 commits**, no merge commit.

| Commit | Files | What |
|--------|-------|------|
| `ea709a0` | 11 SCSS | the eleven conditions → `$bp-mobile-max` (+1 `@use`) |
| `d437db9` | 1 spec | the guard, three SCSS rules + one TS rule |
| `c3de16a` | 1 spec | the guard's interpolation hole and comment false positives |

**The RED-first evidence (D6).** The guard was written and run before any SCSS changed. It reported
**exactly 11 violations at the 11 named sites**: ten `(max-width: $bp-sm)` and one
`(max-width: 576px)` at `user-profile.component.scss:110`. A guard whose first run does not reproduce
the real defect is unproven, however green it goes afterwards. The independent verification was able
to confirm the count **by reading** — it enumerated every width clause in the pre-fix tree and derived
11 — but could not observe the run itself, and said so (F8).

**Gate on the committed tree**

| Check | Result |
|-------|--------|
| `npm run lint` | **PASS** — exit 0, `All files pass linting.` |
| `npm run build` | **PASS** — exit 0 |
| `npm run test:coverage:check` | **PASS** — **2522 tests**, thresholds 94.07 / 75.93 / 89.23 / 94.07 against 80 / 60 / 75 / 80 |
| `grep -rn 'max-width: \$bp-sm' src --include=*.scss` | **0** |
| `grep -rn 'min-width: \$bp-mobile-max' src --include=*.scss` | **0** |
| `grep -rnE '@media[^{]*(575\|576)(\.[0-9]+)?px' src --include=*.scss` | **0** |
| `grep -rn 'min-width: \$bp-sm' src --include=*.scss \| wc -l` | **23** — unchanged, the min side did not move |
| non-token width expressions | **0** |
| artifact `(max-width:575.98px` | **38** — of which 37 CSS blocks + 1 JS string (F5) |
| artifact `(max-width:576px` | **absent → 0** |
| artifact `(min-width:576px` | **49** — unchanged |

The artifact total stays at **87** (11 + 27 + 49 before, 38 + 49 after), which is the evidence that no
media query was lost.

**Eight external probes, each on a live tree with its own scratch file, deleted before the next one**
(the first attempt at this left one scratch file alive and made the next probe look like a
double-report — recorded because it nearly became a false finding against the guard):

| Probe | Injected shape | Violations |
|-------|----------------|-----------|
| P1 | `@media (max-width: 576px)` | 1 |
| P2 | `@media (max-width: $bp-sm)` | 1 |
| P3 | `@media (min-width: $bp-mobile-max)` | 1 |
| P4 | `@media (max-width: ($bp-sm - 1px))` | 1 |
| P5 | `'(min-width: 768px)'` in a non-spec `.ts` | 1 |
| P6 | `@media (max-width: #{$bp-sm})` — interpolation | **1** after `c3de16a`; was **0** before |
| P7 | a SCSS comment quoting the removed condition | **0** — correct; was **1** before |
| P8 | a `.ts` comment quoting a removed query | **0** — correct |

**The guard's own tests**: 12, written and run red before `c3de16a`'s fix (`3 failed | 3 passed`), then
green. They call `scanScss` / `scanTs` directly as pure functions on synthetic content, so the guard's
edge cases — interpolation, comments, line-number stability, one control per probe shape — are
regression-protected without touching the filesystem.

## Findings

Independent read-only verification (`gentle-ai-verify`) over the committed range, plus the record's own
corrections. C1 (the eleven fixes), C4 (zero exceptions), C6 (scope) and C8 (commit messages) were
**UPHELD**. C3 came back **REFUTED** — and that was the finding worth paying for.

- **F1 (medium, mine, corrected) — the record's artifact attribution was numerically wrong.** It
  claimed `_form-layout.scss` is emitted 17 times; measured, it is **13**. See the correction under
  Gate item 5. The final number was right for the wrong reason.
- **F2 (medium, real defect in the guard, FIXED in `c3de16a`) — the guard was blind to
  interpolation.** `MEDIA_BLOCK = /@media([^{]*)\{/` stopped at the `{` of `#{`, so
  `@media (max-width: #{$bp-sm})` never reached rule 1. That is a genuine bypass of the guard's own
  invariant, and `#{…}` is idiomatic here (`#{$grid}`, `#{$page}`, `#{$level-selectors}`), while
  `#{$bp-sm}` emits exactly the 576px this guard exists to stop.
- **F3 (medium, real defect in the guard, FIXED in `c3de16a`) — the guard cried wolf on comments.**
  Nothing stripped comments, so documenting the removed expression — the natural next edit for anyone
  reading this branch — made the condition span from the comment to the next real brace and reported a
  violation for already-correct code. A guard that produces false positives gets disabled; that is
  the failure this whole slice exists to prevent, and it was present in the guard meant to prevent it.
- **F4 (medium, my instruction was wrong, caught by the implementer)** — the fix I prescribed for F2,
  `(?:[^{]|#\{[^}]*\})*`, **does not work**: `[^{]` is tried first and consumes the `#`, after which
  the leftover `{` is read as the block opener and the condition is truncated again. The alternation
  must lead with the interpolation branch. The implementer measured it with a read-only probe, said the
  instruction was wrong rather than complying quietly, and corrected it to
  `(?:#\{[^}]*\}|[^{])*`. Recorded because a silently-complied-with wrong instruction would have
  shipped a fix that did not fix anything.
- **F5 (low, mine, corrected) — the artifact count is not a pure CSS count.** The `38` includes one
  `MOBILE_MAX_WIDTH_QUERY` inlined into a JavaScript chunk; real CSS media blocks are 37.
- **F6 (low, mine, corrected) — the per-site classification summed to ten, not eleven.**
- **F7 (nit, recorded, deliberately NOT corrected) — commit `d437db9` overstates two rules.** It says
  rule 3 pairs `max-width` with `$bp-mobile-max` and `min-width` with `$bp-sm`, but the code bans only
  those two exact crosses; and it describes the TypeScript rule as covering media-query literals,
  omitting the `*.spec.ts` exclusion. Not corrected for the same reason as the sibling slice's F3:
  rewording rewrites the SHAs the verification was performed against.
- **F8 (limitation, accepted) — the guard was never executed by the verifier.** It confirmed GREEN by
  exhaustive source greps and by reading the unconditional `it`, not by running it. The parent's run
  output is the evidence for the test counts.
- **F9 (limitation, accepted) — the scan root and file types are narrow.** `SOURCES_ROOT = 'src'`
  plus `.scss`/`.ts` only means a `.css` file, an HTML `<style>` block, or a stylesheet outside `src/`
  is never inspected. Nothing violates this today, and `src/styles.scss` is inside the root — but the
  guard is advertised as enforcing an app-wide invariant and does not quite.
- **F10 (residual holes, documented and NOT fixed)** — the guard still misses: a protocol-relative
  `url(//host/…)` (the `(?<!:)` guard only protects `http://` and `https://`); an interpolation group
  containing a `}` (`#\{[^}]*\}` stops at the first one, so a map or quoted string inside `#{…}`
  could truncate the condition); `@media` in uppercase or `(max-device-width: …)` or range syntax
  (`(width <= 576px)`), all case-sensitivity or property-name gaps; and a query split across string
  concatenation. **None is reachable in this codebase today**, and each was measured against the
  current source rather than assumed. Recorded so the next person inherits the map instead of the
  illusion of completeness.

## Follow-ups

- **The guard's residual holes (F10) are documented, not fixed.** Protocol-relative `url(//…)`, an
  interpolation group containing `}`, uppercase or `max-device-width` or range-syntax conditions, and
  a query split across string concatenation. None is reachable in this codebase today, each was
  measured rather than assumed. The cheapest real improvement is widening the scan past `.scss`/`.ts`
  (F9); the rest stay theoretical until the SCSS here adopts those shapes.
- **The guard's own edge cases now have regression tests**, which is what makes `c3de16a` worth more
  than the two-line fix it contains: the next person who touches `MEDIA_BLOCK` will be told by a test,
  not by a review, whether they reopened the interpolation hole or the comment false positive.

- **The three off-scale literals** (`home.scss:128` 768, `notification-toast.scss:87` 480,
  `header.scss:184` 767) remain hardcoded, deliberately. `header.scss:184`'s `min-width: 767px` is
  0.98px off `--bp-tablet` (767.98px); the other two have no token at all. Each needs its own
  behaviour decision, and none has test coverage.
- **The reverse-mistake rules (`min-width: $bp-mobile-max`) currently have nothing to catch.** They are
  in the guard to keep the boundary symmetric, and will be unproven in production until someone makes
  that mistake — which is the point of a guard.
- **`header.ts:156` uses CDK `Breakpoints.Small`/`XSmall` (600px)**, a genuinely different tier, not
  this boundary. Deliberately untouched and not covered by the guard.
- Unchanged from the previous inventory: the dead `zones-list`/`stores-list` components, the
  triplicated `matchMedia` block, `var(--bp-*)` having no consumer, the ODD record header drift, and
  `docker/scripts/_common.sh::verify_artifact_freshness`.
