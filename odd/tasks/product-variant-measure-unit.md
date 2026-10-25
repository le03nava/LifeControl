# ODD feature: product-variant-measure-unit

**Repository**: LifeControl — `life-control-api/` + `life-control-app-angular/`
**Status**: implemented — the relation and its ABM are complete; fractional quantities in purchase orders (`W2-D7`), invoicing and conversions stay deferred, each with its reason below.
**Created**: 2026-10-22 · **Risk**: medium — a nullable FK and additive DTO fields, but the quantity meaning of every stored balance becomes explicit, so two guards (D3, D4) are what keep the data readable.

## Decisions

| # | Decision | Choice |
| --- | --- | --- |
| **D1** | Level of the relation | **`product_variants.measure_unit_id`**, one unit per variant, nullable, FK to `measure_units(id)`. The same product can hold a `Pieza` variant and a `Kgm` variant. |
| **D2** | What the API returns | **`measureUnitId` only.** The label (`measureUnitShortName`) is resolved in the Angular client from the catalog it already loads for the picker. Rejected: denormalising the short name into the response, which costs a join on the store-scoped JPQL constructor and an N+1 on the definition list to save the client a lookup it never skips. |
| **D3** | Immutability guard | The unit is **frozen once the variant holds a quantity meaning**: a `product_variant_store_stock` row with `stock <> 0`, or any `inventory_movements` row. Replacing or clearing a declared unit is then rejected with **409**. The asymmetry is deliberate and load-bearing: a **first declaration** (`null -> unit`) is always allowed, even with stock, because nothing stored ever claimed another unit and the whole pre-V29 catalog is `null` with stock — blocking it would make the ABM unusable on existing data. `stock = 0` alone is not a history: it is the column default of a row that was never filled. |
| **D4** | `unit_type` coherence | A product cannot mix `PRODUCT` and `SERVICE` units across its variants. Rejected: filtering the picker by `products.product_type`, which is free text (`VARCHAR(50)`, UI is a plain text input), so the server cannot derive it. |
| **D5** | Filtering the picker | **None.** Every enabled unit is offered (S4). |
| **D6** | `ProductVariantSearchResponse` | **Untouched.** The POS and purchase-order pickers keep their current projection; carrying the unit there is a follow-up of its own because it changes what a point-of-sale line can express. |
| **D7** | Not in this unit | Fractional `purchase_order_details.quantity` (still `INTEGER`, `W2-D7` untouched), CFDI emission, unit conversions, SAT decimal scale. |

## Specs

- **S1** — The unit of measure of an article is declared on its **variant**, not on the product: `seria la opción B`. A variant with no unit keeps working exactly as it does today; the field is optional on the wire and nullable in the schema.
- **S2** — Scope of this unit of work: `en esta unidad de trabajo solo seria la relacion + ABM`. The relation, its validation, the two guards and the form control — nothing else.
- **S3** — The business case that forces D1: `un articulo si se puede vender en piezas y kg`. One product, two variants, two units; both are accepted and both keep their own stock and price row.
- **S4** — `no filtrar`: the unit selector offers **every** `enabled` unit of the catalog. `unit_type` never narrows the list.
- **S5** — D3's guard, as observed behavior: replacing or clearing the unit of a variant that already holds a non-zero stock balance or has ledger movements is rejected with **409** and the stored rows are left untouched; a **first** declaration on such a variant is accepted, because it reinterprets nothing that claimed otherwise.
- **S6** — D4's guard, as observed behavior: setting a unit whose `unit_type` differs from the one already used by another variant of the same product is rejected with **400**.
- **S7** — No existing behavior changes: creating or updating a variant without `measureUnitId` behaves exactly as before (`null` unit), and the store-scoped list, search, stock and pricing endpoints keep their current field set plus the one added field.

## Tasks

| ID | Task | Specs | Route | Commit |
| --- | --- | --- | --- | --- |
| T1 | `V29__product_variant_measure_unit.sql`: nullable `measure_unit_id` FK + index, no backfill | S1, S7 | inline | `318aa74` |
| T2 | `ProductVariant.measureUnitId` (field, getter/setter, builder) | S1 | inline | `318aa74` |
| T3 | Exceptions: `DisabledMeasureUnitException` and `MeasureUnitTypeMismatchException` (400), `MeasureUnitChangeNotAllowedException` (409) | S5, S6 | inline | `318aa74` |
| T4 | `ProductVariantService`: resolve + validate the unit, the two guards, `toResponse` carries the id | S1, S5, S6, S7 | inline | `318aa74` |
| T5 | `ProductVariantResponse` + the store-scoped JPQL constructor expression | S7 | inline | `318aa74` |
| T6 | Backend tests: unit accepted/rejected cases, both guards, response field, previous behavior preserved | S5, S6, S7 | inline | `318aa74` |
| T7 | Angular: `MeasureUnit` model, `measureUnitId` on `ProductVariant`/`ProductVariantRequest` | S1, S7 | inline | `c1f217f` |
| T8 | Angular: `MeasureUnitService` (`GET /api/measure-units`) | S4 | inline | `c1f217f` |
| T9 | Angular: `mat-select` control in `product-variant-form` + catalog load in `product-variant-dialog` | S2, S4 | inline | `c1f217f` |
| T10 | Angular tests: the control renders every enabled unit, the payload carries the id, the reset clears it | S4, S7 | inline | `c1f217f` |

### Delivery slices

Two commits, one delivery unit: `318aa74` is the API (migration, model, service, guards, tests) and `c1f217f` is the ABM (model, catalog service, select, dialog, tests). Measured with the harness's own counter: **1552 authored changed lines** across the two, of which roughly 800 are tests. That is **~3.9× the ~400-line review advisory**, and the split is the API/ABM boundary rather than a 400-line boundary: no smaller slice leaves `main` compiling *and* green, because the response record, its JPQL constructor and `toResponse` change together.

This is the point where the delivery strategy has to be chosen, and it is the maintainer's call, not the implementation's:

1. **One pull request** carrying both commits — honest about a single coherent feature, but ~1552 lines to review.
2. **Two chained pull requests** (`chained-pr`): the API first, then the ABM on top, each reviewable alone because the ABM only consumes the contract the first one publishes.

The record travels in whichever pull request closes the unit.

## Log

- **L1** — `en que parte se le configura el precio de venta de un articulo en una tienda y las unidades de medida`
- **L2** — `si ese es el objetivo, quiero seguir analizando primero, en que parte consideras que se pueda hacer esta relacion`
- **L3** — `ok, 1 seria la opción B un articulo si se puede vender en piezas y kg ves algun riesgo para mantener el inventario y la facturacion o ves alguna mejor opcion ?, 2 en esta unidad de trabajo solo seria la relacion + ABM, 3 me explicas la pregunta`
- **L4** — `ok, dale con el no filtrar`

### Findings carried into the record

- The relation did not exist anywhere before this unit: `measure_units` had **no** inbound reference in any of the 28 migrations, `MeasureUnit` was referenced by no Java file outside its own module, and the Angular tree named `measure-unit` zero times. The catalog was reachable only through `/api/measure-units`.
- The quantity split is pre-existing and deliberately untouched (`D7`): `sales_order_items.quantity`, `inventory_movements.quantity`, `product_variant_locations.stock`, `product_variant_store_stock.stock` and `goods_receipt_items.quantity_received` are all `DECIMAL(12,2)`, while `purchase_order_details.quantity` / `received_quantity` are `INTEGER`, and the receipt boundary rejects a non-whole quantity (`W2-D7`, `odd/tasks/purchase-order-goods-receipt.md:193`). A variant measured in `Kgm` can therefore be sold and stocked in fractions and **cannot yet be ordered or received** in fractions. That boundary is a real limitation of this unit, not a bug in it.
- `measure_units.sat_code` is the SAT `c_ClaveUnidad` catalog and `products.sat_code` is `c_ClaveProdServ`. Keeping the unit on the variant is what makes the CFDI pair (`ClaveProdServ` + `ClaveUnidad`) expressible per concept; the emission itself stays out of scope.
- **A new migration moves seven Flyway head pins.** `goodsreceipt/GoodsReceiptIntegrationTest` and six `hr`/`provisioning` schema-migration suites asserted `"28"` literally; they now assert `"29"`, and the three that named `V28` in a `@DisplayName` or a method name were renamed so the name does not contradict the assertion. Any future migration moves them again: that is the cost of pinning the head instead of deriving it, and deriving it is a separate change.
- **The 409 banner gained a second cause.** `product-variant-dialog` mapped every 409 to "a variant with that barcode or name already exists"; with `D3` a 409 can also mean the unit is frozen. The copy now names both causes in one sentence, because the backend sends no field attribution for either and echoing its English message into a Spanish screen would be worse.
- Pre-existing, out of scope: the repository's `core.hooksPath` is `life-control-app-angular/.husky/_`, so `.githooks/pre-commit` (the anchor guard) is not active in this clone.

## Evidence log

- 2026-10-24 — **API gates**: `./gradlew spotlessCheck spotbugsMain test --no-daemon` in `life-control-api` → **BUILD SUCCESSFUL** (2m 54s; spotbugs `total_bugs="0"`; the whole suite green, including the seven re-pinned Flyway suites and both product integration suites against Testcontainers/PostgreSQL).
- 2026-10-24 — **Angular gates**: `npm run lint` → exit 0 ("All files pass linting."); `npm run test:coverage:check` → exit 0, `Test Files 191 passed (191)`, `Tests 3871 passed (3871)`, and every threshold OK (statements 87.06%, branches 86.23%, functions 79.65%, lines 89.69%).
- 2026-10-24 — **RED before GREEN, API**: with the DTO/repository/service plumbing in place and the guards absent, the focused `ProductVariantServiceTest` run reported 58 tests, 12 failed — the 12 new measure-unit cases — while the 46 pre-existing ones stayed green.
- 2026-10-24 — **RED before GREEN, Angular**: the three new/updated specs failed to build with `TS2305`/`TS2307`/`TS2339` before the implementation existed; after it, the focused run was 71/71 and the feature suite 500/500.
- 2026-10-24 — **Differential check (documented, not a CLI run)**: the backend suites that share the touched code (`ProductVariantServiceTest`, `ProductVariantControllerTest`, `ProductVariantStoreFilterIntegrationTest`, `ProductVariantStoreStockWriteIntegrationTest`) keep their pre-existing assertions and add the new field to their fixtures; on the Angular side the payload-equality assertions in the form/dialog/variant-service specs are the differential check, since the change is client-side and has no CLI surface to run against the base commit.
- 2026-10-24 — Two integration tests were added beyond the literal task list because two of the new reads are raw HQL and derived-query semantics over real rows, which a mock cannot prove: the `unit_type` gate against real SQL, and both `D3` probes driven by a real stock edit (`0 -> 3 -> 0`) and a real ledger row. Fixtures are throwaway rows removed in teardown.
