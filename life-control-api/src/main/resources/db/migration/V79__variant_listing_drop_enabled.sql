-- ============================================
-- V79 — variant_listing.enabled leaves (issue #777)
-- ============================================
-- `V58:99` created `variant_listing.enabled BOOLEAN NOT NULL DEFAULT true`, and nothing in the tree has ever
-- written it: the price write of `PUT /api/variants/{variantId}/stores/{storeId}` is one `INSERT … ON CONFLICT`
-- that sets `list_price` only (`VariantListingRepository`), no endpoint offers the flag, and the two store-scoped
-- queries decide "listed" by the presence of the row —
-- `CASE WHEN l.id IS NULL THEN FALSE ELSE TRUE END`. The entity carried it, both store-scoped reads ignored it,
-- and the comment beside that `CASE` said so out loud: "a predicate over a column nothing can set would be a rule
-- no test could exercise".
--
-- That was an honest workaround and it was also a trap. The API answers a field named `listed` for a state nobody
-- can reach, and the day an availability toggle landed, `listed` and `enabled` would become two different
-- questions the projection has to choose between — silently, because nothing reads the flag (`ProductVariantMode
-- ImmutabilityMigrationIntegrationTest` pins the freeze rule; this is the flag beside it).
--
-- THE DECISION THIS FILE CARRIES, AND WHERE IT IS WRITTEN
-- ------------------------------------------------------
-- Issue #777 offered two branches: give the flag a writer, or drop it. The decision is **drop it**, because
-- ADR-0006 decision 6 already defines "sellable in this store" as *the listing row exists* — a writer would have
-- changed what `listed` means for every client, and it would have been an availability feature, not a cleanup.
-- ADR-0006 decision 5 is where the decision is recorded, together with its `## Schema` block.
--
-- Edits to `V58` are not available, on purpose: Flyway validates checksums on migrate, so a byte changed in an
-- applied file makes every existing database refuse to start. `V77` answered the identical question the identical
-- way — the decision lives in the header of the file that carries it, not in a rewrite of the file it retires —
-- and `V76`'s header does the same for ADR-0006 step 6.
--
-- WHAT THE DROP REFUSES
-- ---------------------
-- Dropping the column is safe while it holds nothing but the schema's default. It is not safe when a row holds
-- `false`: nothing in the tree can write that value, so a `false` was written by a hand — an operator at a psql
-- prompt, a script, a client that predates the flag being ignored — and it is then the only record of an intent
-- somebody expressed. `V74` refuses for the same reason about a quantity nobody could rebuild and `V77` about a
-- price nobody could reproduce: a migration that silently discards the sole record of a fact is not a cleanup.
--
-- So the guard counts them, names the first offender, and states the two next steps honestly: decide that the
-- intent is dead and delete the row's value deliberately, or decide that it is real — in which case the
-- per-store availability toggle is a feature to build (issue #777, branch 1), not a column to drop.
--
-- WHY THE GUARD RUNS INSIDE A `NO FORCE` WINDOW
-- ----------------------------------------------
-- `V58:189` arms `variant_listing` with `apply_tenant_isolation`, and `V52:152-153` installs every policy with
-- `ENABLE` **and** `FORCE`: the owner is filtered too, and a migration runs as the owner with no `app.company_id`
-- set. Without a window the count below reads zero rows, the guard finds no offender, and the file drops the
-- column on exactly the database it exists to refuse — the shape of guard that gets edited under pressure at 3am.
--
-- The window is the mechanism `V57`, `V62`, `V64`, `V66`, `V72`, `V74` and `V77` already use: it is transactional,
-- it is checked before it is relied on — a window that did not take answers every question with zero rows — and it
-- is closed by an assertion rather than by good intentions.

-- ============================================
-- 1. the NO FORCE window, opened before the first read
-- ============================================
ALTER TABLE variant_listing NO FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    v_still_forced text;
BEGIN
    SELECT string_agg(c.relname, ', ' ORDER BY c.relname) INTO v_still_forced
      FROM pg_class c
     WHERE c.relkind = 'r' AND c.relforcerowsecurity
       AND c.relname IN ('variant_listing');

    IF v_still_forced IS NOT NULL THEN
        RAISE EXCEPTION
            'V79 could not open its NO FORCE window on %: the guard below would read no listing, find no '
            'disabled row, and drop a column while it still holds the only record of somebody''s intent. A '
            'window that did not take is the whole defect this block is here to prevent.', v_still_forced;
    END IF;
END $$;

-- ============================================
-- 2. the proof the drop refuses to run without
-- ============================================
DO $$
DECLARE
    v_offenders bigint;
    v_variant uuid;
    v_store uuid;
BEGIN
    SELECT count(*)
      INTO v_offenders
      FROM variant_listing vl
     WHERE vl.enabled = false;

    IF v_offenders > 0 THEN
        SELECT vl.product_variant_id, vl.company_store_id
          INTO v_variant, v_store
          FROM variant_listing vl
         WHERE vl.enabled = false
         ORDER BY vl.product_variant_id, vl.company_store_id
         LIMIT 1;
        RAISE EXCEPTION
            'refusing to drop variant_listing.enabled: % listing(s) hold enabled = false, and nothing in the tree '
            'can write that value — so it is the only record of an intent somebody expressed. First offender: '
            'variant %, store %. Next step: decide what that intent is still worth. If nothing depends on it, '
            'clear it deliberately (the row keeps its price and its position either way) and re-run this '
            'migration; if something does, the per-store availability toggle is a feature to build (issue #777, '
            'branch 1) and this file is the wrong change.', v_offenders, v_variant, v_store;
    END IF;
END $$;

-- ============================================
-- 3. the window closes, and the close is checked
-- ============================================
-- After the guard and never before it: `FORCE` filters the owner, so a read placed below this `ALTER` would see
-- nothing and pass. `V72:148-155`, `V74` and `V77` learned the same lesson in the same words.
ALTER TABLE variant_listing FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    v_not_forced text;
BEGIN
    SELECT string_agg(c.relname, ', ' ORDER BY c.relname) INTO v_not_forced
      FROM pg_class c
     WHERE c.relkind = 'r' AND NOT c.relforcerowsecurity
       AND c.relname IN ('variant_listing');

    IF v_not_forced IS NOT NULL THEN
        RAISE EXCEPTION
            'V79 left % without FORCE ROW LEVEL SECURITY: a table whose owner is exempt from its own policy is '
            'the state ADR-0005 exists to remove, and a window left open weakens the table for everything that '
            'runs after it (V55).', v_not_forced;
    END IF;
END $$;

-- ============================================
-- 4. the drop
-- ============================================
ALTER TABLE variant_listing DROP COLUMN enabled;

-- ============================================
-- 5. the self-check: the column is gone, and what shared its name is not
-- ============================================
-- Two blocks, because they fail for unrelated reasons. The first answers "did the drop reach exactly the column it
-- was aimed at" — which a half-applied file fails. The second answers "did a drop by name take a column from
-- another table that legitimately carries it": `enabled` is the name of live columns on `product_variants` (its
-- own writer: DELETE and PATCH `…/variants/{variantId}`) and on `company_stores`, and a statement written against
-- the wrong table would delete a flag that has a writer and a meaning.
DO $$
DECLARE
    v_present text;
BEGIN
    SELECT string_agg(table_name || '.' || column_name, ', ') INTO v_present
      FROM information_schema.columns
     WHERE table_schema = 'public'
       AND table_name = 'variant_listing'
       AND column_name = 'enabled';
    IF v_present IS NOT NULL THEN
        RAISE EXCEPTION 'variant_listing.enabled must be gone, but % still carries it', v_present;
    END IF;
END $$;

DO $$
DECLARE
    v_missing text;
    v_live integer;
BEGIN
    IF to_regclass('public.variant_listing') IS NULL THEN
        RAISE EXCEPTION
            'the table itself must survive this file: it is what makes a variant sellable in a store (ADR-0006 '
            'decision 6) and the only record of its price. It is missing.';
    END IF;

    SELECT string_agg(c.column_name, ', ' ORDER BY c.column_name) INTO v_missing
      FROM (VALUES ('id'), ('company_id'), ('product_variant_id'), ('company_store_id'), ('list_price'), ('currency')) AS c(column_name)
     WHERE NOT EXISTS (SELECT 1
                         FROM information_schema.columns ic
                        WHERE ic.table_schema = 'public'
                          AND ic.table_name = 'variant_listing'
                          AND ic.column_name = c.column_name);
    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION
            'the drop took more than its column: variant_listing lost %. It keeps its identity, its tenant, the '
            'pair it prices and the money it states, and none of those is this file''s to remove.', v_missing;
    END IF;

    SELECT count(*) INTO v_live
      FROM information_schema.columns
     WHERE table_schema = 'public'
       AND column_name = 'enabled'
       AND table_name IN ('product_variants', 'company_stores');
    IF v_live <> 2 THEN
        RAISE EXCEPTION
            'product_variants.enabled and company_stores.enabled are live columns of other tables and both must '
            'survive: % of the 2 are present. The variant-wide flag has its own writer and its own meaning, and '
            'the store flag is read by the store hierarchy — neither is the column this file retires.', v_live;
    END IF;
END $$;
