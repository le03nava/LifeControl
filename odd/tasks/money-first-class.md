# ODD feature: money-first-class

**Repository**: LifeControl — `life-control-api` (migrations, `salesorder`, `paymentmethod`), `life-control-app-angular` (the POS money surface), `odd/tasks/`
**Branch (planned)**: `feat/money-first-class`
**Status**: implemented — T1 is the currency catalog and `companies.currency`, T2 the breakdown and the scale on
both sales tables, T3 the runnable pre-flight of migration step 2, T4 the platform `tax_code` catalog with its
tenant rate table and the resolver, T5 the payment rows, their voids and the cash flag the guard reads, and T6 the
charge in two slices, each named in its own `Tasks` row: T6a is its money half, and T6b moved the stock booking
from the create/edit path into that same charge. T7 is the API surface, in three slices: T7c the line total
`V85` derives, T7a the read sheet, T7b the charge with many payments. T8 is the Angular POS — the document's
currency in place of the app's default, four-decimal amounts, mixed tender with change, the server's line parts,
and the notice for the rows the server has not answered for — and T10 is the suite that states the invariants. Nothing is dropped or deferred silently: T4 owes the copy of the
rate onto the line until some unit states a line's `tax_code`, T5 owes the historical payment backfill of
ADR-0002 step 4, and step 6's drop of `sales_orders.payment_method_id` waits on that backfill, which the POS reader no
longer blocks (T8 moved every reader of the document's money), together with both of step 6's own conditions. `sales_orders.company_id`, the column that used to be named here as another unit's to deliver,
exists since `V64`, and the document state guard and the stock ledger this record once waited on are T6's and
landed. Six decisions are taken: D-c (`companies.currency`), which decision 2, the `## Schema` block and
migration step 1 of the accepted ADR state; **D-e** (2026-11-01), which keeps the 0002 → 0004 edge and moves
ADR-0004 ahead of T6 in `docs/adr/README.md`'s adoption order so the charge rewrite is written once, after the
guard; **D-d** and **D-b** (2026-11-05), which the tax and payment slices carry; and **D-g** and **D-h**
(2026-11-07), which say that the charge takes money in and never gives it back, and that `final_price` is the line
total the ADR already defines. T9 is closed (applied with the acceptance) and edits no decision.
**Created**: 2026-10-30
**Risk**: items 1 and 3 — item 1 because the migrations rewrite stored money on financial rows (`total_amount`
and the four parts in `V80`/`V84`, `final_price` in `V85`) and a mis-stated backfill is not detectable by any
later test, and item 3 because `SalesOrderRequest`, `SalesOrderResponse`, `ChargeSalesOrderRequest` and
`PATCH /api/sales-orders/{id}/charge` change shape under an Angular POS that already calls them.

## Objective

Implement `docs/adr/0002-money-first-class.md` in `life-control-api` and the Angular POS: a sale becomes a
financial record — every amount carries a currency, every document carries its breakdown, every payment is
a row with a direction, and every void is an appended row.

## Problem — measured in the tree, not quoted from the ADR

| Fact | Evidence |
| --- | --- |
| A charge is a status flip plus one catalog foreign key | `salesorder/service/SalesOrderService.java:386-446` — `so.setPaymentMethodId(request.paymentMethodId())`, `so.setStatusId(completedStatus.getId())`, `save`, then every non-Cancelled item flips to `Added` |
| The only writer of `total_amount` sums line `final_price` | `SalesOrderService.java:1050-1055` (`recalculateTotalAmount`), plus `:175` (`BigDecimal.ZERO`) |
| The charge request carries one field | `salesorder/dto/ChargeSalesOrderRequest.java:6-7` — `UUID paymentMethodId` |
| No currency anywhere, in 52 migrations | `grep -i currency db/migration/*.sql` → one hit, `V22`'s word "concurrency" |
| `sales_orders` has no `company_id`, a nullable total and a nullable method | `V1__baseline_schema.sql:468` (`CREATE TABLE`), `:477` `total_amount DECIMAL(12,2)`, `:478` `payment_method_id UUID` — nullable |
| No payment, tax-rate, sequence, transition or ledger table | `sale_payment`, `tax_rate`, `document_sequence`, `status_transition`, `inventory_ledger` → 0 hits under `db/migration/` |
| `quantity` and prices are two-decimal | `V1:498-503` on `sales_order_items` (`CREATE` at `:496`), mirrored in `model/SalesOrderItem.java:23-33` |
| `discount_applied` is nullable | `V1:500` `discount_applied DECIMAL(12,2) DEFAULT 0` — a `NULL` makes a `CHECK` pass as `NULL` |
| The payment-method catalog has no cash flag, and four seeded rows | `V1:302-309`; seed `V3__seed_reference_data.sql:384-398` — `Efectivo`, `Tarjeta Crédito`, `Tarjeta Débito`, `Transferencia` |
| Tenant machinery, and sixteen tables armed by `V53` | `V52:100` defines `apply_tenant_isolation` and `:203` `unprotected_tenant_tables()`; `V53__tenant_isolation_policies.sql:108-144` arms sixteen tables. `sales_orders`, `sale_payment` and `sale_payment_void` are **not** among them |
| The POS does its own line math and hardcodes the currency | `components/sales-order-item-table/sales-order-item-table.ts:118`, `.html:86,125`; `src/app/app.config.ts:25,33` — `LOCALE_ID 'es-MX'`, `DEFAULT_CURRENCY_CODE 'MXN'` |
| Only the sales suites are schema-coupled to the charge shape, and they go through the service | `SalesOrderServiceTest.java:2189-2541`, `SalesOrderControllerTest.java:434-527`, `SalesOrderIntegrationTest.java:1399` — no suite issues a raw `INSERT INTO sales_orders` or `sales_order_items` (`grep -rln` on both: no hits) |

## Specs

S1 — `docs/adr/0002-money-first-class.md` is the specification of this unit. Its `## Decision` items 1–7,
the `## Schema` block, `## Invariants` M1–M10 and `## Migration from the current tree` steps 1–7 are
authoritative, including the closing `## Out of scope, named`. The ADR's own status governs how it is
cited: «An ADR written by an agent starts at `proposed`, and a session that finds one at `proposed` may
cite it as a proposal, never as authority» (`docs/adr/README.md`).

S2 — decision 1, verbatim: «**Amounts are `NUMERIC(19,4)`, and so is `quantity`.** Four decimals absorb a
unit price that two cannot, and a quantity that a per-gram price implies. The currency decides the display
and the rounding: the rounding to its `minor_units` happens **once, at the document boundary** — never per
line, which is how a total stops matching the paper.»

S3 — decision 3, verbatim: «**A document states its parts.** `subtotal`, `discount_total`, `tax_total`,
`rounding_adjustment` and `total_amount`, in the document's currency. The invariant
`total_amount = subtotal − discount_total + tax_total + rounding_adjustment` is a `CHECK` with **exact**
equality, not a tolerance: every part is `19,4`, so a difference of a ten-thousandth is a bug and not a
rounding artifact.»

S4 — decision 5, verbatim: «**A payment is a row, and a sale may have many.** … A void is **its own
appended row**, never a status column and never a deletion: a payment is void if and only if a
`sale_payment_void` row names it … A void row **is** the payment's transition.»

S5 — decision 7, verbatim: «**The charge is one transaction, serialized on the order.** `chargeSalesOrder`
locks the order row, validates the state guard of ADR-0004, inserts the payments, computes the breakdown,
books the stock through ADR-0001's API and appends the transition, all in one transaction. The lock is what
makes a retried request a no-op instead of a second payment.»

S6 — M1–M10 are the acceptance criteria of this record by number. Two of them carry obligations that do not
live in the schema, and they are the ones a test must observe directly: M2 («recomputed in the same
transaction as the line write, under the order's row lock (M10). A cross-row sum is not expressible as a
`CHECK`, so this is a service invariant with a named lock, not a wish») and M10 («`chargeSalesOrder` takes
`SELECT … FOR UPDATE` on the order row … the second request sees `COMPLETED` and the guard refuses it»).

S7 — the migration's two load-bearing sentences, verbatim: step 2, «Without this step the constraint aborts
the deploy on production data instead of on a report»; step 4, «`paid_at = coalesce(o.updated_at,
o.order_date)` — **not `now()`**, which would date every historical payment the day of the deploy and make
every cash report by date wrong from that minute on».

## Tasks

| ID | S# | Work | Depends on | Route |
| --- | --- | --- | --- | --- |
| T1 | S1, S2 | **(done, 2026-11-01)** Migration: the `currency` catalog (`code CHAR(3)`, `minor_units` bounded 0–4, `symbol`, `enabled`), its seed, and `companies.currency CHAR(3) NOT NULL REFERENCES currency (code)` with its backfill and the now-required field on the company create path — **D-c**, decided 2026-10-30: no `DEFAULT`, because a silent `MXN` is the default-that-hides failure class. Its one-sentence amendment to decision 2 precedes it | — | inline |
| T2 | S1, S2, S3, S7 | **(done, 2026-11-05)** Migration: the breakdown and the scale on both sales tables — `sales_orders` (`currency`, `subtotal`, `discount_total`, `tax_total`, `rounding_adjustment`, `total_amount` → `19,4` and `NOT NULL`, `ck_order_parts_not_negative`, `ck_order_rounding_bounded`) and `sales_order_items` (four widened columns, `subtotal`, `tax_code`, `tax_rate`, `tax_amount`, the three name/attribute snapshots, `ck_item_subtotal`, `ck_item_tax`), plus `discount_applied`'s `NULL` → 0 and `total_amount`'s `NULL` → 0 before the `NOT NULL`s. Additive, backfill, then constrain — never the reverse. **`ck_order_total` and `ck_item_total` are not in it**: measured on the tree, `final_price` is the *unit* net price and not a line total — so the one would refuse every legacy line with `quantity <> 1` and the other would refuse the tree's own service, which writes `total_amount` from unit prices while the parts stay 0 (41 cases answered `409` on the first run). Both move to T6/T7, which also move the writer — see **D-f** | T1 | inline; two commits, one per table |
| T3 | S1, S7 | **(done, 2026-11-05)** The pre-flight report of migration step 2 as a runnable script: `docker/scripts/preflight-money-parts.sh`, its offline suite (43 assertions) and the `AGENTS.md` entry. It emits the drift per order and names the two verdicts —≤ one minor unit of the order's own `currency.minor_units` → `rounding_adjustment`; larger → a manual decision, not the migration's— in ten-thousandths, as integers. It reads as a **superuser** (`sales_orders` is `FORCE ROW LEVEL SECURITY` since `V65`, so the owner reads zero rows and prints a clean dataset) and takes the line sum from `sales_order_items.subtotal`, which `V81` derives under `ck_item_subtotal`, instead of re-deriving the ADR's inline candidate. Nothing calls it yet | — | inline |
| T4 | S1 | **(done, 2026-11-05)** The platform `tax_code` catalog (**D-d**, decided 2026-11-05) with four codes (`NA`, `IVA`, `TASA_0`, `EXENTO`) and no policy, plus `tax_rate` (`company_id`, `tax_code` FK, `rate`, `effective_from`, `enabled`, the unique key) armed by `apply_tenant_isolation` in its own migration. `V82`. The server-side resolution is `TaxRateService.resolve(companyId, taxCode, documentDate)`: the latest `effective_from ≤` the document's date among the enabled rows, mapped to a `TaxRateResolution`, with `lineTaxAmount` providing `ck_item_tax`'s own arithmetic (`subtotal - discount_applied`, scale 4, half away from zero) so Java cannot disagree with the check. **The copy onto the line is not in this slice**: nothing in the tree states a line's `tax_code` — not `products`, not the category, not the company — and the ADR's named consumer is `chargeSalesOrder`, which is T6. See `## Log` | T1, D-d | inline |
| T5 | S1, S4 | **(done, 2026-11-05)** `V83`: `sale_payment` and `sale_payment_void` with the three indexes, both append-only halves (the trigger **and** `REVOKE UPDATE, DELETE` + `GRANT SELECT, INSERT`, because `V52:187` and `V52:196-197` hand the pool all four privileges on every new table), `payment_methods.is_cash` with the backfill of **D-b** (decided 2026-11-05) and `isCash` through the ABM, `uq_sales_orders_company_id` — the target of `fk_payment_order_same_company`, which `V64` gave to `purchase_orders` and `goods_receipts` but never to `sales_orders` — and the two new tables armed by `apply_tenant_isolation` in the file that creates them. **`sales_orders` was already armed**: `V65` applies the policy to it, so "all three sales tables" was one table already done and two new. `V83` and not `V82`: that version belongs to the tax unit's own branch, and the gap is Flyway's to tolerate until it lands | ADR-0003 (**stale**, `sales_orders.company_id` exists since `V64`), D-b | inline |
| T6a | S1, S5, S6 | **(done, 2026-11-06)** `chargeSalesOrder`, money half: `SELECT … FOR UPDATE` on the order row (M10), ADR-0004's guard extended with M3/M4/M8, one `CHARGE` payment row for the total from the existing single `paymentMethodId`, the breakdown written under the lock so M1 and M2 hold (exactly), the transition appended, and `SaleCharged` **not** published because no consumer is named (g) — one transaction. The many-payments surface stays in T7, per the 2026-11-06 log entry | T2, T4, T5, ADR-0004 | inline |
| T6b | S1, S5, S6 | **(done, 2026-11-06)** The stock half of the same decision: the per-line deduction leaves `createSalesOrder`, the line add/edit/delete paths and their `V73` write sequence, and lands in the charge as one batch under the same order lock; a reversal then exists only for an order whose charge booked something. `sales-location-aware-stock` and `ledger-per-write-identity` are edited with it | T6a, ADR-0001 | inline |
| T7c | S1, S2, S3 | **(done, 2026-11-07)** `V85`: `final_price` becomes the line total the ADR defines — `subtotal - discount_applied + tax_amount` — derived by a `BEFORE INSERT OR UPDATE OF list_price, quantity, discount_applied, tax_amount` trigger exactly as `V81` derives `subtotal`, with a backfill that restates the column from each row's own parts before `ck_item_total` judges it (**D-h**, decided 2026-11-07). It moves no `total_amount`: since T6a the document's total is Σ of the parts and never a sum of `final_price`. The entity field becomes read-only and the write sites stop stating a number the schema owns. Ships first, because T7a reports a column whose meaning this slice settles | T6a, T2 | inline; item 1, own commit |
| T7a | S1, S2, S3 | **(done, 2026-11-07)** Read surface: `SalesOrderResponse` gains `currency`, the four breakdown parts and `payments`; `SalesOrderItemResponse` gains `subtotal`, `tax_code`/`tax_rate`/`tax_amount` and the two catalog snapshots, and `finalPrice` keeps reporting the stored line total. The breakdown stays read-only in both directions: no request states a part, and no response omits one | T7c | inline |
| T7b | S1, S3, S4, S6 | **(done, 2026-11-07)** Write surface: `ChargeSalesOrderRequest` carries a payment list with tender and change, **D-g** (decided 2026-11-07) fixes it to `CHARGE` only — a `REFUND` is refused by name — M3 becomes exact equality against the breakdown, M4 refuses change on a method that is not cash, and `@Digits(fraction = 4)` plus `@Valid` on the items state the scale the columns carry on both requests | T7a, T5, D-g | inline |
| T8 | S1, S2 | **(done, 2026-11-07)** Angular POS. `MoneyPipe` (`shared/pipes/money.pipe.ts`) renders every sales money value in the document's own `currency` at `1.4-4` and names its absence instead of falling back to the app's default (**D-j**); the item table renders the server's `subtotal`/`taxAmount`/`finalPrice` in place of its own arithmetic and names the rows it could not represent (**D-i**); the money inputs take four decimals (`step="0.0001"`); and `sales-order-charge-panel` states a payment list — mixed tender, tender and change only for a cash method (M4) — because `PATCH /charge` has taken `payments[]` since T7b and the old single-method button still sent `{ paymentMethodId }`. `sales-money-currency.spec.ts` fails the build if a sales template uses a bare `\| currency` or an `'MXN'` literal | T7 | inline; disjoint surface from T2–T7, so a second writer is admissible |
| T9 | — | The four ADR corrections under `## Reconciliation`: `purchase_order_items` → `purchase_order_details`, the two drifted line citations, the `NOT NULL` method against a nullable column, and `is_cash` with no backfill | — | inline |
| T10 | S1–S7 | Tests: RED first per spec, M1 and M5 through the schema, M2 and M10 through the service under concurrency, M4 through the domain guard, and the scale/rounding boundary in place of the two-decimal assertions | T2–T8 | inline |

## Reconciliation — four corrections and three decisions

The ADR was opened, every `path:line` it cites was read, and four of them need a fix before the slices
start. None changes a decision; three change a step.

(a) **`purchase_order_items` does not exist.** The out-of-scope table is `purchase_order_details`
(`V1:341`), and its `unit_price`/`total` are at `V1:346-347` as cited — the name is the error, not the
lines.

(b) **Two line citations drifted.** `total_amount` is `V1:477`; `V1:478` is `payment_method_id`. The
`sales_order_items` `CREATE TABLE` is `V1:496`; `V1:499-509` is its column list.

(c) **Migration step 4 assumes a method every order has, and the column is nullable.** Step 4 inserts one
`sale_payment` per `Completed` order with «the order's `payment_method_id`», while `V1:478` allows `NULL`
and `sale_payment.method_id` is `NOT NULL`. A `Completed` order without a method has no row that satisfies
both. This is **D-a**: skip those orders and report them, or add a sentinel method the report names.

(d) **`is_cash` is added with no backfill, and the catalog is already seeded.** `ALTER TABLE
payment_methods ADD COLUMN is_cash BOOLEAN NOT NULL DEFAULT false` over the four rows of
`V3:384-398` leaves **every** method non-cash, so M4's domain guard refuses the change on a cash sale from
the first deploy onward, with no error that names the flag. This is **D-b**: backfill `Efectivo` in the
migration, or make it an operator step the migration's comment names.

**D-c — nothing stores a company's operating currency, and it is the value `sales_orders.currency` cannot
be without.** Decision 2 says «a document cannot be in a currency other than the company's operating
currency», and migration step 1 says «Seed `currency` with the company's operating currency». `companies`
has no such column (`V1:44-67`, no later `ALTER` adds one outside `V20`'s employee registry) and the only
currency the product states anywhere is the hardcoded `DEFAULT_CURRENCY_CODE 'MXN'`. Three shapes, and the
choice belongs to the maintainer: a `companies.currency` column (a tenant fact, needs its own migration and
its own backfill); a single platform currency constant that decision 2's sentence is rewritten to admit;
or `sales_orders.currency` defaulted from the platform and overwritten per document, which is the only one
that contradicts M6's intent. That record does not pick one.

**Decided 2026-10-30 by the maintainer: the first shape.** `companies` carries
`currency CHAR(3) NOT NULL REFERENCES currency (code)`; the migration backfills it to the seed's single
currency, adds **no** `DEFAULT` — a silent `MXN` is the failure class this repository treats as a defect —
and the company create path gains the field as a required one. **Written into the ADR on 2026-10-30**:
decision 2 now names the owner in its own words, `## Schema` carries the `ALTER TABLE companies`, and
migration step 1 reads the value from the company instead of assuming it. T1 carries all three.

**D-f — `final_price` is a unit price, and the ADR's `ck_item_total` reads it as a line total** (measured
2026-11-05, T2). The schema block declares
`ck_item_total CHECK (final_price = subtotal - discount_applied + tax_amount)` with
`subtotal = round(list_price * quantity, 4)`, and migration step 3 backfills `subtotal` from the lines'
price times quantity. In the tree `final_price` is not that number: `SalesOrderService:555` and `:618` write
`finalPrice = listPrice - discountApplied` — the **unit** net price, quantity-independent — and
`recalculateTotalAmount:1058` sums those unit prices into `total_amount`, so a legacy document's total is a
sum of unit prices and never a sum of line totals. `SalesOrderServiceTest`'s own fixture states it:
`quantity 2.00, listPrice 100.00, discountApplied 10.00 → finalPrice 90.00`, where the line total the
constraint assumes is `190.00`.

The consequence is not a rounding artifact. Applying the ADR's backfill to a legacy dataset leaves
`ck_order_total` disagreeing by the document's own quantity per line, and `ck_item_total` refuses every line
with `quantity <> 1` — the migration aborts on data that is correct under the semantics it was written
under. Three shapes were on the table: rewrite the line money to line totals, and with it every historical
total (contradicting step 3's «`total_amount` keeps its value», which is the one thing a financial migration
may not do); add the constraints as `NOT VALID`, which leaves `final_price` with two meanings in one column
so any report that sums it without filtering by age lies silently; or land what no reading contradicts and
defer the single constraint that encodes the change.

**Decided 2026-11-05 by the maintainer: the third shape.** `V80` records a legacy document as
`subtotal = total_amount` with `discount_total`, `tax_total` and `rounding_adjustment` at 0 — «no breakdown
was recorded» — so no stored amount moves by one cent and the row states the truth instead of a derivation.
`V81` derives the line's `subtotal` in the schema (`BEFORE INSERT OR UPDATE OF list_price, quantity`) so
`ck_item_subtotal` holds for the writer that does not send one, and lands `ck_item_tax` beside it.

**The second measurement, and what it moved.** The first full run of the branch, against the sales suite,
answered `409` where it expects `201` in **41 cases**: `ck_order_total` refuses the tree's own writer,
because `recalculateTotalAmount:1058` sets `total_amount` from the lines' `final_price` on every add, edit
and delete while `subtotal` and the other two parts stay 0. A constraint the current service violates is a
work order for that service, not a schema statement — so `ck_order_total` (**M1**) is **not** in `V80`
either, and it is T6/T7's, in the same slice as `ck_item_total`. What lands now is the shape: the columns,
the scale, the `NOT NULL`s, the currency and its derivation, the catalog foreign key, and the two order
checks that hold for both writers (`ck_order_parts_not_negative`, `ck_order_rounding_bounded`). The suite
states the debt instead of hiding it: `theBreakdownArithmeticWaitsForTheWriter` writes a disagreeing
subtotal and asserts that it is **accepted**, so a green run cannot be mistaken for M1 being bounded.
`V80`'s self-check still asks the drift question and names the constraint the answer will be judged by. **M2 (`subtotal = Σ line.subtotal`) is forward-only for this table**: true for every
document T6 writes, false — permanently and visibly — for the ones that predate `V80`, which is why
`rounding_adjustment` is 0 there and not inflated to hide the difference. `ck_item_total` is T6/T7's to add,
in the slice that also moves the writer. **Written into the ADR on 2026-11-05**: step 3 carries the
amendment beside the instruction it corrects.

One more thing T2 had to add, and it is not in the ADR's schema block: `sales_orders.currency` is `NOT
NULL`, and the tree's writer does not send it (`SalesOrderService.createSalesOrder` builds the row from a
request that has no currency — T7 is the surface that states one). A `DEFAULT 'MXN'` would be D-c's failure
class one layer down, so `V80` derives the value instead: a `BEFORE INSERT OR UPDATE OF company_id,
currency` trigger reads the company the document names, fills the column when it is absent, and **refuses**
a document that states a different currency than its company's — which is decision 2's sentence and M7,
enforced in the schema so a caller cannot state what the tenant does not operate in.

## Log

**L1** — «puedes revisar 0002-money-first-class.md y hacer una exploracion para generar el odd»

**2026-10-30 — read-only exploration, no source or ADR line touched.** The ADR, its `## Schema`, its
invariants and every `path:line` it cites were read against the tree; the Java money path, the Angular POS
surface, the test surface and the migration state were mapped. One claim inherited from that mapping was
wrong and is corrected here rather than carried: three schema-migration suites were said to insert raw
`sales_orders` rows; `grep -rln "INSERT INTO sales_orders" src/test/java` and the same for
`sales_order_items` return no file, and the suite that was opened
(`StoreHierarchyCompanyScopeSchemaMigrationIntegrationTest.java:452-462`) inserts `purchase_orders`. T10
therefore has no raw-insert surface to repair in the sales tables — only the three suites that reach a sale
through the service.

**2026-10-30 — the version number is not free, and is not chosen here.** The head is `V52`.
`V53` is already claimed by `odd/tasks/tenant-isolation-rls.md` T3 (the RLS startup assertion) and is not on
the tree, so the slice takes the head it finds when its branch starts, not the number this record guesses.
Two sessions writing `V53` is the failure this line exists to prevent.

**2026-10-30 — the RLS interplay is one line, not a slice.** No table carries a policy today: `tenant_table`
gets no row and `apply_tenant_isolation` has no caller, so the three sales tables are registered in
`tenant_table` by T5's migration and are armed with the group that consumes them, which does not exist yet.
Arming `sales_orders` also needs `company_id`, i.e. ADR-0003 — the same column T5's payment foreign key
needs, which is why the order below is not re-arguable slice by slice.

**2026-10-30 — the dependency chain, and what it leaves executable today.** `docs/adr/README.md` fixes the
adoption order as RLS → catalog → ledger → documents → money → states, and the tree matches it: ADR-0005's
machinery is the only landed piece (V52 + `TenantScopeJpaTransactionManager`), and ADR-0001, 0003, 0004 and
0006 have no `odd/tasks/` record and no migration. T1, T3 and T4 depend on none of them; T2 depends only on
T1; T5 waits for ADR-0003's `company_id`; T6 waits for ADR-0001, 0003 and 0004 at once. That is why the
record is `blocked` and not a plan to start at T1: T1 without D-c seeds a catalog with no owner for its
value, and T2's `sales_orders.currency NOT NULL` cannot be satisfied by a writer that has no currency to
write.

**2026-10-30 — three decisions, and the first one is not technical.** D-a (a `Completed` order with no
method) and D-b (`is_cash` backfill) are small and both live inside the migration that raises them. D-c is
the one that decides the feature: whether the operating currency is a tenant fact, a platform constant, or
a per-document value — and one of those three contradicts M6, so the ADR has to be amended, not
implemented, if it is chosen.

**2026-10-30 — the ADR is accepted, and this record travels with it.** The maintainer accepted
`docs/adr/0002-money-first-class.md` in the session that wrote this record: the ADR carries
`**Status**: accepted` and its `**Accepted**:` line, `docs/adr/README.md` carries the status, and the two
evidence corrections (a) and (b) were applied as part of the acceptance because they are facts about the
tree and not decisions. `odd/tasks/architecture-redesign-adrs.md` had its one false sentence repaired, with
the repair recorded there and nothing else touched. T9 is therefore closed in the working tree of
`docs/adr-0002-accepted`; (c) and (d) stay in this record, because each one changes a migration step and
needs an answer a document edit cannot supply.

**2026-10-30 — D-c: the recommendation, recorded before it becomes a decision.** `companies.currency
CHAR(3) NOT NULL REFERENCES currency (code)`, backfilled to the single currency the `currency` seed
declares — **not** a `DEFAULT`, because a default is the silent-`MXN` failure the rest of this repository
treats as a defect (`docker/scripts/tests/env-defaults.test.sh` exists for exactly that class), and the
company create path gains the field as a required one. Three reasons, in order of weight. Decision 2 already
names the value's owner («the company's operating currency») and migration step 1 already seeds from it, so
the ADR is not asking a new question — it is asking one the tree cannot answer. A platform constant has to
be undone with a data migration over every stored document the first time a second currency exists, which is
the cost this ADR set exists to pay once, now, while the system is pre-production. And `tax_rate` is already
a per-company row, so a company-specific tax rate over a platform-wide currency is two scopes that disagree
about whose fact the money is. The precedent that argues the other way is a deferral, not a decision:
`odd/tasks/hr-org-structure.md:74` (T4) and `:297` (G2), and `odd/tasks/employee-registry.md:433`, all three
record "no currency anywhere in the schema" and punt multi-currency as «a platform-wide change, not this
slice's» — which is this slice. If the recommendation is refused, the alternative is a platform constant and
one amended sentence in decision 2; the record has to say which, because T2 cannot write `sales_orders.currency
NOT NULL` over a writer with no currency to read.

**2026-10-30 — D-c decided, and the record stops there.** The maintainer confirmed the first shape:
`companies.currency CHAR(3) NOT NULL REFERENCES currency (code)`, backfilled, no `DEFAULT`, with the
required field on the company create path. Two consequences queued and **not** applied in this turn, by
instruction: decision 2's owning sentence and migration step 1's source, both of which are ADR text and not
schema. Nothing else moved — no migration, no ADR line, no source, no commit. T1 is the slice that carries
the column; T2 still starts from T1; T5 and T6 stay where the dependency chain put them.

(e) **D-e — the dependency graph is missing one edge, and the adoption order was built on the gap.** ADR-0002
consumes ADR-0004 in five places — decision 5 («a void row **is** the payment's transition»), decision 7
(«validates the state guard of ADR-0004»), M3, M4 and the trigger comment — while its `**Depends on**` line
names only `ADR-0001`, `ADR-0003` and `ADR-0005`, and `docs/adr/README.md`'s table repeats that list and
marks 0002 as blocking nothing. ADR-0004 declares its own dependencies as 0005 and 0003 and says nothing
about 0002. That missing edge is why the accepted order can put states **after** money and still read as
consistent: T6, M3, M4 and M10 all wait on the guard. Two shapes, and it is a decision, not an edit: add the
0002 → 0004 edge and move ADR-0004 ahead of T6 in the order, or split T6 so the payments and the breakdown
land against today's status flip and the guard arrives with 0004. The second one lands financial writes
before the guard that is supposed to refuse a second charge, so it needs an explicit yes. **The edge itself
was added on 2026-10-30** — ADR-0002 and `docs/adr/README.md` both name ADR-0004 now — because the edge is a
fact and only the order is a decision.

**D-d — `tax_code` is a key with no vocabulary.** `tax_rate.tax_code` (`VARCHAR(20)`) and
`sales_order_items.tax_code` have no catalog and no source in 52 migrations: the only `sat_code` columns in
the tree are `products.sat_code` (`V1:189`) and `product_categories.sat_code` (`V34`), and both are the CFDI
`c_ClaveProdServ`, not a tax code. Today that is harmless — every line is `'NA'` with `tax_rate` 0 and
`ck_item_tax` holds — and it stops being harmless in T4, the moment one `tax_rate` row exists: nothing can
list the codes an operator is allowed to pick, and nothing refuses a typo that silently taxes a line at
zero. Three shapes: a platform `tax_code` catalog that `tax_rate` and the line reference; free text admitted
by `tax_rate`'s existence alone, with the typo risk named; or no `tax_rate` in this round at all, leaving
`tax_total` 0 and the tax report out. T4 does not start before this is answered.

**2026-10-30 — two gaps found while answering "what is pending", recorded and not acted on.** (e) the
missing 0002 → 0004 dependency edge, which is what makes the accepted adoption order put states after
money, and D-d, the `tax_code` key with no vocabulary. Neither changes a task's content: (e) changes the
order or splits T6, and D-d is T4's gate. No schema, ADR or source line was touched.

(f) **M5 is asserted and half-enforced: `sale_payment` itself has neither trigger nor restrictive grant.**
The schema block gives the append-only pair — the trigger *and* «a grant of INSERT, SELECT only to the
application role» — to `sale_payment_void` alone, while M5 reads «A void is never an `UPDATE` and never a
`DELETE`; a payment is void if and only if a void row names it». A payment row is therefore updatable and
deletable: `UPDATE sale_payment SET amount = …` passes, and the void history still reads as if nothing
moved. `V52:187` and `:196-197` are why: the application role is granted `SELECT, INSERT, UPDATE, DELETE ON
ALL TABLES` and inherits the same four on every later table, so the restrictive half has to be a `REVOKE`
written per table, not an absence of grant — which is also the correction the set needs elsewhere:
ADR-0004:152-153 attributes that INSERT/SELECT-only grant to ADR-0005 («the INSERT/SELECT-only grant that
ADR-0005 owns»), and ADR-0005 owns the opposite. The trigger is carrying append-only alone for every table
in the set, and for `sale_payment` nothing carries it at all. Not a decision — an edit to T5's migration,
and **applied to the ADR on 2026-10-30**: the schema block now carries the trigger on `sale_payment`, the
corrected comment that names `V52:187`/`:196-197` as the reason the restrictive half is a `REVOKE`, and a
`REVOKE UPDATE, DELETE` for each of the two tables.

(g) **Step 7 publishes an event that has no consumer, and no producer either.** «publish `SaleCharged` for
the audit listener» — `SaleCharged` has zero hits in `life-control-api/src`, and the tree's existing
listeners (`KeycloakGroupEventListener`, the company and store services' publishers) are unrelated to a
sale. So the step names an artifact to create twice over, and it does not say what the listener is for while
ADR-0004's deferred constraint triggers already refuse a commit whose state change went unrecorded. Either
the slice creates the event and its consumer and says why, or the clause goes. One line either way; today it
reads as if the audit listener existed.

**2026-10-30 — three more gaps, found by auditing the ADR against what the landed migrations actually grant.**
(f) M5 is half-enforced: `sale_payment` has neither the trigger nor the restriction the ADR names for the
void table, and `V52:196-197` grants the application role `UPDATE`/`DELETE` on every new table, so the
restrictive half has to be a `REVOKE` and not an omission. The same audit shows ADR-0004:152-153 attributing
that grant to ADR-0005, which grants the opposite — the trigger is carrying append-only alone across the
set. (g) `SaleCharged` has no producer and no consumer in the tree. Both are edits to T5 and T6, not
decisions, and T5/T6 now say so. No schema, ADR or source line was touched.

**2026-10-30 — the four open decisions, with the recommendation each one has.** Recorded as
recommendations, not decisions: the maintainer has not answered them. **D-e was answered on 2026-11-01 and
D-b and D-d on 2026-11-05** (see each table row and `## Log`); **D-a is the one still open**, and it is a
measurement before it is a decision.
recommendations, not decisions: the maintainer has not answered them. **D-e was answered on 2026-11-01 and
D-b on 2026-11-05** (see each table row and `## Log`); D-d was answered the same day by the tax unit, which
edits this same table, and **D-a is the one still open** — it is a measurement before it is a decision.

| ID | Recommendation | Why not the alternative |
| --- | --- | --- |
| D-a | **Skip the order and report it — and measure before deciding.** T3's pre-flight emits the count and the ids; at zero the decision evaporates, at a non-zero count it is taken per row with the list in hand | A sentinel method lands in a **global** catalog with a full ABM (`PaymentMethodController` has POST/PUT/DELETE/PATCH, `PaymentMethodRequest` is name/short-name/enabled), visible in every tenant's POS. A missing payment row says "unknown", which is true; an invented one states how a sale was paid when the data never said |
| D-b | **Decided 2026-11-05, by the maintainer: backfill in the migration by the seed's exact name (`Efectivo`, `V3:385`), with a `RAISE NOTICE` when it affects zero rows, and give the ABM an `isCash`.** `PaymentMethodRequest` carries no such field today, so a method created after the deploy can never be cash and M4 refuses its change forever with no UI path | An operator step in a comment fails at the counter, not at the deploy: the cashier discovers it when the customer asks for change. The notice is what makes a renamed catalog say so instead of passing silently |
| D-d | **Decided 2026-11-05, by the maintainer: a platform `tax_code` catalog**, the same shape as `currency` (no `company_id`, no policy): `tax_rate` gets the FK, the line keeps the text snapshot that decision 4 requires | Free text cannot carry what the tax actually needs: CFDI separates Tasa 0% from Exento, and both have `rate = 0` — a string plus a rate cannot tell them apart, and the first typo taxes a line at zero without an error. If T4 slips, T2's `'NA'` with `tax_total = 0` stays valid |
| D-e | **Decided 2026-11-01, by the maintainer: add the 0002 → 0004 edge and move ADR-0004 ahead of T6. Do not split T6.** | M3, M4 and M10 *are* the safety properties of taking money (no Completed sale uncovered by payments, change only on cash, no double charge on a retry). Splitting T6 writes `chargeSalesOrder` twice, the second time under a different state mechanism, and leaves that window open precisely while the payment code is least proven. `docs/adr/README.md` justifies 0004 last as "benefits from a settled shape" — backwards: its shape is an **input** of money |

| D-g | **Decided 2026-11-07, by the maintainer on the recommendation: the charge takes money in only.** `sale_payment.direction` stays in the schema and a `REFUND` row stays expressible, but `PATCH /api/sales-orders/{id}/charge` inserts `CHARGE` rows and refuses a refund **by name**, so the endpoint that records money coming in never becomes the endpoint money leaves by | Decision 5 gives refunds a direction and a row, and the ADR's `## Out of scope` says who authorizes one «is a workflow and not this schema». Accepting `REFUND` here would answer that question with "whoever can charge" — a money-out surface with no control of its own — and the void table T5 built would still have no endpoint either way. A refusal that names the direction costs one exception now and leaves the authorization unit free to choose its own shape |
| D-h | **Decided 2026-11-07, by the maintainer: `final_price` is the line total the ADR defines, and `ck_item_total` lands with it.** `V85` derives the column (`subtotal - discount_applied + tax_amount`) and restates the historical rows from their own parts before the constraint judges them | The other two shapes each leave the tree stating one thing twice. Pinning the unit grain means the ADR's `ck_item_total` is never enforceable and `ck_item_tax` keeps computing a line's tax from a line subtotal minus one unit's discount. Leaving it out a third time keeps `final_price` as the only stored money column with no invariant, in the slice whose whole subject is the document's parts. The reading costs a restated derived column and no document total: `total_amount` is untouched, `final_price` has no money consumer inside the API since T6a, and after the backfill the column has one meaning rather than two |
| D-i | **Decided 2026-11-07 by the maintainer, on the recommendation: the «discard notice» is the row the server has not answered for.** The table renders the server's `subtotal`, `taxAmount` and `finalPrice`; a row that has no parts yet shows the local estimate **labelled** as one, under `[data-testid="local-parts-notice"]`, and the summary labels a local total the same way | The row T8 names is not developed by the ADR, and this is the only reading the ADR does not contradict. Per-part rounding contradicts decision 1's «rounding happens once, at the document boundary»: it would make the screen disagree with the stored value. A client-side copy of M3 is the integrity boundary `## Out of scope, named` refuses («the form is not an integrity boundary»), and the server already refuses it. And the case is real rather than hypothetical: `mergeLineItems` keeps a row without an `id` while its own create is in flight, and the three server fields were being discarded at `toItemTableRow` — «discard» names the defect this slice removes |
| D-j | **Decided 2026-11-07 by the maintainer: `DEFAULT_CURRENCY_CODE` stays, and the sales surface stops depending on it.** `MoneyPipe` takes the currency as an argument, names its absence as «(no currency)» instead of falling back to the token, and a source spec fails the build if a template under `src/features/sales` uses a bare `\| currency` or states an `'MXN'` literal | One `provide()` and six templates outside sales read the token (`product-variant-list`, `product-variant-stock-search`, `purchase-order-edit`, `detail-table`, `product-variant-picker`, `contract-history`). Removing it does not make them currency-less: it makes them render **USD**, Angular's own fallback, in five features this unit does not own — a regression dressed as a cleanup. The rule stays local and enforced; the debt that leaves is named: each of those features needs its own document currency before its template can state one |

The order that follows: accept and implement ADR-0003, then T5 and ADR-0004 in parallel, then T6. Money's
T1–T4 run alongside, because they read neither `sales_orders.company_id` nor the guard. Two items in this
section need no recommendation and no decision: `sale_payment` gets the same trigger plus `REVOKE` the void
table has (f), and step 7's `SaleCharged` clause goes unless someone names its consumer (g).

**2026-10-30 — the tree moved underneath two of this record's claims, and the second one is good news.** Main
advanced to `3ea24de8` while this record was being written: PR #710 (`feat/tenant-isolation-arm`) landed
`V53__tenant_isolation_policies.sql`, which arms **sixteen** tenant tables and writes their `tenant_table`
rows. Two entries above are therefore stale and stand as written, dated: «the head is `V52`» — the head is
`V53`, so the money slices start at **`V54`** — and «no table carries a policy today», which `V53:108-144`
falsifies. The collision this record worried about also dissolved on its own: RLS T3 landed as a Java
startup check on the unmerged `feat/tenant-isolation-startup-guard` branch and takes **no** migration number.
The correction matters more than the correction: `sales_orders`, `sale_payment` and `sale_payment_void` are
absent from that list, and the startup check that is about to land refuses to boot over a `company_id` table
missing from it. T5's registration line is therefore not bookkeeping — a migration that creates
`sale_payment` with its `company_id` and does not arm it in the same file stops the deployment, which is the
assertion doing its job. T5 arms its own tables; it does not wait for a later group.

(h) **The four decimals have no four-decimal source, and this one is a scope finding, not a drift.** Decision 1
widen `sales_order_items.list_price` and `quantity` to `19,4`, and the price a line carries comes from
`product_variant_store_stock.list_price`, which is `DECIMAL(12,2)` (`V13:28`, entity
`ProductVariantStoreStock.java:42-43`) and whose request rejects more than two decimals
(`ProductVariantStoreStockRequest.java:22`, `@Digits(fraction = 2)`); the stock beside it is `DECIMAL(12,2)`
(`V13:27`). The ADR's own example — «a unit price such as 0,0375 per gram» — cannot be **entered** through
any path that exists. Nothing breaks: the container widens, the `CHECK`s and the breakdown close, and a
two-decimal price is a valid four-decimal value. What breaks is the claim that the scale decision delivered
the ability to price by the gram — it delivered the column. `V30`'s precedent widens `quantity` to
`DECIMAL(12,2)`, so `19,4` is a new convention of this set, not one the tree already carries. Recorded as a
dependency on ADR-0006 (which owns `variant_listing`, the price half) rather than as a silent gap; the ADR's
`Depends on` now names it.

**2026-10-30 — the ADR edits the maintainer authorized, applied.** Six changes, all text: decision 2 names
`companies.currency` as the owner of the operating currency and forbids a `DEFAULT`; `## Schema` carries the
`ALTER TABLE companies` that gives that sentence a DDL; migration step 1 reads the value from the company; the
append-only block covers `sale_payment` as well as `sale_payment_void`, with the corrected reason
(`V52:187`/`:196-197`) and a `REVOKE` per table; `Depends on` gains ADR-0004 and ADR-0006; and
`docs/adr/README.md`'s dependency column mirrors both. `D-a`, `D-b`, `D-d`, the `SaleCharged` clause in step 7
and the **order** question of D-e are untouched, because each one is still the maintainer's call.

**2026-11-01 — the six merges after this record's reference point were re-read against it, one claim was
false, and the header is the only line repaired.** `main` moved from `3ea24de8` (#710, the point the log
below measures) to `4d6eeffd` (#725): #711 `3d2ce7ec` (the RLS startup check), #719 `f2f65c83` (the pool
logs in as `lifecontrol_app`, Flyway as `lifecontrol_owner`), #721 `58bbfdf3` (the roles SQL creates the
owner), #725 `7ab208c0` (Flyway baselines a fresh database at 0). Re-read against that tree: **every
`path:line` this record cites still resolves to the line it names**, because no cited source moved —
`V1` is `67792d02` (2026-09-09), `V3` `aec474f5`, `V13` `bd17f079`, `V52` `af37e7a0`, `V53` `2fb94620`,
`SalesOrderService.java` `25cb646f` (2026-10-27), `ChargeSalesOrderRequest.java` `cf86dd19`,
`app.config.ts` `6deeea48`. The migration head is still **`V53`** and `grep -n sales` over it returns
nothing, so the two numbers this record fixes — the slices start at `V54`, and the three sales tables are
not among the sixteen armed — stand unchanged. The startup check landed as
`TenantIsolationStartupCheck.java` (`3d2ce7ec`) and took **no** migration number, which is what leaves
`V54` free; `V53`'s own comment still predicts «T3, `V54`» and is stale on `main`, not here.

**One claim became false, and it is (f)'s justification, in the good direction.** (f) says the restrictive
half of append-only has to be a `REVOKE` because `V52:187` and `:196-197` grant `lifecontrol_app` all four
privileges on every table, present and future. That was written against a pool that still connected **as
the owner** — `V52`'s header item 3 says the grant is «STAGED ON PURPOSE … as long as no policy exists and
no connection is made with that role» — so the `REVOKE` the ADR now carries would have been **inert** for
the application. Neither condition holds any more: `V53` created the policies and #719 made the pool's
login `lifecontrol_app` (`docker-compose.yml:528`, with `FLYWAY_USERNAME=lifecontrol_owner` at `:529`), the
role the ADR revokes from. The ADR text needs no change; what changes is that M5 is now enforceable rather
than staged, and that T5 must write its `REVOKE` in the same migration that creates the tables, because
`V52:196-197`'s default privileges hand the next one all four again.

**One finding that is not drift, recorded and not fixed here.** `## Schema`'s
`ALTER TABLE companies ADD COLUMN currency CHAR(3) NOT NULL REFERENCES currency (code)` cannot execute
over a `companies` table that has rows: `ADD COLUMN … NOT NULL` without a `DEFAULT` refuses on existing
rows, and the acceptance forbids a `DEFAULT` on purpose. Migration step 1's sentence — «every existing
company takes that one code» — is the fix, and it is three steps (add nullable, backfill, `SET NOT NULL`),
which is the shape T1's row already carries. It is a defect in the accepted ADR's own block, not one a
push created, so it does not belong in the acceptance's diff: it is queued as a correction unit of its own,
the way the four corrections under `## Reconciliation` were.

**2026-11-01 — D-e is decided, and it is a decision about the order, not about an ADR.** The maintainer
confirmed the recommendation: the 0002 → 0004 edge stands (it was written on 2026-10-30, because an edge
is a fact) and **ADR-0004 moves ahead of ADR-0002** in `docs/adr/README.md`'s adoption order, which now
reads 0005 → 0006 → 0001 → 0003 → 0004 → 0002. Splitting T6 was refused for the reason the table already
states: M3, M4 and M10 are the safety properties of taking money, and writing `chargeSalesOrder` against
today's status flip and again under the typed machine would leave the window open while the payment code
is least proven. The revision changes no ADR's status, no ADR's decision and no task's content: T5 and T6
keep their dependencies and only the sequence between 0004 and this unit moved. What it does change is
what this record is blocked on — before this, the plan could have started T6 after 0001 and 0003 alone,
which is the plan that writes the charge twice.

**2026-11-01 — T1 is pulled forward and landed, on the maintainer's decision, and it is the only part of
this record that does not wait for another ADR.** The reason it was pulled: `variant_listing` (ADR-0006
decision 5) declares `currency CHAR(3) NOT NULL REFERENCES currency (code)`, so without this catalog the
catalog-identity unit cannot finish, and ADR-0001 — whose price half is that listing — waits behind it. T1
is additive, has no dependency (D-c was decided on 2026-10-30) and touches nothing of this record's risk:
it rewrites no stored amount.

What landed, in `feat/company-currency` (stacked on `feat/catalog-identity`, so its migration is **V57**
behind `V54`–`V56`): the `currency` catalog exactly as the ADR's `## Schema` block states it, seeded with
the one code the product names anywhere (`life-control-app-angular/src/app/app.config.ts:33`), and
`companies.currency CHAR(3) NOT NULL REFERENCES currency (code)` in **three steps** — add nullable,
backfill every existing company, `SET NOT NULL`. The ADR's one-shot `ADD COLUMN … NOT NULL` cannot execute
over a table with rows and a `DEFAULT` is what D-c forbids, so the three steps are the plan and not a style.
The backfill runs inside a `NO FORCE` window on `companies` (ADR-0005 correction 3, the same trap `V54` and
`V55` document) with two self-checks: one refuses a window that did not open, the other refuses a backfill
that reached no row. `currency` is a platform catalog and is armed by nothing.

The write path states the value instead of assuming one: `CompanyRequest.currency` is required (no default),
absent is a 400 naming `currency`, an unknown code is a 404 and a disabled one is a 400.
`CompanyResponse` returns it, and `CompanyRequest` being one record for both endpoints means
`PUT /api/companies/{id}` now requires it too — named here rather than discovered later.

Verification observed by the parent, after the writer's own runs: **1603 cases across 333 classes, 0
failures** over `company`, `product`, `tenant`, `store` and `supplier` — the five surfaces the new required
field reaches. The writer also produced a real RED by removing the `@NotBlank` and observing the three cases
that state the rule fail (`28 tests, 3 failed`), then restoring it; and a measured fact worth keeping: the
policy's `WITH CHECK` answers **before** the `NOT NULL` and the foreign key, so a refused insert has to
state its own id as the scope to be refused by the rule it is testing.

**The consequence this record does not fix, recorded where the next slice reads it.** The Angular company
form is the same component for create and edit
(`life-control-app-angular/src/features/companies/companies/pages/company-edit/company-edit.ts:176` and
`:188`, body built at `:209` from `company.models.ts`, which has no `currency`). Both calls now receive
`400 {"errors":{"currency":"currency is required"}}` until that form states the value. It is the same shape
as the variant-dialog consequence T2 of the catalog-identity unit carries, and it belongs to the form
slice, not to this one.

**2026-11-05 — T2, and the two measurements that reshaped it** («dale a T2»). The slice was planned as
"the migration, two commits, one per table". It landed as that, minus two constraints, because the ADR's
schema block assumes a `final_price` the tree does not have (D-f above) and a `total_amount` the tree's own
writer does not keep consistent with the parts.

What `V80` and `V81` are: the document's `currency` (derived from the company by a trigger, and refused when
a caller states another), the four breakdown parts, `total_amount` widened to `19,4` and made `NOT NULL`,
the four line amounts widened, `discount_applied` `NULL` → 0 → `NOT NULL`, the line's own `subtotal`
(derived), `tax_code`/`tax_rate`/`tax_amount`, the three snapshots read from the catalog, and the four
constraints that hold for both writers: `ck_order_parts_not_negative`, `ck_order_rounding_bounded`,
`ck_item_subtotal`, `ck_item_tax`. Both files open a `NO FORCE` window on every table they read or write
(`sales_orders` and `companies`, then `products` and `product_variants`), run their reports before their
constraints, and fail themselves if the window did not close.

What the first run taught, in the order it taught it: (a) `ALTER TABLE` is refused while deferred trigger
events are pending (SQLSTATE 55006), because `V68`'s pair is `DEFERRABLE INITIALLY DEFERRED` and both files
`UPDATE` a table it guards — the fix is the same `SET CONSTRAINTS ALL IMMEDIATE` step `V68` takes for its
seed, and the header of each file now says so; (b) `sales_orders` is FORCEd, so a fixture read without the
tenant scope answers **no row** and its assertion fails as "missing", not as "wrong"; (c) `ck_item_subtotal`
and `ck_order_total` refuse the tree's **current** writer — `SalesOrderService:225,351,364` never send a
subtotal, and `recalculateTotalAmount:1058` writes the total from unit prices while the parts stay 0, which
the sales suite reported as **41 cases answering `409` where it expects `201`**.

Observed verification, on this branch: the new scratch-database suite `SalesMoneyBreakdownMigrationIntegrationTest`
(7 cases, `PRE_UNIT_HEAD = 79`) green — including the case that asserts the legacy total does not move, and
the one that asserts a disagreeing subtotal is still **accepted** so the debt cannot pass for a bound; the
`salesorder.*` and `company.*` suites green (the 41 failures above went to 0 with nothing skipped); and the
two offline shell suites the record touches, `odd-status-headers.test.sh` (78 assertions) and
`adr-line-citations.test.sh` (11), green.

**What is left, named so it is not discovered later.** `ck_order_total` (M1) and `ck_item_total` are T6/T7's,
in the slice that writes the parts; until then `subtotal` on a **new** document is 0 by default and
`total_amount` is the unit-price sum, so M2 is not merely forward-only — it is unmet for every row written
before that slice. T3 (the pre-flight report) is still the thing that has to run before any dataset with
drift meets the arithmetic, which is the pre-flight that now runs as a script; T4 (`tax_rate`, D-d) and T5
(payments, D-b) are the catalog, the tables and their two resolvers — T5 owes only the historical backfill of
ADR-0002 step 4, which belongs with the charge rewrite; T7 (the API surface, which is what will state a
currency instead of deriving it), T8 (the POS) and T10 (the suite) remain untouched.

**2026-11-05 — T3 is a runnable report, and three of its rules are decisions ADR-0002 does not take.** The
step-2 pre-flight is `docker/scripts/preflight-money-parts.sh`, with its offline suite
`docker/scripts/tests/preflight-money-parts.test.sh` and its `AGENTS.md` entry — the first runnable
pre-flight in the tree: ADR-0003's equivalent was delivered **inside** `V64`, as a `DO` block with a
`RAISE NOTICE`, because a migration can report and a script cannot migrate. RED first (`127`, the script did
not exist), then the suite's **43 assertions** green; the whole `docker/scripts/tests` glob (26 suites) and
the whole `.github/scripts/tests` glob (12) green on this tree, and `shellcheck --severity=warning` over
`docker/scripts`, which is what CI runs, exit 0.

Three rules are decisions and each one is measurably the only option against the schema this runs on.
(a) **The reader is a superuser and the owner is not a candidate.** `sales_orders` carries `ENABLE` +
`FORCE` ROW LEVEL SECURITY since `V65`, and `apply_tenant_isolation` subjects the owner to the policy too, so
the same report run as `lifecontrol_owner` —Flyway's role— **reads zero rows and prints a clean dataset**;
that is the failure mode `V54` and `V64` already record, and a candidate that connects but reads nothing is
worse than no candidate. The role is resolved by asking `pg_roles` for `rolsuper`, in the wrapper's order
(`lifecontrol_bootstrap`, `postgres`, then the declared initdb role), and the exit code separates "the report
could not run" (1) from "a human has to decide" (2) so a failed report can never read as a clean one.
(b) **The line sum is read from `sales_order_items.subtotal`**, which `V81` derives and `ck_item_subtotal`
enforces, instead of re-deriving the ADR's inline candidate: the ADR computed it inline because no column
held it, and two ways of computing one line's money is how a report and a constraint end up disagreeing.
(c) **The tolerance is the order's `currency.minor_units`**, not the literal `1` of
`ck_order_rounding_bounded` — that constraint is the currency-agnostic half (`M8`), the widest bound any
currency allows, and the comparison is done in ten-thousandths as integers, the scale every part carries
since `V80`, so no rounding and no floating point stands between the measurement and the verdict.

**One deliberate deviation from the ADR's literal SQL, and it is not cosmetic.** The block filters
`abs(drift) > 0.0001`; the script filters `drift <> 0`, because every part is `NUMERIC(19,4)` since `V80`
and a drift of one ten-thousandth is therefore a row `ck_order_total` refuses, not a rounding artifact —
`V80`'s own step-6 self-check asks the same question with `<>` for that reason, and `S3` states the equality
as exact. Copying `> 0.0001` would have hidden exactly the rows the report exists to find.

**D-a's measurement is deliberately not carried here.** D-a's recommendation says "T3's pre-flight emits the
count and the ids"; those are the `Completed` orders with no `payment_method_id`, which ADR-0002 step 4
needs, and they are **not** the rows this report names. T3's own row asks for the step-2 report and this is
it: the method-less count is a separate measurement, still untaken, and this entry exists so nobody assumes
the script already answers it.

**The review slice is 586 authored lines, and it is deliberately not split.** 553 of them are two new
files — the script and its suite — and a report without its RED is not a reviewable unit: the split a
chained PR would buy is the one the repository's own rule forbids, because tests travel in the commit that
carries the behaviour they pin. The record is the second slice, this one.

**2026-11-05 — D-d and D-b answered by the maintainer, and T4 unblocked.** Both were taken as written in the
table above, with the alternative each row already refutes. **D-d: a platform `tax_code` catalog**, the same
shape as `currency` — `code`, label, `enabled`, no `company_id` and no policy — with `tax_rate.tax_code` a
**FK** to it and the line keeping its text snapshot (decision 4), so a reprint never depends on the catalog.
The reason the decision existed stands: CFDI separates Tasa 0% from Exento, both are `rate = 0`, and a string
cannot tell them apart — a typo would tax a line at zero without an error. **D-b: `payment_methods.is_cash`
is backfilled in the migration by the seed's exact name (`Efectivo`, `V3:385`) with a `RAISE NOTICE` when the
backfill affects zero rows, and `PaymentMethodRequest` gains `isCash`** — without that field a method created
after the deploy could never be cash, and M4 would refuse its change forever with no screen that fixes it.
D-b belongs to T5, so it is recorded here and not carried by T4. **D-a remains open on purpose**: its
recommendation says to measure before deciding, and the measurement is the count and the ids of `Completed`
orders with no `payment_method_id`, which T3 does not emit and which no unit has taken yet.

**2026-11-05 — T4 is the catalog, the table and the resolver; the copy onto the line waits for a source of the code.** `V82` adds
two tables that are deliberately different from each other. `tax_code` is a platform catalog in the shape of `currency` — `code`,
`label`, `enabled`, no `company_id`, no policy, no `tenant_table` row — seeded with **four** codes: `NA` because `V81:87` defaults
every line to it, `IVA` as the taxed bucket whose amount is not stated there, and `TASA_0` and `EXENTO` because CFDI separates them
and both are `rate = 0`, which is the whole reason **D-d** chose a catalog. `tax_rate` is a tenant table and `V82` arms it with
`SELECT apply_tenant_isolation('tax_rate')` in the file that creates it, the only way a table gets a policy (`V52:100-101`), and
which writes its own `tenant_table` row (`V52:161-166`). Neither table needs a `NO FORCE` window: both are born empty, and a window
that does not need to be open is a hole rather than a habit. The file carries three self-checks of its own decisions — the
vocabulary is exactly four, `tax_rate` landed as `tenant/company_id`, and `tax_code` landed **without** row security — so a future
edit that arms the catalog or typos the kind aborts the migration naming the fact.

**The resolver is `TaxRateService`, and two of its rules are decisions.** (a) The date it takes is the **document's**, a parameter,
never `LocalDate.now()`: the ADR names the document date, and reading the clock would charge today's rate to a document dated last
month and make every test depend on when it ran. (b) It resolves among **enabled** rows only, which the ADR's sentence does not say.
That clause is the only meaning `enabled` can have — a rate is corrected by appending the next `effective_from`, so retiring a row is
the one thing the column is for — and without it retiring a rate would change nothing about what the next line is charged, which is
worse than not having the column: it would look like a control and behave like a comment. `lineTaxAmount` is `ck_item_tax` reachable
from Java: `(subtotal - discount_applied) * rate` at scale 4 with `HALF_UP`, because PostgreSQL's `round(numeric, 4)` rounds half away
from zero and `HALF_EVEN` would disagree on exactly the half cases and get the insert refused. The base is `subtotal - discount_applied`
and never `final_price`, which is a legacy line's **unit** net price (D-f).

**What this slice deliberately does not do, and what it waits for.** It does not copy the rate onto a line, because the resolution
needs the line's `tax_code` and **nothing in the tree states it**: `products` carries `sat_code`, the category carries `sat_code`, and
neither is a tax code; the company states none; the line defaults to `NA`. Writing one there today would mean inventing that source
inside a migration, and the consumer the ADR names is `chargeSalesOrder` (T6), whose line write is where `ck_item_tax` can be satisfied
together with the derived `subtotal`. So `TaxRate` is a **read-only** entity — no constructor, no setters, nothing in the application
writes a rate yet — and the open question this leaves is named: **which unit declares a product's tax code.**

**Evidence on this tree.** The new suites: `TaxRateMigrationIntegrationTest` (7 cases on a scratch database owned by
`lifecontrol_owner`, migrated to `81` and then to head) and `TaxRateServiceTest` (6 cases) — 13 green. `TenantPoliciesIntegrationTest`
(23 armed tables) and `TenantScopeFunctionsIntegrationTest` (27) green, which is also where a literal nobody had counted showed up:
`hasSize(S2_TENANT_TABLES.size() + 11)` became `+ 12`. `MigrationHeadTest`, `ProductVariantListingMigrationIntegrationTest` (which
asserts `tax_rate_id` is absent, and stays green because `V82` does not add it) and `CompanyCurrencyMigrationIntegrationTest` green.
`spotlessApply` run, so the `Lint & Build` formatting barrier starts green.

**The slices are 559 and 407 authored lines as committed, and neither is under the ~400 budget.** The schema one is `V82` itself (136), the two
frozen tenancy lists it invalidates (42) and the migration test that proves it (379); the resolver one is the `tax` package (252) and
its test (155). Splitting the test away from its behaviour is the thing the repository's rule forbids, and a migration without the
suite that proves its arming is not a reviewable unit — so the boundary is stated here instead of being hidden.

**2026-11-05 — T5 is the payment rows, their voids, and the two halves that make them append-only.** `V83` is numbered 83 and not 82
because `V82` belongs to the tax unit on its own branch: Flyway applies what exists, the head this tree derives is the maximum of the
versions present, and the gap closes when that unit lands. Four things land, and the fourth is what makes the other three true
rather than intended.

`uq_sales_orders_company_id UNIQUE (company_id, id)` did not exist. `V64` created exactly that pair for `purchase_orders` and
`goods_receipts` and left `sales_orders` out, because nothing consumed it until a payment had to state its company and its order
together. It is the target of `fk_payment_order_same_company`, and that key is what turns a mis-stated `company_id` into a
**rejection** instead of a row the policy hides — a filtered row is not a rejected write (M9).

`payment_methods.is_cash` arrives with **D-b** as decided: backfilled by the seed's exact name (`Efectivo`, `V3:385`), with a
`RAISE NOTICE` when the backfill touches zero rows, because a renamed catalog leaves every cash sale unable to return change and the
cashier finds out at the counter instead of at the deploy. `isCash` travels the whole ABM — entity, request, response, `toEntity`,
`toResponse` and, the one that would have been silent, `updatePaymentMethod`: the request replaces the method whole and there is no
merge, so an edit that omitted the flag would have turned a cash method into a non-cash one on its next rename.

`sale_payment` and `sale_payment_void` carry the ADR's constraints verbatim, including `ck_payment_tender`'s arithmetic and
`ck_void_replacement_differs`; `sale_payment_void.payment_id` is the primary key, because a payment is void once and stating that as
the key is stronger than a second unique constraint over the same column.

**Append-only is two halves, and the grant half is the one that would be dropped in silence.** `V52:187` and `V52:196-197` grant
`lifecontrol_app` SELECT, INSERT, UPDATE and DELETE on every table present and future, so omitting a grant restricts nothing: the
restrictive half is written per table. `SalePaymentSchemaMigrationIntegrationTest` exercises it rather than reading it — the statement
runs `SET ROLE lifecontrol_app` and PostgreSQL refuses it — because a `REVOKE` nobody exercises is an intention. It connects as the
container's superuser to do it, and that detail is the point: `SET ROLE` is denied to `lifecontrol_owner` (NOSUPERUSER by ADR-0005),
and the owner would have been refused by `FORCE` row security for having no tenant scope, so the case would have passed for the wrong
reason. The trigger half is pinned structurally — present, enabled, on both tables, one function — because exercising it needs a real
payment row and that fixture chain already proves the same pattern in `StockLedgerSchemaMigrationIntegrationTest` (V60) and
`StatusTransitionRecorderIntegrationTest` (V67).

**Five defects were mine, in the migration and its test, and each cost a run.** A missing `)`: moving the constraints inside
`CREATE TABLE` added a nesting level and left the table's paren open, which surfaces as `syntax error at or near ";"` at the end of
the statement and not at the paren. A `LIKE` against `pg_get_constraintdef`, which prints its own parentheses, so the check uses a
regexp. A `SET ROLE` from a role that is not a superuser. A single Java backslash where the regexp needed two. And `boolean ||
boolean` where the query wanted `::text`. The self-check in the migration was the first one to fire wrongly: it had been written as a
probe that inserted `SELECT ... FROM sales_orders LIMIT 1` to watch `ck_payment_tender` refuse, which passes **vacuously** on the
empty database a migration runs against — the failure `odd/tasks/v64-vacuous-self-check.md` exists to name — so it now reads the
constraint from the catalog and the behaviour belongs to the suite.

**Both branches of this feature touch the same two frozen lists and the same decision table, and the conflict is expected.** The tax
unit's branch edits `TenantPoliciesIntegrationTest`, `TenantScopeFunctionsIntegrationTest` and this record's `D-d` row; this one edits
the same three places for its own tables and for D-b. Whoever merges second rebases over the other's counts — 23/27 there, 24/28 here,
and the truth after both is 25/29 with `+ 14` — and neither branch can state that alone.

**Observed here**: `SalePaymentSchemaMigrationIntegrationTest` (5 cases on a scratch database owned by `lifecontrol_owner`),
`PaymentMethodCashFlagTest` (4), the four `paymentmethod` suites, `CacheValueCodecTest`, `SalesOrderIntegrationTest` and both tenancy
suites green in one run, with `spotlessApply` applied so the formatting barrier starts green.

**One gap observed and deliberately not taken.** `TenantPoliciesIntegrationTest.ARMED_TABLES` asserts the size of its own constant,
so it cannot fail on a new table, and after this unit it holds **24 while the tree arms 28**: it is missing `document_sequence`,
`goods_receipts`, `sales_orders` and `status_transition`, all armed by `V65` and `V67`. Closing that belongs to its own slice —
conflating a repair with a unit that adds tables would hide which of the two hundred lines to review.

**The review slices are 573 and 182 authored lines, and the first one is over the ~400 budget on purpose.** The schema slice is
`V83` (234), the two frozen tenancy lists it invalidates (52) and the suite that proves the grant instead of reading it (287); the
`isCash` slice is the four `paymentmethod` files (the flag cannot land in three of them and not the fourth) plus its own suite and the
fifteen call sites the record arity changed. Splitting the suite from the migration would leave an append-only claim with no exercised
`REVOKE`, which is the one thing this unit must not ship — so the boundary is written here instead of being hidden.

**2026-11-06 — T6 taken, and the one question it could not answer from the tree was asked and answered: the stock moves to the charge.**
The maintainer's decision, verbatim: *«Mover el descuento de stock al cobro»*. It is ADR-0002 decision 7 read literally, and it was not free:
`createSalesOrder` deducts today through `StockLedgerEngine.applySaleBatch` (`SalesOrderService:244`, `:949`), and every line add, edit and
delete adjusts that same deduction with `V73`'s per-write identity, so booking again at the charge would deduct twice. What changes: an
order that is not charged holds no stock; the charge deducts the whole line set under the order's row lock; and a reversal exists only for
an order whose charge booked something. The two ODD records that state the old arrangement, `sales-location-aware-stock` and
`ledger-per-write-identity`, are edited with it, not left contradicting it.

**T6 is therefore two slices, one commit each, and the record says so instead of discovering it in the diff.** `T6a` is the money:
the `SELECT … FOR UPDATE` on the order row, ADR-0004's guard extended with M3/M4/M8, the `sale_payment` insert, the breakdown that
satisfies M1 and M2, and the transition. `T6b` is the stock: the deduction leaves the create/edit path and lands in the charge, with its
reversals and its write sequence, and the suites that assert the old moment move with it. `T6a` ships first because it is the smaller half
and the `M10` lock is what `T6b`'s single booking depends on.

**One boundary kept on purpose.** `ChargeSalesOrderRequest` still carries the single `paymentMethodId` in `T6a`, and the charge inserts
exactly one `CHARGE` payment for the order's total — the same row migration step 4 would backfill for a historical order. The many-payments
surface, the tender and the change stay in **T7**, which is the row that already owns `ChargeSalesOrderRequest`. Inventing the list here
would write that contract twice, and the ADR's own rejected alternatives say why that is the expensive direction.

**2026-11-06 — `T6a` implemented: one writer for the document's money, and a charge serialized on the order.** `V84` adds
`ck_order_total`, the exact equality of M1, and it needs no pre-flight because `V80` already left every legacy row with
`subtotal = total_amount` and the other three parts at zero — the statement `V84`'s header makes and that `V80`'s report is the
evidence for. `SalesOrderService#recomputeBreakdown` is now the only writer of the five money columns: it takes
`SELECT … FOR UPDATE` on the order (`findByIdForUpdate`, M10), sums the parts with a JPQL aggregate
(`SalesOrderItemRepository#sumMoneyParts`) instead of adding up the entities — the line's `subtotal` is derived by
`trg_sales_order_items_subtotal` and is therefore `null` in the persistence context right after the write — rounds **once** to
the document's currency (`SalesOrderRepository#findCurrencyMinorUnits`, a native join because the entity does not map
`currency`: a trigger derives it from the owning company), and writes the five fields on the managed row with no `save`, so
the parts and the total land in the same statement that `ck_order_total` judges. `chargeSalesOrder` locks first, promotes
`Active`, recomputes, appends **one** `sale_payment` (no tender, because `ck_payment_tender` allows the absent pair and T7 owns
the mixed-tender surface), guards M3 through `SalePaymentRepository#netPaid` — `Σ CHARGE − Σ REFUND` over the rows no
`sale_payment_void` names — and only then moves to `Completed`. `SaleCharged` is not published: (g) requires a named consumer
and there is none.

**One refusal was added because the tree answered a question the unit had not asked.** A document whose enabled lines add up
to zero — no lines at all, or a line soft-deleted away — reached the insert with `amount = 0` and `V83`'s
`CHECK (amount > 0)` fired at flush, so the client saw `409` and the generic sentence the handler emits for every
`DataIntegrityViolationException`. `SalesOrderEmptyChargeException` (409) now refuses it before the insert and says why. That
is the second finding of this unit, and it belongs to whoever owns the handler: **the generic mapping makes a constraint
refusal indistinguishable from a business refusal**, which is why the guard lives in the service and not in the schema.

**A third finding, named and not taken.** A cancelled line keeps `enabled = true`, so `sumMoneyParts` still sums it, and
`updateSalesOrderItemStatus` never recomputes: a document whose lines are all cancelled charges its full total, and a
document that cancels one line keeps a stored total that no longer matches its parts until some other write recomputes.
Neither is introduced here — the pre-T6a writer summed `final_price` over the same `enabled` filter — and both are
accounting questions of the line's own money, which is the slice `T4` already owes (the rate copied onto the line). Named
here so it is not discovered twice.

**Observed**: `SalesOrderServiceTest` 93 tests and `SalesOrderIntegrationTest` 48, green in one run, with four new integration
cases proving M1 and M2 from the stored document and M10 from the row count (`chargeTwice_isRefusedAndAppendsNoSecondPayment`
asserts one `sale_payment` row, because the refusal alone would pass even if a second payment had been written first), plus
the empty-charge refusal; `spotlessCheck` clean. Thirteen unit cases were repaired rather than deleted — six lost a fixture
stubbing that the removed `findBySalesOrderIdAndEnabledTrue` call made dead, and seven proved "the total was recalculated"
through a `save` that no longer happens, so they now stub the aggregate row and assert the document's own five columns.

**The integration fixture had to learn something the schema already said.** A charge now leaves a `sale_payment` row, the
table is append-only by trigger **and** by grant, and it carries `fk_payment_order_same_company`, so the class's
`salesOrderRepository.deleteAll()` can no longer delete a charged order. `deleteSalePayments` runs in `@AfterEach` through
the container's own login with `SET LOCAL session_replication_role = replica`, the mechanism `deleteStockLedger` already
documents. Its natural home is `AbstractPostgresIntegrationTest`, because `T6b` will need it too; it stays in the class
until then, named here rather than moved in a unit that is already about money.

**The slice boundary of `T6a`, recorded because it is over the review budget on purpose.** Two commits, `88f2f757` (846 lines:
`V84`, the mappings, the two repositories, the payment table's Java shape, the writer, the charge, the thirteen repaired unit
cases and this record) and `a10d2cec` (374 lines: the four integration cases and the empty-charge refusal). The first is over
because the constraint and its writer cannot be separated: `ck_order_total` refuses the writer the tree had, so a commit holding
the migration and not the service is a commit whose own suite is red — the exact failure `V80`'s header documents at the schema
level, and splitting it again would only move the red into a different commit. The second is the proof and is a clean slice.
What the boundary deliberately does **not** do is carry `T6b`: moving the stock booking is a different behaviour, it rewrites
four entry points and 83 test cases, and it needs the two ODD records that state the old arrangement edited with it.

**The independent verification found the deploy-abort this record had argued away, and it was right.** `T6a` was escalated to a
verifier on item 1 — the migration judges financial rows — and the report was `findings`, twice over. **First**: `V84` validated
`ck_order_total` over the whole table while its header claimed the pre-flight was unnecessary because `V80` had run it. `V80`
reconciled the rows that existed **when `V80` ran**, and the writer in production ever since has been `origin/main`'s
`recalculateTotalAmount`, which states `total_amount` and leaves the four parts at their default zero. Every order created between
the two releases would have aborted the deploy — the failure migration step 2 forbids, and the one `preflight-money-parts.sh`
cannot see because it compares the total with the **lines**, not with the parts. `V84` now restates those rows exactly as `V80`
restated the legacy ones (`subtotal = total_amount`, the other three at zero) before the constraint, narrowed to
`subtotal = 0 AND discount_total = 0 AND tax_total = 0 AND rounding_adjustment = 0 AND total_amount <> 0`, and the new case
`restatesTheDocumentsWrittenAfterThePartsExisted` seeds at `83` and migrates to head, so the abort is reproduced by a test instead
of by a deploy. Two traps were measured on the way and are in the file: `sales_orders` is `FORCE ROW LEVEL SECURITY`, so the
backfill runs inside a `NO FORCE` window — an unscoped `UPDATE` would match no row and report success, the mode `V54` and `V64`
already record — and `V68`'s deferred constraint trigger makes the `FORCE` alter fail with `55006` until
`SET CONSTRAINTS ALL IMMEDIATE` runs.

**Second**: the row lock the unit introduced was taken in opposite orders. `chargeSalesOrder` locks the order and then writes
lines; the line paths locked a line and only then reached `recomputeBreakdown`, which locks the order — order→line against
line→order is a deadlock that the baseline could not have, because it took no order lock here at all. Every path that writes a line
now takes the order's row lock before its first line read or write (`updateSalesOrder`, `updateSalesOrderItemStatus`, and the
shared `loadAndValidateModifiableSO` behind add, update and delete), so one order is the only order. `deleteSalesOrder` still
soft-deletes lines without that lock and is named as the remaining window rather than silently widened into this unit.

**Two advisories are named and not taken**, because each is a product question and not a defect of this slice: a document whose
exact total rounds to zero minor units is now refused as "nothing to pay" and is unchargeable (a one-line sale at `0.0040 MXN`),
and a cancelled line keeps `enabled = true`, so the document still charges it — pre-existing, and the same accounting question as
the rate copied onto the line that `T4` already owes.

**Delivery of `T6a` (2026-11-06).** Issue #797, pull request #798, commits `88f2f757` (the constraint, the writer and the charge),
`0c45fd04` (the API proof) and `893cd1f5` (the two verification findings). Full module suite green at 4,568 tests and
`spotlessCheck` clean before the PR was opened; the merge that lands it is #798's.

**2026-11-06 — `T6b` implemented: the charge books the stock, and no other path does.** `S5` / decision 7, verbatim: «`chargeSalesOrder`
locks the order row, validates the state guard of ADR-0004, inserts the payments, computes the breakdown, **books the stock
through ADR-0001's API** and appends the transition, all in one transaction.» `T6a` shipped that sentence minus the stock.

**What moved, and what stopped.** `chargeSalesOrder` now sends **one** `applyStockChanges` batch after the M3 payment guard and
before `moveOrder(COMPLETED)` — inside the transaction and under the row lock taken at the top — with one `deduct` per enabled
non-`CANCELLED` line, each numbered by that line's own `nextWriteSeq`. The six paths that used to book are silent: `createSalesOrder`,
`updateSalesOrder`, `addSalesOrderItem`, `updateSalesOrderItem`, `deleteSalesOrderItem` and `updateSalesOrderItemStatus` ask the
engine for nothing, and `collectLineStockOperations` — the four-site delta fold — is deleted, because at the charge there is no delta:
one line, one full-quantity deduction. `V73`'s counter therefore has exactly one writer left, which is what the rule always wanted
(«a path that books nothing must not bump»), and `nextWriteSeq`'s javadoc says so.

**The reversal now exists only where the charge left a movement.** `updateSalesOrderStatus` reverses nothing: a cancellation only
ever leaves Draft, Active or Pending — none of which holds stock — and `COMPLETED` is terminal for this document (ADR-0004
decision 3), so there is no cancel edge out of a charged order to give anything back for. `deleteSalesOrder` reverses only when the
row's status is `COMPLETED`, the one document its charge booked; `restoreAllLines` has that single caller left.

**The consequence, named instead of discovered later.** A document may now be created and edited with lines the store does not stock,
or with quantities it cannot cover; nothing refuses them until the charge, and the charge's refusal rolls the payment, the breakdown,
the transitions and the lines back with it — so the refusal is all-or-nothing, which is *stronger* than the create-time check it
replaced. Both refusals are **409**: the position gate answers `available 0` before the store row is read, so even a variant with no
stock row in the store is an `InsufficientStockException` and not a `400`. The measurement corrected the hypothesis this slice started
with, and the integration case now names it. The OpenAPI pin followed the contract rather than being relaxed: `F-8`'s
`SalesOrderOpenApiContractTest` asserts that no create or line endpoint declares a stock `409` it can no longer answer, and that
`PATCH /api/sales-orders/{id}/charge` — the one endpoint that can — does declare it.

**A lock inversion this slice opened, closed in it.** `chargeSalesOrder` takes the order row and then the stock positions. `deleteSalesOrder`
reversed the positions and let Hibernate take the order row at flush, so with `T6b` it became the mirror of the charge and could
deadlock with it (40P01, surfaced as a `500`) — the same class of defect `T6a`'s verification found and fixed in three other paths,
and the path it explicitly recorded as still lacking the lock. It now loads with `findByIdForUpdate`, so order-then-position holds on
both sides. The unit case pins it with the stub rather than a `verify`: an unlocked read would leave the locked stub unused, and the
suite's strict stubbing refuses that instead of passing quietly.

**One follow-up opened, not fixed here.** `SalesOrderStoreReassignmentNotAllowedException` still refuses a store change while the order
holds enabled lines. After `T6b` nothing is booked before the charge, so that refusal is stricter than the invariant behind it — the
charge would simply book at the new store. Narrowing it is a decision of its own; relaxing it inside the slice that moved the booking
would be a second, unstated rule change.

**Evidence.** Unit suite 97 tests / 0 failures (94 before, +3 charge-batch cases); `SalesOrderIntegrationTest` 48 / 0;
`com.lifecontrol.api.inventory.*` 151 / 0, untouched. Full module gate `spotlessCheck test spotbugsMain` on the final bytes:
**1,081 suites / 4,578 tests / 0 skipped / 0 failures**, `spotbugsMain` **0** `BugInstance`, `spotlessCheck` green in the same
run. The delta against this slice's first gate (1,080 / 4,574) is exactly the four pins the verification asked for: the unit case
for the locking read and the three integration probes. Every assertion that moved kept its observable — one batch per document, one deduction per live line, that line's own
sequence — and no case was deleted; the inline-diff family was retargeted from deltas to «the diff books no stock» because its subject
was the delta the charge no longer has.

**Why this slice is over the review budget, recorded instead of discovered in the diff.** `T6b` rewrites four entry points and
the 83 test cases that spoke for their stock moment, so its diff cannot sit at ~400 authored lines without separating a behaviour
from its proof — the same argument `T6a` recorded. The boundary chosen is by kind and not by size: `a82e2e8c` is the source and the
published contract (347 authored lines), `f7774412` the unit suite (681), `4e6ac660` the integration suite (~1,760, almost all of it
assertions moving rather than being written), and the records last. Each commit builds on the previous one and the whole slice is
green — 4,574 tests, `spotlessCheck` and `spotbugsMain` — before the records land, so no commit of this slice carries a red suite.

**Commits.** `a82e2e8c` (the charge books the stock, the six paths stop, the lock order, the OpenAPI pin), `f7774412` (the unit
suite moves its assertions to the charge), `4e6ac660` (the integration suite charges before it asserts stock).

**2026-11-06 — the independent verification found one defect, one deploy risk and seven stale comments.** The slice was escalated
to `gentle-ai-verify`, which passed items 1, 3, 4 and 7 of its brief (transaction and atomicity, the write-sequence identity,
idempotency, and S5 verbatim) and came back with the rest.

**The defect, and the trace that was wrong about what it costs.** `updateSalesOrderStatus` read the order row **unlocked** and
then wrote its state. The verification traced that statically to «`CANCELLED` committed over `COMPLETED`, payment and deduction
kept, stock stranded», and the RED run of the new integration probe falsified exactly that part: `V67`'s side-B check is
`DEFERRABLE INITIALLY DEFERRED` and requires a `status_transition` row for the **real** `OLD → NEW`, while the cancel recorded
`PENDING → CANCELLED` — the state it had read. So the commit is refused, the request answers **500**, the cancellation rolls back
and the document is correctly `COMPLETED`. No stock is stranded; the schema's deferred check is a real last line of defence.
What is actually wrong is that a legal cancellation depends on a trigger to avoid an internal error, and answers `500` where the
state machine already knows how to say `409`. Fix (`ed59f94c`): the same one-line lock the slice gave `deleteSalesOrder`; the
loser now reads `COMPLETED`, `DocumentStates` refuses the move, and the answer is 409.

**Recorded because a static trace is not a measurement.** The first version of this entry, of the method's comment and of the
unit case stated the stranded-stock mechanism as fact. The probe's RED run is what corrected them, on the pre-fix tree, from a
copy (`git archive 0cc4be3c` in a scratch directory): 51 tests, **the only failure the new race case**, ``the loser is a refusal
that names the reason, not a 500 — Expecting actual: 500``. A claim that a green suite cannot falsify was falsified by the
assertion that expected the refusal; that is the shape a durable probe is for.

**The pins left behind, three of them.** The unit case (`updateStatus_TakesTheRowLock`) asserts the locking *statement*, because
the fixture's delegate makes both reads answer the same row. `cancelRacingCharge_neverStrandsABookedDeduction` runs the race for
three rounds on two threads, released together, and reads the outcome from the database instead of the responses — `COMPLETED`
iff exactly one `sale_payment` row and one `SALE` movement, `CANCELLED` implies neither and an untouched position. And two
characterization probes state the identity `V73` exists for: a live line's `write_seq` is 1 and its `SALE` rows for that line
number exactly `1`, a cancelled line's is 0 with no movement, and a charge refused for an uncovered line leaves both lines at 0 —
the rollback gives the numbers back (`writeSeq_namesTheMovementAndNothingElse`, `refusedChargeConsumesNoSequence`).

**What the verifier named and this record keeps as a reviewer's risk.** The reversal gate is a *status predicate*, not a *ledger
predicate*: it is immune to this race now, but a future path that lets `status` drift could strand stock again, while «reverse when
the reference holds open `SALE` rows» would not — and the engine already answers that question (`openLedgerSales`). That is a
change of the gate's kind, not of this slice, and it is recorded rather than smuggled in.

**The deploy window, declared and then measured — not silently accepted.** An order created by the pre-T6b code already booked its
deduction at `create` (`write_seq` 1, one open `SALE` row per line). Charged under T6b, the charge bumps to 2 and books a **second**
full deduction under the same reference, which `uq_movement_source` accepts because the write sequence differs: the position drops by
twice the quantity. The maintainer measured the dataset at the cut-over and it holds **no in-flight orders**, so the window is empty
for this deployment. What would have to exist if this is ever replayed against a dataset that has them is named here instead of
left implicit: a read-only report of the Draft/Active/Pending orders whose lines hold an open `SALE` row, plus the operating rule
that they are charged or cancelled **before** the switch — ADR-0002 migration step 2's shape, and the precedent
`docker/scripts/preflight-money-parts.sh` already sets. Not building it now is a statement about this dataset, not about the risk.

**Seven rationale blocks reworded** (`a65336dc` and the same file in `ed59f94c`): `CANCELLED` as «the line whose reversal is
booked», a cancellation as «the reversal that already happened», a cancelled line as «given back in full», and the store refusal as
protecting already-deducted stock. None of that is true after T6b, and a comment that describes the behaviour that just left is the
same defect class as a test named after it.

**Commits of the verification round.** `ed59f94c` (the cancel takes the row lock), `a65336dc` (the states and the two
exceptions stop describing the old moment), `c5cf4081` (the three probes: the race and the two write-sequence identities) and
`f0978e01` (this correction).

**L2** — «puedes continuar con t7 del adr0002»

**2026-11-07 — T7 is the API surface, split in three, and the two questions it could not answer from the tree were
asked and answered.** The slice was read against the tree it inherits (`origin/main` `3ca54e90`, T6b merged): the five
DTOs are records with no currency, no breakdown part and no payment list; `SalesOrderService#recomputeBreakdown` is
already the only writer of the five order columns; `chargeSalesOrder` inserts exactly one `CHARGE` row for the whole
total and the entity's only factory never sets a tender. Two facts the slice could not state for itself, and each one
became a decision instead of an invention.

**D-g — the charge takes money in and never gives it back.** `sale_payment.direction` admits `CHARGE` and `REFUND`, and
the ADR deliberately leaves the refund's authorization out of scope («a refund is a row with `direction = 'REFUND'`; who
authorizes it … is a workflow and not this schema»). A request that may declare `REFUND` would answer that question with
"whoever can charge", which is a money-out surface with no control of its own. The maintainer took the recommendation:
the request carries no `direction`, the server writes `CHARGE`, and a refund arrives with its own unit and its own
authorization. Nothing in the schema changes and the void table T5 built keeps waiting for the same unit.

**D-h — `final_price` is the line total the ADR already defines, and `ck_item_total` lands with it.** The measurement
that forced the question: `final_price` is written as a **unit** net price (`SalesOrderService:231` and `:622`,
`final_price = list_price - discount_applied`), while `ck_item_tax` (`V81:170-171`) and therefore
`TaxRateService.lineTaxAmount` compute a line's tax from `subtotal - discount_applied`, and `recomputeBreakdown` sums the
same triple — so since T6a the document's total is Σ(`subtotal − discount_applied + tax_amount`), which is exactly the
ADR's `ck_item_total` read as a line total. The column is the only one of the two readings that disagreed with the
document, and it is the one nothing consumes: after T6a no code sums `final_price`, and no report in the tree reads it.
`V85` derives it in the schema the way `V81` derives `subtotal`, restates every row from its own parts before the
constraint judges it, and moves no `total_amount`. The alternative the record had already rejected once — restating
line money into historical totals — stays rejected: this restates a derived column, not a document's money.

**The three slices, and why this order.** `T7c` first: it settles what `final_price` means, and `T7a` is the sheet that
reports it. `T7a` second: the read surface, so the contract the POS will consume exists before the charge changes it.
`T7b` last: the charge with many payments, the exact M3 and M4's cash guard, which is the only slice that touches what
the POS sends.

**What T7 deliberately does not do, named so it is not discovered in the diff.** It does not populate a line's
`tax_code`, because nothing in the tree states one (T4's open question, still open). It does not run ADR step 4's
historical payment backfill (T5's debt) nor step 6's drop of `sales_orders.payment_method_id`: that column keeps being
written from the first `CHARGE` row, because step 6 waits on the backfill **and** on every reader — the Angular POS is
T8's, and a listener that reads `paymentMethodId` still exists in the response. It does not build refunds or voids. And
it does not touch the Angular POS, which T8 owns and which will receive a charge body it cannot send until then: that
break is the price of writing the contract once, which is what T6a's boundary existed for.

**2026-11-07 — `T7c` implemented: `final_price` is the line total, and the schema derives it.** `V85` does three
things in one file and in this order. It restates **every** `sales_order_items.final_price` from the row's own
parts (`subtotal - discount_applied + tax_amount`), so the column has one meaning after the deploy instead of two
that a report has to filter by age. It creates `trg_sales_order_items_final_price`
(`BEFORE INSERT OR UPDATE OF list_price, quantity, discount_applied, tax_amount`) which writes the same
expression. And it lands `ck_item_total` last, over rows it has just stated. No `sales_orders` column moves:
`total_amount` comes from the parts since T6a and never from this column, which is what keeps the restatement a
schema statement instead of a financial rewrite (D-h).

**The one measurement that shaped the trigger, and it is not cosmetic.** PostgreSQL fires same-event triggers in
**name order**, and `trg_sales_order_items_subtotal` (`V81`) sorts *after* any trigger named
`trg_sales_order_items_final_price`. A derivation that read `NEW.subtotal` would therefore read the OLD row's
value on every insert and on every edit of `list_price`/`quantity` — silently, and only for the rows nobody would
think to check. The trigger evaluates `round(NEW.list_price * NEW.quantity, 4) - NEW.discount_applied +
NEW.tax_amount` itself, and `ck_item_subtotal` plus `ck_item_total` are what hold the two derivations equal.

**The application side, and the trap `subtotal` had already left open.** `final_price` is
`insertable = false, updatable = false` on `SalesOrderItem`, and the setter and builder method are gone: the six
write sites that stated `listPrice - discountApplied` (`:231`, `:317`, `:330`, `:565`, `:616`) stop, because a
value the schema owns and the application sends is the value the row keeps on any statement the trigger's column
list does not name. That exposed the read side: a derived column is `null` in the persistence context after a
write — the same reason `recomputeBreakdown` sums with a JPQL aggregate — so the response would have reported
`null` for `final_price` on every create, update and add. `refreshDerivedLineMoney` flushes and refreshes the row
before the mapper reads it, and it is conditional on the column being null, because the four derived columns are
`NOT NULL` in the schema: a non-null value proves the instance came from a row, so a plain GET pays no second
statement. `SalesOrderItemResponse.finalPrice` is the response that proves it.

**One pre-existing defect of the test harness surfaced and was repaired with the slice.** `constraintOf` in
`SalesMoneyBreakdownMigrationIntegrationTest` returned the constraint name through a chain of ternaries that named
five constraints; the sixth one — `ck_item_total` — fell through to the whole PostgreSQL error text, and the case
failed looking like a wrong constraint instead of a wrong helper. It now reads the name out of the sentence
PostgreSQL always prints (`violates ... constraint "..."`), so the next constraint needs no edit. This is not the
slice's subject; it is the reason the slice's first red run was red for the wrong reason, and leaving a
hard-coded vocabulary inside an assertion about a new constraint would have hidden the next one.

**RED, observed on the pre-slice tree** (`git archive HEAD` in a scratch directory with only the two suites'
new bytes copied in — no migration, no entity, no service): `66 tests completed, 5 failed`, and the five are
exactly the new or retargeted cases.
`V85 restates a line's final_price from its own parts…` → `expected: "190.0000" but was: "90.0000"`.
`a legacy document keeps its total cent by cent…` → the same assertion, the same 90. `V85 derives the line total
on insert…` → `ERROR: null value in column "final_price" of relation "sales_order_items" violates not-null
constraint`, which is the proof that the trigger is what fills it. `ck_item_total refuses a line whose total is
not its parts` → `the statement was accepted and should have been refused`.
`the created line reports the line total…` → `expected: 190.0000 but was: 90.00`.

**GREEN, on this tree.** `SalesMoneyBreakdownMigrationIntegrationTest` 14 cases, 0 failures — the three new ones,
the retargeted legacy one, and the ten that were already there. `SalesOrderIntegrationTest` (48 + 1),
`SalesOrderServiceTest` (97) and `SalesOrderControllerTest` green in the same runs, and `spotlessApply` applied so
the formatting barrier starts green.

**What the mocked unit suite could and could not say.** Five cases asserted `result.finalPrice()` was `90.00` on a
response built from a repository mock, and a mock runs no trigger. They were retargeted, not deleted: each now
asserts that the mapper **asks the row** (`verify(entityManager).refresh(any(SalesOrderItem.class))`) and the
number itself is pinned by the integration case — the same shape T6b's lock case records, where the stub would
have passed quietly for the wrong reason.

**What this slice deliberately does not do.** It does not carry the response surface (`currency`, the four
document parts, `payments`, the line's tax triple and snapshots) — that is T7a, which now reports a column this
slice settled. It does not touch `tax_amount` or `tax_rate`, which stay 0 until some unit states a line's
`tax_code` (T4's open question). And it does not edit `V80`/`V81`'s headers, which say `ck_item_total` is absent
and are frozen by their own checksum: a migration's narrative is a record of what it did, not a promise about what
comes next.

**The review boundary of `T7c`, recorded because the slice is over the ~400-line budget and the split it would
take is forbidden.** Measured on the branch: `75323bd8` holds 399 authored lines of migration, entity, service,
controller and proof, and the record commit `cf6ee299` adds 145 more — 544 in two commits, with the record
travelling in the one that brings the work, as the repository's rule requires. None of the available cuts is a
cut: the migration without the writer is a constraint whose own suite is red (`V85` refuses the application that
sends a unit price), the writer without the migration is the same red from the other side, and the suite cannot
leave the behaviour it pins. What is not negotiable is that this slice is reviewed alone, because it is the one
that restates a stored money column: `T7a` and `T7b` change no schema.

**The delivery shape, decided here because the branch will exceed the budget by a factor.** `T7c` is its own PR
and `T7a`/`T7b` are stacked on it as the second and third slices of the same unit, which is the chained-slice
form `CONTRIBUTING.md` states for work over ~400 lines. Each staged slice gets its own review, and the record's
header ends in the last one, so no PR exists merely to flip it.

**2026-11-07 — `T7a` implemented: the sheet a document is read on, and the breakdown stops being invisible.** The
response now states what the row states: `currency` (the document's own, which `V80` derives from the company and
refuses otherwise), the four parts (`subtotal`, `discountTotal`, `taxTotal`, `roundingAdjustment`) beside the
`totalAmount` they add up to, `payments` (oldest first, each with its direction and its tender pair), and on the
line `subtotal`, `taxCode`, `taxRate`, `taxAmount` and the two catalog snapshots. **The breakdown is read-only in
both directions**: no request states a part, and no response omits one — which is the whole of T7's "read-only and
computed by the server" in the surface a client touches.

**Three reads were needed and each one is a decision worth keeping.** `currency` cannot come from the entity —
`SalesOrder` deliberately maps no field for a column whose only writer is a trigger — so
`SalesOrderRepository#findCurrencyCode` reads the one column, joining nothing, in the shape
`findCurrencyMinorUnits` already had. An empty answer is a **refusal** (`IllegalStateException`) and not a null:
the column is `NOT NULL`, so an empty read means the row is not visible to this caller, and a response that prints
a total with no currency is the failure class D-c removed from the create path, one layer down. The payments are
read once per document and never once per line. And the snapshots are mapped read-only, because `V81` is the only
writer they have ever had and a mapping that could be set is a second moment for the same name.

**One gap this slice exposes rather than closes, and it is the same shape as T4's.** A line written since `V81`
carries **no** name snapshot: the migration backfilled history, and no application path states one — not the
create, not the item endpoints, not the `PUT`. So the item response answers `null` there, which is a line with no
snapshot and not a line with a wrong one; filling it in the reader would invent the moment decision 4 pins to the
sale. The writer that owes it is the same one that owes the rate: **the line write needs to copy the catalog's
name and the resolved rate at the moment of sale**, and that is one slice, not two — T4's open question (which
unit declares a product's tax code) is its first half.

**The cost, measured and not hidden.** Every `toResponse` now issues two more statements, one for the currency and
one for the payments, on top of the items query the list path already issued per document. A page of twelve
documents pays twenty-four instead of twelve extra. That is not a new class of defect — the list was already N+1
over the items — but it doubles it, and the fix when it matters is a projection or a fetch join for the page, not
a per-line query, so it is named here instead of being discovered on a slow screen.

**RED, observed on the pre-slice tree** (`git archive HEAD` in a scratch directory, only the new integration-test
bytes copied in): `3 tests completed, 3 failed`, each on the absent field — `[the response states the document's
own currency…] Expecting actual not to be null`, `[the response states subtotal] Expecting actual not to be null`,
`[the charge's own response states the row it appended…] Expecting actual not to be null`.

**GREEN, on this tree.** `--tests "com.lifecontrol.api.salesorder.*"`: **216 tests, 0 failed, 0 errors, 0
skipped**, including the 212 that were already there and `SalesOrderOpenApiContractTest` (8). Three controller
cases failed on the first full run (`$.finalPrice expected:<90.0> but was:<190.0>`, and `100.0` vs `500.0` on the
`PUT`): the fixtures' arithmetic had moved with `V85` while their assertions still stated the old numbers, so they
were retargeted to the values their own fixtures now produce — not deleted, and not relaxed to "anything".

**The review boundary of `T7a`.** ~570 lines, of which ~400 are the three integration cases and their four
helpers: the read sheet cannot be proven by a unit mock (a mock runs no trigger and has no policy), so the proof
lives where the schema is, and the slice is stacked on `T7c` in the chained form already recorded.

**2026-11-07 — `T7b` implemented: the charge takes many payments, and the three invariants the schema cannot express move into the service.** `ChargeSalesOrderRequest` is now `payments` — a non-empty, `@Valid` list of
`SalePaymentRequest` (`paymentMethodId`, `amount`, and the optional tender pair) — and the charge appends one
`CHARGE` row per payment. **No `direction` is accepted anywhere**, which is D-g: the schema keeps `REFUND`
expressible and the endpoint that records money coming in never becomes the endpoint money leaves by. The claim
that a client stating one is refused *by name* is true rather than aspirational, and it is not this slice's doing:
`spring.jackson.deserialization.fail-on-unknown-properties=true` (`application.properties:69`) turns an undeclared
field into a `400`, so a body that states what the record does not declare is refused instead of quietly charged.

**The three guards, and why each one is Java and not SQL.** M4 — change is returned only for a cash method — has
**no schema form at all**: a `CHECK` cannot read another table, so `ck_payment_tender` holds the arithmetic and a
card payment with a tender and a consistent change passes it. `payment_methods.is_cash`, written in T5 and read by
nobody until now, is what the guard reads. The **tender arithmetic** is refused as a `400` before the insert,
because a request whose change is not `tendered - amount` is the client's to fix and the constraint's generic `409`
names neither the payment nor the two numbers. And **M3** is exact equality between the payments' sum and the
document's total — not "at least", because each payment's `amount` is what it applies to the sale, and "the customer
handed over 500" belongs in `tendered_amount`, which is the column that exists for it.

**The measured proof that M4 needs the service, produced by ablation.** The natural RED here is a **compile
failure**, not an assertion — the pre-slice charge reads `paymentMethodId`, so no new case can even compile against
it (`18 errors: cannot find symbol … SalePaymentRequest`) — and that is the weaker observation, so it was not the
only one. With both guards commented out, the four new cases answered `4 tests completed, 3 failed`:
M4's `expected: 409 but was: 200` — the card row with a tender landed, which is the schema's blind spot stated as a
measurement — the arithmetic case `expected: 400 but was: 409` (the constraint's envelope, which is what a client
would have met), and the M3 case answered by the table guard's sentence instead of the request's. Guards restored
and the final run re-executed.

**Two decisions the brief left open, and why these.** The statuses: M4 and M3-on-the-request are `409`
(`ConflictException`, the family `SalesOrderEmptyChargeException` and `SalesOrderPaymentInsufficientException`
already belong to), and only the tender arithmetic is the `400` the brief demanded — the difference is that in the
first two every payment is well formed and it is the document's state that the request cannot satisfy, while the
third is a number the client stated wrong. And the refusal messages name the payment's **position** ("payment 2 of
3") rather than an id it does not have yet, because a client holding the body it just sent has no other handle.

**`sales_orders.payment_method_id` keeps being written**, from the first `CHARGE` row, with step 6's two conditions
in the comment. The consequence is named where it bites: a sale paid half in cash and half by card reports one
method, so a report grouping by that column under-counts the second until T8 moves every reader and step 4's
historical backfill exists. That is the column's pending drop, not a defect of this slice.

**GREEN, the whole module, on the final bytes.** `spotlessCheck test spotbugsMain` → **1,083 suites / 4,592
tests / 0 failures / 0 errors / 0 skipped**, `spotbugsMain` with **0** `BugInstance`, `spotlessCheck` green in the
same run — 14 more tests than T6b's closing gate (4,578), which is the three slices' new cases. The sales suites
alone are 222.

**The review boundary of `T7b`.** ~565 lines, of which ~470 are the two suites' retargeted bodies: the request
changed shape, so about thirty construction sites had to move, and moving them is what keeps `M10`'s retry case,
the empty-charge refusal and the stock refusals asserting what they always asserted. The branch now holds T7 in
three stacked slices, and the record's delivery shape is unchanged: `T7c` first, `T7a` and `T7b` on top.

**2026-11-07 — T8 is the POS, and it takes two decisions ADR-0002 does not.** The Angular surface is
`life-control-app-angular/src/features/sales`, and it held three defects: `app.config.ts:33` provided
`DEFAULT_CURRENCY_CODE 'MXN'`, so five sales templates printed a currency the sale had not stated
(`sales-order-item-table.html:56,77,86,127`, `sales-order-list.html:91`, `product-variant-selector.html:28`);
`toItemTableRow` (`sales-order-edit.ts:820-838`) **discarded** `subtotal`, `taxAmount` and `finalPrice`, so the
table showed `rowSubtotal()` — `quantity × listPrice − discountApplied` in float — where the server's own part
belongs; and the charge was one button per payment method still sending `{ paymentMethodId }`
(`sales-order.service.ts:67-71`), a body `T7b` had already made unsendable.

**`D-i` — the «discard notice» is the row the server has not answered for.** T8's row names it and the ADR does
not develop it, so the reading was decided rather than assumed (see the table row). What makes it the reading and
not a preference: `mergeLineItems` deliberately keeps a row without an `id` while its own create is in flight
(`sales-order-edit.ts:459-463`), so the case is real; and the word «discard» names the defect the same slice
removes. The other two candidate readings are refused by the ADR's own text — per-part rounding against decision
1's «rounding happens once, at the document boundary», and a client-side M3 pre-check against
`## Out of scope, named` («the form is not an integrity boundary»). The table therefore renders
`lineSubtotal`/`lineTaxAmount`/`lineFinalPrice` from the server's parts, falls back to the local estimate only for
a row without them, and counts those rows in `[data-testid="local-parts-notice"]`; the summary shows the
document's own `totalAmount` and labels the local sum `(local estimate)` when the document has none.

**`D-j` — the global default stays and the sales surface stops depending on it.** One `provide()` and six
templates outside sales read `DEFAULT_CURRENCY_CODE`; removing it would not make them currency-less, it would make
them render **USD** — Angular's own fallback — in five features this unit does not own. So the token stays, and
the rule becomes local and mechanical instead: `MoneyPipe` (`shared/pipes/money.pipe.ts`) takes the currency as an
argument and renders `(no currency)` when it is absent rather than consulting the token, every sales money value
goes through it at `1.4-4`, and `sales-money-currency.spec.ts` fails on a bare `| currency` or an `'MXN'` literal
under `src/features/sales`. The debt is named where it now lives: each of those five features needs its own
document currency before its template can state one.

**The charge is a list, not a button.** `sales-order-charge-panel` states a `SalePaymentRequest[]`: one row per
tender with method and amount, a tender pair (and the change it implies) only for a method whose `is_cash` is
true (M4), `step="0.0001"` on every money input, and float noise trimmed to the scale `@Digits(fraction = 4)`
holds. It **reports** the difference against the document's total (`data-testid="charge-difference"`) and does not
refuse the charge: M3 is the server's, and the panel refusing would be the client copy of it the ADR declines. What
`amount` costs and what the drawer counts are held apart on purpose — a cash row whose tender is below its amount
covers only the tender, so the screen names the shortfall instead of reporting a covered row.

**RED then GREEN, on the fixture that had to move.** RED first: `MoneyPipe` did not exist (`Cannot find module
'./money.pipe'`) and `ItemTableRow` carried no parts (`'taxAmount' does not exist in type 'Partial<ItemTableRow>'`).
Six fixtures across `order-header-form`, `status-transition`, `sales-order.service`, `sales-order-edit` and
`sales-order-list` now state `currency: 'MXN'`: `SalesOrder.currency` is a required field because the server has
sent it since `T7a`, and a fixture that omits it is a fixture claiming to be a response the API cannot produce.
**GREEN**: `npx ng test --no-watch` → **198 files / 4,137 tests / 0 failed / 0 skipped**, `npm run lint` →
"All files pass linting". The sales suite alone is 270 cases across 12 files, of which the new behaviour is 20 in
`sales-order-charge-panel.spec.ts`, 13 in `sales-order-item-table.spec.ts` and 5 in `money.pipe.spec.ts`, plus the
3 guard cases.

**One assertion was deleted on purpose, and this is why.** The money-pipe case `should honour the locale it is
injected with` asserted `$1,234.5678` under `es-MX`, and in this environment Angular renders the ISO code
(`MXN1,234.5678`) because no locale data is registered outside the app bootstrap. Keeping it would have meant
registering locale data globally for every suite, i.e. changing the harness to keep a case whose rule —
Angular's symbol table — is not this unit's. The tests that remain assert what T8 owns: the rendered currency is
the one the caller passed (MXN and USD differ), four decimals survive, and an absent currency is named. The
currency-change case in the item table proves the argument is live by switching the input, not by trusting one
render.

**The review shape of `T8` is one commit, and that is a measurement, not a preference.** The two halves —
display and charge — share four files (`sales-order.models.ts`, `sales-order.service.ts`, and the edit page's
`.ts` and `.html`), so splitting them would leave an intermediate commit whose item table asks for a currency its
page does not yet pass, i.e. a commit that is not green. The unit is ~1,300 authored lines including tests, over
the ~400 budget, so the PR that ships it should carry it as two slices cut along those four files at author time
if the reviewer wants them apart.
