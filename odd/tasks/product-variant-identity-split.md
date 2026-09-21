# ODD feature: product-variant-identity-split

**Repository**: LifeControl — modules `life-control-api/` and `life-control-app-angular/`
**Branch**: `refactor/product-variant-identity`
**Worktree**: `~/workspace/LifeControl-worktrees/refactor-product-variant-identity`
**Status**: in progress
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
  "Shoe X size 38" is identical in every branch, so the second store **cannot register it**. The model
  cannot represent the same product+size in two stores, which is the normal retail case.
- The reverse cost: to sell size 38 in three stores you must create **three duplicated rows**, each
  with a hand-typed size string.
- There are **two different "SKU" namespaces**: `products.sku` (NOT NULL, unique, validated) and
  `product_variants.sku` (nullable, not unique, not validated, not checked for duplicates). The
  variant one carries no identity: it is only displayed and free-text searched.
- The size is **unstructured free text** in `variant_name`; nothing prevents two "Talla 38" rows for
  the same product in the same store.

The split is the prerequisite for the product-variant CRUD UI: building a variant form against the
mixed model would require asking for a store in every variant write, which is the symptom being fixed.

## Decisions (user-confirmed 2026-09-20)

| # | Decision | Value |
|---|---|---|
| D1 | Pricing level | **Both per store.** `list_price` and `cost_price` live in the per-store row. |
| D2 | Variant identifier | **`bar_code` only.** `NOT NULL`, globally unique. `product_variants.sku` is removed. |
| D3 | Size modeling | **`variant_name` `NOT NULL` + `UNIQUE(product_id, variant_name)`.** |
| D4 | Inventory scope | **No W3.** Stock moves to the per-store row; `product_variant_locations` and the ledger keep today's behavior, including the documented drift. |
| D5 | Historical data | Existing data may be **discarded**. Destructive migrations are authorized; no backfill required. |

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
columns are dropped from it. This removes the FK re-point risk from the original blast-radius map.

## Why this shape

- **Keeps the referenced table stable.** Renaming or splitting the referenced table would force five
  FK migrations plus every `productVariantId` in the codebase. Extracting only the store columns keeps
  every `product_variant_id` column and every DTO field name valid.
- **The global unique on `bar_code` becomes correct.** It was "wrong" only because the table was
  store-scoped. Once the identity row is global, a global unique EAN is exactly right.
- **`product_variant_store_stock` mirrors `product_variant_locations`**, the proven per-scope balance
  pattern in this repo: plain UUID FK columns (no `@ManyToOne`), `UNIQUE(variant, scope)`,
  `Auditable` base class, manual getters/setters plus a static `Builder` (no Lombok).
- **One definition, N stock rows** collapses the N × sizes duplication of the shoe-store case.

## Scope

### In scope

- `V13` migration creating `product_variant_store_stock` (additive).
- `V14` migration dropping the legacy columns from `product_variants`, tightening `bar_code`/
  `variant_name` to `NOT NULL` and adding `UNIQUE(bar_code)` / `UNIQUE(product_id, variant_name)`.
- `ProductVariantStoreStock` entity + repository; `ProductVariant` reduced to the global definition.
- Variant CRUD and read paths moved onto the two tables (`ProductVariantService`,
  `ProductVariantRepository`, DTOs, `ProductController`, `ProductVariantSearchController`).
- Store-membership validators re-pointed: `PurchaseOrderService.resolveProductVariant`,
  `GoodsReceiptService` line validation, `InventoryService` store agreement.
- Stock writers moved to the per-store row plus the lock-order review: `InventoryService`,
  `SalesOrderService`.
- Backend tests re-targeted (unit + Postgres integration).
- Frontend adapters for the changed semantics.

### Out of scope

- The new product-variant **management UI** (create/edit forms, list page, routes). Immediate follow-up.
- W3: `SALE` movements, `product_variant_locations` reconciliation, sales-location-aware writes (D4).
- Restructuring `variant_name` into structured size/color fields (D3 keeps free text with uniqueness).
- Removing `products.sku` — it stays as the global catalog human key.

## Slices

Each slice is a work unit closing with at least one Conventional Commit. Legacy columns stay until
S5, so every slice keeps the module compiling and CI green.

| # | Slice | Contents | Risk |
|---|---|---|---|
| S1 | Additive schema | `V13` creates `product_variant_store_stock`; `ProductVariantStoreStock` entity; repository. Nothing reads it yet. | Low |
| S2 | Definition CRUD + read | Variant create/update write the global definition plus the per-store row; list/search join the new table; DTOs keep their field names. | Medium |
| S3 | Store membership validators | `resolveProductVariant`, goods-receipt line validation and the inventory store agreement prove membership via the new table instead of `variant.companyStoreId`. | High |
| S4 | Stock + locking | `InventoryService` and `SalesOrderService` read/write per-store stock; lock target and sort key become `(companyStoreId, variantId)`. | High |
| S5 | Legacy drop | `V14` drops `company_store_id`/`sku`/`stock`/`list_price`/`cost_price`; dual-write removed; `ProductVariant` reduced. | Medium |
| S6 | Frontend adapters | Variant models, services and the six variant-consuming screens adapted to the final semantics. | Medium |

## Tasks

### S1 — Additive schema

- [ ] `V13__product_variant_store_stock.sql`: table + `UNIQUE(product_variant_id,
      company_store_id)` + indexes, header comment explaining the split and the D5 no-backfill rule.
- [ ] `ProductVariantStoreStock` entity in `product/model/` following `ProductVariantLocation`'s
      shape (no Lombok, `Auditable`, manual `Builder`).
- [ ] `ProductVariantStoreStockRepository` (`JpaRepository` + the unlocked derived finder).
- [ ] Verify: `./gradlew spotlessCheck spotbugsMain --no-daemon` and `./gradlew test --no-daemon`.

### S2 — Definition CRUD + read paths  *(open decision D6: write API shape — resolve before starting)*

- [ ] `ProductVariantService.createVariant` / `updateVariant` write the definition plus the per-store
      row; `bar_code` and `variant_name` validated (`@NotBlank`, `@Size`) in `ProductVariantRequest`.
- [ ] Duplicate checks: `existsByBarCode` → `DuplicateProductVariantException` (409);
      `existsByProductIdAndVariantName`.
- [ ] `listVariants` / `searchVariants` join `product_variant_store_stock` when a `storeId` is given;
      `ProductVariantResponse` / `ProductVariantSearchResponse` keep their current field names.
- [ ] `ProductVariantRepository`: replace the store-scoped derived finders with explicit `@Query`
      joins; keep `findByIdForUpdate` until S4.
- [ ] Tests: `ProductVariantServiceTest`, `ProductVariantControllerTest`,
      `ProductVariantStoreFilterIntegrationTest`.

### S3 — Store membership validators

- [ ] `PurchaseOrderService.resolveProductVariant`: prove `(variant, product)` on the definition and
      `(variant, store)` in the new table; keep the 404 contract.
- [ ] `GoodsReceiptService` line validation: replace the `variant.getCompanyStoreId()` equality.
- [ ] `InventoryService`: replace the store agreement check.
- [ ] Tests: `PurchaseOrderServiceTest`, `PurchaseOrderDetailVariantIntegrationTest`,
      `GoodsReceiptServiceTest`, `GoodsReceiptIntegrationTest`.

### S4 — Stock and locking

- [ ] New pessimistic finder on `ProductVariantStoreStock`
      (`findByProductVariantIdAndCompanyStoreIdForUpdate`), mirroring
      `ProductVariantLocationRepository`'s pattern including a conflict-tolerant insert.
- [ ] `InventoryService.applyReceipt`: per-store stock write replaces `variant.setStock`.
- [ ] `SalesOrderService`: all five stock call sites read/write the per-store row; the deadlock-avoidance
      sort key becomes `(companyStoreId, variantId)`.
- [ ] Re-prove the documented lock order (`purchaseOrder → variant → balance`) against the new target.
- [ ] Tests: `InventoryServiceTest`, `InventoryIntegrationTest`, `SalesOrderServiceTest`,
      `SalesOrderIntegrationTest`.

### S5 — Legacy drop

- [ ] `V14` drops the five legacy columns from `product_variants`, sets `bar_code`/`variant_name`
      `NOT NULL`, adds `UNIQUE(bar_code)` and `UNIQUE(product_id, variant_name)`.
- [ ] Remove the dual-write and the dead `ProductVariant` getters/setters.
- [ ] Tests: full backend suite green.

### S6 — Frontend adapters

- [ ] `product-variant.models.ts`: definition vs per-store split.
- [ ] `product.service.ts` / variant search consumer semantics.
- [ ] Screens: PO variant picker, PO detail table, receipt-create, receipt-detail, sales variant
      selector, sales item table.
- [ ] Gate: `npm run lint`, `npm run build`, `npm run test:coverage:check`.

## Open decisions

- **D6 — variant write API shape.** Does `POST /api/products/{productId}/variants` accept a list of
  store rows in one payload, or is the definition created first and store rows added per store
  (`PUT /variants/{id}/stores/{storeId}`)? Blocks S2 only; S1 is unaffected.
- **D7 — naming.** `product_variant_store_stock` is the working name, chosen to mirror
  `product_variant_locations`. Confirm or rename before S1.

## Review workload

Six slices, an estimated 1800–2400 changed lines total, driven by the compile-all-or-nothing cutover
(`ProductVariant`'s getters feed four services, three DTOs, one JPQL projection, ~15 backend test
classes and ~15 frontend files). This requires **chained PRs**, not a single review. S3 and S4 are the
high-risk slices and carry the lock-order and tenant-isolation changes.

## Risks

1. **Lock-order inversion → deadlocks.** The serialization point moves from the per-store
   `product_variants` row to the new per-store row while the sort key stays `variantId` only.
2. **Store membership check silently disappears.** Today three sites prove membership by reading
   `variant.companyStoreId`; after the split the compiler stops pointing at them, and a missed site
   binds a variant to the wrong store.
3. **Stock drift becomes three-way.** Adding a per-store table next to
   `product_variants.stock` (until S5) and `product_variant_locations.stock` creates a third copy with
   no DB constraint tying them; S4 and S5 must land to converge.
4. **Nullable legacy PO lines.** `purchase_order_details.product_variant_id` is `NULLABLE` (`V9:14`)
   and D5 authorizes discarding rows, but the tolerant code path must be re-checked, not assumed.

## Evidence log

| Date | Slice | Commit | Gate result |
|---|---|---|---|
| — | — | — | — |
