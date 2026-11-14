-- ============================================
-- V102 — S1/T1: the issuer's fiscal regime and its place of issue
-- ============================================
-- `odd/tasks/cfdi-fiscal-document.md`, spec S1 and decision L6(a) of the same record:
--
--   «The issuer states its fiscal regime and its place of issue: `companies` gains a regime with a closed
--     vocabulary and a postal code, both required to issue and neither required to sell.»
--
-- Two nullable columns, and each one is a decision:
--
--   * `regimen_fiscal VARCHAR(3)` — SAT's `c_RegimenFiscal` code, in a **named** `CHECK` over the closed
--     vocabulary the current catalog carries (601, 603, 605, 606, 607, 608, 610, 611, 612, 614, 615, 616,
--     620, 621, 622, 623, 624, 625, 626). The mirror in code is
--     `com.lifecontrol.api.company.model.RegimenFiscal`, the shape `ProductAttributeKind` already has beside
--     `ck_product_attribute_definitions_kind`: the database is the authority for every writer that bypasses
--     the API, and the enum is what lets the API answer a 400 that names the field instead of a constraint
--     violation that names only the rule. The retired RENAPO-era codes (600, 602, 604, 609, 613) are
--     deliberately absent — version 4.0 replaced them and re-admitting one would let an issuer declare a
--     regime the authority no longer accepts — and no code is invented to mean "not declared": NULL is.
--   * `fiscal_zip_code VARCHAR(5)` — the **place of issue** (S9 freezes it on the document at stamp time),
--     and explicitly not the mailing address. Reusing `companies.zip_code` was refused (L6(b)): that column
--     is a twenty-character mailing field whose shape nothing checks, and a fiscal fact sharing a column
--     with a mailing address is one nobody can rely on later.
--
-- WHY BOTH COLUMNS ARE NULLABLE, AND WHY THIS FILE NEEDS NO BACKFILL AND NO `NO FORCE` WINDOW. S1 is
-- explicit that both facts are "required to issue and neither required to sell", so a company that declares
-- neither is a complete row: it sells exactly as it does today and it cannot issue, which is a state the
-- predicate admits (`regimen_fiscal IS NULL OR …`). V57 needed its `NO FORCE ROW LEVEL SECURITY` window for
-- the opposite reason: `companies.currency` is `NOT NULL`, so the migration had to reach **every existing
-- row** with an `UPDATE` and then `SET NOT NULL`, and `V53:108` arms `companies` with `ENABLE` + `FORCE` —
-- `FORCE` is what makes the policy apply to the table's **owner**, which is the role Flyway runs as, so that
-- backfill would have matched zero rows and reported success. Nothing here reads a row: two `ADD COLUMN`s and
-- two constraints that admit NULL are structural changes, and a structural change is not affected by a policy
-- that filters rows. Opening a window this file does not need would be a hole, not a habit (the same sentence
-- `V82` writes about the two tables it creates).
--
-- WHY THE CONSTRAINTS ARE NAMED. A violation must name the rule it broke, like
-- `ck_product_categories_sat_code_format` (V34) and `fk_product_categories_parent_same_company` (V31): an
-- operator who reads `ck_companies_regimen_fiscal` knows to look at the regime, and a reader of
-- `information_schema` can assert the rule exists without parsing a message.
--
-- WHY `VARCHAR` AND NOT `CHAR` (which `companies.currency` uses). The currency code is a fixed-width ISO 4217
-- value; these two are a catalog code and a postal code whose widths are exactly what the authority declares
-- (3 and 5), and `CHAR` pads on read — a padded `'601 '` is not a code the SAT issues and would make the
-- `IN` list above compare the wrong thing.
--
-- Forward-only and additive: dropping the two constraints and the two columns restores V57's shape exactly,
-- and no other column, policy or grant is touched.
-- ============================================

ALTER TABLE companies ADD COLUMN regimen_fiscal VARCHAR(3);
ALTER TABLE companies ADD COLUMN fiscal_zip_code VARCHAR(5);

ALTER TABLE companies
    ADD CONSTRAINT ck_companies_regimen_fiscal
    CHECK (regimen_fiscal IS NULL OR regimen_fiscal IN (
        '601', '603', '605', '606', '607', '608', '610', '611', '612', '614',
        '615', '616', '620', '621', '622', '623', '624', '625', '626'));

ALTER TABLE companies
    ADD CONSTRAINT ck_companies_fiscal_zip_code
    CHECK (fiscal_zip_code IS NULL OR fiscal_zip_code ~ '^[0-9]{5}$');

COMMENT ON COLUMN companies.regimen_fiscal IS
    'S1: the issuer''s SAT regime (c_RegimenFiscal), from the closed vocabulary ck_companies_regimen_fiscal '
    'mirrors. Required to issue a fiscal document, never required to sell: NULL means the issuer has not '
    'declared it yet.';
COMMENT ON COLUMN companies.fiscal_zip_code IS
    'S1/S9: the place of issue the fiscal document freezes at stamp time, five digits. Deliberately NOT '
    'companies.zip_code, which is a twenty-character mailing field with no shape.';

-- ─── the self-check, and what it deliberately does not assert ────────────────────────────────────────
-- Structural only. A row-count self-check ("every company still has NULL and no row was backfilled") cannot
-- be written here honestly: `companies` is `ENABLE` + `FORCE` since `V53:108`, so the owner that runs this
-- file reads zero rows of it and a count would pass over no data at all — the mode of failure `V57` opened
-- its window to avoid and `V64:36` records. What a structural read CAN prove is that the two named rules
-- exist, that they admit NULL, and that this file did not disturb the tenancy it was not about.
DO $$
DECLARE
    v_policies integer;
    v_forced   boolean;
BEGIN
    IF (SELECT count(*) FROM pg_constraint
         WHERE conrelid = 'companies'::regclass
           AND conname IN ('ck_companies_regimen_fiscal', 'ck_companies_fiscal_zip_code')
           AND contype = 'c') <> 2 THEN
        RAISE EXCEPTION
            'V102 expected the two named CHECK constraints on companies and did not find both. The vocabulary '
            'is the API''s half of the rule and the schema''s half: without them a writer that bypasses the '
            'API can store a regime the SAT catalog does not have.';
    END IF;

    -- `pg_get_constraintdef` is read instead of assuming it: `NULL` must be admitted by both, because both
    -- facts are optional to sell (S1). A constraint that dropped the `IS NULL` branch would make every
    -- company created before this migration unsavable and every sale path fail at the first update. The
    -- match is on the substring and not on the exact rendering, because PostgreSQL rewrites the expression
    -- with its own parentheses (`((regimen_fiscal IS NULL) OR (…))`) and a reader pinned to the source text
    -- would pass or fail on that formatting rather than on the rule.
    IF (SELECT count(*) FROM pg_constraint
         WHERE conrelid = 'companies'::regclass
           AND conname IN ('ck_companies_regimen_fiscal', 'ck_companies_fiscal_zip_code')
           AND pg_get_constraintdef(oid) LIKE '%IS NULL%') <> 2 THEN
        RAISE EXCEPTION
            'V102 read a fiscal constraint that does not admit NULL. Both facts are required to issue and '
            'neither is required to sell (S1), so a constraint without the NULL branch turns "this issuer '
            'has not declared its regime" into an error on every existing row.';
    END IF;

    -- And the columns themselves are nullable, which is the other half of the same promise: a constraint that
    -- admits NULL on a `NOT NULL` column would admit nothing at all.
    IF (SELECT count(*) FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'companies'
           AND column_name IN ('regimen_fiscal', 'fiscal_zip_code')
           AND is_nullable = 'YES') <> 2 THEN
        RAISE EXCEPTION
            'V102 read companies.regimen_fiscal or companies.fiscal_zip_code as NOT NULL. S1 makes both '
            'facts optional to sell, so a required column would refuse every company that has not declared '
            'its fiscal identity yet.';
    END IF;

    -- The tenancy this file is not about: still ENABLE + FORCE, still one policy, still keyed by `id`.
    SELECT count(*), bool_and(c.relforcerowsecurity)
      INTO v_policies, v_forced
      FROM pg_policy p
      JOIN pg_class c ON c.oid = p.polrelid
     WHERE p.polrelid = 'companies'::regclass;

    IF v_policies IS DISTINCT FROM 1 OR v_forced IS DISTINCT FROM true THEN
        RAISE EXCEPTION
            'V102 read companies as % policies with FORCE=%. This file adds two nullable columns and has no '
            'reason to touch the tenant machinery; a policy that moved here is an unbounded cross-tenant read.',
            v_policies, v_forced;
    END IF;
END $$;
