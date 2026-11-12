# purchase-cost-into-inventory

**Status**: implemented — a receipt now states what the units cost, and that one fact is enough: the receipt line
snapshots the purchase price it was received at and the inbound movement books it, so `stock_valuation.avg_unit_cost`
—the weighted moving average the schema already derives from the movements— starts describing what the company paid
instead of what someone declared. `cost_price` stays exactly what it was, the declared catalogue price the screens
show and the purchase picker pre-fills, and the outbound path is untouched: ADR-0001 gives a sale its cost through
`stock_allocation.unit_cost` and `companies.costing_method`, and this unit does not move that. Issue #853. Branch
`feat/purchase-cost-into-inventory`, base `origin/main` @ `407fa493`. Nothing is deferred; what stays out is named in
`S9` with the reason it is a decision of its own.
**Created**: 2026-11-12 · **Risk**: item 1 applies — the unit changes the **cost stored on every future inventory
movement** and the average is derived from it, so a wrong derivation is not detectable after the fact (it is copied
into `unit_cost` on movements and into `average_cost` on the stock row). Item 3 applies too: `averageCost` is added to
a consumed response. Mitigations: the migration is additive (two nullable columns, nothing rewritten, `S8`), the
existing behaviour that must not move is pinned by tests that pass on both trees (`S3`), and the new case fails on the
previous tree (`S4`).

## Specs

Quoted verbatim from issue #853 where it states the requirement; the decisions are the operator's, taken 2026-11-12.

- **S1** — "A receipt of a line whose `unit_price` differs from the variant's current `cost_price` books a movement
  whose unit cost is stated by the chosen policy, and is **not** silently the old `cost_price`."
- **S2** — "The policy — including whether a non-deductible tax belongs in cost — is recorded in the ODD record or an
  ADR before any code, with the alternative it rejected." See `D1`.
- **S3** — "The sales-side cost behaviour this does not intend to change is pinned by a test that passes before and
  after."
- **S4** — "`./gradlew test` green on the slice, with the new case failing against today's tree."
- **S5 (`D1`) — the policy is a weighted moving average per (variant, store), and the cost is the purchase's base.**
  The warehouse's unit cost is `stock_valuation.avg_unit_cost`, which the schema already derives: `total_value` is the
  inbound value minus the value the allocations froze, and the average is that over the quantity on hand (`V60:221-230`,
  `V61`'s `replay_stock_valuation`/`refresh_stock_valuation`). **This unit's whole contribution to it is the one fact
  the derivation was missing**: the inbound movement now carries the purchase line's **base** — `unit_price`, i.e.
  `subtotal` without tax — instead of the declared `cost_price`. A creditable VAT is not a cost. **A non-deductible tax
  would be a cost, and this tree cannot tell one from another**: there is no deductibility flag on a company, a product
  or a document, so the policy states the limitation instead of pretending to solve it. Rejected: last purchase price
  (a single atypical purchase moves every margin), and a second average written from Java whose only job would be to
  disagree with the ledger's after an adjustment.
- **S6 (`D2`) — two numbers, and neither is redefined.** `cost_price` keeps its meaning, its `NUMERIC(12,2)` and its
  single manual writer; the inventory's cost is the ledger's `avg_unit_cost`, `NUMERIC(19,4)`, written by the schema and
  only ever **read** from Java. Rejected, with the measurement: repurposing `cost_price` would make the purchase
  screen's unit-price pre-fill (`detail-table.ts:447`) feed the average back into the next purchase, and widening it to
  four decimals would put a four-decimal number into `unitPrice`, which the API refuses at two — a 400 on a value the
  operator never typed.
- **S7 (`D3`) — which movement books which cost is already ADR-0001's answer, and this unit changes exactly half of
  it.** An **inbound** movement records `unit_cost`: a receipt takes the purchase line's base. That is the half
  ADR-0001 states and no code implemented. An **outbound** row carries no cost of its own: the cost applied to the sold
  units is `stock_allocation.unit_cost`, resolved by `companies.costing_method` — `FIFO` consumes the oldest inbound
  rows and `AVERAGE` freezes the average in force. Nothing in this unit touches that, and a test pins it on both trees
  (`S3`). The `cost_price` fallback survives only for a receipt that can state no price at all, after which the ledger
  still says "unrecorded" rather than presenting a zero as a cost.
- **S8 (`D6`) — the receipt line snapshots `unit_cost NUMERIC(19,4)`.** The receipt is an immutable fact
  (`received_at` comes from `@PrePersist` and the row is never updated), so the number is written once and read as
  history; it is also what makes FIFO adoptable later without re-deriving anything.
- **S9 — what stays out, and why it is a decision of its own.** No FIFO layers (needs a layers table, a consumption
  that splits one movement across layers, and a stated opening layer for the stock that already exists). No
  revaluation of the stock that is already there: the first average appears at the next receipt, and a variant that is
  never received again keeps its declared cost. No landed cost (freight, duties). No deductibility flag.
- **S10 (`D7`) — the screen shows the average, read-only.** Next to the catalogue price, labelled as derived, because
  a ledger `unit_cost` no operator can see anywhere is a number nobody can check.

## Tasks

| ID | Spec | Route | Evidence |
| --- | --- | --- | --- |
| T1 | S6, S8 | worker | `V96` adds the two nullable columns; nothing is rewritten |
| T2 | S5, S7, S8 | worker | the receipt writes the snapshot and the average, and books the inbound at the receipt's price |
| T3 | S7 | worker | outbound books `average_cost` → `cost_price` → unrecorded |
| T4 | S3, S4 | worker | the AC case fails on the previous tree; the sales cost that must not move passes on both |
| T5 | S10 | worker | the response carries `averageCost` and the screen shows it read-only |
| T6 | S1–S10 | parent | full suite, spotless, shell suites, the record's evidence |

## Log

**L1 (2026-11-12) — the request and the analysis that produced the policy.** «ok dale con lo recomendado», after the
analysis of #853. The measurements that shaped it: `cost_price` is `NUMERIC(12,2)` and its only writer is a manual
`ProductVariantService` update; every inbound movement already books `storeStock.getCostPrice()`
(`StockLedgerEngine.java:192`) and books `0` **with a reason** when it is null (`:822-823`); the receipt is immutable
and cannot be backdated; and the purchase screen pre-fills `unitPrice` from `cost_price` (`detail-table.ts:447`),
which is why the average got its own column instead of taking that one over.

**L2 (2026-11-12) — the correction, and it is mine: the tree already had the average, and my design was duplicating it.**
The writer stopped mid-unit with an `interaction_required` instead of building what I prescribed, and it was right.
`s6`/`s7` told it to add `product_variant_store_stock.average_cost` written from Java and to make outbound movements
book a `average_cost → cost_price → unrecorded` chain. Both contradict the base commit: `stock_valuation.avg_unit_cost`
(`V60:221-230`) is the derived weighted moving average, maintained by `V61`'s `replay_stock_valuation` /
`refresh_stock_valuation`; and ADR-0001's `## Costing and valuation` says, verbatim, *"a receipt takes the purchase
line's unit cost"* — the half nobody implemented — and *"Outbound rows carry no cost of their own. The cost applied to
the units is in `stock_allocation.unit_cost`, resolved by the company's `costing_method`"*. So there is no outbound
Java chain to extend, and a Java-written average would have been a second one that can disagree with the ledger's after
any manual adjustment. My measurement found every reader of `cost_price` and missed the ledger's own valuation
projection; that is the whole of the error. The fix is smaller than the plan: the receipt snapshot, the inbound
movement carrying the purchase base, and reading `avg_unit_cost` where a screen needs it. Which also means the defect
was a sentence of the ADR that no code implemented, and `L1`'s premise — that the tree had no derived cost — was false.

**L3 (2026-11-12) — the two housekeeping decisions taken with `B`.** The migration is renamed
`V96__goods_receipt_items_unit_cost.sql`, because it adds the receipt snapshot and no average, and a filename that
lies is half of what this unit fixes. And `applyReceipt`'s seven-argument overload is **deleted** rather than
documented: an overload that books the declared `cost_price` when a caller says nothing is precisely the footgun
issue #853 exists to remove, so every caller now states whether it has a price. The `cost_price` fallback survives only
where a receipt genuinely cannot state one, and the ledger's "unrecorded" reason still closes the chain.

**L4 (2026-11-12) — what actually landed, measured.** The receipt now writes `goods_receipt_items.unit_cost`
(`V96`, `NUMERIC(19,4)`, `updatable = false`) with the line's **base**, passes the same number to
`StockLedgerEngine.applyReceipt` — which resolves purchase price → declared `cost_price` → `0` with the unrecorded
reason — and the seven-argument overload is gone, so every caller states whether it has a price (`L3`). On the read
side there is a read-only `StockValuation` entity (`@Immutable`, `@IdClass`, no setter, no builder, no save), a
repository with one finder, a fourth independent `LEFT JOIN` in both store-scoped projections, and `averageCost`
appended beside the untouched `costPrice` in the three variant responses; `GoodsReceiptLineResponse` exposes
`unitCost`. The outbound path is untouched, and a case pins both `costing_method` policies' allocation costs.

**RED, observed on the untouched tree:** `expected: 12.50 but was: 10.0000` on a line priced 12.50 above a declared
cost of 10.00, and `BadSqlGrammarException ... SELECT unit_cost FROM goods_receipt_items` for the snapshot. **GREEN:**
`./gradlew test spotlessCheck` — **1119 classes / 4800 tests / 0 failures / 0 errors / 0 skipped** in 12m49s, spotless
clean; the Angular side ran `104` focused tests, `npm run lint` clean and `npm run build` complete; and the repo's own
`odd-status-headers` (78 assertions) and `adr-migration-citations` suites pass.

**One defect the worker found and I fixed rather than shipped:** the derived average was rendered through `MoneyPipe`
at four decimals beside a declared cost rendered at two, so two adjacent money columns read at different grains.
Both now render through `CurrencyPipe` at two decimals — the four-decimal value stays in the response, and the display
grain is one. The panel shows it read-only with the copy naming the receipts, the list adds the column **only** in the
store-scoped view, and `null` renders as `—` in both: never `0`, which would read as a cost of nothing, and never
`costPrice`, which would hide the missing derivation behind a number nobody derived.

**Named limits, not silently absent.** The stock-search page still shows only the declared cost: it is a finder, not
a valuation surface, and giving it a derived column is a one-line follow-up if an operator asks for it. `averageCost`
is `null`, not `0`, for a variant the ledger never moved in that store. And under the default `FIFO` the on-hand
average moves when units are sold — that is `stock_valuation`'s definition (value over quantity), not a defect of
this unit, and `costing_method = 'AVERAGE'` is the company data that makes the average the costing policy.
