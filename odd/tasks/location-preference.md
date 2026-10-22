# The location preference preselects the screens' selectors, derived from the store

**Status**: implemented — `GET /api/profile` carries a `location` chain derived from the resolved store
preference, `PUT /api/profile` gains `clearStorePreference` (the stored column could not be cleared at
all before this unit), and one shared Angular `LocationPreference` replaces the two per-feature copies
of the resolver. A repair closed the one defect the independent verification found: the shared
resolution now invalidates when the preference is saved. Nothing in this unit is open; `G15` and `G17`
are separate work.
**Created**: 2026-10-21 · **Risk**: **3** — the profile read is shared by several screens and a
regression in its shape is invisible to the type checker; the change adds an optional response field,
an optional request field and one rule to an existing write, so it breaks no contract by itself.
**Not in this unit**: the purchase-order cascade (`company-cascade.service.ts`) and the sales-order
create screen (`sales-order-edit.ts`) keep their own `GET /api/stores/{storeId}` seed. They already
preselect today, so moving them is a refactor rather than a requirement, and it is the blast radius
this unit refuses to grow. The four tenancy ancestors stay **write-never and read-never** (`T18` of
`company-scope-local-fallback`): nothing here returns them, writes them or reads them as an
authorization input.

Feature: `life-control-api` (`profile/**`) + `life-control-app-angular` (`features/user/profile`,
`features/companies/regions`, `features/products`, `features/scheduling`). Worktree branch
`feat/location-preference`.

## Specs

S1. `GET /api/profile` returns `location`: the `companyId`, `companyCountryId`,
`companyRegionId`, `companyZoneId`, `companyStoreId` and `companyStoreName` of the **resolved** store
preference, derived from the store itself through the same authorized read `GET /api/stores/{storeId}`
uses (`CompanyStoreService.getStoreById(UUID)`: load by id, walk the store's own JPA associations,
`verifyCompanyStoreAccess`, a denial reported as not-found). The response carries **no** ancestor
**names**: every screen that renders one already loads the catalogue it needs in order to have the
option present, and a preselect is worthless without the option.

S2. `location` is `null` when there is no store preference, when the stored store is not covered by a
current assignment (D10 of `employee-store-assignments`), or when the caller can no longer read that
store. All three leave the read **successful**: no 500, no 404, and `companyStoreId` keeps exactly the
resolution it has today.

S3. `location` is read-only: no field of `ProfileUpdateRequest` lets a caller assert a chain. It is
enforced by the **parser**, not by the absence of a field — `spring.jackson.deserialization
.fail-on-unknown-properties=true` (`application.properties:29`) rejects a body carrying a
`location`-shaped object **or** the four tenancy ancestors with **400**, and neither Keycloak nor
`user_preferences` is written. The earlier wording said such a body was *ignored*; that is false on
this configuration and is corrected here (`L18`).

S4. `PUT /api/profile` accepts `clearStorePreference` as a boxed `Boolean`, so an absent key arrives as
`null`. `true` sets the stored `company_store_id` to `null`; a non-null `companyStoreId` sets it;
neither present leaves the stored value untouched. This is the rule the screen already assumed and the
service never implemented: with a `UUID` field, an omitted key and an explicit `null` are
indistinguishable, so `«— None —»` restored the stored value instead of clearing it. **It is boxed, not
`boolean`, and that is measured rather than stylistic** (L9): Jackson 3 ships with
`FAIL_ON_NULL_FOR_PRIMITIVES` on, so a primitive component turns an omitted key into a 400 and would
break every existing `PUT /api/profile` client.

S5. `clearStorePreference: true` together with a non-null `companyStoreId` is rejected with **400**, and
neither Keycloak nor `user_preferences` is written — the refusal happens **before** the identity update,
the order `T24` of `employee-store-assignments` established.

S6. The Angular app exposes **one** shared `LocationPreference` whose precedence is: a call-site
override (the route's query parameters) → `profile.location` → `null`. It replaces the two declared
per-feature copies (`products/data/variant-store-context.service.ts`,
`scheduling/data/scheduling-store-context.service.ts`, `D39`), which keep their public surface and
delegate. Because it is root-scoped, its resolution is **invalidated by the writer**: a successful
`PUT /api/profile` calls `invalidate()` so the next `resolve()` re-reads, and the same-input
idempotency holds only until that announcement. A cache with no invalidation would hide the very
write this unit makes possible (`L18`).

S7. `/companies/regions` preselects **Company** and **Country** from `location` when the route carries
no explicit ids, and keeps the route's ids when it does.

S8. When `location` is `null` the selectors start empty and the store-scoped screens **in this unit's
scope** keep failing closed: neither guesses a location nor issues a store-scoped request. The
qualifier is measured, not cosmetic: the purchase-order cascade and the sales-order create screen are
a declared non-goal and still resolve a store from `companyStoreId`, so they do issue that read for
the legitimate `S2` pair (stored id, no chain) — `L18`.

S9. Navigating a screen never writes the preference. The only writer is the subject, from `/profile`.

S10. The profile edit screen's `«— None —»` clears the preference through `clearStorePreference`, and an
untouched store control still omits `companyStoreId` from the payload (`T16` of
`company-scope-local-fallback`).

S11. The profile view mode's Location row shows the store's own name in the free mode (today it prints
the literal `Store assigned`), and keeps the assigned store's derived chain in the constrained mode.

## Tasks

| ID | Spec | Route | Commit |
| --- | --- | --- | --- |
| T1 | S1, S2, S3 | backend: `LocationChain` in `ProfileResponse`, `resolveLocation` best-effort in `ProfileService`; RED specs first | `a41a6e1` |
| T2 | S4, S5 | backend: `clearStorePreference` in `ProfileUpdateRequest`, refusal ordered before Keycloak | `a41a6e1` |
| T3 | S6 | Angular: shared `LocationPreference`; the two copies delegate to it | this commit |
| T4 | S7 | Angular: regions preselection through `regions-page` → `regions-form`'s existing inputs | this commit |
| T5 | S8 | fail-closed pins on the store-scoped screens | this commit |
| T6 | S9, S10, S11 | `/profile` as the only writer; `«— None —»`; view-mode label | this commit |
| T7 | S1–S11 | gates: focused suites, full suites, lint, build, differential | `69d3bbd` |
| T8 | S3, S6, S8 | repair after independent verification: `LocationPreference.invalidate()`, the strict-Jackson pins, the three prose corrections | this commit |

## Log

L1 (user, verbatim): «no vamos a seguir explorando, la idea de esas preferences es por ejemplo para que
cuando el usaurio abra alguna pantalla por ejemplo /companies/regions los selectores ya aparezcan
preseleccionadas con las preferencias del usuario y asi en varias pantallas, como propones que se pueda
hacer esa administración»

L2 (user decision, verbatim option label): «Sólo tienda; la cadena se deriva» — one stored pointer,
ancestors derived from the store, so they cannot go stale and cannot be forged. The consequence taken:
a caller with no employee row has no chain, so the selectors stay empty for them.

L3 (user decision, verbatim option label): «Declarada en /profile» — one explicit declaration, shared by
every screen. Navigating a screen does not rewrite it, which is what keeps `one writer` true.

L4 (user, verbatim): «si arranca con el registro odd»

L5 (measurement, 2026-10-21, against `origin/main @ 8b03687`). The premise this unit rests on, read from
the tree rather than assumed: `user_preferences` keeps five location columns and only
`company_store_id` is written — `getCompanyCountryId`/`getCompanyId`/`getCompanyRegionId`/
`getCompanyZoneId` and their setters have **no caller** outside their own declaration
(`UserPreferences.java:46-58`, `:75-87`), the repository declares no derived query on them, and
`ProfileService.updateProfile` sets only the store. `T18` of `company-scope-local-fallback` keeps the
columns on purpose, so nothing here migrates them.

L6 (measurement, 2026-10-21). The clear defect S4 closes, with its location: `ProfileUpdateRequest`
carries `UUID companyStoreId`, and `ProfileService.updateProfile` writes it under
`if (request.companyStoreId() != null)`, so an omitted key and an explicit `null` take the same branch
and the column can never be cleared — while `profile.models.ts` documents the opposite ("sending `null`
clears it"). The backend pin that appears to cover it
(`ProfileServiceTest.updateProfile_noLocationAtAll_leavesTheFourAncestorsUntouched`) passes only
because its fixture row starts without `company_store_id`.

L7 (measurement, 2026-10-21). The duplication S6 removes is **declared, not incidental**: `D39` of the
products feature justified the copy inside scheduling so that a products rename could not break it. The
shared service lands in `features/user/profile/data/`, which **both** features already import
(`ProfileService`), so it removes two copies without adding an edge.

L8 (design decision). The chain is derived on **read**, never stored: storing the four would denormalize
`store → zone → region → country → company` into a row nobody validates, which is the shape `T14` of
`company-scope-local-fallback` removed. It also makes invalidation free — reassigning a store changes
what the preference resolves to with no cleanup job.

L9 (measurement, T2, and a correction to this record's own first wording). S4 was first written as a
`boolean`, and a primitive is wrong on this stack: with `boolean clearStorePreference`, nine
pre-existing `PUT` specs answered **400** `Malformed or unreadable request body`, because Jackson 3
ships `DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES` **on** — an omitted key on a primitive
creator property fails instead of defaulting. The field is boxed, which is also the module's existing
idiom for an optional boolean (`CompanyRequest.enabled`), and `null` means *do not clear*. Pinned at
the controller (absent key) and in the service (`null`).

L10 (design decision, T1, and the piece S2 depends on). S2 requires the profile read to **succeed** when
the preferred store can no longer be read, and a plain catch could not deliver that: a
`RuntimeException` crossing a *participating* `@Transactional` boundary marks the surrounding
transaction rollback-only, so catching `CompanyStoreNotFoundException` inside `ProfileService`'s
transaction would have died at commit with `UnexpectedRollbackException` — a 500 on exactly the read
S2 protects, and invisible to a unit test of the caller. The derivation therefore lives in its own bean
(`ProfileLocationResolver`) annotated `@Transactional(propagation = NOT_SUPPORTED)`, so the store read
owns its own transaction and a denial rolls back only that one. It has to be a separate bean: a private
method would self-invoke and bypass the proxy. The cost taken, declared rather than hidden: a profile
read briefly holds two pool connections, and the transactional reasoning is **not** covered by an
integration test — the focused suites run without Postgres.

L11 (gate evidence, T1-T2, 2026-10-21). `./gradlew test --tests 'com.lifecontrol.api.profile.*'` →
**53 tests / 0 failures / 0 errors**, read from the surefire XML of all eight classes (35 pre-existing
plus 18 new: `LocationChainTests` 6, `ClearStorePreferenceTests` 4, `ProfileControllerTest` 8).
`spotlessApply` and `spotlessCheck` green. RED was observed twice: first as `compileTestJava` failures
with the contract absent, then as 6 behavioural failures with the contract present and the behaviour
missing. The differential against the base tree, same instrumentation, shows exactly one difference for
a `PUT` carrying the old body shape — the added `location` key — with status, request and every other
field byte-identical.

L12 (delivery strategy, 2026-10-21, recorded before the second commit rather than after it). The unit
travels as **three chained slices**, because the first one measured **876 authored changed lines**
against the repository's ~400-line review budget and the remainder adds two screens' worth of wiring:

- **S1** = T1+T2, the backend contract — `a41a6e1`. 237 of its additions are production code (84 of
them the new resolver), 484 are the two spec files and 155 are this record. The contract is not split
further: cutting a single response shape across two reviews costs more than it buys.
- **S2** = T3+T4+T5, the shared Angular `LocationPreference`, the two contexts that delegate to it and
the regions preselection.
- **S3** = T6, `/profile` as the only writer, «— None —» and the view-mode label.

T7's gates run on every slice and once over the whole branch, since a preselect can be green per slice
and wrong when the second writer appears.

L13 (measurement and two corrections, T3-T5, 2026-10-21). The slice measured **898 insertions / 97
deletions across 21 files** — five production files (one of them the new 187-line service) and sixteen
spec files, two of them new. That is over the ~400-line budget again and it is declared rather than
rounded: the shared service and its two delegates cannot be reviewed apart, because the delegates'
whole content is the delegation. Two of the record's own premises were wrong and are corrected here:

- **T4's seam was not where this record said.** `regions-page` does **not** render `regions-form`; it
  renders `RegionsCard`. `regions-form` and its `initialCompanyId`/`initialCountryId` inputs belong to
  `regions-edit` only. The seam actually used is the page's own `selectedCompanyId`/
  `initialCountryId` signals, extended; `regions-form` is untouched, so no second seam was invented.
- **The override rule is all-or-nothing, and that is now the declared reading of S6.** A route that
  names *any* level answers the location question and the profile is not consulted for the rest,
  because a chain stitched from two sources would pair one company's ancestors with another company's
  store. A level the route did not name is the empty string internally and `null` on the public ids.

L14 (declared coupling and open gap, T3). The service is root-scoped, so `reload()` re-runs the shared
resolution for whichever call site asked last. Two guard rails make that safe rather than lucky: every
resolution is keyed, so a chain resolved for `?storeId=A` is never reported to a call site asking for
`B`, and every call site that overrides nothing shares one key, which is what keeps the profile layer a
single read. **G14**: the only coexisting pair in the tree today — a page and its
`product-variant-store-stock` child — resolves from the same URL value, so contention is untested; and
`user-profile.component.spec.ts` carries `location: null` beside a stored `companyStoreId`, which is
the legitimate S2 pair, so **S11 must put a real chain in that fixture** when the view-mode label starts
reading `location`.

L15 (measurement, T6, 2026-10-21). The location half of the payload now comes from one
`storePreferencePayload()`, so `companyStoreId` and `clearStorePreference` are mutually exclusive **by
construction** rather than by discipline — the server answers the pair with 400, so a rule a caller
could forget was the wrong shape. The three wire states are pinned in both selectors: the free cascade
and the constrained select each clear through their own handler, and a spec covers each. RED was six
failing specs against 46 passing; GREEN was 52 in the component spec (46 pre-existing plus 6) and 8 in
`profile.service.spec.ts`. One pre-existing assertion changed its meaning rather than its count: the
test that pinned *clears by sending `null`* now pins *clears by `clearStorePreference`*, which is the
superseded shape `L6` documented. **G14 is closed**: the component's fixture carries a real chain and
the S2 pair (stored id, unresolved chain, row hidden) keeps its own fixture, so neither case was
weakened to make the other work.

L16 (declared gap, **G15**, found by T6 and left alone). The free mode's ancestor handlers
(`onCompanyChange`, `onCountryChange`, `onRegionChange`, `onZoneChange`) reset the form's
`companyStoreId` **without** setting `storeTouched`, so saving after changing only an ancestor omits
both keys and the stored preference survives. That is pre-existing and consistent with S10, whose three
rules are stated for the store control; it is declared rather than silently changed because it is a
different question — the form can show *no store* while the row still holds one, and the next
`/profile` load brings that store back. Changing it is its own decision with its own spec.

L17 (gate evidence, T7, 2026-10-21). Every number below was read from a report, never from a summary
line:

- **API, full suite**: **938 suites / 3679 tests / 0 failures / 0 errors / 0 skipped**, counted from the
  surefire XMLs under `build/test-results/test/`.
- **Angular, the coverage gate**: **185 test files / 3719 tests passed**; statements 86.70, branches
  85.86, functions 79.31, lines 89.31 — all above threshold, `Cobertura dentro de los umbrales. OK`.
- **Focused specs of this unit**: 6 files / 137 tests.
- `npm run lint` → all files pass; `npm run build` → bundle generation complete; Prettier over the
  25 TS/HTML/SCSS files this unit changed → all matched.
- `angular-formatting-gate.test.sh`, which `origin/main` added in PR #516 **after** this branch was cut,
  passes on the merged tree.
- **Merge `69d3bbd`**: conflict-free, as the pre-merge measurement predicted (zero overlap with this
  unit's 29 files). `origin/main` did not touch `life-control-api`, which is what makes the API suite
  from the pre-merge tree valid evidence instead of something to re-run on a busy box.

G16 (evidence hygiene, found by T7 and recorded because it nearly produced a false green). The first
full gate run piped every command into `tail`, so `$?` was `tail`'s exit code and all four `EXIT=0`
lines meant nothing — the exact failure mode `references/worktrees.md` documents. Nothing was accepted
on those codes: the counts were recovered from the surefire XML and the re-run used `set -o pipefail`.
The mirror-image mistake happened in the same run: a Prettier check over *every* changed file included
`.husky/pre-commit`, a shell script with no parser, and reported exit 2 on a file this unit never
touched. Both are one lesson — an exit code is evidence only when the command **and its scope** are
named.

L18 (repair, 2026-10-21, found by the independent verification and by **none** of this unit's own gates).
Three defects, the first of them a blocker:

1. **BLOCKER — the shared resolution was never invalidated.** `LocationPreference` is root-scoped, and
   `resolve()` returned early when the key was unchanged; the no-override key is the constant
   `'profile'`, and `user-profile.component.ts` never injected the service. Reproduced path: open a
   store-scoped screen with no `?storeId` (chain A settles) → `/profile` → «— None —» → Save → return
   to the screen → it still holds chain A and issues a store-scoped request for the store the user just
   cleared; `/companies/regions` preselects the old company and country. Worse than the baseline, where
   each per-component context re-read the profile on every mount. The fix is `invalidate()` — clear the
   settled key, and `resolution.reload()` **only** when a request exists — called on save success
   **before** the navigation, because the navigation is what mounts the screen that must not read the
   old chain. Clearing the key alone is the trap: it leaves `settled` false and parks every consumer in
   the fail-closed state forever, and that shape was observed failing 2/19 before the guarded reload was
   added.
2. **False green — S3 asserted behaviour the app does not have.** `application.properties:29` sets
   `spring.jackson.deserialization.fail-on-unknown-properties=true`, so a `location`-shaped body is
   **400**, not *ignored*. The pin asserted 200 because `standaloneSetup` installs no Jackson
   configuration, so the app's own config never ran. Fixed with a `JacksonJsonHttpMessageConverter`
   built from a strict `tools.jackson` mapper — `MappingJackson2HttpMessageConverter` takes the
   Jackson 2 mapper and does not serve Jackson 3 on Spring 7 — plus a second pin proving
   `updateProfile` is never called, since a 400 alone does not prove nothing was written.
3. **A third pin of the same kind, pre-existing on `origin/main`.** `shouldNotEchoTheFourTenancyAncestors`
   (from `company-scope-local-fallback` W3/`T14`) also asserted 200 for a body carrying the four
   ancestors. Under the app's real configuration that is 400, which means **that record's `G13` — "an
   old client that still sends the four is silently ignored" — is factually wrong**, and such a client
   is rejected rather than accepted-and-dropped. Corrected here because the strict converter made it
   fail; the correction belongs in that record as well and is named so it is not lost.

What this costs to admit: the unit's own gates — 938 API suites, 185 Angular files, four coverage
thresholds, lint, build — were **all green while the blocker was live**, because not one of them asked
whether the write ever reaches a reader. The verification asked, and it is the reason the unit is not
being shipped as it was.

G17 (declared, found by the same verification). The profile screen's free mode still calls
`getStoreById(storeId)` (`user-profile.component.ts:278-291`) to preselect its cascade although
`location` now carries that chain. It is a second read per screen that S1's own rationale argues
against, it is outside S1–S11, and it is the natural next unit rather than a repair.
