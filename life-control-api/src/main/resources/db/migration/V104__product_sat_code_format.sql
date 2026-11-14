-- ============================================
-- V104 — S4/T3: the product's fiscal key is constrained the way the category's already is
-- ============================================
-- `odd/tasks/cfdi-fiscal-document.md`, spec S4 and decision L6(e) of the same record:
--
--   «The product's fiscal key is constrained, not free text: a format check with the shape the category's
--     key already has, in the same slice that starts reading it.»
--
-- THE MEASUREMENT THAT PUTS THIS FILE HERE. Three of the keys a fiscal document needs already have a column:
-- `measure_units.sat_code` is `NOT NULL UNIQUE` since `V1:289`, `product_categories.sat_code` carries
-- `ck_product_categories_sat_code_format CHECK (sat_code IS NULL OR sat_code ~ '^[0-9]{8}$')` since
-- `V34:40-41`, and `products.sat_code` has been `VARCHAR(20)` free text with no check and no catalog since
-- `V1:189`. The concept is the one element a stamp cannot omit, so the weakest of the three is the one the
-- document needs most. This file gives it the category's exact predicate, named, with the same name shape.
--
-- WHY IT REFUSES LOUDLY INSTEAD OF ABORTING. `products.sat_code` has been writable free text since `V1`, so
-- the rows that violate the format are keys an operator typed, and they are already in production. Adding the
-- constraint over them would abort with `SQLSTATE 23514`, a message naming the constraint and **no row** — a
-- deploy that stops with nothing an operator can act on. So the `DO` block below runs first: it counts the
-- offending rows and names up to five `id (sat_code)` pairs, which is the difference between "fix these five
-- articles" and "the migration failed somewhere". The style is `V99`'s, which names the offender in its
-- refusal for the same reason.
--
-- WHY `NOT VALID` IS REFUSED. A `NOT VALID` constraint is validated only for new writes: it would let every
-- row already in the table keep a key no stamp can use, while a green deploy claims the column is constrained.
-- A constraint that only judges the future is a claim this tree cannot make about its own data, and the whole
-- point of the slice is that the concept's key stops being free text — including where it already is.
--
-- THE `NO FORCE` WINDOW IS THE MEASUREMENT, NOT A HABIT. `V53:122` arms `products` through
-- `apply_tenant_isolation`, which `V52:152-153` writes as `ENABLE` **and `FORCE`**; `FORCE` is what makes the
-- policy apply to the table's **owner**, which is the role Flyway runs as in delivery. Closed, the count below
-- reads **zero rows** and the migration reports success over a table full of malformed keys — the mode of
-- failure `V57` documents in full and `V81`, `V99` and `V100` repeat. The window lasts this file's
-- transaction, and the last section **re-forces and re-checks** it before the file ends: a policy left open
-- is a cross-tenant read, which is worse than the refusal this file exists to make actionable.
--
-- No index, no `NOT NULL`, no default: the key stays optional (a category without a key is savable by `V34`'s
-- own reasoning, and a product an operator has not classified is the same state), and nothing here reads the
-- column except the validation the constraint performs once.
-- ============================================

-- ─── 1. the window: the table whose rows this file must be able to see ──────────────────────────────
ALTER TABLE products NO FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    v_forced text;
BEGIN
    SELECT string_agg(c.relname, ', ' ORDER BY c.relname)
      INTO v_forced
      FROM pg_class c
     WHERE c.relname = 'products'
       AND c.relkind = 'r'
       AND c.relforcerowsecurity;

    IF v_forced IS NOT NULL THEN
        RAISE EXCEPTION
            'V104 could not open its NO FORCE window on %. The pre-flight below would read zero rows and the '
            'constraint would then abort on the first malformed key naming no row at all. See the windows V57, '
            'V81 and V100 use, and ADR-0005 correction 3.', v_forced;
    END IF;
END $$;

-- ─── 2. the pre-flight: what would abort, named before it does ──────────────────────────────────────
DO $$
DECLARE
    v_bad    bigint;
    v_sample text;
BEGIN
    SELECT count(*) INTO v_bad FROM products WHERE sat_code IS NOT NULL AND sat_code !~ '^[0-9]{8}$';

    IF v_bad > 0 THEN
        SELECT string_agg(s.id || ' (' || s.sat_code || ')', ', ' ORDER BY s.id)
          INTO v_sample
          FROM (SELECT id, sat_code
                  FROM products
                 WHERE sat_code IS NOT NULL AND sat_code !~ '^[0-9]{8}$'
                 ORDER BY id
                 LIMIT 5) s;

        RAISE EXCEPTION
            'V104 refuses to add ck_products_sat_code_format: % product row(s) carry a sat_code that is not '
            'the eight-digit SAT ClaveProdServ. Five of them: %. products.sat_code has been free text since '
            'V1, so these are keys somebody typed: correct each one to its eight digits, or set it to NULL '
            'when the article has no fiscal key, and run the migration again. NOT VALID is not the way out — '
            'a constraint that only judges new rows says nothing about the rows this tree already holds, and '
            'the concept is the one element a stamp cannot omit.', v_bad, v_sample;
    END IF;
END $$;

-- ─── 3. the constraint, with the predicate the category's key already has ───────────────────────────
ALTER TABLE products
    ADD CONSTRAINT ck_products_sat_code_format
    CHECK (sat_code IS NULL OR sat_code ~ '^[0-9]{8}$');

COMMENT ON COLUMN products.sat_code IS
    'S4: the product''s SAT ClaveProdServ, eight digits, the same predicate ck_product_categories_sat_code_format '
    'gives the category''s key since V34. Optional but validated: NULL means the article has no fiscal key yet, '
    'and a document that needs one refuses it by name instead of stating twenty characters of free text.';

-- ─── 4. the window closes, and it is read back closed ───────────────────────────────────────────────
-- In this order and not the other: the constraint above is validated against every row, which needs the
-- window; every statement after it needs the policy back, because a `products` table left `NO FORCE` is a
-- catalog any tenant can read across companies with no error anywhere.
ALTER TABLE products FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    v_forced  boolean;
    v_policy  integer;
    v_rule    integer;
BEGIN
    SELECT c.relforcerowsecurity, (SELECT count(*) FROM pg_policy p WHERE p.polrelid = c.oid)
      INTO v_forced, v_policy
      FROM pg_class c
     WHERE c.oid = 'products'::regclass;

    IF v_forced IS DISTINCT FROM true THEN
        RAISE EXCEPTION
            'V104 left products without FORCE ROW LEVEL SECURITY. The window is this file''s transaction only; '
            'an owner exempt from the policy reads every tenant''s catalog.';
    END IF;

    IF v_policy IS DISTINCT FROM 1 THEN
        RAISE EXCEPTION 'V104 read % policies on products and expects exactly one.', v_policy;
    END IF;

    SELECT count(*) INTO v_rule
      FROM pg_constraint
     WHERE conrelid = 'products'::regclass
       AND conname = 'ck_products_sat_code_format'
       AND contype = 'c'
       AND convalidated;

    IF v_rule <> 1 THEN
        RAISE EXCEPTION
            'V104 read ck_products_sat_code_format as missing or NOT VALID. A constraint that only judges new '
            'rows is the claim this file exists to refuse.';
    END IF;
END $$;
