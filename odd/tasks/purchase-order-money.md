# ODD feature: purchase-order-money

**Repository**: LifeControl — `life-control-api` (migrations, `purchaseorder`), `life-control-app-angular` (`features/purchases/purchase-orders`), `odd/tasks/`
**Branch**: `feat/purchase-order-money` · **Worktree**: `~/workspace/LifeControl-worktrees/feat-purchase-order-money`
**Status**: implemented — the unit is the purchase order gaining the money of a document: `purchase_orders` carries `currency`, `subtotal`, `tax_total` and `total_amount` derived by trigger from its own lines, `purchase_order_details.total` widens to `NUMERIC(19,4)` and stops being written by Java, with `subtotal` beside it holding the base, and the screen reads the server's total instead of summing rows in the browser with a hardcoded `MXN`. This slice adds no tax: `tax_total` derives to 0, and its successor `purchase-order-line-tax` fills it through the same derivation function. Nothing is deferred. Two things are recorded here and deliberately not taken: the inventory cost hole (`unit_price` never reaches `cost_price`) and the two tax shapes the product-sourced code cannot express, both named in the Log.
**Created**: 2026-11-10 · **Risk**: item 1 — the migration widens and **recomputes stored money on a populated table** (`purchase_order_details.total` moves by up to 0.005 per line, from 2 to 4 decimals) and adds three `NOT NULL` money columns to `purchase_orders`, one of them a `CHAR(3)` FK backfilled from `companies.currency`; a wrong derivation is not detectable after the fact. Item 6 does not apply because T4 and T5 give the new columns a reader in the same branch.

## Problem

A purchase order has no money of its own. The only amount in the module is a line total written by Java, so nothing can answer what the document costs, and the screen answers it by summing rows in the browser with a currency literal — a number the server never states and no other client can reproduce.

| Fact | Evidence |
| --- | --- |
| `purchase_orders` is created with no money column at all | `life-control-api/src/main/resources/db/migration/V1__baseline_schema.sql:317-329` — id, order_number, supplier_id, company_store_id, payment_method_id, status_id, comments, enabled, created_at, updated_at; every later ALTER (`V41`–`V72`) adds company, supplier FK, doc identity, status and RLS, never money |
| The line's `total` is written by Java and by nothing else | `purchaseorder/service/PurchaseOrderService.java:1038-1040` — `unitPrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP)`, called at `:243`, `:333`, `:446`, `:491` |
| No trigger derives money on the details | the only triggers on `purchase_order_details` are status-related: `trg_purchase_order_details_status_bridge` `V66__purchase_order_status.sql:395-397` and `trg_purchase_order_details_transition_recorded` `V69:84-87` |
| No document total exists anywhere in the backend | `dto/PurchaseOrderResponse.java` has no total field; `grep 'reduce\|\.sum(' purchaseorder/` finds only `PurchaseOrderDetail.getTotal()` `:90` and its response mapping `:1055` |
| The document has no currency and no date of its own | no `currency` on `purchase_orders`; `V64__document_identity_per_tenant.sql:187-188` states a purchase order's date is its `created_at` because no `order_date` column exists |
| The screen sums in the browser with a currency literal | `purchase-order-edit.ts:138-140` (`orderTotal` computed over `quantity * unitPrice`), `purchase-order-edit.html:76` (the `currency` pipe with the literal `'MXN'`), `detail-table.ts:26` (columns), `detail-table.html` (`rowSubtotal(row)` through the same pipe) |
| The shared money renderer already exists and sales uses it | `src/shared/pipes/money.pipe.ts:15,26,46-53` (`MONEY_DIGITS_INFO = '1.4-4'`, `MoneyPipe`); `sales-orders/components/sales-order-item-table/sales-order-item-table.html:87,95,103` |
| The company declares the currency the document should use | `V57__company_currency.sql`, and `AGENTS.md` § «Moneda de la empresa» |
| A line's `unit_price` never reaches inventory cost | `StockLedgerEngine.java:192` passes `storeStock.getCostPrice()`, `:822` sets `unitCost` from it; its only writer is `ProductVariantService.java:493` |

## Specs

**S1** — The purchase order states its own money. `purchase_orders` gains `currency CHAR(3) NOT NULL REFERENCES currency (code)`, `subtotal NUMERIC(19,4) NOT NULL DEFAULT 0`, `tax_total NUMERIC(19,4) NOT NULL DEFAULT 0` and `total_amount NUMERIC(19,4) NOT NULL DEFAULT 0`. All four are **derived**: no request field, no Java assignment. They live in a table `apply_tenant_isolation` already armed, so no policy changes in this unit.

**S2** — A line's money has one definition at four decimals, and the base and the total are two columns from the start. `purchase_order_details` gains `subtotal NUMERIC(19,4)`, and its existing `total` becomes `NUMERIC(19,4)`; both are derived in the database by one function, `subtotal = round(unit_price * quantity, 4)` and `total = subtotal + tax_amount`, where `tax_amount` does not exist yet and is 0 in this slice. Replacing the Java `setScale(2, HALF_UP)` write is what makes `total` derivable at all. The migration recomputes every populated row with that expression, and the declared cost is that a row's `total` may move by up to 0.005. The existing `total >= 0` check stays.

> Two columns and not one, because the successor adds a tax: a single `total` that later starts including tax is a column whose meaning changes under the rows already stored, and every document total would have to be re-derived a second time. `subtotal` is the base; `total` is what the line costs.

**S3** — The document's money follows its lines, in the database, on every mutation. Inserting, updating or deleting a `purchase_order_details` row recomputes `subtotal = sum(details.subtotal)`, `tax_total = sum(details.tax_amount)` (0 in this slice) and `total_amount = subtotal + tax_total` on the parent order, under that order's row lock. Two constraints hold it: `ck_po_order_total` (`total_amount = subtotal + tax_total`) and `ck_po_order_parts_not_negative`. Purchases has no discount concept, so no `discount_total` column is invented to mirror sales.

**S4** — The document's currency is the company's currency, derived, never declared. A client cannot state it, and changing a company's currency does not rewrite the currency of an order already created — the order keeps the currency it was written with, exactly as `V80` decided for a sale.

**S5** — The server owns the money on the wire. `PurchaseOrderResponse` gains `currency`, `subtotal`, `taxTotal` and `totalAmount`; `PurchaseOrderDetailResponse.total` keeps its name and carries the four-decimal value. `PurchaseOrderRequest` and `PurchaseOrderDetailRequest` are **unchanged** — none of the new fields is writable, and the existing strict deserialization refuses a request that tries to send one.

**S6** — The screen reads the server's money. The purchase-order page and its detail table render `totalAmount` and each row's `total` from the response through the shared `MoneyPipe` with the document's own `currency`. No `'MXN'` literal and no client-side sum remain in `features/purchases/purchase-orders`; while the document has no server answer (before it is created) the screen renders nothing rather than an inferred number.

**S7** — This slice states no tax. No `tax_code`, `tax_rate` or `tax_amount` column is added to either purchase-order table, `tax_total` derives to 0, and `total_amount` equals `subtotal`. The successor unit `purchase-order-line-tax` adds the columns and extends this same derivation function instead of replacing it.

**S8** — Nothing else moves. Routes, paths, status codes, the Draft guard (`loadAndValidateDraftPO`), `received_quantity`, and every RLS policy stay as they are; the only observable changes are the new response fields and a `total` that now carries four decimals.

## Tasks

The Flyway head is `V91`; this unit takes the next free pair.

| ID | Specs | Task | Depends | Route | Commit |
| --- | --- | --- | --- | --- | --- |
| T1 | S2, S7 | Migration: `purchase_order_details` gains `subtotal NUMERIC(19,4)`, `total` widens to the same type, and one function derives both (`subtotal = round(unit_price * quantity, 4)`, `total = subtotal + 0`) with its `BEFORE INSERT OR UPDATE` trigger, named so it cannot collide with the status bridge's name order (the lesson of `V85:69-71`), plus the recompute of populated rows | — | worker | — |
| T2 | S1, S3, S4 | Migration: the four columns on `purchase_orders`, `currency` derived from `companies.currency` for existing rows and on insert, the document derivation function with its `AFTER INSERT OR UPDATE OR DELETE` trigger on the details taking the order's row lock, and `ck_po_order_total` / `ck_po_order_parts_not_negative` | T1 | worker | — |
| T3 | S3, S6 | Scratch-database migration suite, shaped like `SalesMoneyBreakdownMigrationIntegrationTest` (OWNER/Flyway split, `migrateTo("91")` → seed → `migrateToHead()` → assert): a seeded order's derived total, a hand-written `total` refused, a line delete recomputing the document, and the recomputed `total` of a pre-existing row | T2 | worker | — |
| T4 | S5 | `PurchaseOrderResponse` and the detail mapping: the four fields, the currency string, and the response assertions in `PurchaseOrderControllerTest` | T2 | inline | — |
| T5 | S6 | Angular: the PO models, the header total and the detail table read the response through `MoneyPipe` with the document currency; delete `orderTotal` and `lineItemsTotal` and every `'MXN'` literal in the PO feature | T4 | inline | — |
| T6 | S6 | Angular specs: the total comes from the response, the client sum is gone, and no currency literal remains in the feature | T5 | inline | — |
| T7 | S1–S8 | Docs: the `AGENTS.md` rows that describe the purchase-order screen and its money, and this record's evidence log | T6 | inline | — |

**Total task count: 7.**

## Slices

The unit is larger than the ~400-authored-line review budget, so it travels as chained slices. Each boundary is
a place where the tree is coherent and the next slice can be reviewed on its own.

| Slice | Tasks | Scope | Why the boundary falls here |
| --- | --- | --- | --- |
| 1 | T1, T2 | the schema: `V92` and `V93` | The two migrations are one subject — the line's money and the document's — and a caller of one without the other is not a state anything reads |
| 2 | T3, T4 | the verification and the wire: the scratch-database suite and `PurchaseOrderResponse` | The suite is what answers what the migrations did to rows written before them, and the response is the first reader of the columns |
| 3 | T5, T6 | the screen: `features/purchases/purchase-orders` reads the response through `MoneyPipe` | Nothing above it changes, and it is the only slice that can be reviewed by looking at rendered numbers |
| 4 | T7 | the docs: the `AGENTS.md` section and this record's evidence | It states what the code does, so it goes last |

## Decisions

**D1 (2026-11-10) — the money is derived in the database, not in Java.** Sales keeps its document totals in `recomputeBreakdown` under the order's row lock, and `V80`/`V81`/`V90` already derive the parts that constraints check. Purchases has no equivalent writer at all, so a Java path would be the only thing standing between a line mutation and a stale document total — which is the silent-defect shape this unit exists to end. The trigger cannot be forgotten by the next route that adds a line.

**D2 (2026-11-10) — one definition of a line's money, so the migration recomputes the populated rows.** The alternative — keep the 2-decimal values and derive 4 decimals only for new writes — creates two truths for the same column and a document total that disagrees with its own history. The cost is declared, not hidden: a stored line total may move by up to 0.005.

**D3 (2026-11-10) — the currency is derived from the company and frozen on the order.** `V80` decided this for a sale; a purchase order that recomputed its currency from the company on every read would restate a historical document the moment the company changed its currency, which is the shape `V57` and issue #829 already had to repair once.

**D4 (2026-11-10) — no `discount_total` in this slice.** Sales carries one because a sale has discounts. A purchase order has no discount concept in the tree, and a column with no writer is the pattern `AGENTS.md` names as the defect to avoid (`variant_listing.enabled`).

**D5 (2026-11-10) — the tax is the successor unit, and the derivation is written so that adding it is additive.** `tax_total` exists from T2 because the constraint that will hold `total_amount` needs it, and the line already carries both `subtotal` and `total` because a single column cannot start meaning something else once rows exist. The successor `purchase-order-line-tax` adds `tax_amount` to the line and a term to the same function — one function, not a third trigger — and fills `tax_total` in place. Writing either column later would mean a second migration re-deriving money that was already derived once.

**D6 (2026-11-10) — the inventory cost stays out.** `purchase_order_details.unit_price` is never read on the receipt path; the cost that lands in `stock_valuation` comes from `product_variant_store_stock.cost_price`, written only by `ProductVariantService.java:493`. Connecting the two is a separate unit with its own accounting decision (whether a non-deductible tax belongs in cost), and this record states the gap instead of fixing it silently.

**D7 (2026-11-10) — the record is written before the first source write.** ODD step 5 requires the document to exist before the code, so the header states the unit's content and is final in the PR that carries it; the delivery evidence belongs to the dated rows of `## Log`.

## Log

**L1 (2026-11-10) — the request that opened the work.** «tambien analizamos agregar el iva a los productos y el desglose tiens algo de eso?» followed by «has el analisis del odd del iva en compras has el analisis y dame las opciones y decisiones que tomar».

**L2 (2026-11-10) — the four decisions, in the operator's own selected words.** «Del producto, como ventas» (the line's tax code comes from `products.tax_code`, as sales does); «Al crear la orden (como ventas)» (the rate is a snapshot taken with `created_at`, never re-resolved); «Sí, pero en un ODD anterior separado» (the document's money layer is this unit, the tax is its successor, so each fits the ~400-line review budget); «No, y se registra el hueco» (the inventory cost is not connected here).

**L3 (2026-11-10) — the measurement behind the problem table.** Two read-only explorations over the anchor at `282e9395`: the purchase-order schema has no money and no date of its own, the only money writer is `PurchaseOrderService.lineTotal` at `:1038-1040`, no trigger derives anything on the details, and the receipt path reads `product_variant_store_stock.cost_price` (`StockLedgerEngine.java:192`, `:822`) whose single writer is `ProductVariantService.java:493`. The screen's total is a client-side `reduce` at `purchase-order-edit.ts:138-140` rendered with a literal `'MXN'`.

**L4 (2026-11-10) — the two shapes the product-sourced code cannot express, recorded so they are not discovered in production.** (a) A supplier exempt on a taxable product: the product says `IVA`, the supplier invoices exempt, and the line computes a tax that is not owed — a valid input producing an invented deductible tax, invisible to every test. (b) Frontier VAT 8% beside the general 16%: both are the code `IVA`, and `uq_tax_rate_company_code_from UNIQUE (company_id, tax_code, effective_from)` (`V82__tax_rate.sql:61`) plus `findFirstBy…EffectiveFromDesc` (`TaxRateRepository.java:44-45`) allow exactly one rate per code on one date, so there is nowhere to state the 8%. Both belong to the successor unit's gaps; neither is resolved here. The mitigation carried forward is that the resolution fails **visible** — an `IVA` line with no enabled rate on the document's date is a 400 naming the code and the date, which is already the sales rule (`SalesOrderService.java:1090-1094`).

**L5 (2026-11-10) — the hole this unit does not close, stated so it is not read as covered.** What a purchase paid does not determine what the inventory costs: `purchase_order_details.unit_price` is read by nothing on the receipt path, and `goods_receipt_items` carries quantities only (`V12__goods_receipts.sql:39-48`). A purchase order with a correct total and an inventory carrying a different cost is a state this tree can already reach.

**L6 (2026-11-10) — the successor, named and not written.** `purchase-order-line-tax` adds `tax_code`, `tax_rate` and `tax_amount` to the line, copies them from `products.tax_code` resolved through `TaxRateService.resolve(companyId, taxCode, createdAt.toLocalDate())` — the same service and `lineTaxAmount` helper that exist today with **no production caller** — moves `ZERO_RATE_CODES` out of `SalesOrderService.java:116` into the tax service where the rule belongs (`TaxCodeService.java:32` already says it does not carry it), and extends the derivation function of T1/T2 in one function rather than adding a third trigger, because same-event triggers fire in name order (`V85:69-71`). Its four tests mirror `SalesOrderServiceTest:4135/4182/4165/4203`.

**L7 (2026-11-10) — the session that answered the question that opened it.** `TaxRateService`, `tax_rate` (armed, per company) and the `tax_code` vocabulary were delivered by `odd/tasks/product-tax-code.md`, which is in `main`; the same session confirmed that purchases has zero occurrences of `tax` in its Java module and no tax column in any purchase-order migration.
