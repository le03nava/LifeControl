# ADR 0004 — Document state is a typed machine with a history

- **Status**: accepted
- **Date**: 2026-10-29
- **Accepted**: 2026-11-02, after the seven defects recorded under *Corrections applied before acceptance* — six corrected in this document and one, the ledger's per-write identity, declared open because it is not this ADR's to decide
- **Decision id**: D4 of `odd/tasks/architecture-redesign-adrs.md`
- **Depends on**: ADR-0005 (`status_transition` is a tenant table, and this ADR arms it — naming the policy
  does not create it), ADR-0003 (`sales_orders.company_id` **and** `goods_receipts.company_id`, both of
  which `document_tenant_and_state` reads)
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
   a `code` (the enum constant) **for the five families that have one** — the other two keep their rows,
   without a code, because a code with no constant behind it is the lie T5 exists to catch — and stop
   being the authority: a label is translation, and renaming one changes what the screen says. Two tests
   keep the three copies honest — enum ↔ catalog, and `CHECK` ↔ enum.
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
    -- SEVEN states, and they are the ones `PurchaseOrderService`'s DETAIL_TRANSITIONS declares and
    -- `V3:187-229` seeds. This is NOT the item set: the detail machine has its own tail (In Process,
    -- Partial Received), and copying the item set here — as an earlier draft of this ADR did, while
    -- claiming in a comment that it had not — both omits five real states and admits 'ADDED', which no
    -- purchase-order detail has ever been able to hold.
    ADD CONSTRAINT ck_purchase_order_details_status CHECK (status IN (
        'PENDING', 'IN_PROCESS', 'IN_TRANSIT', 'PARTIAL_RECEIVED', 'RECEIVED', 'REJECTED', 'CANCELLED'));

-- the commerce document the first draft missed: `V12:58-64` seeds exactly one state, and this ADR
-- types it without inventing a cancellation the product has not asked for
ALTER TABLE goods_receipts
    ADD COLUMN status VARCHAR(24),
    ADD CONSTRAINT ck_goods_receipts_status CHECK (status IN ('REGISTERED'));

-- the catalog stops being an authority and becomes the label of a code
ALTER TABLE statuses
    -- NULLABLE, and that is the correction of a defect rather than a weakening. `statuses` is one
    -- catalog for seven families and this ADR puts two of them out of scope by decision: `APPOINTMENT`
    -- (`V18:59-86`, five rows) and employment (`V20:60-78`, four). Those rows have no domain enum, so a
    -- code for them would be a constant that does not exist, and T5 — which asserts that a row without a
    -- constant fails — would then fail on them. `NOT NULL` here and "out of scope" there cannot both be
    -- true. What replaces it is scope, in two places: T5 joins only the five in-scope families, and step
    -- 4 asserts in the migration that every row of those five has a code.
    ADD COLUMN code VARCHAR(24),
    -- `UNIQUE` rather than the table's habit of a plain constraint: NULLs are distinct in a B-tree, so
    -- the out-of-scope rows cost nothing and the in-scope pairs are still one-to-one.
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

-- append-only twice: this trigger, and the REVOKE below. The comment an earlier draft carried here
-- credited ADR-0005 with an "INSERT/SELECT-only grant" that does not exist to inherit: `V52:190-197`
-- GRANTs SELECT/INSERT/UPDATE/DELETE to `lifecontrol_app` on every table **and** records the same
-- ALTER DEFAULT PRIVILEGES for tables created later, so this one arrives with UPDATE and DELETE in
-- hand. `V60:286-290` states the rule, and it belongs to ADR-0001's ledger: REVOKE is the only
-- statement that takes the privileges away, and the explicit GRANT after it is deliberate rather than
-- implied — it states the complete set the role is meant to hold, so a later `REVOKE ALL` elsewhere
-- cannot leave the table write-only by accident.
CREATE FUNCTION status_transition_append_only() RETURNS trigger AS $$
BEGIN RAISE EXCEPTION 'status_transition is append-only'; END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_status_transition_append_only
    BEFORE UPDATE OR DELETE ON status_transition
    FOR EACH ROW EXECUTE FUNCTION status_transition_append_only();

REVOKE UPDATE, DELETE ON status_transition FROM lifecontrol_app;
GRANT SELECT, INSERT ON status_transition TO lifecontrol_app;

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

-- ─── the new tenant table joins ADR-0005's list, and that is not optional ────────────────────────
-- `status_transition` carries `company_id`, which makes it a tenant table, and ADR-0005's machinery
-- decides what one with no policy means: `unprotected_tenant_tables()` (`V52`) returns every table that
-- carries a `company_id` column and has no `tenant_table` row, and `TenantIsolationStartupCheck`
-- refuses to boot on a non-empty answer. The `Depends on` line said the table "carries a policy"; that
-- was a claim about a statement nobody wrote, which is the defect ADR-0003's N9 had and the same repair.
--
-- The call is the LAST statement of the delivery on purpose: arming before step 5's seed would refuse
-- the seed's own INSERTs, because a policy's `WITH CHECK` applies to whoever writes and does not spare
-- the owner once the policy is FORCEd.
SELECT apply_tenant_isolation('status_transition');
```

## Invariants (each one testable)

| # | Invariant | Enforced by |
| --- | --- | --- |
| T1 | A stored state is always one the enum has | the `CHECK`; a test inserts every value the enum does not have and each one is refused |
| T2 | `canTransition` is the only place that decides | every write path calls it; a state change that skips it is a review finding, and a forbidden transition returns 409 and leaves the row untouched. The database cannot reach the Java enum, so this one is discipline with a named owner, not a constraint |
| T3 | Every state change appends a transition row, a move is recorded once, and history is immutable | the deferred trigger of decision 5 side A (the commit fails without the row), `uq_status_transition_creation` (at most one creation row), `uq_status_transition_once_per_move` (no duplicate write of the same move in the same instant), and the append-only trigger (`UPDATE`/`DELETE` raise) plus the `REVOKE`/`GRANT` pair of the schema block — which is this file's statement and not ADR-0005's, whose `V52:190-197` is what granted the privileges in the first place |
| T4 | A document's `status` equals the `to_status` of its latest transition, in both directions | decision 5's two deferred triggers, evaluated at commit: side A refuses a state change with no row, side B refuses a row whose `to_status` is not the document's final state. The nightly query stays as a **detector** for rows written before the triggers existed, not as the enforcement |
| T5 | The enum and the catalog agree in both directions, **inside the scope this ADR claims** | the parity test, scoped to the five families that have a domain enum — `SALES_ORDER`, `SALES_ORDER_ITEM`, `PURCHASE_ORDER`, `PURCHASE_ORDER_DETAIL`, `GOODS_RECEIPT`; a constant without a catalog row fails it, and so does an in-scope row without a constant. The two out-of-scope families (`APPOINTMENT` in `V18:59-86`, employment in `V20:60-78`, nine rows) carry no code and are excluded by construction. What stops an in-scope row slipping past the join is step 4's assertion in the migration, not a `NOT NULL` — see the `## Schema` block for why that column cannot be `NOT NULL` |
| T6 | The `CHECK` and the enum agree in both directions | a second parity test reads the `CHECK` definition from `pg_constraint` and compares it with `Enum.values()`. Without it the set has three copies and only two are coupled — the pattern is the one `KeycloakClaimMapperCoverageTest` already uses for the mapper script |
| T7 | A transition names a document of its own tenant | the `BEFORE INSERT` trigger of decision 6; a transition for another tenant's document is refused, not hidden by the policy. The table is also armed by the schema block: without that call the application does not start at all, because `unprotected_tenant_tables()` is non-empty and `TenantIsolationStartupCheck` refuses |

## Migration from the current tree

0. **Open the `NO FORCE` window on the three tenant document tables, before the first read, and check
   that it took.** `purchase_orders` is armed today (`V53:126`) and `sales_orders` and `goods_receipts`
   become armed when ADR-0003 lands; `V52`'s `apply_tenant_isolation` sets
   `FORCE ROW LEVEL SECURITY`, which filters the table's **owner**, and the owner is the role Flyway
   runs as. `V54:26` records the consequence: a statement over such a table matches **zero rows and
   reports success**.

   Here that bites three times, and the third is the one an earlier draft of this plan would not have
   survived: the `UPDATE` of step 2 numbers nothing; the "no `status` is null" assertion of step 3
   **also reads nothing**, so it cannot be the guardrail it is treated as; and step 6's seed queues
   deferred triggers whose own `SELECT`s read the documents, which fail closed with a null state and
   abort. `NO FORCE` is the only door a `NOSUPERUSER` owner has, and `V57:85` is the same window with the
   same two self-checks — that the window took, and that a statement inside it reached a row.

   ```sql
   ALTER TABLE purchase_orders     NO FORCE ROW LEVEL SECURITY;
   ALTER TABLE sales_orders        NO FORCE ROW LEVEL SECURITY;
   ALTER TABLE goods_receipts      NO FORCE ROW LEVEL SECURITY;
   ```

   Step 7 closes the window and re-checks it, before step 8 arms the new table — the order matters: a
   window left open weakens the table for everything that runs after it (`V55:100`).

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
4. **Add `statuses.code` and backfill it inside the scope this ADR claims.** The column is nullable, so
   only the five families that have a domain enum get one:

   ```sql
   -- the mapping is step 2's, from the other side: a catalog row of an in-scope family and its constant
   UPDATE statuses s SET code = m.enum_value
   FROM status_types st, (VALUES ('SALES_ORDER', 'Draft', 'DRAFT'), /* … one row per constant … */)
        AS m (type_name, catalog_name, enum_value)
   WHERE st.id = s.status_type_id AND st.status_type_name = m.type_name
     AND s.status_name = m.catalog_name;

   -- the assertion, and it is the one that replaces NOT NULL: every row of the five in-scope families
   -- has a code. An out-of-scope row is allowed to have none, by the scope decision above.
   DO $$
   DECLARE uncoded TEXT;
   BEGIN
       SELECT string_agg(s.status_name, ', ' ORDER BY s.status_name) INTO uncoded
       FROM statuses s JOIN status_types st ON st.id = s.status_type_id
       WHERE st.status_type_name IN ('SALES_ORDER', 'SALES_ORDER_ITEM', 'PURCHASE_ORDER',
                                     'PURCHASE_ORDER_DETAIL', 'GOODS_RECEIPT')
         AND s.code IS NULL;
       IF uncoded IS NOT NULL THEN
           RAISE EXCEPTION 'statuses.code did not reach: %', uncoded;
       END IF;
   END $$;
   ```

   Then land both parity tests (T5 and T6), with T5's join restricted to those five families — the line
   the first draft got wrong by declaring the two other families out of scope **and** requiring a code
   from every row of the table at the same time.
5. **Create the triggers and the append-only grant, then verify them.** `uq_status_transition_creation`,
   `uq_status_transition_once_per_move`, decision 6's `BEFORE INSERT` trigger and the five deferred
   triggers are what turn T3, T4 and T7 from a convention into a constraint; the `REVOKE`/`GRANT` pair
   is what makes append-only a property of the table instead of a property of one trigger. The `DO` block
   at the end of the schema is the assertion that every document table carries its pair — the same shape
   ADR-0005 uses for its policy list, and the reason "the same pair for the other four" cannot stay a
   comment.

   This step comes **before** the seed on purpose. Decision 6's trigger then validates the migration's
   own rows, so the tenant derivation of the backfill is checked by the same function that will check
   every writer after it — a seed written after the trigger would be the one write in the delivery that
   nobody validated.
6. **Seed `status_transition` with one row per existing document AND per existing item**:
   `from_status` null, `to_status` its backfilled status, `occurred_at` its `created_at`,
   `actor = 'unknown:migration'`, `reason = 'MIGRATION'`. The history before this migration is one
   initial row, not a reconstruction; inventing transitions would fabricate evidence. The actor is a
   literal because the column is `NOT NULL` and a backfill is not allowed to invent a person.
   The document and its row go in **one transaction**: the trigger is deferred to commit, so a
   document inserted on its own is refused — which is the constraint working, not an obstacle to work
   around.
7. **Close step 0's window and re-check it.** `ALTER TABLE … FORCE ROW LEVEL SECURITY` on the three
   tables, then assert `relforcerowsecurity` is true on each and that no document row is left without a
   status. A file that backfilled nothing and a file that backfilled everything print the same green,
   and only "did a row arrive" separates them.
8. **Arm `status_transition`, last.** `SELECT apply_tenant_isolation('status_transition')`, then assert
   that `unprotected_tenant_tables()` returns no row — the same query `TenantIsolationStartupCheck` runs
   at every boot. It is last because a policy's `WITH CHECK` applies to whoever writes and does not
   spare the owner once it is FORCEd, so arming before step 6 would refuse the seed the delivery depends
   on.
9. **Drop `status_id`** and its indexes (`V1:334`, `:488`, `:514`, `V12:36`) once every reader uses
   the new column, and delete the maps at `SalesOrderService.java:66-76` and
   `PurchaseOrderService.java:73-81`. The catalog rows stay: they are the labels the screens render,
   and the 32 name comparisons become enum references. `status_id`'s `NOT NULL` goes with the column,
   and the two out-of-scope tables — `scheduling_appointments`, `employees` — keep their `status_id`
   untouched, which is what makes the scope decision visible in the schema instead of only in prose.

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

## What this ADR does not unblock

`odd/tasks/inventory-ledger.md` closed ADR-0001 without its drops and recorded why: T5d "is blocked on
ADR-0004, which is a dependency and not a convenience: without the state machine's per-transition
identity, the `source_id` mirror cannot outlive `inventory_movements`". **That expectation is wrong about
this ADR, and reading the two documents together is what shows it.** This section exists so the error is
corrected here instead of being discovered by the slice that tries to spend it.

`status_transition` is the identity of a *state move*: one row per document (or document item) per move,
with `uq_status_transition_once_per_move` over the pair. What T5d needs is an identity per **write**, and
those are not the same event. The ledger books a sale deduction from
`SalesOrderService:985-989`:

```java
var delta = newQuantity.subtract(oldQuantity);
if (delta.signum() > 0) {
    operations.add(deduct(companyStoreId, newVariantId, delta, itemId));
}
```

Raising a line's quantity books **another SALE movement under the same `itemId`**, and the key that has
to tell the two apart is `(source_type, source_id, movement_type, store_location_id)` — widened with the
location by `V63` because one line genuinely spans two. A quantity edit is not a status change: the item
stays `ADDED`, no transition row is written, and `status_transition` therefore offers nothing to
distinguish the second booking from a retry of the first. A ledger that took the transition as its source
would refuse a legal edit, or accept a duplicate — which is exactly the defect `uq_movement_source`
exists to prevent.

So: **accepting this ADR leaves T5d exactly where it is.** The open decision is the ledger's per-write
identity, and the three shapes were already put to the maintainer in
`odd/tasks/inventory-ledger.md` (2026-11-02): derive the key from the document state — which needs a
sequence the service cannot supply today — let the ledger generate its own key, which costs invariant I5
its status, or stop before the drops. None of the three is a state machine, and this ADR does not choose
among them. It is recorded here because a reader of the adoption order would otherwise conclude that
accepting 0004 releases ADR-0001's debt, and it does not.
