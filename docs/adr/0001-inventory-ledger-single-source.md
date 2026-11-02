# ADR 0001 — Inventory is a single append-only ledger

- **Status**: accepted
- **Date**: 2026-10-29
- **Accepted**: 2026-11-02, by the maintainer, after the three corrections recorded under
  *Corrections applied before acceptance*
- **Decision id**: D1 (+ D1.a) of `odd/tasks/architecture-redesign-adrs.md`
- **Depends on**: ADR-0005 (tenant isolation), ADR-0006 (catalog identity)
- **Blocks**: ADR-0002 (a sale books stock and money in one transaction)
- **Replaces**: the three-representation model of `V10__inventory.sql` and `V13__product_variant_store_stock.sql`

## Context

The same stock number is stored three times:

| # | Where | What it is |
| --- | --- | --- |
| 1 | `product_variant_store_stock.stock` (V13) | the aggregate a store sells from, and the number the operator sees |
| 2 | `product_variant_locations.stock` (V10) | the balance of one variant in one store location |
| 3 | `inventory_movements` (V10) | the append-only ledger that claims to explain every change |

The invariant `1 = SUM(2)` is not enforced by the schema. It is maintained by `InventoryService`, the
single writer, and documented as holding "by construction" (`InventoryService.java:52-70`). The same
docblock records why deriving one side from the other is refused (`:71-84`): a divergence repaired by
derivation would silently resurrect stock a sale already sold. `V15__inventory_balance_reset.sql`
exists because that divergence happened once in this tree, before a sale path wrote the ledger.

Two further defects are load-bearing for this decision:

- **The ledger is not self-sufficient.** Its rows carry `reference_type`/`reference_id` and nothing
  else, so a reversal is computed as "how much of this reference remains uncovered" by reading back
  the ledger (`SalesOrderService.java:948-962`). That arithmetic is a second, implicit data structure,
  and a refusal to re-run it is indistinguishable from a correct no-op.
- **A movement can name two different stores.** `inventory_movements` carries `company_store_id` and
  `store_location_id` with independent foreign keys (`V10:32-38`), and the location hierarchy reaches
  its store only through `store_locations → store_zones → store_areas → company_stores`. Nothing
  forbids a movement in store A naming a location of store B — the same class of leak that
  `V40__product_catalog_company_contract.sql` closed for products, still open here. The same is true
  of `product_variant_locations`, which stores no store at all.

The consequence is not a bug list; it is an authority problem. There are three numbers and no process
that says which one wins when they disagree.

## Decision

**`stock_movement` is the only authority. Balances are projections, and they are disposable.**

1. **One fact, one row.** Every change of quantity is one `stock_movement` row. Nothing else writes
   quantity. Rows are never updated and never deleted: the table is append-only, and a reversal is a
   new row that names the row it reverses.
2. **Projections are derived, rebuildable, and never authored.** `stock_balance` (per location) and
   `stock_position` (per store) are maintained incrementally by a trigger on
   `stock_movement` as a fast path — and rebuilt in full by a function that replays the ledger. The
   rebuild is what makes them disposable, and it is the operation no design in this tree has today.
   A verification query compares projection against replay and fails loudly.
3. **Consumption is recorded, not recomputed.** Which inbound row each outbound quantity consumed is
   stored in `stock_allocation`. A reversal therefore frees exactly the rows the original consumed,
   by construction, instead of re-deriving a remainder from a reference id.
4. **Costing is part of the model** (S2). Each inbound row records what was paid
   (`unit_cost`, `currency`); each allocation records the cost applied to the units it consumed. A
   reversal restores value, not only quantity.
5. **A receipt creates availability** (S1). An inbound movement no longer requires a pre-existing
   store-scoped row: it upserts the projection. Receiving into a store that never stocked the variant
   is a normal operation, and whether that variant is *sellable* there is a separate question owned by
   `variant_listing` (ADR-0006).

### D1.a — the location hierarchy carries its store

`store_areas` already carries `company_store_id` (`V5:11`); `store_zones` and `store_locations` do not.
Each level gains it, with `UNIQUE (company_store_id, id)`, so that `(company_store_id,
store_location_id)` becomes a composite foreign key any operational row can hold. This is the same
mechanism `V48`/`V49` used for the store itself: an inadmissible pair becomes unwritable instead of
being checked at 150 call sites.

## Schema

```sql
-- ─── the single fact ──────────────────────────────────────────────────────────
CREATE TABLE stock_movement (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id           UUID NOT NULL,
    company_store_id     UUID NOT NULL,
    store_location_id    UUID NOT NULL,
    product_variant_id   UUID NOT NULL,
    movement_type        VARCHAR(24) NOT NULL,
    quantity             NUMERIC(19,4) NOT NULL,
    unit_cost            NUMERIC(19,4),
    currency             CHAR(3) REFERENCES currency (code),
    occurred_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor                VARCHAR(255),
    source_type          VARCHAR(32) NOT NULL,
    source_id            UUID NOT NULL,
    reverses_movement_id UUID,
    reason               TEXT,

    CONSTRAINT fk_movement_store
        FOREIGN KEY (company_id, company_store_id)  REFERENCES company_stores (company_id, id),
    CONSTRAINT fk_movement_location
        FOREIGN KEY (company_store_id, store_location_id) REFERENCES store_locations (company_store_id, id),
    CONSTRAINT fk_movement_variant
        FOREIGN KEY (company_id, product_variant_id) REFERENCES product_variants (company_id, id),
    CONSTRAINT fk_movement_reverses
        FOREIGN KEY (reverses_movement_id) REFERENCES stock_movement (id),

    CONSTRAINT ck_movement_quantity CHECK (quantity > 0),
    CONSTRAINT ck_movement_type CHECK (movement_type IN (
        'RECEIPT', 'RECEIPT_REVERSAL', 'SALE', 'SALE_REVERSAL',
        'ADJUSTMENT_IN', 'ADJUSTMENT_OUT', 'TRANSFER_IN', 'TRANSFER_OUT')),
    -- cost travels inward: a row that adds quantity must say what it cost
    CONSTRAINT ck_movement_inbound_cost CHECK (
        movement_type NOT IN ('RECEIPT', 'ADJUSTMENT_IN', 'TRANSFER_IN')
        OR (unit_cost IS NOT NULL AND currency IS NOT NULL)),
    -- a reversal names what it reverses; nothing else does
    CONSTRAINT ck_movement_reversal CHECK (
        (movement_type LIKE '%REVERSAL') = (reverses_movement_id IS NOT NULL))
);

-- one movement per source document line and direction: a retried request cannot double-book
CREATE UNIQUE INDEX uq_movement_source
    ON stock_movement (source_type, source_id, movement_type)
    WHERE reverses_movement_id IS NULL;
-- at most one reversal per movement
CREATE UNIQUE INDEX uq_movement_one_reversal
    ON stock_movement (reverses_movement_id)
    WHERE reverses_movement_id IS NOT NULL;

-- ─── consumption, recorded ────────────────────────────────────────────────────
CREATE TABLE stock_allocation (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id      UUID NOT NULL,
    out_movement_id UUID NOT NULL REFERENCES stock_movement (id),
    in_movement_id  UUID NOT NULL REFERENCES stock_movement (id),
    quantity        NUMERIC(19,4) NOT NULL,
    unit_cost       NUMERIC(19,4) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_allocation_quantity CHECK (quantity > 0),
    CONSTRAINT ck_allocation_distinct CHECK (out_movement_id <> in_movement_id),
    CONSTRAINT uq_allocation_pair UNIQUE (out_movement_id, in_movement_id)
);

-- ─── projections: derived, disposable, never authored ─────────────────────────
CREATE TABLE stock_balance (             -- per location
    company_id         UUID NOT NULL,
    store_location_id  UUID NOT NULL,
    product_variant_id UUID NOT NULL,
    quantity           NUMERIC(19,4) NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (store_location_id, product_variant_id));

CREATE TABLE stock_position (            -- per store: the number the POS sells against
    company_id         UUID NOT NULL,
    company_store_id   UUID NOT NULL,
    product_variant_id UUID NOT NULL,
    quantity           NUMERIC(19,4) NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (company_store_id, product_variant_id));

CREATE TABLE stock_valuation (           -- cost of what is on hand, per store
    company_id         UUID NOT NULL,
    company_store_id   UUID NOT NULL,
    product_variant_id UUID NOT NULL,
    quantity           NUMERIC(19,4) NOT NULL,
    currency           CHAR(3) NOT NULL REFERENCES currency (code),
    total_value        NUMERIC(19,4) NOT NULL,
    avg_unit_cost      NUMERIC(19,4),
    PRIMARY KEY (company_store_id, product_variant_id));

ALTER TABLE companies ADD COLUMN costing_method VARCHAR(10) NOT NULL DEFAULT 'FIFO';
-- CHECK (costing_method IN ('FIFO', 'AVERAGE'))
```

Append-only is enforced twice: a `BEFORE UPDATE OR DELETE` trigger that raises, and a grant set that
gives the application role `INSERT, SELECT` only on `stock_movement` and `stock_allocation`.

## Invariants (each one testable)

| # | Invariant | Test |
| --- | --- | --- |
| I1 | `stock_movement` has no `UPDATE` or `DELETE` path at all | a direct `UPDATE` raises; the application role is denied |
| I2 | `stock_balance` and `stock_position` equal a full replay of the ledger | rebuild into a scratch table and compare; runs in CI and nightly |
| I3 | A movement's store is its location's store | the composite FK refuses the pair |
| I4 | At most one reversal per movement | `uq_movement_one_reversal` |
| I5 | A source document line books one movement per direction | `uq_movement_source`; a retried request is a no-op |
| I6 | `SUM(allocation.quantity)` per outbound row equals its `quantity` | one query; a violation means an allocation writer is incomplete |
| I7 | Every inbound row names a cost and a currency | `ck_movement_inbound_cost` |
| I8 | Balance can never be negative in a store that has no setting allowing it | the deduction refuses before writing, as today |

## Costing and valuation

- **Inbound** rows record `unit_cost` and `currency`: a receipt takes the purchase line's unit cost, an
  adjustment takes the operator's, a transfer takes the cost of the allocations it consumed (so value
  is conserved across stores). The currency is the company's operating currency — `companies.currency`
  (`V57`), which ADR-0002 decision 2 makes the only currency a document can be in — so a movement's
  `currency` is a row of the `currency` catalog and `stock_valuation` states its own, instead of leaving
  two amounts denominated in a unit nobody wrote down.
- **Outbound** rows carry no cost of their own. The cost applied to the units is in
  `stock_allocation.unit_cost`, resolved by the company's `costing_method` at the moment of consumption
  and frozen there.
- **`FIFO`**: allocations consume inbound rows oldest-first, so a sale's cost is the sum of
  `quantity × unit_cost` over its allocations, and `stock_valuation.total_value` is the value of the
  inbound rows not yet fully consumed.
- **`AVERAGE`**: the allocation carries the moving average in force at that instant; the average itself
  is recomputed by the valuation projection as it replays.
- **Rounding** happens at document boundaries, never per allocation: a line's cost is rounded once, and
  the remainder is carried by the projection. `AVERAGE` is the only policy where a replay can differ
  from an incremental run in the last decimal, which is why the rebuild is the reference.

Changing `costing_method` is not retroactive: it changes how new consumption is valued, and prior
allocations keep the cost they were given. That is what makes a re-issued document reproducible.

## Migration from the current tree

Two paths; the first is authorized today, the second is the one that still works after a production
cut-over, and it is the recommended one.

**Path A — synthesize the ledger from today's balances.** For every row of
`product_variant_locations`, one `ADJUSTMENT_IN` with `quantity = stock` and
`unit_cost = cost_price` (from `product_variant_store_stock`), `source_type = 'MIGRATION'`. The
ledger's sum then equals today's balances by construction, `product_variant_store_stock` rows become
`variant_listing` (ADR-0006), and nothing is lost.

**Path B — discard the operational chain**, as `V14` and `V40` already did. Legal while the system is
not productive; it is why this ADR should land before it is.

In either path, the order is below. Each step takes the **next free migration number when it lands**, and
not the one this plan was written against: the `V52`–`V55` it used to name are all occupied now — by
ADR-0005 (`V52`, `V53`) and by ADR-0006 (`V54`–`V56`, `V58`) — and a plan that pins a number across three
accepted decisions collides the moment the first of them lands. `V59` is the first free slot today.

1. **The location hierarchy carries its store (D1.a), first.** `store_zones` and `store_locations` gain
   `company_store_id`, backfilled through their parent, with `UNIQUE (company_store_id, id)`. It runs
   **before** the ledger tables rather than beside them: `fk_movement_location` above references
   `store_locations (company_store_id, id)`, which does not exist without this step, and a composite key
   added afterwards would rewrite the very table the ledger already points at.
2. **The four tables, the trigger and the two partial indexes, plus `companies.costing_method`.** The
   ledger tables are armed on `tenant_table` **in the file that creates them**, the way `V58` armed the
   listing, so ADR-0005's policy set is not reopened as a second slice that can be forgotten.
3. **Backfill by replay** (Path A) and run the rebuild function; assert `I2` and `I6` before commit.
4. **Move the readers** — POS search joins `variant_listing` and `stock_position`; every read of `.stock`
   is answered by the projection.
5. **Drop** `product_variant_locations.stock`, `product_variant_store_stock.stock`, and the
   `inventory_movements` table after its rows are migrated.
6. **Delete `InventoryService`.** Its four callers talk to the new module's API, and
   `InsufficientStockException` moves from `salesorder.exception` to `inventory.exception`, which is what
   breaks the package cycle (`InventoryService.java:13` imports it from today's location).

**Note added 2026-11-02, while implementing: steps 4–6 as numbered are not executable in this order.**
Step 4 moves a reader onto `stock_position` while step 6 is the one that stops `InventoryService` writing
`product_variant_store_stock.stock` and `product_variant_locations.stock`. Between those two steps the
projection holds only what step 3's backfill left there, so a reader moved in step 4 answers with a number
no sale can change — the POS would report the balance of the day the migration ran, for ever, and the legacy
row would keep moving beside it. The executable order is **write, read, stop writing, drop**: the four
writers book the ledger beside the legacy update first (so the two copies can be seen to agree, which is the
only moment a defect in either is visible), then the readers move, then the legacy writes are deleted and
the tables drop. `odd/tasks/inventory-ledger.md` carries the three slices and the reasoning; the decision
this ADR states is unchanged, and none of its invariants moved.

## Rejected alternatives

| Alternative | Why rejected |
| --- | --- |
| Keep the three representations and add a trigger that keeps them in sync | The trigger removes the arithmetic drift and leaves the authority problem: three writable numbers still exist, and the question "which one wins" still has no answer |
| Compute balances from the ledger on every read, with no projection | Correct, and rejected as the default because every POS search and every screen aggregates the full ledger. Kept as the rebuild path, which is exactly what makes the projection disposable |
| Keep `inventory_movements` and add columns to it | It lacks tenant columns on the location side, cost, idempotency and reversal identity. A column set that changes entirely is a new table in everything but the name |
| Event-source the whole aggregate (one event per stock step, no tables) | The ledger already is the event log of stock. Event-sourcing the product or the store buys nothing here |
| Model stock per location only, and drop the per-store aggregate | The store aggregate is what a sale checks and what the operator sees; removing it trades one redundant number for a join on every sell |

## Consequences

- **Good**: one number, one authority, auditable history, and a rebuild that cannot lie. The reversal
  arithmetic in `SalesOrderService` (`:948-962`) disappears. Receiving into a new store stops being an
  error. Cost becomes a measured fact instead of a field somebody typed.
- **Good**: the package cycle `inventory ↔ salesorder` is broken by moving the exception to its owner.
- **Cost**: every reader of `.stock` changes (POS selector, variant list, stock search page, reports).
  The provisional 12,2 scale of quantities becomes 19,4, which touches DTOs, Angular forms and the
  value scales in existing tests.
- **Cost**: the manual per-store edit (`PUT /api/variants/{id}/stores/{storeId}`) loses `stock` and
  gains a movement with a `reason`, so the endpoint that used to overwrite a number now appends an
  explained fact. That is the point, and it is a visible API change.
- **Cost**: a new operational duty exists — the verification job. It is small, and it is the reason
  the projection is trustworthy.

## Corrections applied before acceptance

Read in full for the acceptance decision, against the tree as it stands on 2026-11-02, and three defects
were found. The decision is unchanged; the document now says what it meant.

1. **The migration numbers were already spent.** The plan named `V52`–`V55`, and every one of those slots
   belongs to an ADR this one depends on: `V52`–`V53` are ADR-0005's policies and `V54`–`V56` and `V58`
   are ADR-0006's catalog slices. A plan that pins a number across three accepted decisions is wrong the
   moment the first of them lands, and the wrongness is invisible — it reads as a plan until someone tries
   to run it. The steps are now named by what they do, and each takes the next free slot when it lands.
2. **`stock_valuation` carried two amounts and no currency.** `total_value` and `avg_unit_cost` are money,
   and the projection that sums them stated no unit while the ledger below it recorded `currency` on every
   inbound row. The `currency` catalog and `companies.currency` now exist (`V57`), and ADR-0002 decision 2
   makes the operating currency the only one a document can be in, so the valuation states its own currency
   and both currency columns reference the catalog instead of holding a free string.
3. **D1.a was written as a detail and it is the first migration.** `fk_movement_location` references
   `store_locations (company_store_id, id)`, and `store_locations` (`V7`) has no `company_store_id` at all —
   `store_zones` (`V6`) does not have it either, so there is no parent to carry it from and no unique key
   for the reference to resolve against. As written, the `## Schema` block did not compile, and the order it
   appeared in invited discovering that inside the slice that creates the ledger. It now opens the migration.

What this acceptance does **not** settle, because it is implementation shape rather than decision: whether
`stock_valuation` is maintained by the trigger or only by the rebuild — for `FIFO` an outbound row's value
depends on allocations written after it — and where the `I2`/`I6` verification job lives, in CI, in a
nightly task, or in both. Both are owned by `odd/tasks/inventory-ledger.md`, which cites this ADR as its
authority.
