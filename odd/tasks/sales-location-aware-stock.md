# ODD feature: sales-location-aware-stock

**Status**: slice 1 of four implemented — the movement engine is written, independently verified and
committed on `feat/sales-location-aware-stock`, which sits on `main` @ `167a6ce`. **Slices 2, 3 and 4
remain open**, and slice 2 must land before any release: until it does, the repository carries the new
public API with no production caller. This header makes no claim about push or PR state; see the task log.
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
  column would make every existing reader wrong. `ADJUSTMENT` is **not** added: nothing writes one.
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

## Scope: four slices

Sliced for the review budget, in dependency order. Slices 1 and 3 are independent of each other; slice 2
consumes slice 1; slice 4 depends on all three.

| Slice | Content | Depends on |
| --- | --- | --- |
| 1 — the movement engine | `MovementType` gains the sale types; `InventoryService` gains the signed movement API (deduct with priority spillover, reverse by ledger); unit tests for allocation, spillover, fail-closed, lock order and ledger shape | — |
| 2 — the sales path | Route all 8 entry points through the engine; replace the net-delta fold with per-item deltas; reversal by ledger reference; fix the item-cancellation gap (W3-D4); integration tests that finally assert on location rows | 1 |
| 3 — the reconciliation | `V15__reconcile_location_balances.sql` implementing W3-D2 and W3-D8 | — |
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

- [ ] **S2-T1** Replace the net-delta fold in `applyStockChanges` (step 4) with a per-item delta list, so
      movements carry line-level provenance. Locking keeps the sorted distinct-variant order
      (`:864-873`).
- [ ] **S2-T2** Route the deduct paths through the engine: `createSalesOrder`, `updateSalesOrder`,
      `addSalesOrderItem`, `updateSalesOrderItem`.
- [ ] **S2-T3** Route the restore paths through the engine's reversal: `deleteSalesOrderItem`,
      `updateSalesOrderStatus` → `Cancelled`, `deleteSalesOrder`, and the parts of `updateSalesOrder` that
      delete or shrink a line.
- [ ] **S2-T4** Fix W3-D4: `updateSalesOrderItemStatus` → `Cancelled` restores the line and writes the
      reversal, matching item deletion. Decide and test the double-restore guard (a cancelled line that is
      later deleted must not restore twice — the ledger remainder in W3-D6 is the mechanism).
- [ ] **S2-T5** `SalesOrderService` no longer injects `ProductVariantStoreStockRepository` directly if the
      engine fully owns the mutation; the store-reassignment rejection (`:224-231`) is re-verified because
      reversals depend on it.
- [ ] **S2-T6** Integration tests (Testcontainers, `AbstractPostgresIntegrationTest`) that assert on
      `product_variant_locations` and on `inventory_movements` — closing the location-blindness of the
      whole suite. Extend the existing nested classes 5.1–5.12 rather than adding a parallel suite.
- [ ] **S2-T7** Regression check on the 73 unit tests in `SalesOrderServiceTest`, especially
      `StockDeltaAndRestorationTests` (`:2391`) and `InsufficientStockExceptionTests` (`:2725`).

### Slice 3 — the reconciliation (data)

- [ ] **S3-T1** `V15__reconcile_location_balances.sql`: zero every `product_variant_locations` row and
      credit each `(variant, store)` aggregate to the store's `sales_location_id` (W3-D2), with the
      no-settings-row tie-break (W3-D8). New migration only — never an edit to an applied one
      (`life-control-api/AGENTS.md:859`).
- [ ] **S3-T2** State explicitly what the migration does to an empty database and to a database whose
      history V14 already truncated, so the dev environment's outcome is predictable.
- [ ] **S3-T3** Verify the post-migration invariant with a query: no `(variant, store)` where
      `store_stock.stock != SUM(locations.stock)`.

### Slice 4 — the contract flip

- [ ] **S4-T1** Rewrite `InventoryService.java:33-46` and the `:138-146` comment block: sales is
      location-aware, the aggregate equals the location sum by construction, and the recompute question is
      closed (W3-D5) rather than deferred.
- [ ] **S4-T2** Replace `InterimInconsistencyTests` (`InventoryIntegrationTest:410-442`) with an invariant
      test: after every sales and receipt operation, `aggregate = SUM(locations)`. The old test's premise
      becomes unreachable through the application.
- [ ] **S4-T3** Update `life-control-api/AGENTS.md` where it describes the sales/inventory contract and the
      inventory schema, and check the invariants it states about stock.
- [ ] **S4-T4** Sweep for remaining interim prose: `grep -rn "W3\|until the sales rework" src/main/java`
      must return only intentional historical references.

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
| S4-T1…T4 | declared when reached; prose-only edits are single-file and may run inline | — |

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

## Risks

1. **The invariant has to hold from the first deploy.** If slice 2 ships before slice 3, stores that sold
   before the deploy still overcount; if slice 3 ships before slice 1, the reconciliation immediately
   re-creates the exact inconsistency it is repairing (sales deduces from the aggregate only). Slices 1
   and 2 must land in the same release as slice 3, or slice 3 must be re-run afterwards.
2. **A wrong reconciliation is a data loss with no undo.** The reset discards the per-location
   distribution and, unlike V14, runs against databases that are not dev. `V14` was declared destructive
   and accepted; this one is not destructive but it is *irreversible in meaning* — the pre-migration
   distribution cannot be reconstructed. Take a backup, and gate on the invariant query (S3-T3).
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
- **`ADJUSTMENT` movements** (W3-D7) — no writer, so no type.
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
- **F8 — the reconciliation has an undefined case.** A receipt can target a store with no
  `store_inventory_settings` row via the operator override (`GoodsReceiptService:354-364`), so location
  balances can exist without a `sales_location_id` to reconcile onto. W3-D8 supplies the tie-break.
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
| 2026-09-25 | Work-unit commit, rebase, and the route/checks declaration | This record and the pointer in `purchase-order-goods-receipt.md` committed together as `docs(odd): plan W3 as its own record (sales-location-aware-stock)`, then rebased onto `167a6ce` (PR #172), one commit replayed with no conflicts — which supersedes the *"not done yet"* half of the row above. Anchors re-checked against the new base: PR #172 is frontend-only and touched no file this record anchors to, including `store-inventory-settings.html:68`, whose *"Ubicación de venta"* label is intact. RDD verified disabled (`gentle_review inspect` → `stop / rdd_disabled`), so tasks carry ordinary checks and no review ceremony. TDD mode, gate and per-task route declared in `## Checks and route` |
