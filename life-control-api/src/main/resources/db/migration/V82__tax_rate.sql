-- ============================================
-- V82 — the tax catalog and the tax rate table (ADR-0002 decision 6; T4 of odd/tasks/money-first-class.md)
-- ============================================
-- Decision 6, verbatim: «**Tax is a rate snapshot, not a calculation in the UI.** The rate is resolved
-- server-side from the latest `tax_rate` row with `effective_from ≤ the document's date` for the company
-- and tax code, and is copied onto the line at write time, so a rate change never rewrites history.»
--
-- Two tables, and the difference between them is the whole point of D-d (decided 2026-11-05):
--
--   * `tax_code` is a **platform catalog**, exactly like `currency` (`V57:60-67`): no `company_id`, no
--     policy, no `tenant_table` row. It exists because CFDI separates **Tasa 0%** from **Exento** and both
--     are `rate = 0` — a free-text code cannot tell them apart, and a typo in one would tax a line at zero
--     without raising anything anywhere.
--   * `tax_rate` is a **tenant table**: a rate belongs to the company that files it, with its own effective
--     date. It is armed by `apply_tenant_isolation` in this same file, which is the only way a table gets a
--     policy (`V52:100-101`), and that call writes the `tenant_table` row itself (`V52:161-166`).
--
-- Neither table needs a `NO FORCE` window: both are empty when they are created, so nothing here reads a
-- row written before this file. The window exists for a backfill over a table that already has data
-- (`V80:57-78`, `V81:42-57`) and a window that does not need to be open is a hole, not a habit.

-- ─── 1. the platform catalog, its vocabulary and the read that nobody has yet ─────────────────────
-- `VARCHAR(20)` and not `CHAR`: it has to equal `sales_order_items.tax_code VARCHAR(20)` (`V81:87`), which
-- is the snapshot a line keeps. Two different types would make the same code compare differently in the
-- catalog and on the document.
CREATE TABLE tax_code (
    code    VARCHAR(20) PRIMARY KEY,
    label   VARCHAR(120) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true);

COMMENT ON TABLE tax_code IS
    'ADR-0002 decision 6 / D-d: the platform tax-code catalog, the same shape as currency (V57): no '
    'company_id, no policy, seeded by migration and with no ABM. A code names WHAT the tax is, never how '
    'much: the rate belongs to tax_rate, because the rate is the company''s and changes with an effective '
    'date while the code does not.';
COMMENT ON COLUMN tax_code.label IS
    'Presentation only. `code` is what a report filters and what a line snapshots.';

-- Four codes, and each one earns its row:
--   `NA`     — `V81:87` defaults every existing line to it, so the catalog cannot omit it.
--   `IVA`    — the taxed bucket: how much is not stated here, it is resolved from `tax_rate`.
--   `TASA_0` — CFDI's "Tasa 0%": a rate that is known to be zero and is declared as such.
--   `EXENTO` — CFDI's "Exento": no tax applies at all. Same zero, different meaning from `TASA_0`, which
--              is the distinction D-d was decided on.
INSERT INTO tax_code (code, label, enabled) VALUES
    ('NA', 'No aplica', true),
    ('IVA', 'IVA', true),
    ('TASA_0', 'Tasa 0%', true),
    ('EXENTO', 'Exento', true);

-- ─── 2. the tenant rate table ──────────────────────────────────────────────────────────────────────
CREATE TABLE tax_rate (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id     UUID NOT NULL REFERENCES companies (id),
    tax_code       VARCHAR(20) NOT NULL REFERENCES tax_code (code),
    rate           NUMERIC(9,6) NOT NULL CHECK (rate >= 0 AND rate <= 1),
    effective_from DATE NOT NULL,
    enabled        BOOLEAN NOT NULL DEFAULT true,
    -- Two rates for one company, code and date would make "the latest rate" depend on insertion order,
    -- which is the one thing a resolver must never leave to chance.
    CONSTRAINT uq_tax_rate_company_code_from UNIQUE (company_id, tax_code, effective_from));

COMMENT ON TABLE tax_rate IS
    'ADR-0002 decision 6: a rate is resolved by (company_id, tax_code) taking the latest effective_from '
    'that is <= the document date, and copied onto the line. A rate change appends a row; it never '
    'rewrites the lines already written.';
COMMENT ON COLUMN tax_rate.rate IS
    'NUMERIC(9,6) because `sales_order_items.tax_rate` is (V81:88) and ck_item_tax compares the two: a '
    'narrower type here would round the copy and make the check disagree with the row it came from.';
COMMENT ON COLUMN tax_rate.effective_from IS
    'The first day this rate applies. The resolution is `effective_from <= the document date`, so the '
    'document date decides and today never does.';

-- The unique key already indexes the (company_id, tax_code) prefix the resolution reads, so a second
-- index here would duplicate it.
SELECT apply_tenant_isolation('tax_rate');

-- ─── 3. the self-checks: the two tables are what the decision says, and different from each other ──
DO $$
DECLARE
    v_codes      bigint;
    v_armed      bigint;
    v_kind       text;
    v_key        text;
    v_catalog_rls boolean;
BEGIN
    SELECT count(*) INTO v_codes FROM tax_code;
    IF v_codes <> 4 THEN
        RAISE EXCEPTION
            'V82 seeded % tax codes and the vocabulary is exactly four (NA, IVA, TASA_0, EXENTO). A count '
            'that moved means the INSERT above did not run, not that the catalog is richer.', v_codes;
    END IF;

    -- The tenant table must be armed, and armed as a tenant keyed by company_id: a policy over another
    -- column would filter the wrong thing and still look armed.
    SELECT count(*), min(policy_kind), min(key_column)
      INTO v_armed, v_kind, v_key
      FROM tenant_table
     WHERE table_name = 'tax_rate'::regclass;

    IF v_armed <> 1 OR v_kind <> 'tenant' OR v_key <> 'company_id' THEN
        RAISE EXCEPTION
            'V82 expects one tenant_table row for tax_rate as tenant/company_id and read % row(s) as %.%. '
            'apply_tenant_isolation writes that row; a missing one leaves the table readable across '
            'tenants with no error anywhere.', v_armed, v_kind, v_key;
    END IF;

    -- And the platform catalog must NOT be armed. `currency` is the precedent (`V57:37-48`): a policy over
    -- a table with no company_id would be a predicate over a column that does not exist, and arming it
    -- would also claim it belongs to the tenant machinery it is deliberately outside of.
    SELECT relrowsecurity INTO v_catalog_rls
      FROM pg_class
     WHERE oid = 'tax_code'::regclass;

    IF v_catalog_rls THEN
        RAISE EXCEPTION
            'V82 read row security ENABLED on tax_code, which is a platform catalog with no company_id. '
            'Only tax_rate is a tenant table in this file.';
    END IF;

    IF EXISTS (SELECT 1 FROM tenant_table WHERE table_name = 'tax_code'::regclass) THEN
        RAISE EXCEPTION
            'V82 read a tenant_table row for tax_code. tax_code is shared like currency, and a shared row '
            'here would imply a tenancy column the table is not supposed to have.';
    END IF;
END $$;

-- ─── 4. what this file deliberately does NOT do ───────────────────────────────────────────────────
-- `sales_order_items.tax_code` gets **no** foreign key to this catalog, by decision (D-d): the line keeps
-- a text snapshot, and a snapshot has to survive the retirement of the code it names. An FK would turn
-- "this code is withdrawn" into "these historical documents cannot exist".
--
-- Nothing is granted and nothing is revoked. `V52:190-199` grants SELECT/INSERT/UPDATE/DELETE on every
-- table, present and future, through the default privileges; the `REVOKE` pattern exists for append-only
-- tables only (`V60:286-296`, `V67:92-112`), and neither of these two is one: a rate is corrected by
-- appending a new effective date, and the row stays.
