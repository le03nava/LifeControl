# Drop `product_variant_store_stock.list_price` (issue #779)

**Status**: implemented — the store row carries no price in any representation: `V77` drops the column behind a guard, and `ProductVariantStoreStock` no longer declares the field, its accessors or its builder method. Nothing is deferred.

## Specs

The requirement is issue #779, whose acceptance criteria are quoted verbatim.

- **S1**
  > `grep -rn "product_variant_store_stock" life-control-api/src/main/resources/db/migration/` shows one new
  > migration dropping `list_price`, following the shape `V74` used for `stock` (guarded drop, self-check
  > read back, no `NO FORCE` window left open).

- **S2**
  > The drop refuses, with a named message, when any store row holds a `list_price` that its listing does not
  > reproduce — the same "a migration that reads an armed table sees zero rows and PASSES" trap `V74`'s first
  > draft hit.

- **S3**
  > `ProductVariantStoreStock` no longer declares the field, the getter, the setter or the builder method, and
  > the module still compiles: `./gradlew :life-control-api:compileJava` exits 0.

- **S4**
  > A migration test asserts the column is gone (`information_schema.columns`) and that `cost_price` and
  > `company_store_id` survive.

- **S5**
  > The `product`, `inventory` and `salesorder` suites are green, and no response field changes value: the
  > price a client reads is the listing's, before and after.

Non-goals, also from the issue: dropping the table itself or `cost_price`; any ADR-0001 work; backfilling listings for store rows whose price was never copied.

## Tasks

| ID | Specs | Route | Commit |
| --- | --- | --- | --- |
| T1 | S3, S5 | Remove the field, accessors and builder method; fix the thirteen call sites | `refactor(product): remove the list_price field from the store row` |
| T2 | S1, S2, S4 | `V77__product_variant_store_stock_drop_list_price.sql` + `StoreStockListPriceDropMigrationIntegrationTest` | `chore(db): drop product_variant_store_stock.list_price (V77)` |

## Log

- **L1** — maintainer's request, verbatim: "dale con 779, aprueba el issue, el cambio, push y PR".
- **L2** — issue #779 approved with `status:approved` and opened as unit `chore/drop-store-stock-list-price`.
- **L3** — measured before writing: `product_variant_store_stock` carries **no** RLS policy (`apply_tenant_isolation` never names it; `V58:63-69` says why: the table has no `company_id`), so the drop itself needs no window. `variant_listing` **is** armed by `V58:189` and inherits `ENABLE`+`FORCE` from `V52:152-153`, so the guard that reads it does need one — that is the whole difference between this file and a plain `DROP COLUMN`.
- **L4** — `V13:28` declares `list_price DECIMAL(12,2)` with **no** `CHECK` constraint, so the drop removes the column alone; there is no constraint to name.
- **L5** — thirteen Java call sites reached the field: eight `builder().listPrice(...)` (goods receipt, purchase order, inventory ×2, listing read, variant search, store filter, sales order) and five `getListPrice()).isNull()` assertions that pinned "the price is not on the store row". The assertions are deleted rather than reworded: once the column does not exist, the scheme itself is the guarantee they were restating, and a test cannot assert the absence of a field any better than the compiler can.
- **L6** — RED observed before `V77` existed: 4 of the 5 cases of `StoreStockListPriceDropMigrationIntegrationTest` failed for the right reason (the column was still present, and the two divergent-price fixtures migrated instead of refusing). GREEN after the migration: that suite 5/5, then `product`, `inventory`, `salesorder`, `goodsreceipt` and `purchaseorder` in one run (259 classes, 1085 tests, 0 failures, 0 skipped) and `support`, `hr`, `supplier`, `company`, `store`, `document` in a second one (397 classes, 1806 tests, 0 failures, 0 skipped) — the second run because a new migration moves the Flyway head those suites derive, not because the drop touches them. `odd-status-headers.test.sh` green on the new record (144 records, 78 passed, 0 failed).
- **L7** — the slice is 737 authored lines (665 added, 72 deleted) against the repo's ~400-line review budget, and 585 of them are `V77` plus its migration suite: the shape this repo already uses for a schema change (`ProductVariantModeImmutabilityMigrationIntegrationTest` is the same size and the same pattern). Delivered as **one PR with the size declared in its body** rather than a chain, because the halves are not independently shippable in either order: without `V77` the removed accessors leave a dead column behind, and with `V77` the accessors break `ddl-auto: validate`. A chain here would produce a second PR that exists only to undo the first one's compromise.
