# store-claim-hardening

**Status**: merged — PR #136 (`fix/store-claim-hardening` @ `7958dd052`), 2026-09-21. No work left.
**Work unit**: closes the missing store-claim check on three store-scoped paths.
**Branch**: `fix/store-claim-hardening` · **Base**: `main` @ `e645e9f` · **Worktree**: `~/workspace/LifeControl-worktrees/fix-store-claim-hardening`
**Related**: `odd/tasks/product-variant-admin-ui.md` (the variant management UI that follows), `odd/tasks/product-variant-identity-split.md` (the feature whose review surfaced the sibling finding JD-B-003)

## Problem

`CurrentUserContext.verifyCompanyStoreAccess` is the repository's store-scope guard: given the
company → country → region → zone → store chain, it fails closed with 403 unless the caller holds a
grant covering that chain (`lc-admin` is exempt). It is applied by the store tree services, the
inventory services, the goods-receipt service, and — since the JD-B-003 fix — by
`ProductVariantService.upsertStoreStock`.

Four store-scoped paths accept a caller-supplied store id and never call it:

| Path | Evidence | Exposure |
|---|---|---|
| `GET /api/product-variants/search` | `ProductVariantService.searchVariants` delegates to `searchByQuery(trimmed, storeId, pageable)` with no guard | Returns another company's store `stock`, `listPrice`, `costPrice`, `barCode`, variant and product names for any `storeId`. Requires only `lc-admin`/`lc-sales`. |
| `POST`/`PUT` sales orders | `SalesOrderService.validateCompanyStoreExists` is `existsById` only | An `lc-sales` principal can create an order against another company's store, which is the precondition that makes the search leak reachable through the product UI. |
| `POST`/`PUT` purchase orders | `PurchaseOrderService.validateCompanyStoreExists` is `findById(...).filter(enabled)` only | Same, for the purchasing side. |
| `GET /api/products/{productId}/variants?storeId=` | `ProductVariantService.listVariants` store-scoped branch delegates to `findStoreScopedByProductIdAndStoreId` with no guard | The same data class, on the endpoint the purchase-order variant picker calls (`product-variant-picker.ts:122`). **Found by the independent security review after the first three were fixed**, not in the original plan. |

The service's own javadoc on `verifyStoreAccess` already states the intent: *"Without it, any
principal holding `lc-sales` could set the sellable stock and the list and cost prices of a store of
another company."* The read path returns exactly that data.

## Decisions (maintainer)

1. **Scope: the root cause, not only the symptom.** The search endpoint gets the guard *and* the two
   order write paths get it. Chosen over "search only" (leaves the precondition) and over "search
   fail-soft, return an empty page" (no leak, but inconsistent with every other store-scoped
   endpoint and it hides the abuse instead of surfacing it).
2. **Failure mode: 403**, matching `upsertStoreStock` and the rest of the API. Not an empty page.
3. **This is its own PR**, separate from the variant management UI.
4. **`lc-sales` is registered at `ScopeLevel.STORE`** (see the next section). This was discovered
   during implementation, not anticipated in the plan.

## The `lc-sales` scope gap (found during implementation)

The guard does not read claims until the caller has a scope in range: `verifyCompanyStoreAccess` →
`verifyBroadestGranted(STORE, …)` → `broadestGrantedScope(STORE)`, which returns `null` when none of
the caller's roles is registered at that level — and then throws *"Insufficient role"* **before any
claim is inspected**. `Roles.SALES` was registered at no `ScopeLevel`, so an `lc-sales`-only
principal could never pass a store guard, claim path or not. `ScopeLevel`'s own javadoc documents the
trap when justifying `Roles.RECEIVING`: *"a role absent from `roleNames()` would leave such a caller
with no scope in range and no way to pass the store check at all."*

That made the guard a functional regression for the persona the frontend gates the sales module on
(`life-control-app-angular/src/features/sales/sales.routes.ts:20` → `['lc-admin','lc-sales']`): the
variant picker on the sales screen calls `GET /api/product-variants/search`, so the picker would have
started answering 403. Twelve integration tests in `SalesOrderIntegrationTest` failed for the same
reason — they authenticate as `lc-sales` with no store claim, which is the honest shape of the gap.

**Resolution**: `Roles.SALES` is appended to `ScopeLevel.STORE`, mirroring the documented
`RECEIVING` precedent. An `lc-sales` principal carrying the claim chain now passes; one without the
claim path is denied at the claim check, which is the intended fail-closed behavior. This is safe by
construction: `verifyCompanyStoreAccess` is an additional guard, never the access gate — every
endpoint still requires its own `@PreAuthorize`, and every endpoint that reaches the guard and accepts
`SALES` (`GET /api/product-variants/search`, `POST`/`PUT /api/sales-orders`,
`POST`/`PUT /api/purchase-orders`, `PUT /api/variants/{variantId}/stores/{storeId}`) already allowed
`lc-sales`. No endpoint gains a role it did not declare. The integration helper was then de-muled
(it had been granting `lc-company-store` to work around exactly this gap) so the tests exercise the
real `lc-sales` persona end to end.

### Non-code precondition this does not close

The token must carry the claim path `company_id` → `company_country_id` → `company_region_id` →
`company_zone_id` → `company_store_id`, or every store-scoped endpoint answers 403 (writes) or an
empty page (reads). This is already recorded as a non-code precondition for `lc-receiving` in
`odd/tasks/purchase-order-goods-receipt.md` (~line 671). Verified: `docker/scripts/keycloak-setup.sh`
creates the clients and the `lc-*` roles but **no protocol mapper** for those claims, so the claim
path is a manual Keycloak configuration today. It is now a precondition for the sales persona too.

## Declared behavior change

Creating or updating an order against a store the caller has no grant for now returns 403. Before,
it silently succeeded. This is intentional: the flow was illegitimate. Two consequences to keep
visible:

- The variant picker in the sales UI feeds `storeId` from the user's profile or the loaded order
  (`sales-order-edit.html:88`, `sales-order-edit.ts:212-221`), so a legitimate user queries their own
  store and is unaffected. A user who somehow holds an order in a foreign store will now see the
  picker fail with 403 instead of silently reading that store's prices.
- Any client that relied on cross-store order creation breaks. No such client is known in this repo.
- The guard only inspects claims once the caller has a scope in range; see the `lc-sales` section
  above for the persona that was invisible to it and the token precondition this exposes.

## Non-goals

- **No refactor of the guard itself.** The company → country → region → zone walk is already
  duplicated in `GoodsReceiptService` (twice) and `ProductVariantService`; the other store services
  take the ids from the URL path. This change mirrors the established private-helper pattern instead
  of introducing a shared overload on `CurrentUserContext` (which would make the security utility
  depend on the store model). The duplication is a deliberate, declared trade-off here.

- The **global** branch of `GET /api/products/{productId}/variants` (no `storeId`) stays without a
  store claim, because there is no store to check: it returns the product's global definitions with
  the store-scoped fields `null`. The **store-scoped** branch (`?storeId=`) is a different thing and
  is now guarded (T11). The first draft of this document declared the whole endpoint a non-goal under
  the rationale "a variant definition is global by design" — that was wrong for the `storeId` case,
  and the independent review caught it.
- No frontend change.
- No new scope/claim model: reuse `verifyCompanyStoreAccess` exactly as the other services do.

## Tasks

- [x] **T1 — Guard `searchVariants`.** `ProductVariantService.searchVariants` resolves the store from
      the supplied `storeId` and calls the existing private `verifyStoreAccess(store)` before querying.
      A blank/absent query keeps returning `Page.empty` (no store read, no guard needed). Update the
      method javadoc to document the 403. An unknown `storeId` mirrors `upsertStoreStock` exactly:
      `CompanyStoreNotFoundException` (404), not a silent empty page. The endpoint therefore matches
      its sibling write, which already distinguishes unknown (404) from unauthorized (403).
      *Evidence*: `life-control-api/src/main/java/com/lifecontrol/api/product/service/ProductVariantService.java`
- [x] **T2 — Tests for T1.** Unit: guard invoked with the chain derived from the store; 403 when the
      caller holds no grant; blank query short-circuits. Controller: `GET /api/product-variants/search`
      maps `AccessDeniedException` to 403.
      *Evidence*: `ProductVariantServiceTest.java`, `ProductVariantControllerTest.java`
- [x] **T3 — Guard the sales order store.** `SalesOrderService` gains `CurrentUserContext` by
      constructor injection; `validateCompanyStoreExists` resolves the store (it currently only asserts
      existence) and applies the same derived-chain check as `upsertStoreStock`. All call sites
      (create and update) are covered.
      *Evidence*: `life-control-api/src/main/java/com/lifecontrol/api/salesorder/service/SalesOrderService.java`
- [x] **T4 — Guard the purchase order store.** Same treatment in `PurchaseOrderService`, keeping its
      existing `enabled` filter and its `CompanyStoreNotFoundException` behavior for unknown stores.
      *Evidence*: `life-control-api/src/main/java/com/lifecontrol/api/purchaseorder/service/PurchaseOrderService.java`
- [x] **T5 — Tests for T3/T4.** Every test that constructs either service gains the new mock (a mock's
      `verifyCompanyStoreAccess` is a no-op, so existing assertions keep their meaning). New coverage:
      403 when the caller holds no grant, and the derived chain for the store. Integration tests that
      authenticate as `lc-admin` are unaffected by design (`isAdmin()` short-circuits) — verify, do not
      assume.
      *Evidence*: `SalesOrderServiceTest.java`, `SalesOrderControllerTest.java`, `PurchaseOrderServiceTest.java`, `PurchaseOrderControllerTest.java`, plus any test that builds these services directly
- [x] **T6 — Gates.** `./gradlew spotlessApply spotlessCheck spotbugsMain test --no-daemon` with zero
      failures; report the class/test counts.
- [x] **T8 — Register `Roles.SALES` in `ScopeLevel.STORE`.** Append the role to the `STORE` constant
      and extend the javadoc paragraph that justifies `RECEIVING` so it covers `SALES` with the same
      rationale.
      *Evidence*: `life-control-api/src/main/java/com/lifecontrol/api/common/security/ScopeLevel.java`
- [x] **T9 — Tests for T8.** An `lc-sales`-only principal carrying the full claim chain passes
      `verifyCompanyStoreAccess`; the same principal without the claim path is denied **at the claim
      check** (not with "Insufficient role"). The pre-existing exact-role-list registry assertion was
      updated to include `Roles.SALES`; no test anywhere asserted an `lc-sales` denial at store scope.
      *Evidence*: `CurrentUserContextTest.java`
- [x] **T10 — De-mule the integration helper.** `SalesOrderIntegrationTest.scopedSalesJwt` carries
      `lc-sales` only plus the claim chain (the `lc-company-store` workaround is gone), so the 27 call
      sites exercise the real persona. Every existing assertion kept, including the reassignment test,
      which now uses a two-store claim so the 409 rule stays the thing under test.
      *Evidence*: `SalesOrderIntegrationTest.java`
- [x] **T11 — Guard the store-scoped variant list.** `ProductVariantService.listVariants` guards the
      `companyStoreId != null` branch (resolve the store → 404 on unknown → `verifyStoreAccess` before
      the query). The `companyStoreId == null` branch is behavior-identical: no store read, no guard.
      No other backend caller of the store-scoped overload exists (verified by grep).
      *Evidence*: `ProductVariantService.java`, `ProductVariantController.java:146-155`
- [x] **T12 — Tests for T11.** Unit: derived chain verified, 403 with no grant, unknown store → 404,
      and the global branch proves it neither reads the store nor invokes the guard. Controller: the
      `?storeId=` call maps `AccessDeniedException` to 403; the global call is unchanged.
      *Evidence*: `ProductVariantServiceTest.java`, `ProductVariantControllerTest.java`
- [x] **T13 — Fix the stale role list.** `CurrentUserContext`'s store-scope javadoc restated the
      store-scoped roles as "today `lc-company-store`, `lc-company-store-read` and `lc-receiving`" —
      wrong the moment `Roles.SALES` was registered. It now points at
      `ScopeLevel.STORE.roleNames()` as the single source of truth instead of restating a list that
      can drift again. Javadoc only; no executable code changed in that file.
      *Evidence*: `CurrentUserContext.java:265-273`
- [x] **T7 — Work-unit commit and PR**: landed as PR #136 (`7958dd052`), 2026-09-21.

## Constraints

- No Lombok, constructor injection, records for DTOs, `@PreAuthorize` via `Roles.*` constants
  (`life-control-api/AGENTS.md`).
- Spotless (`palantirJavaFormat`) + SpotBugs (MEDIUM, `ignoreFailures=false`) must pass.
- Flyway untouched: this change needs no migration.
- Do not widen the blast radius beyond the three named paths.

## Independent security review

The `project-conventions` skill makes a security review mandatory for a high-risk auth/role/guard
change. The native `review-risk` lens agent is **not dispatchable with RDD off** (it requires a
controller-owned candidate lineage binding), so the review ran as a read-only `gentle-ai-verify`
subagent over `git diff main`. Its findings and dispositions:

| Finding | Severity | Disposition |
|---|---|---|
| The same cross-store price/stock leak remained open on `GET /api/products/{productId}/variants?storeId=` (`ProductVariantService.java:83`), and this document's non-goal rationale for it was factually wrong | WARNING (escalated) | **Fixed** in T11/T12 after the maintainer authorized widening the work unit |
| The claim path (`company_id` → `company_store_id`) is provisioned nowhere in the repository, so an `lc-sales` principal without a manual Keycloak mapper is denied on all four guarded endpoints | WARNING | **Declared**, not fixed: it is a pre-existing non-code precondition that already applied to `lc-receiving`. Recorded below and as a follow-up |
| Registering `Roles.SALES` at `STORE` **does** change effective behavior on `PUT /api/variants/{variantId}/stores/{storeId}`: an `lc-sales`-only principal was always 403 there (no scope in range) and is now conditionally allowed with a valid claim chain. Intended, but "no endpoint gains a role it did not declare" is true only at the `@PreAuthorize` level | SUGGESTION | **Declared** in this document; belongs in the PR description |
| `isAuthenticated()`-only purchase-order reads (`PurchaseOrderController.java:50, 61, 143`) expose every store's orders to any authenticated principal | SUGGESTION | **Out of scope**, pre-existing, already carried as debt in `purchase-order-goods-receipt.md`. Follow-up |
| 404 (unknown store) vs 403 (no grant) is a store-existence oracle on the search endpoint | SUGGESTION | **Accepted**: it mirrors the pre-existing sibling `upsertStoreStock` exactly, so it is the API's established convention rather than something this change introduces |
| `CurrentUserContext` logs the derived company/country/region/zone ids on denial | SUGGESTION | **Accepted** with a note: the HTTP body is generic (`GlobalExceptionHandler`), only the server log carries the ids. Follow-up if log hygiene is tightened repo-wide |
| The denial test's `salesOrderRepository.findAll()).isEmpty()` is trivially true after `@BeforeEach` deletes everything | SUGGESTION | **Accepted**: the test is non-vacuous through its stock assertion (the seeded 100.00 must be unchanged), which fails if the guard is removed |

Verified as sound by the review: the guard runs before every read/write on all four paths; the
`lc-admin` exemption is unchanged; broader country/region/zone grants still pass; no endpoint that
invokes `verifyCompanyStoreAccess` is `isAuthenticated()`-only, and none of the store-tree, inventory
or goods-receipt role lists contains `SALES`, so `lc-sales` cannot newly reach them; the 403 body leaks
no derived ids.

## Task log

| Date | Slice | Gate result |
|---|---|---|
| 2026-09-21 | T1–T5 (guards + unit/controller tests) | Focused run green (207 tests). Full gate red on 12 `SalesOrderIntegrationTest` tests: 403 instead of 201/409 — the guard denying an `lc-sales` JWT with no store claim. Stopped at the surface boundary instead of editing outside it. |
| 2026-09-21 | T1–T5 + integration fixture | 27 call sites migrated to a claims-bearing `lc-sales` JWT; new end-to-end denial test. `./gradlew spotlessApply spotlessCheck spotbugsMain test --no-daemon` → **BUILD SUCCESSFUL**, 614 classes / 2020 tests / 0 failures / 0 errors. |
| 2026-09-21 | T8–T10 (the `lc-sales` gap) | `Roles.SALES` registered at `ScopeLevel.STORE`; 2 new `CurrentUserContextTest` cases; helper de-muled. Full gate → **BUILD SUCCESSFUL**, 614 classes / 2022 tests / 0 failures / 0 errors / 0 skipped. Diff: 10 files, +501/−73. |
| 2026-09-21 | Independent security review (read-only `gentle-ai-verify`) | Found the fourth surface (`listVariants?storeId=`) and the wrong non-goal rationale; confirmed the guard ordering on the three fixed paths and that registering `SALES` widens no endpoint's declared role set. |
| 2026-09-21 | T11–T13 (fourth surface + stale javadoc) | Full gate re-run **by the parent, not the writer** (`./gradlew spotlessCheck spotbugsMain cleanTest test --no-daemon`) → **BUILD SUCCESSFUL in 1m 13s**, 614 suites / 2025 tests / 0 failures / 0 errors / 0 skipped. Diff: 11 files, +584/−88. |
