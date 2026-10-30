-- ============================================
-- V53 — ADR-0005's policies: the sixteen tables that carry a tenant
-- ============================================
--
-- WHAT THIS MIGRATION IS
--
-- The policies. Sixteen calls to `apply_tenant_isolation`, one per table, and not a single
-- `CREATE POLICY` written by hand: that is the property ADR-0005 asks for and the reason "a new tenant
-- table is a new list entry" is true rather than aspirational. Everything the policy is made of — the
-- two scope settings, the predicate per kind, ENABLE and FORCE, the name, the `tenant_table` row — lives
-- in V52's function, so a table armed here and a table armed by the next migration cannot disagree.
--
-- The sixteen are the fifteen tables S2 of `odd/tasks/tenant-isolation-rls.md` names («Solo las 15
-- tablas que ya tienen company_id») plus the root, which is the sixteenth because it has no `company_id`
-- at all:
--
--   * `companies` — isolated by its own `id`. It is the one call that passes a key column, and that is
--     why `apply_tenant_isolation` takes `key_column` at all: the ADR's original helper hardcoded
--     `company_id`, so the table the whole hierarchy hangs from could not use the helper that makes the
--     pattern uniform (ADR correction 1 of "Corrections applied before acceptance"). Two consequences
--     follow and both are already in the tree: the create path states the id it generates as the
--     transaction's scope (T5a), and the administrator's list stopped being an unscoped `findAll` (T5b) —
--     under this policy that read answers one tenant, not every company.
--   * `user_preferences` — the one table isolated by a person and not by a company, so it is the only
--     call with `kind => 'subject'` and the only key that is not a uuid. Its `company_id` is the user's
--     chosen context and the row is read during authentication, before any company is known, so a tenant
--     predicate would empty the table exactly when it is needed (S3, ADR decision 2's third kind). The
--     comparison is against `app.user_id` and casts nothing: `keycloak_user_id` is a VARCHAR.
--   * the two attribute catalogs — `kind => 'shared'`, because a NULL owner is the global row every
--     tenant reads and a non-NULL owner is that tenant's override (ADR decision 5).
--   * everything else — the ordinary tenant predicate on `company_id`.
--
-- THE ORDER IS DELIBERATE
--
--   Group 1 (`companies`, the store hierarchy, the catalog, the suppliers, the purchase orders) is armed
--   first, then group 2 (organisational: `departments`, `employees`), then the two shared catalogs and the
--   subject table. The order inside a group changes nothing — no policy references another — but keeping
--   the ADR's grouping means a reviewer can read this file next to the ADR's migration section.
--
--   What the order cannot be is "the policies, later". T4 installed the transaction scope and T5a/T5b
--   made every path that names no company state its tenant, so the tree satisfies these policies before
--   they exist; the reverse — policies first — reads zero rows on every tenant table and looks like data
--   loss. That is the whole reason the slices were sequenced mechanism, scope, then arming.
--
-- WHAT THIS MIGRATION COSTS LATER MIGRATIONS (ADR decision 6, and it is a rule, not advice)
--
--   `FORCE ROW LEVEL SECURITY` is on every table below, so the policies apply to the table's OWNER — and
--   `lifecontrol_owner` is the role Flyway runs as. A future migration that `UPDATE`s or `DELETE`s rows on
--   any of these sixteen therefore affects **0 rows and reports success**: no error, no warning, a data
--   migration that silently did nothing and a deployment that looks green. `TRUNCATE` is unaffected (RLS
--   is not applied at table level), which is precisely why the trap is invisible in the `V14`/`V40` shapes
--   already in this tree.
--
--   The rule for every migration after this one, in its two allowed forms:
--
--     1. A migration that knows its tenant calls `SELECT set_tenant_scope('<uuid>')` first, in the same
--        transaction, before the first row it means to touch. The owner then reads and writes exactly
--        that tenant's rows, like the application does.
--     2. A migration that must rewrite rows ACROSS tenants runs in Flyway's own elevated connection —
--        `lifecontrol_bootstrap`, the `SUPERUSER LOGIN` role with no password and no grant (ADR decision
--        3) — and says why in its own file. A superuser bypasses RLS by PostgreSQL rule, which is also
--        why no ordinary migration and no application credential may hold it.
--
--   There is no third form. "Connect as the owner and drop the WHERE" is form 1 with a missing call, and
--   its failure mode is the one this ADR exists to remove: silence.
--
-- WHAT IT DOES NOT DO
--
--   It adds three indexes and no other structure. The key column already leads an index or a unique
--   constraint on thirteen of the sixteen — measured against the migrations, not assumed: `companies` by
--   its primary key on `id`, `user_preferences` by the `UNIQUE` on `keycloak_user_id` and the eleven
--   remaining by a `(company_id, …)` index or unique constraint. The ADR's `## Consequences` says the
--   `(company_id, …)` leading columns become load-bearing and "the rest get one in their group's
--   migration", which is this file. The three that had nothing usable are `product_suppliers` and
--   `purchase_orders` (their `company_id` arrived in V43 as a column carried by a composite FOREIGN KEY,
--   and a referencing-side foreign key creates no index at all) and `product_attribute_profile_roots`
--   (V37 gives it a unique index on `COALESCE(company_id, '000…')`, which is an EXPRESSION index and
--   cannot answer `company_id = <uuid>`). `companies` gets none: a second index on a primary key is a
--   write cost buying nothing.
--
-- Prerequisites: V52 (the scope functions, the list, the helper) and `docker/postgres-init/
-- lifecontrol-roles.sql` applied by `docker/scripts/db-roles.sh <env>`, which V52 already refuses to
-- continue without.
-- ============================================

-- ─── 1. the indexes the predicate runs on: three tables had none, and only three ──────
-- The predicate is `company_id = <the scope>`, evaluated per row unless the planner can use an index, so
-- this is the read cost of isolation rather than a tuning preference. Written IF NOT EXISTS so a replay
-- (Flyway `repair`, a restored dump) converges instead of failing on its own leftovers, like everything in
-- V52.
CREATE INDEX IF NOT EXISTS idx_product_suppliers_company_id
    ON product_suppliers (company_id);

-- `purchase_orders` carries seven single-column indexes from V1 and not one of them is the owner: the
-- order's company is derived from its store (V43) and pinned to it by a composite key (V51), and both of
-- those are foreign keys, which index the REFERENCED side only.
CREATE INDEX IF NOT EXISTS idx_purchase_orders_company_id
    ON purchase_orders (company_id);

-- An expression index is not this index: `uq_product_attribute_profile_roots_owner_root` indexes
-- `COALESCE(company_id, '00000000-0000-0000-0000-000000000000'::uuid)`, and the planner cannot use it for
-- `company_id = <uuid>` — the expressions differ. The `shared` predicate reads the column directly.
CREATE INDEX IF NOT EXISTS idx_product_attribute_profile_roots_company_id
    ON product_attribute_profile_roots (company_id);

-- ─── 2. group 1: identity, store hierarchy and catalog ─────────────────────────────────
-- The root first, and it is the one call whose key is not `company_id`.
SELECT apply_tenant_isolation('companies', 'tenant', 'id');

-- The store hierarchy. Each of these already leads `company_id` in the `UNIQUE (company_id, id)` its
-- contract migration added — and that constraint exists as the target of the composite foreign key one
-- level below it, so the index was already paid for.
SELECT apply_tenant_isolation('company_countries');
SELECT apply_tenant_isolation('company_regions');
SELECT apply_tenant_isolation('company_zones');
SELECT apply_tenant_isolation('company_stores');

-- The catalog. `product_categories`, `products`, `product_variants` and `suppliers` are the four whose
-- contract migrations made the same `company_id` structure do double duty (tenancy guarantee and
-- composite foreign key target), so V31/V40/V41 already own the index the predicate reads.
SELECT apply_tenant_isolation('product_categories');
SELECT apply_tenant_isolation('products');
SELECT apply_tenant_isolation('product_variants');
SELECT apply_tenant_isolation('suppliers');
SELECT apply_tenant_isolation('product_suppliers');
SELECT apply_tenant_isolation('purchase_orders');

-- ─── 3. group 2: the organisation ─────────────────────────────────────────────────────
-- `employees` is armable only because its reader outside any request resolves the company first: the
-- provisioning worker goes through `task_employee_company` (T5a) instead of a join under no scope. Without
-- that resolver this line would stop the worker, silently, for every tenant.
SELECT apply_tenant_isolation('departments');
SELECT apply_tenant_isolation('employees');

-- ─── 4. group 3: the shared catalogs and the subject table ────────────────────────────
-- The two that are readable by every tenant when they carry no owner, and by their owner when they are an
-- override (ADR decision 5). The kind is the second positional argument of the helper, which is what makes
-- these two calls differ from the twelve above by one word.
SELECT apply_tenant_isolation('product_attribute_profiles', 'shared');
SELECT apply_tenant_isolation('product_attribute_profile_roots', 'shared');

-- The one table compared to a person and not to a company (S3): the third kind, and the only key column
-- this file passes besides `companies`' `id`.
SELECT apply_tenant_isolation('user_preferences', 'subject', 'keycloak_user_id');

-- ─── 5. the assertion, in the migration that arms the others ──────────────────────────
-- ADR migration group 5 asks for "assert, then narrow": a table that carries `company_id` and is not on the
-- list must fail THIS migration, not a deployment later — which is what a startup check (T3, `V54`) cannot
-- do for the migration that forgot it, because by the time the application starts the omission is already
-- applied and the backup is the only way back.
--
-- The body aggregates the names with `string_agg` because "one or more tables are unprotected" is a
-- message an operator cannot act on: the list is the fix.
DO $$
DECLARE
    v_unprotected text;
BEGIN
    SELECT string_agg(t::text, ', ') INTO v_unprotected FROM unprotected_tenant_tables() t;

    IF v_unprotected IS NOT NULL THEN
        RAISE EXCEPTION
            'V53 armed the sixteen tables of ADR-0005, but these tables carry company_id and no policy: %. '
            'Add one apply_tenant_isolation call per table to this migration, in its own group, or say in '
            'the file why the table is out of scope. A table missing here reads every tenant''s rows.',
            v_unprotected;
    END IF;
END $$;
