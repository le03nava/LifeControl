-- ============================================
-- V88 — the tax pointer ADR-0006 decision 5 declares, added by the ALTER V58 promised
-- ============================================
-- ADR-0006 is accepted (`docs/adr/0006-catalog-identity.md`, 2026-11-01). This file is the debt
-- `V58__variant_listing.sql:26-32` named and left open, and nothing else:
--
--   * decision 5 (`:57-59`) replaces the price half of `product_variant_store_stock` with
--     `(variant, store)` + `list_price`, `currency`, `tax_rate_id`, `enabled`, and no stock and no cost;
--   * the `## Schema` block writes that pointer as `tax_rate_id UUID REFERENCES tax_rate (id)` (`:116`);
--   * `V58:89-91` said the column would arrive as an addition, not a rewrite, "in the slice that creates
--     `tax_rate`". That slice is `V82__tax_rate.sql`, and the condition is met.
--
-- Two year-old facts this file depends on, both read and neither guessed:
--   * `V82:41-55` creates `tax_rate` with a primary key on `id` and a unique key on
--     `(company_id, tax_code, effective_from)`, and arms it through `apply_tenant_isolation`, which is
--     `ENABLE` **and `FORCE`** (`V52:152-153`). It has **no** unique key on `(company_id, id)`, which is
--     why section 1 exists.
--   * `variant_listing` is already armed by `V58`, so nothing here arms it again: `apply_tenant_isolation`
--     is called once per table, by the migration that creates it, and a second call would be a second
--     `tenant_table` row for one table — the shape `V64` records as a vacuous self-check waiting to happen.
--
-- THE ONE DEPARTURE FROM THE ADR'S LITERAL BLOCK, and it is deliberate: `:116` writes
-- `REFERENCES tax_rate (id)`; this file writes
--
--     FOREIGN KEY (company_id, tax_rate_id) REFERENCES tax_rate (company_id, id)
--
-- Reason, in the order it matters. `tax_rate` is a **tenant** table under `ENABLE`+`FORCE`, and a
-- PostgreSQL referential check **bypasses row security** by design (it has to: otherwise the constraint
-- would be satisfiable or not depending on who is inserting). An id-only reference would therefore accept
-- company A's listing naming company B's rate: the FK only asks whether the id exists, and it exists. The
-- resulting row is then unreadable inside A's own scope — the resolver runs under A's policy, finds no
-- rate row for that id, and taxes the line at zero **in silence**, which is the failure mode ADR-0005 was
-- accepted to end. The composite key refuses the pair instead, and `V58:114-119` already proves that shape
-- one table over (`fk_listing_variant`, `fk_listing_store` both name `company_id` first).
--
-- Section 1 is the price of that key: a foreign key needs a unique key on its target, and `(company_id, id)`
-- does not exist yet. `id` is already unique on its own, so `uq_tax_rate_company_id` adds an index and no
-- new truth about the data.
--
-- WHY THE COLUMN IS NULLABLE, and it is not laziness: `V58` copied a listing for every store row that
-- carries a `list_price`, and those rows state a price and no tax. `NOT NULL` here would have required this
-- file to invent a rate for every one of them — the ADR says a rate is resolved for the **document's date**
-- (`docs/adr/0002-money-first-class.md`, decision 6), and a migration has no document. So the column starts
-- NULL and stays NULL until a write path resolves a rate; nothing in this tree writes it yet
-- (`odd/tasks/variant-listing-tax-rate.md`, T4).
-- ============================================

-- ─── 1. the unique key the composite foreign key needs as a target ───────────────────────────────
-- Not a new fact: `id` alone is the primary key, so a pair of it with the tenant is unique by construction.
-- It exists because `REFERENCES` has no way to say "the id, and also the column beside it".
ALTER TABLE tax_rate
    ADD CONSTRAINT uq_tax_rate_company_id UNIQUE (company_id, id);

COMMENT ON CONSTRAINT uq_tax_rate_company_id ON tax_rate IS
    'Target of variant_listing.fk_listing_tax_rate (V88). `id` is already unique; the pair exists so the '
    'pointer can carry the tenant and a listing cannot name another tenant''s rate.';

-- ─── 2. the column and its tenancy-checked reference ─────────────────────────────────────────────
-- Column order in the ADR's block puts `tax_rate_id` before `enabled`, but `V79` dropped `enabled`, so the
-- addition lands after `currency` — an `ALTER` appends, which is exactly the "addition to this statement
-- and not a rewrite of it" `V58:89-91` promised.
ALTER TABLE variant_listing
    ADD COLUMN tax_rate_id UUID,
    ADD CONSTRAINT fk_listing_tax_rate
        FOREIGN KEY (company_id, tax_rate_id) REFERENCES tax_rate (company_id, id);

COMMENT ON COLUMN variant_listing.tax_rate_id IS
    'The rate this listing is priced against (ADR-0006 decision 5). NULL since V88 because no write path '
    'resolves a rate yet: decision 6 resolves it for the document date, and a listing has no date. Composite '
    'with company_id on purpose — a referential check bypasses row security, so an id-only pointer would let '
    'one tenant name another tenant''s rate, which its own policy then makes unreadable and the resolver '
    'reads as no tax at all.';

-- ─── 3. the self-checks, all of them structural, and why none of them counts rows ────────────────
-- A row check here would be vacuous by construction: the column was created by this transaction, so every
-- row is NULL because nothing has written one yet — counting "0 rows with a rate" would assert a fact this
-- file creates rather than one it can fail to achieve. That is the shape `V64` records. What a later reader
-- CAN silently lose is the constraint's shape (an id-only FK looks identical in `information_schema`), so
-- that is what is read back, together with the nullability and the arming.
DO $$
DECLARE
    v_type        text;
    v_nullable    text;
    v_definition  text;
    v_fk_type     "char";
    v_armed       bigint;
    v_unique      bigint;
BEGIN
    SELECT data_type, is_nullable
      INTO v_type, v_nullable
      FROM information_schema.columns
     WHERE table_name = 'variant_listing' AND column_name = 'tax_rate_id';

    IF v_type IS DISTINCT FROM 'uuid' OR v_nullable IS DISTINCT FROM 'YES' THEN
        RAISE EXCEPTION
            'V88 expects variant_listing.tax_rate_id as nullable uuid and read %.%. NOT NULL would refuse '
            'every listing V58 already copied, and a migration has no document date to resolve a rate for.',
            v_type, v_nullable;
    END IF;

    -- The whole reason this file does not copy `:116` verbatim: the definition has to name the tenant.
    SELECT pg_get_constraintdef(oid), contype
      INTO v_definition, v_fk_type
      FROM pg_constraint
     WHERE conname = 'fk_listing_tax_rate';

    IF v_fk_type IS DISTINCT FROM 'f'
       OR v_definition NOT LIKE 'FOREIGN KEY (company_id, tax_rate_id)%'
       OR v_definition NOT LIKE '%REFERENCES tax_rate(company_id, id)%' THEN
        RAISE EXCEPTION
            'V88 read fk_listing_tax_rate as "%" (type %). It has to be FOREIGN KEY (company_id, '
            'tax_rate_id) REFERENCES tax_rate(company_id, id): a single-column reference to tax_rate (id) '
            'accepts another tenant''s rate, because referential checks bypass row security.', v_definition,
            v_fk_type;
    END IF;

    SELECT count(*) INTO v_unique
      FROM pg_constraint
     WHERE conname = 'uq_tax_rate_company_id' AND conrelid = 'tax_rate'::regclass AND contype = 'u';

    IF v_unique <> 1 THEN
        RAISE EXCEPTION
            'V88 read % unique key(s) named uq_tax_rate_company_id on tax_rate. The composite foreign key '
            'cannot exist without its target, so a missing one means the key was dropped after the fact.',
            v_unique;
    END IF;

    -- Armed exactly once, and not re-armed here: a second tenant_table row for one table is the defect this
    -- file's header names. `V58` armed it; this check is what would notice a later edit that armed it again.
    SELECT count(*) INTO v_armed
      FROM tenant_table
     WHERE table_name = 'variant_listing'::regclass;

    IF v_armed <> 1 THEN
        RAISE EXCEPTION
            'V88 expects exactly one tenant_table row for variant_listing and read %. apply_tenant_isolation '
            'is called once per table, by the file that creates it (V58); this file only adds a column.',
            v_armed;
    END IF;
END $$;

-- ─── 4. what this file deliberately does NOT do ──────────────────────────────────────────────────
-- * **No writer and no reader.** No service sets `tax_rate_id`: the resolver that will
--   (`tax/service/TaxRateService`) already answers with the row id it would store, and the slice that wires
--   it is `odd/tasks/variant-listing-tax-rate.md` T4, not this one. A migration that invented a rate for
--   every existing listing would be stating a tax nobody resolved.
-- * **No `NOT NULL` and no backfill**, for the same reason: decision 6 resolves for the document date.
-- * **No privilege change.** `V52:190-199` grants SELECT/INSERT/UPDATE/DELETE on every table, present and
--   future, through the default privileges; `variant_listing` is not append-only, so the `REVOKE` pattern of
--   `V60:286-296` does not apply to it.
-- * **No index on `tax_rate_id`.** The reads this column will serve are per-listing or per-store, and
--   `uq_listing_variant_store` plus the policy's tenant predicate already narrow them; a reverse lookup
--   ("which listings use this rate") has no caller in the tree, and an index without a reader costs every
--   write (`V58`'s header records the same decision for its own tenant predicate). Add it with the reader.
-- * **No `sales_order_items.tax_code` change.** The line's snapshot is ADR-0002 T4's remaining debt and is
--   independent of this pointer: a line keeps the code, the rate and the amount, whether or not a listing
--   ever named a rate.
