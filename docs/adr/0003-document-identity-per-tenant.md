# ADR 0003 — Document numbers are allocated per tenant

- **Status**: proposed
- **Date**: 2026-10-29
- **Decision id**: D3 of `odd/tasks/architecture-redesign-adrs.md`
- **Depends on**: ADR-0005 (the tenant machinery `document_sequence` is registered in, and the policy it
  carries). ADR-0002 consumes the `company_id` this ADR puts on `sales_orders`
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
   from the document's date (`order_date`, `order_date`), which is set once before the allocator runs;
   the same value is used for the render. It is never read from the wall clock at allocation time,
   because a sale created at 23:59 on the last day of a month would otherwise land in the next period
   depending on when its transaction happens to reach the allocator.
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
7. **`doc_type` is a closed vocabulary, shared with ADR-0004, and each table holds exactly its own
   value.** The strings are spelled the same as `status_transition.document_type` (`0004:83`), so
   `'SALES_ORDER'` means one thing in both tables; `ck_*_doc_type` pins each document table to its own
   constant, and `document_sequence` admits only the period-numbered types, so a derived document
   cannot open a counter. A free-text `doc_type` is a silent second counter: one trailing space is a
   new number space and nothing fails.
8. **A gapless sequence is a different contract and is out of scope here.** It requires serializing
   allocation against the document's commit, forbidding reservation ahead of time, and a rule for what
   a void does to the counter; fiscal numbering under a tax authority is where that belongs.

## Schema

```sql
-- V53. The next free version: V52 is the tenant machinery this table is registered in.
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
| N9 | The counter row belongs to its tenant, and so does every numbered document | `company_id NOT NULL` plus `fk_document_sequence_company`, `fk_goods_receipts_order_same_company`; the counter table is registered in ADR-0005's policy list, whose startup assertion fails if a `company_id` table is not listed. `NOT NULL` is what makes the tenant part of the key: a unique index over a nullable `company_id` does not enforce N1 for those rows |

## Migration from the current tree

The next free schema version is **V53** (`V52__tenant_isolation_machinery.sql` already exists), plus
**V54**, a Java migration, for the render.

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
   known before the deploy.
2. **Add the columns nullable** and create `document_sequence`; nothing reads them yet. The `## Schema`
   block above is the target shape, so its `NOT NULL`s land in step 5, not here. Derive each
   document's company: `sales_orders.company_id` from `company_stores.company_id`,
   `goods_receipts.company_id` from its `purchase_orders.company_id`, and `purchase_orders` keeps the
   one V43/V51 already own.
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

4. **Render `doc_number` with the application renderer, from a Java migration (V54).** A Flyway SQL
   migration cannot call the renderer, and this repository has only SQL migrations today, so this step
   introduces the Java-migration mechanism — that is its cost, and it is cheaper than two renderers
   that disagree. The render is `{prefix}-{period}-{seq_value zero-padded to 5, widened when it needs
   more}`, so `SO-202610-00001`; the receipts are re-rendered in the **same** migration, from the new
   parent `doc_number` and their ordinal, in the order: sales, purchase orders, receipts. Then assert
   that no row holds a partial identity.
5. **Seed the counters** with `max(seq_value) + 1` per `(company, type, period)`: the last `max + 1` in
   the tree, run once, single-threaded, inside the migration. Then set `company_id` on the three
   tables, plus `seq_value` and `doc_number`, to `NOT NULL`, and only then add the unique indexes of the
   schema above: an index over a nullable `company_id` admits duplicate parts, so creating it before
   the `NOT NULL` would leave a window in which N1 is not enforced.
6. **Move the writers.** `generateOrderNumber()` leaves `SalesOrderService` (`:922-941`) and
   `PurchaseOrderService` (`:816-835`); one allocator owns the statement of decision 2, and both
   `findTopByOrderNumberStartingWithOrderByOrderNumberDesc` declarations
   (`SalesOrderRepository.java:35`, `PurchaseOrderRepository.java:115`) go with it.
   `GoodsReceiptService.generateReceiptNumber` (`:526-534`) keeps its per-order count and stops reading
   `purchaseOrder.getOrderNumber()`, reading the frozen `doc_number` instead.
7. **Drop `order_number` and `receipt_number` last**, once every reader renders from `doc_number`. Until
   then they record how a number was allocated under the old scheme: `SO-20261029-00007` was the seventh
   document of that day across every tenant; after the backfill it is the seventh of that tenant in that
   month. Step 7 is a second release.

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
  renumbered: step 7 is two releases.
- **Cost**: a new mechanism, the Java migration, exists for one step. It is named here so the
  maintainer decides it once, not in the middle of the slice.
- **Cost**: decision 3's lock window. A tenant's sales of one month serialize for the tail of each
  charge transaction; the mitigation is allocation last, and the bound is measured in the slice that
  implements it, not assumed.
