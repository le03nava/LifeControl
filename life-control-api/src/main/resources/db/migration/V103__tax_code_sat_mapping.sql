-- ============================================
-- V103 — S3/T3: the tax vocabulary learns the authority's two coordinates
-- ============================================
-- `odd/tasks/cfdi-fiscal-document.md`, spec S3 and decision L6(d) of the same record:
--
--   «The tax vocabulary gains the authority's two coordinates as mapped columns on `tax_code` —
--     `sat_impuesto` and `sat_tipo_factor` — rather than a parallel catalog.»
--
-- WHY COLUMNS AND NOT A SECOND TABLE. ADR-0007 decision 6 refuses a parallel SAT catalog for the same reason
-- ADR-0002 decision 6 refused a second tax vocabulary: a table holding the same fact is a second place to
-- keep it aligned, and `tax_code` already discriminates rate, zero rate, exempt and not-applicable. Two mapped
-- columns make that distinction speak the authority's language.
--
-- BOTH COLUMNS ARE NULLABLE AND UNCONSTRAINED, AND THAT IS THE DECISION (L6(d), ADR-0007 decision 11). The
-- codes belong to the authority's catalog, so pinning them here would make this migration wrong the first
-- time the catalog is reissued — a reissue is a data migration, never a silent acceptance. And a code with no
-- mapping has to be **visible as null** so the stamp refuses it with a message that names the code, instead of
-- reading a default this tree guessed.
--
-- THE FOUR DECLARED CODES, and the one that gets no coordinates at all:
--
--   * `IVA`     → ('002', 'Tasa')   — Impuesto 002 is IVA; Tasa because the line states a rate.
--   * `TASA_0`  → ('002', 'Tasa')   — the SAME pair on purpose: the rate is what separates a taxed line from a
--                                     zero-rate one, not the coordinates. Two different coordinates here would
--                                     be the same fact stated twice, and the second copy could drift.
--   * `EXENTO`  → ('002', 'Exento') — the authority's own factor, and the reason these columns exist: before
--                                     them, `EXENTO` and `TASA_0` differed only by the string in a code.
--   * `NA`      → (NULL, NULL)      — "not subject to the tax" is the **absence** of a tax node, not a tax of
--                                     zero. Writing ('002', 'Exento') here would make a not-applicable line and
--                                     an exempt one the same statement in a document.
--
-- WHAT IS NOT DECLARED. `tax_code` may gain a fifth code by a reviewed migration (V82 states the vocabulary is
-- closed at four and that a fifth is an `INSERT`). That code arrives **unmapped**, which is the honest state:
-- the stamp refuses it by name until someone maps it here, and a self-check below fails if a row carries
-- coordinates nobody declared.
--
-- No `NO FORCE` window, for the reason `V82` states: `tax_code` is a platform catalog with no `company_id`, no
-- policy and no `tenant_table` row, so no policy filters the `UPDATE`s below. Nothing is granted or revoked:
-- `V52:190-199`'s default privileges already cover the table, and these two columns add no write path.
-- ============================================

ALTER TABLE tax_code ADD COLUMN sat_impuesto VARCHAR(3);
ALTER TABLE tax_code ADD COLUMN sat_tipo_factor VARCHAR(10);

COMMENT ON COLUMN tax_code.sat_impuesto IS
    'S3: the authority''s `Impuesto` code a CFDI tax node states (002 = IVA). Nullable and unconstrained by '
    'decision (ADR-0007 decision 11): the catalog is the authority''s, and a code with no mapping must be '
    'visible as null so the stamp refuses it instead of guessing.';
COMMENT ON COLUMN tax_code.sat_tipo_factor IS
    'S3: the authority''s `TipoFactor` a CFDI tax node states (Tasa, Cuota, Exento). Nullable and '
    'unconstrained for the same reason as sat_impuesto.';

-- ─── the mapping of the four declared codes ───────────────────────────────────────────────────────────
UPDATE tax_code SET sat_impuesto = '002', sat_tipo_factor = 'Tasa' WHERE code IN ('IVA', 'TASA_0');
UPDATE tax_code SET sat_impuesto = '002', sat_tipo_factor = 'Exento' WHERE code = 'EXENTO';

-- Written instead of omitted, so the intent is a statement and not an absence: `NA` is a code that states no
-- tax node, and a later reader must not mistake it for a mapping somebody forgot.
UPDATE tax_code SET sat_impuesto = NULL, sat_tipo_factor = NULL WHERE code = 'NA';

-- ─── the self-check: the four rows came out as stated, and no fifth row was mapped by accident ───────
DO $$
DECLARE
    v_declared bigint;
    v_mapped   bigint;
    v_wrong    text;
BEGIN
    -- All four declared codes exist, whatever else the catalog may carry.
    SELECT count(*) INTO v_declared FROM tax_code WHERE code IN ('IVA', 'TASA_0', 'EXENTO', 'NA');
    IF v_declared <> 4 THEN
        RAISE EXCEPTION
            'V103 read % of the four declared tax codes (IVA, TASA_0, EXENTO, NA). V82 seeds them and this '
            'file maps them: a missing row means the mapping below reached fewer codes than it states.',
            v_declared;
    END IF;

    -- Three of the four state coordinates, and the fourth (`NA`) states none by decision, so a count of
    -- **three** is the correct assertion and not four: "not subject to the tax" is the absence of a tax node.
    SELECT count(*) INTO v_mapped FROM tax_code WHERE sat_impuesto IS NOT NULL OR sat_tipo_factor IS NOT NULL;
    IF v_mapped <> 3 THEN
        RAISE EXCEPTION
            'V103 read % tax codes carrying SAT coordinates and the declared vocabulary maps exactly three '
            '(IVA and TASA_0 to (002, Tasa), EXENTO to (002, Exento)); NA is declared with no coordinates at '
            'all. A count that moved means the UPDATEs above did not run, or a row was mapped without a '
            'decision behind it — and an undeclared mapping is a tax a document would state on its own.',
            v_mapped;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM tax_code WHERE code = 'IVA' AND sat_impuesto = '002' AND sat_tipo_factor = 'Tasa')
       OR NOT EXISTS (SELECT 1 FROM tax_code WHERE code = 'TASA_0' AND sat_impuesto = '002'
                        AND sat_tipo_factor = 'Tasa') THEN
        RAISE EXCEPTION
            'V103 expects IVA and TASA_0 to both state (002, Tasa): the pair is the same because the rate, not '
            'the coordinates, is what separates them.';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM tax_code WHERE code = 'EXENTO' AND sat_impuesto = '002'
                    AND sat_tipo_factor = 'Exento') THEN
        RAISE EXCEPTION
            'V103 expects EXENTO to state (002, Exento). Without it EXENTO and TASA_0 are the same string '
            'again, which is the one thing S3 exists to end.';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM tax_code WHERE code = 'NA' AND sat_impuesto IS NULL AND sat_tipo_factor IS NULL)
    THEN
        RAISE EXCEPTION
            'V103 expects NA to state no coordinates at all: "not subject to the tax" is the absence of a tax '
            'node and not a tax of zero.';
    END IF;

    -- The other direction: every mapped row is one of the four declared codes. This is the assertion that
    -- catches a mapping written for a code nobody decided about, which no count of four would notice.
    SELECT string_agg(code, ', ' ORDER BY code) INTO v_wrong
      FROM tax_code
     WHERE (sat_impuesto IS NOT NULL OR sat_tipo_factor IS NOT NULL)
       AND code NOT IN ('IVA', 'TASA_0', 'EXENTO', 'NA');

    IF v_wrong IS NOT NULL THEN
        RAISE EXCEPTION
            'V103 mapped tax code(s) nobody declared: %. A mapping is a decision with a document behind it: '
            'declare it in odd/tasks/cfdi-fiscal-document.md and in this file, or leave the code unmapped so '
            'the stamp refuses it by name.', v_wrong;
    END IF;
END $$;
