# ODD feature: ledger-per-write-identity

**Repository**: LifeControl — `life-control-api` (migrations, `inventory`, `salesorder`, `goodsreceipt`),
`odd/tasks/`
**Branch**: `feat/ledger-per-write-identity` ·
**Worktree**: `~/workspace/LifeControl-worktrees/feat-ledger-per-write-identity`
**Status**: implemented — T1–T5 are the branch's content and the two suites are green on them; S8 names what
this record deliberately does not touch, and `## Review workload` states how it has to be reviewed and in
which slices it is delivered. The migration is **`V73`**: as `V72` it collided with the document-identity
self-check that reached `main` first (see `## Log`).
**Created**: 2026-11-03
**Risk**: items **1**, **3** and **6** — item 1 because `V73` adds a column to two tables that already hold
rows and moves the unique index that decides whether a stock fact can be written at all; item 3 because the
ledger's `source_type`/`source_id` vocabulary gains a value and the mirror stops being the only source, so a
reader written against the old vocabulary reads a different ledger; item 6 because no existing test refuses a
wrong `write_seq` — the case that pins it is T5's, and until it runs the defect is silent. Item 2 does not
apply (no credential, no guard, no scope) and item 4 does not apply (the bump is a row lock, not a new
ordering).

## Objective

Implement the per-write identity the maintainer decided for ADR-0001's ledger, so `stock_movement.source_id`
can carry the document line. The blocker it removes is the one recorded in `odd/tasks/inventory-ledger.md`:
today `source_type = 'INVENTORY_MOVEMENT'` and `source_id` is the legacy `inventory_movements` row, so the
ledger's identity dies with the table T5d has to drop.

## Specs

S1. `docs/adr/0001-inventory-ledger-single-source.md` is the authority: decision 1 ("One fact, one row"), I5
    (`:184`, "A source document line books one movement per direction") and the `## Schema` block. The shape
    below is the maintainer's decision of 2026-11-03, recorded verbatim in `## Log` and in
    `odd/tasks/inventory-ledger.md`.

S2. **The key.** `uq_movement_source` becomes
    `(source_type, source_id, movement_type, store_location_id, write_seq)` over the same partial predicate
    (`WHERE reverses_movement_id IS NULL`). For a sale: `source_type = 'SALES_ORDER_ITEM'`,
    `source_id = sales_order_items.id`, `write_seq = the line's value at that write`. Two legal writes of one
    line therefore hold distinct keys, and a replayed write does not.

S3. **The sequence is a column, and it is bumped by a locked update.** `sales_order_items.write_seq BIGINT NOT
    NULL DEFAULT 0`, bumped with
    `UPDATE sales_order_items SET write_seq = write_seq + 1 WHERE id = ? RETURNING write_seq` — never a JPA
    read-modify-write, because two concurrent edits of one line would both read the same value and book two
    rows under one key, turning a legal concurrent edit into a constraint violation or a silent duplicate. The
    column is **not mapped on the entity**: Hibernate's flush must not be able to write back a stale `0` over
    the value SQL just produced.

S4. **`stock_movement.write_seq` is `BIGINT NOT NULL DEFAULT 0`.** `NOT NULL` for the reason `V63`'s own
    self-check states: a `NULL` is distinct from every other `NULL` in a unique index, so a nullable column
    would make the key refuse nothing. Existing rows take `0` and stay exactly as unique as they are today,
    because their `source_id` already is unique per row.

S5. **Every deduction path of a sales order line books the line's current sequence.** Measured call sites of
    `deduct(...)` in `SalesOrderService`: `:237` (create), `:574` (add item), `:965`, `:970`, `:975`, `:978`
    (`collectLineStockOperations`). The slice enumerates them and states, per site, whether it bumps or reuses.
    A path that books nothing must not bump: a gap in the sequence is allowed, a movement with no number is
    not.

S6. **The retry rule keeps holding, and this record's branch adds the case that proves it.** Three identical
    `PUT /api/sales-orders/{id}/items/{itemId}` leave exactly one `SALE` row in `stock_movement` and one in
    `inventory_movements` (`SalesOrderIntegrationTest` §5.4, "a retried quantity update books no movement").
    What carries that no-op is the delta rule (`SalesOrderService:988-996`), which reads document state — the
    same source the key now reads. The sequence is not what refuses a replay: a replay books nothing, so it
    bumps nothing, and the key has no row to refuse. A replay is therefore refused by the delta rule alone,
    which is exactly how the tree already behaves for every other writer.

S7. **The migration ends with a self-check that compares, not searches**, the convention `V63:131-185` sets.
    The index must be unique, on `stock_movement`, over **exactly** the five columns in order, with its
    predicate intact, and `stock_movement.write_seq` must be `NOT NULL` — asserted against `pg_index.indkey`
    resolved through `pg_attribute`, not by finding the column names inside a longer definition string. A
    six-column superset, a reordered list, the same index on another table, a dropped or inverted predicate
    and a nullable column are all refused, each naming what it found. A migration that reports success while
    having decided nothing is the failure this block exists to refuse.

S8. **Out of scope, named.** The receipt identity (`GoodsReceiptService:269` hands the writer the document id
    once per line — shape 3 of `StockLedgerWriter`'s javadoc), the reversal path's own flip
    (`bookSaleReversal` keeps the mirror until the drops), the drops themselves and the deletion of
    `InventoryService` are T5d's remaining slices in `odd/tasks/inventory-ledger.md`. This record builds the
    per-write identity for the sale line only, because that is the one the decision was taken about.

## Tasks

| ID | S# | Work | Route |
| --- | --- | --- | --- |
| T1 | S2, S4, S7 | `V73` — `sales_order_items.write_seq`, `stock_movement.write_seq`, `uq_movement_source` re-created with the fifth column, and the self-check block | **done** — `ac4077ee` |
| T2 | S2, S4 | `StockLedgerWriter`: `Booking` carries `writeSeq`, `INSERT_MOVEMENT` states it, `SINGLE_WRITE` names the "books once" case, and the class javadoc's identity section is rewritten to what is now true | **done** — `2a13d9bc` |
| T3 | S2 | `InventoryService`: `SaleChange` carries the sequence, `applySaleDeduction` books the line's own reference, and the reversal's read side reads the ledger by that source with the mirror as a fallback | **done** — `2a13d9bc` |
| T4 | S3, S5 | `SalesOrderService`: the locked bump, threaded into all six deduction sites — `:242` create, `:575` add item and `:982`/`:987`/`:990`/`:993` in `collectLineStockOperations` — none of the no-op or reversal-only paths bumping | **done** — `2a13d9bc` |
| T5 | S6, S7 | Tests: the collision cases re-measured under the new identity, the receipt case stating it stays on the mirror, two-edits and concurrent-edit cases, the mirror-identity reversal and its refusal, the duplicate on a mirrored source, and the retry case unchanged; then the two probes the independent verification named — the retry's counter and the key's catalog shape | **done** — `8dca20a2`, `f5bb549b` |

## Review workload

**≈1620 changed lines (additions + deletions, `git diff --numstat` per slice)** against the ~400 review budget, delivered as a
chain of **three** slices to the default branch, in this order. Each slice compiles, runs green on its own,
and its diff against `main` is exactly its own work once its predecessors have merged:

| PR | Branch | Content | Lines (add+del) |
| --- | --- | --- | --- |
| 1 | `feat/ledger-write-seq` | `V73` alone — the schema and the key it moves | 245 |
| 2 | `feat/ledger-sale-books-line` | `StockLedgerWriter`, `SalesOrderItemRepository`, `InventoryService`, `SalesOrderService`, both suites, and the two probes the independent verification named | 1109 |

The last row is approximate on purpose: its number was measured before the paragraphs that state it, which are
itself part of the slice, so it drifts by a handful of lines. The first two rows are exact.
| 3 | `docs/ledger-record` | this record and `odd/tasks/inventory-ledger.md` | ≈266 |

**Why slice 2 is over the budget and does not split further, after one honest pass.** It was tried as the
record's earlier three-way boundary (writer + repository, then `InventoryService`, then `SalesOrderService`)
and each intermediate tree is red, not merely thin: `Booking` gains a **required** component with no default
and no overload, so the writer and both of its callers move in one commit; and `V73`'s fifth key column means
the moment `InventoryService` books the line's own reference without `SalesOrderService` bumping the counter,
raising a line's quantity collides on `uq_movement_source` and the pre-existing quantity cases fail. The same
commit must also carry both suites, because the cases that pinned the old mirror behaviour are the ones the
new key changes — leaving them for a later slice would land a PR whose own tests are red. Splitting the two
suites from each other is the only cut left and it saves nothing: they assert the two ends of one key.

Roughly a third of the authored lines are the migration and the javadoc prose this repository's convention
demands (`V63`, the template, is 185 lines for two statements).

## Log

**2026-11-03 — L1, the user's request, verbatim**: «ok dame las recomendaciones de las decisiones», then
«la a», then «armala». The first asked for the recommendation on the four open decisions, the second chose
shape (a) of the ledger's per-write identity, and the third authorized building it.

**2026-11-03 — what was measured before this record was written.** `sales_order_items` has no per-write
identity (`V1:496-509` declares no `version`, no later migration adds one) and its only moving column is
`updated_at`, maintained by `Auditable`'s application-side `@PreUpdate LocalDateTime.now()` — a timestamp,
not an identity, and keying on a clock is refused. The retry rule was measured, not argued: the §5.4 case
runs three identical requests and sees one `SALE` row in each ledger, with stock at 95/95.

**2026-11-03 — T1–T5 land as `c698e4c9` and `143a5f35`, and two of my specs were wrong.** Recorded because a
spec that the code had to contradict is worth more than one that was vague enough to survive:

- **S3 said the bump is a locked `RETURNING` statement; it did not say it cannot be `@Modifying`.** With that
  annotation Hibernate runs the statement through `executeUpdate()`, which PostgreSQL answers with SQLState
  `0100E` ("A result was returned when none was expected"); 43 of 55 tests failed that way before the
  annotation came off. The property S3 wants is intact — one statement, the row lock, the new number back,
  never a JPA read-modify-write — and `SalesOrderItemRepository` states why the marker is absent.
- **S8 said the reversal *books* on the mirror and implied the read was untouched.** Removing the mirror from
  the sale's write path also removed what the reversal *reads*: it resolved a legacy row to its ledger twin
  through the mirror key, so every reversal after a V73 sale would have thrown. `InventoryService` now reads
  the ledger by the movement's own source and falls back to the mirror for rows booked before V73. That read
  is the one part of this slice S8 did not name, and it is the part with a declared cost: **a line whose
  stock spans the deploy — a pre-V73 row and a post-V73 row on one reference — cannot be reversed** until
  T5d, and refuses loudly instead of half-reversing. Accepted: the tree is pre-production, the ADRs treat
  its data as disposable (`V14`, `V40` are TRUNCATE), and refusing is the honest form of "the ledger cannot
  express this yet".
- **S2/S5 said the sale books the literal `'SALES_ORDER_ITEM'`.** It books the caller's `referenceType`,
  which for every production caller and every fixture is that same string. The indirection keeps
  `inventory_movements.reference_type` and `stock_movement.source_type` one vocabulary, which is what the
  reversal's read depends on.

**2026-11-03 — independent verification: no blockers, six claims met, five advisories.** The read-only pass
re-ran both suites itself (`cleanTest`, 55 tests, 0 failures), probed the bump with two PostgreSQL sessions
(1 then 2, not 1 then 1), ran the migration's self-check against six wrong database shapes, and diffed the
new reversal fallback against the pre-change body (byte-identical). Three advisories were closed in a second
pass with the RED each case was made to produce: the self-check compared by containment and accepted a
six-column superset (now an exact catalog comparison, refused through a real Flyway run); three durable
probes were missing (mirror-identity reversal, the mixed-identity refusal, and the duplicate on a mirrored
source — now cases); and the concurrency case passed for a read-modify-write (now a bump-then-hold with a
`pg_stat_activity` barrier, which answers `[2, 2]` for the naive implementation and `[2, 3]` for the locked
one). The two that stay declared rather than fixed are the mixed-identity reversal above and the fact that
**no test forces the bump's flush order**: `nextWriteSeq` runs after the line is persisted, and a future path
that bumped before persisting would get an empty-result failure instead of a wrong number.

**Final evidence on the branch**: `./gradlew test --tests '...InventoryLedgerBookingIntegrationTest'
--tests '...SalesOrderIntegrationTest'` → **58 tests, 0 failures, 0 errors, 0 skipped** over 23 result
files, `spotlessCheck` and `spotbugsMain` clean, and V73 applied from V1 on the Testcontainers PostgreSQL in
the same run — which is the self-check block having executed.

**2026-11-03 — delivery: the branch was rebased, its migration renamed to `V73`, and the chain re-cut.** Three
things had to be measured against `main` before anything could be delivered, and one of them was a defect the
branch would have caused on merge:

- **`V72` was taken.** The branch created `V72__stock_ledger_per_write_identity.sql`, and by the time it was
  ready `main` already held `V72__document_identity_self_check.sql` (the fix for #756, PR #762). Flyway reads
  two files claiming one version as a checksum/versioning failure, so the branch's file is **`V73`** from here
  on: the filename, every self-check message inside it, the javadoc references in `StockLedgerWriter`,
  `InventoryService`, `SalesOrderService`, `SalesOrderItemRepository` and both suites, and the two ODD records.
  The prose that narrates how this slice was reached keeps saying `V72` where it is describing the past; the
  artifacts that fly say `V73`.
- **The branch was behind `main`.** It is now rebased onto `aa127a3e`, so the slices' diffs are against a base
  that already contains the document-identity self-check.
- **The history was re-cut into the three slices `## Review workload` now states**, from one main-code commit
  plus one tests commit into: schema (`ac4077ee`), write path plus both suites (`2a13d9bc`, `8dca20a2`), and
  this record. The re-cut is not cosmetic: it is what makes slice 1 landable on its own, and it is the one cut
  that leaves every slice's own tree green.

**A second worktree from an earlier attempt at this same record was removed, not merged.** A
`docs/ledger-per-write-identity` worktree sat on `627f0295` with two uncommitted edits — the retry case in
`SalesOrderIntegrationTest` and an earlier draft of this record's header. Both were checked against this
branch before removal: the test case exists here verbatim and the prose is the draft that became the header
above, so nothing that worktree held is absent from this one.

**2026-11-03 — independent verification of the delivered tree: six claims met, no blockers, three advisories.**
The read-only pass re-ran the two suites itself in a scratch copy (58 tests, 0 failures, V73 applied from V1),
extracted V73's self-check block and ran it in `postgres:16-alpine` against **13 wrong shapes** plus the
correct one — all 13 raised, each with its own message, so the block is not vacuous — and re-measured the
concurrency case against a naive read-modify-write, which answered `[2, 2]` where the locked bump answers
`[2, 3]`. The three advisories, and what was done with each:

- **the retry case pinned "no movement" but not "no bump"** — closed by `f5bb549b`: the case now asserts the
  line's counter is still `1` after three identical sends, so a future bump on the zero-delta branch can no
  longer pass every assertion while disagreeing with the movements;
- **this record's arithmetic had drifted** (it said 1307 authored lines and 203 for the last slice) — replaced
  by the measured `additions + deletions` per slice and the measured total, with the branch of the last slice
  named as it is (`docs/ledger-record`);
- **suites outside the two authorized ones were not executed by the verifier** — they were executed by this
  branch's own `./gradlew cleanTest test` (1065 result files, 4502 tests, 0 failures), which is the run this
  record already cites as its evidence.

**2026-11-06 — S5's call sites are now one, and T4's six become one; the rule itself is unchanged.** ADR-0002
decision 7 (record `odd/tasks/money-first-class.md`, task **T6b**) moved the sales deduction from the line
writes to the charge. The five sites S5 enumerates — `createSalesOrder`, `addSalesOrderItem` and the four in
`collectLineStockOperations` — no longer exist: `collectLineStockOperations` is deleted, and
`chargeSalesOrder` is the single path that bumps a line's sequence, once per booked line, before the engine
books anything. The rule S5 states is what makes that safe rather than what it breaks: «a path that books
nothing must not bump», and the create/edit/delete/status paths now book nothing, so they no longer bump.
The item diff has no delta left to book either: one line is one full-quantity deduction, taken at the charge.
`updateSalesOrderStatus` books no reversal any more, and `deleteSalesOrder` reverses only a `COMPLETED`
document. T4's six sites are therefore one, and S5's enumeration is historical.

The verifier's other durable probe — a catalog assertion on the key's five columns — also landed in
`f5bb549b`, in the schema suite rather than in the migration, because a file that checks itself proves the
check ran and not that an independent reader agrees with it.
