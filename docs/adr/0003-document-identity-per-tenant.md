# ADR 0003 — Document numbers are allocated per tenant

- **Status**: accepted
- **Date**: 2026-10-29
- **Accepted**: 2026-11-02, after the four corrections recorded under *Corrections applied before acceptance*
- **Decision id**: D3 of `odd/tasks/architecture-redesign-adrs.md`
- **Depends on**: ADR-0005 (the tenant machinery `document_sequence` is registered in, the policy it
  carries, and the startup assertion that refuses a `company_id` table nobody armed — so arming the three
  tables below is a step of this ADR, not a follow-up). ADR-0002 consumes the `company_id` this ADR puts
  on `sales_orders`
- **Replaces**: the global `UNIQUE` on every document number and the `max + 1` generator

## Context

A document number is an identity, and today it is a globally unique string produced by reading the
highest number in the whole table and adding one. Four facts carry the decision:

| # | Evidence | What it means |
| --- | --- | --- |
| 1 | `V1__baseline_schema.sql:470` declares `order_number VARCHAR(30) NOT NULL UNIQUE`; `:319` declares the same on `purchase_orders`; `V12__goods_receipts.sql:21` declares `receipt_number VARCHAR(30) NOT NULL UNIQUE` | Three number spaces, all global, all unique across every company in the database |
| 2 | `SalesOrderService.java:922-941` builds `SO-yyyyMMdd-` (`:925`) and adds one to the highest number carrying that prefix (`:927`, `SalesOrderRepository.java:35`, increment at `:934`). `PurchaseOrderService.java:816-835` repeats it with `PO-` (`:819`, query at `:821`, `PurchaseOrderRepository.java:115`) | A read followed by a write, with no company in the query, and the counter recoverable only by parsing the string (`:932-934`) |
| 3 | `GoodsReceiptService.java:528` builds `GR-` + the purchase order's number + `-NN`, with the ordinal from `countByPurchaseOrderId` (`:231`) under a `PESSIMISTIC_WRITE` lock on the order (`:167-180`) | A **fourth** fact the first draft of this ADR missed: one numbered document derives its number from another document's number, and its counter is the parent's lock, not a global one |
| 4 | `sales_orders` carries `company_store_id` and no `company_id` (`V1:468-482`); the owner is reachable only through `company_stores.company_id`, which V49 made `NOT NULL` (`V49:71`) | Nothing to scope the read by, even if the query wanted to |

Two defects follow, and the second is the one retrying cannot fix:

| Defect | Consequence |
| --- | --- |
| Two cashiers compute the same next value | Both read the same max, both add one, and the second insert violates the constraint inside the sale's transaction, so the whole ticket — items, stock, payment — rolls back and the operator rings it up again; the retry races again |
| Tenants set each other's numbers | A tenant writing 40.000 documents in a day pushes every other tenant's next number past five digits, so a tenant reads another's daily volume off its own ticket |
| A receipt's number is its parent's number, plus an ordinal | Renumbering a purchase order without renumbering its receipts leaves the tree holding receipt numbers that name an order number which no longer exists, and future receipts of that order embed the new one |

## Decision

**A period-numbered document takes its number from a row per `(company, document type, period)`,
allocated in one statement; a derived document inherits its parent's frozen number; and the identity
that the database enforces is the parts, never the rendered string.**

1. **The counter is a row.** `document_sequence (company_id, doc_type, period, next_value)` with
   `PRIMARY KEY (company_id, doc_type, period)`; `next_value` is the next value to hand out, so the
   first allocation of a period inserts `2` and hands out `1`.
2. **Allocation is one statement, never a read followed by a write.**

   ```sql
   INSERT INTO document_sequence (company_id, doc_type, period, next_value)
   VALUES (:companyId, :docType, :period, 2)
   ON CONFLICT (company_id, doc_type, period)
   DO UPDATE SET next_value = document_sequence.next_value + 1
   RETURNING next_value - 1 AS allocated;
   ```

   Insert and increment are atomic, so no two callers can read the same value. The race stops existing
   instead of being retried.
3. **The lock is held until the caller commits, and that is a cost, not a footnote.** The upsert takes
   a row lock on the sequence row and PostgreSQL holds it to the end of the transaction, so two sales of
   one tenant, one type and one period **serialize for the whole charge** — stock, payments, transition
   included — not just for the allocation. The bound is one tenant, one type, one period, one
   transaction at a time; different tenants, types and periods never touch the same row. Two rules keep
   it acceptable: **allocate as the last write before commit**, so the window is the tail of the
   transaction and not its head; and a transaction that allocates more than one document type
   allocates them in a fixed order (`doc_type` ascending, then `period`) so two such transactions
   cannot deadlock on two sequence rows.
4. **The period comes from the document's own date, frozen before allocation.** `period` is derived
   from the document's own date (`sales_orders.order_date`, `purchase_orders.order_date`), which is set
   once before the allocator runs, and the same value is used for the render. It is never read from the
   wall clock at allocation time, because a sale created at 23:59 on the last day of a month would
   otherwise land in the next period depending on when its transaction happens to reach the allocator.
5. **The document carries its identity in parts, and the parts are what is unique.**
   `doc_type`, `seq_period`, `seq_value` and a rendered `doc_number`; the database key is
   `(company_id, doc_type, seq_period, seq_value)`. `doc_number` is presentation — rendered once,
   stored, never re-rendered on read, so a reprint shows the number that was printed — and it carries a
   second unique index as a net, not as the guarantee.
6. **Two shapes, one mechanism.** A **period-numbered** document (`SALES_ORDER`, `PURCHASE_ORDER`)
   allocates from `document_sequence` and stores the three identity parts. A **derived** document
   (`GOODS_RECEIPT`) inherits its parent's **frozen** `doc_number` and keeps its ordinal in the
   rendered string (`GR-{parent.doc_number}-{NN}`), allocated under the parent's row lock — the
   mechanism that already exists at `GoodsReceiptService.java:167-180`. It stores `doc_type` and
   `doc_number`, and no period or ordinal column, because its ordinal is scoped to its parent and
   forcing it into a period sequence would destroy the "receipt N of order M" reading that makes the
   number useful. A receipt is re-rendered **only** together with its parent, in the same migration,
   and never afterwards.
7. **`doc_type` is a closed vocabulary, and this ADR fixes its spelling first.**
   `ck_*_doc_type` pins each document table to its own constant, and `document_sequence` admits only the
   period-numbered types, so a derived document cannot open a counter. A free-text `doc_type` is a
   silent second counter: one trailing space is a new number space and nothing fails. The strings are
   the ones ADR-0004's `status_transition.document_type` (`0004:83`) **must reuse** when it lands, so
   `'SALES_ORDER'` means one thing in both tables — but that is a spelling contract, not a schema
   dependency: `status_transition` does not exist yet and ADR-0004 is still `proposed`, so this ADR does
   not wait for it and accepting it does not accept 0004. Writing the vocabulary down here is what makes
   a later mismatch ADR-0004's defect instead of a second number space in this one.
8. **A gapless sequence is a different contract and is out of scope here.** It requires serializing
   allocation against the document's commit, forbidding reservation ahead of time, and a rule for what
   a void does to the counter; fiscal numbering under a tax authority is where that belongs.

## Schema

```sql
-- V64. V52 and V53 are the tenant machinery this table is registered in, and V54–V63 are spent (ADR-0006's
-- catalog identity, ADR-0001's stock ledger), so the number this ADR first carried is dead.
CREATE TABLE document_sequence (
    company_id  UUID        NOT NULL,
    doc_type    VARCHAR(24) NOT NULL,
    period      VARCHAR(8)  NOT NULL,      -- 'YYYYMM' by default, configurable per doc_type
    next_value  BIGINT      NOT NULL,      -- the next value to hand out; the first handed out is 1
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_document_sequence PRIMARY KEY (company_id, doc_type, period),
    CONSTRAINT fk_document_sequence_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT ck_document_sequence_next CHECK (next_value >= 2),
    -- only the period-numbered types allocate from a counter: a derived document (a receipt) must not
    -- be able to open one, which is what decision 6 forbids
    CONSTRAINT ck_document_sequence_type CHECK (doc_type IN ('SALES_ORDER', 'PURCHASE_ORDER')),
    -- alphanumeric and short, so a doc_type may periodize differently: 'YYYYMM' is this ADR's
    -- default, not a constraint the schema can assume
    CONSTRAINT ck_document_sequence_period CHECK (period ~ '^[A-Z0-9]{1,8}$'));

-- ─── the period-numbered documents ────────────────────────────────────────────────────────────────
-- `company_id` is added here and is NOT NULL in the target shape; the migration adds it nullable and
-- sets it after the backfill, which is why the unique indexes below are created last. A unique index
-- over a nullable column does not enforce N1 for the rows where it is NULL. ADR-0002 adds the store
-- pin (`fk_sales_orders_store_same_company`, `V51:86-90`'s pattern) and the `UNIQUE (company_id, id)`
-- key its payments reference.
ALTER TABLE sales_orders
    ADD COLUMN company_id UUID NOT NULL,
    ADD COLUMN doc_type   VARCHAR(24) NOT NULL DEFAULT 'SALES_ORDER',
    ADD COLUMN seq_period VARCHAR(8),       -- the same type as document_sequence.period
    ADD COLUMN seq_value  BIGINT,
    ADD COLUMN doc_number VARCHAR(40),      -- rendered once, never recomputed
    -- one value per table, not a set: a sales order cannot be filed as a receipt, and a CHECK that
    -- admits three values would let it
    ADD CONSTRAINT ck_sales_orders_doc_type CHECK (doc_type = 'SALES_ORDER'),
    ADD CONSTRAINT ck_sales_orders_doc_identity
        CHECK ((doc_number IS NULL) = (seq_period IS NULL)
               AND (seq_period IS NULL) = (seq_value IS NULL));

ALTER TABLE purchase_orders
    ADD COLUMN doc_type   VARCHAR(24) NOT NULL DEFAULT 'PURCHASE_ORDER',
    ADD COLUMN seq_period VARCHAR(8),
    ADD COLUMN seq_value  BIGINT,
    ADD COLUMN doc_number VARCHAR(40),
    ADD CONSTRAINT ck_purchase_orders_doc_type CHECK (doc_type = 'PURCHASE_ORDER'),
    ADD CONSTRAINT ck_purchase_orders_doc_identity
        CHECK ((doc_number IS NULL) = (seq_period IS NULL)
               AND (seq_period IS NULL) = (seq_value IS NULL)),
    -- the key the receipt's tenant pin needs; `purchase_orders.company_id` already exists (V43:113)
    ADD CONSTRAINT uq_purchase_orders_company_id UNIQUE (company_id, id);

-- the identity is the parts; the rendered string is presentation with a second net over it
CREATE UNIQUE INDEX uq_sales_orders_doc_identity
    ON sales_orders (company_id, doc_type, seq_period, seq_value) WHERE seq_value IS NOT NULL;
CREATE UNIQUE INDEX uq_sales_orders_doc_number
    ON sales_orders (company_id, doc_type, doc_number) WHERE doc_number IS NOT NULL;
CREATE UNIQUE INDEX uq_purchase_orders_doc_identity
    ON purchase_orders (company_id, doc_type, seq_period, seq_value) WHERE seq_value IS NOT NULL;
CREATE UNIQUE INDEX uq_purchase_orders_doc_number
    ON purchase_orders (company_id, doc_type, doc_number) WHERE doc_number IS NOT NULL;

-- ─── the derived document ────────────────────────────────────────────────────────────────────────
-- A receipt has no period and no ordinal column: its ordinal lives in the rendered string, scoped to
-- its parent, and the parent's frozen `doc_number` is what it names.
ALTER TABLE goods_receipts
    ADD COLUMN company_id UUID NOT NULL,
    ADD COLUMN doc_type   VARCHAR(24) NOT NULL DEFAULT 'GOODS_RECEIPT',
    ADD COLUMN doc_number VARCHAR(40),
    ADD CONSTRAINT ck_goods_receipts_doc_type CHECK (doc_type = 'GOODS_RECEIPT'),
    -- the receipt cannot name another tenant's order; with `company_id` NOT NULL the default MATCH
    -- SIMPLE is enough, because there is no NULL on the child side that would skip the comparison
    ADD CONSTRAINT fk_goods_receipts_order_same_company
        FOREIGN KEY (company_id, purchase_order_id)
        REFERENCES purchase_orders (company_id, id);

CREATE UNIQUE INDEX uq_goods_receipts_doc_number
    ON goods_receipts (company_id, doc_type, doc_number) WHERE doc_number IS NOT NULL;

-- ─── the three tables join ADR-0005's list, and that is not optional ─────────────────────────────
-- `company_id` on these three tables is what makes them tenant tables, and ADR-0005's machinery decides
-- what a tenant table with no policy means: `unprotected_tenant_tables()` (V52) returns every table that
-- carries a `company_id` column and has no `tenant_table` row, and `TenantIsolationStartupCheck` refuses
-- to boot on a non-empty answer. It keys on the **column's existence**, not on its `NOT NULL`, so there
-- is no version of this plan where the columns land now and the policies land later — the arming is part
-- of the same delivery (step 7) and the ADR claims it here or not at all.
SELECT apply_tenant_isolation('document_sequence');
SELECT apply_tenant_isolation('sales_orders');
SELECT apply_tenant_isolation('goods_receipts');

-- ─── the global keys go, last ────────────────────────────────────────────────────────────────────
-- `order_number` and `receipt_number` are dropped in a later release, once every reader renders from
-- `doc_number`: until then they record how a number was allocated under the old scheme.
```

## Invariants (each one testable)

| # | Invariant | Enforced by |
| --- | --- | --- |
| N1 | Two documents of one tenant, one type and one period never share a sequence value | `uq_*_doc_identity` over the parts; a duplicate insert fails. The rendered string is not what is enforced |
| N2 | Allocation never reads before it writes | the upsert is the only allocator; a `max` query in the numbering path is a review finding, and the allocator's test drives it from several threads |
| N3 | One tenant's volume cannot move another tenant's counter | the key is company-scoped; a test allocates in two companies and asserts both start at `1` |
| N4 | A new period starts at `1` with no reset job | the first allocation of a new `period` is an `INSERT` that conflicts with nothing |
| N5 | `doc_number` is written once and agrees with its parts; a consumed value is never reassigned | no `UPDATE` path exists; one query asserts every `doc_number` ends in its zero-padded `seq_value`, and the renderer is the only producer |
| N6 | A **rolled-back** allocation reuses its value, and a **committed** allocation whose document never lands is a gap that is never reassigned | two tests, one per direction: a transaction that allocates and rolls back leaves `next_value` untouched and the next allocation returns the same value; a transaction that allocates and commits without writing its document leaves a gap the next allocation skips |
| N7 | A receipt's number names its parent's frozen `doc_number`, and is re-rendered only with it | `GR-{parent.doc_number}-{NN}` is built from the stored parent column, never from a fresh read of the catalog; the migration re-renders both in one step |
| N8 | `doc_type` is one of the closed set, and each table holds only its own | `ck_*_doc_type` pins one value per document table and `ck_document_sequence_type` admits only the period-numbered types; a typo is refused instead of opening a parallel counter |
| N9 | The counter row belongs to its tenant, and so does every numbered document | `company_id NOT NULL` plus `fk_document_sequence_company`, `fk_goods_receipts_order_same_company`, and the three `apply_tenant_isolation` calls of the `## Schema` block. `V52`'s `unprotected_tenant_tables()` is the query, `TenantIsolationStartupCheck` the refusal that runs at every boot, and it keys on the column's existence — which is why the arming cannot be deferred. `NOT NULL` is what makes the tenant part of the key: a unique index over a nullable `company_id` does not enforce N1 for those rows |

## Migration from the current tree

The next free schema version is **V64** (`V52__tenant_isolation_machinery.sql` and
`V53__tenant_isolation_policies.sql` already exist, and V54–V63 are spent by ADR-0006's catalog identity
and ADR-0001's stock ledger), plus **V65**, a Java migration, for the render. The two files ship
together; step 2 says why neither is deployable alone.

0. **Open the `NO FORCE` window on `purchase_orders`, before the first read, and check that it took.**
   `V53:126` arms `purchase_orders`, and `V52`'s `apply_tenant_isolation` sets
   `FORCE ROW LEVEL SECURITY`, which filters the table's **owner** — the role Flyway runs as.
   `V54:26` records the consequence in the tree's own words: on such a table a statement matches
   **zero rows and reports success**. Without this window the report in step 1 reads nothing and is
   indistinguishable from a clean tree, the backfill in step 3 numbers nothing, and step 4's
   `SET NOT NULL` succeeds over a column of nulls. `NO FORCE` is the only door a `NOSUPERUSER` owner
   has, and `V57:85` is the same window carrying the same two self-checks — one that the window took,
   one that the statement inside it reached a row — because a window that failed to open fails
   silently by construction.

   ```sql
   ALTER TABLE purchase_orders NO FORCE ROW LEVEL SECURITY;
   ```

   The window does not span the two files: `V64` opens and closes its own, and `V65` opens and closes
   another. `V55:100` exists because a file that leaves the window open on a table weakens that table
   for everything that runs after it, and a crash between two migrations would leave it open with
   nobody to close it.

1. **Pre-flight, before any write.** Report the blast radius, so the renumbering is a decision and not
   a surprise:

   ```sql
   -- documents whose printed number does not follow the shape the old generator promised
   SELECT 'SALES_ORDER' AS doc_type, order_number AS printed,
          'unparseable' AS reason
   FROM sales_orders
   WHERE order_number !~ '^SO-[0-9]{8}-[0-9]+$'
   UNION ALL
   SELECT 'PURCHASE_ORDER', order_number, 'unparseable'
   FROM purchase_orders
   WHERE order_number !~ '^PO-[0-9]{8}-[0-9]+$'
   UNION ALL
   -- receipts whose ordinal is not the numeric suffix the re-render assumes
   SELECT 'GOODS_RECEIPT', receipt_number, 'non-numeric ordinal'
   FROM goods_receipts
   WHERE receipt_number !~ '-[0-9]+$';
   ```

   A row here is renumbered by the migration, not refused: the report exists so the count per tenant is
   known before the deploy. It runs **after** step 0 on purpose: over a forced table, "no unparseable
   number" and "not one row visible" are the same empty output, and a report that cannot tell them apart
   is worse than no report.
2. **Add the columns nullable** and create `document_sequence`; nothing reads them yet. The `## Schema`
   block above is the target shape, so its `NOT NULL`s land in steps 4 and 6, not here. Derive each
   document's company: `sales_orders.company_id` from `company_stores.company_id`,
   `goods_receipts.company_id` from its `purchase_orders.company_id`, and `purchase_orders` keeps the
   one V43/V51 already own.

   **This step is what ties the two files together.** `unprotected_tenant_tables()` (`V52`) returns
   every table that carries a `company_id` column and no policy, and `TenantIsolationStartupCheck`
   refuses to boot on a non-empty answer — keyed on the **column**, not on its `NOT NULL`. The moment
   this step runs, `sales_orders` and `goods_receipts` are unprotected tables, and the only reason the
   application still boots is that the arming in step 7 lands in the same Flyway run. Shipping `V64`
   without `V65` is a deployment that refuses to start: the correct failure, named here so it is not
   discovered in staging.
3. **Backfill the parts per tenant and period with a window function**, ordered by `created_at` so the
   rebuilt sequence follows the order documents were written, with the printed number as a tiebreak:

   ```sql
   WITH numbered AS (
       SELECT so.id, cs.company_id, to_char(so.order_date, 'YYYYMM') AS period,
              row_number() OVER (PARTITION BY cs.company_id, to_char(so.order_date, 'YYYYMM')
                                 ORDER BY so.created_at, so.order_number) AS seq_value
       FROM sales_orders so JOIN company_stores cs ON cs.id = so.company_store_id)
   UPDATE sales_orders so
   SET company_id = n.company_id, doc_type = 'SALES_ORDER', seq_period = n.period,
       seq_value = n.seq_value
   FROM numbered n
   WHERE so.id = n.id;
   ```

   It runs inside step 0's window. On `purchase_orders` this statement is invisible to its own owner
   otherwise — the `UPDATE` would report `UPDATE 0` and the migration would continue happily.

4. **Seed the counters and set the parts `NOT NULL`.** `max(seq_value) + 1` per
   `(company, type, period)`, run once, single-threaded, inside the migration. Then set `company_id` and
   `seq_value` to `NOT NULL`, and add the two `uq_*_doc_identity` indexes: an index over a nullable
   `company_id` admits duplicate parts, so creating it before the `NOT NULL` would leave a window in
   which N1 is not enforced.

   **`doc_number` is not in this step, and that is an ordering fact, not an omission.** The identity
   `CHECK` the `## Schema` block declares makes the three columns all-set or all-null together, and the
   renderer runs in `V65` from a Java migration; setting `doc_number NOT NULL` here would either fail at
   once or, worse, succeed over the empty string of an `UPDATE` that reached no row. Its `NOT NULL`, its
   `CHECK` and its unique index land in step 6, after the value exists. This is the same order defect
   ADR-0001's acceptance found in its own plan — write, read, stop writing, drop — caught here while it
   is still prose.
5. **Close step 0's window and re-check it, before `V64` ends.** `ALTER TABLE purchase_orders FORCE ROW
   LEVEL SECURITY`, then assert `relforcerowsecurity` is true again and that no row of the backfilled
   tables is left without its parts. The assertion is not ceremony: a file that backfilled nothing and a
   file that backfilled everything print the same green, and only "did a row arrive" separates them.
6. **Render `doc_number` with the application renderer, from a Java migration (V65).** A Flyway SQL
   migration cannot call the renderer, and this repository has only SQL migrations today, so this step
   introduces the Java-migration mechanism — that is its cost, and it is cheaper than two renderers
   that disagree. The render is `{prefix}-{period}-{seq_value zero-padded to 5, widened when it needs
   more}`, so `SO-202610-00001`; the receipts are re-rendered in the **same** migration, from the new
   parent `doc_number` and their ordinal, in the order: sales, purchase orders, receipts. Then set
   `doc_number NOT NULL`, add `ck_*_doc_identity` and the `uq_*_doc_number` indexes, and assert that no
   row holds a partial identity.

   It opens its own `NO FORCE` window on `purchase_orders`, because step 5 re-forced it and the render
   has to reach its rows like any other statement; it checks the window took, renders, closes it and
   re-checks it — the same two checks as step 0, for the same reason.
7. **Arm the three tables, last.** This is the step the ADR's first draft claimed without writing it
   (see the `## Schema` block),

   ```sql
   SELECT apply_tenant_isolation('document_sequence');
   SELECT apply_tenant_isolation('sales_orders');
   SELECT apply_tenant_isolation('goods_receipts');
   ```

   and it is what makes step 2's temporary state legal: after these three calls
   `unprotected_tenant_tables()` — the query `TenantIsolationStartupCheck` runs at every boot — returns
   no row, so the assertion N9 leans on stops being a claim and becomes a check that somebody else's
   deployment runs. It comes after the render on purpose: arming before a cross-tenant `UPDATE` is the
   zero-row trap with extra steps.
8. **Move the writers.** `generateOrderNumber()` leaves `SalesOrderService` (`:922-941`) and
   `PurchaseOrderService` (`:816-835`); one allocator owns the statement of decision 2, and both
   `findTopByOrderNumberStartingWithOrderByOrderNumberDesc` declarations
   (`SalesOrderRepository.java:35`, `PurchaseOrderRepository.java:115`) go with it.
   `GoodsReceiptService.generateReceiptNumber` (`:526-534`) keeps its per-order count and stops reading
   `purchaseOrder.getOrderNumber()`, reading the frozen `doc_number` instead.
9. **Drop `order_number` and `receipt_number` last**, once every reader renders from `doc_number`. Until
   then they record how a number was allocated under the old scheme: `SO-20261029-00007` was the seventh
   document of that day across every tenant; after the backfill it is the seventh of that tenant in that
   month. Step 9 is a second release.

## Rejected alternatives

| Alternative | Why rejected |
| --- | --- |
| One PostgreSQL `SEQUENCE` per tenant and document type | It needs dynamic DDL per tenant — a company created tomorrow is a schema change at runtime — and the tenant list stops being data. It also cannot scope by period without one sequence per month per tenant per type |
| Keep `max + 1` and take a table lock around it | Correct, and rejected because it serializes the hottest write path of the product: every sale in every tenant queues behind one lock. The upsert serializes per tenant, type and period — the narrowest key that still prevents the race — and decision 3 states the window it opens. Scoping the query by company alone does not help either: two cashiers of one tenant still read the same max |
| Reference documents by UUID only, and print the UUID | A ticket, a purchase order sent to a supplier and a call to support need a number a human can read, dictate and search. A UUID satisfies uniqueness and nothing else |
| Retry the insert on a unique violation | It hides the race instead of removing it: the retry recomputes the same value, so it can loop under load, and it makes a correct refusal (a real duplicate) indistinguishable from a retry that fails to converge |
| Give `goods_receipts` its own period sequence (`GR-202610-00042`) | Uniform, and it deletes the only thing that makes a receipt number useful: which order it closes. A receipt's ordinal is per parent by nature, and a global period counter would renumber the second reception of an order as number 43 of the month instead of number 2 of that order |
| Enforce uniqueness on `doc_number` only | The rendered string is presentation. If the renderer ever drops the period, or the format changes, the identity collides or splits without the database noticing — which is the defect this ADR exists to remove, moved one layer up |
| Render in SQL and keep one function as the shared implementation | It is one implementation and it was a close second. Refused because formatting in the database has no test surface in this repository's suites, and the Java renderer is the one the API already needs; a parity test between a SQL function and a Java renderer is the fallback if the maintainer prefers not to introduce Java migrations, and it is not what this ADR picks |

## Consequences

- **Good**: a race stops being a lost sale at the till. No retry, no constraint violation, no queue
  shared by tenants, and one tenant's volume is invisible to another in the number and in its width.
- **Good**: period, ordinal and type are columns, and they are what the database enforces. "How many
  sales did this store write in October" filters on `seq_period` instead of parsing a string, and a
  format change stops rewriting history.
- **Good**: the third numbered document stops being a blind spot. A receipt number keeps naming the
  order it closes, and the renumbering reaches it in the same step instead of leaving it pointing at a
  number that no longer exists.
- **Cost**: every path that creates a numbered document changes; two repositories lose a derived query
  their tests use, `SalesOrderService` loses its private `generateOrderNumber` — also the only place
  logging the unparseable-number warning (`:936`) — and `GoodsReceiptService` changes what it reads for
  the number. Allocation is a round trip inside the caller's transaction, and old numbers are
  renumbered: step 9 is two releases.
- **Cost**: `sales_orders` and `goods_receipts` become tenant tables for the first time, and that changes
  **who can read them**, not only how numbers are assigned. Neither has a `{companyId}` path segment
  (`/api/sales-orders`, `/api/goods-receipts`), so `TenantScopeFilter` states their tenant from a single
  `company_id` claim: a caller with one claim sees its own documents, a caller with none stops seeing
  every tenant's, and a caller with more than one is refused on those paths — a refusal the filter
  already applies today, which arming makes consistent with the rows instead of merely with the path.
- **Cost**: a new mechanism, the Java migration, exists for one step. It is named here so the
  maintainer decides it once, not in the middle of the slice.
- **Cost**: decision 3's lock window. A tenant's sales of one month serialize for the tail of each
  charge transaction; the mitigation is allocation last, and the bound is measured in the slice that
  implements it, not assumed.

## Corrections applied before acceptance

Read against the tree for the acceptance decision, and four defects came out. The decision is unchanged
and is still unimplemented: `SalesOrderService:922` and `PurchaseOrderService:816` still build `max + 1`
over a global counter, and `GoodsReceiptService:527` still derives a receipt's number from its parent's.
What was wrong was the plan, and each defect is a kind this set has already paid for once.

1. **The migration numbers were dead.** The `## Schema` block carried `-- V53. The next free version` and
   the render step named `V54`; both are spent (`V53__tenant_isolation_policies.sql`,
   `V54__product_variant_mode.sql`) and the head is `V63`. The plan now takes `V64` and `V65`. This is the
   same collision ADR-0001's acceptance found in `V52`–`V55`.
2. **The backfill ran blind under `FORCE ROW LEVEL SECURITY`.** This ADR was written before ADR-0005's
   machinery existed, and named RLS once, in its `Depends on` line. `V53:126` arms `purchase_orders` and
   `V52` sets `FORCE`, which filters the owner — the role Flyway runs as — so the pre-flight report would
   have reported zero defects and the backfill would have numbered nothing, both green, for the reason
   `V54:26` already records. The plan now opens a `NO FORCE` window as step 0 with `V57:85`'s two
   self-checks, closes it with a re-check as step 5, and gives `V65` its own window, because one that
   spans two files is one a crash can leave open (`V55:100`).
3. **N9 claimed enforcement the schema did not deliver.** It stated that the counter "is registered in
   ADR-0005's policy list, whose startup assertion fails if a `company_id` table is not listed", and the
   `## Schema` block never called `apply_tenant_isolation` for any of the three tables. It matters more
   than it reads: `unprotected_tenant_tables()` keys on the **column's existence**, not on its `NOT NULL`,
   so adding a nullable `company_id` in step 2 and arming later is a deployment that refuses to boot
   (`TenantIsolationStartupCheck` throws). The three calls are now in `## Schema`, N9 cites them by name,
   and step 7 runs them last.
4. **Two statements named facts that did not hold.** Decision 4 gave the period's source as
   "(`order_date`, `order_date`)"; decision 7 pinned `doc_type` to `status_transition.document_type` of
   ADR-0004 — a table that does not exist, in an ADR that is still `proposed`. The vocabulary is now a
   spelling contract this ADR fixes first: accepting it neither waits for 0004 nor accepts it.

The reordering is itself a correction and was caught by re-reading the steps against the `## Schema`
block: putting `doc_number NOT NULL` before the render is unexecutable, because `ck_*_doc_identity`
requires the three columns together or none. The order is now **write → read → stop writing → drop**,
the same order ADR-0001's acceptance had to fix.

One consequence the first draft did not name is now recorded as a cost: `sales_orders` and
`goods_receipts` become tenant tables for the first time, so arming them changes **who can read them**,
not only how numbers are assigned.

The new SQL was not executed: no runner is authorized, so it is read-verified, not observed. The claims
it rests on are read from migrations that already do the same thing (`V54`, `V55`, `V57`) and from the
code that runs the assertion at every boot (`TenantIsolationStartupCheck`).
