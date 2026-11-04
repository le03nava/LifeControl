# ODD feature: ledger-mirror-retirement

**Repository**: LifeControl — `life-control-api` (migrations, `inventory`, `salesorder`, `goodsreceipt`,
`product`), `odd/tasks/`
**Branch**: `feat/ledger-mirror-retirement` (T1–T3c) · `feat/ledger-mirror-drops` (T4a, PR #770) ·
`feat/ledger-mirror-drops-columns` (T4b, T5). Worktrees under `~/workspace/LifeControl-worktrees/`, one per
branch.
**Status**: implemented — ADR-0001's last stage is closed: the receipt and the sale book their own lines, the write
path maintains the ledger only, the two legacy `stock` columns and `inventory_movements` are dropped, and
`InventoryService` is gone with the package cycle it carried. `## Tasks` names what each slice owes and
`## Decisions taken here` carries the decisions the slices could not take by themselves; `## Log` states what was
measured before any of it was written.
**Created**: 2026-11-03
**Risk**: items **1**, **2**, **3** and **6** — item 1 because the drops delete the only remaining copy of two
balances and a table that claims to explain every change; item 2 because the ledger is the thing that decides
who may write stock and a guard that moves with it is silent when it is missing; item 3 because the store-scoped
reads and the per-store editor answer from the ledger now, so their contract must not move again; item 6 because
no current test would notice a reversal that frees the wrong rows once the mirror is gone. Item 4 does not apply
(the serialization point stays a row lock, not a new ordering).

## Objective

Close ADR-0001. The ledger exists (`V60`, `V61`), was backfilled (`V62`), keyed by location (`V63`) and, for a
sale, by the write that produced each movement (`V73`). What it still cannot do is stand **alone**: a `RECEIPT`
and every reversal still name an `inventory_movements` row as their source, `InventoryService` still maintains
`product_variant_locations.stock` and `product_variant_store_stock.stock` beside the ledger, and the
`inventory` ↔ `salesorder` package cycle still exists because `InsufficientStockException` lives on the wrong
side of it. This unit removes the mirror, then the two columns, then the service.

## Specs

S1. `docs/adr/0001-inventory-ledger-single-source.md` is the authority: decision 1 ("One fact, one row"),
    invariant I5 (`:184`, "A source document line books one movement per direction"), I2/I4/I6, and the
    `## Schema` block. `odd/tasks/inventory-ledger.md`'s T5d and T6 entries name this debt; nothing here
    weakens them.

S2. **A receipt line's ledger identity is its own row.** `RECEIPT` movements carry
    `source_type = 'GOODS_RECEIPT_ITEM'` and `source_id = goods_receipt_items.id`, with `SINGLE_WRITE`
    (`write_seq = 0`), because a receipt is immutable — `GoodsReceiptService:242-246` states that receipts are
    never edited or deleted, so a line books exactly once and needs no counter, and the grain is the line because
    that is the identity that survives the mirror.
    **What is wrong today is not a collision but a dependency**: a `RECEIPT` states the mirrored
    `inventory_movements` row (`InventoryService:280`), so the ledger's identity dies with a table T4 drops. The
    document id is *not* the replacement, and the existing case states why
    (`InventoryLedgerBookingIntegrationTest:444-446`): the key does not carry the variant, so a document id would
    make the second line of every multi-line receipt collide with the first.
    The case this spec owes is therefore the grain: two lines of one receipt for the **same variant at the same
    location** book two `RECEIPT` rows whose sources name the line, and no source states the mirror. Its RED is
    the assertion at `:449-455`, which today requires `MIRRORED_SOURCE_TYPE` and would fail under the new shape.

S3. **A sale reversal resolves identity from the ledger.** What a reversal credits per location is the sum of
    the sale rows the ledger still holds open — `StockLedgerWriter.openSales` already answers that, per variant,
    store and location — and the legacy arithmetic `SUM(SALE) − SUM(SALE_REVERSAL)` over
    `inventory_movements:960-968` stops being the source of that number. The reversal row's own source is the
    **sale line**, not the mirrored reversal row's id (`InventoryService:1028-1040`); `reverses_movement_id` still
    names the movement being corrected, which is what keeps a reversal outside `uq_movement_source` (its predicate
    is `reverses_movement_id IS NULL`).
    **Two consequences, both stated rather than discovered later**: (a) the refusal
    `requireTheWholeRemainderIsOpen` has nothing left to compare once the remainder *is* the set of open rows, so
    it goes and the case that pinned it is rewritten to what is now true — a reference whose stock spans the
    deploy reverses **both** identities instead of being refused, because the ledger holds both rows and each
    reversal names its own; (b) the mirror read survives this slice **only** as the fallback that finds a sale
    booked before `V73` by the row it mirrors, and it dies with the table in T4.

S4. **The write path stops maintaining a second balance.** Receipts, sales and adjustments stop writing
    `product_variant_locations.stock` and `product_variant_store_stock.stock`; `stock_movement` plus `V61`'s
    projection trigger is the only writer, and the readers that T5b already moved to `stock_position` stay the
    readers. Whatever the trigger cannot answer must be answered by a ledger read, never by a third column.

S5. **The drops are preceded by the proof that nothing reads them**, and each drop names what it refuses.
    `product_variant_locations.stock`, `product_variant_store_stock.stock` and `inventory_movements` go; the
    migrations that drop them state the check they ran, in the shape this repository's migrations already use.

S6. **`InventoryService` is deleted and the package cycle is broken.** `InsufficientStockException` moves to
    `inventory.exception`; `inventory` no longer imports from `salesorder`; its callers write through
    `StockLedgerWriter` and read through the projections.

S7. **The measure-unit history guard answers from the ledger.** `ProductVariantService:726` and `:730` refuse a
    measure-unit change when stock history exists, and today they ask the two legacy columns. They must ask
    `stock_movement`, or the refusal disappears with the columns.

S8. Every rule above gets a case that fails before it is implemented, and the two suites that already pin the
    ledger's identity (`InventoryLedgerBookingIntegrationTest`, `SalesOrderIntegrationTest`) keep passing
    unchanged except where a case's *shape* is the thing being changed.

## Tasks

| ID | S# | Work | Route |
| --- | --- | --- | --- |
| T1 | S2, S8 | The receipt's line identity: `GoodsReceiptService` hands the writer the line, `InventoryService.applyReceipt` books `GOODS_RECEIPT_ITEM` + `goods_receipt_items.id` while the legacy row keeps the document, and the grain case (two lines, one variant, one location) is written first | **done** — `4fb4c6c4` |
| T2 | S3, S8 | The reversal's identity off the mirror: the remainder read moves to `stock_movement`, the reversal names the line, and the mixed-identity refusal that only existed because of the mirror is retired with a case that states why | **done** — inline, `5ad8fa7a` |
| T3a/T3b | S4, S8 | The sale's split reads the projection (`StockLedgerWriter.openBalances`) while the columns are still maintained, because an intermediate state where one writer stops and another reads is not shippable | **done** — `444979ce` |
| T3c | S4, S7, S8 | The write path: receipts, sales and adjustments stop writing the two legacy columns and the five `inventory_movements` writes stop, the store-aggregate gate goes, the measure-unit guard asks `StockLedgerWriter.hasMovements`, and the 87 dual-truth cases across 8 suites are rewritten to a single truth | **done** — inline; the branch's write-path commit (T4's record update names its hash) |
| T4a | S3(b), S4, S6, S8 | The cycle break and the engine: `InsufficientStockException` to `inventory.exception`, `InventoryService` → `inventory.ledger.StockLedgerEngine` with no legacy lock, no legacy column and no mirror fallback, `openBalances` ordering by the ledger's own FIFO, and the two cases that pinned the mirror fallback rewritten to pin its deletion | **done** — `c9bf83b0`, PR #770, merged `3eb890fb` |
| T4b | S5, S6, S8 | The drops (`V74` for the two `stock` columns, `V75` for `inventory_movements`), the deletion of `InventoryMovement`, `MovementType`, `InventoryMovementRepository` and the stock field of the two surviving entities, and the ~14 test files that name the dropped objects | **done** — `6a06a9b3` |
| T5 | S1, S5 | The records: `odd/tasks/inventory-ledger.md` T5d/T6 closed with what landed and what the drops removed, and this record's evidence | **done** — the commit that carries this row |

## Decisions taken here

**D-1 — a sale booked before `V73` is reversible until the mirror is dropped, and not after.** The fallback that
finds those rows is deleted with `inventory_movements` in T4, and from that moment nothing maps a sale line id to a
ledger row whose source is the mirror. The drop's migration therefore asserts that no unreversed sale movement
carrying the mirror identity exists, and refuses with the operator's next step in the message. The alternative — a
backfill that rewrites those sources to the line identity — was refused because it would have to invent a
`write_seq` for rows that never had one, and an invented identity is the one thing `V73` exists to stop.
**Amended on 2026-11-04, when the drop was written: the refusal is a report, and the reason is measured.**
`V62`'s backfill creates a mirror-identity `SALE` for every sale this deployment ever booked, and almost none of
them is reversed, so "refuse while one is unreversed" fires on every database that has sold anything before
`V73` — the dev datadir included. The maintainer was shown the three shapes and answered «Reportar y seguir (como
V62)»: `V75` counts them, prints the count and a sample as a `RAISE NOTICE`, and drops. The cost stays exactly the
one this decision named — a pre-`V73` sale is not reversible after the drop — and what changed is who pays it and
when: an operator who wants those sales reversible reverses them before upgrading, and the report tells them the
size of the job. The refusal survives only where it costs nothing: `V74`'s guard, which refuses a legacy balance
the ledger never moved (a number that would otherwise disappear without anyone booking it) and passes on any tree
`V62` already replayed.

## Log

**2026-11-03 — L1, the user's request, verbatim**: «ok con que seguimos?», answered with the three candidates
(«Cerrar ADR-0001», «Arrancar ADR-0002 por lo independiente», «Resolver primero las dos decisiones abiertas») and
the maintainer chose **«Cerrar ADR-0001»**. The receipt's shape was taken with that choice: the same shape (a) as
the sale, which is what S2 records.

**What was measured before this record was written** (read-only, `path:line` evidence):

- `GoodsReceiptService:293-300` passes `saved.getId()` — the **document** id — to `applyReceipt` once per line,
  and `InventoryService:280` books the ledger with `movement.getId()`, the mirrored row's id.
- The lines table is **`goods_receipt_items`** (`V12__goods_receipts.sql:39-48`); no `ALTER` ever added a
  sequence or version to it, and no receipt is ever edited (`GoodsReceiptService:242-246`).
- **No receipt reversal exists**: `applySaleReversal` is the only reversal in `src/main`.
- `inventory_movements` has **five writers**, all in `InventoryService` (`:265, :524, :838, :921, :1026`), and
  **three readers** in `src/main`: the remainder (`InventoryService:960`), the pre-`V73` fallback (`:1207`) and
  the measure-unit guard (`ProductVariantService:730`).
- The two legacy columns are read only by `InventoryService` and by the same guard
  (`ProductVariantService:726`); nothing else in `src/main` reads them, which is what T5b left.
- `InsufficientStockException` lives in `salesorder/exception/` and is thrown only by `InventoryService`
  (`:433, :439, :472, :498, :896`); nothing catches it, and `GlobalExceptionHandler:68-71` answers 409 through
  its `ConflictException` parent, so moving it does not change the contract.
- Blast radius for the drops: **14 test files** name one of the three objects, with hard SQL (not only entity
  access) in `InventoryLedgerBookingIntegrationTest`, `StockLedgerBackfillMigrationIntegrationTest` and
  `ProductVariantListingMigrationIntegrationTest`.

**One claim was corrected before any code was written**: an earlier draft of S2 said two lines of one receipt
collide today. Reading `InventoryLedgerBookingIntegrationTest:433-462` shows they do not — each line carries its
own mirrored row, so each has its own `source_id`. The defect is the dependency, not a collision, and S2 now says
so.

**Highest migration in the tree is `V73`** (this unit's own `V73`, merged as PR #763), so T4's drops start at
`V74` and must not reuse a number `main` has taken — the collision that had to be fixed once already.

**2026-11-03 — T1 landed: the receipt books its own line.** Three things were measured or changed in a way the
specs did not state, and each is recorded because the next slice depends on it:

- **No signature change was needed.** `applyReceipt` already carried a `referenceType`/`referenceId` pair that
  it ignored for the ledger (it booked `MIRRORED_SOURCE_TYPE` + `movement.getId()` no matter what the caller
  said). A receipt now passes `RECEIPT_LINE_SOURCE_TYPE` and the `goods_receipt_items.id` through that pair, so
  the ~40 call sites keep their arity and only the meaning of the pair changed. The consequence is stated
  plainly: **the goods receipt document id no longer reaches the inventory API at all** — the mirror row names
  the line too, and nothing in `src/main` read a receipt's document reference.
- **The RED was observed at the assertion, not by inventing a defect.** With the test helper passing the line
  identity, the case failed with exactly `["INVENTORY_MOVEMENT", "INVENTORY_MOVEMENT"]` against the expected
  `["GOODS_RECEIPT_ITEM"]`. The new grain case — two lines of one receipt, one variant, one location, two rows —
  was **green before the change**, because each line carried its own mirrored row id; it is a guard for the new
  key, not a RED, and saying otherwise would be a lie about what was measured.
- **The pairing of a request line with its persisted row is by construction.** `createReceipt` now keeps a
  `builtItems` list beside `resolvedLines`, because step 8 has to name the id the persistence layer assigned. A
  lookup by `purchase_order_detail_id` was tried first and failed in `GoodsReceiptServiceTest`'s mocked save,
  which is the honest signal that the lookup was a guess about a collection whose order this class does not own.
  Two supporting changes followed: the unit test's `save` stub now assigns line ids (its mock has to provide
  what a real save provides), and the two fixture helpers that seeded stock through `applyReceipt` with a null
  reference now pass a line id, because `stock_movement.source_id` is `NOT NULL`.

Evidence: the six suites this touches (`InventoryLedgerBookingIntegrationTest`, `GoodsReceiptServiceTest`,
`InventoryIntegrationTest`, `InventoryServiceTest`, `ProductVariantListingReadIntegrationTest`,
`ProductVariantStoreStockWriteIntegrationTest`) ran green — **84 tests, 0 failures, 0 errors, 0 skipped**.

**What the six suites did not tell me, and the full suite did.** The first full run failed on **two** cases in
`GoodsReceiptIntegrationTest`, both asserting the legacy row's `reference_type` was `GOODS_RECEIPT` and its
`reference_id` the receipt document's id. Both are consequences of S2 and both are now stated the same way the
other cases state it: the legacy row names the line too. That failure is the reason the fixture list above is not
the evidence of record — the whole suite is, and six suites agreeing with a change is not the same claim as the
tree agreeing with it.

**2026-11-03 — T2 landed: the reversal names the line, and the remainder is the open set.** What the code had to
learn, in the order it learned it:

- **The union, not the fallback.** Keeping "primary read first, mirror only if empty" would have been the smaller
  edit and the wrong one: a reference whose stock spans the deploy holds rows of both shapes, and with `if empty`
  it would silently reverse only the half the first read sees. The two reads are now unioned by ledger movement
  id, and the mixed-identity case asserts both halves are reversed.
- **The mirror reader had to stop throwing.** It used to raise when a legacy SALE row had no ledger twin, because
  a missing twin meant a broken deploy window. From T2 a post-`V73` sale's ledger row names the LINE, so "no
  mirror twin" is the normal case, and the throw fired on **31 tests** across the sales surfaces before it was
  changed to skip. Learning that took one full-suite run: the six suites I would have chosen were not the ones
  that broke.
- **The unit fixture had to stop claiming every legacy row was mirrored.** `InventoryServiceSaleMovementTest`
  stubbed `mirroredMovementId` to a random id for every row, which the union turned into "every legacy sale is
  open", so cases began locking store rows they never stated. The stub now answers `Optional.empty()`, which is
  what the post-T2 world looks like, and cases that want a reversal to actually happen state what the ledger
  holds open through a new `givenOpenSales` helper.
- **The refusal case became its opposite**, and its javadoc says so: it used to argue that refusing was correct,
  now it argues that the refusal was an artefact of deriving the amount from a second table.

Evidence: `InventoryLedgerBookingIntegrationTest` 15/15 and `InventoryServiceSaleMovementTest` 37/37 green, and
the full suite: **1066 result files, 4504 tests, 0 failures, 0 errors, 0 skipped**, `spotlessCheck` and
`spotbugsMain` clean. The slice is **374 changed lines** (236+/138−).

**2026-11-03 — T3, first half: the sale's split reads the projection, and a lie in the column cannot move it.**
The order of work here was chosen so that the tree is green between halves:

- `StockLedgerWriter.openBalances(companyStoreId, productVariantId)` is the new read: quantities from
  `stock_balance` (the projection `V61` maintains from every movement), joined to
  `product_variant_locations` **for the order only** — `created_at, store_location_id`, the pair the legacy
  query ordered by — so the change is a change of source and not of behaviour.
- `applySaleDeduction`'s steps 6-9 and `applyAdjustmentDecrease` take their amounts from that read, keep locking
  the same rows in the same order, and still maintain the columns (that stops in the second half). `orderForDeduction`
  now orders location **ids**, because the entity is no longer the thing holding the number.
- **The case that pins it corrupts the column on purpose**: a receipt of 10 leaves `stock_balance` at 10, then the
  location's legacy `stock` is set to 0 behind the ledger's back, and a sale of 5 is booked. **RED was measured
  against the previous code** — `InsufficientStockException: requested 5, available 0` — and the case is green
  after the change. That is the difference between saying "the column is not the source" and proving it.

**What the unit fixture had to be told, again.** `InventoryServiceSaleMovementTest` declared its world through the
legacy repository and the legacy columns; with the split moved, seventeen of its cases refused every sale for
having no stock, then failed on stubs that had become unnecessary, then on a verification that named a read the
code no longer makes. The fixture now states the projection beside the rows it already stated, and it says why:
a fixture that declares only the column describes a world where the ledger holds nothing.

**2026-11-03 — T3c was implemented, measured, and parked on purpose. The inventory is the plan.** Its own slice,
not the tail of T3:

What was built (kept at `wip/ledger-legacy-writes-off`, commit `d6fbc815`, **tree red by construction**): the eight
legacy column writes and the five legacy `inventory_movements` writes removed from every writer, the store-aggregate
availability gate deleted (the `stock_position` gate already decides it), the measure-unit guard moved to
`StockLedgerWriter.hasMovements`, and the manual edit's delta taken from `stock_position` — a real defect the change
created and that was caught before any commit: the delta was computed against the frozen column, so the editor would
have set the wrong target while every projection stayed self-consistent.

Why it is parked: the full suite reported **87 failing cases across 8 classes**, and none of them is a defect in the
change. Every one is a **dual-truth fixture**: the suites seed stock by writing `product_variant_store_stock.stock`
and `product_variant_locations.stock` *and* booking the ledger, then assert on the columns. Once the write path
stops maintaining them, those fixtures describe a world the code no longer lives in — the same lesson T1, T2 and
T3a each taught once, arriving here at the scale of a whole suite rewrite:

| Class | Cases |
| --- | --- |
| `InventoryLedgerBookingIntegrationTest` | 11 |
| `SalesOrderIntegrationTest` (13 nested classes) | 41 |
| `InventoryServiceSaleMovementTest` | 14 |
| `InventoryServiceTest` | 5 |
| `ProductVariantStoreStockWriteIntegrationTest` | 8 |
| `InventoryIntegrationTest` | 4 |
| `ProductVariantServiceTest` | 3 |
| `GoodsReceiptIntegrationTest` | 3 |

The plan the inventory implies, in this order: (1) every fixture that seeds stock books a movement and nothing else
— `SalesOrderIntegrationTest.seedSplitBalances` is the model, it already relocates through the ledger and its legacy
writes are exactly what has to go; (2) every assertion that reads a balance reads `stock_position` /
`stock_balance`; (3) the two cases that exist *because* the copies could disagree (`raiseTheLegacyBalancesByHand`
and its divergence case) are deleted rather than adapted, because the state they construct stops existing; (4) the
measure-unit and `ManualStockAdjustmentTests` cases assert the ledger's answer.

Two decisions recorded here so the next slice does not have to re-derive them: **D-2** (a manual edit's source is
`MANUAL_ADJUSTMENT` and the movement's own id — not shape (b) of the document identity, because nothing retries a
manual edit) and **the guard move** (the measure-unit refusal must ask `stock_movement` or it disappears silently
with the columns).

**2026-11-03 — delivered as ONE pull request, on the maintainer's choice.** The branch is 1042 authored lines in
four commits (three of code and records, one parking T3c), above the ~400-line advisory budget. The one honest
slicing pass was done and its boundaries are real: T1+T2 (the ledger's identity stops needing the mirror) and
T3a+b (the split reads the projection) are each green and each separately reviewable, which is why they are
separate commits with separate evidence. The maintainer chose a single PR over a chain after seeing both cuts, so
the budget is exceeded deliberately and with the reason stated here rather than in a review comment later. T3c and
T4 — which are where the columns and the service actually die — are **not** in this PR and the issue stays open for
them.

**2026-11-03 — T3c resumed as its own unit. L2, the maintainer's choice, verbatim**: «Sí, unidad nueva para T3c».
The parked slice leaves `wip/ledger-legacy-writes-off` (`d6fbc815`) and is delivered from `c80c3227` on
`feat/ledger-legacy-writes-off` (worktree `~/workspace/LifeControl-worktrees/feat-ledger-legacy-writes-off`),
together with the fixture rewrite the inventory above implies: a fixture that seeds stock by writing the two legacy
columns *and* booking the ledger describes a world the code stops living in the moment this slice lands, so each of
the 87 cases is rewritten to state one truth, not adapted to compare two.

**2026-11-03 — T3c landed: the write path stops maintaining the mirror, and the 87 dual-truth cases state one truth.**
What the code lost, and why each removal had to happen in this slice:

- **The eight legacy column writes and the five `inventory_movements` writes are gone** (`applyReceipt`,
  `applySaleDeduction`, `applyStockAdjustment`, `applyAdjustmentDecrease`, `applySaleReversal`). Nothing in
  `src/main` writes either column or that table now. `MIRRORED_SOURCE_TYPE` is read by exactly one thing — D-1's
  pre-`V73` fallback — and it dies with the table in T4. `newMovement`, the legacy row builder, had no caller left
  and is deleted; its javadoc had drifted onto the wrong method (`OpenSale`), which is now corrected.
- **The store-aggregate availability gate is deleted** from the sale path: it compared the frozen column, and the
  position gate (`V61` I8) is the one that decides. Both rows are still locked in the same order — store first,
  then the location — and the sale's read of the store row now says out loud that the lock is the point and the
  number is nobody's source. SpotBugs caught the three dead stores this left (`applySaleDeduction`,
  `applyStockAdjustment`, `reverseScope`), plus the accumulator `reverseScope` used only to update the aggregate:
  removing the write is not the same as removing the read.
- **The measure-unit guard asks the ledger** (`StockLedgerWriter.hasMovements`, S7), and
  `ProductVariantService`'s `InventoryMovementRepository` dependency is deleted because that guard was its last
  user. One probe now answers what two used to: a quantity above zero is nothing but a projection of the movements
  it counts, so `stock > 0` and `a row exists` were always the same question asked against two clocks.
- **A manual edit's delta comes from `stock_position` under its lock.** This is the defect the parked slice had
  already caught: against the frozen column the editor would set the wrong target while every projection stayed
  self-consistent — a change that creates a defect every projection agrees with.
- **D-2 is literal now.** The parked slice booked a fresh id as the source while the row got a different id from
  the schema default: two identities for one row, which is the shape this ADR exists to refuse. `bookAdjustmentIn`
  and `bookAdjustmentOut` now hand the booking's `sourceId` to the insert as the row's id, so a manual edit's
  source *is* its row. Nothing else derives from it — a manual edit is never reversed and never retried by its
  identity — which is exactly why it is not shape (b).

The 87 red cases, per file. Every one was a **dual-truth fixture**; none was a defect in the change:

| File | Cases | What it had to be told |
| --- | --- | --- |
| `ProductVariantServiceTest` | 3 | the guard's probe is `hasMovements`; the two column reads are asserted as never asked. The two cases that stated the two probes became one, because there is one rule now |
| `InventoryServiceTest` | 5 | a receipt books one ledger row and writes **no** legacy column; two cases about column arithmetic (`additive`, `NULL counts as zero`) are deleted — the arithmetic no longer exists |
| `GoodsReceiptIntegrationTest` | 3 | balances from `stock_balance`/`stock_position`, the booked movement from `stock_movement` |
| `InventoryIntegrationTest` | 5 | the fixture seeds stock through a receipt; the W3-D5 invariant is restated as the ledger states it (`stock_position = SUM(stock_balance)` over the store's locations); the 40-vs-100 divergence case is deleted — the state it constructed can no longer exist |
| `InventoryLedgerBookingIntegrationTest` | 11 | `assertTheLedgerAgreesWithTheLegacyBalances` became `assertTheLedgerHolds`: the "agreement between two copies" property is the thing this slice removes, so the helper now asserts the ledger's own equation plus the I2/I6 invariants; `raiseTheLegacyBalancesByHand` is deleted. The case that corrupted the column behind the ledger's back was deleted and then **restored**, because its state does still exist — see the verification entry below |
| `InventoryServiceSaleMovementTest` | 15 | the mock world is declared through the ledger's collaborators (a position and a set of balances, no column arithmetic) and the assertions read the bookings the engine makes (`bookSale`, `bookSaleReversal`, `bookAdjustmentIn`, `bookAdjustmentOut`) instead of legacy repository saves. No case deleted |
| `ProductVariantStoreStockWriteIntegrationTest` | 8 | the editor's probe is the ledger and its balances are the projections; the half of one case that probed the legacy stock floor is dropped (S7). One assertion on `product_variant_store_stock.stock == 0` remains on purpose: it reads the INSERT's default, not a balance, and goes with the drop in T4 |
| `SalesOrderIntegrationTest` | 41 | `saveStoreStock` books a receipt instead of writing the column, `seedSplitBalances` relocates through the ledger, and every balance/movement assertion reads the projections and `stock_movement`. One case deleted (5.2): it existed to make both legacy gates pass while the position refused; its message-shape assertions fold into the surviving 409 case |

**Two measurement corrections to the parked inventory.** `SalesOrderIntegrationTest` had **37** failing of 44 —
not 41 — and `InventoryServiceSaleMovementTest` **15**, not 14; the parked per-class split was counted from a run
whose `build/` a sibling session had overwritten (the `EOFException` / `in-progress-results` collision that hit
three of this session's runs). The total was 87 either way, and the count is the thing the inventory was for.

**Five cases are gone in total** (one per file above except the last two rows) and each deletion is stated in place
with its reason; the suite therefore reports **4500** tests instead of 4505.

Evidence, measured on the delivered tree:

- Full suite: **1066 result files, 4500 tests, 0 failures, 0 errors, 0 skipped** (baseline before the slice: the
  same 1066 files, 4505 tests, **87 failed**).
- `spotlessCheck` and `spotbugsMain` clean. `spotbugsMain` was red at first with three `DLS_DEAD_LOCAL_STORE`
  findings — the dead stores the removed writes left behind — which is the one place the mechanical half of this
  slice was not mechanical.
- Opened as `feat/ledger-legacy-writes-off` from `c80c3227`. T4 — the drops, the deletion of `InventoryService`
  and the cycle break — is still owed, so the issue stays open.

**2026-11-03 — independent verification of the slice, and the three things it corrected.** Native `assess` called
the candidate `medium`/`under_budget` (`reviewDue: false`), and the Task Size line above names items 1, 2, 3 and 6,
so the slice was escalated on item 2 ("the ledger decides who may write stock, and a guard that moves with it is
silent when it is wrong") and a separate read-only verifier ran over `c80c3227..HEAD`. Its verdicts, and what each
one changed:

- **S4, locks, S7, D-2 and the delta: all pass**, on `path:line` evidence — including that lock call sites went 16 →
  17 and none was removed, and that the manual edit's `stock_movement.source_id` is now literally its `id`.
- **One deletion reason was wrong, and the case is back.** The deleted split case claimed "the column holds its
  default for every variant". That is false for an upgraded database, where the frozen column keeps the last number
  the old writer set — precisely the rollout window this slice creates and the next one closes. The case is restored
  as `aSaleSplitsByTheProjectionNotByTheStaleColumn`: it seeds the ledger with a receipt, sets the column to 999 by
  hand, and asserts the split ignores it. The four other deletions stand, each for a state that can no longer exist.
- **Two dead probes stopped advertising themselves.** The measure-unit exception's javadoc still documented the two
  old probes; `ProductVariantStoreStockRepository.existsByProductVariantIdAndStockNot` still claimed the D3 guard
  uses it (it survives only so `ProductVariantServiceTest` can assert it is *never* called, and its javadoc now says
  exactly that); and `InventoryMovementRepository.existsByProductVariantId` was deleted outright rather than left as
  an invitation to read a table nothing writes.

Re-verified on the corrected tree: **1066 result files, 4500 tests, 0 failures**, `spotbugsMain` and
`spotlessCheck` clean. The verifier did not re-run the suite (it checked the artifacts of the run that produced them
and re-ran `spotlessCheck` in a scratch copy); this run is the one the delivered tree states.

**2026-11-04 — T4a landed (`c9bf83b0`, PR #770, merged `3eb890fb`): the cycle breaks and the engine stops reading the
mirror, with one defect found by the suite rather than by reading.** `InsufficientStockException` moved to
`inventory.exception` — the package that raises it — and `InventoryPackageDependencyTest` now pins the direction a
compiler cannot, because a cycle of two packages compiles happily and an unused import to a package a file no longer
builds against is the shape a later reader copies. `InventoryService` became
`inventory.ledger.StockLedgerEngine`: the four entry points keep their 400/404/409 contracts and lose the legacy
lock, the legacy write and the pre-`V73` fallback, so the serialization point is now the row each decision is about
(`stock_position` `FOR UPDATE`, and `V61`'s trigger for every writer that reaches the table).

The defect is the part worth keeping. `StockLedgerWriter.openBalances` joined `product_variant_locations` for one
reason only — to read that row's `created_at` for the FIFO order — and it was an **inner** join. The moment the
engine stopped creating those rows (`insertBalanceIfAbsent` was the only writer left), every balance vanished from
the split, so a sale the position had just authorized was refused by the second fail-closed check with
`available 0`. 46 integration cases said so before any test was written for it. The order is now the instant each
location first moved, which is where FIFO actually lives. Two consequences the slice states instead of hiding: a
pre-`V73` sale is no longer reachable by its line reference (the two cases that pinned the fallback now pin its
deletion), and the split answers from the ledger even when the frozen column holds another number.

Verification: the module suite green (`BUILD SUCCESSFUL`, 8m22s) and the four required checks green on PR #770.

**2026-11-04 — T4b landed (`6a06a9b3`): the drops, and the guard that had to be written twice.** `V74` drops the two
`stock` columns and keeps both tables — the store row still records the cost every inbound movement states, and both
carry identity rows the ledger's foreign keys point at. `V75` drops `inventory_movements` after reporting what that
costs.

- **`V74`'s first draft refused every database.** Its guard compares the legacy columns against `stock_balance` and
  `stock_position`, which `V60` armed with `ENABLE` **and** `FORCE`: the owner is filtered too, and a migration runs
  as the owner with no `app.company_id`, so both `NOT EXISTS` saw zero rows and every non-zero legacy balance looked
  unbooked. Four migration suites caught it (`V58`'s and `V62`'s among them) with the file's own message. The fix is
  the transactional `NO FORCE` window `V57`/`V62`/`V64`/`V66`/`V72` established, checked open before it is relied on
  and checked closed after, because a window that did not take answers every question with zero rows **and passes**.
- **`V75`'s refusal became a report, and D-1 above records why.** Measured before deciding: `V62` creates a
  mirror-identity `SALE` for every historical sale and almost none is reversed, so the refusal fired on every
  database that has sold anything before `V73` — the dev datadir included, which sits at `V09` and runs `V10`→`V75`
  in one go on its next start. The maintainer was shown the shapes and chose the report.
- **The legacy Java surface is gone**: `InventoryMovement`, `MovementType`, `ProductVariantLocation`,
  `InventoryMovementRepository`, `ProductVariantLocationRepository`, the surface suite that pinned the ledger
  repository, and `ProductVariantStoreStock.stock` with its accessors.
- **The suites moved with it**: a fixture that seeded a quantity seeds it through `applyReceipt` inside the tenant
  transaction, an assertion that read a legacy column reads the projection, and the three cases whose only subject
  was a dropped object are deleted with a note where they stood — never an assertion relaxed. One extra case needed
  the same treatment after the window fix let it run: `StockLedgerBackfillMigrationIntegrationTest` asserted the
  store column after migrating to head, and now asserts the column is absent while the two cases that deliberately
  stop before `V74` keep reading it.
- `StockLedgerMirrorDropMigrationIntegrationTest` is the new witness: no `stock` column on either survivor, both
  tables alive with `cost_price`, `inventory_movements` absent from `pg_class`, the five ledger tables and the two
  unique keys intact, and a receipt through `applyReceipt` still moving `stock_balance` and `stock_position`.

Verification: 944 cases over the `inventory`, `salesorder`, `goodsreceipt`, `product` and `tenant` packages, 0
failures; `spotlessCheck` clean.

**2026-11-04 — the unit closes.** ADR-0001's last stage is delivered as a chain on issue #739: T1 `4fb4c6c4`, T2
`5ad8fa7a`, T3a/T3b `444979ce`, T3c (#767, `19df0395`), T4a (#770, `3eb890fb`) and T4b `6a06a9b3`. What `main` now
enforces that it did not before this record: a receipt books its own line, a sale books its line and its write, a
manual edit books its own movement id, and nothing outside `stock_movement` writes quantity — the two legacy columns
and the table that claimed to explain every change are gone, `InventoryService` with them, and `inventory` no longer
imports `salesorder`. The debt this record named is paid; the cost this record accepted is the one in D-1, printed by
`V75` on every deploy that has history.
