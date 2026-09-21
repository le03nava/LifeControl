# ODD feature: product-variant-admin-ui

**Repository**: LifeControl — component `life-control-app-angular/` (+ backend integration tests in `life-control-api/`)
**Branch**: `feat/product-variant-admin-ui`
**Worktree**: `~/workspace/LifeControl-worktrees/feat-product-variant-admin-ui`
**Base**: `main` @ `7958dd0` (PR #136 merge)
**Status**: in progress — S1 pending
**Created**: 2026-09-21
**Risk**: **high** — route guard and role-set change (`assets/risk-classification-matrix.md`: "Auth, permissions, role, or guard change")

## Objective

Give the variant model that S2 of `product-variant-identity-split` shipped a UI: manage **global
variant definitions** (per product) and the **per-store stock and prices** of each definition, so the
post-split backend can be operated from the application instead of only from the API.

## Problem

The backend was cut over to "global definition + per-store stock row" and merged. The frontend was
never given the matching screens. Verified on `main` @ `7958dd0`:

- `life-control-app-angular/src/features/products/` has **no variant page, component, service or
  spec**: only `data/product.service.ts` with a single `getProductVariants(productId, storeId, page,
  size)` (`:69-81`). Zero call sites for `POST`/`PUT`/`DELETE` variant, `PATCH .../enable`, or
  `PUT /api/variants/{variantId}/stores/{storeId}`.
- `models/product-variant.models.ts` (`:19-36`) has no create/update request type and no store-stock
  model. The two write endpoints have no Angular client by explicit decision of the S3 slice
  ("shipping unused client methods is dead code").
- `core/security/roles.ts` has **no `LC_SALES` constant** even though `features/sales/sales.routes.ts:20`
  gates on the literal `'lc-sales'`.
- `features/products/products.routes.ts` gates the **whole module** with `data: { roles: ['lc-admin'] }`
  on the parent route, so a sales principal cannot reach any child under `/products`.

The backend contract the UI must consume (all `hasAnyRole('lc-admin','lc-sales')`):

| Method | Path | Request | Response |
|---|---|---|---|
| GET | `/api/products/{productId}/variants?storeId=&page=&size=` | — | `Page<ProductVariantResponse>` |
| POST | `/api/products/{productId}/variants` | `{ barCode, variantName }` | `ProductVariantResponse` (201) |
| GET | `/api/products/{productId}/variants/{variantId}` | — | `ProductVariantResponse` |
| PUT | `/api/products/{productId}/variants/{variantId}` | `{ barCode, variantName }` | `ProductVariantResponse` |
| DELETE | `/api/products/{productId}/variants/{variantId}` | — | 204 |
| PATCH | `/api/products/{productId}/variants/{variantId}/enable` | — | `ProductVariantResponse` |
| PUT | `/api/variants/{variantId}/stores/{storeId}` | `{ listPrice?, costPrice?, stock? }` | `ProductVariantStoreStockResponse` |

Error mapping the UI must render: 404 `ResourceNotFoundException`, 409 `ConflictException`
(duplicate `barCode` or duplicate `(productId, variantName)`) and 409
`DataIntegrityViolationException`, 403 `AccessDeniedException`, 400 bean validation.

## Decisions (user-confirmed 2026-09-21)

| # | Decision | Value |
|---|---|---|
| D1 | Roles that reach the new screens | **`lc-admin` + `lc-sales`**, mirroring the backend's `@PreAuthorize`. Requires adding `LC_SALES` to `core/security/roles.ts` and broadening the `products` parent gate. |
| D2 | Delivery structure | **One feature, two slices.** S1 variant definitions, S2 per-store stock and prices. Minimum two work-unit commits; one PR or two sequential PRs decided at delivery. |
| D3 | Keycloak claim mappers | **Out of scope**, declared follow-up. This feature assumes the claim chain exists, exactly as `store-claim-hardening` did. |
| D4 | How the parent gate is broadened | **Follow the `companies.routes.ts` mold**: the parent gate becomes the union any child needs (`lc-admin` + `lc-sales`) and **every pre-existing child route is re-gated to `lc-admin`**, so the products ABM does not open to sales. A route-config spec must prove it. |

### Design facts established by read-only exploration (not open decisions)

- **The store is chosen in the UI, not derived from the token.** The frontend never reads
  `company_store_id` from `keycloak.tokenParsed`; it resolves the store through the
  company → country → region → zone → store cascade. `GET /api/profile` already returns the whole
  chain as user preferences (`features/user/profile/data/profile.models.ts:10-15`), and
  `CompanyCascadeService` already uses it to preselect.
- **`CompanyCascadeService` is not directly promotable.** It is 463 lines under
  `features/purchases/purchase-orders/data/` and imports `PurchaseOrderHeaderControl` and
  `PurchaseOrder` from the purchase-order models, so it is coupled to that feature. Reuse requires
  either extracting the generic cascade into `shared/data/` (blast radius: `purchases` + its specs) or
  building a narrower store selector on the per-level `companies/*` services. **Resolve at S2 start
  with evidence**, then record the choice here.
- `lc-admin` is exempt from the store guard inside `CurrentUserContext`, and an admin profile may carry
  no `companyStoreId`. The stock editor therefore **cannot** rely on the profile default alone: admin
  needs an explicit store selector.

## Target

```
/products                              (parent gate: lc-admin + lc-sales)
  ├── '' , list, create, edit/:id, ... (re-gated: lc-admin)  ← unchanged behavior
  ├── edit/:id/suppliers/**            (re-gated: lc-admin)  ← unchanged behavior
  └── edit/:productId/variants         (lc-admin + lc-sales)  ← new: definitions list
        ├── create                     (lc-admin + lc-sales)  ← new
        └── edit/:variantId            (lc-admin + lc-sales)  ← new: definition + per-store stock/prices
```

New files live under the existing products feature layout, mirroring `suppliers/` and the
`companies/stores/` mold: `pages/`, `components/`, `data/`, `models/`, `ui/`.

## Scope

### In scope

- `core/security/roles.ts`: `LC_SALES` plus the two role allow-lists the routes need.
- Variant models: definition request/response, store-stock request/response, and the nullability
  correction the backend actually returns.
- `ProductService` (or a variant service) clients for the seven endpoints above.
- Route restructure per D4 plus its guard spec.
- Variant definitions UI: list, create, edit, enable/re-enable, delete confirmation.
- Per-store stock and prices UI: pick a store, read the store row, upsert `listPrice`, `costPrice`,
  `stock`.
- Reachability: the menu/dashboard entry that makes the screens discoverable.
- Backend integration tests closing `JD-B-005` and `JD-B-006`.
- Unit specs for every new model, service, page and component; the frontend gates.

### Out of scope

- The Keycloak protocol mappers for the claim chain (D3) — declared follow-up, blocking for any real
  environment.
- `JD-A-003` (duplicate gates count soft-deleted rows, so re-creating a barcode returns a 409 with no
  identifier to act on) — it changes the 409 contract and the UI does not need it. Declared follow-up.
- `JD-A-002` (409 instead of the documented 404 for an unknown `productVariantId` on the inline
  create/update paths) — cosmetic, pre-existing.
- Store-claim checks on the **older** variant endpoints (`GET`/`POST`/`PUT`/`DELETE` under
  `/api/products/{productId}/variants`), which `store-claim-hardening` deliberately left out because
  adding them changes behavior for existing readers. Declared debt, carried forward.
- W3 (`SALE` movements, `product_variant_locations` reconciliation), structured size/color fields,
  removing `products.sku` — all still out of scope from the parent feature.
- Any backend behavior change: this feature adds backend **tests**, not backend semantics.

## Plan

| # | Slice | Contents | Risk | Status |
|---|---|---|---|---|
| S1 | Variant definitions UI | Role constants; variant models; endpoint clients; D4 route restructure + guard spec; definitions list, create, edit, enable, delete; reachability; frontend gate. | High (guard/role) | pending |
| S2 | Per-store stock and prices | Store selection (extraction or narrower selector, decided with evidence); store-row read and upsert UI; integration into the definitions screens; backend integration tests `JD-B-005`/`JD-B-006`; both gates. | Medium | pending |

## Tasks

### S1 — Variant definitions UI

- [ ] T1 — `core/security/roles.ts`: add `LC_SALES`, register it in `CLIENT_ROLES`, and export the two
      allow-lists (`PRODUCT_ADMIN_ROLES`, `VARIANT_ROLES`). Spec covers the new constants.
- [ ] T2 — Models: `ProductVariantRequest`, `ProductVariantStoreStockRequest`,
      `ProductVariantStoreStock`, and the `ProductVariant` nullability correction. Spec covers them.
- [ ] T3 — Data layer: clients for the seven endpoints (`getVariantById`, `createVariant`,
      `updateVariant`, `deleteVariant`, `enableVariant`, `upsertStoreStock`, plus the existing list).
      Spec with `HttpTestingController` asserts URL, method, body and error mapping per call.
- [ ] T4 — Route restructure per D4 + `products.routes.spec.ts` asserting every pre-existing child is
      still `['lc-admin']` and only the variant children carry `['lc-admin','lc-sales']`.
- [ ] T5 — Definitions list page: paginated list, enable/disable action, delete confirmation dialog,
      empty and error states. Spec.
- [ ] T6 — Definition create/edit page + form component: reactive form with a typed control map,
      per-field server errors, `ApiError` banner, cancel/navigate. Specs.
- [ ] T7 — Reachability: menu entry and/or dashboard card, gated by role. Spec.
- [ ] T8 — S1 gate: `npm run lint`, `npm run build`, `npm run test:coverage:check`. Work-unit commit.

### S2 — Per-store stock and prices

- [ ] T9 — Resolve the store-selection design with evidence (extract a generic cascade into
      `shared/data/` vs a narrower store selector) and record the choice and blast radius here.
- [ ] T10 — Stock and prices editor: store selection, read of the current store row, upsert of
      `listPrice`/`costPrice`/`stock` with the backend's "null leaves the value" semantics. Spec.
- [ ] T11 — Integrate the editor into the definitions screens so one definition shows its per-store
      rows. Spec.
- [ ] T12 — Backend integration tests (Testcontainers Postgres): `JD-B-005` exercises the `ON CONFLICT`
      insert path end to end; `JD-B-006` proves one definition serves two stores with distinct stock.
- [ ] T13 — S2 gate: frontend gates plus `./gradlew test --no-daemon` for the touched backend tests.
      Work-unit commit.

## Review workload

Declared forecast, to be corrected with real numbers at each slice close:

- S1: role constants, models, service clients, the route restructure, three pages/components, a dialog
  and their specs. **~700–1000 changed lines including specs.**
- S2: store selection (extraction if chosen), the editor component, integration into the list, and two
  backend integration test classes. **~500–800 changed lines including specs.**

Two slices, two review units, minimum two work-unit commits. If S2's store selection is resolved by
extracting `CompanyCascadeService` into `shared/data/`, S2 grows and its blast radius on `purchases`
must be declared in the PR body rather than absorbed silently.

## Risks

1. **Opening the products ABM to `lc-sales`.** D4 broadens a parent gate; a missed child re-gate hands
   sales the product CRUD screens. Mitigated by T4's route-config spec, which fails when any
   pre-existing child loses its admin-only gate.
2. **Two role sources drifting.** The backend allows `lc-sales` on every variant verb; if the UI gates
   create/delete more narrowly, the mismatch must be deliberate and documented, not accidental.
3. **Nullability lies in the model.** `companyStoreId`, `listPrice`, `costPrice` and `stock` are `null`
   when no `?storeId=` is sent, while `ProductVariant` declares them non-nullable today. S3 fixed the
   consumers; T2 must fix the type itself.
4. **The 409 has no identifier.** Duplicate `barCode` or duplicate `(productId, variantName)` both
   return a generic conflict, and `JD-A-003` means a soft-deleted row can trigger it. The form must
   tell the user which field collided, or the message is unusable.
5. **The claim chain is unprovisioned.** Without the Keycloak mappers (D3), store-scoped reads return
   an empty page and writes return 403 in any real environment. This is a declared precondition, not a
   defect this feature introduces.
6. **Admin has no store preference.** The stock editor needs an explicit selector or it is unusable for
   the one role that is exempt from the guard.

## Evidence log

| Date | Slice | Commit | Gate result |
|---|---|---|---|
| 2026-09-21 | exploration | — | Read-only mapping of the backend contract, the `companies/stores` mold, the current variant consumers, the gates and the JD follow-ups. No files written. |

## Declared follow-ups

| Item | Why deferred |
|---|---|
| Keycloak protocol mappers for `company_id` → `company_store_id` (D3) | Non-code precondition, separate component (`docker/`), blocking for any real environment; already a precondition of `lc-receiving` and `lc-sales`. |
| `JD-A-003` — duplicate gates count soft-deleted rows | Changes the 409 contract; the UI does not require it. |
| `JD-A-002` — 409 instead of the documented 404 on inline paths | Cosmetic, pre-existing. |
| Store-claim checks on the older variant endpoints | Contract change for existing readers, deliberately left by `store-claim-hardening`. |
