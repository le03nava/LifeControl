-- ============================================
-- V109 — what the fiscal document states: the frozen snapshot, and the judgement that keeps it honest against
--        the sale it derives from (S8 and S9 of `odd/tasks/cfdi-fiscal-document.md`; T5, decisions L9(a),
--        L9(e)–L9(h) of the same record)
-- ============================================
-- WHY THIS FILE IS THE SECOND ONE AND NOT PART OF `V108` (L9(a)). `V108` creates the columns that make a
-- document **exist as a history** — identity, state, the authority's UUID, the cancellation pointers — and arms
-- the three tables. This file adds every column that says **what the document states**, and the comparison that
-- makes that statement checkable. Nothing can stamp between the two: no writer exists until A3's stamping port,
-- so a table that cannot yet hold a complete document is a state no caller can reach, and the `NOT NULL`
-- additions below land on empty tables. The split is the two task titles verbatim — T4 is the document, T5 is
-- the frozen snapshot.
--
-- WHY EVERY FACT IS FROZEN ON THE DOCUMENT (S9, ADR-0007 decision 4). «Clave, unit, description, quantity, unit
-- value, discount, tax object, base, tax, factor and rate are copied onto `fiscal_document_line` at stamp time,
-- and so is the receiver's fiscal identity. Nothing is read from the live catalogs when a document is re-issued,
-- reprinted or cancelled, for the reason `V81` froze the rate on the line: a catalog is today's answer to a
-- question asked last year.»
--
-- The **issuer's** identity is frozen for the same reason, and the ADR's schema block froze only the
-- receiver's (L9(f)). A document states the issuer's `rfc`, legal name and regime exactly as it states the
-- receiver's, and `companies.rfc` and `companies.razon_social` are mutable columns: a document that read them
-- live would change its face the day a company corrects its name.
--
-- WHY THE DOCUMENT HAS NO `rounding_adjustment` (L9(g)), STATED HERE BECAUSE ITS ABSENCE IS THE TOLERANCE.
-- `sales_orders` carries `rounding_adjustment` and `ck_order_total` (V84) makes its four parts add up to the
-- total. The authority's document states subtotal, discount and tax, and **its total is their sum**: there is
-- no rounding term in the schema it is stamped against. Copying the sale's rounding would put on the invoice a
-- number that document does not accept, and dropping it makes the two totals disagree by at most the rounding
-- `ck_order_rounding_bounded` (V80) already bounds to one minor unit — which is exactly the tolerance `S8`
-- grants and the comparison in section 5 reads from `currency.minor_units`.
--
-- THE VOCABULARY RULE, WHICH IS THE ONE PLACE THIS FILE CORRECTS L9(i). `tipo_comprobante`, `metodo_pago`,
-- `status`, `source_type` and the line's `tipo_factor` are named `CHECK`s, because their value sets are stated
-- by the authority's schema and by code this tree already runs (`V103` maps `Tasa`, `Cuota` and `Exento`
-- today). `exportacion`, `tax_object` and `cancellation_motive` get **no `CHECK` at all**, and that is not an
-- omission: ADR-0007 decision 11 says the catalog values come from the version in force and are never pinned
-- from memory, and — unlike `PUE`/`PPD` and `I` — the authority's `c_Exportacion`, `c_ObjetoImp` and
-- `c_MotivoCancelacion` value sets are **stated nowhere in this tree**. Writing plausible-looking codes here
-- would be exactly the recollection decision 11 refuses, and it would refuse a legal document the day the
-- catalog is reissued. The columns are typed and required, their vocabularies land with the writer that reads
-- them, and `V109`'s self-check plus the migration suite assert that no `CHECK` constrains any of the three, so
-- a later edit cannot quietly pin a catalog.
--
-- WHAT THIS FILE DOES NOT DO, named so it is not discovered in the diff:
--   * **No backfill, and none is possible.** The two tables are empty; no writer exists until A3. The
--     `ADD COLUMN … NOT NULL` statements are the assertion: PostgreSQL fills a new required column with `NULL`
--     over existing rows and refuses the statement, so a row that had been stamped by something would abort
--     this file instead of leaving the column half stated. A `DO` block counting rows would **not** do it —
--     both tables are `FORCE`d since `V108`, and the owner reads zero rows under the policy, which is the
--     «read nothing and report nothing wrong» defect `V64:30-42` records.
--   * **No enums.** `L9(i)` keeps them with their first reader: A3's builder. The vocabularies live in named
--     `CHECK`s, verified where they live.
--   * **No writer, no service, no screen.** The stamp is A3's; this file is the schema it will find.
--   * **No comparison of the document's parts with its own lines.** Section 5 compares two documents, and it
--     says what that leaves unenforced.
-- ============================================

-- ─── 1. the receiver's and the issuer's identity, frozen ──────────────────────────────────────────
-- Five columns for the receiver (ADR-0007 decision 4, S9) and three for the issuer (L9(f)). All of them copy a
-- value that lives on `customers` and `companies` today, which is why they are here and not derived at read
-- time: what a stamp states of the person it names is the person as they were when the authority witnessed it.
--
-- `receptor_zip_code` is five characters, not the ten the ADR's schema sketch gives the receiver, for the reason
-- the ADR's own amendment states: the fact is the postal code of the fiscal domicile, five digits, and a wider
-- column invites a value no stamp accepts. `V107` made the same decision on the receiver's profile, and the
-- issuer's `companies.fiscal_zip_code` was `VARCHAR(5)` for the same reason (`V102`).
ALTER TABLE fiscal_document
    ADD COLUMN receptor_rfc        VARCHAR(13) NOT NULL,
    ADD COLUMN receptor_name       VARCHAR(255) NOT NULL,
    ADD COLUMN receptor_regimen    VARCHAR(3) NOT NULL,
    ADD COLUMN receptor_zip_code   VARCHAR(5) NOT NULL,
    ADD COLUMN receptor_uso_cfdi   VARCHAR(5) NOT NULL,
    ADD COLUMN emisor_rfc          VARCHAR(13) NOT NULL,
    ADD COLUMN emisor_name         VARCHAR(255) NOT NULL,
    ADD COLUMN emisor_regimen      VARCHAR(3) NOT NULL;

COMMENT ON COLUMN fiscal_document.receptor_rfc IS
    'S9 / ADR-0007 decision 4: the receiver''s RFC as it stood at stamp time, copied and never re-read. The '
    'live value is customers.rfc (V107), which is editable, and a document that read it live would change its '
    'face after the authority witnessed it.';
COMMENT ON COLUMN fiscal_document.receptor_name IS
    'S9: the receiver''s legal name at stamp time (customers.legal_name). A rename in the catalog must not '
    'restate a document that was already issued — the reason V81 froze the product name on the sale line.';
COMMENT ON COLUMN fiscal_document.receptor_regimen IS
    'S9: the receiver''s fiscal regime at stamp time (customers.regimen_fiscal, V107). Two characters of the '
    'authority''s c_RegimenFiscal, three wide as the catalog states it, and no CHECK: the vocabulary is an enum '
    'mirrored by a named constraint on customers, and copying it here would be a second vocabulary free to '
    'drift from the one the profile validates.';
COMMENT ON COLUMN fiscal_document.receptor_zip_code IS
    'S9: the postal code of the receiver''s fiscal domicile at stamp time. Five digits and not the ten the '
    'ADR''s sketch gives (ADR-0007 amendment of 2026-11-14, L8(c)): the only fact a stamp states of that '
    'domicile is this one.';
COMMENT ON COLUMN fiscal_document.receptor_uso_cfdi IS
    'S9: the use the receiver declared for this document, frozen. Required by the authority''s schema on every '
    'document, and conditional in meaning — the value comes from the receiver''s profile or from the stamp '
    'request, and never from the live catalog at reprint time.';
COMMENT ON COLUMN fiscal_document.emisor_rfc IS
    'S9, and the ADR block''s omission corrected by L9(f): the issuer''s RFC as it stood at stamp time, copied '
    'from companies.rfc. The block froze only the receiver''s identity; a document states the issuer''s too.';
COMMENT ON COLUMN fiscal_document.emisor_name IS
    'S9 / L9(f): the issuer''s legal name at stamp time (companies.razon_social). A corrected company name is a '
    'mutable column and a stamped document must not follow it.';
COMMENT ON COLUMN fiscal_document.emisor_regimen IS
    'S9 / L9(f): the issuer''s fiscal regime at stamp time (companies.regimen_fiscal, V102), which is what S1 '
    'added to the issuer and what makes a document issueable at all.';

-- ─── 2. the three facts that are not about money, and the place of issue ─────────────────────────
-- ADR-0007 decision 8: the type of the document, the postal code of the place of issue and the export
-- declaration are columns, and the last two are required on every document by the fourth revision of the
-- authority's schema. `lugar_expedicion` is the issuer's postal code frozen at stamp (`S1` + `S9`, `L6`(b)):
-- the company declares its place of issue once and every document copies it, exactly as a rate is frozen on a
-- line.
--
-- `tipo_comprobante` is checked to `I` because income is the only type this tree issues until a note exists —
-- a credit note is a document type of its own, and the slice that gives money a way back is where the
-- vocabulary moves. `metodo_pago` admits `PUE` and `PPD` and the refusal of `PPD` is **not** this file's: S13
-- names it as a validation because a deferred invoice needs a receivable and a payment complement, so the
-- column holds both methods today and `T9` is what refuses the one a sale cannot satisfy.
--
-- `forma_pago` is nullable because it is conditional in the authority's schema: it is stated with the payment
-- method, and a document that states none is not a document with a wrong one.
ALTER TABLE fiscal_document
    ADD COLUMN tipo_comprobante VARCHAR(1) NOT NULL DEFAULT 'I',
    ADD COLUMN metodo_pago      VARCHAR(3) NOT NULL DEFAULT 'PUE',
    ADD COLUMN forma_pago       VARCHAR(2),
    ADD COLUMN exportacion      VARCHAR(2) NOT NULL DEFAULT '01',
    ADD COLUMN lugar_expedicion VARCHAR(5) NOT NULL,
    -- S12 mentions the document's type, place of issue and export status as columns "with a closed vocabulary".
    -- Two of the three are closed here; `exportacion` is deliberately not, per the header: its catalog is the
    -- authority's `c_Exportacion` and this tree has never held its values.
    ADD CONSTRAINT ck_fiscal_document_tipo_comprobante CHECK (tipo_comprobante = 'I'),
    ADD CONSTRAINT ck_fiscal_document_metodo_pago CHECK (metodo_pago IN ('PUE', 'PPD'));

COMMENT ON COLUMN fiscal_document.tipo_comprobante IS
    'S12 / ADR-0007 decision 8: the type of the document, closed to I — income — because it is the only type '
    'this tree issues until a note exists (ck_fiscal_document_tipo_comprobante).';
COMMENT ON COLUMN fiscal_document.metodo_pago IS
    'S12 / ADR-0007 decision 9: PUE or PPD (ck_fiscal_document_metodo_pago). Both are admitted here and PPD is '
    'refused as a validation by S13/T9 — the column is the schema''s, the refusal is the domain''s, and a '
    'deferred invoice needs a receivable and a payment complement before it can mean anything.';
COMMENT ON COLUMN fiscal_document.forma_pago IS
    'S12: the payment form, nullable because it is conditional in the authority''s schema — stated with the '
    'method and never invented. Two characters of the authority''s c_FormaPago, whose values this tree reads '
    'from the catalog and does not pin.';
COMMENT ON COLUMN fiscal_document.exportacion IS
    'ADR-0007 decision 8: whether the operation exports, required on every document by the fourth revision and '
    'defaulted to 01. Deliberately no CHECK: the authority''s c_Exportacion values are stated nowhere in this '
    'tree, and decision 11 forbids pinning a catalog from memory.';
COMMENT ON COLUMN fiscal_document.lugar_expedicion IS
    'S1 + S9 / L6(b) and ADR-0007 decision 8: the postal code of the place of issue, frozen at stamp from '
    'companies.fiscal_zip_code (V102). Five digits, because that is the fact.';

-- ─── 3. the money the authority reconciles ────────────────────────────────────────────────────────
-- Four parts and a total, at the scale every money column in this tree carries since `V80` (ADR-0002
-- decision 1), in the document's own currency. `tax_transferred` and `tax_withheld` are separate from the
-- start (ADR-0007 decision 7): a withheld total that is always zero is still a column someone has to see, and
-- per-line withholding is out of scope for this slice.
--
-- `currency` is the document's and it references the platform catalog, so a code the catalog does not hold
-- cannot be stated. It is copied from the sale's at stamp and never re-derived: `V80` decided the same thing
-- for the sale and `V93` for the purchase order, and the reason is that a document must not restate its own
-- money the day the company changes currency.
--
-- The two constraints are the document's arithmetic and its sign. `ck_fiscal_document_total` is the equality
-- the authority's schema states — the total **is** the sum of the parts, with no rounding term, which is the
-- whole subject of `L9(g)` and of the tolerance section 5 reads.
ALTER TABLE fiscal_document
    ADD COLUMN currency        CHAR(3) NOT NULL REFERENCES currency (code),
    ADD COLUMN subtotal        NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN discount_total  NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN tax_transferred NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN tax_withheld    NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN total_amount    NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_fiscal_document_parts_not_negative CHECK (
        subtotal >= 0 AND discount_total >= 0 AND tax_transferred >= 0 AND tax_withheld >= 0
        AND total_amount >= 0),
    ADD CONSTRAINT ck_fiscal_document_total CHECK (
        total_amount = subtotal - discount_total + tax_transferred - tax_withheld);

COMMENT ON CONSTRAINT ck_fiscal_document_total ON fiscal_document IS
    'S8 / L9(g) / ADR-0007 decision 7: the document states its parts and the total is their sum. There is no '
    'rounding_adjustment and deliberately so: the authority''s document has no such term, and the one minor '
    'unit a sale''s rounding can separate the two totals is exactly the tolerance '
    'fiscal_document_snapshot_matches_sale grants.';
COMMENT ON CONSTRAINT ck_fiscal_document_parts_not_negative ON fiscal_document IS
    'S8: every part of the document''s money is a non-negative number, so a document cannot state a negative '
    'total out of non-negative parts.';
COMMENT ON COLUMN fiscal_document.currency IS
    'ADR-0002 decision 2 / ADR-0007: the currency the document states, frozen at stamp from the sale''s. It '
    'references the platform catalog, so a code the catalog does not hold is refused here rather than by the '
    'authority.';
COMMENT ON COLUMN fiscal_document.tax_transferred IS
    'ADR-0007 decision 7: the transferred tax of the whole document — the sale''s tax_total, as the comparison '
    'in section 5 reads it. Separate from the withheld total because an invoice states the two directions '
    'apart; withholding per line is out of scope for this slice.';
COMMENT ON COLUMN fiscal_document.tax_withheld IS
    'ADR-0007 decision 7: the withheld tax, allowed to be zero and zero on every document this tree issues '
    'today. It is a column and not an absence, because the document-level total is what the authority '
    'reconciles.';

-- ─── 4. the line, and what it states ─────────────────────────────────────────────────────────────
-- `V108` gave the line its identity and the sale line it names. This is the snapshot `S9` describes: the two
-- authority keys, the description as it was, the quantity and the values, the tax object and base, the tree's
-- own tax code, the factor and the rate. Nothing here is read from `products`, `measure_units` or `tax_code`
-- when a document is reprinted or cancelled.
--
-- `tax_code` is `VARCHAR(20)`, **not** the three characters the ADR's schema block gives it (`L9(e)`): the
-- column copies the tree's internal code — `NA`, `IVA`, `TASA_0`, `EXENTO` — from `sales_order_items.tax_code`,
-- and `EXENTO` does not fit in three. The block was written before the copy it describes.
--
-- `tipo_factor` is checked to the three values `V103` itself just mapped onto `tax_code.sat_tipo_factor`:
-- `Tasa`, `Cuota` and `Exento`. `tasa_o_cuota` is nullable because `Exento` states a factor and no rate, and a
-- zero would be a rate the authority reads as a different statement.
--
-- `tax_object` gets no `CHECK`: see the header's vocabulary rule. It is required because the fourth revision
-- requires it on every concept, and its values belong to the authority's `c_ObjetoImp`.
ALTER TABLE fiscal_document_line
    ADD COLUMN clave_prod_serv VARCHAR(20) NOT NULL,
    ADD COLUMN clave_unidad    VARCHAR(5) NOT NULL,
    ADD COLUMN description     VARCHAR(1000) NOT NULL,
    ADD COLUMN quantity        NUMERIC(19,6) NOT NULL,
    ADD COLUMN unit_value      NUMERIC(19,4) NOT NULL,
    ADD COLUMN discount        NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN tax_object      VARCHAR(2) NOT NULL,
    ADD COLUMN base            NUMERIC(19,4) NOT NULL,
    ADD COLUMN tax_code        VARCHAR(20) NOT NULL,
    ADD COLUMN tipo_factor     VARCHAR(10) NOT NULL,
    ADD COLUMN tasa_o_cuota    NUMERIC(19,6),
    ADD COLUMN tax_amount      NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_fiscal_document_line_tipo_factor CHECK (
        tipo_factor IN ('Tasa', 'Cuota', 'Exento'));

COMMENT ON CONSTRAINT ck_fiscal_document_line_tipo_factor ON fiscal_document_line IS
    'S9 / ADR-0007 decision 4: the three factors V103 mapped onto tax_code.sat_tipo_factor. A factor the tree '
    'cannot resolve is a line no stamp can state, so the vocabulary is closed here and verified here.';
COMMENT ON COLUMN fiscal_document_line.clave_prod_serv IS
    'S9 / ADR-0007 decision 4: the product''s fiscal key as it stood at stamp, copied from products.sat_code '
    '(V104 constrains its shape). The concept is the one element a stamp cannot omit, which is why the copy '
    'exists: a recategorization must not restate an issued document.';
COMMENT ON COLUMN fiscal_document_line.clave_unidad IS
    'S9: the unit''s key at stamp, copied from measure_units.sat_code. The live value is required and unique on '
    'the catalog (V1); this column is what the document said, not what the catalog says now.';
COMMENT ON COLUMN fiscal_document_line.description IS
    'S9: what the line states the customer bought, as it stood at stamp — the product and variant name '
    'snapshots the sale line already carries, rendered once. A rename in the catalog must not change it.';
COMMENT ON COLUMN fiscal_document_line.quantity IS
    'S9 / S8: the quantity the document states, at six decimals because the authority accepts fractions; the '
    'comparison holds it to sales_order_items.quantity within one minor unit of the document''s currency.';
COMMENT ON COLUMN fiscal_document_line.unit_value IS
    'S9 / S8: the unit value before discount, the sale line''s list_price as it stood at stamp.';
COMMENT ON COLUMN fiscal_document_line.discount IS
    'S9 / S8: the discount the document states for this line, the sale line''s discount_applied. Zero when '
    'there is none, which is why the column carries a default.';
COMMENT ON COLUMN fiscal_document_line.base IS
    'S9 / S8: the line''s base before tax — quantity times unit value less the discount — which is the sale '
    'line''s subtotal. The tax is stated on this number and the comparison holds it to that column.';
COMMENT ON COLUMN fiscal_document_line.tax_object IS
    'ADR-0007 decision 4 and the fourth revision of the authority''s schema: the tax object of this concept, '
    'required on every line. Deliberately no CHECK — its values are the authority''s c_ObjetoImp and this tree '
    'has never held them (decision 11).';
COMMENT ON COLUMN fiscal_document_line.tax_code IS
    'S9 / L9(e): the tree''s own tax code frozen on the line, copied from sales_order_items.tax_code, which '
    'V89/V81 took from the product. Twenty characters and not the ADR block''s three: EXENTO does not fit in '
    'three.';
COMMENT ON COLUMN fiscal_document_line.tipo_factor IS
    'S9 / ADR-0007 decision 4: the factor the authority states for this line''s tax — Tasa, Cuota or Exento '
    '(ck_fiscal_document_line_tipo_factor), frozen from the mapping V103 put on the tax vocabulary.';
COMMENT ON COLUMN fiscal_document_line.tasa_o_cuota IS
    'S9: the rate or the quota the factor names, nullable because Exento states a factor and no rate. A zero '
    'here would be a rate, which is a different statement to the authority.';
COMMENT ON COLUMN fiscal_document_line.tax_amount IS
    'S9 / S8: the tax this line carries, which the comparison holds to sales_order_items.tax_amount within one '
    'minor unit of the document''s currency.';

-- ─── 5. S8: the comparison, as one judgement at commit ────────────────────────────────────────────
-- `L9(h)`: **one** deferred constraint trigger on the document, not one per line. A stamp writes the document
-- and its lines in one transaction, so the question «does this document state this sale» is answered once, at
-- commit, over the whole row set — instead of per row, against a sale that the same transaction has not
-- finished writing.
--
-- WHY NO ROW LOCK, WHICH IS THE ONE THING THIS FUNCTION DELIBERATELY TAKES FROM `V93`'s SHAPE AND DOES NOT
-- COPY. `purchase_order_money()` (`V93:163-176`) takes `PERFORM 1 … FOR UPDATE` on the order before its
-- aggregate, because two concurrent writers can each add a line and each then compute a total missing the
-- other's. That race does not exist here, and there are two reasons:
--
--   * **Nothing aggregates another writer's rows.** The five amounts this function compares per line are read
--     from the row the same transaction wrote, and `fiscal_document_line` is append-only (`V108`): no second
--     writer can move a line between the write and the commit.
--   * **The sale is read, never written.** `FOR UPDATE` on the sale would serialize a stamp against an
--     unrelated edit to a document this transaction does not modify, and it would not make the comparison
--     truer: a sale edited **after** the stamp is exactly what ADR-0007 exists for — the document is a
--     snapshot precisely because the sale can still change — so a lock narrowed the window and never closed
--     it.
--
-- WHAT THE COMPARISON DOES NOT DO, NAMED SO IT IS NOT DISCOVERED LATER. It compares the document's four parts
-- with the sale's, and every line with the sale line it names. It does **not** check that the document's
-- `subtotal` is the sum of its own lines, because that is the writer's obligation (A3's builder states the
-- document from its lines) and not a comparison between two documents: a document whose totals equal the
-- sale's and whose lines are missing from it would pass this judgement. Adding that sum here would be a second
-- definition of the document's arithmetic beside `ck_fiscal_document_total`, and it belongs to the slice that
-- has a writer to hold it to.
CREATE FUNCTION fiscal_document_snapshot_matches_sale() RETURNS trigger AS $$
DECLARE
    v_sale        sales_orders%ROWTYPE;
    v_minor_units smallint;
    v_tolerance   numeric;
    v_line        record;
BEGIN
    -- Fail closed on a source kind nobody taught this function. `ck_fiscal_document_source_type` (V108) admits
    -- `SALES_ORDER` alone today, so this branch is unreachable — and it is here because the day a second value
    -- is added to that vocabulary, the alternative is a stamped document that nothing compared.
    IF NEW.source_type <> 'SALES_ORDER' THEN
        RAISE EXCEPTION
            'fiscal_document % names source_type %, and the comparison with the source it derives from has no '
            'branch for it (S8). Teaching this function the new source is the same edit as widening '
            'ck_fiscal_document_source_type, and stamping an uncompared document is what happens between the two.',
            NEW.id, NEW.source_type;
    END IF;

    SELECT * INTO v_sale
      FROM sales_orders
     WHERE id = NEW.source_id
       AND company_id = NEW.company_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION
            'fiscal_document % of company % names sale % and no such sale exists, so what the document states '
            'cannot be compared with anything (S8). `source_id` carries no foreign key by design (V108): the '
            'pair (source_type, source_id) is polymorphic, and the stamp is the first moment the pointer can '
            'finally be checked. Either the pointer is wrong or the sale belongs to another tenant, and the '
            'policy hides the second case instead of refusing it (ADR-0005).',
            NEW.id, NEW.company_id, NEW.source_id;
    END IF;

    -- The tolerance is one minor unit of **the document's** currency, read from the catalog and never from a
    -- literal: a currency with two decimals tolerates 0.01 and one with none tolerates 1, and a constant here
    -- would be right for exactly one of them (L9(g)).
    SELECT c.minor_units INTO v_minor_units FROM currency c WHERE c.code = NEW.currency;
    v_tolerance := power(10::numeric, -v_minor_units);

    IF abs(NEW.subtotal - v_sale.subtotal) > v_tolerance THEN
        RAISE EXCEPTION
            'fiscal_document % states subtotal % and sale % states %: the document''s parts have to be the '
            'sale''s within one minor unit (%) of its currency (S8, ADR-0007 invariant F5)', NEW.id, NEW.subtotal,
            v_sale.id, v_sale.subtotal, v_tolerance;
    END IF;

    IF abs(NEW.discount_total - v_sale.discount_total) > v_tolerance THEN
        RAISE EXCEPTION
            'fiscal_document % states discount_total % and sale % states %: the document''s parts have to be the '
            'sale''s within one minor unit (%) of its currency (S8, ADR-0007 invariant F5)', NEW.id,
            NEW.discount_total, v_sale.id, v_sale.discount_total, v_tolerance;
    END IF;

    -- The document's transferred tax against the sale's one tax total: the authority states transferred and
    -- withheld apart (decision 7), and the sale has a single tax figure, so the transferred half is the one
    -- that corresponds to it.
    IF abs(NEW.tax_transferred - v_sale.tax_total) > v_tolerance THEN
        RAISE EXCEPTION
            'fiscal_document % states tax_transferred % and sale % states tax_total %: they have to be the same '
            'money within one minor unit (%) (S8, ADR-0007 invariant F5)', NEW.id, NEW.tax_transferred, v_sale.id,
            v_sale.tax_total, v_tolerance;
    END IF;

    IF abs(NEW.total_amount - v_sale.total_amount) > v_tolerance THEN
        RAISE EXCEPTION
            'fiscal_document % states total_amount % and sale % states %: the total is the number the authority '
            'reconciles, and it has to be the sale''s within one minor unit (%) — the difference a sale''s '
            'rounding leaves when the document has no rounding term of its own (L9(g), S8)', NEW.id,
            NEW.total_amount, v_sale.id, v_sale.total_amount, v_tolerance;
    END IF;

    -- Every line, against the sale line it names. `sales_order_item_id` is `NOT NULL` and keyed (V108), so an
    -- invoice line that names no sale line cannot exist — an invoice line nobody could compare is the state S8
    -- exists to remove — and this join is therefore complete by construction.
    FOR v_line IN
        SELECT l.id                  AS document_line,
               i.id                  AS sale_item,
               i.sales_order_id      AS sale_of_item,
               l.quantity            AS stated_quantity,
               i.quantity            AS sale_quantity,
               l.unit_value          AS stated_unit_value,
               i.list_price          AS sale_unit_value,
               l.discount            AS stated_discount,
               i.discount_applied    AS sale_discount,
               l.base                AS stated_base,
               i.subtotal            AS sale_base,
               l.tax_amount          AS stated_tax,
               i.tax_amount          AS sale_tax
          FROM fiscal_document_line l
          JOIN sales_order_items i ON i.id = l.sales_order_item_id
         WHERE l.fiscal_document_id = NEW.id
    LOOP
        -- A line of one sale cannot appear on the document of another. Neither pointer can say this on its own:
        -- the document names the sale and the line names the sale line, and the pair is only complete here.
        IF v_line.sale_of_item IS DISTINCT FROM NEW.source_id THEN
            RAISE EXCEPTION
                'fiscal_document % states line % for sale line %, which belongs to sale % and not to the sale % '
                'the document derives from (S8): the line''s amounts could agree with a document that has '
                'nothing to do with this one', NEW.id, v_line.document_line, v_line.sale_item,
                v_line.sale_of_item, NEW.source_id;
        END IF;

        IF abs(v_line.stated_quantity - v_line.sale_quantity) > v_tolerance THEN
            RAISE EXCEPTION
                'fiscal_document_line % states quantity % and sale line % states %: a line''s quantity has to be '
                'its sale line''s (S8)', v_line.document_line, v_line.stated_quantity, v_line.sale_item,
                v_line.sale_quantity;
        END IF;

        IF abs(v_line.stated_unit_value - v_line.sale_unit_value) > v_tolerance THEN
            RAISE EXCEPTION
                'fiscal_document_line % states unit_value % and sale line % states list_price %: a line''s unit '
                'value has to be its sale line''s (S8)', v_line.document_line, v_line.stated_unit_value,
                v_line.sale_item, v_line.sale_unit_value;
        END IF;

        IF abs(v_line.stated_discount - v_line.sale_discount) > v_tolerance THEN
            RAISE EXCEPTION
                'fiscal_document_line % states discount % and sale line % states discount_applied %: a line''s '
                'discount has to be its sale line''s (S8)', v_line.document_line, v_line.stated_discount,
                v_line.sale_item, v_line.sale_discount;
        END IF;

        IF abs(v_line.stated_base - v_line.sale_base) > v_tolerance THEN
            RAISE EXCEPTION
                'fiscal_document_line % states base % and sale line % states subtotal %: the base the tax is '
                'stated on has to be the sale line''s (S8)', v_line.document_line, v_line.stated_base,
                v_line.sale_item, v_line.sale_base;
        END IF;

        IF abs(v_line.stated_tax - v_line.sale_tax) > v_tolerance THEN
            RAISE EXCEPTION
                'fiscal_document_line % states tax_amount % and sale line % states %: a line''s tax has to be '
                'its sale line''s within one minor unit (%) of the document''s currency (S8, ADR-0007 invariant '
                'F5)', v_line.document_line, v_line.stated_tax, v_line.sale_item, v_line.sale_tax, v_tolerance;
        END IF;
    END LOOP;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION fiscal_document_snapshot_matches_sale() IS
    'S8 / ADR-0007 invariant F5, decision L9(h): compares a stamped document — its four parts and every line — '
    'with the sale and the sale lines it names, within one minor unit of the document''s currency read from '
    'currency.minor_units. Deferred to commit so a stamp that writes the document and its lines in one '
    'transaction is judged once over the whole row set. No row lock: the lines are append-only and the sale is '
    'read, never written, and a sale edited after the stamp is what a snapshot is for.';

-- `AFTER INSERT OR UPDATE` and deferred, on the document alone. `UPDATE` is named because the one legitimate
-- update in `fiscal_document` is the transition out of `DRAFT` (V108, L9(b)): the builder writes the draft and
-- its lines, and the stamp is the update that makes the document exist — the moment that has to be compared.
-- The arrow points one way only: a stamped row refuses `UPDATE` by trigger, so this event fires once per
-- document.
CREATE CONSTRAINT TRIGGER trg_fiscal_document_snapshot_matches_sale
    AFTER INSERT OR UPDATE ON fiscal_document
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (NEW.status = 'STAMPED')
    EXECUTE FUNCTION fiscal_document_snapshot_matches_sale();

COMMENT ON TRIGGER trg_fiscal_document_snapshot_matches_sale ON fiscal_document IS
    'S8: the only place two documents are compared. Deferred, so the judgement is one per commit and not one '
    'per row; conditioned on the stamped status, so a draft — the failed attempt L9(b) keeps mutable — states '
    'whatever it likes.';

-- ─── 6. the self-check: the shape the comparison and the writer depend on, read back ─────────────
-- Catalog reads only, and deliberately so: both tables are `FORCE`d, Flyway runs as their owner, and the policy
-- filters the owner too (`V54:50-58`), so a row count here would answer zero for the wrong reason. Every fact
-- asserted below lives in `pg_catalog`, where no policy applies.
DO $$
DECLARE
    v_missing   text;
    v_pinned    text;
    v_trigger   text;
    v_required  text;
BEGIN
    -- The five vocabularies this file writes, read from the definitions and not counted by name: a `CHECK`
    -- under the right name holding the wrong values passes every reader and refuses nothing useful (L9(i)).
    SELECT string_agg(e.name, ', ' ORDER BY e.name) INTO v_missing
      FROM (VALUES
            ('ck_fiscal_document_tipo_comprobante', 'fiscal_document', 'I'),
            ('ck_fiscal_document_metodo_pago', 'fiscal_document', 'PPD'),
            ('ck_fiscal_document_metodo_pago', 'fiscal_document', 'PUE'),
            ('ck_fiscal_document_line_tipo_factor', 'fiscal_document_line', 'Cuota'),
            ('ck_fiscal_document_line_tipo_factor', 'fiscal_document_line', 'Exento'),
            ('ck_fiscal_document_line_tipo_factor', 'fiscal_document_line', 'Tasa')
           ) AS e(name, relation_name, value)
     WHERE NOT EXISTS (
           SELECT 1
             FROM pg_constraint c
            WHERE c.conname = e.name
              AND c.conrelid = ('public.' || e.relation_name)::regclass
              AND c.contype = 'c'
              AND pg_get_constraintdef(c.oid) LIKE '%' || quote_literal(e.value) || '%');

    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION
            'V109 could not read % admitting the value it states. The vocabulary lives in the schema until A3 '
            'brings the enum and its parity test (L9(i)), so a missing or renamed constraint is a vocabulary '
            'nobody enforces.', v_missing;
    END IF;

    -- The document's total, as its own expression and not as `ck_order_total`'s: no rounding term, and the
    -- withheld part subtracted. Read from the definition, for the same reason as above.
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'ck_fiscal_document_total'
           AND conrelid = 'fiscal_document'::regclass
           AND contype = 'c'
           AND regexp_replace(pg_get_constraintdef(oid), '[()[:space:]]', '', 'g')
               = 'CHECKtotal_amount=subtotal-discount_total+tax_transferred-tax_withheld') THEN
        RAISE EXCEPTION
            'V109 could not read ck_fiscal_document_total as total_amount = subtotal - discount_total + '
            'tax_transferred - tax_withheld. That equality is the authority''s document and the other half of '
            'S8''s tolerance: a rounding term here would make the comparison in section 5 refuse every sale '
            'with a rounding adjustment.';
    END IF;

    -- And the vocabulary rule itself: no `CHECK` on the two tables may constrain `exportacion`, `tax_object` or
    -- `cancellation_motive` to a value. The pattern is PostgreSQL's rendering of a column compared with a
    -- literal or a literal list — `((col)::text = …` — and the null-coupling `V108` wrote for the cancellation
    -- columns does not match it, because it compares the column with NULL and not with a code.
    SELECT string_agg(c.conname, ', ' ORDER BY c.conname) INTO v_pinned
      FROM pg_constraint c
     WHERE c.contype = 'c'
       AND c.conrelid IN ('fiscal_document'::regclass, 'fiscal_document_line'::regclass)
       AND pg_get_constraintdef(c.oid)
           ~ '\((exportacion|tax_object|cancellation_motive)\)::text *[=<>]';

    IF v_pinned IS NOT NULL THEN
        RAISE EXCEPTION
            'V109 reads % pinning exportacion, tax_object or cancellation_motive to a value. ADR-0007 decision '
            '11 keeps the catalog values out of the schema: the authority''s c_Exportacion, c_ObjetoImp and '
            'c_MotivoCancelacion sets are stated nowhere in this tree, so a CHECK here would be a code recalled '
            'from memory, refusing a legal document the day the catalog is reissued. Remove it and let the '
            'writer that reads the catalog state the value.', v_pinned;
    END IF;

    -- The trigger: present, a constraint trigger, deferrable, initially deferred, enabled, per row, on the
    -- document, calling the comparison. A plain `AFTER` trigger under this name would judge each row as it is
    -- written — refusing a stamp that has not finished writing itself — and would pass every reader that only
    -- looked for the name (L9(h)).
    SELECT string_agg(e.name, ', ' ORDER BY e.name) INTO v_trigger
      FROM (VALUES ('trg_fiscal_document_snapshot_matches_sale')) AS e(name)
     WHERE NOT EXISTS (
           SELECT 1
             FROM pg_trigger t
            WHERE t.tgname = e.name
              AND t.tgrelid = 'fiscal_document'::regclass
              AND NOT t.tgisinternal
              AND t.tgenabled <> 'D'
              AND t.tgdeferrable
              AND t.tginitdeferred
              AND t.tgconstraint <> 0
              AND t.tgfoid = 'fiscal_document_snapshot_matches_sale'::regproc);

    IF v_trigger IS NOT NULL THEN
        RAISE EXCEPTION
            'V109 reads % as missing, disabled, as not a deferred constraint trigger, or as pointing at another '
            'function. Deferral is the whole of L9(h): an immediate judgement would compare a document against a '
            'transaction that has not finished writing it, so every accepted case would be an accident of the '
            'order its statements happened to run in.', v_trigger;
    END IF;

    -- Every column this file makes required is required. PostgreSQL refuses the `ADD COLUMN … NOT NULL` above
    -- over a row, so this is the second half of the same claim: a later edit that drops a `NOT NULL` would
    -- leave a document free to state nothing about the party it names, which is the shape F6 refuses.
    SELECT string_agg(e.relation_name || '.' || e.column_name, ', ' ORDER BY e.relation_name, e.column_name)
      INTO v_required
      FROM (VALUES
            ('fiscal_document', 'receptor_rfc'),
            ('fiscal_document', 'receptor_name'),
            ('fiscal_document', 'receptor_regimen'),
            ('fiscal_document', 'receptor_zip_code'),
            ('fiscal_document', 'receptor_uso_cfdi'),
            ('fiscal_document', 'emisor_rfc'),
            ('fiscal_document', 'emisor_name'),
            ('fiscal_document', 'emisor_regimen'),
            ('fiscal_document', 'tipo_comprobante'),
            ('fiscal_document', 'metodo_pago'),
            ('fiscal_document', 'exportacion'),
            ('fiscal_document', 'lugar_expedicion'),
            ('fiscal_document', 'currency'),
            ('fiscal_document', 'subtotal'),
            ('fiscal_document', 'discount_total'),
            ('fiscal_document', 'tax_transferred'),
            ('fiscal_document', 'tax_withheld'),
            ('fiscal_document', 'total_amount'),
            ('fiscal_document_line', 'clave_prod_serv'),
            ('fiscal_document_line', 'clave_unidad'),
            ('fiscal_document_line', 'description'),
            ('fiscal_document_line', 'quantity'),
            ('fiscal_document_line', 'unit_value'),
            ('fiscal_document_line', 'discount'),
            ('fiscal_document_line', 'tax_object'),
            ('fiscal_document_line', 'base'),
            ('fiscal_document_line', 'tax_code'),
            ('fiscal_document_line', 'tipo_factor'),
            ('fiscal_document_line', 'tax_amount')
           ) AS e(relation_name, column_name)
     WHERE NOT EXISTS (
           SELECT 1
             FROM pg_attribute a
            WHERE a.attrelid = ('public.' || e.relation_name)::regclass
              AND a.attname = e.column_name
              AND a.attnum > 0
              AND NOT a.attisdropped
              AND a.attnotnull);

    IF v_required IS NOT NULL THEN
        RAISE EXCEPTION
            'V109 reads % as absent or as nullable. Every one of these facts is required on a stamped document '
            '(S9 and ADR-0007 invariant F6), and the columns are added while the tables are empty precisely so '
            'that the requirement needs no default and no backfill.', v_required;
    END IF;
END $$;
