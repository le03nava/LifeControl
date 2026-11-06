-- ============================================
-- V83 — the payment rows, their voids, and what makes a sale a financial record
--        (ADR-0002 decisions 5 and 7; T5 of odd/tasks/money-first-class.md)
-- ============================================
-- Decision 5, verbatim: «**A payment is a row, and a sale may have many.** Cash and card in one sale are
-- two rows, each with a `direction` of `CHARGE` or `REFUND`. A void is **its own appended row**, never a
-- status column and never a deletion: a payment is void if and only if a `sale_payment_void` row names it,
-- so the drawer's history is auditable and a void with no replacement is expressible — which the sale
-- requires, because a payment taken with the wrong tender has no replacement, only a correction. The void
-- row **is** the payment's transition.»
--
-- Four things land here, and the fourth is the one that makes the other three true rather than intended:
--
--   1. the composite key `sales_orders` needs for the cross-table FK (ADR-0002's `## Schema`);
--   2. `payment_methods.is_cash` with its backfill (**D-b**, decided 2026-11-05) — M4's "change only on
--      cash" needs a fact, and the catalog had none;
--   3. `sale_payment` and `sale_payment_void`, tenant tables armed by `apply_tenant_isolation` in the file
--      that creates them;
--   4. **append-only on both**, as the trigger *and* the `REVOKE` — because `V52:187` and `V52:196-197`
--      grant SELECT/INSERT/UPDATE/DELETE on every table present and future, so the absence of a grant is
--      not a restriction and the restrictive half has to be written.
--
-- No `NO FORCE` window: the two new tables are born empty, and `payment_methods` is a platform catalog with
-- no policy at all (`V1:302-309`: no `company_id`). A window exists to let a backfill read across tenants
-- (`V80:57-78`); nothing here needs one.
--
-- Version: `V82` belongs to the sibling unit that adds the tax catalog (`odd/tasks/money-first-class.md`,
-- T4). Flyway tolerates the gap until that unit lands, and the head this tree derives is the maximum of the
-- versions in the directory, so nothing here depends on 82 existing.

-- ─── 1. the key the FK below points at ─────────────────────────────────────────────────────────────
-- A composite foreign key needs a unique constraint on the exact column pair it references. `V64` added
-- this pair for `purchase_orders` (`V64:253-254`) and for `goods_receipts`; `sales_orders` was not in that
-- unit because it had no consumer for it until now.
ALTER TABLE sales_orders
    ADD CONSTRAINT uq_sales_orders_company_id UNIQUE (company_id, id);

COMMENT ON CONSTRAINT uq_sales_orders_company_id ON sales_orders IS
    'The target of fk_payment_order_same_company (ADR-0002): a payment states its company and its order '
    'together, so the schema refuses a payment that names another tenant''s order instead of hiding it '
    'behind the policy — a filtered row is not a rejected write (M9).';

-- ─── 2. is_cash, and the backfill that makes it true (D-b) ─────────────────────────────────────────
ALTER TABLE payment_methods ADD COLUMN is_cash BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN payment_methods.is_cash IS
    'ADR-0002 decision 5 / M4: change is returned only for a cash method. A platform fact, not a tenant '
    'one: payment_methods is a global catalog (V1:302-309), and the guard of ADR-0004 reads it.';

-- The backfill is by the seed's **exact name** (`V3:385`), and the notice is the whole point of D-b: a
-- catalog where `Efectivo` was renamed would silently leave every cash sale unable to return change, and
-- the cashier would find out at the counter instead of here. A `RAISE NOTICE` and not an exception: a
-- renamed catalog is a configuration the operator has to describe, not a reason to abort a deploy.
DO $$
DECLARE
    v_marked bigint;
BEGIN
    UPDATE payment_methods
       SET is_cash = true
     WHERE lower(payment_method_name) = lower('Efectivo');

    GET DIAGNOSTICS v_marked = ROW_COUNT;

    IF v_marked = 0 THEN
        RAISE NOTICE
            'V83: no payment method named Efectivo was found, so nothing was marked as cash. M4 refuses '
            'change on every method until at least one row has is_cash = true: mark the real cash method '
            'with UPDATE payment_methods SET is_cash = true WHERE id = ... (D-b).';
    ELSE
        RAISE NOTICE 'V83: % payment method(s) marked as cash by name (D-b).', v_marked;
    END IF;
END $$;

-- ─── 3. the payment row ────────────────────────────────────────────────────────────────────────────
CREATE TABLE sale_payment (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id      UUID NOT NULL,
    sales_order_id  UUID NOT NULL,
    method_id       UUID NOT NULL REFERENCES payment_methods (id),
    -- The sign is stated once, by the direction, and never by a negative amount: `amount > 0` plus this
    -- check keeps every sum a sum of positives and one `CASE`.
    direction       VARCHAR(8) NOT NULL CHECK (direction IN ('CHARGE', 'REFUND')),
    amount          NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    -- Cash only: how much the customer handed over, and how much came back. Null on a card or a transfer,
    -- which is why they are nullable and why the check below refuses half a pair.
    tendered_amount NUMERIC(19,4),
    change_amount   NUMERIC(19,4),
    reference       VARCHAR(100),
    paid_at         TIMESTAMPTZ NOT NULL,
    -- Who took it. Not the order's user_id: that column is mutable and a drawer report cites the person.
    actor           VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Tenant and order pinned together by the schema: a mis-stated company_id on a payment would otherwise
    -- be *hidden* by ADR-0005's policy rather than rejected (M9).
    CONSTRAINT fk_payment_order_same_company FOREIGN KEY (company_id, sales_order_id)
        REFERENCES sales_orders (company_id, id),
    -- The target of the void table's two keys.
    CONSTRAINT uq_sale_payment_company_id UNIQUE (company_id, id),
    -- "Change only on cash" is ADR-0004's guard, because a CHECK cannot read the payment-method catalog
    -- (a STABLE function is not admissible inside one). What stays here is the arithmetic: tender and
    -- change agree with the amount, or neither is stated.
    CONSTRAINT ck_payment_tender CHECK (
        (direction = 'REFUND' AND tendered_amount IS NULL AND change_amount IS NULL)
        OR (direction = 'CHARGE' AND (
               (tendered_amount IS NULL AND change_amount IS NULL)
            OR (tendered_amount IS NOT NULL AND change_amount IS NOT NULL
                AND tendered_amount >= amount
                AND change_amount = tendered_amount - amount)))));

COMMENT ON TABLE sale_payment IS
    'ADR-0002 decision 5: one row per tender. A sale with cash and card is two rows, a refund is a row with '
    'direction REFUND, and a void is a row in sale_payment_void — never an UPDATE and never a DELETE.';
COMMENT ON COLUMN sale_payment.paid_at IS
    'When the money was taken. The migration that backfills historical payments must use '
    'coalesce(o.updated_at, o.order_date) and never now(), which would date every historical payment the '
    'day of the deploy (ADR-0002 step 4).';

CREATE INDEX idx_sale_payment_order ON sale_payment (company_id, sales_order_id);
CREATE INDEX idx_sale_payment_day ON sale_payment (company_id, paid_at);
CREATE INDEX idx_sale_payment_method ON sale_payment (company_id, method_id, paid_at);

-- ─── 4. the void, which is the payment's only transition ──────────────────────────────────────────
CREATE TABLE sale_payment_void (
    -- The primary key *is* the payment: a payment can be void once, and stating that as the key is cheaper
    -- and stronger than a second unique constraint over the same column.
    payment_id             UUID PRIMARY KEY,
    company_id             UUID NOT NULL,
    -- NULL is a legitimate void: a wrong tender has no replacement, only a correction.
    replacement_payment_id UUID,
    actor                  VARCHAR(255) NOT NULL,
    reason                 VARCHAR(255) NOT NULL,
    occurred_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_void_payment_same_company FOREIGN KEY (company_id, payment_id)
        REFERENCES sale_payment (company_id, id),
    CONSTRAINT fk_void_replacement_same_company FOREIGN KEY (company_id, replacement_payment_id)
        REFERENCES sale_payment (company_id, id),
    CONSTRAINT ck_void_replacement_differs CHECK (replacement_payment_id IS DISTINCT FROM payment_id));

COMMENT ON TABLE sale_payment_void IS
    'ADR-0002 decision 5: the row that makes a payment void, and the payment''s transition. ADR-0004''s '
    'status_transition records documents that have a state column; a payment deliberately has none.';

-- ─── 5. append-only on both, as the trigger AND the grant ─────────────────────────────────────────
-- The two halves answer two different ways of writing: the trigger refuses `lifecontrol_owner` and the
-- superuser, and the REVOKE refuses the pool's role, which is the one the application actually connects
-- with. `V52:187` and `V52:196-197` hand `lifecontrol_app` all four privileges on every table present and
-- future, so omitting a grant would leave UPDATE and DELETE inherited — the restrictive half has to be
-- written, per table, on purpose.
CREATE FUNCTION sale_payment_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only (ADR-0002 decision 5): % is refused. A void is a new row in sale_payment_void, and a payment is never edited or deleted',
        TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_sale_payment_append_only
    BEFORE UPDATE OR DELETE ON sale_payment
    FOR EACH ROW EXECUTE FUNCTION sale_payment_append_only();

CREATE TRIGGER trg_sale_payment_void_append_only
    BEFORE UPDATE OR DELETE ON sale_payment_void
    FOR EACH ROW EXECUTE FUNCTION sale_payment_append_only();

REVOKE UPDATE, DELETE ON sale_payment FROM lifecontrol_app;
GRANT SELECT, INSERT ON sale_payment TO lifecontrol_app;

REVOKE UPDATE, DELETE ON sale_payment_void FROM lifecontrol_app;
GRANT SELECT, INSERT ON sale_payment_void TO lifecontrol_app;

-- ─── 6. tenancy, in the file that creates them ────────────────────────────────────────────────────
-- `sales_orders` is already armed: `V65__Document_identity_render.java:265-266` applies the policy to it
-- together with `document_sequence` and `goods_receipts`. These two are new, so they are armed here.
SELECT apply_tenant_isolation('sale_payment');
SELECT apply_tenant_isolation('sale_payment_void');

-- ─── 7. the self-checks: the four claims above, read back ─────────────────────────────────────────
DO $$
DECLARE
    v_armed      bigint;
    v_bad_priv   text;
    v_tender     bigint;
BEGIN
    SELECT count(*) INTO v_armed
      FROM tenant_table
     WHERE table_name IN ('sale_payment'::regclass, 'sale_payment_void'::regclass)
       AND policy_kind = 'tenant'
       AND key_column = 'company_id';

    IF v_armed <> 2 THEN
        RAISE EXCEPTION
            'V83 armed % of the two payment tables as tenant/company_id and expected both. '
            'apply_tenant_isolation writes that row; a missing one leaves the table readable across tenants '
            'with no error anywhere.', v_armed;
    END IF;

    -- The REVOKE is the half that a future edit can silently drop, because the default privileges would
    -- hand the pool its write back without a word. This reads the grant back instead of trusting the line.
    SELECT string_agg(c.relname, ', ') INTO v_bad_priv
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public'
       AND c.relname IN ('sale_payment', 'sale_payment_void')
       AND (has_table_privilege('lifecontrol_app', c.oid, 'UPDATE')
         OR has_table_privilege('lifecontrol_app', c.oid, 'DELETE')
         OR NOT has_table_privilege('lifecontrol_app', c.oid, 'SELECT')
         OR NOT has_table_privilege('lifecontrol_app', c.oid, 'INSERT'));

    IF v_bad_priv IS NOT NULL THEN
        RAISE EXCEPTION
            'V83 read the wrong grant set on: %. The pool must hold SELECT and INSERT and neither UPDATE '
            'nor DELETE: V52:187 and :196-197 grant all four on every new table, so both halves are '
            'written here and this check is what keeps them.', v_bad_priv;
    END IF;

    -- M4's arithmetic: the constraint is read back from the catalog instead of probed with a row, because a
    -- probe that inserts `SELECT ... FROM sales_orders LIMIT 1` passes vacuously on the empty database this
    -- file runs against — the failure `odd/tasks/v64-vacuous-self-check.md` exists to name. The behaviour of
    -- the check (a REJECTED write) is a scratch-database case's job, where a company, an order and a method
    -- exist to be refused.
    SELECT count(*) INTO v_tender
      FROM pg_constraint
     WHERE conname = 'ck_payment_tender'
       AND conrelid = 'sale_payment'::regclass
       -- A regexp and not a LIKE: PostgreSQL renders the definition with its own parentheses and
       -- spacing, so `change_amount = (tendered_amount - amount)` is the shape it prints.
       AND pg_get_constraintdef(oid) ~ 'change_amount = \(*tendered_amount - amount\)*';

    IF v_tender <> 1 THEN
        RAISE EXCEPTION
            'V83 could not read ck_payment_tender stating change_amount = tendered_amount - amount. That '
            'equality is the half of M4 a CHECK can carry; without it a card payment can hold a change value '
            'and nothing refuses it.';
    END IF;
END $$;
