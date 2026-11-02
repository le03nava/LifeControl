# ODD feature: document-identity

**Repository**: LifeControl — `life-control-api/` (migrations, the three document modules), `docs/adr/`, `odd/tasks/`
**Branch**: `feat/document-identity` · **Worktree**: `~/workspace/LifeControl-worktrees/feat-document-identity`
**Status**: implemented — this unit delivers ADR-0003's steps 0–8: the per-tenant counter, the parts-shaped
identity and the three writers moved onto it. ADR-0003 step 9 (dropping `order_number` and
`receipt_number`) is a second release and is **not** part of this record.
**Created**: 2026-11-03
**Risk**: items 1, 3 and 6 — item 1 because the migration renumbers every stored document and drops the
global `UNIQUE` on all three number columns; item 3 because the rendered number format other readers and
screens depend on changes, and because arming `sales_orders` and `goods_receipts` changes **who can read
them**; item 6 because decision 3's ordering rule (allocate as the last write before commit) is a
transaction-shape cost that no assertion in the suite can observe.

## Objective

Implement accepted ADR-0003 (`docs/adr/0003-document-identity-per-tenant.md`): a period-numbered document
takes its number from a row per `(company, document type, period)` allocated in one statement, a derived
document inherits its parent's frozen number, and the identity the database enforces is the parts, never
the rendered string.

## Specs

S1. `docs/adr/0003-document-identity-per-tenant.md`, **accepted 2026-11-02**, is the specification of this
    unit. Its decisions 1–8, the `## Schema` block, invariants N1–N9 and `## Migration from the current
    tree` steps 0–8 are authoritative, in that order.

S2. **Decision 2, verbatim** — the only allocator:

    ```sql
    INSERT INTO document_sequence (company_id, doc_type, period, next_value)
    VALUES (:companyId, :docType, :period, 2)
    ON CONFLICT (company_id, doc_type, period)
    DO UPDATE SET next_value = document_sequence.next_value + 1
    RETURNING next_value - 1 AS allocated;
    ```

S3. **Decision 4, verbatim**: «**The period comes from the document's own date, frozen before
    allocation.** `period` is derived from the document's own date (`sales_orders.order_date`,
    `purchase_orders.order_date`), which is set once before the allocator runs, and the same value is used
    for the render. It is never read from the wall clock at allocation time».

S4. **Decision 5, verbatim**: «The document carries its identity in parts, and the parts are what is
    unique.» … «`doc_number` is presentation — rendered once, stored, never re-rendered on read».

S5. **Decision 6, verbatim**: «A **derived** document (`GOODS_RECEIPT`) inherits its parent's **frozen**
    `doc_number` and keeps its ordinal in the rendered string (`GR-{parent.doc_number}-{NN}`), allocated
    under the parent's row lock».

S6. **Decision 7, verbatim**: «`doc_type` is a closed vocabulary … The strings are the ones ADR-0004's
    `status_transition.document_type` (`0004:83`) **must reuse** when it lands». The spellings are
    `'SALES_ORDER'`, `'PURCHASE_ORDER'`, `'GOODS_RECEIPT'`.

S7. **Migration step 2, verbatim**: «Shipping `V64` without `V65` is a deployment that refuses to start:
    the correct failure, named here so it is not discovered in staging.» The two migrations, the render and
    the three writers are therefore **one deployable unit**, and the chain's second slice carries them all.

S8. **ADR-0003's `Replaces` line, verbatim**: «the global `UNIQUE` on every document number and the
    `max + 1` generator». The global unique constraints on `sales_orders.order_number`,
    `purchase_orders.order_number` and `goods_receipts.receipt_number` are dropped in `V64`: a
    tenant-scoped number is not globally unique, so leaving them would refuse the second tenant's
    `SO-202610-00001` — the migration would fail on the change it exists to make.

S9. **Migration step 0, verbatim**: «`NO FORCE` is the only door a `NOSUPERUSER` owner has, and `V57:85` is
    the same window carrying the same two self-checks — one that the window took, one that the statement
    inside it reached a row — because a window that failed to open fails silently by construction.»

S10. **N6, verbatim**: «A **rolled-back** allocation reuses its value, and a **committed** allocation whose
     document never lands is a gap that is never reassigned».

## Tasks

| ID | S# | Work | Depends on | Route |
| --- | --- | --- | --- | --- |
| T1 | S1, S2, S4, S5, S6 | The numbering contract in Java, additive and with no behavior change: `DocumentType` (the three closed-vocabulary spellings plus their prefixes and which of them is period-numbered), `DocumentNumberRenderer` (`{prefix}-{period}-{seq zero-padded to 5, widened when it needs more}` and `GR-{parent.doc_number}-{NN}`) and `DocumentSequenceAllocator` (decision 2's single statement, refusing a derived type). Unit-tested; the allocator's database proof belongs to T2 because until `V64` exists there is no table to allocate from | — | inline; first slice of the chain |
| T2 | S1, S2, S3, S8, S9 | `V64`: the `NO FORCE` window with `V57:85`'s two self-checks, the pre-flight report, `document_sequence`, the nullable columns, the drop of the three global `UNIQUE` constraints, the per-tenant window-function backfill, the counter seed, `NOT NULL` on `company_id`/`seq_period`/`seq_value`, the identity indexes, then the window closed and re-checked | T1 | inline; second slice |
| T3 | S1, S4, S5, S7, S9 | `V65`, the tree's first Java migration: re-render `doc_number` for sales orders, purchase orders and then receipts in one order, set `doc_number NOT NULL`, add `ck_*_doc_identity`, `uq_*_doc_number`, its own `NO FORCE` window, and arm the three tables last with `apply_tenant_isolation` | T2 | inline; second slice |
| T4 | S1, S3, S4, S5, S7 | Move the writers: `SalesOrderService` and `PurchaseOrderService` allocate through the allocator and store the parts, `GoodsReceiptService` renders from the parent's frozen `doc_number`, the two `findTopByOrderNumberStartingWith…` declarations go, and the entities carry the new columns | T3 | inline; second slice |
| T5 | S1, S8, S10 | The PostgreSQL suites that pin N1, N3, N4, N5, N6, N7 and N9 — and the premise that the existing suites still pass with the global `UNIQUE` gone | T4 | inline; second slice |

## Log

**L1** — «continua con el adr 0003»

**2026-11-03 — the one fact of ADR-0003 this unit could not implement literally, and why it is recorded
rather than quietly fixed.** Decision 4 names the period's source as «`sales_orders.order_date`,
`purchase_orders.order_date`». The second column **does not exist**: `V1__baseline_schema.sql:313-325`
declares `purchase_orders` with no `order_date`, and no later migration adds one — the table carries only
`created_at`/`updated_at` among its dates. So `V64`'s backfill and the purchase-order writer derive the
period from `created_at` (the document's own frozen timestamp, read inside the `NO FORCE` window), and the
writer captures one `LocalDate` and uses it for both the allocation and the render. The *property* decision
4 exists for is preserved — the period comes from the document and never from the allocation's clock — and
no new column was invented to make the ADR's sentence true. This is the fifth defect of the same family as
the four the acceptance caught (D1 a dead migration number, D2 the blind backfill, D3 unregistered
policies, D4 two facts that did not hold): a statement about a column nobody re-read against the tree.

**2026-11-03 — decision 3's "allocate last" cannot be honoured by this tree, and the cost is named
instead of hidden.** Decision 3 asks the caller to allocate as its last write before commit, so the row
lock is held for the tail of the transaction. A sales order cannot: the order row carries `NOT NULL`
`order_number`/`doc_number` at insert, and the lines and the stock movement need that row's id, so the
allocation happens immediately **before** the insert and the lock is held for the whole charge — which is
the serialization decision 3 itself calls "a cost, not a footnote". The ordering the code uses is the latest
one the schema allows, and the slice's comment says so at the call site. Options that would restore the
mitigation (pre-generating the document id, or allocating in a separate committed transaction) were refused:
the first reorders JPA's flush around a foreign key, and the second breaks invariant N6, which requires a
rolled-back allocation to hand its value out again.

**2026-11-03 — the delivery is two slices and the second one is atomic.** `V64` adds `company_id` to
`sales_orders` and `goods_receipts` (a boot refusal without `V65`'s arming) and sets the parts `NOT NULL`
(which refuses every insert the un-moved writers make), so the migrations and the three writers ship in one
pull request; the numbering contract that precedes them (T1: `DocumentType`, `DocumentNumberRenderer`,
`DocumentSequenceAllocator`, 420 authored lines, no behavior change) is the chain's first slice. Recorded
before the first migration was written, and confirmed by the record's own T-row dependencies.

**2026-11-03 — T2 and T3, and the third recurrence of the same family, found by running the SQL instead of
reading it.** `V64__document_identity_per_tenant.sql` and `V65__Document_identity_render.java` are written
and both are proven by `DocumentIdentityMigrationIntegrationTest` (a scratch database held at `V63`, a
history written in the old shape, then the chain to head). Two defects came out of that run, and neither was
visible by re-reading the file:

1. **The pre-flight report selected a column that did not exist yet.** Step 1 grouped by
   `sales_orders.company_id` and `goods_receipts.company_id`, and step 2 is what adds them; Flyway aborted
   with `column so.company_id does not exist` before reaching a single document. The report now reads the
   tenant through the store and through the parent order — the same walk step 3's backfill uses — which is
   the only reading available before the columns exist.
2. **The backfill was blind again, one join over.** The ADR's step 0 opens a `NO FORCE` window on
   `purchase_orders` and this file opened exactly that. But the sales backfill derives each order's tenant by
   **joining `company_stores`**, which `V53` armed too — so the `CTE` read zero rows, the `UPDATE` matched
   none, and every sales order kept a NULL `company_id` while the migration reported success. The failure
   surfaced one step later as `null value in column "company_id" of relation "document_sequence"`, which is a
   misleading message for a policy. The window now covers **every armed table the file reads**
   (`purchase_orders` and `company_stores`), the close restores both, and the self-check names whichever is
   still forced instead of asserting one hardcoded table.

That is the third time in this ADR set that "the migration names what it enforces" was false — ADR-0003's
own N9, ADR-0004's `Depends on` line, and now this — and the first time the claim failed inside a window the
ADR itself introduced. The lesson is one line: a `NO FORCE` window is sized by the tables the migration
**reads**, not by the table it intends to rewrite.

**2026-11-03 — T4 and T5, and what the moved writers had to change in the suites.** The three writers moved
onto the allocator (delegated to a writer, reviewed here): `SalesOrderService` resolves the store once and
keeps it, so the guard and the tenant answer the same question; both order services freeze one date and
allocate immediately before the row is built; `GoodsReceiptService` renders `GR-{parent.doc_number}-{NN}`
from the parent's frozen column, keeping the per-order count under the parent's `PESSIMISTIC_WRITE` lock and
the 30-character guard that protects the narrower `receipt_number`; both `findTopByOrderNumberStartingWith…`
finders and both `max + 1` generators are gone. Seven existing assertions were rewritten and **none was
weakened**: every case that asserted a number still asserts a number, now `SO-<yyyyMM>-00001` instead of
`SO-yyyyMMdd-00001`, and `GoodsReceiptServiceTest`'s fixture deliberately keeps `orderNumber` and `docNumber`
different so the derivation can be observed reading the frozen one. `SalesOrder.receipt_number`'s mapping —
`GoodsReceipt.receipt_number`'s — lost its `unique = true` as well: `V64` drops that constraint, and two
tenants' identical rendered receipt number is legal. Entity nullability was tightened by the parent to what
`V64` actually states (`company_id`, `seq_period`, `seq_value`, `doc_number` are all `NOT NULL` after this
unit), so the mapping cannot claim a shape the schema refuses.

**2026-11-03 — T5's evidence.** `DocumentIdentityMigrationIntegrationTest` (9 cases) pins N1, N3, N4, N5,
N7 and N9 against the migrations themselves, including the two directions that a sequential test cannot
reach: two tenants holding the same rendered number, and a receipt following its parent's new number.
`DocumentSequenceAllocatorIntegrationTest` (4 cases) pins the allocation contract against the real policies:
a new period starts at 1, a committed allocation with no document leaves a gap that is never reassigned, a
rolled-back allocation hands the same value out again (`MANDATORY` propagation is what makes that true), and
**eight concurrent callers receive exactly the values 1..8** — the case that a read-then-write would fail.
Both suites run as `lifecontrol_owner` for the migrations and as `lifecontrol_app` for the allocation, which
is the split delivery runs.

**2026-11-03 — the suite's order dependence, which arming the tables turned from invisible into red.** The
first full run after T4 gave **125 failures**, and every one of them was a *cleanup* problem, not a behavior
problem: `@BeforeEach`/`@AfterEach` calls such as `deleteAll()` run **outside any tenant scope**, and on an
armed table an unscoped `DELETE` matches zero rows and reports success. The leftovers then pinned rows the
next class deletes — `store_locations` for the receipts and `shifts` for the sales orders — so the suite
became dependent on which class ran first. Isolated, the same classes were green; on `origin/main`, in a
separate worktree, the same pair was green too. Two repairs, both in the suites and both stating the tenant
instead of borrowing a wider credential: the two suites that write these documents install
`TenantScope.set(...)` for the whole test and clear it at the end, and `GoodsReceiptIntegrationTest`'s
teardown calls a new `deleteAllGoodsReceipts()` that removes **every** receipt — its scoping cases
deliberately write receipts for a second company, which a scoped delete cannot reach — through the
superuser and one `SET LOCAL session_replication_role = replica`, exactly the credential and the shape
`deleteStockLedger` already documents.

**2026-11-03 — the last five, and the two mechanisms that hid rows without hiding anything.** The remaining
failures looked like "the read is on the wrong tenant" and were not: in both cases the write and the read
named the same company.
1. **A declared finder gets no transaction.** `countByPurchaseOrderId` is a derived query, so Spring Data
   does not wrap it in one (`No need to create transaction for SimpleJpaRepository.countByPurchaseOrderId`),
   and `doBegin` is the only place the tenant is installed — no transaction, no `set_tenant_scope`, zero rows
   on an armed table. `count()` answered 1 in the same case because `SimpleJpaRepository` implements it and
   *is* transactional. The repair is a scoped read, not a wider scope.
2. **The request filter clears the scope when it finishes.** `TenantScopeFilter` clears the thread's scope in
   its `finally`, so a suite that installs one in `@BeforeEach` and then goes through `mockMvc` has none
   afterwards; the row is there (`findById` inside a scoped helper returns it with `enabled=true`), it is just
   invisible to a bare read. Three such reads in `SalesOrderIntegrationTest` and one count in the receipt
   suite now state the scope the write used.

Both mechanisms were measured, not inferred — the worker's diagnostics showed `scope=Optional.empty` and a
superuser read of the same row — and both directions are now pinned by the suites themselves, since a
mislabelled read fails loudly.

**2026-11-03 — three expectations moved with the unit, and each for a stated reason.** The head of
`db/migration` is now `65` and the helper that derives it read `*.sql` only, so it reported `64` for a
database at `65` — the tree's first Java migration is the one kind of migration that scan missed; it now
reads both halves, and `MigrationHeadTest` pins that, including that a `.class` file can be the head.
`FlywayDeliveryBaselineIntegrationTest`'s own head filter said "type is `SQL`", which excludes a Java
migration for the same reason; it now says "carries a version and is not synthetic", so the baseline row is
still excluded and no migration type is privileged. And `TenantScopeFunctionsIntegrationTest`'s frozen list
of armed tables went from twenty-two to twenty-five, with the three new rows naming their kind and key
column — the offset in `hasSize` moved with it.

**2026-11-03 — the final evidence.** Full API suite: **4396 cases, 0 failures** (`./gradlew cleanTest test`),
`spotlessCheck` green, and the ODD record audit green over 137 records. The new coverage of this unit is 13
cases: 9 for the two migrations and 4 for the allocation contract.

**2026-11-03 — the commits, and the chain the review budget forces.** The unit is delivered as **two
slices**, both pushed: `430c0d01` (T1, 420 authored lines — the numbering contract, no behavior change) on
branch `feat/document-identity-numbering`, and `d3da99a4` (T2–T5 plus the suite repairs, 2392 lines) on
`feat/document-identity`, which is based on the first. The second slice cannot be split further: `V64` adds
`company_id` to two tables (a boot refusal without `V65`'s arming) and makes the parts `NOT NULL` (which
refuses every insert the un-moved writers make), so the migrations and the writers are one deployable unit,
and the review budget loses to that constraint on purpose. The order is slice 1 then slice 2; the issue
stays open until slice 2 lands. Evidence of record: `./gradlew cleanTest test` → **4396 cases, 0 failures**;
`spotlessCheck` green; `.github/scripts/tests/odd-status-headers.test.sh` → 137 records, 0 findings.
