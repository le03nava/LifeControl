# ADR 0004 — Document state is a typed machine with a history

- **Status**: proposed
- **Date**: 2026-10-29
- **Decision id**: D4 of `odd/tasks/architecture-redesign-adrs.md`
- **Depends on**: —
- **Replaces**: the `statuses` lookup as the authority and the transition maps in both services

## Context

A document's state is a row in a lookup table, referenced by `status_id`, and the machine that moves
it between states is a `Map<String, Set<String>>` declared inside a service. Four facts carry the
decision:

| # | Evidence | What it means |
| --- | --- | --- |
| 1 | `V1__baseline_schema.sql:268-276` defines `statuses(status_name, status_type_id)` over `status_types`; documents hold `status_id` (`V1:323`, `:350`, `:476`, `:505`), indexed at `:334`, `:488`, `:514` | The legal states are rows, editable at runtime, invisible to the compiler |
| 2 | `V3__seed_reference_data.sql:34-40` renames `'Borrador'` to `'Draft'` for `SALES_ORDER`, and `:84-90` repeats it for `SALES_ORDER_ITEM` | The name is load-bearing data: a migration had to rename it because code compares it |
| 3 | `SalesOrderService.java:66-76` declares the transitions as strings; `:160-163` resolves the initial state by name, `:400-406` resolves two more, `:433` tests `!"Cancelled".equals(itemStatus.getStatusName())`, `:601` compares by name, `:910-918` validates an item transition | The machine lives in one service, spelled in strings |
| 4 | `PurchaseOrderService.java:73-81` repeats it with eight states including `'In Transit'`, `:83-90` for the details, `:187-190` resolves by name and `:631` compares by name | A second module, a second copy of the same discipline |

It is not two services. `grep -rn '"Draft"\|"Active"\|"Cancelled"\|"Completed"' --include=*.java
life-control-api/src/main/java | wc -l` returns **32** on 2026-10-29.

Three defects follow, and the third is the one no refactor of the strings would fix:

| Defect | Consequence |
| --- | --- |
| A rename is a behavior change | Renaming a catalog row changes what the code does, with no compile error and no test that fails unless it happens to open that path |
| The transition set is not one place | The single-item edit (`:595-612`), the order-level put, the charge (`:400-412`, `:433`) and the cancel each resolve and compare their own way; a state added to one path is refused by another |
| There is no history | The row is overwritten in place. Nothing answers who cancelled an order, when, or why, and a "reason" has nowhere to live |

## Decision

**Each document type has one domain enum, the row stores a value constrained to it, transitions are
declared once, and every change appends a row.**

1. **The enum is the only source of truth.** `SalesOrderState`, `SalesOrderItemState` and
   `PurchaseOrderState` are Java enums; the row stores `status VARCHAR(24) NOT NULL` with a `CHECK`
   listing their values — **not** a foreign key to `statuses`, which can be edited at runtime and
   says nothing to the compiler, while a `CHECK` derived from the enum refuses any other value.
2. **Transitions are declared once, next to the enum, as a closed set.** One guard,
   `canTransition(from, to)`, is the only place that decides, and every path calls it: the
   single-item edit (`:595-612`), the order-level put, the charge (`:400-412`, `:433`) and the
   cancel. A state with no entry has no outgoing transition, and a target outside its successor set
   is refused with the transition named.
3. **Item-level states are typed the same way.** `sales_order_items` and `purchase_order_details`
   get their own enum and their own closed set, so "an item of a charged order cannot be Cancelled"
   is a rule in one place instead of the `!"Cancelled".equals(…)` test at `:433`.
4. **Every change appends a row.** `status_transition (company_id, document_type, document_id,
   from_status, to_status, actor, occurred_at, reason)` is append-only: no `UPDATE`, no `DELETE`.
   The state column answers "where is this document"; the table answers "how did it get here".
5. **The lookup survives as a presentation catalog.** `statuses`/`status_types` keep their rows,
   gain a `code` (the enum constant) and stop being the authority: a label is translation, and
   renaming one changes what the screen says. A test asserts both directions agree.

## Schema

```sql
ALTER TABLE sales_orders
    ADD COLUMN status VARCHAR(24),
    ADD CONSTRAINT ck_sales_orders_status CHECK (status IN (
        'DRAFT', 'ACTIVE', 'PENDING', 'COMPLETED', 'CANCELLED'));

ALTER TABLE sales_order_items
    ADD COLUMN status VARCHAR(24),
    ADD CONSTRAINT ck_sales_order_items_status CHECK (status IN (
        'PENDING', 'ADDED', 'CANCELLED'));
-- purchase_order_details gets the same two statements, with its own enum and its own CHECK

ALTER TABLE purchase_orders
    ADD COLUMN status VARCHAR(24),
    ADD CONSTRAINT ck_purchase_orders_status CHECK (status IN (
        'DRAFT', 'SENT', 'ACCEPTED', 'IN_TRANSIT', 'RECEIVED', 'BILLED', 'CLOSED', 'REJECTED'));

-- the catalog stops being an authority and becomes the label of a code
ALTER TABLE statuses
    ADD COLUMN code VARCHAR(24),
    ADD CONSTRAINT uq_statuses_type_code UNIQUE (status_type_id, code);

CREATE TABLE status_transition (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id    UUID NOT NULL,
    document_type VARCHAR(24) NOT NULL,          -- 'SALES_ORDER', 'PURCHASE_ORDER', …
    document_id   UUID NOT NULL,
    from_status   VARCHAR(24),                   -- NULL only for the row that creates the document
    to_status     VARCHAR(24) NOT NULL,
    actor         VARCHAR(255),
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    reason        TEXT,
    CONSTRAINT ck_transition_moves CHECK (from_status IS DISTINCT FROM to_status)
);

CREATE INDEX idx_status_transition_document
    ON status_transition (company_id, document_type, document_id, occurred_at);

-- append-only twice: this trigger, and a grant of INSERT, SELECT only to the application role
CREATE FUNCTION status_transition_append_only() RETURNS trigger AS $$
BEGIN RAISE EXCEPTION 'status_transition is append-only'; END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_status_transition_append_only
    BEFORE UPDATE OR DELETE ON status_transition
    FOR EACH ROW EXECUTE FUNCTION status_transition_append_only();
```

## Invariants (each one testable)

| # | Invariant | Enforced by |
| --- | --- | --- |
| T1 | A stored state is always one the enum has | the `CHECK`; a test inserts every value the enum does not have and each one is refused |
| T2 | `canTransition` is the only place that decides | every write path calls it; a state change that skips it is a review finding, and a forbidden transition returns 409 and leaves the row untouched |
| T3 | Every state change appends exactly one transition row, and history is immutable | the transitioning method writes both, in one transaction; `UPDATE` and `DELETE` raise, and a fixture that changes state without a row fails a query over the two |
| T4 | A document's `status` equals the `to_status` of its latest transition | one query, run in CI over a fixture and nightly over the database |
| T5 | The enum and the catalog agree in both directions | the parity test; a constant without a catalog row fails it, and so does a row without a constant |

## Migration from the current tree

1. **Add `status` nullable** to each document table, `purchase_order_details` included. Nothing
   reads it yet.
2. **Backfill with an explicit mapping**, not a derivation: the migration names each catalog value
   and the constant it becomes, so a rename cannot silently produce a value the enum lacks.

   ```sql
   UPDATE sales_orders so
   SET status = m.enum_value
   FROM statuses s
   JOIN (VALUES ('Draft', 'DRAFT'), ('Active', 'ACTIVE'), ('Pending', 'PENDING'),
                ('Completed', 'COMPLETED'), ('Cancelled', 'CANCELLED'))
        AS m (catalog_name, enum_value) ON m.catalog_name = s.status_name
   WHERE s.id = so.status_id;
   ```

   The item and purchase tables use the same statement with their own `VALUES` list; `'In Transit'`
   becomes `IN_TRANSIT`, and the mapping is the only place that correspondence is written down.
3. **Assert no `status` is null**, then add the `CHECK` constraints and `NOT NULL`: a constraint
   added before the backfill would refuse the rows it must accept.
4. **Add `statuses.code`**, backfill it from the same mapping, and land the parity test.
5. **Seed `status_transition`** with one row per existing document: `from_status` null, `to_status`
   its backfilled status, `occurred_at` its `created_at`, `reason = 'MIGRATION'`. The history before
   this migration is one initial row, not a reconstruction; inventing transitions would fabricate
   evidence.
6. **Drop `status_id`** and its indexes (`V1:334`, `:488`, `:514`) once every reader uses the new
   column, and delete the maps at `SalesOrderService.java:66-76` and
   `PurchaseOrderService.java:73-81`. The catalog rows stay: they are the labels the screens render,
   and the 32 name comparisons become enum references.

## Rejected alternatives

| Alternative | Why rejected |
| --- | --- |
| Keep the lookup table as the authority | No compiler checks a string, and the transition map lives in a service: a row edit changes behavior at runtime, without a commit, and the type system never sees it |
| A workflow engine such as Camunda or Activiti | It buys a runtime, a deployment artifact and its own schema for a machine that fits in an enum. The engine's definition is still a second copy of what the code assumes, and the copy is now in XML |
| Store only the current state | The state is a fact; the path is the audit. Without `status_transition`, nobody can answer why an order was cancelled, by whom, or when, which is the question a refund and a support call both start with |
| A PostgreSQL `ENUM` type instead of `VARCHAR` plus `CHECK` | A value cannot be removed from a type, and the domain enum has to change with the code that references it; a database type cannot follow a refactor, and it makes the value set a migration instead of a commit |
| Keep the string map and move it to a configuration table | A table is a second authority and is still unchecked: it makes the transition set editable at runtime and still fails at the first typo. The closed set belongs where the compiler can read it |

## Consequences

- **Good**: a rename stops being a behavior change. Renaming `Draft` in the catalog changes a label;
  renaming the constant changes every site the compiler can see, which is all of them.
- **Good**: the transition set is one readable declaration per document type, and every path —
  single-item edit, order-level put, charge, cancel — is refused by the same rule instead of four.
- **Good**: the history exists. "Why was this cancelled, by whom, when" is a query, and a state that
  arrives from a bug leaves a row that says so.
- **Cost**: every reader of `status_id` changes. DTOs return a code, the Angular screens map it
  through the catalog, and queries that filtered on a joined `status_name` filter on the column.
  The parity test is an obligation whenever a state is added, and `status_transition` grows with
  every transition: the audit tax is paid on write. Its rows are written in the same transaction as
  the state, so a writer that forgets one produces a document T4 rejects — which is the point.
