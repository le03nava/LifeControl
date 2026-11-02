# ODD feature: architecture-redesign-adrs

**Repository**: LifeControl — `docs/adr/` (new), `odd/tasks/`
**Branch**: `docs/architecture-redesign-adrs`, then `docs/adr-0005-accepted` for the acceptance
**Worktree**: `~/workspace/LifeControl-worktrees/docs-architecture-redesign-adrs`, then
`~/workspace/LifeControl-worktrees/docs-adr-0005-accepted` for the acceptance
**Status**: implemented — the six ADRs, their index and this record are the set's content. The redesign
ADR-0002, ADR-0003 and ADR-0004 describe is not implemented, and ADR-0001's is partly (T1–T5c are in
`main`, T5d is open on the ledger's per-write identity, which no ADR supplies). **All six are accepted**:
ADR-0005 (2026-10-29), ADR-0002 (2026-10-30), ADR-0006 (2026-11-01), and ADR-0001, ADR-0003 and ADR-0004
(2026-11-02). This record makes no delivery claim. Nothing is deferred.
**Created**: 2026-10-29
**Risk**: none — documentation only. No code, no schema, no runtime surface.

## Objective

Record the target architecture of the product/inventory/commerce core as six ADRs under
`docs/adr/`, so the redesign has one versioned source of truth instead of a conversation, and so each
future slice can cite the decision it implements.

## Problem

The system is pre-production, and that is the only moment a model change is cheap. The tree already
uses `TRUNCATE` as a design tool: `V14__product_variant_definition.sql` emptied the whole operational
chain and `V40__product_catalog_company_contract.sql` emptied the catalog. Pre-production it costs a
re-run; in production it costs a window, a backup and a risk of data loss.

The model carries six structural problems, each measured in the tree:

1. **Three representations of one stock fact** — `product_variant_store_stock.stock` (V13),
   `product_variant_locations.stock` and `inventory_movements` (V10) — held together by
   `InventoryService`'s discipline, not by the schema (`InventoryService.java:52-84`), with
   `V15__inventory_balance_reset.sql` as the record of the one time it failed.
2. **Package cycles inside the "modular monolith"** — `inventory` imports
   `salesorder.exception.InsufficientStockException` (`InventoryService.java:13`) while `salesorder`
   imports `inventory`; `product` and `inventory` import each other.
3. **Tenancy by discipline** — `company_id` on 15 tables, 150 ad-hoc access checks, and zero
   Row-Level Security policies in 51 migrations.
4. **State as strings in a lookup table** — 32 comparisons against `"Draft"`/`"Active"`/`"Cancelled"`,
   transition maps hand-written in the service (`SalesOrderService.java:67`), no history.
5. **Fat services over an anemic model** — `SalesOrderService` 1107 lines, `PurchaseOrderService` 927,
   `InventoryService` 833, `ProductVariantService` 634.
6. **A sale model that is not accounting-grade** — `chargeSalesOrder` is a status flip plus
   `payment_method_id` (`SalesOrderService.java:386-446`): no payment row, no currency, no tax
   breakdown; `sales_orders.order_number` is globally `UNIQUE` (`V1:470`) and generated with `max + 1`
   (`:922-941`).

The catalog branch of the same conversation produced three more: `product_variants.bar_code` is
`NOT NULL` for every variant including a single-variant article, `measure_unit_id` is optional (V29),
and "sellable in this store" and "there is stock in this store" are the same row.

## Specs — the affected requirements, in the user's words

**S1** — «1 si puede crear la disponibilidad» — an inventory receipt may create availability for a
store that never stocked the variant; a store-scoped stock row is not a precondition of receiving.

**S2** — «si que entre en el rediseño» — costing is in scope: the cost of an article is part of the
target model, not deferred to a later phase.

**S3** — «solo explicame el alta de un articulo su variante y el alta en la tienda como quedan y que
depende de que» — the three creations (article, variant, store listing) have an explicit order and an
explicit dependency.

**S4** — «entonces si se tiene que crear una variante o se crea sola?» — whether the variant row is
written by the operator or by the backend is a recorded decision, not an implementation detail left
implicit.

**S5** — «si contina con los adrs» — deliver the ADR set for decisions D1–D5, including the target DDL
of `product`, `variant` and `variant_listing`, and the migration plan from the current schema.

## Tasks

| ID | Spec | What | Commit |
| --- | --- | --- | --- |
| T1 | S5 | Record file + the ADR index with adoption order and inter-ADR dependencies | `d7fa13ca` |
| T2 | S2 | `docs/adr/0001` — inventory is a single append-only ledger, with costing and valuation | `d7fa13ca` |
| T3 | S5 | `docs/adr/0002` — money as a first-class value: currency, breakdown, payment rows, tax | `d2a26777` |
| T4 | S5 | `docs/adr/0003` — document identity per tenant: `document_sequence`, the end of `max + 1` | `55e76351` |
| T5 | S5 | `docs/adr/0004` — typed state machine with a transition history | `55e76351` |
| T6 | S5 | `docs/adr/0005` — tenant isolation by Row-Level Security | `25c74f83` |
| T7 | S1, S3, S4 | `docs/adr/0006` — catalog identity: `variant_mode`, required measure unit, barcode per mode, `variant_listing` | `25c74f83` |

## Log

**L1** — «para comprar o vender un producto se ocupa tener una variante? que se ocuparia para tener
productos sin variantes y se puedan comprar y vender»

**L2** — «para un futuro cual seria la mejor forma para administrar y mantener el sistema, ahorita el
sistema no esta productivo, analiza y dime cual es la mejor opcion sin importar el costo y se tenga
que rediseñar»

**L3** — «si arma el diagrama»

**L4** — «1 si puede crear la disponibilidad, si que entre en el rediseño, solo explicame el alta de un
articulo su variante y el alta en la tienda como quedan y que depende de que»

**L5** — «entonces si se tiene que crear una variante o se crea sola?»

**L6** — «si contina con los adrs»

**2026-10-29 — scope decided.** The ADR set covers D1–D5 from the analysis plus a sixth, D6, because
the catalog rules that the conversation settled (`variant_mode`, required measure unit, barcode per
mode, `variant_listing`) were decided but had no home; folding them into D1 would have hidden a
catalog decision inside an inventory ADR. The three answers L4/L5 produced are recorded in ADR-0006
(S3, S4) and ADR-0001 (S1, S2).

**2026-10-29 — status is `proposed`, deliberately.** An ADR authored by an agent is a proposal. The
`accepted` transition is the maintainer's decision and is not claimed by this record; the ADR set is
written so each one can be accepted or rejected on its own.

**2026-10-29 — four commits, each inside the review budget.** `d7fa13ca` carries the index and
ADR-0001 (329 lines), `d2a26777` ADR-0002, `55e76351` ADR-0003 and ADR-0004, `25c74f83` ADR-0005 and
ADR-0006 (367 lines). ADR-0005 and ADR-0006 were written by two delegated writers with disjoint
surfaces; ADR-0003 through ADR-0006 had every `path:line` they cite opened and read before its commit.

**2026-10-29 — three defects found on review, corrected before their commit.** (a) ADR-0002 declared
`doc_type`/`seq_period`/`seq_value` on `sales_orders`, which ADR-0003 owns: two `ALTER`s in two ADRs
is a migration applied twice, so 0002 now references them and owns none. (b) ADR-0002's
`ck_payment_tender` called `method_is_cash(method_id)` inside a `CHECK`, which PostgreSQL refuses
because a `STABLE` function is not admissible there; it is now a self-consistent arithmetic check,
with `payment_methods.is_cash` and the ADR-0004 guard carrying the cash-only rule. (c) ADR-0003 forced
`period ~ '^[0-9]{6}$'` while calling the period configurable per document type; the column widened to
alphanumeric `VARCHAR(8)` and `YYYYMM` became its default rather than a schema assumption. Two smaller
gaps closed the same way: ADR-0005 gained `ALTER DEFAULT PRIVILEGES`, so a table created by a later
migration is readable by the application role, and ADR-0006 states that its trigger is created after
the `variant_mode` backfill, because the default would otherwise refuse the update that classifies a
`MULTI` product.

**2026-10-29 — verification.** `odd-status-headers.test.sh` runs green over 129 records (78 assertions,
0 failed), and no ADR claims a status other than `proposed`.

**2026-10-29 — the adoption order is accepted, the ADRs are not.** The maintainer confirmed the order
in `docs/adr/README.md` — RLS, then catalog, then the ledger, then documents, then money, then states —
so it is now the agreed plan for what gets implemented first rather than a recommendation. That
acceptance deliberately does not travel to the six ADRs: each stays `proposed` and needs its own
decision, taken per ADR.

**2026-10-29 — ADR-0005 is accepted, after the three corrections it needed to be admissible.** The
maintainer chose to close them before the status flipped rather than list them as implementation notes:
`companies` is isolated by `id` and did not fit a helper that hardcoded `company_id`; the grants covered
tables and left the application role without `USAGE` on sequences, so every identity `INSERT` would have
failed at runtime; and `FORCE` filters the owner, which is the role that runs Flyway, so a migration
`UPDATE`ing tenant rows would have touched 0 rows in silence — the trap `TRUNCATE` hides, because RLS is
not applied at table level. The decision is unchanged; the document now says what it meant, and the
corrections are recorded in the ADR itself, not only here. `docs/adr/README.md` carries the status and
the other five ADRs stay `proposed`. The SQL was not executed: no runner was authorized.

**2026-10-30 — ADR-0002 is accepted, and this header's count was repaired in the same edit.** The
maintainer accepted `docs/adr/0002-money-first-class.md`; `docs/adr/README.md` carries the status and the
`Accepted:` line. Two of the header's words became false the moment it was accepted ("the other five are
`proposed`"), so the sentence now names the two accepted ADRs — the only change to this line, and only
because rule 5 of `references/feature-records.md` repairs a claim that is false *now*. Nothing else in this
record was touched. The acceptance also carries three evidence citations corrected against the tree
(`purchase_order_details`, `V1:477`, `V1:496`) and names the three implementation decisions it does not
settle, which live in `odd/tasks/money-first-class.md`.

**2026-11-01 — the adoption order's steps 5 and 6 swapped, on the maintainer's decision.** `docs/adr/README.md`
listed ADR-0002 fifth and ADR-0004 sixth, while ADR-0002's own `Depends on` line had carried the 0002 → 0004
edge since 2026-10-30 — so the document said money consumes states and the plan implemented money first. The
evidence and the two rejected shapes are **D-e** of `odd/tasks/money-first-class.md`, and that record carries
the decision. The order now reads 0005 → 0006 → 0001 → 0003 → 0004 → 0002. No ADR changed status, no ADR's
decision changed, and no task changed its dependencies: only the sequence between 0004 and the money unit
moved, and the reason the original order gave for 0004 last ("it benefits from a settled shape") was the one
sentence of it that was backwards.

**2026-11-01 — ADR-0006 is accepted, and the header's count was repaired in the same edit.** The
maintainer accepted `docs/adr/0006-catalog-identity.md` in the session that opened
`odd/tasks/catalog-identity.md`; `docs/adr/README.md` carries the status, and the header's "the other
four are `proposed`" became "three" because accepting one makes that word false — rule 5 of
`references/feature-records.md`, the same repair the ADR-0002 acceptance needed. The acceptance also
recorded one fact that is not a decision and not drift: the accepted `## Schema` block references
`currency (code)` and `tax_rate (id)`, which no migration in the tree provides, so 0006's listing slice
sequences after ADR-0002's T1 and T4 while its `variant_mode`, unit and barcode slices do not. That
edge is stated in the ADR's `Accepted:` line and in `docs/adr/README.md`, because the `Depends on`
column mirrors each ADR's own line and 0006's still names only ADR-0005. Nothing else in this record
was touched.

**2026-11-02 — ADR-0001 is accepted, after the three corrections it needed to be executable.** The
maintainer chose to close them before the status flipped, the same way ADR-0005 was accepted, rather than
carry them as implementation notes: the migration plan named `V52`–`V55`, which ADR-0005 and ADR-0006
have all taken, so the steps are now named by content and each takes the next free slot when it lands;
`stock_valuation` held `total_value` and `avg_unit_cost` with no currency, and both currency columns now
reference the `currency` catalog `V57` created; and D1.a — `store_zones` and `store_locations` gaining
`company_store_id` and `UNIQUE (company_store_id, id)` — is a precondition of `fk_movement_location`, not
a detail, and `V7`'s `store_locations` has no such column, so the `## Schema` block did not compile as
written. The decision itself is unchanged. One sentence of `docs/adr/README.md` was also repaired because
this acceptance made it false in a second direction: it said ADR-0006's listing slice could not run before
ADR-0002's T1 and T4 — T1 has landed (`V57`) and the listing has landed (`V58`) reading it, while
`tax_rate` still does not exist and `V58:26-31` names the departure it took instead. That sentence says
"the redesign they describe is not implemented", and the word "they" had already stopped being true for
0005 and 0006, so it now names the two ADRs it does describe: 0001 and 0002. The third acceptance-count
repair is the header's "the other three are `proposed`", now two. `0003` and `0004` remain `proposed`,
and each still needs its own decision.

**2026-11-02 — ADR-0003 was read against the tree and four defects came out; the document was corrected
before any acceptance decision.** The decision itself is untouched and still unfixed: `SalesOrderService:922`
and `PurchaseOrderService:816` still build `max + 1` over a global counter, and `GoodsReceiptService:527`
still derives a receipt's number from its parent's. What was wrong was the plan, and each defect is a kind
this set has already paid for once. **(1)** The `## Schema` block carried `-- V53. The next free version`
and step 4 named `V54` for the Java render migration; both numbers are spent
(`V53__tenant_isolation_policies.sql`, `V54__product_variant_mode.sql`) and the head is `V63`, so the plan
now takes `V64` and `V65` — the same collision ADR-0001's acceptance found in `V52`–`V55`. **(2)** The ADR
was written before ADR-0005's machinery existed and named RLS once, in its `Depends on` line; its steps 1
and 3 read and rewrote every document row, and `V53:126` arms `purchase_orders` while `V52` sets
`FORCE ROW LEVEL SECURITY`, which filters the owner — the role Flyway runs as. So the pre-flight report
would have reported zero defects and the backfill would have numbered nothing, both green, for the reason
`V54:26` already records. The plan now opens a `NO FORCE` window as step 0 with `V57:85`'s two self-checks,
closes it with a re-check as step 5, and gives `V65` its own window because a window that spans two files
is one a crash can leave open (`V55:100`). **(3)** N9 claimed the counter was "registered in ADR-0005's
policy list, whose startup assertion fails if a `company_id` table is not listed", and the `## Schema`
block never called `apply_tenant_isolation` for any of the three tables — a claim of enforcement the
schema did not deliver, which is the defect class #706/#707/#708 corrected. It matters more than it reads:
`unprotected_tenant_tables()` keys on the **column's existence**, so adding a nullable `company_id` in
step 2 and arming later is a deployment that refuses to boot, and the arming is now step 6 of the same
delivery. **(4)** Decision 4 named the period's source as "( `order_date`, `order_date` )" and decision 7
pinned `doc_type` to `status_transition.document_type` of ADR-0004 — a table that does not exist, in an ADR
that is still `proposed`. It is now stated as a spelling contract this ADR fixes first, not a schema
dependency, so accepting 0003 neither waits for 0004 nor accepts it. One consequence the first draft did
not name is now recorded as a cost: `sales_orders` and `goods_receipts` become tenant tables for the first
time, so arming them changes **who can read them** — `/api/sales-orders` and `/api/goods-receipts` have no
`{companyId}` segment, and `TenantScopeFilter` resolves their tenant from a single `company_id` claim. The
header is unchanged: `0003` is still `proposed` and the decision is the maintainer's.

**2026-11-02 — ADR-0003 is accepted, on the corrected document, and two headers were repaired because
they said otherwise.** The maintainer chose acceptance in the same PR that carries the corrections, the
way ADR-0005 was accepted. `docs/adr/0003-document-identity-per-tenant.md` flips to `accepted` with an
`Accepted:` line pointing at its new *Corrections applied before acceptance* section, and
`docs/adr/README.md` carries the status in its column and the date in the adoption-order paragraph, whose
last sentence said "`0003` and `0004` are the two still `proposed`" — one word made false by this
decision, repaired here rather than left to rot. This record's own header carried the same falsehood in
two places and both were repaired in this edit: the acceptance list gains "and ADR-0003 (2026-11-02)"
and "the other two are `proposed`" becomes "ADR-0004 is the one still `proposed`", and the sentence naming
which ADRs describe a redesign that is not implemented now names three — 0001, 0002 and 0003 — because
accepting an ADR is not implementing it. The earlier log entry above ends by saying `0003` is still
`proposed` and that the decision is the maintainer's; that was true when it was written and it is not
true now, and the honest repair is this entry, not an edit to a statement that was accurate at its time.
Nothing in the ADR's decision changed after the corrections. `0004` is the only ADR left `proposed`.

**2026-11-02 — ADR-0004 was read against the tree and seven defects came out; six were corrected and the
seventh is a decision that is not this ADR's to make.** Its decision is untouched and still unimplemented:
both services still hold their maps as `Map<String, Set<String>>`, the `"Draft"|"Active"|"Cancelled"|
"Completed"` count is still exactly **32**, and `status_id` is still the authority. Every cited
`path:line` still resolves, which is worth saying because a two-week-old ADR in this tree usually has
drifted lines; it did not. **(1)** `ck_purchase_order_details_status` admitted three values that were the
**item** set — `PENDING`, `ADDED`, `CANCELLED` — under a comment claiming "its own enum, not a copy of the
item one". The detail machine has seven states (`PurchaseOrderService:83-90`) and `V3:187-229` seeds all
seven, so the CHECK omitted five real ones and admitted `ADDED`, which no detail can hold. **(2)**
`statuses.code VARCHAR(24) NOT NULL` contradicted the scope paragraph four pages above it: the catalog is
one table for seven families, this ADR puts `APPOINTMENT` and employment out of scope by decision, and
those nine rows (`V18:59-86`, `V20:60-78`) have no constant to carry. T5 made it a second contradiction by
requiring a code from every row. The column is now nullable, T5 is scoped to the five families with an
enum, and step 4 asserts in the migration that those five are fully coded — the assertion, not a `NOT
NULL`, is what stops a row slipping past the join. **(3)** `status_transition` carries `company_id` and was
never armed, while the `Depends on` line claimed it "carries a policy" — the same claim-of-enforcement gap
ADR-0003's N9 had, one day after it was corrected there. The `apply_tenant_isolation` call is now in the
schema block, step 8 runs it last (arming before the seed would refuse the seed), and T7 cites it. **(4)**
The plan had no `NO FORCE` window, and it needed three: `purchase_orders` is armed today (`V53:126`) and
`sales_orders`/`goods_receipts` become armed with ADR-0003. The third failure is the one that would have
been hardest to read: step 6's seed queues deferred triggers whose own `SELECT`s read the documents, so
they fail closed with a null state and abort. Step 0 opens the window with `V57:85`'s two self-checks and
step 7 closes it. **(5)** The append-only grant was credited to ADR-0005 and never written; `V52:190-197`
grants `lifecontrol_app` UPDATE and DELETE on every table **and** the default privileges that reach tables
created later, so `V60:286-290`'s rule applies: the `REVOKE`/`GRANT` pair belongs in this file, and it is
there now. **(6)** `Depends on` named only `sales_orders.company_id` while `document_tenant_and_state` also
reads `goods_receipts.company_id`; both arrive with ADR-0003. Two smaller repairs came with them: the plan
said "the three deferred triggers" where there are five, and the seed now runs **after** the triggers so
decision 6 validates the migration's own rows instead of being the one write nobody checked. **(7)** The
seventh is not a plan defect and is not corrected by rewriting: ADR-0001's T5d was closed as "blocked on
ADR-0004's per-transition identity", and this ADR does not supply one — it cannot, since a quantity edit
books a second movement under the same `itemId` with no state move at all. ADR-0004 now carries a `## What
this ADR does not unblock` section, and `odd/tasks/inventory-ledger.md`'s header and a new log entry say
the same thing. The acceptance decision is still the maintainer's: `0004` remains `proposed`.

**2026-11-02 — ADR-0004 is accepted on the corrected document, and the set has no `proposed` ADR left.**
The maintainer chose acceptance in the same PR as the corrections again, the ADR-0005 and ADR-0003
precedent. `docs/adr/0004-typed-state-machine.md` flips to `accepted` with an `Accepted:` line pointing at
its *Corrections applied before acceptance* section, and three statements that said `proposed` were
repaired in the same edit: the README status column, the README adoption-order paragraph whose count has
now been wrong six times — it says so, because the sentence that keeps being falsified is the one worth
marking — and this record's header, which now names which redesign is unimplemented instead of counting
the accepted ones. The header also stops implying that ADR-0001 is wholly unimplemented: T1–T5c are in
`main` and only T5d is open. One thing the acceptance does **not** do, and both `docs/adr/README.md` and
the ADR itself now say so: it releases no ledger debt. T5d was closed as blocked on ADR-0004's
"per-transition identity", which the ADR does not supply and cannot, and the decision that would — the
ledger's per-write identity, three shapes recorded in `odd/tasks/inventory-ledger.md` — is still the
maintainer's. ADR-0004's decision itself is unchanged by the acceptance and is still unimplemented.
