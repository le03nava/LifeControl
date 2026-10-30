# ADR 0005 — Tenant isolation is enforced by Row-Level Security

- **Status**: accepted
- **Date**: 2026-10-29
- **Accepted**: 2026-10-29, after the three corrections recorded at the end of this document
- **Decision id**: D5 of `odd/tasks/architecture-redesign-adrs.md`
- **Depends on**: —
- **Blocks**: ADR-0001 (every ledger table carries a policy), ADR-0002 (payments are tenant rows)
- **Replaces**: tenancy by call-site checks, which is what the tree has today

## Context

Measured in this tree on 2026-10-29:

```
grep -rn "verifyStoreAccess\|companyIdOf(\|AccessDeniedException" --include=*.java life-control-api/src/main/java | wc -l   # 150
grep -rc "CREATE POLICY" life-control-api/src/main/resources/db/migration/*.sql | grep -v ':0' | wc -l                       # 0 in 51 migrations
grep -rn "company_id UUID" life-control-api/src/main/resources/db/migration/*.sql | wc -l                                   # 15
grep -rn "SET LOCAL\|current_setting" --include=*.java life-control-api/src/main/java | wc -l                                # 0
```

One hundred and fifty access checks, zero policies. Every one of those 150 checks is correct — this ADR
does not argue that the code is wrong. It argues that a correct check written 150 times is a barrier
with 150 doors, and the door that matters is the one a new endpoint forgets: the compiler says nothing
when a query misses its `AND p.company_id = :companyId`, and a one-tenant fixture says nothing either.

The tenant is a token fact, not a database fact. `ScopeLevel` declares the five claims — `company_id`
(`ScopeLevel.java:58`), `company_country_id` (`:61`), `company_region_id` (`:64`), `company_zone_id`
(`:67`), `company_store_id` (`:91`) — and `CurrentUserContext` resolves them per request
(`CurrentUserContext.java:401-402`) from the JWT (`:443`), all of it on the Java side of the boundary:
PostgreSQL receives a query and has no idea which tenant asked.

The database already knows how to make an inadmissible pair unwritable, and the tree used that four
times: `UNIQUE (company_id, id)` on `products` and `product_variants`
(`V40__product_catalog_company_contract.sql:105-106`) with the composite key pinning a variant to its
product (`:131-135`), the same key on the store (`V48:44`, `V49:71`) and its zone (`V49:76-78`). That
closes the *write* side and not the read: it forbids a mixed pair and says nothing about a query that
names no tenant and returns all fifteen.

Two tables carry a deliberately nullable `company_id` — `product_attribute_profiles` (`V37:71`) and
`product_attribute_profile_roots` (`V37:140`) — because `NULL` means "the global row", and a policy that
ignored that would empty a shared catalog.

## Decision

**The tenant is a fact of the transaction, and PostgreSQL enforces it.**

1. **Every request transaction states its tenant before its first read.** A filter sets `app.company_id`
   with `SET LOCAL` (`set_config(..., true)`) from the principal. It fails closed: an absent or
   multi-valued claim is a refusal, never a wider scope, and a transaction that reaches a query without
   the setting reads **no rows**, never all rows.
2. **Every tenant table carries one policy**, `USING (company_id = current_setting('app.company_id',
   true)::uuid)` for `SELECT`/`UPDATE`/`DELETE` and the same predicate as `WITH CHECK` for
   `INSERT`/`UPDATE`. The `true` makes a missing setting a `NULL` predicate, not an error: `company_id =
   NULL` is `NULL`, which filters the row.
3. **Two roles, two jobs.** `lifecontrol_owner` owns the objects and runs Flyway; `lifecontrol_app` owns
   nothing, has no `BYPASSRLS`, and is the only role the pool connects as. Every tenant table gets
   `ENABLE` **and** `FORCE ROW LEVEL SECURITY`, so the owner is not implicitly exempt either. The
   application role also needs `USAGE` on the **sequences** its identity columns own: a grant that
   covers tables and stops there fails every `INSERT` with `permission denied for sequence`, a runtime
   failure and not a policy one.
4. **The shared catalogs are an explicit, closed exception.** `countries` (`V1:11`), `measure_units`
   (`V1:284`), `statuses` (`V1:268`), `status_types` (`V1:255`), `payment_methods` (`V1:302`) and
   `currency` (ADR-0002) carry no `company_id` and no policy. The startup assertion below fails the
   moment a `company_id` table is not on the list, which is how the exception stays closed.
5. **The two override catalogs are shared rows, not tenant rows.** `product_attribute_profiles` and
   `product_attribute_profile_roots` get `company_id IS NULL OR company_id = ...`: readable by every
   tenant when global, by its owner when an override. **The 150 call-site checks stay** as defence in
   depth: they express *authorization* (which store, which role) on top of *isolation* (which tenant).
6. **A migration that writes tenant rows states its scope too, and one that spans tenants is the one
   named exception.** `FORCE` applies to the owner as well, so a migration running as
   `lifecontrol_owner` that `UPDATE`s or `DELETE`s rows on the list affects **0 rows** — silently,
   which is the failure mode this ADR exists to remove. `TRUNCATE` is *not* filtered, because RLS is not
   applied at table level, so the `V14`/`V40` shape hides the trap instead of revealing it. Rule: a
   migration that knows its tenant calls `set_tenant_scope('<uuid>')` first; a migration that must
   rewrite rows across tenants runs in Flyway's own elevated connection — a superuser, which bypasses
   RLS by PostgreSQL rule — and says why in the file. That is not the rejected third `BYPASSRLS` role:
   it is a credential the pool never holds, and invariant T5 keeps the owner honest.

## Schema

```sql
-- ─── two roles: one owns and migrates, one runs the application ───────────────
CREATE ROLE lifecontrol_owner LOGIN;              -- Flyway: owns every object
CREATE ROLE lifecontrol_app   LOGIN NOBYPASSRLS;  -- the pool: owns nothing
GRANT USAGE ON SCHEMA public TO lifecontrol_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO lifecontrol_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO lifecontrol_app;
-- a table created by a later migration inherits the grant, so "a new table is a list entry" holds for
-- the privileges the application needs as well as for the policy
ALTER DEFAULT PRIVILEGES FOR ROLE lifecontrol_owner IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO lifecontrol_app;
ALTER DEFAULT PRIVILEGES FOR ROLE lifecontrol_owner IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO lifecontrol_app;

-- ─── the tenant scope of one transaction: `true` is SET LOCAL, so the value dies
-- with the transaction and a pooled connection cannot carry a tenant into the next request
CREATE FUNCTION set_tenant_scope(company uuid) RETURNS void LANGUAGE sql AS $$
    SELECT set_config('app.company_id', company::text, true);
$$;

-- ─── the list: a new tenant table is a new row, never a new policy script ────
CREATE TABLE tenant_table (
    table_name  regclass PRIMARY KEY,
    shared_rows BOOLEAN NOT NULL DEFAULT false   -- true: a NULL owner means "every tenant"
);

CREATE FUNCTION apply_tenant_isolation(target regclass, shared_rows boolean DEFAULT false,
                                      key_column text DEFAULT 'company_id')
RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    relname   text := (SELECT relname FROM pg_class WHERE oid = target);
    predicate text := format('%I = current_setting(''app.company_id'', true)::uuid', key_column);
BEGIN
    IF shared_rows THEN predicate := 'company_id IS NULL OR ' || predicate; END IF;
    EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', target);
    EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', target);
    EXECUTE format('CREATE POLICY %I ON %s FOR ALL USING (%s) WITH CHECK (%s)',
                   'p_tenant_' || relname, target, predicate, predicate);
    INSERT INTO tenant_table (table_name, shared_rows) VALUES (target, shared_rows)
    ON CONFLICT (table_name) DO UPDATE SET shared_rows = EXCLUDED.shared_rows;
END $$;

-- ─── the assertion: a company_id column without a policy is a startup failure ─
SELECT c.oid::regclass AS unprotected_table
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public' AND c.relkind = 'r'
   AND EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid
                AND a.attname = 'company_id' AND a.attnum > 0 AND NOT a.attisdropped)
   AND NOT EXISTS (SELECT 1 FROM tenant_table t WHERE t.table_name = c.oid);

-- ─── the root is isolated by its own key, not by a company_id column ──────────
SELECT apply_tenant_isolation('companies', false, 'id');
SELECT apply_tenant_isolation('product_attribute_profiles', true);
SELECT apply_tenant_isolation('product_attribute_profile_roots', true);
```

That last query is not documentation: the application runs it at startup and refuses to boot when it
returns a row, so a `company_id` added later is either on the list in that migration or the deployment
stops — the shape of ADR-0001's rebuild check.

## Invariants (each one testable)

| # | Invariant | Test |
| --- | --- | --- |
| T1 | Every `company_id` table has a policy, enabled and forced | the assertion returns no row; `pg_class.relforcerowsecurity` is true |
| T2 | A transaction without `set_tenant_scope` reads zero rows | fresh connection, `SELECT count(*) FROM products` returns 0 |
| T3 | A cross-tenant `SELECT` returns zero rows with no `WHERE` on company | as `lifecontrol_app` scoped to A, select B's id by primary key: 0 rows |
| T4 | A cross-tenant `INSERT` is refused | same connection, insert a row naming B's `company_id`: SQLSTATE 42501 |
| T5 | The owner role is not implicitly exempt | as `lifecontrol_owner`, the same `SELECT` returns 0 rows |
| T6 | The tenant setting is transaction-local | after commit, `current_setting('app.company_id', true)` is `NULL` on that pooled connection |
| T7 | A shared row is readable by all, an override only by its owner | the `NULL`-owner profile is visible to A and to B; the override only to its tenant |
| T8 | The sequence grant is not an afterthought | as `lifecontrol_app` scoped to A, insert a row whose `id` comes from an identity column: succeeds |

The parent decision's suite, T2–T5, runs as `lifecontrol_app` on Testcontainers (`build.gradle:85-86`).

## Migration from the current tree

One migration per group, in this order, because a policy on a table whose `company_id` is still nullable
hides the rows it cannot attribute:

1. **Roles and grants.** Create both roles, move ownership to `lifecontrol_owner`, point
   `DATABASE_USERNAME` at `lifecontrol_app`. Flyway runs as the owner.
2. **Columns first.** A table on the list needs `company_id NOT NULL` before it gets a policy. The
   contract migrations did this for products, variants, suppliers and the store hierarchy
   (`V40:101-102`, `V49:71`); a group still nullable is closed in its own migration, never by guess.
3. **Identity, store hierarchy and catalog**: `companies` (isolated by `id`, the root —
   `apply_tenant_isolation('companies', false, 'id')`, the one call that does not use `company_id`),
   `company_countries`, `company_regions`, `company_zones`, `company_stores`, `products`,
   `product_variants`, `product_categories`, `suppliers`, `product_suppliers`, and the two override
   catalogs with `shared_rows = true`.
4. **Documents and operations**: the purchase and sales documents with their lines, the goods receipts,
   `inventory_movements`, `product_variant_locations`, `product_variant_store_stock`, the employee and
   org tables, and `tax_rate` once ADR-0002 exists.
5. **Assert, then narrow.** The assertion runs inside the migration and T2–T5 are the gate; nothing else
   lands on top until they are green.

Each group is one `PERFORM apply_tenant_isolation(...)` per table: no hand-written `CREATE POLICY`
anywhere, which is what makes "a new table is a list entry" true rather than aspirational. These land
**before** every other ADR's, so they take the lowest free versions and ADR-0001's `V52…V55` shift by
however many this one takes. The other order writes every policy twice.

## Rejected alternatives

| Alternative | Why rejected |
| --- | --- |
| A schema per tenant | One schema is one migration set, so every migration becomes N migrations plus a loop that skips a failure on tenant 3 of 40. A cross-tenant report becomes a `UNION` over an unbounded number of schemas, and the pool still pins a schema per request — the same `SET LOCAL` this ADR needs, with more moving parts |
| Keep the 150 checks and add more discipline | That is the status quo, measured: 150 correct checks and nothing that makes the 151st correct. A review process is not an enforcement mechanism, and the failure it produces is silent |
| A Hibernate filter or a Spring Data interceptor | It covers only what goes through the ORM. A native query, a JPQL constructor projection (`ProductVariantRepository.java:152-170` is one) or a `JdbcTemplate` call bypasses it without a warning. A defence an already-present pattern silently skips is not a barrier |
| Policies plus a third `BYPASSRLS` role for admin and batch work | One role that can see everything is one credential that can see everything. The audited service context below is the deliberate alternative |

## Consequences

- **Good**: the barrier stops depending on the caller. A query that forgets the tenant predicate returns
  nothing instead of another company's stock — true for a native query, a report, a batch job and an
  endpoint nobody wrote yet. Reads and writes are enforced at the schema, so isolation is a migration
  line, not a review checklist.
- **Cost**: an operational duty exists that did not before. A job that legitimately spans tenants — a
  nightly report, a support tool, a data migration — needs an explicit, audited service context: a scope
  the filter sets from configuration, one tenant at a time, or an owner-role connection whose use is
  logged. "Connect as the app role and drop the `WHERE`" stops being possible, by design.
- **Cost**: a missing `SET LOCAL` must fail closed rather than leak, so a new asynchronous path (a
  `@Scheduled` job, a listener, a thread that outlives the request) is a bug reading as "returns
  nothing" instead of "returns everything". Silent-empty is the safe failure and a confusing one, so
  the filter logs a warning with the request id when it refuses.
- **Cost**: the predicate runs per row unless the planner uses an index, so the `(company_id, …)`
  leading columns become load-bearing — they exist where it matters (`V40`, `V48`); the rest get one in
  their group's migration. Cross-tenant fixtures need a real PostgreSQL: Testcontainers, not H2.

## Corrections applied before acceptance

Read in full for the acceptance decision, and three defects were found. The decision is unchanged; the
document now says what it meant.

1. **`companies` did not fit the helper.** `apply_tenant_isolation` hardcoded `company_id`, while the
   migration section singles `companies` out as "its policy is on `id`, the root" — so the one table the
   ADR names could not use the helper that makes "a new tenant table is a new row" true. The function
   now takes `key_column` (`DEFAULT 'company_id'`), and `companies` is applied with `'id'`.
2. **The grants stopped at tables.** `lifecontrol_app` is the only role that writes, and identity columns
   are backed by sequences it could not touch: every `INSERT` would have failed with
   `permission denied for sequence`, which reads as a broken application rather than a tenant problem.
   Both the explicit grant and its `ALTER DEFAULT PRIVILEGES` counterpart now cover sequences, and T8
   pins it.
3. **`FORCE` filters the owner, and the owner runs Flyway.** A migration that `UPDATE`s or `DELETE`s
   tenant rows would have affected 0 rows in silence. `TRUNCATE` is unaffected because RLS is not
   applied at table level, which is why the `V14`/`V40` shape never surfaced the trap. Decision item 6
   states the rule and names the single elevated exception.

The SQL was not executed: no runner was authorized, so it is read-verified, not observed.
