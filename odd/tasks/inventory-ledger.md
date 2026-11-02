# ODD feature: inventory-ledger

**Repository**: LifeControl — `life-control-api` (migrations, `inventory`, `salesorder`, `purchaseorder`,
`goodsreceipt`, `product`), `docker/`, `odd/tasks/`
**Branch**: `feat/inventory-ledger` · **Worktree**: `~/workspace/LifeControl-worktrees/feat-inventory-ledger`
**Status**: implemented — T1–T5c are in `main`: the location hierarchy carries its store (`V59`), the ledger
exists with its engine (`V60`, `V61`), today's balances were replayed into it (`V62`), the five write paths
book it beside the legacy update, the readers answer from its position, and its source key includes the
location (`V63`). **T5d is blocked on ADR-0004**, which is a dependency and not a convenience: without the
state machine's per-transition identity, the `source_id` mirror cannot outlive `inventory_movements`, so the
drops, the deletion of `InventoryService` and the move of `InsufficientStockException` stay open. The debt
that leaves — one stock fact still stored three times, and the `inventory` ↔ `salesorder` package cycle — is
named here rather than closed quietly.
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

**2026-11-02 — T3 landed (`V61`), delegated and verified by the parent.** The ledger's engine, in SQL: the
direction in one function, the pool one inbound row still holds, the valuation as **one expression**, the
`AFTER INSERT` projection trigger, the allocation writer (`FIFO` oldest-first and `AVERAGE` at the moving
average in force), the full replay, the rebuild, and the two checks — `verify_stock_projections` for I2 over
all three projections and `unallocated_stock_movements` for I6.

RED first: the new suite failed **11/11**, in four distinct shapes — six `BadSqlGrammarException` on the
missing writer, one on the missing rebuild, two `EmptyResultDataAccessException` reading projections that no
trigger wrote, two `AssertionError` where a guard should have raised. Worth recording what happened next,
because it is what the suite is for: **the same suite then caught three real defects in the first draft** —
an ambiguous `projection` reference in the check, `AVERAGE` applying `20.0000` instead of `15`, and a
reversal restoring `280.0000` instead of `140` — and a fourth defect in the suite itself (an assertion that
compared only quantities, so a valuation row with the right quantity passed). GREEN: the slice's 11 cases,
then **186 cases across 45 classes** for `inventory`+`tenant`, then the module.

Decisions taken here, all recorded rather than left implicit:
1. **The valuation is one expression, not a per-row delta.** σ(inbound qty × cost) − σ(allocations of
   outbounds that have no reversal) − σ(receipt reversals at the reversed row's cost). The trigger, the
   rebuild and the check read the same function, so the fast path and the reference cannot disagree — which
   is stronger than the ADR asked for, since it admitted an `AVERAGE` last-decimal gap. The cost is stated in
   the file: a refresh re-reads that key's own history.
2. **The moving average in force is a quotient, not the stored column.** Between an outbound insert and its
   allocations the projection's `quantity` has already dropped while `total_value` has not, so the stored
   `avg_unit_cost` is transient — measured as cost `20.0000` instead of `15` in the draft. The writer divides
   `total_value` by `quantity + this movement's quantity`.
3. **I8 lives in the projection trigger, at store grain, `>= 0` for every store**, because no store setting
   allows a negative balance: `store_inventory_settings` (`V11:19-26`) has two locations, a version and two
   timestamps, and no allow-negative column exists anywhere in `db/migration/**`. The guard reads the position
   `FOR UPDATE`, and the lock is the point as much as the check: without it two concurrent deductions read the
   same balance and commit a negative one. The grain is the store — I8's own grain, and what
   `InventoryService.applySaleDeduction` compares against zero today — so a location may hold a deficit the
   store covers.
4. **A reversal must be an exact inverse**: same store and variant, same quantity, and no reversal of a
   reversal. Neither document states it, and it is the decision most likely to be argued: freeing is a read
   rule over append-only rows, which is exact only for a full reversal, and a partial one would reintroduce
   the remainder arithmetic decision 3 removes. A partial correction stays available as
   `ADJUSTMENT_IN`/`ADJUSTMENT_OUT`.
5. **`stock_valuation.currency` is `companies.currency`** — the operating currency ADR-0002 decision 2 makes
   the only one a document can be in — so it is never NULL even when nothing is on hand.
6. **Two indexes land here**, and their reason is a measurement rather than a guess:
   `idx_movement_store_variant (company_store_id, product_variant_id, occurred_at)` and
   `idx_allocation_in_movement (in_movement_id)`, because `uq_allocation_pair` is keyed out-first and its
   prefix answers the opposite question, so every pool lookup would otherwise scan the whole allocation table.
7. **The functions are `SECURITY INVOKER` and the self-check refuses a `SECURITY DEFINER` one**: the ledger's
   writers meet ADR-0005's policies, as the decision above requires, and a definer function would bypass the
   policy the schema slice armed.

Three gaps this slice leaves, named rather than faked: the allocation writer's own pool refusal has **no
test** because it is unreachable through the public interface while the ledger is consistent (the position
gate fires first, and reaching it needs a hand-written allocation); the `FOR UPDATE` serialization cannot be
proven in a single-session integration test, so it is a stated argument; and the O(key-history) valuation
refresh has no benchmark in this module, so its cost is a design argument to watch before T5 puts it on the
sale path. One tidiness item is also recorded: T2's suite cleanup predates the trigger and therefore leaves
the projection rows it creates. No current assertion is affected; a company-wide I2 check on that fixture key
would see them.

**2026-11-02 — T4 landed (`V62`), and it collided with an existing fixture.** Path A, as one migration: a
seven-table `NO FORCE` window that is **proven open two ways** — nothing still `FORCE`d, and the tree
actually reachable (the count of `product_variant_locations` against the count reached through its two
`NOT NULL` foreign keys) — then the refusals, the replay, an insert check, the rebuild, the two assertions,
the reports, and the close with its own proof. That second window check is the slice's most important line:
an open window over an unreachable tree writes zero movements and reports success, with I2/I6 holding over
nothing.

RED first: **5/5** failed — two `AssertionError` on movement counts, two `FileNotFoundException` on the file
that did not exist, and one `AssertionError: Expecting code to raise a throwable` for the abort. One of the
five pinned a movement count on purpose, which is worth keeping: the two "I2 and I6 are empty" cases would
have passed vacuousley over an empty ledger, so the count is what makes them evidence.

**The collision, and the decision it forced.** `ProductVariantListingMigrationIntegrationTest` seeded
`product_variant_store_stock.stock = 4` with **no `product_variant_locations` row** — the exact divergence
`V62` refuses to interpret, so two cases of the module went red. The writer stopped and asked instead of
editing outside its surface, and offered three shapes. Option A was taken: the fixture now seeds the area,
zone and location that explain the store row's stock, with the **same constant** for both halves so they
cannot drift, `stock = 4` unchanged and **no assertion touched**. Option B (seed `stock = 0`) was refused
because it deletes the state the case exists to exercise — a fixture weakened to let a migration pass is the
failure this repository keeps paying for; option C (leave two red and fix later) was refused because a
permanently red module makes every later slice's verification mean less. A bonus fell out of it: the fixture
is written at `V57`, where the three location levels still carry no `company_store_id`, so `V59`'s
derivation now has a fixture that exercises it for the first time.

Decisions the ADR and the record left open, all stated in the file:
1. **A zero balance writes no movement.** `ck_movement_quantity` refuses `<= 0`, and the rebuild derives the
   same nothing for that location.
2. **No recorded cost → `unit_cost = 0` with a `reason` that says the cost is unknown**, and the count is
   printed. Refusing to run on a tree where nobody filled `cost_price` was the alternative, and a migration
   that cannot run gets edited under pressure.
3. **A negative location balance aborts** — a third refusal the task did not name. It cannot be an inbound
   row, and an `ADJUSTMENT_OUT` would be a consumption with no allocation behind it, i.e. a permanent I6
   violation created by this file.
4. **"no location row at all" is read as "no row of that variant in that store"**, because the variant may
   legitimately hold stock in another store.
5. **The window covers seven tables**, including `stock_allocation` (read by the valuation replay and I6 even
   though nothing is allocated) and excluding the three location-side tables, which carry no `company_id`
   and were never armed.
6. **`occurred_at = now()`, `actor = NULL`**: a synthesized balance has no historical instant, and its
   attribution is `source_type`/`source_id` plus `flyway_schema_history`.
7. **The reports are `RAISE NOTICE`s** and the two cases that assert them execute the migration file over
   JDBC and read `Statement.getWarnings()`, because Flyway's own connection makes its notices unreachable.

Two observations that are not defects and are worth knowing: the notices are visible to an operator running
the migration and are **not persisted**, so a later requirement for a queryable audit is a new decision; and
`FIFO` ordering over the migration's rows rests on their insertion instant, which is older than anything T5
will write — true, and worth remembering when consumption lands on top of it.

**2026-11-02 — T5 is re-cut into T5a and T5b, before either starts.** The ADR's step 4 ("move the
readers") carries two changes that only share a package, not a behaviour: **T5a** moves the POS search, the
store-scoped variant reads and the stock search onto `variant_listing` ⨝ the projection, with no request
shape changing at all; **T5b** turns `PUT /api/variants/{id}/stores/{storeId}` from "overwrite the number"
into "append a movement with a `reason`", which the ADR's `## Consequences` names as a **visible API
change**. One is a read-path swap whose whole risk is a join or a scope; the other changes a contract a
screen already calls. Putting them in one slice would make the reviewer hold two different failure modes at
once, and would bury the contract change inside a diff about joins. T5b therefore sequences after T5a, and
neither is a smaller decision than the other.

**2026-11-02 — T5 is re-cut a second time, because the ADR's step 4 is not executable before step 6.**
Found while scoping the reader move, and recorded rather than worked around: the ADR's migration says "4.
move readers … every read of `.stock` is answered by the projection", and only in "6. `InventoryService` is
deleted. Its four callers talk to the new module's API". Between those two steps the sale path still writes
`product_variant_store_stock.stock` and `product_variant_locations.stock` and nothing else, so a reader moved
onto `stock_position` in step 4 would answer with the number the **backfill** left there — and every sale
after that would be invisible on the POS while the legacy row kept moving. The order cannot be
read → drop → write; it has to be write → read → stop writing → drop.

The executable order, and the three slices it makes:

- **T5a — the four writers book the ledger.** `InventoryService`'s entry points (`applyReceipt`,
  `applySaleBatch`, `applySaleReversal`, `applyStockAdjustment`) write `stock_movement` and, for an outbound
  row, its allocations **in the same transaction as the legacy update**, so a moved reader and a moved
  writer cannot disagree and nothing regresses while both exist. The public methods keep their signatures,
  so the four callers are untouched.
- **T5b — the readers move** onto `variant_listing` ⨝ `stock_position` / `stock_balance`, with the legacy
  writes still in place, so the drop is left with no reader.
- **T5c — the legacy writes stop**, `InventoryService`'s arithmetic is deleted, and T6's drops and the
  package-cycle break follow.

Why dual-writing is the right shape rather than a shortcut: the ledger and the legacy tables hold the same
fact for one transition, and the slice that introduces the second copy is the only one where a defect can
be *seen* rather than inferred — the test pins that after a sale the two agree. Deleting the legacy write in
the same slice that introduces the ledger's would remove the only witness.

**2026-11-02 — T5a: the dual-write landed, and it found three things worth more than the code.** The four
writers book the ledger beside the untouched legacy update through a `StockLedgerWriter` that holds no
arithmetic; the V61 trigger and functions own projections, consumption and valuation. Consent to change four
fixture files was given (option A) for one reason: a fixture that writes `product_variant_store_stock.stock`
or `product_variant_locations.stock` by hand authors the aggregate the ADR retires, so it now seeds a state
the ledger never observed and I8 refuses the sale. The fixtures move to seeding **through**
`inventoryService.applyReceipt`, which is more faithful than what they did, and every assertion and stock
constant stays.

1. **`source_id` is a mirror, and the mirror is temporary.** The writer identifies every ledger row by
   `source_type = 'INVENTORY_MOVEMENT'` and `source_id = ` the id of the legacy `inventory_movements` row it
   mirrors — the same shape V62 used for the backfill. That is honest for the transition and **dies with
   T6**, which drops `inventory_movements`: the ledger's source would dangle and document provenance would be
   gone. Worse, the document line cannot be the source today, measured three ways: a sale line that spans two
   locations needs two movements under one line id; `SalesOrderService` raises a quantity by selling the
   difference under the **same** `sales_order_items` row (`:989`, `:991-992`), so a second `SALE` under that
   line id is refused by `uq_movement_source`; and `GoodsReceiptService:269` passes the `goods_receipts` id
   once per line. So the ledger needs a key per **transition**, not per line, and `uq_movement_source` needs
   `store_location_id` beside it. That is T5c, and it must land before the drops.
2. **After this slice the position is the authority for availability, and one class of tree changes
   behaviour.** In a tree where `product_variant_store_stock.stock` exceeds the sum of its locations, V62
   reports the pair and follows the locations; from T5a on, a sale is gated by `stock_position`, so such a
   store will refuse a sale the legacy engine would have allowed. That is the ADR's decision 1 arriving: one
   authority, and the number the operator reads changes. V62's report is what tells the operator which pairs
   those are, which is why it was a report and not a silent choice.
3. **Two mechanisms that only looked equivalent are not.** The suite cleanup of an append-only table cannot
   use `ALTER TABLE … DISABLE TRIGGER USER`: it is a catalog change on a table the whole container shares, so
   a sibling suite's `UPDATE` would pass the append-only rule for as long as it is off. It uses
   `SET LOCAL session_replication_role = replica` on the container-superuser connection, which is
   session-scoped and invisible to anyone else, and the helper is itself tested — book, clean, assert empty —
   rather than trusted.

**2026-11-02 — T5a landed: the four writers book the ledger, and the fixture problem was the interesting
half.** `StockLedgerWriter` books one `stock_movement` per legacy movement and its allocations for every
outbound row, in the same transaction as the untouched legacy update; the four public methods and their four
callers are unchanged. `Booking` now carries a **required** `sourceType` — no default, no overload — and the five
production call sites state `MIRRORED_SOURCE_TYPE` explicitly, so the transition shape is visible where it is
decided rather than hidden in the writer. That parameter is not a test accommodation: T5c's finding says the
mirror dies with T6, and this is the seam that replaces it, built once where the fixture forced it.

Three interactions were needed to get here, and each one was a stop rather than a guess:

1. **The first attempt to fix the fixtures was measured and refused.** Authorized to seed
   `SalesOrderIntegrationTest` through `applyReceipt`, the writer measured that it changes the legacy
   `inventory_movements` counts **19 of 40** cases read — and that `seedSplitBalances` has **no writer at all**,
   because `InventoryService` has no transfer between locations. My authorization was wrong and it said so with
   the measurement instead of applying it.
2. **Then two of my own conditions were mutually unsatisfiable**, and it said so instead of choosing: booking
   through the writer and labelling the row `FIXTURE_OPENING` cannot both hold while the writer hard-codes
   `source_type`. The resolution opened the required parameter above.
3. **The split is booked as a relocation, not a sale.** A split between two locations has no writer of its own,
   so the fixture books `ADJUSTMENT_OUT` of the difference at the priority location plus the secondary
   location's opening — each sourced by the location balance row it changes, which is V62's own shape — rather
   than a `SALE`+`RECEIPT` pair, which would have recorded a sale that never happened. The helper refuses a split
   that does not sum to the seeded aggregate, so a per-location disagreement cannot be seeded silently.

The fixture state this names is a real one: **stock that predates the ledger**. It is labelled
`FIXTURE_OPENING`, it is booked through the production writer rather than raw SQL, and it dies with the suite.
A fixture that inserts a ledger row the writers cannot produce would prove nothing.

Two facts that are written here rather than left to be inferred from green tests: the schema suite's two I1
cases run with the append-only trigger **on** — they assert that a direct `UPDATE` and a direct `DELETE` raise,
which is why that suite keeps the container superuser handle — and **no executable catalog-level trigger lift
remains in the module**: the two hits on `DISABLE TRIGGER` are javadoc explaining why the mechanism is not used,
and the one executable suppression is the shared helper's session-scoped
`SET LOCAL session_replication_role = replica`.

Verification: module **4359 cases, 0 failures**, from a forced `cleanTest` re-run rather than an `up-to-date`
reuse; `spotlessCheck` and `spotbugsMain` clean.

**2026-11-02 — T5b landed: the readers answer from the projections, and the 409 came back.** Four readers
moved onto `stock_position`, joined as a **third independent `LEFT JOIN`** the way `V58` established for the
price: the store-scoped variant list (`GET …/products/{id}/variants?storeId=`), the POS and stock-search read,
the per-store editor's response, and the sale's availability gate. The visibility predicate widened to
`(pvss.id IS NOT NULL OR l.id IS NOT NULL OR sp.quantity IS NOT NULL)`, so a position alone keeps a variant
answerable — which is decision 5's "a receipt creates availability" surviving into the read.

Four things the slice deliberately did **not** move, each with its reason rather than by omission:
`InventoryService`'s internal reads are the legacy **write** path and stopping them is T5d; the reversal's
identity reads (`uncoveredRemainder`, `openLedgerSales`) still read `inventory_movements` because reversal
identity is T5c's; the measure-unit history guard is split across two clocks and moving half of it would
refuse on the wrong one; and **`stock_balance` has no reader in the API at all** — no endpoint answers "how
much in this location", so mapping a second projection would have been an unused mapping. That last one is a
finding, not a shortcut: the task asked for two projections and the tree only reads one.

**The 409 is preserved, and by a Java gate rather than by parsing a database error.** `V61`'s I8 raises a
plpgsql exception, which the API was surfacing as a **500**; from this slice the position is the authority a
sale is refused by, so `applySaleDeduction` gains a fail-closed check against `stock_position` that raises the
documented `InsufficientStockException` with the position's own number. RED was observed for it in three
places, at three levels: the read answering the legacy column (`expected 4 but was 99.00`), the service
raising `UncategorizedSQLException` instead of the contract, and the HTTP case answering **500 instead of
409**. `InsufficientStockException` did **not** move to `inventory.exception`: that is T5d's, with the
package-cycle break.

Two API-visible changes that have to be recorded here because a DTO javadoc is not where an operator looks:
`stock = null` on a store-scoped read now means "the ledger holds no position for this variant in this store"
— it used to mean "no per-store row" — and the per-store editor's response can answer `null` where it used to
answer `0` for a key nothing ever moved. `0` now means drained, and the four states are otherwise intact.

Two risks carried forward, both for T5c: the manual **decrease** path has no position gate, so on a divergent
tree it can still surface I8 as a 500 — symmetric with the bug this slice fixed, and not widened because that
path's arithmetic is what T5c deletes; and **the serialization point is still the legacy store row's lock**.
Every mover of a key takes it first, which is the only reason the gate is safe under concurrency. After T5c
that lock is gone, so the projection row's own lock — the one `V61` takes — becomes the only serialization
point, and the gate has to move with it.

Verification: module **4362 cases across 1038 classes, 0 failures**, from a forced `cleanTest` re-run;
`spotlessCheck` and `spotbugsMain` clean.

**2026-11-02 — the identity of a movement cannot outlive `inventory_movements`, and the maintainer decided
where that stops this unit.** Verified in the code before asking, not inferred: `SalesOrderService:988-992`
raises a line's quantity by deducting the **delta under the same `sales_order_items` id**, and lowers it by
reversing and re-deducting the whole new quantity **under that same id**. So a document line cannot be a
movement's key while the document is editable, and the only fact that identifies a transition today is the
legacy `inventory_movements` row — the mirror. When that table drops, I5's "a retried request is a no-op" has
nothing left to be derived from, and a per-transition identity is exactly what ADR-0004's state guard would
provide.

Three shapes were put to the maintainer, with their costs stated: derive the key from the document state
(requires ADR-0004, and the service cannot supply the sequence today), let the ledger generate its own key
(possible without 0004, but I5 stops being a property of the ledger and becomes a hope about the order's
guard), or **stop before the drops**. The maintainer chose the third, and this entry is the authority for it:
**ADR-0001 closes without T5d.** The drop of `product_variant_locations.stock`,
`product_variant_store_stock.stock` and `inventory_movements`, and the deletion of `InventoryService` with the
`InsufficientStockException` move that breaks the package cycle, wait on ADR-0004. The three representations
and the package cycle therefore survive this unit, named as debt rather than quietly closed.

What still lands, because neither depends on the identity: **T5c widens `uq_movement_source` with
`store_location_id`** — one document line genuinely spans two locations, and today the index would refuse the
second row it needs — **and the position gate takes the projection row's lock**, because until now the only
serialization point was the legacy store row's lock, which T5b's readers already outgrew and T5d would remove
entirely.

**2026-11-02 — T5c landed (`V63`), and it closed exactly one thing while naming the rest.** The index now
permits the one case the narrow key refused — the same source, direction and location pair at **two
locations**, which is one document line drawing from two places, a legal line the ledger was refusing. It still
refuses the same source, direction **and** location, so a retried booking is still refused (I5 read at its own
grain: a movement names exactly one location, `V60:120`). And it states its own limit rather than implying
otherwise: **reversing a movement does not free its key**, because the reversed row keeps
`reverses_movement_id IS NULL` — so re-booking the same source after a reversal is refused, and that is
precisely the gap ADR-0004's per-transition identity fills. Two facts the widening rests on, both checked: the
partial predicate is kept verbatim, and `store_location_id` is `NOT NULL` — a nullable column is distinct from
every other `NULL` in a B-tree unique index, so the key would have refused nothing at all.

**The gate now serializes on the row it decides about.** `applySaleDeduction` step 4b takes
`@Lock(PESSIMISTIC_WRITE)` on `stock_position`, so a second deduction waits on that row, re-reads under
`READ COMMITTED` and refuses with the quantity the first committed — instead of depending on the legacy store
row's lock taken three steps earlier. An absent projection row takes no lock and is read as **zero, refused**:
fail-closed, never "no row, so nothing to check". `V61`'s trigger keeps its own `FOR UPDATE` and it is not
redundant: that one faces **every** writer that reaches the table, including one that never walks this method;
the gate's lock exists so the refusal is the API's documented 409 before anything is written. Lock order grows
one level to `storeStock -> stockPosition -> locationBalance`, and no path takes the position before the store
row of the same key, so it stays cycle-free.

RED was real for all three: two index cases failed with `DuplicateKeyException` on the legitimate second
location (and the refusal case failed on its own *precondition*, which is why it was unreachable before), and
the gate case failed exactly as predicted — the plain read got the previously committed `10`, passed, and the
trigger refused later with the 500 the gate exists to replace. The concurrency case's barrier is
`pg_stat_activity`'s lock wait rather than `pg_locks` (a blocked row lock is a `transactionid` wait, so a filter
on the relation would never see it), and its assertion is the exception type and number, never timing.

One thing the gate change broke and it is worth recording as a pattern: twelve existing unit cases mocked
`findQuantity`, which the gate no longer calls, so the mock answered empty and every one of them saw
`available 0`. The fix was to move the stub to the new method; no assertion was touched. A mock is a claim
about **which** method is called, and changing the caller silently invalidates it — the tests were right to
fail.

Verification: module **4366 cases across 1040 classes, 0 failures**, from a forced `cleanTest` re-run;
`spotlessCheck` and `spotbugsMain` clean.

**2026-11-02 — the unit closes; delivery evidence, and the debt it leaves.** Six slices reached `main` as
chained pull requests on #739: T1 `V59` (#741, merge `98f43a05`), T2 `V60` (#742, `6661b360`), T3 `V61` (#743,
`bce2dc04`), T4 `V62` (#744, `211c2958`), T5a (#745, `9dac365e`), T5b (#746, `ac41052c`) and T5c `V63`
(#747). Each merged with the four required checks green, and each slice's tests were re-run by the parent with
`cleanTest` rather than trusted from the writer's run — twice catching that an `up-to-date` build reports
success without executing anything.

What `main` now enforces that it did not before this unit: a stock change is one append-only row and nothing
else can write quantity; the projections are derived, rebuildable and checked against a replay by
`verify_stock_projections`; a movement's store is its location's store (`fk_movement_location`, made
expressible by `V59`); today's balances were replayed rather than truncated (`V62`, with the `NO FORCE` window
proven open before it relied on it); the sale's availability answer and its 409 come from the ledger's
position, not from a column somebody typed; and the ledger's source key is per location, so a line that draws
from two places is no longer refused.

What is **not** true, and a reader of this record must not conclude otherwise: `product_variant_store_stock.stock`,
`product_variant_locations.stock` and `inventory_movements` still exist and are still written by
`InventoryService`; the two copies are kept in agreement by the dual-write rather than by having one; and the
`inventory` ↔ `salesorder` package cycle is intact, because the exception that breaks it has not moved. That is
T5d, and T5d needs ADR-0004 first.
