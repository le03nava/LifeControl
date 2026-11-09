-- ============================================
-- V89 — the product declares its fiscal code (ADR-0002 decision 6; S2/S4 of odd/tasks/product-tax-code.md)
-- ============================================
-- The maintainer's decision of 2026-11-08, verbatim: «El código vive en el catálogo del artículo». The code
-- is therefore a column on `products` — not a field on a sale line's request and not a company default — so
-- a client cannot state one for a document and the server never guesses one. This file is that column, its
-- default, its reference to the platform catalog and nothing else: no write path and no reader land here
-- (`odd/tasks/product-tax-code.md` T2 for the catalog's read, T4 for the copy onto the line, T5 for the
-- form).
--
-- WHY THIS FILE IS NUMBERED V89 AND V88 IS ABSENT
-- -----------------------------------------------
-- The gap is declared here so it is not discovered at a merge. V88 is **not** this file's to fill: it is the
-- sibling branch's `V88__variant_listing_tax_rate.sql` (`feat/variant-listing-tax-rate`), which is unmerged.
-- This branch is based on `origin/main`, where no V88 exists yet. Two branches that both write a migration at
-- version 88 leave the merge with **two files at the same version**, and Flyway refuses both — not the later
-- one, both — so this file takes 89 and leaves 88 to the branch that already wrote it. The gap says nothing
-- about what V88 contains and is not an instruction to fill it.
--
-- THE SHAPE THIS COLUMN HAS TO AGREE WITH, read and not assumed
-- ------------------------------------------------------------
--   * `V82__tax_rate.sql:26-29` is the catalog: `tax_code (code VARCHAR(20) PRIMARY KEY, label VARCHAR(120)
--     NOT NULL, enabled BOOLEAN NOT NULL DEFAULT true)`. It is a **platform** catalog like `currency`
--     (`V82:10-14`): no `company_id`, no policy, no `tenant_table` row — which is exactly what makes it
--     referenceable from a tenant table with a single-column foreign key instead of a composite one.
--   * `V82:45-49` seeds exactly four codes: `NA`, `IVA`, `TASA_0`, `EXENTO`. `NA` is the one this file's
--     default names, and its presence is what lets the `ADD COLUMN` below validate the rows that already
--     exist: the foreign key is checked as the column is filled with its default, so a catalog without `NA`
--     fails this migration instead of producing a schema nobody can insert into.
--   * `V81:87` is the line's own snapshot, `tax_code VARCHAR(20) NOT NULL DEFAULT 'NA'`, and this column is
--     the same type and width on purpose: it is the **source** of that copy, and two different types would
--     make the same code compare differently in the catalog and on the document. `V82:67-69` records the
--     same reasoning for `tax_rate.rate` against `sales_order_items.tax_rate`.
--   * `V53:122` arms `products` through `apply_tenant_isolation`, which is `ENABLE` **and `FORCE`**
--     (`V52:152-153`). That is why section 2 has no data check — see section 3.

-- ─── 1. the column, its default and its reference ────────────────────────────────────────────────
-- `NOT NULL DEFAULT 'NA'` and **no backfill**, because there is no other state to backfill: every product
-- that exists in every environment is "no aplica" — the tax resolution of decision 6 has never had a code to
-- resolve — and PostgreSQL applies a constant default to the rows that already exist when the column is
-- added, so the column is born full. A backfill `UPDATE products SET tax_code = 'NA'` would be the opposite
-- of harmless here: `products` is `ENABLE`+`FORCE`, so that statement matches **zero rows and reports
-- success** (`V64:36`, `V54:26`), and the `NOT NULL` that followed it would pass over a column of nulls. The
-- default is what makes the statement unnecessary instead of merely wrong.
--
-- The foreign key is named, because section 2 reads it back and because a violation has to say which rule
-- broke: an unnamed constraint would be `products_tax_code_fkey`, which names a column rather than a rule.
-- Its check bypasses row security by design (a referential check cannot depend on who is inserting), so
-- referencing a **platform** catalog is safe: `tax_code` has no tenant to leak. Referencing a tenant table
-- this way would not be — that is why `V88` uses a composite key and this file does not need one.
ALTER TABLE products
    ADD COLUMN tax_code VARCHAR(20) NOT NULL DEFAULT 'NA',
    ADD CONSTRAINT fk_products_tax_code FOREIGN KEY (tax_code) REFERENCES tax_code (code);

COMMENT ON COLUMN products.tax_code IS
    'ADR-0002 decision 6 / S2 of odd/tasks/product-tax-code.md: the fiscal code the ARTICLE declares, from '
    'the platform catalog tax_code (V82), and the source of the snapshot a sale line takes at write time. '
    'sales_order_items.tax_code (V81:87) is a COPY taken from here when the document is written, not a '
    'mirror: a later product edit or a retired code never rewrites a past sale. `NA` means no tax applies — '
    'a code with a meaning, not a missing value — and is the default, so every product that predates V89 '
    'reads as such. The vocabulary is the catalog''s: fk_products_tax_code refuses a code it does not have.';

-- ─── 2. the self-checks: structural, and why none of them reads a product row ─────────────────────
-- Column type, nullability, default and the shape of the reference. That is everything a reader of the
-- schema can lose silently: a nullable column that still defaults to `'NA'` accepts a product with no fiscal
-- code at all, and a `VARCHAR(20)` with no reference accepts any string a client sends — both look correct
-- in a `\d products` skim.
--
-- What is deliberately **not** checked is a row: `products` is armed `ENABLE`+`FORCE` by `V53:122`, so inside
-- this migration — which runs as the owner, the role the policies filter — `SELECT count(*) FROM products`
-- reads **zero rows** and reports success. A "no product is left without a code" assertion here would pass
-- vacuously on a table of nulls, which is the defect `V64:30-42` records and the reason it opens a
-- `NO FORCE` window before its own backfill. This file needs no window because it needs no row: the column
-- is born with its default and the foreign key is validated by PostgreSQL as the column is filled, which is
-- a check the migration cannot skip and does not have to open a policy to perform.
DO $$
DECLARE
    v_type     text;
    v_length   integer;
    v_nullable text;
    v_default  text;
    v_definition text;
    v_fk_type  "char";
    v_target   regclass;
BEGIN
    SELECT data_type, character_maximum_length, is_nullable, column_default
      INTO v_type, v_length, v_nullable, v_default
      FROM information_schema.columns
     WHERE table_schema = 'public' AND table_name = 'products' AND column_name = 'tax_code';

    IF v_type IS DISTINCT FROM 'character varying'
       OR v_length IS DISTINCT FROM 20
       OR v_nullable IS DISTINCT FROM 'NO' THEN
        RAISE EXCEPTION
            'V89 expects products.tax_code as VARCHAR(20) NOT NULL and read %(%) nullable=%. Input is refused '
            'unless the type equals sales_order_items.tax_code (V81:87), and a nullable column would accept a '
            'product that declares no fiscal code at all.', v_type, v_length, v_nullable;
    END IF;

    -- `'NA'::character varying` on PostgreSQL 16. The cast is what a version may render differently, so the
    -- literal is what is compared and the cast is not: a check that pinned the whole string would turn a
    -- minor-version upgrade into a failed deploy over a fact that did not change.
    IF v_default IS NULL OR v_default NOT LIKE '''NA''%' THEN
        RAISE EXCEPTION
            'V89 expects products.tax_code to default to ''NA'' and read %. The default is what fills every '
            'product that existed before this file, and this file deliberately writes no backfill over a '
            'FORCEd table (V64:36).', v_default;
    END IF;

    -- The whole reason the reference is named: an unnamed FK and a nameless column reference look identical
    -- to a reader who only skimmed the ALTER.
    SELECT pg_get_constraintdef(c.oid), c.contype, c.confrelid
      INTO v_definition, v_fk_type, v_target
      FROM pg_constraint c
     WHERE c.conrelid = 'products'::regclass AND c.conname = 'fk_products_tax_code';

    IF v_fk_type IS DISTINCT FROM 'f'
       OR v_definition IS DISTINCT FROM 'FOREIGN KEY (tax_code) REFERENCES tax_code(code)' THEN
        RAISE EXCEPTION
            'V89 read fk_products_tax_code as "%" (type %). It has to be FOREIGN KEY (tax_code) REFERENCES '
            'tax_code(code): without it the column accepts a code the catalog does not have, which is a typo '
            'no report can detect later.', v_definition, v_fk_type;
    END IF;

    IF v_target IS DISTINCT FROM 'tax_code'::regclass THEN
        RAISE EXCEPTION
            'V89 expects fk_products_tax_code to reference tax_code and read %. The catalog is the only '
            'vocabulary of fiscal codes in this schema (V82:26-29, seeded at V82:45-49).', v_target;
    END IF;
END $$;

-- ─── 3. what this file deliberately does NOT do ──────────────────────────────────────────────────
-- * **No backfill, and no `NO FORCE` window.** The column's default fills every existing row
--   (`V89` section 1) and the foreign key validation reads the catalog, which is not armed. A
--   `NO FORCE` window that does not need to be open is a hole, not a habit (`V82:18-21`).
-- * **No index on `tax_code`.** What reads this column are a per-product read (already narrowed by the
--   primary key and the tenant predicate) and, later, the product form's select. The reverse question —
--   "which products declare `IVA`" — has no caller in the tree, and an index without a reader costs every
--   write (`V58`, `V88`). Add it with the reader.
-- * **No writer and no reader here.** Nothing in `src/main` copies this code onto a sale line yet: that is
--   T4 of `odd/tasks/product-tax-code.md`, and until it lands `sales_order_items.tax_code` keeps the `V81`
--   default. The catalog's own read is T2.
-- * **No `NOT NULL` on the line's snapshot and no FK there.** `sales_order_items.tax_code` stays a text
--   snapshot with no reference to this catalog — `V82:129-132` and decision D-d say why: a snapshot has to
--   survive the retirement of the code it names, and a reference would turn "this code is withdrawn" into
--   "these historical documents cannot exist". This file adds the reference on the **catalog side** (a
--   product declares a live code) and leaves the document side as it was.
-- * **No company default and no per-line override.** S2 of the unit: the code lives on the article, so a
--   client cannot state one for a sale. A `companies.default_tax_code` column would be that client's guess
--   with an extra step (the argument `V57:31-37` makes for `companies.currency` having no default).
-- * **No privilege change.** `V52:190-199` grants SELECT/INSERT/UPDATE/DELETE on every table, present and
--   future, through the default privileges; `products` is not append-only, so the `REVOKE` pattern of
--   `V60:286-296` does not apply to it.
