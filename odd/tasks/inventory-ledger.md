# ODD feature: inventory-ledger

**Repository**: LifeControl — `life-control-api` (migrations, `inventory`, `salesorder`, `purchaseorder`,
`goodsreceipt`, `product`), `docker/`, `odd/tasks/`
**Branch**: `feat/inventory-ledger` · **Worktree**: `~/workspace/LifeControl-worktrees/feat-inventory-ledger`
**Status**: implemented — the record, its spec citations and the slice order are the content so far; no
slice of the ADR has landed yet. T1–T6 are all open and each names what it waits on: T1 is next, T2 needs
the location key T1 lands, and T3–T6 follow in order.
**Created**: 2026-11-02
**Risk**: items 1, 2, 3 and 6 — item 1 because the backfill rewrites stored quantities and the drops
delete the only copy of two balances, item 2 because the ledger decides who may write stock and a missing
guard is silent, item 3 because `POST`/`PUT` store reads change shape under an Angular POS that already
calls them, and item 6 because no test today would notice a reversal that frees the wrong rows.

## Objective

Implement accepted ADR-0001 (`docs/adr/0001-inventory-ledger-single-source.md`, accepted 2026-11-02) in
`life-control-api`: one append-only `stock_movement` ledger is the only authority on quantity, balances are
disposable projections, consumption is recorded, and cost is part of the model. Issue: **#739**.

## Specs

S1. `docs/adr/0001-inventory-ledger-single-source.md`, **accepted 2026-11-02**, is the specification of this
    unit. Its decision items 1–5, D1.a, the `## Schema` block, invariants I1–I8, `## Costing and
    valuation` and `## Migration from the current tree` are authoritative, in that order, including the
    three corrections its `## Corrections applied before acceptance` section records.

S2. **Decision 1, verbatim**: «**One fact, one row.** Every change of quantity is one `stock_movement` row.
    Nothing else writes quantity. Rows are never updated and never deleted: the table is append-only, and a
    reversal is a new row that names the row it reverses.»

S3. **Decision 2, verbatim**: «**Projections are derived, rebuildable, and never authored.** `stock_balance`
    (per location) and `stock_position` (per store) are maintained incrementally by a trigger on
    `stock_movement` as a fast path — and rebuilt in full by a function that replays the ledger. The rebuild
    is what makes them disposable, and it is the operation no design in this tree has today. A verification
    query compares projection against replay and fails loudly.»

S4. **Decision 3, verbatim**: «**Consumption is recorded, not recomputed.** Which inbound row each outbound
    quantity consumed is stored in `stock_allocation`. A reversal therefore frees exactly the rows the
    original consumed, by construction, instead of re-deriving a remainder from a reference id.»

S5. **Decision 4, verbatim**: «**Costing is part of the model** (S2). Each inbound row records what was paid
    (`unit_cost`, `currency`); each allocation records the cost applied to the units it consumed. A reversal
    restores value, not only quantity.»

S6. **Decision 5, verbatim**: «**A receipt creates availability** (S1). An inbound movement no longer
    requires a pre-existing store-scoped row: it upserts the projection. Receiving into a store that never
    stocked the variant is a normal operation, and whether that variant is *sellable* there is a separate
    question owned by `variant_listing` (ADR-0006).»

S7. **D1.a, verbatim**: «`store_areas` already carries `company_store_id` (`V5:11`); `store_zones` and
    `store_locations` do not. Each level gains it, with `UNIQUE (company_store_id, id)`, so that
    `(company_store_id, store_location_id)` becomes a composite foreign key any operational row can hold.
    This is the same mechanism `V48`/`V49` used for the store itself: an inadmissible pair becomes
    unwritable instead of being checked at 150 call sites.»

S8. **The three corrections, which bind the order**: the migration steps take the next free number when
    they land and are named by content; `stock_movement.currency` and `stock_valuation.currency` reference
    the `currency` catalog that `V57` created; and D1.a runs **before** the ledger tables, because
    `fk_movement_location` references `store_locations (company_store_id, id)`.

S9. **Out of scope, from the ADR and the issue**: the money side of a sale (ADR-0002), the per-tenant
    document number (ADR-0003), the typed state machine (ADR-0004), `tax_rate`, `variant_listing.tax_rate_id`,
    a ledger retention or compaction policy, and `odd/tasks/catalog-identity.md` T6, which lands in its own
    slice once `stock_movement` exists.

## Tasks

| ID | S# | Work | Depends on | Route |
| --- | --- | --- | --- | --- |
| T1 | S7, S8 | `V59`: `store_zones` and `store_locations` gain `company_store_id`, backfilled through their parent, `NOT NULL` after the backfill, `UNIQUE (company_store_id, id)` on the two of them plus `store_areas`, the two composite keys replacing the plain ones, and the two `BEFORE INSERT` triggers that derive the owner — plus the read-only mapping on the two entities. Suite: the two derivations, the two refusals by constraint name, and "the refusal stored nothing" | — | inline |
| T2 | S2, S5, S8 | `V60`: `stock_movement`, `stock_allocation`, `stock_balance`, `stock_position`, `stock_valuation`, `companies.costing_method`; the append-only trigger; the two partial indexes; the grants that leave the application role `INSERT, SELECT` on the first two; armed on `tenant_table` in the file that creates them | T1 | inline |
| T3 | S3, S4, S6 | The projection trigger, the full-replay rebuild function, the allocation writer, and the `I2`/`I6` verification query — with the decisions the ADR leaves open (whether `stock_valuation` is trigger-maintained or rebuild-only, and where the verification job lives), taken here and recorded under `## Log` | T2 | delegated writer, disjoint from T4 |
| T4 | S6 | The backfill by replay (Path A) and the `I2`/`I6` assertions before commit | T2 | inline |
| T5 | S1, S6 | The readers move: POS search, the store-scoped variant reads and the stock search answer from `variant_listing` ⨝ the projection; the per-store manual edit becomes a movement with a `reason` | T3, T4 | delegated writer |
| T6 | S2, S9 | The drops (`product_variant_locations.stock`, `product_variant_store_stock.stock`, `inventory_movements` after migration) and the deletion of `InventoryService`, with `InsufficientStockException` moved to `inventory.exception` | T5 | inline |

## Log

**L1** — «puedes iniciar con adr0001»

**L2** — «si has el PR y el merge y continua»

**2026-11-02 — the acceptance slice, before this record existed.** ADR-0001 was accepted in
`docs/adr-0001-accepted` (commit `549ce490`, PR #740, merged as `e4bfbb9b`) after the maintainer approved
the three corrections. That slice touched no migration: it is the authority this record cites, not work of
this unit.

**2026-11-02 — scope decided.** The ADR's six-step migration is cut into six tasks, one per reviewable
slice, because step 3 (the projections and the rebuild) and step 4 (the readers) are each larger than the
~400 authored lines a slice is allowed. T1 lands alone: `store_zones` and `store_locations` are the two
levels `V44`–`V50` left behind, and `stock_movement`'s location key cannot be written without them. T3 is
the only task whose decisions are not already taken by the ADR; both are named in its row and will be
recorded here when the slice lands, not settled by silence.
