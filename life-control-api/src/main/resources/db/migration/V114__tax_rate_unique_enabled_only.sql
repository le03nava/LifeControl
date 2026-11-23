-- ══════════════════════════════════════════════════════════════════════════════════════════════════════════
-- V114 — the same-day key is a key over ENABLED rows, so retiring a rate frees its day (issue #888)
-- ══════════════════════════════════════════════════════════════════════════════════════════════════════════
-- `V95` stated the scope axis as two partial unique indexes and the service restated them as
-- `requireNoRateForTheSameDay`. Neither considered `enabled`, so a retired row kept owning its
-- `(company_id, tax_code, scope, effective_from)` and there was no path in the product that corrected a
-- mistyped rate: declaring the right value for the same day answered 409 while the retired row sat there, and
-- `PUT …/{id}` can only start a successor on a strictly later day — which leaves the wrong rate applying from
-- its own date until the successor's. The only exit left was SQL, which bypasses the audit trail the
-- append-only model exists to protect.
--
-- The resolution side never had this problem: both resolver queries read the latest **enabled** row on or
-- before the document's date (`TaxRateRepository`), so one retired row plus one enabled row for the same day
-- already answered deterministically. What forbade that state was the key, not the rule. This file moves the
-- key onto the state the resolver reads.
--
-- Two things this file deliberately does NOT do:
--   · It does not widen the key to "any row". Two rows for one day in one scope remain impossible **while
--     they are enabled**; the pair this file admits is one retired plus one enabled, which is the correction.
--     Two retired rows for one day also become representable and are accepted on purpose: they never resolve,
--     no query returns them, and the list shows them as the history they are.
--   · It does not drop `uq_tax_rate_company_id` (`V88`). That key is the target of
--     `variant_listing.fk_listing_tax_rate`, keys no day, and is untouched by this file — the same boundary
--     `V95:96-99` draws.

-- ─── 1. the audit: no scope may already hold two enabled rows for one day ─────────────────────────────────
-- Run **before** the keys are created, so a dataset that would refuse them is reported as a sentence naming
-- the rows instead of as the bare «could not create unique index» PostgreSQL would otherwise emit.
--
-- The window is not ceremony. `tax_rate` is armed with `ENABLE` + `FORCE` row-level security (`V82`, applied by
-- `V53:114`), and Flyway runs as the table's owner, whom the policy also filters (`V54:50-58`, `V64`) — so the
-- same `SELECT` without this window reads **zero rows** and the `RAISE` below would never fire while looking
-- exactly like a clean dataset. That is the trap `V95` documents in its own step 3, and it is why the audit
-- reads rows only inside the window and asserts the window's state from the catalog instead of trusting the
-- `ALTER`.
ALTER TABLE tax_rate NO FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    v_window_open boolean;
    v_enabled     bigint;
    v_duplicates  text;
BEGIN
    -- The window is read back, not assumed: a `NO FORCE` that did not land leaves the audit blind, and a blind
    -- audit is the one failure mode this whole block exists to rule out.
    SELECT NOT c.relforcerowsecurity INTO v_window_open
      FROM pg_class c
     WHERE c.oid = 'tax_rate'::regclass;

    IF v_window_open IS DISTINCT FROM true THEN
        RAISE EXCEPTION
            'V114 could not open the NO FORCE ROW LEVEL SECURITY window on tax_rate, so the duplicate audit '
            'below would read zero rows and report a clean dataset over nothing. The window has to be read back '
            'from pg_class, never trusted to the ALTER that opens it.';
    END IF;

    -- The offending keys, as the caller would have to name them: company, code, the scope (a region id or the
    -- company-wide rows) and the day. Built as text here so the exception names every key at once instead of
    -- one per deploy.
    SELECT string_agg(
               format(
                   '(company %s, code %s, scope %s, day %s)',
                   company_id,
                   tax_code,
                   coalesce(company_region_id::text, 'company-wide'),
                   effective_from),
               ', ' ORDER BY company_id, tax_code, effective_from)
      INTO v_duplicates
      FROM (SELECT company_id, tax_code, company_region_id, effective_from
              FROM tax_rate
             WHERE enabled
             GROUP BY company_id, tax_code, company_region_id, effective_from
            HAVING count(*) > 1) duplicated;

    IF v_duplicates IS NOT NULL THEN
        RAISE EXCEPTION
            'V114 found more than one enabled tax rate for the same company, code, scope and day: %. The keys '
            'this file states are unique over the enabled rows, so those rows would refuse the deploy — and a '
            'dataset like that has two answers for one document, with the resolver returning whichever the '
            'planner reached first. Retire all but one of each pair before running this migration.', v_duplicates;
    END IF;

    -- How many rows the audit actually read. Not an assertion — the window's state above is that — but a
    -- deployed file that prints `0 enabled rows` on a company that has rates is the trace that makes the wrong
    -- window visible in the migration log rather than silent.
    SELECT count(*) INTO v_enabled FROM tax_rate WHERE enabled;
    RAISE NOTICE 'V114 audited % enabled tax_rate rows under the NO FORCE window', v_enabled;
END $$;

-- ─── 2. the window closes, and the flag is asserted back ───────────────────────────────────────────────────
ALTER TABLE tax_rate FORCE ROW LEVEL SECURITY;

-- Not a formality: a `NO FORCE` an aborted deploy leaves open lets the owner of every armed table read across
-- tenants, and `V64:50-58` records that this state fails silently by construction. `V93:229-257` is the same
-- pair, read back for the same reason.
DO $$
DECLARE
    v_open text;
BEGIN
    SELECT string_agg(c.relname, ', ' ORDER BY c.relname)
      INTO v_open
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE c.relkind = 'r'
       AND n.nspname = current_schema()
       AND c.relname = 'tax_rate'
       AND NOT c.relforcerowsecurity;

    IF v_open IS NOT NULL THEN
        RAISE EXCEPTION
            'V114 found % without FORCE ROW LEVEL SECURITY after the audit. The window this file opens is a '
            'NO FORCE, and leaving it open lets the owner of an armed table read across tenants.', v_open;
    END IF;
END $$;

-- ─── 3. the two keys are re-stated over the enabled rows ──────────────────────────────────────────────────
-- Dropped and re-created, never left beside the new pair: a key that survived would refuse exactly the
-- correction this file exists to allow, and the deploy would read as applied while the feature was unusable —
-- the failure `V95:76-79` names.
DROP INDEX uq_tax_rate_scope_from;
DROP INDEX uq_tax_rate_region_scope_from;

-- The company-wide scope. The `enabled` half is what this file is about: while a row is enabled it owns its
-- day, and retiring it gives the day back.
CREATE UNIQUE INDEX uq_tax_rate_scope_from ON tax_rate (company_id, tax_code, effective_from)
    WHERE company_region_id IS NULL AND enabled;

-- The region scope, unchanged in shape: two regions may state the same code from the same day, and neither
-- may state it twice while both rows are enabled.
CREATE UNIQUE INDEX uq_tax_rate_region_scope_from
    ON tax_rate (company_id, tax_code, company_region_id, effective_from)
    WHERE company_region_id IS NOT NULL AND enabled;

-- ─── 4. the self-check: both keys are the ones this file declares, predicate included ─────────────────────
-- Catalog reads only, and deliberately so — `pg_index` is where a predicate lives, and the audit above already
-- proved the rows were readable while the window was open. Asserting the predicate and not only the index's
-- existence is the whole point: a key that kept `company_region_id IS NULL` without `enabled` still counts as
-- one partial unique index, still passes `V95`'s own check, and leaves every correction refused.
DO $$
DECLARE
    v_keys      bigint;
    v_wide_ok   boolean;
    v_region_ok boolean;
BEGIN
    SELECT count(*) INTO v_keys
      FROM pg_index i
     WHERE i.indrelid = 'tax_rate'::regclass
       AND i.indisunique
       AND NOT i.indisprimary
       AND strpos(pg_get_indexdef(i.indexrelid), 'effective_from') > 0;

    IF v_keys <> 2 THEN
        RAISE EXCEPTION
            'V114 expects exactly two unique keys on tax_rate over `effective_from` (the company-wide scope and '
            'the region scope) and read %. One means a scope lost its key and two enabled rates can share a '
            'day; three means a key this file does not declare survived the deploy.', v_keys;
    END IF;

    SELECT i.indisunique
           AND i.indpred IS NOT NULL
           AND strpos(pg_get_expr(i.indpred, i.indrelid), 'company_region_id IS NULL') > 0
           AND strpos(pg_get_expr(i.indpred, i.indrelid), 'enabled') > 0
           AND strpos(pg_get_indexdef(i.indexrelid), '(company_id, tax_code, effective_from)') > 0
      INTO v_wide_ok
      FROM pg_index i
     WHERE i.indexrelid = to_regclass('uq_tax_rate_scope_from');

    IF v_wide_ok IS NULL OR NOT v_wide_ok THEN
        RAISE EXCEPTION
            'V114 left uq_tax_rate_scope_from as something other than a unique partial index on '
            '(company_id, tax_code, effective_from) over `company_region_id IS NULL AND enabled`. Without the '
            '`enabled` half a retired company-wide row still owns its day, and the correction this file exists '
            'to allow is refused while the deploy reads as clean.';
    END IF;

    SELECT i.indisunique
           AND i.indpred IS NOT NULL
           AND strpos(pg_get_expr(i.indpred, i.indrelid), 'company_region_id IS NOT NULL') > 0
           AND strpos(pg_get_expr(i.indpred, i.indrelid), 'enabled') > 0
           AND strpos(pg_get_indexdef(i.indexrelid), '(company_id, tax_code, company_region_id, effective_from)') > 0
      INTO v_region_ok
      FROM pg_index i
     WHERE i.indexrelid = to_regclass('uq_tax_rate_region_scope_from');

    IF v_region_ok IS NULL OR NOT v_region_ok THEN
        RAISE EXCEPTION
            'V114 left uq_tax_rate_region_scope_from as something other than a unique partial index on '
            '(company_id, tax_code, company_region_id, effective_from) over '
            '`company_region_id IS NOT NULL AND enabled`. A region that could state the same code twice on one '
            'day while both rows are enabled has two answers for the same document.';
    END IF;
END $$;
