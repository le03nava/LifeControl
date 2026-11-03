# ODD feature: ledger-mirror-retirement

**Repository**: LifeControl — `life-control-api` (migrations, `inventory`, `salesorder`, `goodsreceipt`,
`product`), `odd/tasks/`
**Branch**: `feat/ledger-mirror-retirement` · **Worktree**: `~/workspace/LifeControl-worktrees/feat-ledger-mirror-retirement`
**Status**: implemented — T1 is the receipt's own line identity; T2–T5 are the rest of ADR-0001's last stage
(the reversal's identity, the write path that stops maintaining two balances, the drops that delete
`inventory_movements` and the two legacy `stock` columns, and the deletion of `InventoryService` with the
package-cycle break). `## Tasks` names what each one owes and `## Decisions taken here

**D-1 — a sale booked before `V73` is reversible until the mirror is dropped, and not after.** The fallback that
finds those rows is deleted with `inventory_movements` in T4, and from that moment nothing maps a sale line id to a
ledger row whose source is the mirror. The drop's migration therefore asserts that no unreversed sale movement
carrying the mirror identity exists, and refuses with the operator's next step in the message. The alternative — a
backfill that rewrites those sources to the line identity — was refused because it would have to invent a
`write_seq` for rows that never had one, and an invented identity is the one thing `V73` exists to stop.

## Log` states what was measured before any of
it was written.
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
| T3 | S4, S7, S8 | The write path: receipts, sales and adjustments stop writing the two legacy columns, and the measure-unit guard asks the ledger | inline |
| T4 | S5, S6, S8 | The drops, the deletion of `InventoryService`, the move of `InsufficientStockException` and the cycle break, with the ~14 test files that name the dropped objects updated | inline |
| T5 | S1, S5 | The records: `odd/tasks/inventory-ledger.md` T5d/T6 closed with what landed and what the drops removed, and this record's evidence | inline |

## Decisions taken here

**D-1 — a sale booked before `V73` is reversible until the mirror is dropped, and not after.** The fallback that
finds those rows is deleted with `inventory_movements` in T4, and from that moment nothing maps a sale line id to a
ledger row whose source is the mirror. The drop's migration therefore asserts that no unreversed sale movement
carrying the mirror identity exists, and refuses with the operator's next step in the message. The alternative — a
backfill that rewrites those sources to the line identity — was refused because it would have to invent a
`write_seq` for rows that never had one, and an invented identity is the one thing `V73` exists to stop.

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
