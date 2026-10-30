# ODD feature: architecture-redesign-adrs

**Repository**: LifeControl — `docs/adr/` (new), `odd/tasks/`
**Branch**: `docs/architecture-redesign-adrs`, then `docs/adr-0005-accepted` for the acceptance
**Worktree**: `~/workspace/LifeControl-worktrees/docs-architecture-redesign-adrs`, then
`~/workspace/LifeControl-worktrees/docs-adr-0005-accepted` for the acceptance
**Status**: implemented — the six ADRs, their index and this record are the set's content. The
redesign they describe is not implemented. ADR-0005 is `accepted` (2026-10-29); the other five are
`proposed` and each needs its own decision. This record makes no delivery claim. Nothing is deferred.
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
