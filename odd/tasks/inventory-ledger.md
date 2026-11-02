# ODD feature: inventory-ledger

**Repository**: LifeControl — `life-control-api` (migrations, `inventory`, `salesorder`, `purchaseorder`,
`goodsreceipt`, `product`), `docker/`, `odd/tasks/`
**Branch**: `feat/inventory-ledger` · **Worktree**: `~/workspace/LifeControl-worktrees/feat-inventory-ledger`
**Status**: implemented — T1 (D1.a, `V59`) and T2 (the ledger's schema, `V60`) are in the tree with their
suites; T3–T6 are the remainder this record still owes, each named in its own row. The slice boundary is one
pull request per task: PR 1 is T1 (`7d004b24`), PR 2 is T2.
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
| T1 | S7, S8 | `V59`: `store_zones` and `store_locations` gain `company_store_id`, backfilled through their parent, `NOT NULL` after the backfill, `UNIQUE (company_store_id, id)` on the two of them plus `store_areas`, the two composite keys replacing the plain ones, and the two `BEFORE INSERT` triggers that derive the owner — plus the read-only mapping on the two entities. Suite: the two derivations, the two refusals by constraint name, and "the refusal stored nothing" | — | inline; done `7d004b24` |
| T2 | S2, S5, S8 | `V60`: `stock_movement`, `stock_allocation`, `stock_balance`, `stock_position`, `stock_valuation`, `companies.costing_method`; the append-only trigger; the two partial indexes; the grants that leave the application role `INSERT, SELECT` on the first two; armed on `tenant_table` in the file that creates them | T1 | delegated writer; done |
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

**2026-11-02 — T1 landed (`V59`, `7d004b24`), with RED observed first.** The new suite failed 5/5 before the
migration existed — the two derivation cases through `BadSqlGrammarException` on the missing column, the
two refusal cases through `AssertionError` on a constraint that did not exist yet — and passed 5/5 after it.
The two entity mappings are `insertable = false, updatable = false` and carry the docblock `CompanyStore`
carries for its `company_id`: `V50`'s measurement (a `@PrePersist` that read the parent's association threw
`LazyInitializationException` on a detached-parent save) applies unchanged one level down, so the
derivation is a `BEFORE INSERT` trigger and `BEFORE UPDATE` is deliberately absent — a zone cannot move to
another store, so that trigger could never fire.

Neither of the two tables is armed on `tenant_table`, and that is a decision recorded rather than omitted:
ADR-0005's armed scope is the fifteen tables that carry `company_id` (S2 of `odd/tasks/tenant-isolation-rls.md`,
the maintainer's decision), and `store_zones`/`store_locations` carry `company_store_id` exactly as
`store_areas` does — and `store_areas` has no policy. Their tenancy is enforced through `company_stores`,
which is armed, plus the composite keys that pin each level to its parent. Widening the armed set would be
a different decision and a different slice.

Verification observed by the parent: **637 cases across 129 classes, 0 failures** over the `store` and
`tenant` packages plus the new suite (the tenant suites are the ones that pin the armed set, and they stayed
green precisely because nothing was armed); `spotlessCheck` and `spotbugsMain` clean; `ddl-auto=validate`
green, so the two read-only mappings agree with the columns `V59` wrote.

**2026-11-02 — T2 landed (`V60`), delegated to a writer and verified by the parent.** RED first: the new
suite failed **7/7** at one statement — `relation "stock_movement" does not exist` — and the two pins that
freeze the armed set failed exactly as `V58` made them fail once: `TenantScopeFunctionsIntegrationTest`
held seventeen tables against the tree's twenty-two. Both were **grown, not relaxed** — five entries added,
`hasSize(17)`→`22` and `+2`→`+7`, every other assertion byte-identical. GREEN: the full module suite,
**4335 cases across 1035 classes, 0 failures**, plus `spotlessCheck` and `spotbugsMain` clean. The parent
re-ran the whole suite rather than the two packages the writer was authorized for, because a migration is
global and six scratch-database suites had never seen `V59`/`V60`.

Three decisions the ADR did not take, taken by the writer and accepted here:
1. **`stock_allocation` is append-only too**, with the same trigger. The ADR's grant sentence covers both
   tables and its trigger sentence names only `stock_movement`; the reason to cover both is invariant I6 —
   a deleted allocation is indistinguishable from a writer that never ran. The consequence is named rather
   than hidden: with `uq_allocation_pair` beside it, the quantity of an (outbound, inbound) pair is final,
   and the only correction path is to reverse the outbound movement.
2. **`REVOKE ALL` followed by `GRANT SELECT, INSERT`**, not a narrow `REVOKE UPDATE, DELETE`: the file
   states the complete set the role is meant to hold, so a later `REVOKE ALL` elsewhere cannot leave the
   table unreadable without this file's intent being visible. The revoke is load-bearing: `V52:196-197`
   grants `SELECT, INSERT, UPDATE, DELETE` as a DEFAULT PRIVILEGE, so a new table arrives writable.
3. **No `(company_id, …)` index on `stock_movement` beyond the two partial ones the ADR declares.** The
   tenant predicate has no leading-column index, which is the same per-table write-cost decision `V58` left
   out of its schema slice; T3 is where the access pattern becomes measurable, and an index invented before
   a query exists is a guess.

The writer also found a **weak assertion it did not touch**, recorded here because it is the kind of thing
that makes a green suite mean less than it looks: `has_table_privilege(role, oid, 'SELECT,INSERT,UPDATE,DELETE')`
is an **OR** over the list, so `TenantScopeFunctionsIntegrationTest#theApplicationRoleHoldsTheTableGrants`
would pass with the ledger tables holding only `SELECT`+`INSERT`. The new suite pins the exact set with four
single-privilege calls instead, and tightening the older assertion is a separate decision with its own diff —
it is not this slice's to make silently.

**2026-11-02 — the two decisions the acceptance left open are taken, before T3 starts.** The ADR's
`## Corrections applied before acceptance` names exactly two things it does not settle, and both are T3's:

- **`stock_valuation` is maintained incrementally, not rebuild-only.** Decision 2's shape is "a trigger as a
  fast path, and the rebuild as what makes the projection disposable", and it applies to all three
  projections: under FIFO the value still on hand is `SUM((quantity − allocated) × unit_cost)` over the
  inbound rows, which the allocation writer already knows at the moment it writes each row. A rebuild-only
  valuation would report a number that is stale between rebuilds, on the one projection a report reads
  without a replay. The rebuild stays the reference, and `I2` is extended to compare valuation against
  replay as well as balance and position.
- **The `I2`/`I6` verification runs in the suite CI already runs, not on a nightly task.** Nothing in this
  repository schedules a nightly job, so "nightly" here would be a claim with no runner behind it — the same
  shape as a default that hides a feature being off. The comparison is an integration test (rebuild into a
  scratch table inside a rolled-back transaction, compare, fail loudly) plus the rebuild function any
  operator can call; if a scheduler ever exists, it calls the same function and the test keeps proving it.

**2026-11-02 — a third decision, taken before T3 starts: the ledger's writers are SQL functions.** The ADR
says what must be true — allocations recorded, projections derived, a rebuild — and not where that lives.
It lives in the database, and the reason is the one decision 3 exists for: under `FIFO` the consumption is
the oldest inbound rows of a `(company_id, company_store_id, product_variant_id)`, which is a set
operation over rows the ledger already holds. A Java implementation would read the ledger back into memory
and reproduce exactly the remainder arithmetic `SalesOrderService` performs today, which is the structure
the ADR removes. The Java module calls these functions from T5 on, and gains the entity mapping then;
T3 proves the engine through the functions themselves.

The consequence that has to be stated with it: the functions run as the calling role, so they meet ADR-0005's
policies. A transaction must already carry the tenant scope, and a backfill that writes across tenants —
T4's — needs the `NO FORCE` window `V58:122-123` establishes. `SECURITY DEFINER` would dodge both and
bypass the policy the previous slice armed, so it is refused rather than used.
