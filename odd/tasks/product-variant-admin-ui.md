# ODD feature: product-variant-admin-ui

**Repository**: LifeControl — component `life-control-app-angular/` (+ backend integration tests in `life-control-api/`)
**Branch (S1)**: `feat/variant-definitions-ui`
**Worktree (S1)**: `~/workspace/LifeControl-worktrees/feat-variant-definitions-ui` (removed after merge)
**Base (S1)**: `feat/variant-include-disabled` (the backend slice PR), itself `main` @ `7958dd0` (PR #136 merge)
**Status**: **S1 delivered** — PRs #137/#138/#139 merged into `main` and the S1 branch/worktree cleaned
up. **S2 complete (T9–T15)** on `feat/variant-store-stock-ui`: seven commits from `main` @ `2704a6e`,
both gates green (see the evidence log). The S2 store source was resolved with evidence on 2026-09-22
(see `### S2 store source`), which superseded the premise of the original T9.

**S2 is merged** as the four units the split proposed — `feat/variant-store-scope` #146 (`14e271657`),
`test/variant-store-write-it` #147 (`5c95d0d56`), `feat/variant-store-stock-editor` #148 (`0450b929e`)
and `feat/variant-store-sales-entry` #149 (`3bc5555df`), 2026-09-22 — verified at `bcb8d10` on
2026-09-24. The slice measured **3490 changed lines against a declared forecast of 500–800**; see
`## Review workload`. No work is left in this feature.
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
| D2 | Delivery structure | **One feature, two slices.** S1 variant definitions, S2 per-store stock and prices. Minimum two work-unit commits. **Delivery decided at the S1 gate: three PRs** (see `## Delivery`), superseding the earlier "one PR or two sequential PRs". |
| D3 | Keycloak claim mappers | **Out of scope**, declared follow-up. This feature assumes the claim chain exists, exactly as `store-claim-hardening` did. |
| D4 | How the parent gate is broadened | **Follow the `companies.routes.ts` mold**: the parent gate becomes the union any child needs (`lc-admin` + `lc-sales`) and **every pre-existing child route is re-gated to `lc-admin`**, so the products ABM does not open to sales. A route-config spec must prove it. |
| D5 | How `lc-sales` reaches the variant screens in S1 | **Deferred to S2.** S1 ships the screens with their `VARIANT_ROLES` gates and reaches them from the product list (admin), because variants are nested under a product and the only product-picking UI is admin-only. The sales entry point is built in S2 with the per-store stock editor, which is where sales has a real job. Declared consequence: `lc-sales` has no discoverable entry until S2. |
| D6 | The re-enable gap the review found (F2) | **Fix it properly**: add the opt-in `includeDisabled` to the variant list endpoint, mirroring `listProducts`, plus a "Mostrar deshabilitadas" toggle. |
| D7 | The pre-existing backend gap the review found (F1) | **Its own branch and PR, on `main`, deliberately not stacked on this one**: `fix/product-write-authz`. |

### Design facts established by read-only exploration (not open decisions)

- **The store is chosen in the UI, not derived from the token.** The frontend never reads
  `company_store_id` from `keycloak.tokenParsed`; it resolves the store through the
  company → country → region → zone → store cascade. `GET /api/profile` already returns the whole
  chain as user preferences (`features/user/profile/data/profile.models.ts:10-15`), and
  `CompanyCascadeService` already uses it to preselect.
- **`CompanyCascadeService` is not directly promotable.** It is 463 lines under
  `features/purchases/purchase-orders/data/`; its form binding is the spine of the class (all four
  change handlers open with `if (!this.form) return;` and write `controls.*`), it imports
  `PurchaseOrderHeaderControl` and `PurchaseOrder`, and **it has no spec of its own** — the only
  reference from a spec is a comment at `purchase-order-edit.spec.ts:796`. Extraction is therefore a
  rewrite of a live purchase-order path with no safety net. Resolved 2026-09-22, see below.

### S2 store source — DECIDED 2026-09-22 (supersedes the T9 premise)

T9 assumed the store picker has to live inside `products`, forcing a choice between extracting
`CompanyCascadeService` and duplicating the ladder. Evidence gathered at S2 start shows a third shape
that the repo already standardised, so neither was chosen.

1. **The repo already has a standard for "per-store leaf page": `store-inventory-settings`**
   (`features/companies/stores/pages/store-inventory-settings/store-inventory-settings.ts`, 260 lines
   + 106 HTML + 641 spec). Its contract, recorded in its own class JSDoc: *"the whole chain arrives
   through the five query params (`companyId`, `countryId`, `regionId`, `zoneId`, `storeId`) and the
   page never cascades. When any of the five is missing it fails closed — no request is issued and the
   operator is sent back to the store list — instead of guessing a store."* It is entered from a card
   action that preserves the chain (`stores-page.ts:322-338` → `/companies/store-inventory-settings?…`).
2. **The profile is a user-editable preference, not a token claim.** `ProfileService.getProfile()`
   (`ProfileService.java:47-70`) builds the store chain from `user_preferences` via
   `UserPreferencesRepository`, creating an empty row when none exists. An `lc-admin` who never set a
   preference gets five `null`s. The frontend mirror is `profile.models.ts:11-15`. So the profile is
   usable as a **best-effort default and a “change my store” target**, never as an authorization
   source.
3. **The store-scoped read already needs only `storeId`.** `listVariants`'s store branch derives and
   verifies the whole company → country → region → zone → store chain server-side from the single
   `storeId` query param (`ProductVariantService.java:83-96`). The frontend does not have to carry five
   levels to read or write a store row.
4. **The store-scoped read is an inner join and ignores `includeDisabled`.**
   `ProductVariantRepository.findStoreScopedByProductIdAndStoreId` (`:68-82`) starts `FROM
   ProductVariantStoreStock pvss JOIN ProductVariant pv …` and filters `pv.enabled = true`; the service
   JSDoc (`:88-90`) states the branch *"always filter `enabled = true` regardless of
   `includeDisabled`, because they feed the purchase-order variant picker and the point-of-sale
   selector"*. Two consequences: a variant with **no row for that store is absent from the
   store-scoped list**, and the `includeDisabled` toggle from S1 is inapplicable while a store is
   selected.
5. **The single-row read needs no new endpoint.** `GET /api/product-variants/search?q=&storeId=`
   matches `pv.barCode = :query` by **equality** as its first predicate
   (`ProductVariantRepository.searchByQuery`, `:100`), and `barCode` is globally unique by D2 of the
   parent feature. So searching the variant's barcode scoped to a store returns exactly 0 rows (not
   configured yet) or 1 row (the store row), with no pagination ambiguity. No backend contract change
   is required for S2.
6. **The ladder is already duplicated four times**: `StoreCascadePage` (372 lines, base of four store
   pages), `CompanyCascadeService` (463 lines, PO), and `user-profile.component.ts` (464 lines). A
   fifth copy was rejected; consolidating the existing ones is a separate refactor, not this slice.

**Decision (user-ratified 2026-09-22).** The store reaches the variant screens as a *resolved id*, not
as a picker:

- `?storeId=` on the URL wins when present; otherwise the profile's `companyStoreId`; otherwise
  `null`. No cascade is built in `products`, and `purchases` is not touched.
- When the store is `null`, the screens **fail closed**: no store-scoped request is issued, the global
  definition read keeps working, and the per-store editor is disabled behind a "change my store" call
  to action that reuses the cascade that already exists (`/user/profile`).
- The store-scoped definition **list** shows the store's `listPrice`/`costPrice`/`stock` columns, and
  the **editor is an upsert panel on the variant edit page** (read the current row, write it back).
  Reading and writing each live where they belong; the read half is nearly free because the backend
  already returns those columns.
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
| S1 | Variant definitions UI | Role constants; variant models; endpoint clients; D4 route restructure + guard spec; definitions list, create, edit, enable, delete; reachability; frontend gate. | High (guard/role) | delivered (3 PRs merged) |
| S2 | Per-store stock and prices | Store source resolved by evidence (query param -> profile -> null, no new picker); store-scoped list view; per-store editor; backend integration tests `JD-B-005`/`JD-B-006`; both gates. | Medium | complete (T9–T15) |

## Tasks

### S1 — Variant definitions UI — DONE

- [x] T1 — `core/security/roles.ts`: `LC_SALES` added, registered in `CLIENT_ROLES`, and
      `VARIANT_ROLES` exported with a JSDoc that pins its backend source. `roles.spec.ts` covers the
      primitives including `hasAnyClientRole` in an injection context. (`3f02a5e`)
- [x] T2 — Models: `ProductVariantRequest`, `ProductVariantStoreStockRequest` and
      `ProductVariantStoreStock`. **Correction to this plan**: the `ProductVariant` nullability was
      already fixed by S3 of the parent feature, so no correction was needed here. (`2d43793`)
- [x] T3 — Data layer: `ProductVariantService` owns all seven endpoints. The existing read was moved
      out of `ProductService` so no resource has two owners; the purchase-order picker was its only
      production call site. (`2d43793`)
- [x] T4 — Route restructure per D4, plus the variant children, with a derived-walk control spec.
      (`3f02a5e`, `3d6d484`, `372d773`)
- [x] T5 — Definitions list page, reversible-disable dialog, and the additive "Variantes" row action
      on the product list. (`3d6d484`)
- [x] T6 — Create/edit page and form component, with the 409 rendered as one honest form-level
      banner. (`372d773`)
- [x] T7 — Reachability for admin: the product list row action. The sales entry is D5. (`3d6d484`)
- [x] T8 — S1 gate on the committed tip: `npm run lint`, `npm run build`,
      `npm run test:coverage:check` → 120 files / 2210 tests / 0 failures.
- [x] T9 — Review follow-up: `includeDisabled` end to end (backend opt-in + the
      "Mostrar deshabilitadas" toggle) and the two inaccurate comments. (`3bc7524`, `ba73bb5`)

### S2 — Per-store stock and prices

Renumbered 2026-09-22 to make room for the resolved store source and the D5 entry point; the
*contents* of the old T10 (editor) and old T11 (integration) are preserved, the numbers moved.

- [x] T9 — Resolve the store-selection design with evidence and record the choice and blast radius.
      **DONE 2026-09-22** — see `### S2 store source`. Chosen: a resolved store id (query param →
      profile → `null`), no new picker, no cascade in `products`, no `purchases` refactor, no backend
      contract change. Blast radius: `features/products/**` only.
- [x] T10 — Store source resolver: `?storeId=` → profile `companyStoreId` → `null`, fail closed,
      one owner, with a spec that proves each branch and that an unknown/absent store issues no
      store-scoped request. (`f33be2f`)
- [x] T11 — Store-scoped definitions list: pass the resolved store to `getVariants`, render the
      `listPrice`/`costPrice`/`stock` columns only when a store is resolved, suppress the
      `includeDisabled` toggle while store-scoped (the backend ignores it on that branch), and render
      the fail-closed state with the "change my store" action. Spec. (`677a77a`)
      **Added beyond the plan**: the store scope is an operator control, not just a resolution. The
      store-scoped read cannot list a soft-deleted definition nor one with no row in that store, so
      defaulting to it with no way back would have made S1's re-enable affordance unreachable for any
      operator with a configured store.
- [x] T12 — Per-store editor panel on the variant edit page: read the current row through
      `search?q={barCode}&storeId={storeId}` (exact equality, 0 rows = not configured yet), upsert via
      the existing `PUT /api/variants/{variantId}/stores/{storeId}`, sending **only** the fields the
      operator filled so an absent field keeps the stored value, role-gated save, unsaved-changes
      aware. Spec. (`ea41fe2`) **Correction to this plan**: no role gate was added. This route admits
      exactly `VARIANT_ROLES`, which is the same pair the endpoint's `@PreAuthorize` accepts, so an
      extra gate would mirror nothing. A test asserts the absences instead: an empty field is sent as
      an absent key, never `null` and never `0`.
- [x] T13 — Backend integration tests (Testcontainers Postgres): `JD-B-005` exercises the `ON CONFLICT`
      insert path end to end; `JD-B-006` proves one definition serves two stores with distinct stock.
      (`96e03de`) Five tests; the `ON CONFLICT` claim is proven by mutation, not by the green run.
- [x] T14 — S2 gate: backend `spotlessCheck spotbugsMain cleanTest test` → 621 suites / 2054 tests / 0
      failures; frontend lint, build and `test:coverage:check` → 122 files / 2258 tests / 0 failures.
      Commits: `0ec4464`, `f33be2f`, `677a77a`, `ea41fe2`, `96e03de`.
- [x] T15 — The D5 entry point: `lc-sales` could reach the variant screens only by deep link (the
      `/products` `''` child is re-gated admin-only, so there was no discoverable path). A store-scoped
      variant search page on `GET /api/product-variants/search` — authorised for exactly
      `lc-admin` + `lc-sales`, and built for this — plus a menu entry for that role pair and a row
      action into the T12 editor. (`e66feb1`)
      **Deliberate divergence**: search runs on **submit**, not on every keystroke. The sales variant
      selector debounces because it is an autocomplete; this is a page of results whose primary flow is
      scanning or typing an identifier and pressing Enter, and skipping the debounce keeps the surface
      deterministic instead of time-dependent.
      **Corrected two comments the new child falsified**: the parent gate no longer admits sales for
      "upcoming" screens, and `edit/:id/variants` is no longer described as the only non-admin child.
      **No spec change was needed for the route control**: its derived walk classifies children by
      whether the path contains `variants`, so the new child was already covered by both the variant
      and the admin-only assertions. One explicit path-pinned assertion was added.

## Delivery (3 PRs)

Decided at the S1 gate. The plan was one PR for the whole of S1 (~3471 changed lines). It was sliced
once, honestly, and the outcome is recorded here.

| PR | Branch | Base | Changed lines | What it carries |
|---|---|---|---|---|
| A | `fix/product-write-authz` | `main` | **410** | Finding F1: the three unguarded `/api/products` writes. |
| B | `feat/variant-include-disabled` | `main` | **219** | The backend opt-in `includeDisabled`, alone. |
| C | `feat/variant-definitions-ui` | `feat/variant-include-disabled` | **2976** + the ODD record | This branch: the Angular screens, plus the record. |

**Why A is not stacked on C.** It was, and that was backwards: it queued a CRITICAL security fix
behind a 3471-line review. A security fix wants the shortest path to `main`, and its review is the
cheapest of the three.

**Why B is its own PR.** It is the only part of the feature with an invariant worth reviewing in
isolation: the `includeDisabled` opt-in must not weaken the store-scoped and `search` reads, which
keep filtering `enabled = true` unconditionally because they feed the purchase-order picker and the
point of sale. Separating it keeps that invariant out of 3000 lines of Angular.

**What each PR measures, and why one number is left open.** B is 219 changed lines across 6
backend files. C is 2976 changed lines of Angular surface plus the ODD record; at the moment of the
split that record was 276 lines, so the original single-branch artifact measured
219 + 2976 + 276 = **3471**, and that decomposition is fixed history.

C's *total* is deliberately not restated here. Writing this record is itself part of C's diff, so
any total committed into the record is wrong the moment it is committed, and every attempt to
correct it moves the number again. Read C's total from the PR; read the Angular surface here
(2976), which does not move.

**Split verification.** Branch C was rebuilt from the original single branch by replaying its seven
web commits onto B's tip. The resulting tree is **identical** to the original
(`fab4a2dd8b3e0b91872a29fea1dbcfbccdf0b593` on both), so the restructure changed the delivery shape
and not one byte of the artifact.

**`size:exception` required for C.** One honest slicing pass cannot bring C under the 400-line
review budget: its specs alone are ~1330 lines (`product-variant-list.spec.ts` 393,
`product-variant.service.spec.ts` 330, `product-variant-edit.spec.ts` 282,
`product-variant-form.spec.ts` 153, `products.routes.spec.ts` 80, `roles.spec.ts` 90). Compressing
tests or dropping documentation to fit the number would trade review honesty for a metric. Repo
precedent: PR #134 merged at 3307 changed lines.

**A lands at 410**, i.e. 10 over budget: 3 lines are the fix, 303 the security test that proves it,
2 the endpoint-map row, and the rest the record. Separating the evidence from the fix to shave 10
lines would be worse than the overage.

**Chain hygiene.** Retarget C's base **before** merging B. Deleting the base branch of an open PR
closes it, and it can be neither reopened nor retargeted. A is independent of both and may land in
any order; if it lands before B, B needs a trivial rebase (disjoint hunks in `ProductController`).

## Review workload

### S1 — delivered

Role constants, models, service clients, the route restructure, three pages/components, a dialog and
their specs. **Declared ~700–1000 changed lines including specs.**

### S2 — measured, and the forecast was wrong

**Declared: ~500–800 changed lines including specs. Actual: 2514** (`git diff --shortstat main..HEAD`
→ 19 files, 2395 insertions, 119 deletions), i.e. **~3.1x the forecast and ~6x the 400-line review
budget**. Split by what the reviewer is actually reading:

| Bucket | Changed lines |
|---|---|
| Frontend source (the feature) | 1034 |
| Frontend specs | 991 |
| Backend integration tests | 377 |
| The ODD record | 112 |
| **Total** | **2514** |

**Where the forecast went wrong.** It assumed the editor was one component. It is a component plus the
read path: there is no `GET` for a single store row, so the editor also needed a store-scoped search
client, a search projection model, and the service method and specs around them. The specs are 40% of
the slice, and the repo's own precedent is that compressing tests or dropping documentation to fit a
number trades review honesty for a metric.

**Slicing cannot fix it.** The three natural work units measure 1048 (T9+T10+T11), 1097 (T12) and 377
(T13 + the record). Only the backend tests fit under 400 without cutting evidence, and the backend
tests are the one part that is *cheap* to review anyway: five tests, one file, no production change.

### S2 delivery units — decided 2026-09-22 (user-ratified)

**Four units, one concern each.** T15 was left out of the original count; with it the branch measures
**3490 changed lines**, 27 files.

| Unit | Commits | Lines | Depends on | What it carries |
|---|---|---|---|---|
| 1 | `0ec4464`, `f33be2f`, `677a77a` | **1048** | `main` | The record + the resolved store source + the store-scoped **read** lens. No writes. |
| 2 | `ea41fe2` | **1097** | unit 1 | The per-store **write** editor on the variant edit page. |
| 3 | `96e03de` | **377** | `main` | The backend integration tests closing `JD-B-005`/`JD-B-006`. |
| 4 | `e66feb1` | **916** | units 1 and 2 | The discoverable `lc-sales` entry point, whose row action lands on unit 2's editor. |

Unit 3 is deliberately independent of the frontend: it closes two findings that predate this slice, and
the same reasoning that gave the security fix its own branch applies — a closed finding wants the
shortest path to `main`, and its review is the cheapest of the four.

Unit 1 is the only unit that is both a prerequisite and small enough to review first, so units 2 and 4
stack on it. Order: **3 (independent) → 1 → 2 → 4**.

The record is one document that every unit touches, so the record commits travel with unit 1 and the
later units append their own evidence rows. That is the S1 precedent, where the record shipped inside
the last PR of the chain; here it moves to the first because unit 1 carries the decisions the rest of
the slice is judged against.

When these become PRs, each branch is rebuilt by replaying its commits and verified by tree identity
against the linear branch, the same technique the S1 delivery used.

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
| 2026-09-21 | data layer | `2d43793` | Parent-run gate: `npm run lint` clean; `npm run test:coverage:check` → 92.47/74.87/88.18/92.47 (baseline 92.41/74.64/88.08/92.41). |
| 2026-09-21 | roles + route gates | `3f02a5e` | Parent-run gate → 92.74/74.88/87.41/92.74, all within thresholds. |
| 2026-09-21 | definitions list | `3d6d484` | Parent-run gate → 92.82/74.95/87.52/92.82. |
| 2026-09-21 | create/edit screens | `372d773` | Parent-run gate → 92.87/75.10/87.55/92.87. |
| 2026-09-21 | **S1 gate (T8)** | tip of S1 | Parent, on the committed content: `npm run lint` clean; `npm run build` complete with no warnings; `npm run test:coverage:check` → **120 files / 2210 tests / 0 failures**. |
| 2026-09-21 | review follow-up (F2) | `3bc7524`, `ba73bb5` | Backend: `./gradlew spotlessCheck spotbugsMain cleanTest test --no-daemon` → BUILD SUCCESSFUL in 1m 33s, **614 suites / 2033 tests / 0 failures / 0 errors / 0 skipped** (baseline 614/2025, +8). Frontend: 120 files / 2215 tests / 0 failures, coverage 92.87/75.13/87.56/92.87. |
| 2026-09-21 | chained F1 branch | `ffcb38c` on `fix/product-abm-authz` | `./gradlew spotlessCheck spotbugsMain cleanTest test --no-daemon` → BUILD SUCCESSFUL in 1m 35s, **620 suites / 2049 tests / 0 failures / 0 errors / 0 skipped** (+16). |
| 2026-09-22 | S2 store source (T9) | `0ec4464` | Read-only exploration, no source written: the `store-inventory-settings` mold, the profile chain's real source (`user_preferences`), the exact search read, the four existing copies of the cascade ladder. |
| 2026-09-22 | S2 store resolver (T10) | `f33be2f` | `npm run lint` clean; focused spec 9/9; the new service at 100% statements/lines/functions. |
| 2026-09-22 | S2 store-scoped list (T11) | `677a77a` | `npm run lint` clean; `src/features/products/**` → 19 files / 200 tests / 0 failures. |
| 2026-09-22 | S2 per-store editor (T12) | `ea41fe2` | `npm run lint` clean; `npm run build` complete; `npm run test:coverage:check` → 20 products files / 222 tests / 0 failures. |
| 2026-09-22 | S2 backend IT (T13) | `96e03de` | Focused `./gradlew test --tests '…ProductVariantStoreStockWriteIntegrationTest'` → BUILD SUCCESSFUL, XML reports **5 tests / 0 failures / 0 errors / 0 skipped**. No `@Nested`, so the outer report carries the real count and the vacuous-green trap does not apply. |
| 2026-09-22 | **S2 gate (T14)** | `96e03de` (tip) | Backend: `./gradlew spotlessCheck spotbugsMain cleanTest test --no-daemon` → BUILD SUCCESSFUL in 1m 36s, **621 suites / 2054 tests / 0 failures / 0 errors / 0 skipped** (baseline 620/2049 → +1 suite, +5 tests: exactly the new IT class, no collateral). Frontend: `npm run lint` clean, `npm run build` complete, `npm run test:coverage:check` → **122 files / 2258 tests / 0 failures** (baseline 120/2215) and coverage **93.01/75.37/87.84/93.01** against thresholds 80/60/75/80 (S1 baseline 92.87/75.13/87.56/92.87). |
| 2026-09-22 | S2 T15 sales entry point | `e66feb1` | `npm run lint` clean; `npm run build` complete with no warnings; `npm run test:coverage:check` → **123 files / 2279 tests / 0 failures**, coverage 93.06/75.38/87.85/93.06, all above thresholds. The route control and the menu gating are covered by the existing derived specs plus one path-pinned assertion and four new menu cases. |

### Controls proven by mutation, not by assertion

A green control spec is not evidence that the control works. Both security-relevant specs on this
branch were falsified deliberately:

| Control | Mutation applied | Observed |
|---|---|---|
| `products.routes.spec.ts` | A non-variant child lost its `canActivate` | **Failed**: `expected undefined to deeply equal [ [AsyncFunction keycloakRoleGuard] ]` |
| `products.routes.spec.ts` | A variant child lost `lc-sales` | **Failed**: `expected { roles: [ 'lc-admin' ], …(1) } to deeply equal { …(2) }` |
| `ProductControllerSecurityTest` | `@PreAuthorize` removed from `POST /api/products` | **Failed**: 3 tests, including the two 403 + `verify(never())` cases |
| `ProductVariantStoreStockWriteIntegrationTest` | `ON CONFLICT (product_variant_id, company_store_id) DO NOTHING` removed from `insertStoreStockIfAbsent` | **Failed**: 1 of 5 — "JD-B-005: the first write inserts the row, and the next one keeps what it did not send". Without `DO NOTHING` the second write meets the unique constraint, so the endpoint answers 409 instead of 200. Source restored byte for byte (same sha256, empty `git diff`). |

### The vacuous-assertion trap (cost a full investigation; carry it forward)

The first version of the route control used `expect(route.canActivate).toContain(keycloakRoleGuard)`.
That assertion **passes when `canActivate` is `undefined`**, because chai only rejects a non-indexable
actual when the expected value is a string. Verified empirically on this repo's stack:

| Assertion | `actual = undefined` |
|---|---|
| `expect(undefined).toContain('x')` | **throws** |
| `expect(undefined).toContain(fn)` | **passes** (vacuous) |
| `expect(undefined).not.toContain('x')` | **throws** |
| `expect(undefined).toEqual([fn])` | **throws** |

A child with `data.roles = ['lc-admin']` but no `canActivate` of its own is **inert**, so it would have
been reachable by anyone the broadened parent gate admits — exactly the `lc-sales` leak the spec
existed to prevent. The repo already had the correct form in
`src/features/purchases/purchases.routes.spec.ts` ("should re-guard each orders child as admin-only");
the branch now follows it. **Rule: never assert a possibly-undefined route property with `toContain`.**

## Independent security review

A read-only `gentle-ai-verify` subagent reviewed the branch adversarially (`git diff main...HEAD`) with
eight claims to refute rather than confirm. It reproduced the gate state exactly and returned the
following. It also independently reproduced the matcher-semantics table above.

| # | Severity | Finding | Disposition |
|---|---|---|---|
| F1 | **CRITICAL** | The product ABM writes (`POST`/`PUT`/`DELETE /api/products`) carried **no `@PreAuthorize`** and `SecurityConfig` only requires `.authenticated()` for `/api/**`, so any authenticated principal could create, update or soft-delete products. The client guard was the only boundary. **Pre-existing**, not introduced by this branch. | **Fixed** on the chained branch `fix/product-abm-authz` (`ffcb38c`), per D7. |
| F2 | **WARNING** | A disabled variant could never be re-enabled from the UI: the global-definition read filtered `enabled = true` with no opt-in, so the row vanished and the dialog promised a reversal the UI could not perform. **Introduced** by this branch's copy. | **Fixed** per D6 (`3bc7524`, `ba73bb5`). |
| F3 | WARNING | The `VARIANT_ROLES` JSDoc claimed a navigation entry that does not exist (D5 defers it). | **Fixed**: the JSDoc now states only what is true. (`ba73bb5`) |
| F4 | SUGGESTION | The route-order comment overstated declaration order as load-bearing; `defaultUrlMatcher`'s full-consumption rule already prevents shadowing. | **Fixed**: comment rewritten. (`ba73bb5`) |
| F5 | SUGGESTION | The root-scoped service's `loading`/`error` signals have no consumer today. | **Accepted**: it is the repository's service convention (`ProductService` has the same shape); the cross-component clobbering risk is a repo-wide pattern question, not this branch's. |
| F6 | SUGGESTION | A list spec rendered a `{ enabled: false }` row the backend could never return, and one assertion was near-vacuous. | **Fixed**: the spec now mocks the real contract and the vacuous assertion is replaced. (`ba73bb5`) |

The review upheld the branch's core claims: no pre-existing `/products` child is reachable by an
`lc-sales`-only principal (client-side); the variant children are the only ones carrying `lc-sales`;
the UI is never broader than the backend on the endpoints it calls; the 409 renders no fabricated
field attribution; the unsaved-changes guard cannot trap the user; and no secret or sensitive value is
introduced.

## Declared follow-ups

| Item | Why deferred |
|---|---|
| Keycloak protocol mappers for `company_id` → `company_store_id` (D3) | Non-code precondition, separate component (`docker/`), blocking for any real environment; already a precondition of `lc-receiving` and `lc-sales`. |
| Read authorization on `GET /api/products` and `GET /api/products/{id}` | Both stay authenticated-only. `GET /{id}` is consumed by these variant screens, which are reachable by `lc-sales`, so narrowing the read is a contract change, not a one-line guard. The review's consumer map is the input for that decision. |
| The discoverable `lc-sales` entry point (D5) | Lands in S2 with the per-store stock editor. |
| `JD-A-003` — duplicate gates count soft-deleted rows | Changes the 409 contract; the UI does not require it. |
| `JD-A-002` — 409 instead of the documented 404 on inline paths | Cosmetic, pre-existing. |
| Store-claim checks on the older variant endpoints | Contract change for existing readers, deliberately left by `store-claim-hardening`. |
| Residual English copy in the products dashboard (`Products Administration`, `All Products`) | Pre-existing legacy copy inside an otherwise Spanish feature; unrelated to this slice. |
| Two frontend clients for `GET /api/product-variants/search` | S2 added `ProductVariantService.searchVariants`; `sales-orders/components/product-variant-selector` still calls the endpoint with its own inline `HttpClient`. That is pre-existing, not introduced here, and consolidating it is a `sales` refactor with its own review surface. |
| `ProductVariantOption` in `sales-order.models.ts` declares `listPrice`/`stock` non-nullable | That model is search-projection shaped, and a store row can exist with its prices unset. Pre-existing; the products-side `ProductVariantSearchResult` types them as `number | null`. |
| The fail-closed store notice is duplicated between the list and the per-store panel | Two consumers with slightly different branch sets and different copy; extracting it now would define a component by whatever both happen to need. Cheaper to reach for once a third consumer exists. |
