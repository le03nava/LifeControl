-- ============================================
-- V76 — ADR-0006 decision 1: variant_mode is immutable once the product's variants hold stock movements
-- ============================================
-- ADR-0006 is accepted (`docs/adr/0006-catalog-identity.md`, 2026-11-01). Its decision 1 (`:46-47`) says the
-- mode is "set at creation, immutable once its variant has stock movements", and its migration step 6
-- (`:165-167`) deferred the guard with a measured reason instead of a promise: "today the only 'movement' is
-- a per-store stock row, so the guard is 'refuse a mode change while any variant holds a listing or non-zero
-- stock'; once `stock_movement` exists, the guard reads the ledger." `stock_movement` has existed since
-- `V60__stock_ledger_schema.sql:116-153`. The guard does not exist — there is no trigger on `products` at all
-- — which is GitHub issue #774, and whose approved criteria are decision 1's letter.
--
-- WHAT THE MISSING GUARD COSTS, measured in the tree:
--   * `UPDATE products SET variant_mode = 'SIMPLE'` on a product with two variants is reachable today, and it
--     leaves a state that `V56`'s `assert_variant_matches_mode()` (`V56:95-98`) forbids every write to
--     create: "product % is SIMPLE and already has a variant". A database that can hold a state its own
--     trigger refuses to re-create is a rule enforced on the way in and not on the way out.
--   * It reinterprets the ledger, not the catalogue. `stock_movement` names a `product_variant_id`
--     (`V60:121`) and says nothing about the mode, so a reclassification silently changes what every movement
--     of that article was booked against — and `V60:260-283` makes those rows append-only, so the correction
--     that would undo the reinterpretation cannot be written.
--   * The API never changes the mode: `ProductService.updateProduct` maps the name, the category and nothing
--     of the mode, and the update path posts neither `variantMode` nor `variants`. The rule therefore lives
--     in the database, where it holds for every writer — an operator's script, a future migration, the
--     suite — instead of in one service that happens to be today's only caller.
--
-- WHY `variant_listing` IS NOT A FREEZE CONDITION (#774):
--   Step 6 was written before the ledger existed and named "a listing or non-zero stock". Decision 1 — the
--   accepted decision — names stock movements, and #774's approved criteria are decision 1's letter. Leaving
--   the listing out is a recorded decision rather than an oversight: freezing on a price row would refuse a
--   reclassification no ledger has to reinterpret, and adding it later is additive, one more `EXISTS`.
--
-- WHY THIS GUARD NEEDS NO TENANT-SCOPE PROBE, unlike `V56`'s trigger (`V56:80-96`):
--   `V56` had to read `products` from a trigger on `product_variants`, so a writer with no scope read no
--   product, and stating the scope from `NEW` inside a BEFORE trigger would have made the policy's `WITH
--   CHECK` — evaluated afterwards — compare the row to its own claim. None of that applies here:
--     * A BEFORE UPDATE trigger on `products` only runs for a row the `UPDATE` could **see** under the
--       policy, and that row is the one whose old and new mode the decision is about. There is no lookup
--       whose result could come back empty, so there is no "the mode is unknown, refuse everything" branch to
--       write: an invisible product is a row the statement never touches, which is the answer the policy
--       already gives.
--     * The one read the rule needs joins `stock_movement` to `product_variants`, and both are keyed on the
--       same `company_id` as the product: `fk_product_variants_product_same_company`
--       (`V40__product_catalog_company_contract.sql:133-135`) refuses a variant whose company disagrees with
--       its product's, and `fk_movement_variant` (`V60:137-138`) refuses a movement whose company disagrees
--       with its variant's. A writer scoped to the product's tenant therefore sees every row the join needs,
--       and a writer scoped to another tenant cannot reach the product to fire the trigger at all.
--     * MEASURED, not argued: `tenant_table` lists `products`, `product_variants` and `stock_movement` as
--       `tenant` tables keyed on `company_id` (`V53__tenant_isolation_policies.sql:122-123` and `V60` §7),
--       and `pg_class` reports `relrowsecurity` **and** `relforcerowsecurity` true for all three, which is
--       what `V52__tenant_isolation_machinery.sql:152-153` applies. Both facts are read back by
--       `ProductVariantModeImmutabilityMigrationIntegrationTest`, whose cross-tenant case changes zero rows.
--
-- WHY THE COLUMN LIST AND THE FIRST RETURN:
--   `BEFORE UPDATE OF variant_mode` keeps an ordinary edit of the article — a renamed product, a category
--   move — from being the question this trigger asks. The column being in the `SET` list fires the trigger
--   even when the value does not move, which is every ORM that writes each mapped column and every
--   `UPDATE products SET variant_mode = variant_mode`: the first statement below returns `NEW` for those, so
--   a writer that re-sends the whole row is not refused for a change that did not happen.
--
-- WHAT THIS FILE DOES NOT DO:
--   * it does not touch `V54`, `V56`, `ProductService`, the DTOs or any response shape. #774 is a
--     database-level guard, and the API already cannot change the mode;
--   * it does not freeze on `variant_listing`, for the reason stated above;
--   * it does not repair a product that was reclassified while it had stock, and it does not backfill: a
--     trigger fires on writes, so this file refuses future ones and leaves stored rows exactly as they are.
--
-- Forward-only. Undoing it by hand means `DROP TRIGGER trg_product_variant_mode_immutable ON products` and
-- `DROP FUNCTION refuse_variant_mode_change()`; no data is written by this file in either direction.
-- ============================================

-- ─── 1. the rule ───────────────────────────────────────────────────────────────────────────────
-- The message quotes the decision and explains itself, because it is what an operator reads when a script
-- tried to reclassify a selling article: it names the product, both values, and why the ledger forbids it.
CREATE OR REPLACE FUNCTION refuse_variant_mode_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    -- A writer that re-sends the row, or re-states the value it already has, is not changing anything: the
    -- column is in the SET list, the trigger fires, and the answer is that the write proceeds untouched.
    IF NEW.variant_mode IS NOT DISTINCT FROM OLD.variant_mode THEN
        RETURN NEW;
    END IF;

    -- No `set_tenant_scope` here on purpose: the header measures why this trigger reads under its writer's
    -- own scope instead of stating one, and why that is enough for a guard on the table it is armed on.
    -- The join needs no `company_id` predicate of its own — the composite keys make the three rows one tenant.
    IF EXISTS (SELECT 1
                 FROM stock_movement m
                 JOIN product_variants v ON v.id = m.product_variant_id
                WHERE v.product_id = OLD.id) THEN
        RAISE EXCEPTION
            'product % cannot change variant_mode from % to % (ADR-0006 decision 1): its variants already hold stock movements, and those rows name the variants and are append-only, so reclassifying the article would reinterpret facts that can never be corrected',
            OLD.id, OLD.variant_mode, NEW.variant_mode;
    END IF;

    RETURN NEW;
END $$;

COMMENT ON FUNCTION refuse_variant_mode_change() IS
    'ADR-0006 decision 1 and migration step 6: the mode is set at creation and immutable once the product''s '
    'variants hold stock movements. It reads the ledger under the writer''s own tenant scope and needs no '
    'scope probe of its own: a BEFORE UPDATE trigger only runs for a row the UPDATE could see, and the '
    'composite keys (V40:133-135, V60:137-138) make product, variant and movement one tenant.';

-- ─── 2. the trigger, converged on replay ──────────────────────────────────────────────────────
-- DROP before CREATE for the same reason `V52` drops its policies and `V56:111-113` drops its trigger: a
-- replay of this file against a database that already ran it has to converge instead of failing on its own
-- leftover.
DROP TRIGGER IF EXISTS trg_product_variant_mode_immutable ON products;

CREATE TRIGGER trg_product_variant_mode_immutable BEFORE UPDATE OF variant_mode ON products
    FOR EACH ROW EXECUTE FUNCTION refuse_variant_mode_change();

-- ─── 3. the self-check this branch ends every migration with ───────────────────────────────────
-- Two questions, because there are two ways this file can report success and have installed nothing:
--   * the trigger exists, is ENABLED — `tgenabled = 'O'`, the ordinary state; a `D` is a guard that exists
--     and is switched off — and is the one declared above: BEFORE, FOR EACH ROW, `UPDATE OF variant_mode`.
--     A statement-level trigger would see no row, an AFTER one would refuse a change it had already stored,
--     and a trigger without the column list would ask its question about every edit of a selling article.
--   * no `NO FORCE` window stayed open. This file writes **no row**: it creates a function and a trigger and
--     nothing else, so it needs no window and must not leave one behind. That is the failure this check
--     catches — a window opened "just in case" and not closed is a database where every armed table reads
--     zero rows until the next boot refuses it.
DO $$
DECLARE
    v_enabled    "char";
    v_definition text;
    v_open       text;
BEGIN
    SELECT t.tgenabled, pg_get_triggerdef(t.oid)
      INTO v_enabled, v_definition
      FROM pg_trigger t
     WHERE t.tgname = 'trg_product_variant_mode_immutable'
       AND t.tgrelid = 'products'::regclass
       AND NOT t.tgisinternal;

    IF v_definition IS NULL THEN
        RAISE EXCEPTION
            'V76 did not install trg_product_variant_mode_immutable on products, so a reclassification of an article whose variants already hold stock is still accepted and the ledger is reinterpreted in silence.';
    END IF;

    IF v_enabled <> 'O' THEN
        RAISE EXCEPTION
            'V76 installed trg_product_variant_mode_immutable but PostgreSQL reports tgenabled = %: a disabled trigger is a guard that exists and is switched off, which reads exactly like the missing one.',
            v_enabled;
    END IF;

    IF strpos(v_definition, 'BEFORE UPDATE OF variant_mode ON') = 0
       OR strpos(v_definition, 'FOR EACH ROW EXECUTE FUNCTION refuse_variant_mode_change()') = 0 THEN
        RAISE EXCEPTION
            'V76 left a trigger that is not the one it declares: %. It has to be BEFORE UPDATE OF variant_mode FOR EACH ROW, or it either sees no row or refuses edits the decision never forbade.',
            v_definition;
    END IF;

    SELECT string_agg(c.relname, ', ' ORDER BY c.relname)
      INTO v_open
      FROM pg_class c
     WHERE c.relkind = 'r'
       AND c.relname IN ('products', 'product_variants', 'stock_movement')
       AND NOT (c.relrowsecurity AND c.relforcerowsecurity);

    IF v_open IS NOT NULL THEN
        RAISE EXCEPTION
            'V76 left a NO FORCE row level security window open on: %. This file writes no row and needs none, so an open window is a mistake that hides every row from every writer until the startup check refuses the database.',
            v_open;
    END IF;
END $$;
