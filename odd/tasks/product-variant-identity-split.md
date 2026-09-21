# ODD feature: product-variant-identity-split

**Repository**: LifeControl — modules `life-control-api/` and `life-control-app-angular/`
**Branch**: `refactor/product-variant-identity`
**Worktree**: `~/workspace/LifeControl-worktrees/refactor-product-variant-identity`
**Status**: in progress — S1 and S2 done, S3 (frontend adapters) pending
**Created**: 2026-09-20
**Risk**: high — schema change, API contract change, tenant-boundary semantics

## Objective

Split `product_variants` into a **global variant definition** (per product: barcode, name/size) plus a
**per-store row** (stock and pricing per `company_store`), so the same physical variant can exist in
more than one store.

## Problem

`product_variants` conflates two different concepts in one table:

1. **The sellable definition** — `product_id`, `variant_name`, `sku`, `bar_code`. Global by nature.
2. **The per-store stock/price row** — `company_store_id NOT NULL`, `stock`, `list_price`, `cost_price`.

Consequences verified in the current tree:

- `V1__baseline_schema.sql:415` declares `CREATE UNIQUE INDEX idx_product_variants_bar_code ON
  product_variants(bar_code)` — a **global** unique index on a **store-scoped** table. A real EAN for
  "Shoe X size 38" is identical in every branch, so the second store **cannot register it**.
- The reverse cost: to sell size 38 in three stores you must create **three duplicated rows**.
- There are **two different "SKU" namespaces**: `products.sku` (NOT NULL, unique, validated) and
  `product_variants.sku` (nullable, not unique, not validated). The variant one carries no identity.
- The size is **unstructured free text** in `variant_name`; nothing prevents two "Talla 38" rows for
  the same product in the same store.

## Decisions (user-confirmed 2026-09-20)

| # | Decision | Value |
|---|---|---|
| D1 | Pricing level | **Both per store.** `list_price` and `cost_price` live in the per-store row. |
| D2 | Variant identifier | **`bar_code` only.** `NOT NULL`, globally unique. `product_variants.sku` is removed. |
| D3 | Size modeling | **`variant_name` `NOT NULL` + `UNIQUE(product_id, variant_name)`.** |
| D4 | Inventory scope | **No W3.** Stock moves to the per-store row; `product_variant_locations` and the ledger keep today's behavior, including the documented drift. |
| D5 | Historical data | Existing data may be **discarded**. Destructive migrations are authorized; no backfill required. |
| D6 | Write API shape | **Option (b), two endpoints.** `POST /api/products/{productId}/variants` creates the global definition; `PUT /api/variants/{variantId}/stores/{storeId}` upserts one store row. No bulk endpoint until proven necessary. |
| D7 | Table naming | Working name `product_variant_store_stock`, mirroring `product_variant_locations`. Committed in `V13`; still cheap to rename before merge. |

## Target model

```
products (global)
  └── product_variants                              GLOBAL definition
        id, product_id FK NOT NULL, bar_code, variant_name, enabled, timestamps
        UNIQUE(bar_code)  ·  UNIQUE(product_id, variant_name)
        DROPPED: company_store_id, sku, stock, list_price, cost_price
        │
        └── product_variant_store_stock             PER STORE
              id, product_variant_id FK NOT NULL, company_store_id FK NOT NULL,
              stock NOT NULL DEFAULT 0, list_price, cost_price, timestamps
              UNIQUE(product_variant_id, company_store_id)
              │
              └── (the five existing FKs keep pointing at product_variants.id — NO re-point)
                    sales_order_items.product_variant_id          V1:499
                    purchase_order_details.product_variant_id     V9:14
                    goods_receipt_items.product_variant_id        V12:43
                    product_variant_locations.product_variant_id  V10:20
                    inventory_movements.product_variant_id        V10:32
```

Because `product_variants` remains the referenced table, the five foreign keys are untouched; only
columns are dropped from it.

## Why this shape

- **Keeps the referenced table stable.** Extracting only the store columns keeps every
  `product_variant_id` column and every DTO field name valid; no FK re-point migration.
- **The global unique on `bar_code` becomes correct.** It was wrong only because the table was
  store-scoped.
- **`product_variant_store_stock` mirrors `product_variant_locations`**, the proven per-scope balance
  pattern: plain UUID FK columns, `UNIQUE(variant, scope)`, `Auditable`, manual getters/setters plus a
  static `Builder` (no Lombok).
- **One definition, N stock rows** collapses the N × sizes duplication of the shoe-store case.

## Staging finding (corrects the original slice plan)

The original six-slice plan assumed the cutover could be staged by keeping the legacy columns
populated until the end. **That is not possible**, and S1's own analysis was too optimistic:

- `product_variants.company_store_id` is `NOT NULL` (`V1:403`).
- Under D6(b) the definition is created **without** a store, so the definition row cannot be inserted
  while that column still exists and is required.
- Making it nullable to buy a window would put a single `stock` number back on a global row while
  sales and receipts still read it per variant — a deliberate functional regression in stock
  semantics, which is the exact drift class the refactor is meant to remove.

Therefore **the schema cutover and the service cutover are indivisible**: there is no valid
intermediate state. The only genuinely separable pieces are S1 (additive, done) and the frontend.

## Scope

### In scope

- `V13` creating `product_variant_store_stock` (done).
- `V14` reshaping `product_variants` into the global definition: drop `company_store_id`, `sku`,
  `stock`, `list_price`, `cost_price`; set `bar_code`/`variant_name` `NOT NULL`; add
  `UNIQUE(bar_code)` and `UNIQUE(product_id, variant_name)`.
- `ProductVariant` reduced; `ProductVariantStoreStock` entity + repository (done).
- Variant definition CRUD and read paths on the new model, including the D6(b) store endpoint.
- Store-membership validators re-pointed: `PurchaseOrderService.resolveProductVariant`,
  `GoodsReceiptService` line validation, `InventoryService` store agreement.
- Stock writers moved to the per-store row plus the lock-order review: `InventoryService`,
  `SalesOrderService`.
- Backend tests re-targeted (unit + Postgres integration).
- Frontend adapters for the changed semantics.

### Out of scope

- The new product-variant **management UI** (create/edit forms, list page, routes). Follow-up.
- W3: `SALE` movements, `product_variant_locations` reconciliation (D4).
- Structured size/color fields (D3 keeps free text plus uniqueness).
- Removing `products.sku`.

## Plan

| # | Slice | Contents | Risk | Status |
|---|---|---|---|---|
| S1 | Additive schema | `V13` + `ProductVariantStoreStock` entity + repository. Nothing reads it. | Low | **done** |
| S2 | Backend cutover (atomic) | `V14`; `ProductVariant` reduced; definition CRUD + D6(b) store endpoint; read paths joined; membership validators; stock writers and locking; all backend tests. | High | pending |
| S3 | Frontend adapters | Variant models, services and the six variant-consuming screens. | Medium | pending |

S2 cannot be split further without creating an invalid or deliberately regressed intermediate state
(see the staging finding). It is therefore a large review unit and must be handled as such.

## Tasks

### S1 — Additive schema — DONE

- [x] `V13__product_variant_store_stock.sql`: table + `UNIQUE(product_variant_id, company_store_id)`
      + indexes, header comment explaining the split and the D5 no-backfill rule.
- [x] `ProductVariantStoreStock` entity in `product/model/`, following `ProductVariantLocation`'s shape.
- [x] `ProductVariantStoreStockRepository` (non-locking finder + membership probe).
- [x] Gate: `./gradlew spotlessCheck compileJava --no-daemon` and `LifeControlApiApplicationTests`
      against real Postgres (Flyway `V1`–`V13` + `ddl-auto=validate`).
- [x] Commit `bd17f07`.

### S2 — Backend cutover (atomic, high risk) — DONE

Schema:
- [x] `V14__product_variant_definition.sql`: drop the five legacy columns; `bar_code` and
      `variant_name` to `NOT NULL`; `UNIQUE(bar_code)` replaces `idx_product_variants_bar_code`;
      add `UNIQUE(product_id, variant_name)`; keep `enabled` and the audit columns. Header comment
      records the D5 data-discard decision and that `V10`/`V12`/`V9`/`V1` FKs are unaffected.
- [x] Confirm `product_variant_locations` and `inventory_movements` rows are cleared with the rest of
      the discarded data, so no orphan balance outlives its variant.

Definition model and API:
- [x] Reduce `ProductVariant` to `productId`, `barCode`, `variantName`, `enabled`.
- [x] `ProductVariantRequest` (definition only: `barCode`, `variantName`) with `@NotBlank`/`@Size`.
- [x] New request/response for the store row (`listPrice`, `costPrice`, `stock`).
- [x] `POST /api/products/{productId}/variants` creates the definition only (201).
- [x] `PUT /api/variants/{variantId}/stores/{storeId}` upserts the store row (200), validating that
      the store exists (404) and that the variant exists (404).
- [x] Duplicate checks → `DuplicateProductVariantException` (409) for `bar_code` and for
      `(product_id, variant_name)`; register the thin exception under `product/exception/`.
- [x] Read paths: `listVariants` / `searchVariants` join `product_variant_store_stock` when `storeId`
      is present; `ProductVariantResponse` / `ProductVariantSearchResponse` keep their current field
      names so the frontend keeps compiling.
- [x] `ProductVariantRepository`: replace the store-scoped derived finders with `@Query` joins; move
      `findByIdForUpdate` to `ProductVariantStoreStockRepository` (see locking).
- [x] `ProductVariantService.getVariant` / `updateVariant` / `deleteVariant` re-pointed to the
      definition; `deleteVariant` stays a soft delete.

Store membership validators:
- [x] `PurchaseOrderService.resolveProductVariant`: prove `(variant, product)` on the definition and
      `(variant, store)` on the store row, preserving the 404 contract.
- [x] `GoodsReceiptService` line validation: replace the `variant.getCompanyStoreId()` equality.
- [x] `InventoryService`: replace the store agreement check.

Stock, pricing and locking:
- [x] `ProductVariantStoreStockRepository`: pessimistic finder
      (`findByProductVariantIdAndCompanyStoreIdForUpdate`) plus the conflict-tolerant insert,
      mirroring `ProductVariantLocationRepository` including the `ON CONFLICT DO NOTHING` note.
- [x] `InventoryService.applyReceipt`: per-store stock write replaces `variant.setStock`.
- [x] `SalesOrderService`: all five stock call sites read/write the per-store row; the
      deadlock-avoidance sort key becomes `(companyStoreId, variantId)`.
- [x] Re-prove the documented lock order (`purchaseOrder → variant → balance`) against the new target.

Tests:
- [x] `ProductVariantServiceTest`, `ProductVariantControllerTest`, `ProductVariantStoreFilterIntegrationTest`.
- [x] `PurchaseOrderServiceTest`, `PurchaseOrderDetailVariantIntegrationTest`.
- [x] `GoodsReceiptServiceTest`, `GoodsReceiptIntegrationTest`.
- [x] `InventoryServiceTest`, `InventoryIntegrationTest`.
- [x] `SalesOrderServiceTest`, `SalesOrderIntegrationTest`.
- [x] Gate: `./gradlew spotlessCheck spotbugsMain --no-daemon` then `./gradlew test --no-daemon`.

### S3 — Frontend adapters

- [ ] `product-variant.models.ts`: definition vs per-store split; new store-row request type.
- [ ] `product.service.ts` variant methods plus the store-row endpoint client.
- [ ] Screens: PO variant picker, PO detail table, receipt-create, receipt-detail, sales variant
      selector, sales item table.
- [ ] Gate: `npm run lint`, `npm run build`, `npm run test:coverage:check`.

## Review workload

S1: ~180 lines (done). S2 is the atomic cutover: it touches four services, three DTOs, one JPQL
projection, two repositories, ~15 backend test classes and one destructive migration. Realistically
**900–1300 changed lines in a single review unit**, which exceeds the review-workload guard and must
be declared as such rather than hidden behind artificial slices. S3 is a separate, smaller review.

Mitigation options presented to the user: (A) accept S2 as one declared large review; (B) split S2 by
concern into a chained stack whose members must merge together (each intermediate is not independently
valid); (C) merge S2 and S3 into one PR (largest review, no intermediate state at all).

## Risks

1. **Lock-order inversion → deadlocks.** The serialization point moves from the per-store
   `product_variants` row to the new per-store row while the sort key would otherwise stay `variantId`.
2. **Store membership check silently disappears.** Today three sites prove membership by reading
   `variant.companyStoreId`; after the split the compiler stops pointing at them.
3. **Discarded data must be discarded completely.** `product_variant_locations`,
   `inventory_movements`, `goods_receipt_items`, `sales_order_items` and the PO detail variant link
   all reference variants; a partial truncate leaves orphans or deletes rows still referenced.
4. **Nullable legacy PO lines.** `purchase_order_details.product_variant_id` is `NULLABLE` (`V9:14`);
   keeping or dropping that tolerance is a deliberate choice in S2, not an accident.

## Evidence log

| Date | Slice | Commit | Gate result |
|---|---|---|---|
| 2026-09-20 | S1 | `bd17f07` | `spotlessCheck compileJava` → BUILD SUCCESSFUL; `LifeControlApiApplicationTests` → BUILD SUCCESSFUL (Flyway `V1`–`V13`, `ddl-auto=validate`) |
| 2026-09-20 | S2 | `2d991de` | `spotlessCheck spotbugsMain` → BUILD SUCCESSFUL; `test` → BUILD SUCCESSFUL, 612 classes / 2006 tests / 0 failures / 0 errors / 0 skipped (Testcontainers Postgres, Flyway `V1`–`V14`, `ddl-auto=validate`) |

### S2 outcome notes

- `purchase_order_details.product_variant_id` became `NOT NULL` in `V14`: the column was nullable only
  because V9 shipped the link before the picker existed, the DTO has required it since the contract
  flip, and D5 discards the data, so the legacy null tolerance was dead code that disagreed with the
  database. `PurchaseOrderService.resolveProductVariant` no longer accepts a null variant.
- `ProductVariantRequest` no longer carries `enabled` (the flag is not part of the global identity).
  That would have removed the ability to restore a soft-deleted variant, so
  `PATCH /api/products/{productId}/variants/{variantId}/enable` was added, mirroring the
  repository-wide re-enable convention (`promotion`, `customer`, `measure unit`, store tree levels).
- The sales path calls `insertStoreStockIfAbsent` before locking, so selling a `(variant, store)` pair
  that has no row creates a 0-stock row and then fails with `InsufficientStockException` instead of a
  404. The insert rolls back with the failed transaction, so nothing persists; the semantic choice
  (0 stock rather than 404) is deliberate and worth a reviewer's eye.
- Not covered by a dedicated test: a direct DB-level constraint-violation test for `UNIQUE(bar_code)`
  and `UNIQUE(product_id, variant_name)`. They are covered at the service/controller layer (409) and
  enforced by `ddl-auto=validate`; adding a constraint-violation integration test would strengthen it.
