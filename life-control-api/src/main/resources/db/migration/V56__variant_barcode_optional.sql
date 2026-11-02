-- ============================================
-- V56 — ADR-0006 decision 4: a barcode is required for a MULTI variant and optional for a SIMPLE one
-- ============================================
-- ADR-0006 is accepted (`docs/adr/0006-catalog-identity.md`, 2026-11-01). This file is its migration
-- step 3 and the trigger of its `## Schema` block, and nothing more: `bar_code` stops being required, the
-- tenant key becomes the partial `uq_variant_bar_code`, and `trg_variant_matches_mode` is what makes the
-- product's mode decide what a variant may be (invariants C2, C3 and C5). The listing split (steps 4-5)
-- and the mode immutability (step 6) are the later slices of `odd/tasks/catalog-identity.md` and are
-- deliberately absent here.
--
-- WHY THE BARCODE STOPS BEING REQUIRED, measured in the tree:
--   * `V14__product_variant_definition.sql:72` made it required in the same statement that made the
--     variant the global sellable definition — `ALTER COLUMN bar_code SET NOT NULL`, with a global
--     `UNIQUE (bar_code)` at `:74` — so every article in the catalogue had to carry a barcode and a loaf
--     of bread could not exist.
--   * `V40__product_catalog_company_contract.sql:124-126` re-scoped that key to the tenant
--     (`uq_product_variants_company_bar_code`) and inherited the requirement: a key that covers every
--     row is a `NOT NULL` in disguise.
--   * The request mirrored it: `ProductVariantRequest.barCode` carried `@NotBlank` until this slice, and
--     what is left of it is the size the column has (`ProductVariantRequest.java:42-43`). A DTO
--     annotation cannot ask the question decision 4 asks — which mode the product is — while `V54` is
--     exactly the column that can answer it.
--
-- NO BACKFILL, AND WHY THAT IS NOT AN OMISSION. The two rules the trigger introduces are true of every
-- row this database can already hold, so there is nothing to repair and a no-op `UPDATE` would only
-- report work it did not do:
--   * every existing variant has a barcode, because `V14:72` never allowed a NULL one; and
--   * every product with more than one variant is already `MULTI`, because `V54` derived that from the
--     catalogue itself and its own `DO` block refuses a run where the derivation matched nothing.
-- What this file does NOT do is re-validate rows already stored: a trigger fires on writes. A product
-- created through the API between `V54` and this file was `SIMPLE` by the column's default, so if a
-- caller gave it two variants in that window, the next write to either of them is refused here with the
-- `SIMPLE` message until the product is reclassified. Stating it is the honest half; inventing a
-- reclassification for those rows is a data decision this migration does not own.
--
-- WHY THE TRIGGER DOES **NOT** SET THE TENANT SCOPE, though the first draft of this file did — and why
-- that draft was a tenant-isolation hole. `V52__tenant_isolation_machinery.sql:152-153` calls `ENABLE`
-- **and `FORCE`** on every table it arms and `V53__tenant_isolation_policies.sql:122-123` arms `products`
-- and `product_variants`, so every read and every write below runs under a policy and a writer that
-- stated no `app.company_id` reads no product. The tempting fix is `set_tenant_scope(NEW.company_id)`
-- (`V52:60-63`) before the `SELECT`: the mode is then read for every writer. Measured against this
-- container, that fix is worse than the problem — a BEFORE ROW trigger runs **before** the policy's
-- `WITH CHECK`, and the check is evaluated against the row the trigger left behind, with the session
-- setting the trigger just changed. A caller scoped to one company would then be checked against the
-- company its own row names, so the policy stops refusing a cross-tenant insert: the barrier ADR-0005
-- exists for would answer "yes" to whatever the row claims. The trigger therefore reads under whatever
-- scope its writer has, and a mode that comes back NULL `RAISE`s instead of skipping the check, which is
-- the honest outcome: the product is not visible to that writer, so the rules it must match are unknown
-- and the row is refused. A migration that must insert variants runs inside the `NO FORCE` window `V54`
-- and `V55` document, or sets a scope itself; there is no third door, and this file deliberately does not
-- add one.
-- ============================================

-- ─── 1. the barcode stops being required ────────────────────────────────────────────────────────
-- A single-variant article has no barcode to give (decision 4). Nothing is dropped from a row: the
-- column keeps its values, it only stops refusing the absence of one.
ALTER TABLE product_variants
    ALTER COLUMN bar_code DROP NOT NULL;

COMMENT ON COLUMN product_variants.bar_code IS
    'ADR-0006 decision 4: required on every variant of a MULTI product and optional on a SIMPLE one. '
    'Unique per tenant where it exists (uq_variant_bar_code, partial).';

-- ─── 2. the tenant key, now over the rows that have a barcode ───────────────────────────────────
-- A partial `UNIQUE INDEX` and not a constraint: PostgreSQL has no partial constraint, and the null
-- rows are precisely the ones that must not collide with each other (C5).
ALTER TABLE product_variants DROP CONSTRAINT uq_product_variants_company_bar_code;

CREATE UNIQUE INDEX uq_variant_bar_code
    ON product_variants (company_id, bar_code)
    WHERE bar_code IS NOT NULL;

-- ─── 3. the mode, enforced on every write ──────────────────────────────────────────────────────
-- The two messages are the ADR's own, verbatim, because they are what an operator reads when a client
-- got this wrong: they name the product that has to change.
CREATE OR REPLACE FUNCTION assert_variant_matches_mode() RETURNS trigger LANGUAGE plpgsql AS $$
    -- SIMPLE allows exactly one variant; MULTI requires a barcode on each
DECLARE mode varchar(6);
BEGIN
    -- No `set_tenant_scope` here on purpose: the header measures why stating the row's own tenant would
    -- make the policy's `WITH CHECK` — evaluated after this trigger — accept any tenant the row claims.
    SELECT variant_mode INTO mode FROM products WHERE id = NEW.product_id;

    -- Not a branch that skips: an unreadable product would make both rules below pass over a row
    -- nobody validated, which is the state this whole file exists to prevent. It is also what a writer
    -- with no tenant scope sees, and refusing there is the correct answer — the row it wants to write
    -- would fail the same policy one statement later.
    IF mode IS NULL THEN
        RAISE EXCEPTION
            'assert_variant_matches_mode: the product % of company % could not be read, so the variant '
            'mode it must match is unknown. Refusing the write instead of validating nothing.',
            NEW.product_id, NEW.company_id;
    END IF;

    IF mode = 'SIMPLE' AND EXISTS (SELECT 1 FROM product_variants
                                   WHERE product_id = NEW.product_id AND id <> NEW.id) THEN
        RAISE EXCEPTION 'product % is SIMPLE and already has a variant', NEW.product_id;
    END IF;
    IF mode = 'MULTI' AND NEW.bar_code IS NULL THEN
        RAISE EXCEPTION 'bar_code is required for a variant of MULTI product %', NEW.product_id;
    END IF;
    RETURN NEW;
END $$;

COMMENT ON FUNCTION assert_variant_matches_mode() IS
    'ADR-0006 decisions 1 and 4: a SIMPLE product has exactly one variant and a MULTI one requires a '
    'barcode on each. It reads the product under the writer''s own tenant scope and refuses the row when '
    'it is not visible, rather than stating a scope itself: a BEFORE ROW trigger runs before the policy''s '
    'WITH CHECK, so setting the scope from the new row would make that check compare the row to itself.';

-- DROP before CREATE for the same reason V52 drops its policies: a replay of this file against a
-- database that already ran it has to converge instead of failing on its own leftover.
DROP TRIGGER IF EXISTS trg_variant_matches_mode ON product_variants;

CREATE TRIGGER trg_variant_matches_mode BEFORE INSERT OR UPDATE ON product_variants
    FOR EACH ROW EXECUTE FUNCTION assert_variant_matches_mode();
