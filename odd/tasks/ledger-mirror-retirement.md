# ODD feature: ledger-mirror-retirement

**Repository**: LifeControl — `life-control-api` (migrations, `inventory`, `salesorder`, `goodsreceipt`,
`product`), `odd/tasks/`
**Branch**: `feat/ledger-mirror-retirement` · **Worktree**: `~/workspace/LifeControl-worktrees/feat-ledger-mirror-retirement`
**Status**: implemented — T1 is the receipt's own line identity; T2–T5 are the rest of ADR-0001's last stage
(the reversal's identity, the write path that stops maintaining two balances, the drops that delete
`inventory_movements` and the two legacy `stock` columns, and the deletion of `InventoryService` with the
package-cycle break). `## Tasks` names what each one owes and `## Log` states what was measured before any of
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

S3. **A sale reversal resolves identity without the mirror.** The remainder a reversal credits is computed from
    `stock_movement` by source (`SUM(SALE) − SUM(SALE_REVERSAL)` per variant, store and location) instead of
    from `inventory_movements:960-968`, and the reversal row's `source_id` is the sale line's identity — not the
    legacy reversal row's id (`InventoryService:1028-1040`). `reverses_movement_id` still names the movement
    being corrected, which is what keeps the reversal outside `uq_movement_source` (its predicate is
    `reverses_movement_id IS NULL`).

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
| T2 | S3, S8 | The reversal's identity off the mirror: the remainder read moves to `stock_movement`, the reversal names the line, and the mixed-identity refusal that only existed because of the mirror is retired with a case that states why | inline |
| T3 | S4, S7, S8 | The write path: receipts, sales and adjustments stop writing the two legacy columns, and the measure-unit guard asks the ledger | inline |
| T4 | S5, S6, S8 | The drops, the deletion of `InventoryService`, the move of `InsufficientStockException` and the cycle break, with the ~14 test files that name the dropped objects updated | inline |
| T5 | S1, S5 | The records: `odd/tasks/inventory-ledger.md` T5d/T6 closed with what landed and what the drops removed, and this record's evidence | inline |

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
