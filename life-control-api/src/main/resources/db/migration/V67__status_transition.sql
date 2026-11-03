-- ============================================
-- V67 — status_transition: the history of every state change (ADR-0004 task T4)
-- ============================================
-- The state column answers "where is this document". This table answers "how it got here", and it answers it
-- with evidence: one row per change, append-only, and the row names the tenant, the document, both ends of
-- the move, the actor and the instant (decision 4). Nothing here decides a transition — that is
-- `DocumentStates.canTransition`, in Java, because the database cannot reach the enum (invariant T2). What
-- this file does is make the history impossible to lose or to rewrite, and make the tie between a document
-- and its history a constraint instead of a convention.
--
-- WHAT THIS FILE DELIBERATELY DOES NOT CREATE, AND WHY
-- ---------------------------------------------------
-- The five deferred triggers of decision 5's **side A** — `assert_document_transition_recorded` plus
-- `trg_<table>_transition_recorded` on `sales_orders`, `sales_order_items`, `purchase_orders`,
-- `purchase_order_details` and `goods_receipts` — do **not** land here. They are per family, in tasks T5, T6
-- and T7 (migrations `V68`, `V69`, `V70`), for one reason: side A refuses **every existing writer** of a
-- document table until that family's writers append their rows, so a side A trigger created before the
-- family moved would break the tree between two commits instead of the next slice. The same applies to the
-- assertion `DO` block that checks all five pairs exist: it belongs with the fifth pair, in T7, because
-- asserting five pairs after creating one is a file that fails on its own contents.
--
-- This is **not** an oversight and it is not a deferral of the invariant: T4 lands decision 5's **side B**
-- (`assert_transition_matches_document_state` and its `DEFERRABLE INITIALLY DEFERRED` constraint trigger),
-- because side B constrains only the rows of **this** table — a row cannot claim a state its document does
-- not hold — so it refuses no existing writer and can be armed the moment the table exists.
-- `assert_document_transition_recorded()` is left to T5 for the same reason: it has no caller and no case in
-- this slice, and a function that lands unexercised is a path nobody has proven.
--
-- WHY `apply_tenant_isolation` IS THE LAST STATEMENT
-- --------------------------------------------------
-- `status_transition` carries `company_id`, so it is a tenant table, and ADR-0005's machinery decides what
-- one with no policy means: `unprotected_tenant_tables()` returns every table that carries the column and has
-- no `tenant_table` row, and `TenantIsolationStartupCheck` refuses to boot on a non-empty answer. Arming
-- before this file's own writes would be harmless today (nothing writes the table here), but the ADR puts the
-- call last for a reason that outlives this slice: a policy's `WITH CHECK` applies to whoever writes and does
-- not spare the owner once it is FORCEd, so a later seed of this table — T5's, in `V68` — is the write the
-- ordering protects. Keeping the call last here is what makes that ordering the file's habit and not a
-- surprise in the next one.

-- ============================================
-- 1. The table
-- ============================================
-- `seq` is insertion order and not a replacement key: inside one transaction every row shares the same
-- `now()`, so "the latest transition" needs an answer that `occurred_at` cannot give. `from_status` is NULL
-- only for the row that creates a document — that NULL is what `uq_status_transition_creation` makes unique.
-- `document_type` is wider than ADR-0003's `doc_type`: an item's state change is a fact too, so the set names
-- documents and their lines.
CREATE TABLE status_transition (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    seq           BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    company_id    UUID NOT NULL,
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

-- ============================================
-- 2. The three indexes the ADR declares, and no fourth one
-- ============================================
-- The document's own history, and the tenant's month. Two questions, two indexes: "what happened to this
-- document" and "what moved in this tenant last month" are not the same query and neither index serves both.
CREATE INDEX idx_status_transition_document
    ON status_transition (company_id, document_type, document_id, occurred_at);

CREATE INDEX idx_status_transition_tenant_day
    ON status_transition (company_id, occurred_at);

-- Exactly one creation row per document, and it is the one with no predecessor.
CREATE UNIQUE INDEX uq_status_transition_creation
    ON status_transition (company_id, document_type, document_id) WHERE from_status IS NULL;

-- One move is recorded once. Two identical rows written in the same transaction share `now()`, so the index
-- refuses the duplicate write; a genuine A->B->A->B at different instants is a different fact and passes,
-- which is why this is not a plain UNIQUE over the four columns.
CREATE UNIQUE INDEX uq_status_transition_once_per_move
    ON status_transition (company_id, document_type, document_id, from_status, to_status, occurred_at);

-- ============================================
-- 3. Append-only, twice: the trigger and the grant set
-- ============================================
-- Neither half is redundant. The trigger is what holds for every role that can reach an `UPDATE` or a
-- `DELETE` — the table's owner and a superuser included — and the grant set is what stops the application
-- from reaching one at all.
--
-- The `REVOKE` is load-bearing and it is not "never granted": `V52__tenant_isolation_machinery.sql:190-197`
-- GRANTs `SELECT, INSERT, UPDATE, DELETE` to `lifecontrol_app` on every table **and** records the same as an
-- `ALTER DEFAULT PRIVILEGES` for tables created later, so this table arrives writable by the application role.
-- The explicit `GRANT SELECT, INSERT` after the `REVOKE` is deliberate rather than implied — it states the
-- complete set the role is meant to hold, so a later `REVOKE ALL` elsewhere cannot leave the table write-only
-- (or unreadable) without this file's intent being visible. `V60__stock_ledger_schema.sql:280-300` states the
-- same rule for ADR-0001's ledger, and this is the same shape for the same reason.
CREATE FUNCTION status_transition_append_only() RETURNS trigger AS $$
BEGIN RAISE EXCEPTION 'status_transition is append-only'; END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION status_transition_append_only() IS
    'ADR-0004 decision 4: refuses any UPDATE or DELETE on status_transition. The other half of the rule is the '
    'grant set lifecontrol_app holds (SELECT, INSERT only), so the application never reaches this.';

CREATE TRIGGER trg_status_transition_append_only
    BEFORE UPDATE OR DELETE ON status_transition
    FOR EACH ROW EXECUTE FUNCTION status_transition_append_only();

REVOKE UPDATE, DELETE ON status_transition FROM lifecontrol_app;
GRANT SELECT, INSERT ON status_transition TO lifecontrol_app;

-- ============================================
-- 4. One place resolves a polymorphic document to its tenant and its state
-- ============================================
-- `document_id` is polymorphic, so decision 6's check is a trigger and not a foreign key. A new document type
-- adds one branch here; a forgotten one fails **closed** — the function returns no row, and both triggers
-- below treat that as a refusal instead of a pass, because `NULL IS DISTINCT FROM` anything is true.
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

COMMENT ON FUNCTION document_tenant_and_state(VARCHAR, UUID) IS
    'ADR-0004: the one place a (document_type, document_id) pair becomes a tenant and a state. A type with no '
    'branch returns no row on purpose: both triggers treat that as a refusal, never as a pass.';

-- ============================================
-- 5. Decision 6: a transition names a document of its own tenant
-- ============================================
-- Without this, a wrong `company_id` is not rejected but **hidden**: ADR-0005's policy filters the row instead
-- of failing the write, so the mistake survives as a row nobody can read and a history nobody can prove
-- (invariant T7). The message names the document type and the id, because the id is the only part a caller
-- cannot derive from the request it sent.
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

-- ============================================
-- 6. Decision 5, side B: a row cannot claim a state the document does not hold
-- ============================================
-- The other direction of the same tie, and what makes invariant T4 true instead of aspirational: a transition
-- appended without moving the document is refused at commit. It reads the LATEST row by `seq` — not by
-- `occurred_at`, which two rows of one transaction share — so a document created and moved inside one
-- transaction is judged on its final state and not on its first row.
--
-- DEFERRABLE INITIALLY DEFERRED is the whole point and not a detail: the order of the document's write and
-- this row's insert inside the transaction stops mattering, which is what makes the rule enforceable instead
-- of a convention about call order.
--
-- Side A — the same tie from the document's side — is per family in T5, T6 and T7; see the header.
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

-- ============================================
-- 7. The new tenant table joins ADR-0005's list — last, as the ADR prescribes
-- ============================================
-- `apply_tenant_isolation` is the only way a table gets a policy, and the row it writes in `tenant_table` is
-- what keeps "listed" and "protected" one fact. It sets ENABLE and FORCE, so from here on the policy applies
-- to the owner as well and a write cannot name a tenant the reader could not see.
SELECT apply_tenant_isolation('status_transition');

-- The assertion, and it is the same query `TenantIsolationStartupCheck` runs at every boot: a table that
-- carries `company_id` and is missing from the list is a table the application refuses to start over, and a
-- migration that armed the wrong table would otherwise print the same green as one that armed this one.
DO $$
DECLARE
    v_unprotected text;
BEGIN
    SELECT string_agg(t::text, ', ' ORDER BY t::text) INTO v_unprotected
      FROM unprotected_tenant_tables() t;

    IF v_unprotected IS NOT NULL THEN
        RAISE EXCEPTION
            'table(s) carrying company_id with no policy after V67 armed status_transition: %', v_unprotected;
    END IF;
END $$;
