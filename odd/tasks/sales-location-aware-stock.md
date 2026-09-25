# ODD feature: sales-location-aware-stock

**Status**: all four slices implemented and independently verified on `feat/sales-location-aware-stock`,
which sits on `main` @ `167a6ce` — the movement engine, the sales-path rewiring, the balance reset with
its source closure, and the flipped contract. **No slice work remains open.** What remains are the
follow-ups the feature opened, listed under `### Follow-ups opened by slice 2`. This header makes no claim about push or PR state; see the task log.
**Created**: 2026-09-24 · **Risk**: **high** — the change alters the stock-deduction semantics of a live
sales path, adds the missing ledger writer, and repairs persisted inventory balances from data that
cannot be recomputed (the ledger has no sales history to derive them from).
**Base**: `main` @ `6423256`, rebased onto `167a6ce` (PR #172) on 2026-09-25 before any source was
written. Branch `feat/sales-location-aware-stock`, worktree
`~/workspace/LifeControl-worktrees/feat-sales-location-aware-stock` (herdr `w18`), created with the
procedure in `.agents/skills/project-conventions/references/worktrees.md`.
**Requested by**: the user, asking on 2026-09-24 for the analysis of workstream W3 and for a new ODD
record if the analysis justified one. The product decisions below were taken by the user: W3-D1…W3-D4 on
2026-09-24, W3-D9 on 2026-09-25, plus the `stacked-to-main` delivery strategy and the slice-1 pull-request
shape.
**Relationship to other records**: this record owns **W3** of `odd/tasks/purchase-order-goods-receipt.md`.
That record's `# W3 — Sales rework (deferred)` section (`:777-800`) stays frozen as the record of the
deferral and carries a pointer here. Its decisions D1, D6 and D7-B are **cited below, not copied**.

## Why this exists

Three contracts written into the code name W3 as their resolution, and all three are still in force:

| Contract | Anchor | What it asserts today |
| --- | --- | --- |
| Sales is location-blind | `inventory/service/InventoryService.java:33-46` | Sales bypasses locations, so `product_variant_locations.stock` exceeds the sellable aggregate by everything sold since receipt |
| The ledger is partial | `inventory/model/MovementType.java:6-9` | Only `RECEIPT` exists; `SALE` and `ADJUSTMENT` arrive with W3 |
| The location is stored for W3 | `db/migration/V11__store_inventory_settings.sql:3-6` | `sales_location_id` is "stored now and consumed by the sales rework, W3" |

The sales path deducts from `product_variant_store_stock.stock` — the per-store **sellable aggregate** —
and never touches `product_variant_locations` nor the `inventory_movements` ledger. The reasoning path
(`InventoryService.applyReceipt`) writes all three.

Two live consequences, both operator-visible:

1. **Location balances overcount.** They are never reduced by a sale, so any screen that reads them as
   physical stock overstates it by everything sold since the last receipt.
2. **The ledger is not an audit trail.** It explains receipts only, so it cannot answer "why is this
   balance what it is", which is the reason it exists.

A third contract was discovered while writing this record and is not documented anywhere: **cancelling an
item does not restore its stock**, while deleting the item does (see F2).

## What the code says today (the map)

Every claim below was verified against `main` @ `6423256` with shell commands; the anchors are line
numbers, not recollection.

### The sales path: 8 stock-mutating entry points, one engine, no ledger

All stock lives in `product_variant_store_stock`; the sales domain never touches a location row and never
writes a movement (`grep -rn "InventoryMovement\|MovementType" life-control-api/src/main/java/com/lifecontrol/api/salesorder/`
→ no matches). One repository is used throughout: `ProductVariantStoreStockRepository`.

| Entry point | Anchor | Trigger | Stock effect |
| --- | --- | --- | --- |
| `createSalesOrder` | `:137-204` (call `:173`) | create with items | deducts |
| `updateSalesOrder` | `:207-321` (call `:264`) | item add/update/delete diff | net delta |
| `updateSalesOrderStatus` | `:413-457` (lock `:442`) | transition → `Cancelled` | restores |
| `deleteSalesOrder` | `:460-508` (lock `:486`) | soft-delete the order | restores |
| `addSalesOrderItem` | `:533-588` (call `:567`) | add one line | deducts |
| `updateSalesOrderItem` | `:591-634` (call `:610`) | change variant or quantity | net delta |
| `deleteSalesOrderItem` | `:637-660` (lock `:649`) | soft-delete the line | restores |
| `applyStockChanges` | `:833-937` | the shared engine | `:929` subtract, `:933` add |
| — `updateSalesOrderItemStatus` | `:663-688` | item → `Cancelled` | **nothing** (F2) |

The engine's shape matters for the ledger. Inside `applyStockChanges`:

- step 2 (`:864-870`) **sorts** the distinct variants by `StoreVariantKey` — a composite
  `(companyStoreId, variantId)` record (`:960-968`) — and step 3 (`:871-873`) locks them in that order,
  through `lockStoreStock` (`:946-952`), whose comment at `:862` names the reason: *"Sort by the composite
  store-scoped row identity to prevent deadlocks."*
- step 4 folds the per-item deltas into **one net delta per variant**, so the engine has no per-line
  granularity. That fold is the obstacle to a per-item ledger (see W3-D6).
- `:915` is the only stock read, always through `orZero` (`:955-957`). Every `getStock()` access in
  `salesorder/**` and `inventory/**` is `orZero`-wrapped — the NPE hazard recorded at
  `purchase-order-goods-receipt.md:223` no longer reproduces (F3).

Order store reassignment is rejected (`:224-231`), so a reversal always returns to the store the sale
deducted from.

### `InventoryService` cannot deduct

Its public surface is a constructor (`:57`) and **one** mutator: `applyReceipt` (`:80`). That method
rejects `quantity <= 0` up front (`:90-93`) and only ever adds (`:147`, `:150`). There is no
negative-delta path, no allocation across locations, and no reversal. **W3 has to add the movement API,
not reuse this one.**

Its lock order is `storeStock → locationBalance` (`:23-31`, code `:111-120`), and `:138-146` carries the
canonical explanation of why it must stay additive while sales is location-blind:

> `// 6. ADDITIVE, NEVER A RECOMPUTE. … Recomputing the sellable stock from the location sum would`
> `//    silently resurrect stock that sales already sold. … W3 owes the reconciliation and the switch`
> `//    of this method to recompute semantics.`

That comment is the interim contract in prose, and it is wrong the moment W3 lands (see W3-D5).

### The sales location exists and nobody reads it

`store_inventory_settings.sales_location_id` is `UUID NOT NULL REFERENCES store_locations(id)`
(`V11:24`), mapped at `StoreInventorySettings.java:35-36`, and set by the operator through the store's
inventory screen — `<mat-label>Ubicación de venta</mat-label>` at
`store-inventory-settings.html:68-71`. **Zero readers** outside that settings service:
`getSalesLocationId|salesLocationId` over `src/main/java` filtered of `StoreInventorySettings*` returns
nothing, and `salesorder/**` has no `StoreInventorySettings` dependency at all (its only mention is a
javadoc line at `SalesOrderService.java:716`). The data is ready; consuming it is the whole workstream.

### The reconciliation's edge case

The receiving location resolves **operator override first, the store's settings row second**
(`GoodsReceiptService.resolveReceivingLocationId`, `:354-364`). So a receipt into a store with **no
settings row** is reachable, which means location balances can exist for a store whose
`sales_location_id` is unknown. Any reconciliation rule keyed on the settings row has a hole here
(see W3-D7).

### The test surface, including the test that fixes the interim contract

| Suite | Anchor | Notes |
| --- | --- | --- |
| `SalesOrderIntegrationTest` | `:75`, helper `storeStockOf` `:373`, nested 5.1 `:381`, … | Testcontainers, real PostgreSQL. 5.1 `createOrderWithItems_StockDeducted` is the primary behaviour lock. **Entirely location-blind**: no test under `salesorder/**` reads `product_variant_locations` |
| `SalesOrderServiceTest` | `StockDeltaAndRestorationTests:2391`, `InsufficientStockExceptionTests:2725` | Mockito; 73 `@Test` methods in the class |
| `InventoryServiceTest` | `@Nested` at `:118` (quantity guard), `:161` (store guard), `:194` (additive receipt), `:337` (lock order) | The lock-order test at `:337-360` is the precedent the sales path lacks |
| `InventoryIntegrationTest` | `InterimInconsistencyTests:410-442` | **This test asserts the interim contract**: it constructs locations = 100 with an aggregate of 40 and asserts the receipt keeps the aggregate additive at 45. Its own comment names the reason: *"it fails under a recompute implementation."* W3 inverts its premise (F5) |

**Anchor drift after slices 1 and 2.** The map above is the pre-change picture at `main @ 6423256` and is
kept as such: slices 1 and 2 have since moved several of its anchors — the engine's new public methods,
`SalesOrderService`'s line numbers, and the unit-test count it quotes as 73, which is 77 now. The
decisions, findings and task list below are the live contract; the map is the historical picture.

## Inherited decisions (cited, not copied)

Taken in `odd/tasks/purchase-order-goods-receipt.md` and unchanged here:

| # | Decision | Effect on this record |
| --- | --- | --- |
| D1 | Stock balance per `(product_variant, store_location)` **plus** an append-only ledger; the per-store aggregate stays the sellable number | The location row and the aggregate row must both move, in the same transaction |
| D6 | **No backfill.** The system is not in production; rows may be reset | Authorises W3-D2's reset instead of a computed backfill |
| D7-B | Deduction by priority — the store's sales location first, then remaining locations FIFO — with an operator override | Priority and FIFO are adopted; **the override is dropped** (W3-D3) |
| W2-D7 | Whole-number `quantityReceived`; `DECIMAL(12,2)` columns with an integrality check at the receipt boundary | Sales quantities stay `DECIMAL(12,2)`; W3 does not revisit the measure-unit question |
| W3-1…W3-5 | The five deferred items (`:779-785`) | W3-1, W3-2, W3-4 adopted; **W3-3 is superseded** (W3-D5); **W3-5 is discharged by construction** (F6) |

## Decision register

**Product decisions, taken by the user on 2026-09-24:**

- **W3-D1 — insuffiency is fail-closed against the location sum, with priority spillover.** If the
  chosen location cannot cover the quantity but the store's locations together can, deduct the priority
  location to zero and continue FIFO. If the total cannot cover it, raise `InsufficientStockException`
  (409) exactly as today. *Precision added while writing the task list:* validation runs against the
  **aggregate** — the number the operator sees and the precondition that exists today — and the location
  allocation is a second, defence-in-depth check that fails closed when it cannot be satisfied. After
  W3-D2 the two coincide, so the second check can only fire on a state the reconciliation should have
  removed.
- **W3-D2 — the reconciliation is a derived reset.** For each `(variant, store)`, all location balances
  are zeroed and the aggregate is credited to that store's `sales_location_id`. Deterministic,
  idempotent, one migration. The accepted cost, already conceded by D6: the physical distribution across
  locations is discarded, and it is unknowable anyway — the ledger has no sales history to derive it
  from.
- **W3-D3 — no operator override.** W3 fixes the automatic deduction, the ledger and the
  reconciliation. No new request field and no UI. D7-B's override stays unbuilt, recorded as a
  follow-up.
- **W3-D4 — the item-cancellation gap is fixed inside W3.** `updateSalesOrderItemStatus` transitioning a
  line to `Cancelled` restores its stock and writes the reversal movement, matching item deletion.

**Engineering decisions, taken while writing this record (reviewable, not product choices):**

- **W3-D5 — W3-3's "switch to recompute semantics" is dropped.** Its premise was that the aggregate
  legitimately differs from the location sum. Once every mutation writes both sides in one transaction,
  the invariant `aggregate = SUM(locations)` holds by construction and a recompute has nothing to
  correct; keeping `applyReceipt` additive is then correct rather than a compromise. This *removes* work
  from the inherited plan. The comment block at `InventoryService.java:138-146` must be rewritten
  accordingly, and `InterimInconsistencyTests` must be replaced by an invariant test (F5).
- **W3-D6 — reversal reverses the ledger, not the allocation.** Restoring a sale by re-running priority
  allocation drifts the location distribution permanently (a sale that spilled 3+2 across two locations
  would restore 5 to the priority one). Instead a reversal reads the `SALE` movements of the reference
  and credits each location exactly what it gave, which the ledger makes possible and which is
  idempotent by construction: the uncovered remainder is `SUM(SALE) - SUM(SALE_REVERSAL)` per reference
  and location. **Consequence:** the engine's net-delta fold (`applyStockChanges` step 4) must become a
  per-item delta list so movements carry `SALES_ORDER_ITEM` + item id as their reference. Locking stays
  per distinct variant in `StoreVariantKey` order — the deadlock invariant is not touched.
- **W3-D7 — the ledger is type-driven, not sign-driven.** `SALE` and `SALE_REVERSAL` are added to
  `MovementType` and every `quantity` stays positive; the balance arithmetic is
  `SUM(RECEIPT) + SUM(SALE_REVERSAL) - SUM(SALE)` per location. Rejected alternative: storing a negative
  `quantity` on a `SALE` row. It is not forbidden by the schema (`V10` puts no `CHECK` on the column),
  which is exactly why the convention has to be explicit — and `RECEIPT` rows are positive, so a signed
  column would make every existing reader wrong. `ADJUSTMENT` is **not** added: nothing writes one. **Corrected 2026-09-25 by W3-D16:**
  `ADJUSTMENT_INCREASE` and `ADJUSTMENT_DECREASE` now exist, because slice 3 gave them a writer and the
  repo's rule is that a type arrives with its consumer — which is exactly why this decision refused to add
  one earlier. What the pair preserves is the *convention* established here: the direction lives in the
  type and every quantity stays positive, so the fold needed no amendment and no existing reader became
  conditional.
- **W3-D8 — a store with no settings row allocates FIFO and warns, it does not fail closed.** The
  reconciliation tie-break for a `(variant, store)` whose store has no `store_inventory_settings` row
  (reachable via a receipt override, see the edge case above) credits the aggregate to the location
  holding the largest balance, breaking ties by `store_location_id`. At deduction time the same absence
  means no priority location exists: allocate FIFO across the store's locations and log a warning.
  Rejected: failing closed with `StoreInventorySettingsNotFoundException`, which would turn a missing
  configuration row into a store-wide sales outage — the precondition for a sale is the aggregate, which
  has already been validated, and the location split is an accounting refinement.
- **W3-D9 — a decommissioned location's balance is still sellable.** Taken by the user on 2026-09-25,
  after independent verification found that the new allocation query filters `enabled` at no level of the
  `store_locations -> store_zones -> store_areas` chain. **This contradicts a documented intent in the
  code it mirrors**: `store/repository/StoreLocationRepository.java:36-38` states that a decommissioned
  leaf *"is never offered as a receiving or sales location"*, and the receiving path enforces it by
  rejecting the location outright (`GoodsReceiptService.java:193-199`,
  `DisabledReceivingLocationException`). The divergence is deliberate: `enabled` governs where new goods
  may be **placed**, not whether goods already on a retired shelf ceased to exist, and the system has no
  transfer feature — refusing the deduction would strand stock with no operator path to recover it. The
  consequence stays visible rather than implied: selling from a retired location is allowed, and
  W3-D2's reconciliation absorbs the whole location split into the sales location afterwards. The
  alternative is a one-line `enabled = true` filter on the new query if the project prefers the
  precedent's wording to its own edge case.
- **W3-D10 — the deduction reads an absent store-stock row as zero sellable stock, and answers 409.**
  Taken by the parent on 2026-09-25. Slice 1 gave the deduction a store guard symmetric with
  `applyReceipt`, which rejects a missing row with `IllegalArgumentException` (`:129-133`, 400 today).
  Slice 2 showed the symmetry was the wrong reading for a sale: a variant that exists and is enabled but
  has no row in the order's store used to answer `InsufficientStockException` (409) after creating the
  row on the fly. The deduction now raises `InsufficientStockException` directly and writes nothing; the
  receipt keeps rejecting. The asymmetry is deliberate and documented on both methods, because the two
  paths ask different questions — *is there sellable stock here* versus *does this store hold this
  variant at all*. This revision is why slice 1's `rejectsVariantWithNoStoreRow` expectation changed;
  the test was rewritten to the corrected contract rather than deleted.
- **W3-D11 — a line holds stock iff it is enabled, its item status is not `Cancelled`, and its order is
  not terminal.** Taken by the user on 2026-09-25, together with the instruction to close inside slice 2
  the hole it exposed. A disabled→enabled transition is a **fresh hold**: the deletion already reversed
  the stock, so the delta for a re-enabled line is its full quantity, not the difference against a
  quantity that no longer holds anything. The rule replaces three scattered booleans with one statement
  every path can be checked against. A `Cancelled` item is terminal (`SO_ITEM_TRANSITIONS`), so reviving
  a hold on it would create a ghost hold that the next order cancel would only return.
- **W3-D12 — an order in a terminal status cannot be modified.** Taken by the user on 2026-09-25,
  choosing to close the class of states rather than the single instance. `PUT /api/sales-orders/{id}`
  now rejects `Completed` and `Cancelled` with 409 (`SalesOrderAlreadyFinalizedException`). *Terminal* is
  derived from the transition table rather than hardcoded, so it stays true if the status machine
  changes. The stranded deduction this closes was **pre-existing** — the old net-delta path deducted on a
  cancelled order too — and **slice 3 would not have repaired it**: W3-D2 re-expresses the existing
  aggregate and never credits it, so a stranded deduction survives as a lower sales-location balance.
- **W3-D13 — a `Cancelled` or soft-deleted line cannot be modified either.** Taken by the parent on
  2026-09-25 as W3-D12's reasoning applied at line granularity, and recorded as such so that it is
  reviewable and reversible instead of silently widened: the user's choice for W3-D12 was explicitly to
  close the class, and the sibling hole was the same defect one level down. `PUT
  /api/sales-orders/{id}/items/{itemId}` rejects both states with 409
  (`SalesOrderItemNotModifiableException`, a new subtype of `ConflictException`). Re-enabling a
  soft-deleted line stays possible through the order-level `PUT` — the W3-D11 path, which deducts
  correctly — and that supported route is what lets the line-level guard be strict.
- **W3-D14 — the per-store stock editor writes all three sides, or it refuses.** Taken by the user on
  2026-09-25 after measurement showed this editor is **the only real source of divergence in the only
  environment that exists**: `ProductVariantService.upsertStoreStock` set the aggregate alone, and dev held
  aggregate 11.00 against location 1.00 with one RECEIPT and **zero** sales orders — so the divergence was
  never the "sold since receipt" overcount the narrative describes, it was a manual edit. This is the
  fourth stock writer, and the W3 map never listed it. The editor now writes the aggregate, the destination
  location row and a movement explaining the delta; the destination is the store's `sales_location_id`, and
  **a store with no settings row is refused** rather than guessed — which on dev is the normal case (zero
  settings rows), so configuring the store becomes the precondition for setting stock by hand. Same
  argument that forced slice 2 to migrate deduction and restoration together: a writer left outside the
  invariant re-creates the divergence that repairing it removes.
- **W3-D15 — the reset is a truncate, and it supersedes W3-D2.** Taken by the user on 2026-09-25 in
  preference to the derived reset: there is no environment whose history is worth a data rewrite (measured
  — see the corrected risk 2 and F17), so the smallest, most honest and most testable shape is the one V14
  already used. W3-D2's derived credit is **superseded, not deleted** — it stays in this register as the
  record of what was decided before the measurement. W3-D8's *reconciliation* tie-break goes with it,
  because with nothing to credit there is nothing to tie-break; W3-D8's **deduction-time** FIFO fallback
  stands untouched and is the live half of that decision. Consequence for slice 3: it is no longer a
  reconciliation slice but a **reset plus source closure** slice, and the two halves are independent — the
  truncate clears the existing inconsistency, W3-D14 stops it being re-created.
- **W3-D16 — a manual decrease is allocated like a sale, not assigned to one location.** Taken by the
  parent on 2026-09-25 as **W3-D1 applied to a second operation**, and recorded as such so it is
  reviewable rather than silently widened: the decrease is drawn over the store's locations in the same
  order the sale deduction uses — priority location first, then FIFO — by **reusing** the existing
  allocation helper instead of writing a second rule for the same concept, and one `ADJUSTMENT_DECREASE`
  row is written per location actually drawn from, so a spanning decrease explains itself in the ledger.
  An **increase** still credits the sales location, because there is nothing to draw from. The engine's
  fail-closed guard stays and raises the same typed exception, but verification established it has **no
  reachable case** while the invariant holds — `target >= 0` implies `amount <= aggregate = locationTotal`
  — so it is defence in depth, and the test that exercises it seeds a deliberately broken invariant
  instead of claiming coverage.

## Scope: four slices

Sliced for the review budget, in dependency order. Slices 1 and 3 are independent of each other; slice 2
consumes slice 1; slice 4 depends on all three.

| Slice | Content | Depends on |
| --- | --- | --- |
| 1 — the movement engine | `MovementType` gains the sale types; `InventoryService` gains the signed movement API (deduct with priority spillover, reverse by ledger); unit tests for allocation, spillover, fail-closed, lock order and ledger shape | — |
| 2 — the sales path | Route all 8 entry points through the engine; replace the net-delta fold with per-item deltas; reversal by ledger reference; fix the item-cancellation gap (W3-D4); integration tests that finally assert on location rows | 1 |
| 3 — the reset and the source closure | `V15` truncate reset (W3-D15, supersedes W3-D2) **plus** the per-store editor writing all three sides (W3-D14) | — |
| 4 — the contract flip | Rewrite the three interim contracts in prose; replace `InterimInconsistencyTests` with the invariant test; update `life-control-api/AGENTS.md` where it describes the sales/inventory contract | 1, 2, 3 |

## Task list

### Slice 1 — the movement engine (backend, no caller changes)

- [x] **S1-T1** `MovementType` gains `SALE` and `SALE_REVERSAL`, and its javadoc stops saying the sale
      types "arrive with W3" (`MovementType.java:6-9`).
- [x] **S1-T2** `InventoryService` gains the deduction API: resolve the store's priority location, allocate
      across locations with spillover (W3-D1), decrement each `product_variant_locations` row and the
      `product_variant_store_stock` row, and append one `SALE` movement per location consumed. Lock order
      stays `storeStock → locationBalance` (`:111-120`); the variant definition is read without a lock
      (`:98-103`).
- [x] **S1-T3** `InventoryService` gains the reversal API: for a reference, read its `SALE` movements,
      credit each location exactly, credit the aggregate, and append `SALE_REVERSAL` rows for the
      uncovered remainder only (W3-D6). No-op when the remainder is zero.
- [x] **S1-T4** No-settings-row fallback for both APIs (W3-D8), with a single warning and no new exception
      type.
- [x] **S1-T5** Unit tests: allocation order (`sales_location` first, then FIFO), spillover boundary
      (partial priority location), fail-closed on the total, reversal fidelity across a spillover,
      reversal idempotency, lock-order assertion mirroring `InventoryServiceTest:337-360`, and the
      ledger row shape (one row per location, `reference_type`/`reference_id` populated, positive
      quantities).
- [x] **S1-T6** `applyReceipt` is left untouched; the changed reading of its comment block is slice 4.

### Slice 2 — the sales path (backend)

- [x] **S2-T1** Replace the net-delta fold in `applyStockChanges` (step 4) with a per-item delta list, so
      movements carry line-level provenance. Locking keeps the sorted distinct-variant order
      (`:864-873`).
- [x] **S2-T2** Route the deduct paths through the engine: `createSalesOrder`, `updateSalesOrder`,
      `addSalesOrderItem`, `updateSalesOrderItem`.
- [x] **S2-T3** Route the restore paths through the engine's reversal: `deleteSalesOrderItem`,
      `updateSalesOrderStatus` → `Cancelled`, `deleteSalesOrder`, and the parts of `updateSalesOrder` that
      delete or shrink a line.
- [x] **S2-T4** Fix W3-D4: `updateSalesOrderItemStatus` → `Cancelled` restores the line and writes the
      reversal, matching item deletion. Decide and test the double-restore guard (a cancelled line that is
      later deleted must not restore twice — the ledger remainder in W3-D6 is the mechanism).
- [x] **S2-T5** `SalesOrderService` no longer injects `ProductVariantStoreStockRepository` directly if the
      engine fully owns the mutation; the store-reassignment rejection (`:224-231`) is re-verified because
      reversals depend on it.
- [x] **S2-T6** Integration tests (Testcontainers, `AbstractPostgresIntegrationTest`) that assert on
      `product_variant_locations` and on `inventory_movements` — closing the location-blindness of the
      whole suite. Extend the existing nested classes 5.1–5.12 rather than adding a parallel suite.
- [x] **S2-T7** Regression check on the 73 unit tests in `SalesOrderServiceTest`, especially
      `StockDeltaAndRestorationTests` (`:2391`) and `InsufficientStockExceptionTests` (`:2725`).

### Follow-ups opened by slice 2 (neither closed nor belonging to slices 3 or 4)

- [ ] **F-1 — the engine exposes a raw lock whose protocol lives in its caller.** `lockStoreStock` became
      public so the sales batch can pre-lock before it reorders reversals ahead of deductions. It adds no
      behaviour and preserves `storeStock -> locationBalance` for a correctly sequenced caller, but the
      cross-key ordering invariant now lives in `SalesOrderService` while the lock it depends on lives in
      `InventoryService`: **one invariant with two owners**, and the protocol is prose-only. The honest
      shape is an engine-owned batch operation that sorts, pre-locks and runs the two phases internally.
      Follow-up, not a defect — but it is the kind of split that rots.
- [ ] **F-2 — the cancelled-status lookup fails open.** `isCancelledLine` is fed
      `findByTypeNameAndStatusName(...).orElse(null)`; with that row missing the guard silently does not
      fire and a `Cancelled` line becomes modifiable again. Reachable only against a database missing a
      status V3 seeds, but for a guard that is the wrong default. Fix: fail closed, or resolve by id.
- [ ] **F-3 — the `Pending` asymmetry needs a decision rather than drift.** The order-level `PUT` accepts
      a `Pending` order while the item-level endpoints reject it through `loadAndValidateModifiableSO`.
      Verified pre-existing on base `167a6ce` and now documented in the method comment. Unifying the two
      policies is a product decision; W3-D12 deliberately did **not** narrow the rule to `Pending`.
- [ ] **F-4 — the multi-key lock ordering has no concurrent test.** The pre-lock's deadlock argument is
      read, not reproduced: the existing concurrency case (5.9) exercises a single `(variant, store)` key,
      so multi-key correctness rests on sequential tests.
- [ ] **F-5 — no test probes the item-level ownership boundary.** The ownership check runs before the line
      guard (verified by reading), so a wrong-order probe cannot distinguish states — but nothing builds
      an item belonging to a different order to prove it.
- [ ] **F-6 — two stubs are declared `lenient()`, which switches off Mockito's unused-stub detection for
      them.** `SalesOrderServiceTest:296-303` wraps `existsByIdAndEnabledTrue` and the
      `"SALES_ORDER_ITEM"/"Cancelled"` lookup in `lenient()`, while the class otherwise runs under
      `STRICT_STUBS`. A writer cited that mode as evidence that no stub is unused; for these two the claim
      does not hold, and independent verification caught it. The lookup is load-bearing for the reason
      that actually matters — `updateSalesOrder_ReEnableCancelledLineWithChangedQuantity_NoStockMovement`
      asserts `verifyNoInteractions(inventoryService)` — not because of strictness.

### Slice 3 — the reset and the source closure (data + writer)

Two halves, independent in content, per W3-D15 and W3-D14: the truncate clears the inconsistency that
exists, the editor fix stops it being re-created. Neither is sufficient alone.

- [x] **S3-T1** `V15__inventory_balance_reset.sql`: a truncate in V14's shape, clearing the balance side of
      the inventory — `product_variant_store_stock`, `product_variant_locations`, `inventory_movements` —
      and whatever else coherence requires, decided explicitly and stated rather than assumed. New
      migration only, never an edit to an applied one (`life-control-api/AGENTS.md:859`), and its header
      carries the **measured** precondition from F17 rather than an assumed one, in V14's own style.
- [x] **S3-T2** State what the migration does to an empty database and to one V14 already truncated, where
      both are no-ops, and what it means operationally: afterwards dev has variants and zero stock, and
      setting stock by hand requires configuring the store's inventory settings first (W3-D14) — which on
      dev means one row that does not exist today.
- [x] **S3-T3** Verify the post-reset invariant with a query: no `(variant, store)` where
      `store_stock.stock != SUM(locations.stock)`. Say plainly that on an empty database this is vacuous,
      so the query's real job is the operator gate on a database that has data — not a test.
- [x] **S3-T4** `ProductVariantService.upsertStoreStock` writes the aggregate, the destination location row
      and a movement explaining the delta, and refuses with a typed 4xx when the store has no settings row
      (W3-D14). The destination is the store's `sales_location_id`. If the delta needs a signed quantity,
      say so and propose amending W3-D7 explicitly instead of quietly breaking its positive-quantity
      convention.
- [x] **S3-T5** Tests for S3-T4 on both stock sides and the ledger, the refusal, and the invariant holding
      after an edit — including an edit that **lowers** stock, a direction the ledger has no precedent for.
- [x] **S3-T6** The `ADJUSTMENT` movement type is justified **in this register** by its
      consumer — W3-D16 for the pair, the corrected W3-D7 for the convention the pair preserves. Slice 1's
      refusal to add the type earlier is what makes the justification a requirement rather than a
      formality.

### Slice 4 — the contract flip

- [x] **S4-T1** Rewrite `InventoryService.java:33-46` and the `:138-146` comment block: sales is
      location-aware, the aggregate equals the location sum by construction, and the recompute question is
      closed (W3-D5) rather than deferred.
- [x] **S4-T2** Replace `InterimInconsistencyTests` (`InventoryIntegrationTest:410-442`) with an invariant
      test: after every sales and receipt operation, `aggregate = SUM(locations)`. The old test's premise
      becomes unreachable through the application.
- [x] **S4-T3** Update `life-control-api/AGENTS.md` where it describes the sales/inventory contract and the
      inventory schema, and check the invariants it states about stock.
- [x] **S4-T4** Sweep for remaining interim prose. Its original gate — `grep -rn "W3\|until the sales
      rework" src/main/java` returning only intentional references — **passed and proved less than it
      claimed**: 47 hits, all accurate `W3-Dn` citations, zero literal "until the sales rework", while a
      sentence reading *"the one sales **will later** deduct from"* sat in two files, named no `W3`, and
      was invisible to the pattern. The gate was widened to forward-looking phrasings (`will later`,
      `stored now`, `not yet`, `arrives with`, `once W3`, `when W3`, `until W3`, `for now`, `is deferred`,
      `pending the`, `to be read by`, `will be read`) and every hit was then classified **by reading it
      against the code**, not by matching. Result: **seven false passages corrected across four
      rounds** — three of the four members the plan named (`InventoryService`'s class javadoc, its
      additive comment block, `InventoryMovement`'s javadoc; the fourth, `MovementType`, was already
      accurate from slice 1), two it had not enumerated (`ProductVariantLocation.java:15`,
      `StoreInventorySettings.java:11-13`), one duplicate of the last
      (`StoreInventorySettingsService.java:34-35`), and one the **pre-commit seal** found in a file slice 3
      had already committed (`MovementType.java:33`, claiming a manual decrease removes stock from the
      sales location — false since W3-D16). The twelve-pattern set returns zero hits now; the wider
      reading-based sweep's extra hits were all accurate. **This class was declared closed twice before it
      was closed**, the second time by the text you are reading, which is the sharpest instance of what the
      next sentence says. **What the gate does not prove, stated where the gate lives:** a pattern finds
      only prose that uses its phrases, so a green sweep means *nothing matched*, never *nothing false
      remains*.

## Checks and route

**Resolved test mode: strict TDD is ON.** Source: the session-level `gentle-ai` skill — *"If tests exist,
follow strict TDD: RED, GREEN, TRIANGULATE, REFACTOR, and record evidence"* — and this repository has
tests. Runner: `./gradlew test` from `life-control-api/` (JUnit 5 + AssertJ; Testcontainers through
`AbstractPostgresIntegrationTest` for the integration classes). No repository or session configuration
states a TDD mode, so this paragraph records the **resolution**, not a setting: a task shows observed RED
before the implementation and GREEN after, with the failure output quoted, and a task that cannot obtain
RED evidence says so instead of manufacturing it.

**Gate per task**: `./gradlew spotlessCheck spotbugsMain test --no-daemon` in `life-control-api/`. Comment
and docblock changes are part of whichever task changes the behaviour they describe — they are not a
trailing cleanup that may be dropped.

**Route per task**, recorded so that skipped delegation is observable instead of silent:

| Task | Route | Trigger evidence |
| --- | --- | --- |
| S1-T1…T6 | delegated → one `gentle-ai-worker` | Writer trigger: 2+ non-trivial files (`InventoryService`, `MovementType`, two repositories, the test class) |
| S2-T1…T7 | delegated → one `gentle-ai-worker` | Writer trigger, plus four entry points sharing one engine whose semantics must move together |
| S3-T1…T3 | declared when reached | — |
| S4-T1…T4 | delegated → one `gentle-ai-worker`, then two scoped follow-up rounds | Writer trigger: 4 files including a test replacement (`InventoryService`, `InventoryMovement`, `InventoryIntegrationTest`, `AGENTS.md`). The follow-up rounds were delegated too (2+ files each). One single-line javadoc correction (`StoreInventorySettingsService.java:34-35`) ran **inline** under the mechanical-single-file rule — the only production edit the parent made in this feature |

**Declared divergence, slice 1**: it lands a public API with no production caller, against this
repository's own rule that rejects code added ahead of its consumer
(`purchase-order-goods-receipt.md:265` refused to ship exception types before their throwers). The reason
is that the consumer is slice 2, whose diff is the largest in the plan, and the engine is fully testable
in isolation. Consequence to honour: **slice 2 is not optional and must land before any release** — until
it does, the repository carries an unreferenced public method.

**Delivery strategy: `stacked-to-main`**, decided by the user on 2026-09-25 before the first source
commit, because the forecast below exceeds the 400-authored-line budget. One pull request per slice, each
targeting `main` and stacked on the previous one, so a PR holds exactly its slice's commits and nothing
else. No pull request existed when this line was written and no number is asserted here — a PR number is
recorded in the task log once it exists. Retargeting a stacked PR is done with `gh api --method PATCH`
followed by a check of the base ref, because `gh pr edit --base` fails silently under Projects-classic
(`product-variant-admin-ui.md`).

| Slice | Its own pull request | Commits it holds |
| --- | --- | --- |
| 1 — movement engine | yes, first | slice 1's work-unit commits |
| 2 — sales path | yes, stacked on slice 1 | slice 2's commits |
| 3 — reconciliation | yes, independent in content of 1 and 2 | slice 3's commit |
| 4 — contract flip | yes, last | slice 4's commits |

## Review workload forecast

| Slice | Estimated changed lines | Over the 400-line budget? |
| --- | --- | --- |
| 1 — movement engine + unit tests | ~450–650 | Yes, marginally |
| 2 — sales path + integration tests | ~700–1000 | Yes, clearly |
| 3 — reconciliation migration | ~120–200 | No |
| 4 — contract flip + docs | ~150–250 | No |

Total ~1400–2100 lines. **Chained PRs are recommended**, stacked to `main`, in the order 1 → 2 → 3 → 4,
following the procedure and the retargeting trap documented in
`odd/tasks/product-variant-admin-ui.md` (merge independent slices first; retarget stacked PRs by API
before merging their base). The forecast is a band, not a measurement: the previous slice in this
repository overshot its own forecast by a factor of four when it assumed a single component and found a
missing read path, so slice 1 must be gated before slice 2's estimate is trusted.

**Measured against forecast, slice 1: 1115 authored lines** — 384 production plus 731 test — against the
450–650 band above, that is 1.7–2.5× the estimate, and this is the first honest measurement of the plan:
the band was written before the engine's shape was known. The user decided on 2026-09-25 that **slice 1
ships as one pull request with this divergence declared, not split** — the ~400-line figure is a planning
heuristic whose governing rule forbids treating it as a cap, a forced split or a size-only rework trigger;
the writer's explanation for the size was reviewed; and independent verification measured the 22 tests as
mapping 1:1 onto the seven named behaviours of S1-T5 rather than padding. Consequence for the chain: the
first pull request is ~2.8× the budget, and **slice 2's band below must be re-measured rather than
trusted**, because it was derived the same way this one was.

**Slice 2 measured: 1902 insertions / 513 deletions across 6 files, plus one new 23-line exception —
~2438 line-changes**, against a 700–1000 band, and it took **four production rounds** after the first
implementation: the variant-change ordering defect (F13), the re-enable hole (W3-D11), the terminal-order
guard (W3-D12) and the line-level guard (W3-D13). Two of those were found by the coverage round a
verification demanded, and one — the worst — was found by the writer of that coverage work. **Slices 1 and
2 now measure ~3553 line-changes for two of four slices**, against a band of 1400–2100 for all four. The
bands below are therefore historical: **S3 and S4 must be estimated from these measurements**, not from
the original forecast, and F15 is the reason the overrun is worth more than its number.

## Risks

1. **The invariant has to hold from the first deploy.** If slice 2 ships before slice 3, stores that sold
   before the deploy still overcount; if slice 3 ships before slice 1, the reconciliation immediately
   re-creates the exact inconsistency it is repairing (sales deduces from the aggregate only). Slices 1
   and 2 must land in the same release as slice 3, or slice 3 must be re-run afterwards.
   **Corrected 2026-09-25:** the coupling changes shape rather than disappearing. A truncate cannot
   re-create anything (W3-D15), but it also does not *stay* repaired — until W3-D14 lands, the per-store
   editor keeps producing divergence, so the reset and the editor fix are what must ship together, while
   slices 1 and 2 are what make the invariant hold going forward. The measured precondition (F17) is what
   makes the reset acceptable at all.
2. **A wrong reconciliation is a data loss with no undo.** The reset discards the per-location
   distribution and, unlike V14, runs against databases that are not dev. `V14` was declared destructive
   and accepted; this one is not destructive but it is *irreversible in meaning* — the pre-migration
   distribution cannot be reconstructed. Take a backup, and gate on the invariant query (S3-T3).
   **Corrected 2026-09-25, by measurement rather than argument:** the clause *"runs against databases that
   are not dev"* is **false**. `docker ps -a` shows only `lifecontrol-dev-*` containers,
   `docker/volumes-staging` is 88 KB and empty, and prod's `VOLUMES_ROOT=/var/lib/lifecontrol` does not
   exist — one environment, whose history V14 already discarded once. The paragraph above is kept as the
   record of what was assumed; what replaces it is a truncate (W3-D15) whose precondition is measured. The
   residual risk is no longer data loss but its opposite: a reset that a still-broken writer re-breaks,
   which is why W3-D14 ships with it.
3. **The ledger becomes an audit trail only if the reversal is exact.** A reversal that re-allocates
   instead of reading the ledger silently corrupts every location balance it touches while keeping the
   aggregate right — the failure mode is invisible on the number the operator looks at. This is why
   W3-D6 is a decision and S1-T5 tests reversal fidelity across a spillover.
4. **The net-delta fold removal touches the hottest path in the module.** Four entry points share
   `applyStockChanges`, and the existing integration tests 5.1–5.12 assert the deduction semantics of all
   of them. The lock-order invariant (`StoreVariantKey`, `:864-873`) must survive the refactor.
5. **A second writer in the same area — resolved before any code was written.** A concurrent worktree
   was live at the time of this analysis — `fix-inventory-settings-conflict-ux` (`w17`), branch of the
   same name, touching `store-inventory-settings.ts`, `store-location-summary.models.ts`,
   `http-error-message.ts` and `receipt-create.spec.ts`, with a new
   `odd/tasks/inventory-settings-conflict-ux.md`. It is the frontend half of D13b and it edits the screen
   that sets `sales_location_id`. **It merged as PR #172 (`167a6ce`) on 2026-09-25.** Verified outcome:
   the change is frontend-only — it makes `StoreInventorySettingsResponse.version` required, sends the
   precondition, and recovers the form from a 409 — and it leaves the backend `store_inventory_settings`
   table, entity and `sales_location_id` exactly as this record assumes. No file of this feature overlaps
   it. The instruction *"verify the settings contract has not moved before writing S3-T1"* is discharged
   by that verification, not deferred.

## Out of scope (decided)

- **The operator location override** (W3-D3) — stays unbuilt; a follow-up, not a gap.
- **Recompute semantics for receipts** (W3-D5) — dropped, with the reasoning recorded rather than left as
  an unfinished task.
- **`ADJUSTMENT` movements** (W3-D7) — *corrected 2026-09-25:* the pair
  `ADJUSTMENT_INCREASE`/`ADJUSTMENT_DECREASE` **does** exist, because slice 3 gave it a writer (W3-D16).
  What stays out of scope is a bare `ADJUSTMENT` type and any signed quantity.
- **The Keycloak protocol mappers** for the `company_id → company_store_id` claim chain. Blocking for
  `lc-sales` in any real environment (`docker/scripts/keycloak-setup.sh` provisions none), inherited from
  `store-claim-hardening` and `purchase-order-goods-receipt.md:471`. A separate component, unaffected by
  this record.
- **Negative stock** (W3-D1 chose fail-closed) and the measure-unit/integrality question (W2-D7).
- **The `status-transition` defect in `features/sales`** — a separate unit in the same Angular feature,
  with its own record if it is fixed.
- **Pre-existing debt recorded elsewhere**: `ProductVariantOption` nullability
  (`product-variant-admin-ui.md:479`), the two clients for `GET /api/product-variants/search` (`:478`),
  read authorization on `GET /api/products` (`:472`).

## Findings

- **F1 — the deferral record's narrative is stale.** `purchase-order-goods-receipt.md:45-65` describes the
  interim as `applyStockChanges (:774)` doing `variant.getStock().subtract(...)`. V14 removed `stock` from
  `ProductVariant` (`ProductVariant.java:26-40` has id, productId, barCode, variantName, enabled) and the
  engine now moves `ProductVariantStoreStock` at `:833`, `:915` through `orZero`. The intent of W3-1…W3-5
  survives; the anchors and the mechanism do not. The old record is not corrected here beyond the pointer,
  per its D4.
- **F2 — cancelling an item never restores its stock.** `updateSalesOrderItemStatus` (`:663-688`) sets the
  status and stops. Deleting the item restores (`:637-660`), cancelling the order restores (`:413-457`),
  and `Pending → Cancelled` is an allowed item transition, so the deduction stands forever with no ledger
  trace. Found while mapping; not documented in any record (W3-D4 fixes it).
- **F3 — the NPE hazard recorded as a follow-up does not exist.** `purchase-order-goods-receipt.md:223`
  claims `SalesOrderService.java:860-861` reads stock without a null guard. All six `getStock()` reads in
  `salesorder/**` and `inventory/**` are `orZero`-wrapped (`:448`, `:492`, `:650`, `:915`, `:147`, `:150`)
  and `grep "\.getStock()\." src/main/java` returns nothing.
- **F4 — the sales location has zero readers.** Verified by grep over `src/main/java`; the settings screen
  is the only writer path. V11 has been carrying a column destined for W3 since before the inventory
  module had a ledger.
- **F5 — a test currently asserts the interim contract.** `InterimInconsistencyTests`
  (`InventoryIntegrationTest:410-442`) builds locations = 100 against an aggregate of 40 and asserts the
  receipt answers 45, with a comment stating it fails under a recompute. W3 does not merely add tests: it
  inverts this one's premise, which is why S4-T2 is a task rather than a cleanup.
- **F6 — W3-5 is discharged by construction, not by work.** "Make the location picker honest — location
  stock only becomes sellable availability after W3" presumed the frontend would show location stock.
  It does not: `grep -ril "locationBalance\|locationStock\|product_variant_locations" life-control-app-angular/src`
  returns nothing, and the only stock a sales principal sees is the per-store aggregate
  (`product-variant-selector.html:28`, projected from `pvss.stock` at `ProductVariantRepository.java:71`).
  With W3-D3 (no override) there is nothing to build here.
- **F7 — there is no deadlock inversion to fix.** Inventory locks `storeStock → locationBalance`
  (`:111-120`); sales locks only `storeStock`, sorted by `(companyStoreId, variantId)` (`:871-873`). Where
  they overlap the order agrees. The invariant that must survive slice 2 is the *introduction* of the
  balance lock into the sales path, which is why the sales path needs the lock-order test the inventory
  service already has (`InventoryServiceTest:337-360`).
- **F8 — the "store with no settings row" state is reachable, and it is the ordinary case rather than an
  edge.** A receipt can target a store with no `store_inventory_settings` row through the operator
  override (`GoodsReceiptService.resolveReceivingLocationId`, `:355-363` — the override returns before
  the settings lookup runs), so location balances can exist with no `sales_location_id` to attach them
  to. **Independently re-verified** by the session that owns the settings screen, which added the
  precision that a *never-configured* store is the common case and not a corner; the measurement agrees,
  since dev holds **zero** settings rows. What follows changed with W3-D15: the reconciliation tie-break
  is gone, because with nothing to credit there is nothing to tie-break. What stays live is W3-D14's
  **refusal** on the stock editor — which is what turned configuring the store into a precondition for
  setting stock by hand, in ordinary use rather than in an edge — plus W3-D8's deduction-time FIFO
  fallback. That same session also claimed the settings row could disappear *between* a GET and a PUT, and then
  **retracted the claim after verifying it** — the evidence is worth keeping: the repository has no
  caller for any inherited `delete*` (its five call sites are `findById` ×3, `save` and `saveAndFlush`),
  and `V11:22` declares `company_store_id` as a primary key referencing `company_stores` **without
  `ON DELETE CASCADE`**, so the foreign key would reject the delete and stores are soft-deleted anyway.
  **Latent, not reachable:** the inherited delete methods still exist on the interface, so a future
  caller could remove a row for a live store. That is the only way this state gains a second producer,
  and it would be a new decision rather than a bug. The session's own account of how it got there is the
  same failure this record keeps finding: it inferred a path from a page's 404 handling without looking
  for the delete, which is an inference presented as a measurement.
- **F9 — the reset decision buys a scope reduction.** W3-D2 plus W3-D5 remove two items from the inherited
  plan: the recompute switch and, with it, the reason the additive receipt was a compromise.
- **F10 — a green gate can execute nothing, and this worktree produced one.** The authorized gate
  `./gradlew spotlessCheck spotbugsMain test --no-daemon` returned `Task :test UP-TO-DATE` with
  `9 actionable tasks: 9 up-to-date` — **zero tests executed** — while the writer's own XML reported 600
  classes / 2082 tests. The cause is stale incremental state in `build/`, which in this worktree also
  produced `No tests found` for existing classes and a green run aggregating only the 22 new tests. It was
  caught only because verification was independent: a verifier that had accepted the writer's summary
  would have certified a suite that never ran. Force a real execution (`cleanTest`) before treating any
  green in this repository as evidence.
- **F11 — the allocation ignores `enabled`, against the nearest precedent's stated intent.** Settled by
  the user as W3-D9, with the contradiction to `StoreLocationRepository` recorded there rather than
  smoothed over.
- **F12 — the new store-scoped JPQL is never executed against PostgreSQL in slice 1.** The FIFO finder's
  scope chain is validated only in the sense that Spring Data parses it at bootstrap (the integration
  classes boot the context) and the unit tests mock the repository; no test calls the allocation against a
  real database. Its join chain matches the executable precedent and its ordering is deterministic, but
  **data semantics are unexecuted until S2-T6**, which owns that coverage. Do not mistake a parsed query
  for a proven one.
- **F13 — slice 2 lost a sale whenever the new variant's uuid sorted first, and no invariant could see
  it.** A variant change registered `reverse(old)` and `deduct(new)` under one reference with two
  different `StoreVariantKey`s, and the batch was sorted by key, so the deduction ran first; the
  reference-scoped reversal then read that fresh `SALE` row and credited it straight back. The line said
  variant B while **nobody held the stock**. Reachable through both the order-level and the item-level
  `PUT` for roughly half of all variant changes — those whose new uuid sorts earlier. The repro is
  `SalesOrderIntegrationTest$UpdateItemVariantChangeTests.updateItem_VariantChange_NewVariantSortsFirst_StillSellsNew`,
  observed as `expected: 7.00 but was: 10.00`. It survived both of this record's own guardians: slice 1's
  verification passed the engine's contract, slice 2's passed `aggregate = SUM(locations)` path by path,
  and both were green while the stock was simply unheld, because the ledger and the balances stayed
  mutually coherent. Fixed by running all reversals before all deductions in a batch, with every distinct
  key pre-locked in order first so the reorder cannot invert the lock acquisition order.
- **F14 — three times, a state that must not be modifiable was modifiable.** The re-enable of a deleted
  line handed out stock for free (W3-D11); `PUT /api/sales-orders/{id}` modified a terminal order and left
  a deduction nothing would ever reverse, which slice 3 would **not** have repaired (W3-D12); and the
  item-level `PUT` accepted a `Cancelled` or soft-deleted line, deducting on it with no line holding the
  stock (W3-D13). Each was pre-existing or newly reachable, each was reachable through the API with a
  well-formed request, and each needed its own guard rather than a fix to the stock arithmetic. The
  register now carries all three decisions with their reasoning.
- **F15 — the defects were compositional, and only coverage found them.** None of slice 2's four defects
  lived inside a part: the ordering one is a property of the *sequence of two calls*, the re-enable one of
  the interaction between `enabled` and the status machine, and the two guards were a state nobody had
  ever enumerated. A component contract check and an invariant check can both be green while the
  composition loses money. **Consequence for slices 3 and 4:** their verification plans must lead with
  adversarial orderings and boundary states — an explicitly inverted key order, a status/enabled matrix,
  and a re-run of a mutating operation — instead of waiting for coverage to stumble onto them three rounds
  later.
- **F16 — there was a fourth stock writer, and it was the only one actually breaking the invariant.**
  `ProductVariantService.upsertStoreStock` (`:249-280`) inserts the aggregate row if absent and assigns
  `request.stock()` **without writing any location row and without requiring a store settings row**. It is
  the per-store stock editor, live for `lc-admin` and `lc-sales`. Measured on dev 2026-09-25: aggregate
  11.00 against a single location balance of 1.00, with the ledger holding exactly one `RECEIPT` of 1.00
  and `sales_orders` empty — so the divergence W3 was planned around had **nothing to do with sales**. The
  record's own map listed the eight sales paths and the receipt and missed this one, which means the
  invariant was being broken by a feature the plan never considered. Settled as W3-D14.
- **F17 — the environment claim in risk 2 was never measured, and it was false.** `docker ps -a` lists
  only `lifecontrol-dev-*` containers; `docker/volumes-staging` is 88 KB and empty; prod's
  `VOLUMES_ROOT=/var/lib/lifecontrol` does not exist. There is exactly **one** environment with data, it is
  dev, and its operational chain had already been truncated once by V14 (Flyway at version 14, four
  variants, one aggregate row, one location row, one movement, zero sales orders, zero purchase orders,
  **zero** `store_inventory_settings` rows). Every claim in this record that assumed non-dev databases with
  history to lose — risk 2 and S3-T1's premise — was assumption, not evidence. Corrected in place and
  recorded here rather than quietly fixed.
- **F18 — the manual stock edit could drive a location negative while the invariant held.**
  `applyStockAdjustment` assigned the whole delta to the store's `sales_location_id`. Because `applyReceipt`
  credits the store's **receiving** location while the editor debited the **sales** location, a store
  configured with two different locations could hold its stock on the dock and zero on the sales location:
  receive 10 on the dock, set stock to 5, and the sales location landed at **−5** while
  `aggregate = SUM(locations)` stayed true — so no invariant check and no test could see it. It was found
  by the writer of slice 3 as a risk on its own work and reproduced before the fix at unit and integration
  level as `expected: 0.00 but was: -5.00`. Settled as W3-D16. Recorded here because the code cites `F18`
  and, until this line existed, the citation resolved to nothing.
- **F19 — three times in one feature, a check passed because the check was narrower than the claim it
  stood for.** Slice 1's gate reported a green suite that had executed **zero** tests (`Task :test
  UP-TO-DATE`, **F10**). Slice 2's invariant check passed path by path while a variant change lost its
  sale, because the ledger and the balances stayed mutually coherent while nobody held the stock
  (**F13**). Slice 4's prose gate returned only intentional references while a future-tense sentence
  asserted the opposite of the code in two files, because the pattern looked for `W3` and the sentence
  never named it. Three different mechanisms — a build system, an invariant, a regex — and one failure:
  **the gate was calibrated against a phrase or a symptom rather than against the property, and its green
  was read as if it proved the property.** None of the three was a bug in the check; all three were caught
  by someone who opened the artefact instead of reading the check's result. **What this leaves behind is a
  convention:** state what each gate does *not* prove, next to the gate. Slice 3's migration gate does it
  — *the query is prose; the data outcome is not testable in this stack* — and that is the standard this
  record asks the next one to meet.

## Task log

| Date | Task | Evidence |
| --- | --- | --- |
| 2026-09-24 | Read-only W3 analysis (mapping + independent re-verification of every anchor in this record) | Scout `gentle-ai-explore` returned a nine-section code map; the anchors of this record were then re-verified in the parent shell, which corrected three of its claims (the `openspec/` tree does exist and holds an empty sales change directory; `LC_SALES` exists at `core/security/roles.ts:16`; the NPE hazard does not reproduce) |
| 2026-09-24 | Product decisions W3-D1…W3-D4 | Taken by the user in this session, each from the option recommended in the analysis |
| 2026-09-24 | This record and the worktree | Branch `feat/sales-location-aware-stock`, worktree `~/workspace/LifeControl-worktrees/feat-sales-location-aware-stock`, herdr workspace `w18`, off `main` @ `6423256` |
| 2026-09-24 | Pointer added to the deferral record | `odd/tasks/purchase-order-goods-receipt.md`, two insertions: after the header block and at the `# W3 — Sales rework (deferred)` heading. No existing text replaced, per that record's D4 |
| 2026-09-25 | Base moved under the record; risk 5 discharged | A peer session reported `main` had advanced: verified `6423256` → `167a6ce` (PR #172, the frontend half of D13b), 3 commits ahead of this worktree, diff = 6 Angular files plus a new `odd/tasks/inventory-settings-conflict-ux.md`, so **no file of this record overlaps it**. Also verified the type change the peer flagged does not reach this record's tasks: `StoreInventorySettingsResponse` already carried a required `long version` and `StoreInventorySettingsRequest` an optional `Long version`, so the frontend model was catching up to a backend contract that did not move. The rebase is deliberately **not** done yet — the record is uncommitted and shall not be stashed for it; it runs after the work-unit commit |
| 2026-09-25 | Delivery strategy decided: `stacked-to-main` | The user chose it from the `ask-on-risk` preflight ask, taken before the first source commit because the forecast below exceeds the 400-authored-line budget. Recorded with the slice→pull-request mapping in `## Checks and route` |
| 2026-09-25 | Slice 1 implemented (S1-T1…S1-T6) | Written by one delegated `gentle-ai-worker` (route and trigger in `## Checks and route`): 6 files under `life-control-api/**/inventory/` plus a new `InventoryServiceSaleMovementTest` (22 tests, 7 nested classes). The reachable RED for a brand-new Java API is a **compile failure** (`cannot find symbol method applySaleDeduction`), not an assertion failure — recorded as the weaker form it is. Verification found one overstated javadoc claim (the balance rows are *not* locked "the same sequence for every caller": the reversal orders by `store_location_id` while the deduction orders FIFO; safety comes from the shared `storeStock` row being first for every mover), corrected in this commit. That correction is **comment-only**, so the compiled behaviour is identical to the bytes the `09:30:46` gate covered, but it is not literally the same tree: the next forced gate, at slice 2, is the first that covers the final bytes. Stated rather than glossed, because F10 is exactly the cost of not stating it. |
| 2026-09-25 | The gate was a green no-op and was re-run under force | `Task :test UP-TO-DATE`, 9/9 up-to-date, **no test executed**, while the writer's XML showed 2082. Forced with `cleanTest`: `BUILD SUCCESSFUL in 1m 22s`, XML mtime `09:30:46` postdating the last source edit `08:42:22`, aggregate **600 classes / 2082 tests / 0 failures / 0 errors / 0 skipped**, SpotBugs 0 findings. This is what promoted S1-T5 from "read" to "independently executed" — see F10 |
| 2026-09-25 | Verification of slice 1 by a separate read-only session | `gentle-ai-verify` independently re-read the engine and confirmed: lock order `storeStock -> locationBalance` with non-vacuous lock-order tests; no write on either fail-closed path; the reversal derives from the ledger, never consults the settings row and is a true no-op when re-run; the FIFO tie-break is deterministic even within one transaction; W3-D8 warns exactly once; `applyReceipt` byte-identical; no file outside the allowed surfaces; the writer's self-reported index writes left no residue. It also produced F11 and F12 |
| 2026-09-25 | Slice 2 implemented (S2-T1…S2-T7) | One delegated `gentle-ai-worker`: every one of the eight sales entry points routes through the engine, the net-delta fold became a per-line operation list sorted by `StoreVariantKey`, the ledger reference is the persisted line id, and `SalesOrderService` no longer touches store stock directly. Measured 1332 changed lines at that point |
| 2026-09-25 | Slice 2's first independent verification | No blocking finding: the invariant holds path by path (no path moves only the aggregate), the lock order survives, provenance is asserted against the id the API returned. It found **one real defect of coverage** — the unit tests had been converted from asserting the post-state to asserting the engine's arguments, and nothing backfilled it for the update paths — plus four untested idempotency orderings |
| 2026-09-25 | The coverage round found F13 | ~520 added lines, 12 of 13 new tests green, and the thirteenth left **failing on purpose** as the repro for the variant-change ordering defect instead of touching production outside its surfaces. That is the round that also closed S2-T6/S2-T7's gap and produced the genuine RED `expected: 7.00 but was: 10.00` |
| 2026-09-25 | Fix rounds: F13 ordering, then W3-D11 re-enable | The ordering fix keeps the key split, adds a `reversal` flag, pre-locks all distinct keys in sorted order and only then reorders. The naive "reversals first" alone would have re-introduced a deadlock by inverting lock acquisition; that was the parent's instruction being incomplete, caught by the writer. The re-enable fix grounded "holds stock" on the live state instead of the recorded quantity, with its own observed RED `expected: 96.00 but was: 100.00` |
| 2026-09-25 | Verification of the ordering and re-enable fix | The gate was a green no-op in the writer's hands first (`Task :test UP-TO-DATE`) and only `cleanTest` produced real execution — 606 classes / 2103 tests. Confirmed the pre-lock is the first *stock* lock, that `applyReceipt` keeps its rejection while the deduction answers 409, and that the cancelled-line guard was **vacuous** as tested (same quantity ⇒ delta zero with or without it) |
| 2026-09-25 | Fix rounds: W3-D12 terminal order, W3-D13 line guard, and the vacuous test | Both guards answer 409 through `ConflictException` types, *terminal* is derived from the transition table, and the guard runs before any mutation. The vacuous test was strengthened to a changed quantity and the guard was **mutated off** to observe `expected: 100.00 but was: 99.00` before restoring it — non-vacuity demonstrated rather than asserted |
| 2026-09-25 | Verification of the guards delta | W3-D12 and W3-D13 implemented exactly as the register states them; no blocking or real-defect finding. It confirmed the `Pending` asymmetry is pre-existing on base `167a6ce`, called out the dead `orderCancelled` branch that W3-D12 had just made unreachable, and noted the fail-open cancelled-status lookup |
| 2026-09-25 | Dead-code removal | The unreachable `orderCancelled` branch and its redundant status read removed (net −4 lines) with a dominance argument rather than an assumption, no test touched and no assertion changed — 607 classes / 2111 tests green, identical counts to the pre-removal baseline. **Gap:** the pre-deletion revision was not captured anywhere — nothing staged, no stash, no backup — so the deletion is verified by the dominance argument plus this green rather than by an observed diff. Next time a writer removes code, capture the diff before it lands |
| 2026-09-25 | The environment was measured instead of assumed, and F16 came out of it | `docker ps -a` lists only `lifecontrol-dev-*`; `docker/volumes-staging` is 88 KB and empty; prod's `VOLUMES_ROOT=/var/lib/lifecontrol` does not exist. Dev: Flyway 14, one aggregate row at 11.00, one location row at 1.00, one RECEIPT, zero sales orders, **zero** `store_inventory_settings` rows. That measurement refuted this record's own risk 2 and exposed the fourth writer (`upsertStoreStock`) as the only real source of divergence — which is what turned slice 3 from a reconciliation into a reset plus a source closure |
| 2026-09-25 | Slice 3 implemented (S3-T1…S3-T6) | One delegated `gentle-ai-worker`. Half 1: `V15__inventory_balance_reset.sql`, a plain `TRUNCATE` of the three balance tables with F17's measured precondition in its header and the operator-gate query; the one breaking assertion (`GoodsReceiptIntegrationTest`'s Flyway head) moved 14 → 15, corroborated by `pending().isEmpty()`. Half 2: `InventoryService.applyStockAdjustment` (pure insertion, 103 lines, class javadoc untouched) and `upsertStoreStock` reduced to validation, authorisation, prices and delegation — no stock arithmetic left in the product service. RED was real and reproduced F16: `expected: 0 but was: 11.00`. Gate 609 classes / 2122 tests |
| 2026-09-25 | Fix round: F18, the negative location | The writer reported it as a risk on its own work rather than shipping it. Decrease now allocated through the existing allocation helper, one movement per location drawn from, increase still on the sales location. RED observed at both levels as `expected: 0.00 but was: -5.00`; gate 609 classes / 2127 tests. The fail-closed guard was left in place with an explicit statement that it has no reachable case |
| 2026-09-25 | Verification of slice 3 | No blocking finding and no real defect in the migration, the adjustment or the delegation. It confirmed the truncated table set, that **no foreign key in V1–V15 references those three tables** so the plain `TRUNCATE` is safe (and that the green suite proves it: PostgreSQL rejects it at plan time), that keeping the business documents is defensible because a kept order's reversal reads the now-empty ledger and no-ops, that **no other code anywhere derives a balance from the ledger**, and that `uncoveredRemainder`'s restriction to `SALE`/`SALE_REVERSAL` keeps an adjustment out of any sale reversal. It also produced F18's citation gap, the stale `InventoryMovement` javadoc, and three nits |
| 2026-09-25 | Slice 4 implemented (S4-T1…S4-T4) | One delegated `gentle-ai-worker`, 185 changed lines across four files. The interim contract in `InventoryService` became the stock contract, with the additive rationale kept and re-grounded on W3-D5 rather than deleted; `InventoryMovement`'s javadoc stopped claiming RECEIPT-only; `InterimInconsistencyTests` became `BalanceInvariantTests` with the central assertion **byte-identical** (`45.00` against `105.00`) and the seeded divergence reframed as the one operation a receipt must never perform, plus store-wide invariant assertions after a receipt, a sale and a reversal. The writer declined to add adjustment coverage because slice 3 already covers that path store-wide, and said so instead of inflating the count. RED not obtainable, and stated as such |
| 2026-09-25 | `AGENTS.md` described a pre-inventory world | The record's premise was wrong — the file contains **zero** occurrences of "stock", so there was no stale `product_variants.stock` claim to fix. The real defect was omission: the migration table ended at V3, the schema list held no inventory table, the endpoint map held no inventory endpoint, and a schema-wide claim ("all tables use UUID PKs and `enabled` for soft-delete") was false for `InventoryMovement` and `StoreInventorySettings`. All corrected, plus an `Inventory and stock contract` section, with every rewritten claim checked against the code first |
| 2026-09-25 | Two prose rounds found what the first sweep could not | `ProductVariantLocation.java:15` and `StoreInventorySettings.java:11-13` were false and un-enumerated by the plan; then the widened pattern found the same future-tense sentence duplicated in `StoreInventorySettingsService.java:34-35`. Six false-pending sentences fixed across three rounds, and the wider sweep classified every surviving hit by reading it. The pattern that missed them was the gate this slice had declared green — see F19 |
| 2026-09-25 | Verification of slice 4 (the pre-commit seal) | Gate on the final bytes: 609 classes / 2129 tests / 0 failures, XML postdating the parent's inline edit — the first green covering it. It confirmed the inline correction's claim from the code and checked F19's three examples as accurately described. **It also refuted a claim this record had just made**: one of the 47 sweep hits *is* false prose — `MovementType.java:33` says a manual decrease removes stock from the sales location, which W3-D16 made untrue — so "47 hits, all accurate" was wrong, and the class was declared closed before it was. It further flagged that the twelve-pattern set now returns zero hits (making "every surviving hit classified" vacuous) and that the header claimed independent verification before any slice-4 verification row existed. Two of those three findings were this record's own errors, not the code's |
| 2026-09-25 | Seventh false passage: `MovementType.java:33` | Found by the seal in a file slice 3 had already committed and the sweep had already blessed. All five movement-type constants were re-checked against the code; the false one was corrected and the others reported accurate. Slice 4's closure of the prose class rests on this row, not on the sweep that preceded it. **Which green covers which bytes:** the pre-commit seal's gate (XML `16:05`) covers the bytes *before* this correction; the writer's own gate (XML `16:11`, after `clean`) covers the final bytes, and the delta is a javadoc hunk whose truth the seal itself established by reading the code. Stated rather than smoothed — this commit's independent green is the seal's, and it does not literally cover the last hunk |
| 2026-09-25 | Delivery opened | Four stacked pull requests against `main`, one per slice, on branches pushed to `origin`: **#173** (`feat/w3-slice-1-movement-engine`, which carries the plan record too), **#174** (`feat/w3-slice-2-sales-path`), **#175** (`feat/w3-slice-3-balance-reset`) and **#176** (`feat/w3-slice-4-contract-flip`). The provenance branch `feat/sales-location-aware-stock` carries all five commits. **No merge has happened**, and merge order plus the retargeting of a stacked base are the maintainer's decision. Each PR states both its own diff and the prefix GitHub displays until its base merges, because the two numbers differ |
| 2026-09-25 | Work-unit commit, rebase, and the route/checks declaration | This record and the pointer in `purchase-order-goods-receipt.md` committed together as `docs(odd): plan W3 as its own record (sales-location-aware-stock)`, then rebased onto `167a6ce` (PR #172), one commit replayed with no conflicts — which supersedes the *"not done yet"* half of the row above. Anchors re-checked against the new base: PR #172 is frontend-only and touched no file this record anchors to, including `store-inventory-settings.html:68`, whose *"Ubicación de venta"* label is intact. RDD verified disabled (`gentle_review inspect` → `stop / rdd_disabled`), so tasks carry ordinary checks and no review ceremony. TDD mode, gate and per-task route declared in `## Checks and route` |
