-- ============================================
-- V57 — ADR-0002 decision 2 (D-c): a currency is a row, and a company states its own
-- ============================================
-- ADR-0002 is accepted (`docs/adr/0002-money-first-class.md`). This file is its migration step 1 and
-- nothing more: the platform `currency` catalog, the one row it seeds, and the tenant column that owns
-- the value. The breakdown, `tax_rate`, `sale_payment`, the scale change and every other slice of
-- `odd/tasks/money-first-class.md` are deliberately absent — they are T2 and later.
--
-- THE NUMBER: `V54`–`V56` belong to `odd/tasks/catalog-identity.md`, which this branch is stacked on,
-- so this file takes `V57` and the two units never collide. Nothing here reads a table those three
-- create or drop, which is what lets this migration be applied on its own (`V53` is the last version it
-- depends on) as well as on top of them.
--
-- THE CODE IS NOT INVENTED — it is the only one the product states anywhere today:
--   * `life-control-app-angular/src/app/app.config.ts:33` pins `DEFAULT_CURRENCY_CODE` to `'MXN'` on
--     `LOCALE_ID 'es-MX'` (`:32`), the POS's own line math repeats it
--     (`sales-order-item-table.ts:118`), and no migration in the tree names a currency at all (`grep -i
--     currency` over `db/migration/` returns `V22`'s word "concurrency" and nothing else). One code, so
--     one seed row: a second row is a product decision nobody took, and this catalog exists so that
--     decision is an `INSERT` later instead of a schema change.
--   * `minor_units` is the number of decimals the product already prints — the Angular `currency` pipe
--     renders two — and `symbol` is what that pipe shows under `es-MX`, where `MXN` is `$`. Neither is
--     derived from the ADR's prose: decision 1 puts the rounding at the document boundary with the
--     currency's own `minor_units`, so these two values are what makes that rounding expressible.
--
-- WHY THREE STEPS INSTEAD OF THE ADR'S ONE `ADD COLUMN … NOT NULL` — this is the trap, not a style
-- choice, and the accepted ADR's `## Schema` block cannot execute as written:
--   * `ADD COLUMN currency CHAR(3) NOT NULL REFERENCES currency (code)` refuses on any `companies` table
--     that has a row: PostgreSQL rejects a `NOT NULL` column added without a `DEFAULT` the moment one
--     row exists. Production has rows.
--   * A `DEFAULT` would execute and is exactly what D-c forbids: a default writes one country's
--     currency onto another's sales and never says so, the failure class this repository treats as a
--     defect. So the column is added nullable, every existing company is backfilled with the seeded
--     code, and only then `SET NOT NULL`. Every company that exists today has been operating in the one
--     currency the product states, so the backfill records a fact instead of choosing one; a company
--     created from now on states its currency in the request (`CompanyRequest.currency`, required, no
--     default) and a missing value is a 400 naming `currency`, not a guess.
--
-- WHY THE ROW-LEVEL SECURITY WINDOW — ADR-0005 correction 3, the same trap the catalog-identity
-- migrations (`V54`–`V56`, landed by `odd/tasks/catalog-identity.md`) document:
--   `V52__tenant_isolation_machinery.sql:152-153` calls `ENABLE` **and `FORCE`** on every table it arms,
--   and `V53__tenant_isolation_policies.sql:108` arms `companies` (its policy keys on `id`, so the
--   backfill states no scope at all: `companies` has no `company_id` and its tenant IS the row). `FORCE`
--   is what makes a policy apply to the table's **owner**, and the owner is the role Flyway runs as, so
--   the `UPDATE` below would match **zero rows and report success** — a green deploy over a column of
--   `NULL`s that fails later, in this same file, with a message naming no row. `NO FORCE` is the only
--   door a `NOSUPERUSER` owner has, it lasts this file's transaction, and it is closed — and re-checked
--   — before the file ends. The self-check in step 3 is the other half: it refuses a backfill that
--   reached nothing instead of reporting success, which is the defect ADR-0005 correction 3 records.
--
-- WHAT THIS FILE DOES NOT ARM: `currency` gets no policy and no `tenant_table` row. It is a platform
--   catalog with no `company_id` — the same treatment decision 2 gives it and ADR-0005 gives
--   `measure_units` — so there is no tenant column for `unprotected_tenant_tables()`
--   (`V52__tenant_isolation_machinery.sql:203`) or the startup check built on it to refuse. A policy here
--   would be a predicate over a column the table does not have. `companies.currency` inherits the
--   isolation `companies` already carries.
-- ============================================

-- ─── 1. the catalog, exactly the shape ADR-0002's `## Schema` block states ──────────────────────
CREATE TABLE currency (
    code         CHAR(3) PRIMARY KEY,           -- ISO 4217
    minor_units  SMALLINT NOT NULL CHECK (minor_units BETWEEN 0 AND 4),
                                                -- 2 for ARS/MXN/USD, 0 for JPY, 3 for KWD/BHD/TND
    symbol       VARCHAR(6),
    enabled      BOOLEAN NOT NULL DEFAULT true);

COMMENT ON TABLE currency IS
    'ADR-0002 decision 2: the platform currency catalog. No company_id and no policy — a currency is not '
    'a tenant fact; the operating currency of a tenant lives on companies.currency.';

COMMENT ON COLUMN currency.minor_units IS
    'ADR-0002 decision 1: how many decimals the currency is rounded and printed with. The rounding happens '
    'once, at the document boundary, against this number.';

-- ─── 2. the seed: the one code the product states today, and nothing else ───────────────────────
INSERT INTO currency (code, minor_units, symbol, enabled) VALUES ('MXN', 2, '$', true);

-- ─── 3. the tenant column: add nullable, backfill, then constrain ───────────────────────────────
-- Nullable first, because `NOT NULL` without a `DEFAULT` cannot be added over rows, and no `DEFAULT`
-- because D-c forbids one (see the header). The next two statements are exactly the repair.
ALTER TABLE companies
    ADD COLUMN currency CHAR(3) REFERENCES currency (code);

-- The window: without it, the `UPDATE` and every statement after it match **zero rows**, silently.
ALTER TABLE companies NO FORCE ROW LEVEL SECURITY;

UPDATE companies SET currency = 'MXN' WHERE currency IS NULL;

-- The self-check, while the window is still open, and it is the reason this file is not a `SET NOT NULL`
-- and a hope: a backfill that read nothing raises nothing on its own. Two refusals, because there are two
-- ways this file does nothing — the window did not take, or the `UPDATE` did not reach a row.
DO $$
DECLARE
    v_forced  boolean;
    v_missing text;
BEGIN
    SELECT c.relforcerowsecurity INTO v_forced
      FROM pg_class c
     WHERE c.relname = 'companies' AND c.relkind = 'r';

    IF v_forced THEN
        RAISE EXCEPTION
            'V57 could not open its NO FORCE window on companies: every statement below would match zero '
            'rows and report success, leaving every existing company without a currency. See ADR-0005 '
            'correction 3, and the NO FORCE window the catalog-identity migration V54 uses.';
    END IF;

    SELECT string_agg(c.company_key, ', ' ORDER BY c.company_key)
      INTO v_missing
      FROM companies c
     WHERE c.currency IS NULL;

    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION
            'V57 backfilled companies.currency but these companies are still unset: %. Every company that '
            'exists has been operating in the one currency the product states (MXN), so the backfill '
            'records that fact and invents nothing; a row it did not reach means the statement read no '
            'row — check the NO FORCE window at the top of this file.', v_missing;
    END IF;
END $$;

-- Nothing is left to guess, so the column can now state what it always meant.
ALTER TABLE companies ALTER COLUMN currency SET NOT NULL;

COMMENT ON COLUMN companies.currency IS
    'ADR-0002 decision 2: the company''s operating currency, a reference to the platform catalog. Stated '
    'by the caller when the company is created — there is deliberately no DEFAULT, because a default '
    'would write one country''s currency onto another''s sales and never say so.';

-- ─── 4. the window closes, and a forgotten close fails this file instead of the next one ────────
ALTER TABLE companies FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    v_unarmed text;
BEGIN
    SELECT string_agg(c.relname, ', ' ORDER BY c.relname)
      INTO v_unarmed
      FROM pg_class c
     WHERE c.relname = 'companies'
       AND c.relkind = 'r'
       AND NOT (c.relrowsecurity AND c.relforcerowsecurity);

    IF v_unarmed IS NOT NULL THEN
        RAISE EXCEPTION
            'V57 opened a NO FORCE window and did not close it on: %. That table is readable by its owner '
            'without a tenant scope, which is the state ADR-0005 correction 3 refuses.', v_unarmed;
    END IF;
END $$;
