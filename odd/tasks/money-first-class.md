# ODD feature: money-first-class

**Repository**: LifeControl — `life-control-api` (migrations, `salesorder`, `paymentmethod`), `life-control-app-angular` (the POS money surface), `odd/tasks/`
**Branch (planned)**: `feat/money-first-class`
**Status**: blocked — this record, the evidence under `## Problem`, the ADR acceptance that travels with it
and the slice order are the content; the code is not. One thing outside this record holds the work:
T5–T7 consume `sales_orders.company_id`, the document state guard and the ledger booking, which ADR-0003,
ADR-0004 and ADR-0001 own and which no migration in the tree provides. D-c is decided (`companies.currency`),
and decision 2, the `## Schema` block and migration step 1 of the accepted ADR state it. T9 is closed
(applied with the acceptance) and edits no decision.
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
| T1 | S1, S2 | Migration: the `currency` catalog (`code CHAR(3)`, `minor_units` bounded 0–4, `symbol`, `enabled`), its seed, and `companies.currency CHAR(3) NOT NULL REFERENCES currency (code)` with its backfill and the now-required field on the company create path — **D-c**, decided 2026-10-30: no `DEFAULT`, because a silent `MXN` is the default-that-hides failure class. Its one-sentence amendment to decision 2 precedes it | — | inline |
| T2 | S1, S2, S3, S7 | Migration: the breakdown and the scale on both sales tables — `sales_orders` (`currency`, `subtotal`, `discount_total`, `tax_total`, `rounding_adjustment`, `total_amount` → `19,4` and `NOT NULL`, `ck_order_total`, `ck_order_parts_not_negative`, `ck_order_rounding_bounded`) and `sales_order_items` (four widened columns, `subtotal`, `tax_code`, `tax_rate`, `tax_amount`, the three name/attribute snapshots, `ck_item_subtotal`, `ck_item_tax`, `ck_item_total`), plus `discount_applied`'s `NULL` → 0 and `total_amount`'s `NULL` → 0 before the `NOT NULL`s. Additive, backfill, then constrain — never the reverse | T1 | inline; two commits, one per table |
| T3 | S1, S7 | The pre-flight report of migration step 2 as a runnable script, emitting the drift per order and naming the two verdicts (≤ one minor unit → `rounding_adjustment`; larger → a manual decision, not the migration's) | — | inline |
| T4 | S1 | Migration: `tax_rate` (`company_id`, `tax_code`, `rate`, `effective_from`, `enabled`, the unique key) and the server-side resolution of the latest rate with `effective_from ≤` the document date, copied onto the line at write time. The `tax_code` vocabulary is **D-d** | T1, D-d | inline |
| T5 | S1, S4 | Migration: `sale_payment`, `sale_payment_void`, the three indexes, the append-only pair **on both tables** — trigger plus `REVOKE UPDATE, DELETE`, because `V52:196-197` hands the application role all four privileges on every new table — `payment_methods.is_cash` **with its backfill** (D-b), and the registration of all three sales tables in `tenant_table`. Blocked: `fk_payment_order_same_company` needs `sales_orders.company_id`, which ADR-0003 owns | ADR-0003 | inline |
| T6 | S1, S5, S6 | `chargeSalesOrder` rewritten: lock the order, validate ADR-0004's guard, insert the payments, compute the breakdown, book the stock through ADR-0001's API, append the transition and publish `SaleCharged` **only with a named consumer** (g) — one transaction | T2, T4, T5, ADR-0001, ADR-0004, **D-e** | inline |
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
recommendations, not decisions: the maintainer has not answered them.

| ID | Recommendation | Why not the alternative |
| --- | --- | --- |
| D-a | **Skip the order and report it — and measure before deciding.** T3's pre-flight emits the count and the ids; at zero the decision evaporates, at a non-zero count it is taken per row with the list in hand | A sentinel method lands in a **global** catalog with a full ABM (`PaymentMethodController` has POST/PUT/DELETE/PATCH, `PaymentMethodRequest` is name/short-name/enabled), visible in every tenant's POS. A missing payment row says "unknown", which is true; an invented one states how a sale was paid when the data never said |
| D-b | **Backfill in the migration, by the seed's exact name (`Efectivo`, `V3:385`), with a `RAISE NOTICE` when it affects zero rows — and give the ABM an `isCash`.** `PaymentMethodRequest` carries no such field today, so a method created after the deploy can never be cash and M4 refuses its change forever with no UI path | An operator step in a comment fails at the counter, not at the deploy: the cashier discovers it when the customer asks for change. The notice is what makes a renamed catalog say so instead of passing silently |
| D-d | **A platform `tax_code` catalog**, the same shape as `currency` (no `company_id`, no policy): `tax_rate` gets the FK, the line keeps the text snapshot that decision 4 requires | Free text cannot carry what the tax actually needs: CFDI separates Tasa 0% from Exento, and both have `rate = 0` — a string plus a rate cannot tell them apart, and the first typo taxes a line at zero without an error. If T4 slips, T2's `'NA'` with `tax_total = 0` stays valid |
| D-e | **Add the 0002 → 0004 edge and move ADR-0004 ahead of T6. Do not split T6** | M3, M4 and M10 *are* the safety properties of taking money (no Completed sale uncovered by payments, change only on cash, no double charge on a retry). Splitting T6 writes `chargeSalesOrder` twice, the second time under a different state mechanism, and leaves that window open precisely while the payment code is least proven. `docs/adr/README.md` justifies 0004 last as "benefits from a settled shape" — backwards: its shape is an **input** of money |

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
