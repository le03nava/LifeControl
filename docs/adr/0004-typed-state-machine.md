# ADR 0004 — Document state is a typed machine with a history

- **Status**: proposed
- **Date**: 2026-10-29
- **Decision id**: D4 of `odd/tasks/architecture-redesign-adrs.md`
- **Depends on**: ADR-0005 (`status_transition` is a tenant table and carries a policy), ADR-0003
  (`sales_orders.company_id`, which the transition's tenant pin reads)
- **Replaces**: the `statuses` lookup as the authority and the transition maps in both services

## Context

A document's state is a row in a lookup table, referenced by `status_id`, and the machine that moves
it between states is a `Map<String, Set<String>>` declared inside a service. Five facts carry the
decision:

| # | Evidence | What it means |
| --- | --- | --- |
| 1 | `V1__baseline_schema.sql:268-276` defines `statuses(status_name, status_type_id)` over `status_types`; documents hold `status_id` (`V1:323`, `:350`, `:476`, `:505`, `V12:25`), indexed at `V1:334`, `:488`, `:514` and `V12:36` | The legal states are rows, editable at runtime, invisible to the compiler |
| 2 | `V3__seed_reference_data.sql:34-40` renames `'Borrador'` to `'Draft'` for `SALES_ORDER`, and `:84-90` repeats it for `SALES_ORDER_ITEM` | The name is load-bearing data: a migration had to rename it because code compares it |
| 3 | `SalesOrderService.java:66-76` declares the transitions as strings; `:160-163` resolves the initial state by name, `:400-406` resolves two more, `:433` tests `!"Cancelled".equals(itemStatus.getStatusName())`, `:601` compares by name, `:910-918` validates an item transition | The machine lives in one service, spelled in strings |
| 4 | `PurchaseOrderService.java:73-81` repeats it with eight states including `'In Transit'`, `:83-90` for the details, `:187-190` resolves by name and `:631` compares by name | A second module, a second copy of the same discipline |
| 5 | `grep -rn '"Draft"\|"Active"\|"Cancelled"\|"Completed"' --include=*.java life-control-api/src/main/java \| wc -l` returns **32** on 2026-10-29 | The size of the surface that a rename has to reach |

Three defects follow, and the third is the one no refactor of the strings would fix:

| Defect | Consequence |
| --- | --- |
| A rename is a behavior change | Renaming a catalog row changes what the code does, with no compile error and no test that fails unless it happens to open that path |
| The transition set is not one place | The single-item edit (`:595-612`), the order-level put, the charge (`:400-412`, `:433`) and the cancel each resolve and compare their own way; a state added to one path is refused by another |
| There is no history | The row is overwritten in place. Nothing answers who cancelled an order, when, or why, and a "reason" has nowhere to live |

**Scope, stated because the first draft of this ADR undercounted it.** Seven columns reference
`statuses` today: the four above, `goods_receipts.status_id` (`V12:25`, with `Registered` seeded at
`:58-64`), `scheduling_appointments.status_id` (`V18:38`) and `employees.employment_status_id`
(`V20:39`). `goods_receipts` is a numbered commerce document (ADR-0003) and is **in scope**;
appointments and the employment status are not commerce documents — an appointment's lifecycle is a
calendar fact and an employment status is an HR attribute — and they are **out of scope**, named here
so the omission is a decision and not an oversight.

## Decision

**Each document type has one domain enum, the row stores a value constrained to it, transitions are
declared once, every change appends a row, and the database refuses a state that has no row.**

1. **The enum is the only source of truth.** `SalesOrderState`, `SalesOrderItemState`,
   `PurchaseOrderState`, `PurchaseOrderDetailState` and `GoodsReceiptState` are Java enums; the row
   stores `status VARCHAR(24) NOT NULL` with a `CHECK` listing their values — **not** a foreign key to
   `statuses`, which can be edited at runtime and says nothing to the compiler, while a `CHECK`
   derived from the enum refuses any other value.
2. **Transitions are declared once, next to the enum, as a closed set.** One guard,
   `canTransition(from, to)`, is the only place that decides, and every path calls it: the
   single-item edit (`:595-612`), the order-level put, the charge (`:400-412`, `:433`) and the
   cancel. A state with no entry has no outgoing transition, and a target outside its successor set
   is refused with the transition named.
3. **Item-level states are typed the same way.** `sales_order_items` and `purchase_order_details`
   get their own enum and their own closed set, so "an item of a charged order cannot be Cancelled"
   is a rule in one place instead of the `!"Cancelled".equals(…)` test at `:433`.
4. **Every change appends a row, and the database checks that it did.** `status_transition
   (company_id, document_type, document_id, from_status, to_status, actor, occurred_at, reason)` is
   append-only: no `UPDATE`, no `DELETE`. The state column answers "where is this document"; the
   table answers "how it got here".
5. **The tie between the two is enforced at commit, not audited at night.** A deferred constraint
   trigger on each document table refuses a commit in which a state change has no matching
   `status_transition` row, and a document created without its creation row. The state column and the
   history are two representations of one fact; the difference from a nightly query is that a writer
   that forgets the row **cannot commit**, instead of being discovered later.
6. **A transition names a document of its own tenant, and the database proves it.** `document_id` is
   polymorphic, so no foreign key can express this; a `BEFORE INSERT` trigger resolves the document by
   `document_type` and refuses a row whose document does not exist under that `company_id`. Without it
   a wrong `company_id` is not rejected but **hidden**, because ADR-0005's policy filters the row
   instead of failing the write.
7. **The lookup survives as a presentation catalog.** `statuses`/`status_types` keep their rows, gain
   a `code` (the enum constant) and stop being the authority: a label is translation, and renaming one
   changes what the screen says. Two tests keep the three copies honest — enum ↔ catalog, and
   `CHECK` ↔ enum.
8. **A payment is not a document with a state.** ADR-0002 gives `sale_payment` no state column and
   makes its `sale_payment_void` row the transition. Nothing in this ADR applies to it, and a reader
   should not expect a `status_transition` row per void.

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

ALTER TABLE purchase_orders
    ADD COLUMN status VARCHAR(24),
    ADD CONSTRAINT ck_purchase_orders_status CHECK (status IN (
        'DRAFT', 'SENT', 'ACCEPTED', 'IN_TRANSIT', 'RECEIVED', 'BILLED', 'CLOSED', 'REJECTED'));

ALTER TABLE purchase_order_details
    ADD COLUMN status VARCHAR(24),
    ADD CONSTRAINT ck_purchase_order_details_status CHECK (status IN (
        'PENDING', 'ADDED', 'CANCELLED'));   -- its own enum, not a copy of the item one

-- the commerce document the first draft missed: `V12:58-64` seeds exactly one state, and this ADR
-- types it without inventing a cancellation the product has not asked for
ALTER TABLE goods_receipts
    ADD COLUMN status VARCHAR(24),
    ADD CONSTRAINT ck_goods_receipts_status CHECK (status IN ('REGISTERED'));

-- the catalog stops being an authority and becomes the label of a code
ALTER TABLE statuses
    -- NOT NULL because a UNIQUE over a nullable column admits any number of NULL rows, and a row
    -- without a code escapes the parity test's join. The backfill and this constraint land together.
    ADD COLUMN code VARCHAR(24) NOT NULL,
    ADD CONSTRAINT uq_statuses_type_code UNIQUE (status_type_id, code);

CREATE TABLE status_transition (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- insertion order, so "the latest transition" has one answer even inside a single transaction,
    -- where every row shares the same now()
    seq           BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    company_id    UUID NOT NULL,
    -- documents AND items: an item's state change is a fact too, so the set is wider than ADR-0003's
    -- `doc_type`, which names documents only
    document_type VARCHAR(24) NOT NULL,
    document_id   UUID NOT NULL,
    from_status   VARCHAR(24),                   -- NULL only for the row that creates the document
    to_status     VARCHAR(24) NOT NULL,
    actor         VARCHAR(255) NOT NULL,         -- the question this table exists to answer
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    reason        TEXT,
    CONSTRAINT ck_transition_moves CHECK (from_status IS DISTINCT FROM to_status),
    CONSTRAINT ck_transition_document_type CHECK (document_type IN (
        'SALES_ORDER', 'SALES_ORDER_ITEM', 'PURCHASE_ORDER', 'PURCHASE_ORDER_DETAIL',
        'GOODS_RECEIPT')));

CREATE INDEX idx_status_transition_document
    ON status_transition (company_id, document_type, document_id, occurred_at);
-- the audit question "what moved in this tenant last month" is not the same query
CREATE INDEX idx_status_transition_tenant_day
    ON status_transition (company_id, occurred_at);

-- exactly one creation row per document, and it is the one with no predecessor
CREATE UNIQUE INDEX uq_status_transition_creation
    ON status_transition (company_id, document_type, document_id) WHERE from_status IS NULL;

-- one move is recorded once. Two identical rows written in the same transaction share now(), so the
-- index refuses the duplicate write; a genuine A->B->A->B at different instants is a different fact
-- and passes, which is why this is not a plain UNIQUE over the four columns.
CREATE UNIQUE INDEX uq_status_transition_once_per_move
    ON status_transition (company_id, document_type, document_id, from_status, to_status, occurred_at);

-- append-only twice: this trigger, and the INSERT/SELECT-only grant that ADR-0005 owns for the
-- application role. The grant is named here so the trigger is not mistaken for the whole enforcement
CREATE FUNCTION status_transition_append_only() RETURNS trigger AS $$
BEGIN RAISE EXCEPTION 'status_transition is append-only'; END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_status_transition_append_only
    BEFORE UPDATE OR DELETE ON status_transition
    FOR EACH ROW EXECUTE FUNCTION status_transition_append_only();

-- ─── one place resolves a polymorphic document to its tenant and its state ───────────────────────
-- A new document type adds one branch here. A forgotten one fails CLOSED: the function returns no
-- row, and both triggers below treat that as a refusal instead of a pass.
CREATE FUNCTION document_tenant_and_state(p_type VARCHAR, p_id UUID)
RETURNS TABLE (company_id UUID, status VARCHAR) AS $$
    SELECT o.company_id, o.status FROM sales_orders o
      WHERE p_type = 'SALES_ORDER' AND o.id = p_id
    UNION ALL
    SELECT o.company_id, i.status FROM sales_order_items i JOIN sales_orders o ON o.id = i.sales_order_id
      WHERE p_type = 'SALES_ORDER_ITEM' AND i.id = p_id
    UNION ALL
    SELECT o.company_id, o.status FROM purchase_orders o
      WHERE p_type = 'PURCHASE_ORDER' AND o.id = p_id
    UNION ALL
    SELECT o.company_id, d.status FROM purchase_order_details d JOIN purchase_orders o ON o.id = d.purchase_order_id
      WHERE p_type = 'PURCHASE_ORDER_DETAIL' AND d.id = p_id
    UNION ALL
    SELECT g.company_id, g.status FROM goods_receipts g
      WHERE p_type = 'GOODS_RECEIPT' AND g.id = p_id
$$ LANGUAGE sql STABLE;

-- ─── decision 6: the transition names a document of its own tenant ───────────────────────────────
-- `document_id` is polymorphic, so this is a trigger and not a foreign key.
CREATE FUNCTION status_transition_document_belongs_to_company() RETURNS trigger AS $$
DECLARE d record;
BEGIN
    SELECT * INTO d FROM document_tenant_and_state(NEW.document_type, NEW.document_id);
    IF d.company_id IS DISTINCT FROM NEW.company_id THEN
        RAISE EXCEPTION 'status_transition % / % does not name a % of company %',
            NEW.document_type, NEW.document_id, NEW.document_type, NEW.company_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_status_transition_document_company
    BEFORE INSERT ON status_transition
    FOR EACH ROW EXECUTE FUNCTION status_transition_document_belongs_to_company();

-- ─── decision 5, side A: the state cannot move without its row, and nothing is born without one ──
-- Deferred to commit on purpose: the order of the two writes inside the transaction stops mattering,
-- which is what makes this enforceable instead of a convention.
CREATE FUNCTION assert_document_transition_recorded() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NOT EXISTS (
            SELECT 1 FROM status_transition t
            WHERE t.document_type = TG_ARGV[0] AND t.document_id = NEW.id
              AND t.company_id = NEW.company_id
              AND t.from_status IS NULL AND t.to_status = NEW.status)
        THEN
            RAISE EXCEPTION '% % was created without its status_transition row', TG_ARGV[0], NEW.id;
        END IF;
    ELSIF NEW.status IS DISTINCT FROM OLD.status THEN
        IF NOT EXISTS (
            SELECT 1 FROM status_transition t
            WHERE t.document_type = TG_ARGV[0] AND t.document_id = NEW.id
              AND t.company_id = NEW.company_id
              AND t.from_status = OLD.status AND t.to_status = NEW.status)
        THEN
            RAISE EXCEPTION '% % moved % -> % without its status_transition row',
                TG_ARGV[0], NEW.id, OLD.status, NEW.status;
        END IF;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- ─── decision 5, side B: a row cannot claim a state the document does not hold ───────────────────
-- The other direction of the same tie, and what makes T4 true instead of aspirational: a transition
-- appended without moving the document is refused at commit. It reads the LATEST row by `seq`, so a
-- document created and moved inside one transaction is judged on its final state, not on its first
-- row.
CREATE FUNCTION assert_transition_matches_document_state() RETURNS trigger AS $$
DECLARE d record; latest VARCHAR;
BEGIN
    SELECT * INTO d FROM document_tenant_and_state(NEW.document_type, NEW.document_id);
    SELECT t.to_status INTO latest
    FROM status_transition t
    WHERE t.company_id = NEW.company_id AND t.document_type = NEW.document_type
      AND t.document_id = NEW.document_id
    ORDER BY t.seq DESC LIMIT 1;
    IF d.status IS DISTINCT FROM latest THEN
        RAISE EXCEPTION 'status_transition % / %: the latest row says % but the document is %',
            NEW.document_type, NEW.document_id, latest, coalesce(d.status, 'missing');
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_status_transition_matches_state
    AFTER INSERT ON status_transition
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_transition_matches_document_state();

-- ─── one pair of deferred triggers per document table ────────────────────────────────────────────
CREATE CONSTRAINT TRIGGER trg_sales_orders_transition_recorded
    AFTER INSERT OR UPDATE ON sales_orders
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_document_transition_recorded('SALES_ORDER');

CREATE CONSTRAINT TRIGGER trg_sales_order_items_transition_recorded
    AFTER INSERT OR UPDATE ON sales_order_items
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_document_transition_recorded('SALES_ORDER_ITEM');

CREATE CONSTRAINT TRIGGER trg_purchase_orders_transition_recorded
    AFTER INSERT OR UPDATE ON purchase_orders
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_document_transition_recorded('PURCHASE_ORDER');

CREATE CONSTRAINT TRIGGER trg_purchase_order_details_transition_recorded
    AFTER INSERT OR UPDATE ON purchase_order_details
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_document_transition_recorded('PURCHASE_ORDER_DETAIL');

CREATE CONSTRAINT TRIGGER trg_goods_receipts_transition_recorded
    AFTER INSERT OR UPDATE ON goods_receipts
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_document_transition_recorded('GOODS_RECEIPT');

-- ─── the assertion: a document table without its pair fails the migration ────────────────────────
DO $$
DECLARE missing TEXT;
BEGIN
    SELECT string_agg(t, ', ') INTO missing
    FROM unnest(ARRAY['sales_orders', 'sales_order_items', 'purchase_orders',
                      'purchase_order_details', 'goods_receipts']) AS t
    WHERE NOT EXISTS (
        SELECT 1 FROM pg_trigger g
        WHERE g.tgrelid = t::regclass
          AND g.tgdeferrable AND NOT g.tgisinternal
          AND g.tgfoid = 'assert_document_transition_recorded'::regproc);
    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'document table(s) without the deferred transition trigger: %', missing;
    END IF;
END $$;
```

## Invariants (each one testable)

| # | Invariant | Enforced by |
| --- | --- | --- |
| T1 | A stored state is always one the enum has | the `CHECK`; a test inserts every value the enum does not have and each one is refused |
| T2 | `canTransition` is the only place that decides | every write path calls it; a state change that skips it is a review finding, and a forbidden transition returns 409 and leaves the row untouched. The database cannot reach the Java enum, so this one is discipline with a named owner, not a constraint |
| T3 | Every state change appends a transition row, a move is recorded once, and history is immutable | the deferred trigger of decision 5 side A (the commit fails without the row), `uq_status_transition_creation` (at most one creation row), `uq_status_transition_once_per_move` (no duplicate write of the same move in the same instant), and the append-only trigger (`UPDATE`/`DELETE` raise) plus ADR-0005's grant |
| T4 | A document's `status` equals the `to_status` of its latest transition, in both directions | decision 5's two deferred triggers, evaluated at commit: side A refuses a state change with no row, side B refuses a row whose `to_status` is not the document's final state. The nightly query stays as a **detector** for rows written before the triggers existed, not as the enforcement |
| T5 | The enum and the catalog agree in both directions | the parity test; a constant without a catalog row fails it, and so does a row without a constant. `statuses.code` is `NOT NULL`, so a catalog row cannot slip past the join |
| T6 | The `CHECK` and the enum agree in both directions | a second parity test reads the `CHECK` definition from `pg_constraint` and compares it with `Enum.values()`. Without it the set has three copies and only two are coupled — the pattern is the one `KeycloakClaimMapperCoverageTest` already uses for the mapper script |
| T7 | A transition names a document of its own tenant | the `BEFORE INSERT` trigger of decision 6; a transition for another tenant's document is refused, not hidden by the policy |

## Migration from the current tree

1. **Add `status` nullable** to the five tables, `purchase_order_details` and `goods_receipts`
   included. Nothing reads it yet.
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

   The item, purchase and receipt tables use the same statement with their own `VALUES` list;
   `'In Transit'` becomes `IN_TRANSIT`, `'Registered'` becomes `REGISTERED`, and the mapping is the
   only place that correspondence is written down.
3. **Assert no `status` is null**, then add the `CHECK` constraints and `NOT NULL`: a constraint
   added before the backfill would refuse the rows it must accept.
4. **Add `statuses.code`**, backfill it from the same mapping, `SET NOT NULL`, and land both parity
   tests (T5 and T6). `NOT NULL` is not cosmetic: a `UNIQUE` over a nullable column admits any number
   of `NULL` rows, so without it a catalog row escapes the join and T5 passes over it.
5. **Seed `status_transition` with one row per existing document AND per existing item**:
   `from_status` null, `to_status` its backfilled status, `occurred_at` its `created_at`,
   `actor = 'unknown:migration'`, `reason = 'MIGRATION'`. The history before this migration is one
   initial row, not a reconstruction; inventing transitions would fabricate evidence. The actor is a
   literal because the column is `NOT NULL` and a backfill is not allowed to invent a person.
   The document and its row go in **one transaction**: the trigger is deferred to commit, so a
   document inserted on its own is refused — which is the constraint working, not an obstacle to work
   around.
6. **Create the triggers, then verify them.** `uq_status_transition_creation`,
   `uq_status_transition_once_per_move` and the three deferred triggers are what turn T3 and T4 from a
   convention into a constraint. The `DO` block at the end of the schema is the assertion that every
   document table carries its pair — the same shape ADR-0005 uses for its policy list, and the reason
   "the same pair for the other four" cannot stay a comment.
7. **Drop `status_id`** and its indexes (`V1:334`, `:488`, `:514`, `V12:36`) once every reader uses
   the new column, and delete the maps at `SalesOrderService.java:66-76` and
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
| Verify T4 with a nightly query only | A query answers "did it drift"; a deferred constraint trigger answers "it cannot". The nightly query stays as a detector for pre-migration rows, which is a different job |
| One child transition table per document type, so a foreign key can pin the tenant | It is the only shape where the pin is a real FK, and it costs five tables, five indexes and five queries for one history. The `BEFORE INSERT` trigger of decision 6 buys the same refusal for one function, and fails closed when a document type is added and its `WHEN` is not |

## Consequences

- **Good**: a rename stops being a behavior change. Renaming `Draft` in the catalog changes a label;
  renaming the constant changes every site the compiler can see, which is all of them.
- **Good**: the transition set is one readable declaration per document type, and every path —
  single-item edit, order-level put, charge, cancel — is refused by the same rule instead of four.
- **Good**: the history exists and cannot silently disagree with the state. "Why was this cancelled, by
  whom, when" is a query, and a state that arrives from a bug leaves a row that says so — or does not
  commit at all.
- **Good**: the scope is the whole commerce core. A receipt has a typed state too, and the two
  non-commerce tables are named as out of scope instead of being discovered later.
- **Cost**: every reader of `status_id` changes. DTOs return a code, the Angular screens map it
  through the catalog, and queries that filtered on a joined `status_name` filter on the column.
  The two parity tests are an obligation whenever a state is added, and `status_transition` grows with
  every transition: the audit tax is paid on write.
- **Cost**: five document tables carry a deferred trigger pair, and decision 6's function carries one
  `WHEN` per document type. Adding a document type is an enum constant, a `CHECK`, a migration, two
  triggers and one `WHEN` — six places, all of them mechanical, and the one that fails closed is the
  `WHEN`.
- **Cost**: the deferred triggers make the two writes atomic in a way a reader of the service code
  cannot see. That is the point, and it is why the trigger names carry the invariant number.
