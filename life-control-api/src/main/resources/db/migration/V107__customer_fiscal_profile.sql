-- ============================================
-- V107 — S2/T2: the receiver's fiscal profile on `customers`
-- ============================================
-- `odd/tasks/cfdi-fiscal-document.md`, spec S2, decisions L8(a)–L8(c) and L8(e) of the same record, and
-- decision 5 of `docs/adr/0007-fiscal-document-is-separate.md`:
--
--   «The receiver carries a fiscal profile, not a name and an identifier: legal name, regime, fiscal address
--     with a postal code, and a default use — and `rfc`, when present, is validated for shape. The profile is
--     required **at issue**, not at the till: a cash sale needs none and an invoice cannot exist without one.»
--
-- Seven nullable columns, and each one is a decision:
--
--   * `legal_name VARCHAR(255)` — the receiver's name as the authority states it on the document
--     (`fiscal_document.receptor_name` freezes it at stamp time). Deliberately not `customers.name`, which is
--     the trade name an operator recognizes in a list and may be a nickname: a fiscal fact that shares a
--     column with a mailing name is one nobody can rely on afterwards, the same refusal L6(b) made about
--     `companies.zip_code`.
--   * `regimen_fiscal VARCHAR(3)` — SAT's `c_RegimenFiscal` code, in a **named** `CHECK` over the closed
--     vocabulary `ck_companies_regimen_fiscal` (V102) already declares: 601, 603, 605, 606, 607, 608, 610, 611,
--     612, 614, 615, 616, 620, 621, 622, 623, 624, 625, 626. The mirror in code is
--     `com.lifecontrol.api.fiscal.model.RegimenFiscal` — the **same** enum the issuer uses, moved to the
--     `fiscal` package by L8(a) precisely because this column is its second user: two copies, one for the
--     issuer and one for the receiver, would be two copies free to drift.
--   * `fiscal_street VARCHAR(255)` and `fiscal_state VARCHAR(120)` — optional parts of the fiscal domicile.
--     They are carried because a document states them, and they are optional because what a CFDI cannot omit
--     of the receiver's address is the postal code (L8(e)).
--   * `fiscal_zip_code VARCHAR(5)` — the receiver's fiscal postal code, five digits, in a named `CHECK`. The
--     ADR's `## Schema` block sketches `VARCHAR(10)`; L8(c) narrows it to five for the reason the issuer's
--     amendment already states (`V102`): the fact is five digits, and a ten-character column invites a postal
--     code with a hyphen that no stamp accepts.
--   * `fiscal_country_id UUID REFERENCES countries (id)` — optional, and named like every other foreign key
--     this table has, so a violation names the rule.
--   * `default_uso_cfdi VARCHAR(5)` — SAT's `c_UsoCFDI` code, in a **named** `CHECK` over the closed
--     vocabulary `com.lifecontrol.api.fiscal.model.UsoCfdi` declares: G01, G02, G03, I01–I08, D01–D10, P01,
--     S01, CP01, CN01. It is a **default** and not the document's use — the document slice copies it at stamp
--     time and may state another one — but a default the stamp cannot use is worse than no default (L8(b)),
--     so it is part of the required set and it is constrained here. The retired codes of the third revision
--     are deliberately absent, per decision 11: the codes belong to the authority, and a reissue is a
--     migration plus a constant, never a silent acceptance.
--
-- WHY EVERY COLUMN IS NULLABLE, AND WHY THIS FILE NEEDS NO BACKFILL AND NO `NO FORCE` WINDOW. S2 is explicit
-- that the profile is "required **at issue**, not at the till": a cash customer is a complete row with all
-- seven columns NULL, so there is nothing to backfill and no row to reach. That is also why no window is
-- opened here, unlike `V105` and `V106`, which read and wrote rows of a table armed with `ENABLE` + `FORCE`
-- and would have affected zero rows as the owner. `V102` states the same argument for the issuer's two
-- columns: a structural change (an `ADD COLUMN` plus constraints that admit NULL) is not filtered by a policy,
-- which filters rows. The constraint validation PostgreSQL performs when a `CHECK` is added scans the existing
-- rows, and every one of the three admits NULL, so even a scan that saw no row would judge the same thing —
-- there is no dataset here that could turn a green migration into a red one.
--
-- WHY THE ALL-OR-NOTHING RULE IS NOT A `CHECK`. "If a request states any of the four required facts it must
-- state all four" is a rule about a **request**, not about a row: a row with all seven NULL is legal (a cash
-- customer) and a row with all four stated is legal, so the only shape the schema could refuse is a partial
-- one, and the write path refuses it with a 400 that names each missing field (L8(e)). A `CHECK` here would
-- also have to be true of every row that already exists, and it would refuse a **half-filled draft** the API
-- would have to explain — the mode of failure the record refused: "a half profile waiting for the document to
-- refuse it looks like a profile in every listing and cannot be used in one".
--
-- WHY THE CONSTRAINTS ARE NAMED. A violation must name the rule it broke, the shape `V102`, `V34` and `V31`
-- already established: an operator who reads `ck_customers_default_uso_cfdi` knows to look at the use, and a
-- reader of `information_schema` can assert the rule exists without parsing a message.
--
-- WHAT THIS FILE DOES NOT DO. It does not touch `V105` or `V106`: the tenant column, the policy, the unique key
-- `uq_customers_company_id` and the composite pointers are the prerequisite unit's and stay exactly as they
-- are — this file adds seven nullable columns to an armed table and asserts that the arming did not move. It
-- does not rewrite `customers.rfc` (nothing to rewrite: the column was already nullable and the shape is
-- enforced at the API boundary by `@ValidRFC`, which L8(d) keeps as the tree's one RFC regex). It adds no
-- index: no query filters on these columns yet, and an index without a reader costs writes only.
--
-- Forward-only and additive: dropping the three constraints, the foreign key and the seven columns restores
-- V106's shape exactly.
-- ============================================

ALTER TABLE customers ADD COLUMN legal_name VARCHAR(255);
ALTER TABLE customers ADD COLUMN regimen_fiscal VARCHAR(3);
ALTER TABLE customers ADD COLUMN fiscal_street VARCHAR(255);
ALTER TABLE customers ADD COLUMN fiscal_zip_code VARCHAR(5);
ALTER TABLE customers ADD COLUMN fiscal_state VARCHAR(120);
ALTER TABLE customers ADD COLUMN fiscal_country_id UUID;
ALTER TABLE customers ADD COLUMN default_uso_cfdi VARCHAR(5);

ALTER TABLE customers
    ADD CONSTRAINT ck_customers_regimen_fiscal
    CHECK (regimen_fiscal IS NULL OR regimen_fiscal IN (
        '601', '603', '605', '606', '607', '608', '610', '611', '612', '614',
        '615', '616', '620', '621', '622', '623', '624', '625', '626'));

ALTER TABLE customers
    ADD CONSTRAINT ck_customers_default_uso_cfdi
    CHECK (default_uso_cfdi IS NULL OR default_uso_cfdi IN (
        'G01', 'G02', 'G03',
        'I01', 'I02', 'I03', 'I04', 'I05', 'I06', 'I07', 'I08',
        'D01', 'D02', 'D03', 'D04', 'D05', 'D06', 'D07', 'D08', 'D09', 'D10',
        'P01', 'S01', 'CP01', 'CN01'));

ALTER TABLE customers
    ADD CONSTRAINT ck_customers_fiscal_zip_code
    CHECK (fiscal_zip_code IS NULL OR fiscal_zip_code ~ '^[0-9]{5}$');

ALTER TABLE customers
    ADD CONSTRAINT fk_customers_fiscal_country
    FOREIGN KEY (fiscal_country_id) REFERENCES countries (id);

COMMENT ON COLUMN customers.legal_name IS
    'S2: the receiver''s legal name as a CFDI states it. Deliberately not customers.name, which is the trade '
    'name an operator recognizes and may be a nickname.';
COMMENT ON COLUMN customers.regimen_fiscal IS
    'S2: the receiver''s SAT regime (c_RegimenFiscal), from the closed vocabulary ck_customers_regimen_fiscal '
    'mirrors. The same catalog as companies.regimen_fiscal (V102). Required to issue, never required to sell.';
COMMENT ON COLUMN customers.fiscal_street IS
    'S2: the street and number of the fiscal domicile. Optional: what a CFDI cannot omit is the postal code.';
COMMENT ON COLUMN customers.fiscal_zip_code IS
    'S2: the receiver''s postal code, five digits. Narrowed from the ADR sketch''s VARCHAR(10) by decision '
    'L8(c): a ten-character column invites a postal code with a hyphen that no stamp accepts.';
COMMENT ON COLUMN customers.fiscal_state IS
    'S2: the state or province of the fiscal domicile. Optional, with fiscal_street.';
COMMENT ON COLUMN customers.fiscal_country_id IS
    'S2: the country of the fiscal domicile, when the receiver states one. Optional.';
COMMENT ON COLUMN customers.default_uso_cfdi IS
    'S2: the receiver''s default SAT use of the voucher (c_UsoCFDI), from the closed vocabulary '
    'ck_customers_default_uso_cfdi mirrors. A default the document proposes at stamp time, which may state '
    'another use for that document.';

-- ─── the self-check: the seven columns, the three named rules, and the tenancy this file is not about ──────
-- Structural only, exactly like `V102`'s: `customers` is `ENABLE` + `FORCE` since `V105`, so the owner that
-- runs this file reads zero rows of it and a row-count self-check would pass over no data at all.
DO $$
DECLARE
    v_columns  integer;
    v_checks   integer;
    v_policies integer;
    v_forced   boolean;
BEGIN
    SELECT count(*) INTO v_columns
      FROM information_schema.columns
     WHERE table_schema = 'public'
       AND table_name = 'customers'
       AND column_name IN ('legal_name', 'regimen_fiscal', 'fiscal_street', 'fiscal_zip_code',
                           'fiscal_state', 'fiscal_country_id', 'default_uso_cfdi')
       AND is_nullable = 'YES';

    IF v_columns <> 7 THEN
        RAISE EXCEPTION
            'V107 expected the seven fiscal profile columns on customers, nullable, and found % of 7. S2 makes '
            'the profile required at issue and never at the till, so a required column would refuse every '
            'cash customer this table already stores.', v_columns;
    END IF;

    -- The three rules exist and each admits NULL: a constraint without its `IS NULL` branch would make every
    -- existing customer unsavable, because all seven columns are NULL on every row that predates this file.
    -- `pg_get_constraintdef` is read instead of assumed, and matched on the substring rather than the exact
    -- rendering, because PostgreSQL rewrites the expression with its own parentheses.
    SELECT count(*) INTO v_checks
      FROM pg_constraint
     WHERE conrelid = 'customers'::regclass
       AND contype = 'c'
       AND conname IN ('ck_customers_regimen_fiscal', 'ck_customers_default_uso_cfdi',
                       'ck_customers_fiscal_zip_code')
       AND pg_get_constraintdef(oid) LIKE '%IS NULL%';

    IF v_checks <> 3 THEN
        RAISE EXCEPTION
            'V107 read % of the three named CHECK constraints on customers, or one of them without its NULL '
            'branch. The regime and the use are closed vocabularies the API mirrors in code, and the postal '
            'code is five digits; all three are optional, so all three must admit NULL.', v_checks;
    END IF;

    IF NOT EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE conrelid = 'customers'::regclass
               AND contype = 'f'
               AND conname = 'fk_customers_fiscal_country') THEN
        RAISE EXCEPTION
            'V107 expected fk_customers_fiscal_country, the named foreign key from customers.fiscal_country_id '
            'to countries. A country id with no key is a pointer nobody checks.';
    END IF;

    -- The tenancy of the prerequisite unit: still armed and forced, still exactly one policy. `V107` adds
    -- columns and has no reason to touch that machinery; a policy that moved here is an unbounded
    -- cross-tenant read of the most sensitive personal data this tree holds (L6(f)).
    SELECT count(*), bool_and(c.relforcerowsecurity)
      INTO v_policies, v_forced
      FROM pg_policy p
      JOIN pg_class c ON c.oid = p.polrelid
     WHERE p.polrelid = 'customers'::regclass;

    IF v_policies IS DISTINCT FROM 1 OR v_forced IS DISTINCT FROM true THEN
        RAISE EXCEPTION
            'V107 read customers as % policies with FORCE=%. The table was armed by V105, and the fiscal profile '
            'lands here only because it is: a profile behind a query with no tenant predicate is the state that '
            'held this task since L6(f).', v_policies, v_forced;
    END IF;
END $$;
