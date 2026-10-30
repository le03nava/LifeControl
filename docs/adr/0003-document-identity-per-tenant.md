# ADR 0003 — Document numbers are allocated per tenant

- **Status**: proposed
- **Date**: 2026-10-29
- **Decision id**: D3 of `odd/tasks/architecture-redesign-adrs.md`
- **Depends on**: —
- **Replaces**: the global `UNIQUE` on every document number and the `max + 1` generator

## Context

A document number is an identity, and today it is a globally unique string produced by reading the
highest number in the whole table and adding one. Three facts carry the decision:

| # | Evidence | What it means |
| --- | --- | --- |
| 1 | `V1__baseline_schema.sql:470` declares `order_number VARCHAR(30) NOT NULL UNIQUE`; `:319` declares the same on `purchase_orders` | One number space for every company in the database |
| 2 | `SalesOrderService.java:922-941` builds `SO-yyyyMMdd-` (`:925`) and adds one to the highest number carrying that prefix (`:927`, `SalesOrderRepository.java:35`, increment at `:934`). `PurchaseOrderService.java:816-835` repeats it with `PO-` (`:819`, query at `:821`, `PurchaseOrderRepository.java:115`) | A read followed by a write, with no company in the query, and the counter recoverable only by parsing the string (`:932-934`) |
| 3 | `sales_orders` carries `company_store_id` and no `company_id` (`V1:468-482`); the owner is reachable only through `company_stores.company_id`, which V49 made `NOT NULL` (`V49:71`) | Nothing to scope the read by, even if the query wanted to |

Two defects follow, and the second is the one retrying cannot fix:

| Defect | Consequence |
| --- | --- |
| Two cashiers compute the same next value | Both read the same max, both add one, and the second insert violates the constraint inside the sale's transaction, so the whole ticket — items, stock, payment — rolls back and the operator rings it up again; the retry races again |
| Tenants set each other's numbers | A tenant writing 40.000 documents in a day pushes every other tenant's next number past five digits, so a tenant reads another's daily volume off its own ticket |

## Decision

**A number comes from a row per `(company, document type, period)`, allocated in one statement, and
the document stores its identity in parts.**

1. **The counter is a row.** `document_sequence (company_id, doc_type, period, next_value)` with
   `PRIMARY KEY (company_id, doc_type, period)`; `next_value` is the next value to hand out.
2. **Allocation is one statement, never a read followed by a write.**

   ```sql
   INSERT INTO document_sequence (company_id, doc_type, period, next_value)
   VALUES (:companyId, :docType, :period, 2)
   ON CONFLICT (company_id, doc_type, period)
   DO UPDATE SET next_value = document_sequence.next_value + 1
   RETURNING next_value - 1 AS allocated;
   ```

   Insert and increment are atomic, so no two callers can read the same value. The lock is per
   `(company, type, period)`: cashiers of one tenant serialize on that row, different tenants never
   touch it. The race stops existing instead of being retried.
3. **Numbering is per document type and period** — `YYYYMM` by default, configurable per type. The
   period is a column of the sequence, so a new month needs no reset job: its first allocation is an
   `INSERT` that conflicts with nothing.
4. **The document carries its identity in parts.** `doc_type`, `seq_period`, `seq_value` and a
   rendered `doc_number`; uniqueness becomes `(company_id, doc_type, doc_number)` and the global
   constraint is dropped. Rendering (`SO-20261029-00001`) is presentation: derived once, stored,
   never re-rendered on read, so a reprint shows the number that was printed.
5. **Gaps are allowed and contiguousness is not promised.** The allocation runs in the document's
   own transaction and the upsert holds the sequence row until commit, so a rollback reuses the
   value. That is a side effect, not a contract: a gap appears when a number is consumed in its own
   transaction (an importer, a batch reserving ahead) or when a later step fails after commit.
6. **A gapless sequence is a different contract and is out of scope here.** It requires serializing
   allocation against the document's commit, forbidding reservation ahead of time, and a rule for
   what a void does to the counter; fiscal numbering under a tax authority is where that belongs.

## Schema

```sql
CREATE TABLE document_sequence (
    company_id  UUID        NOT NULL,
    doc_type    VARCHAR(24) NOT NULL,      -- 'SALE', 'PURCHASE_ORDER', 'PAYMENT', …
    period      VARCHAR(8)  NOT NULL,      -- 'YYYYMM' by default, configurable per doc_type
    next_value  BIGINT      NOT NULL,      -- the next value to hand out, not the last handed out
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_document_sequence PRIMARY KEY (company_id, doc_type, period),
    CONSTRAINT ck_document_sequence_next CHECK (next_value > 1),
    -- alphanumeric and short, so a doc_type may periodize differently: 'YYYYMM' is this ADR's
    -- default, not a constraint the schema can assume
    CONSTRAINT ck_document_sequence_period CHECK (period ~ '^[A-Z0-9]{1,8}$')
);

-- Also added by ADR-0002 for its payment documents; this ADR owns them and the sequence that fills
-- them. `company_id` is nullable because the backfill derives it, and becomes NOT NULL afterwards.
ALTER TABLE sales_orders
    ADD COLUMN company_id UUID,
    ADD COLUMN doc_type   VARCHAR(24) NOT NULL DEFAULT 'SALE',
    ADD COLUMN seq_period CHAR(6),
    ADD COLUMN seq_value  BIGINT,
    ADD COLUMN doc_number VARCHAR(40),      -- rendered once, never recomputed
    ADD CONSTRAINT ck_sales_orders_doc_identity
        CHECK ((doc_number IS NULL) = (seq_period IS NULL)
               AND (seq_period IS NULL) = (seq_value IS NULL));

-- the same four columns for the other numbered document; the same identity check applies
ALTER TABLE purchase_orders
    ADD COLUMN doc_type   VARCHAR(24) NOT NULL DEFAULT 'PURCHASE_ORDER',
    ADD COLUMN seq_period CHAR(6),
    ADD COLUMN seq_value  BIGINT,
    ADD COLUMN doc_number VARCHAR(40);

-- uniqueness becomes per tenant
ALTER TABLE sales_orders    DROP CONSTRAINT sales_orders_order_number_key;
ALTER TABLE purchase_orders DROP CONSTRAINT purchase_orders_order_number_key;

CREATE UNIQUE INDEX uq_sales_orders_doc_number
    ON sales_orders (company_id, doc_type, doc_number) WHERE doc_number IS NOT NULL;
CREATE UNIQUE INDEX uq_purchase_orders_doc_number
    ON purchase_orders (company_id, doc_type, doc_number) WHERE doc_number IS NOT NULL;
```

## Invariants (each one testable)

| # | Invariant | Enforced by |
| --- | --- | --- |
| N1 | Two documents of one tenant, one type and one period never share a number | `uq_*_doc_number`; a duplicate insert fails |
| N2 | Allocation never reads before it writes | the upsert is the only allocator; a `max` query in the numbering path is a review finding, and the allocator's test drives it from several threads |
| N3 | One tenant's volume cannot move another tenant's counter | the key is company-scoped; a test allocates in two companies and asserts both start at `1` |
| N4 | A new period starts at `1` with no reset job | the first allocation of a new `period` is an `INSERT` that conflicts with nothing |
| N5 | `doc_number` is written once and agrees with `seq_value`; a consumed number is never reassigned | no `UPDATE` path exists; one query asserts every `doc_number` ends in its zero-padded `seq_value`; the allocator's gap test allocates in a separate transaction, discards it, and asserts the next allocation skips that value |

## Migration from the current tree

The next free migration is `V52` at the time of writing. In order:

1. **Add the columns nullable** and create `document_sequence`; nothing reads them yet. Derive each
   document's company: `sales_orders.company_id` from `company_stores.company_id`, and
   `purchase_orders` keeps the one V43/V51 already own.
2. **Backfill per tenant and period with a window function**, ordered by `created_at` so the rebuilt
   sequence follows the order documents were written, with `order_number` as a tiebreak:

   ```sql
   WITH numbered AS (
       SELECT so.id, cs.company_id, to_char(so.order_date, 'YYYYMM') AS period,
              row_number() OVER (PARTITION BY cs.company_id, to_char(so.order_date, 'YYYYMM')
                                 ORDER BY so.created_at, so.order_number) AS seq_value
       FROM sales_orders so JOIN company_stores cs ON cs.id = so.company_store_id)
   UPDATE sales_orders so
   SET company_id = n.company_id, doc_type = 'SALE', seq_period = n.period,
       seq_value = n.seq_value
   FROM numbered n
   WHERE so.id = n.id;
   ```

3. **Render `doc_number` with the application renderer**, not in SQL, so the format has one
   implementation, then assert no row holds a partial identity. **Seed the counters** with
   `max(seq_value) + 1` per `(company, type, period)`: the last `max + 1` in the tree, run once,
   single-threaded, in the migration. Then drop the global unique constraints, add the per-tenant
   index, and set `company_id` and `seq_value` `NOT NULL`.
4. **Move the writers.** `generateOrderNumber()` leaves `SalesOrderService` (`:922-941`) and
   `PurchaseOrderService` (`:816-835`); one allocator owns the statement above, and both
   `findTopByOrderNumberStartingWithOrderByOrderNumberDesc` declarations
   (`SalesOrderRepository.java:35`, `PurchaseOrderRepository.java:115`) go with it.
5. **Drop `order_number` last**, once every reader renders from the new columns. Until then it
   records how a number was allocated under the old scheme: `SO-20261029-00007` was the seventh
   document of that day across every tenant; after the backfill it is the seventh of that tenant.

## Rejected alternatives

| Alternative | Why rejected |
| --- | --- |
| One PostgreSQL `SEQUENCE` per tenant and document type | It needs dynamic DDL per tenant — a company created tomorrow is a schema change at runtime — and the tenant list stops being data. It also cannot scope by period without one sequence per month per tenant per type |
| Keep `max + 1` and take a table lock around it | Correct, and rejected because it serializes the hottest write path of the product: every sale in every tenant queues behind one lock. The upsert serializes per tenant, type and period, the narrowest lock that still prevents the race. Scoping the query by company alone does not help either: two cashiers of one tenant still read the same max |
| Reference documents by UUID only, and print the UUID | A ticket, a purchase order sent to a supplier and a call to support need a number a human can read, dictate and search. A UUID satisfies uniqueness and nothing else |
| Retry the insert on a unique violation | It hides the race instead of removing it: the retry recomputes the same value, so it can loop under load, and it makes a correct refusal (a real duplicate) indistinguishable from a retry that fails to converge |

## Consequences

- **Good**: a race stops being a lost sale at the till. No retry, no constraint violation, no queue
  shared by tenants, and one tenant's volume is invisible to another in the number and in its width.
- **Good**: period, ordinal and type are columns. "How many sales did this store write in October"
  filters on `seq_period` instead of parsing a string, and a format change stops rewriting history.
- **Cost**: every path that creates a numbered document changes; two repositories lose a derived
  query their tests use, and `SalesOrderService` loses its private `generateOrderNumber`, also the
  only place logging the unparseable-number warning (`:936`). Allocation is a round trip inside the
  caller's transaction, and old numbers are renumbered: step 5 is two releases.
