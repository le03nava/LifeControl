-- ============================================
-- V112 — the cancellation motive becomes a closed vocabulary (T8, S7)
-- ============================================
--
-- WHAT. `fiscal_document.cancellation_motive` gains a named `CHECK` over the four codes the authority states,
-- and nothing else changes: no column is added, dropped or retyped, no policy, grant or trigger is touched.
--
-- WHY THIS IS A REVERSAL, STATED AS ONE. `V109` closes with a guard that RAISES if any `CHECK` on this table
-- pins `exportacion`, `tax_object` or `cancellation_motive` to a value, and its argument was that the
-- authority's `c_Exportacion`, `c_ObjetoImp` and `c_MotivoCancelacion` sets are "stated nowhere in this tree",
-- so a `CHECK` here would be a code recalled from memory. That argument is right, and what changed is the
-- premise: `c_MotivoCancelacion` is now read, not recalled (see the provenance below), and a catalog that has
-- been read is exactly what a `CHECK` is for. The two columns the argument still covers — `exportacion` and
-- `tax_object` — stay unconstrained, because neither has been read from a primary source. The reversal is
-- deliberate and one column wide, and it is recorded as `L20`(e) of `odd/tasks/cfdi-fiscal-document.md`.
--
-- WHERE THE FOUR CODES COME FROM, because this file is the one that would otherwise be a guess:
--   * the SAT's `cancelación.xsd` (the "Estándar de Cancelación CFDI v4" request schema) declares
--     `<xsd:attribute name="Motivo" use="required">` with `<xsd:enumeration>` values `01`, `02`, `03`, `04`;
--   * Solución Factible's own cancellation catalogue (LCO "Timbrado.cancelar", updated 2026-11-15) lists the
--     same four with their meaning, and states that from 2022-01-01 every cancellation request must carry one;
--   * neither source declares `05`. If the authority publishes a fifth code, that is a reviewed migration
--     touching this constraint and `FiscalCancellationMotive` together — never a silent acceptance.
--
-- WHY THE NULL COUPLING IS NOT REPEATED HERE. `ck_fiscal_document_cancellation` (`V108`) already says a
-- `CANCELLED` row carries a motive and every other row carries none, so a null-tolerant `IS NULL OR` here is
-- the only shape that does not restate that rule in a second place where it could drift.
--
-- WHY `FolioSustitucion` IS NOT IN THE SCHEMA. The XSD makes it `use="optional"` and requires it only when the
-- motive is `01`; `replaces_fiscal_document_id` is that attribute's home, and the one-way rule (required for
-- `01`) lives in `FiscalCancellationMotive` and in the cancellation service, which can name the field in a
-- 400. Adding a `CHECK` that also forbade it for `02`–`04` would encode a rule no retrieved source states —
-- the SAT rejects a *wrong* UUID (code `312`), not the attribute's presence.
--
-- Forward-only and additive: dropping `ck_fiscal_document_cancellation_motive` restores V111's shape exactly.
-- ============================================

ALTER TABLE fiscal_document
    ADD CONSTRAINT ck_fiscal_document_cancellation_motive
    CHECK (cancellation_motive IS NULL OR cancellation_motive IN ('01', '02', '03', '04'));

COMMENT ON COLUMN fiscal_document.cancellation_motive IS
    'S7/L20(e): the authority''s c_MotivoCancelacion code this row carries, two characters, from the closed '
    'vocabulary ck_fiscal_document_cancellation_motive mirrors. Non-null exactly on a CANCELLED row, which '
    'ck_fiscal_document_cancellation (V108) states and this constraint does not restate.';

-- ============================================
-- The self-check: read the admitted set back, and refuse a silent widening
-- ============================================
--
-- What this asserts, and what it deliberately does not. A migration cannot prove a `CHECK` refuses by
-- inserting a probe row: `fiscal_document` carries nine constraints besides this one (V108, V109, V110), so a
-- probe row violating the motive would in practice violate `ck_fiscal_document_cancellation` first — a
-- cancellation row with no `cancels_fiscal_document_id` — and the block would catch a refusal for the wrong
-- reason and report a rule it never tested. The live refusal is `FiscalDocumentSnapshotMigrationIntegrationTest`'s
-- job, where a complete, otherwise-valid row exists. Here the constraint's own definition is read back and the
-- **set of codes it admits is extracted and compared**, which is the one fact this file can state about itself
-- without a witness: exactly `01`–`04`, in that order, and nothing else. An edit that widens or narrows the list
-- by one code fails this before it reaches a single row.
DO $$
DECLARE
    v_definition TEXT;
    v_admitted   TEXT;
BEGIN
    SELECT pg_get_constraintdef(c.oid) INTO v_definition
      FROM pg_constraint c
     WHERE c.conrelid = 'fiscal_document'::regclass
       AND c.conname = 'ck_fiscal_document_cancellation_motive';

    IF v_definition IS NULL THEN
        RAISE EXCEPTION
            'V112 added no ck_fiscal_document_cancellation_motive on fiscal_document. The motive is the one '
            'fact S7 says a cancellation states, and a writer that stores it unconstrained is the state this '
            'migration exists to leave.';
    END IF;

    -- The admitted codes, in the order the constraint states them, read out of PostgreSQL's own rendering
    -- (`((cancellation_motive)::text = ANY ((ARRAY[''01''::character varying, …])::text[]))`).
    SELECT string_agg(m[1], ',' ORDER BY ord) INTO v_admitted
      FROM regexp_matches(v_definition, '''([0-9]{2})''', 'g') WITH ORDINALITY AS r(m, ord);

    IF v_admitted IS DISTINCT FROM '01,02,03,04' THEN
        RAISE EXCEPTION
            'V112 reads ck_fiscal_document_cancellation_motive admitting [%] instead of [01,02,03,04]. Neither '
            'the SAT''s cancelación.xsd nor the vendor''s catalogue declares a fifth code, and dropping one would '
            'refuse a cancellation the authority accepts: the list is the catalog, so changing it is a reviewed '
            'migration that edits this file, the constraint and FiscalCancellationMotive together.',
            coalesce(v_admitted, 'none');
    END IF;
END $$;
