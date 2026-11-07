# ODD feature: money-first-class

**Repository**: LifeControl — `life-control-api` (migrations, `salesorder`, `paymentmethod`), `life-control-app-angular` (the POS money surface), `odd/tasks/`
**Branch (planned)**: `feat/money-first-class`
**Status**: implemented — T1 is the currency catalog and `companies.currency`, T2 the breakdown and the scale on
both sales tables, T3 the runnable pre-flight of migration step 2, T4 the platform `tax_code` catalog with its
tenant rate table and the resolver, and T5 the payment rows, their voids and the cash flag the guard reads. The
record, the evidence under `## Problem`, the ADR acceptance that travels with it and the slice order are the
rest. Nothing is dropped or deferred silently: T4 owes the copy of the rate onto the line until some unit states
a line's `tax_code`, T5 owes the historical payment backfill of ADR-0002 step 4, and T6 waits on ADR-0001 and
ADR-0004, each named in its own `Tasks` row. One thing outside this record holds what is left: T6 and T7
consume the document state guard and the ledger booking, which ADR-0004 and ADR-0001 own — and
`sales_orders.company_id`, the column that used to be named here as another unit's to deliver, exists since
`V64`. Four decisions are taken: D-c (`companies.currency`), which decision 2, the `## Schema` block and
migration step 1 of the accepted ADR state; **D-e** (2026-11-01), which keeps the 0002 → 0004 edge and moves
ADR-0004 ahead of T6 in `docs/adr/README.md`'s adoption order so the charge rewrite is written once, after the
guard; and **D-d** and **D-b** (2026-11-05), which the tax and payment slices carry. T9 is closed (applied with
the acceptance) and edits no decision.
**Created**: 2026-10-30
**Risk**: items 1 and 3 — item 1 because the migration rewrites stored totals on financial rows and a
mis-stated backfill is not detectable by any later test, and item 3 because `SalesOrderRequest`,
`SalesOrderResponse`, `ChargeSalesOrderRequest` and `PATCH /api/sales-orders/{id}/charge` change shape
under an Angular POS that already calls them.

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
| T6a | S1, S5, S6 | **(in progress, 2026-11-06)** `chargeSalesOrder`, money half: `SELECT … FOR UPDATE` on the order row (M10), ADR-0004's guard extended with M3/M4/M8, one `CHARGE` payment row for the total from the existing single `paymentMethodId`, the breakdown written under the lock so M1 and M2 hold (exactly), the transition appended, and `SaleCharged` **not** published because no consumer is named (g) — one transaction. The many-payments surface stays in T7, per the 2026-11-06 log entry | T2, T4, T5, ADR-0004 | inline |
| T6b | S1, S5, S6 | **(pending, 2026-11-06)** The stock half of the same decision: the per-line deduction leaves `createSalesOrder`, the line add/edit/delete paths and their `V73` write sequence, and lands in the charge as one batch under the same order lock; a reversal then exists only for an order whose charge booked something. `sales-location-aware-stock` and `ledger-per-write-identity` are edited with it | T6a, ADR-0001 | inline |
| T7 | S1, S3 | API surface: `SalesOrderRequest`, `SalesOrderItemRequest`, `SalesOrderResponse`, `SalesOrderItemResponse`, `ChargeSalesOrderRequest` (many payments, tender and change, direction) and the controller contract, with the breakdown read-only and computed by the server | T2, T5 | inline |
| T8 | S1, S2 | Angular POS: the document's currency instead of `DEFAULT_CURRENCY_CODE`, four-decimal amounts without losing the display rounding, mixed tender with change, the item table showing the server's line parts, and the discard notice when a breakdown part cannot be represented | T7 | inline; disjoint surface from T2–T7, so a second writer is admissible |
| T9 | — | The four ADR corrections under `## Reconciliation`: `purchase_order_items` → `purchase_order_details`, the two drifted line citations, the `NOT NULL` method against a nullable column, and `is_cash` with no backfill | — | inline || T10 | S1–S7 | Tests: RED first per spec, M1 and M5 through the schema, M2 and M10 through the service under concurrency, M4 through the domain guard, and the scale/rounding boundary in place of the two-decimal assertions | T2–T8 | inline |

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
