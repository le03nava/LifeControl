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
