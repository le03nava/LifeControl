# The location preference preselects the screens' selectors, derived from the store

**Status**: implemented — `GET /api/profile` carries a `location` chain derived from the resolved store
preference, `PUT /api/profile` gains `clearStorePreference` (the stored column could not be cleared at
all before this unit), and one shared Angular `LocationPreference` replaces the two per-feature copies
of the resolver. Nothing is deferred.
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

S3. `location` is read-only. No field of `ProfileUpdateRequest` feeds it, so a client that posts a
`location`-shaped object writes nothing (it is ignored as an unknown property, `G13` of
`company-scope-local-fallback`).

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
delegate.

S7. `/companies/regions` preselects **Company** and **Country** from `location` when the route carries
no explicit ids, and keeps the route's ids when it does.

S8. When `location` is `null` the selectors start empty and the store-scoped screens keep failing
closed: neither guesses a location nor issues a store-scoped request.

S9. Navigating a screen never writes the preference. The only writer is the subject, from `/profile`.

S10. The profile edit screen's `«— None —»` clears the preference through `clearStorePreference`, and an
untouched store control still omits `companyStoreId` from the payload (`T16` of
`company-scope-local-fallback`).

S11. The profile view mode's Location row shows the store's own name in the free mode (today it prints
the literal `Store assigned`), and keeps the assigned store's derived chain in the constrained mode.

## Tasks

| ID | Spec | Route | Commit |
| --- | --- | --- | --- |
| T1 | S1, S2, S3 | backend: `LocationChain` in `ProfileResponse`, `resolveLocation` best-effort in `ProfileService`; RED specs first | |
| T2 | S4, S5 | backend: `clearStorePreference` in `ProfileUpdateRequest`, refusal ordered before Keycloak | |
| T3 | S6 | Angular: shared `LocationPreference`; the two copies delegate to it | |
| T4 | S7 | Angular: regions preselection through `regions-page` → `regions-form`'s existing inputs | |
| T5 | S8 | fail-closed pins on the store-scoped screens | |
| T6 | S9, S10, S11 | `/profile` as the only writer; `«— None —»`; view-mode label | |
| T7 | S1–S11 | gates: focused suites, full suites, lint, build, differential | |

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
