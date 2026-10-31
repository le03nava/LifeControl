# ADR 0005 — Tenant isolation is enforced by Row-Level Security

- **Status**: accepted
- **Date**: 2026-10-29
- **Accepted**: 2026-10-29, after the three corrections recorded under *Corrections applied before acceptance*
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

1. **Every transaction that serves a request states its tenant before its first read.** The value is set
   with `set_config('app.company_id', ..., true)`, so it dies with the transaction (item 2 states the
   predicate it feeds). **Which component installs it is an implementation choice, not part of this
   decision**: the requirement is that it happens inside the transaction and before its first query,
   because `SET LOCAL` outside a transaction is not local at all. A servlet filter can satisfy that only
   by owning the request's transaction; a hook at the transaction boundary satisfies it without one.
   What the decision forbids is the third shape — a filter that issues `SET LOCAL` on some other
   connection, which is a no-op dressed as enforcement. It fails closed, and the rule is about the
   **operation**, not about the claim: the value is the tenant the operation names — in this tree the
   `{companyId}` path variable of the endpoint — verified against the token's claims, so a request naming a
   company outside its claims is a refusal and never a wider scope, and a transaction that reaches a query
   with no setting reads **no rows**, never all rows. **A multi-valued claim is not itself a refusal**: the
   mapper is multivalued because one person may work for several companies, and it is the operation that
   says which one is in scope. Refusing on arity would break exactly the case the mapper exists for; what
   fails closed is an operation whose tenant cannot be determined at all.
   One table is not tenant-scoped at all: `user_preferences` is a **subject** row, so its transactions
   also state `app.user_id`, and the same rule applies — a subject transaction that reaches that table
   without the setting reads no rows.
2. **Every tenant table carries one policy**, `USING (company_id =
   nullif(current_setting('app.company_id', true), '')::uuid)` for `SELECT`/`UPDATE`/`DELETE` and the
   same predicate as `WITH CHECK` for `INSERT`/`UPDATE`, and both arguments are load-bearing. The `true`
   makes a setting that no session ever created a `NULL` predicate instead of an error; `nullif` covers
   the other half, measured on `postgres:16-alpine`: after one transaction-local `set_config` and its
   `COMMIT` the parameter exists and equals the **empty string**, so a bare `::uuid` raises `invalid
   input syntax for type uuid: ""` (SQLSTATE 22P02) on the ordinary pooled connection, after its first
   request. `company_id = NULL` is `NULL`, which filters the row, and `nullif` maps `''` to that same
   `NULL`. Fail-closed is the promise; fail-loud was the delivered text.
3. **Three roles, three jobs.** `lifecontrol_owner` owns the objects and runs Flyway; `lifecontrol_app`
   owns nothing, has no `BYPASSRLS`, and is the only role the pool connects as; and
   `lifecontrol_bootstrap` is a `SUPERUSER LOGIN` maintenance credential with **no password** and no
   grant on any table. The third is not a spare: it is the role `initdb` itself creates, because
   `POSTGRES_USER` names **it** and not the owner. The owner is created `NOSUPERUSER` by the role script,
   since PostgreSQL refuses to take `SUPERUSER` away from the role `initdb` created (`ERROR: permission
   denied to alter role / DETAIL: The bootstrap superuser must have the SUPERUSER attribute`), so the
   two can never be one row. Without the bootstrap role nothing in the database can
   `ALTER ROLE ... PASSWORD` or `ALTER ROLE ... NOSUPERUSER` again — the role script fails from its
   **second** run onward — and no later migration can `CREATE EXTENSION`, which
   `V21__employee_contracts.sql:27` already needs for `btree_gist`. It authenticates on the container's
   local socket, which is `trust`; its password is dropped (`ALTER ROLE ... PASSWORD NULL`), and a role
   with no password cannot connect over TCP at all, where the image requires `scram-sha-256`. The compose
   files name it because `initdb` needs it, which is the only place its name appears; nothing logs in as
   it.
   The rule that follows, and it is the point: the bootstrap role is for the DDL PostgreSQL reserves to
   superuser — creating a role, moving a role's attributes, creating an extension — and **never** for a
   migration that touches tenant rows, because that connection bypasses every policy, which is exactly
   what invariant T5 exists to catch. Every tenant table gets `ENABLE` **and** `FORCE ROW LEVEL
   SECURITY`, so the owner is not implicitly exempt either. The application role also needs `USAGE` on
   the **sequences** its identity columns own: a grant that covers tables and stops there fails every
   `INSERT` with `permission denied for sequence`, a runtime failure and not a policy one.
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
   rewrite rows across tenants runs in Flyway's own elevated connection — `lifecontrol_bootstrap`
   (item 3), a superuser, which bypasses RLS by PostgreSQL rule — and says why in the file. That is not
   the rejected `BYPASSRLS` role: it is a credential the pool never holds, and invariant T5 keeps the
   owner honest.

## Schema

```sql
-- ─── three roles: one owns and migrates, one runs the application, one maintains ────────────
CREATE ROLE lifecontrol_owner LOGIN NOSUPERUSER;   -- Flyway: owns every object, bypasses nothing
CREATE ROLE lifecontrol_app   LOGIN NOBYPASSRLS;   -- the pool: owns nothing
CREATE ROLE lifecontrol_bootstrap SUPERUSER LOGIN; -- initdb's role: roles and extensions only
ALTER ROLE lifecontrol_owner WITH LOGIN PASSWORD :'owner_password';   -- the owner's secret file
ALTER DATABASE lifecontrol OWNER TO lifecontrol_owner;                -- see the note below
ALTER ROLE lifecontrol_bootstrap PASSWORD NULL;                       -- no TCP login, ever
-- The bootstrap role is not a second application credential: it is the role `initdb` creates, because
-- `POSTGRES_USER` names it. The owner is created `NOSUPERUSER` here and takes ownership of the database,
-- which is what lets a fresh volume create its first table: `pg_database_owner` resolves to
-- `POSTGRES_USER`, so a database left owned by the bootstrap role refuses the owner's DDL. No password
-- means the `trust` local socket is its only way in (the image requires scram-sha-256 over TCP), and no
-- table is granted to it. A migration that connects with it says why in its own file; one that touches
-- tenant rows never does (item 6, invariant T5).
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

-- ─── the subject scope: a table read before any company is known cannot use the tenant one
CREATE FUNCTION set_user_scope(subject text) RETURNS void LANGUAGE sql AS $$
    SELECT set_config('app.user_id', subject, true);
$$;

-- ─── the list: a new tenant table is a new row, never a new policy script ────
-- The kind is a column and not a boolean because there are three shapes, and a boolean has no value
-- for the third: `tenant` is the ordinary predicate, `shared` means a NULL owner is every tenant
-- (the two override catalogs), and `subject` is a row that belongs to a person, not to a company
-- (`user_preferences`, read during authentication before any company is known).
CREATE TABLE tenant_table (
    table_name  regclass PRIMARY KEY,
    policy_kind text NOT NULL CHECK (policy_kind IN ('tenant', 'shared', 'subject')),
    key_column  text NOT NULL DEFAULT 'company_id'
);

CREATE FUNCTION apply_tenant_isolation(target regclass, kind text DEFAULT 'tenant',
                                      key_column text DEFAULT 'company_id')
RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    v_target    regclass := $1;   -- the parameters are read through aliases: PL/pgSQL resolves an
    v_kind      text     := $2;   -- identifier that matches both a parameter and a column to the
    v_key       text     := $3;   -- COLUMN, and `key_column` is a column of `tenant_table`
    v_relname   text;
    v_policy    text;
    v_predicate text;
BEGIN
    IF v_kind IS NULL OR v_kind NOT IN ('tenant', 'shared', 'subject') THEN
        RAISE EXCEPTION 'apply_tenant_isolation: unknown policy kind %', v_kind;
    END IF;
    -- Refused before anything is altered or listed. A policy on a column the table does not have
    -- matches nothing, in silence, so the table reads as empty forever: the failure mode this ADR
    -- exists to remove. This is the case that catches `companies`, isolated by its `id`.
    IF NOT EXISTS (SELECT 1 FROM pg_attribute
                    WHERE attrelid = v_target AND attname = v_key
                      AND attnum > 0 AND NOT attisdropped) THEN
        RAISE EXCEPTION 'apply_tenant_isolation: % has no column % (kind %)', v_target, v_key, v_kind;
    END IF;

    SELECT c.relname INTO v_relname FROM pg_class c WHERE c.oid = v_target;
    v_policy := 'p_tenant_' || v_relname;

    IF v_kind = 'tenant' THEN
        v_predicate := format('%I = nullif(current_setting(''app.company_id'', true), '''')::uuid',
                              v_key);
    ELSIF v_kind = 'shared' THEN
        v_predicate := format('%I IS NULL OR %I = nullif(current_setting(''app.company_id'', true), '''')::uuid',
                              v_key, v_key);
    ELSE
        -- No cast: `user_preferences.keycloak_user_id` is a VARCHAR, and `::uuid` would make the read
        -- during authentication fail instead of filter. `''` already matches no row, so this kind
        -- fails closed without a `nullif` of its own.
        v_predicate := format('%I = current_setting(''app.user_id'', true)', v_key);
    END IF;

    EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', v_target);
    EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', v_target);
    -- DROP before CREATE, so a re-applied table converges instead of stacking a second policy.
    EXECUTE format('DROP POLICY IF EXISTS %I ON %s', v_policy, v_target);
    EXECUTE format('CREATE POLICY %I ON %s FOR ALL USING (%s) WITH CHECK (%s)',
                   v_policy, v_target, v_predicate, v_predicate);
    INSERT INTO tenant_table AS t (table_name, policy_kind, key_column)
    SELECT v_target, v_kind, v_key
    ON CONFLICT (table_name) DO UPDATE
       SET policy_kind = EXCLUDED.policy_kind,
           key_column   = EXCLUDED.key_column;
END $$;

-- ─── the assertion: a company_id column without a policy is a startup failure ─
SELECT c.oid::regclass AS unprotected_table
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public' AND c.relkind = 'r'
   AND EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid
                AND a.attname = 'company_id' AND a.attnum > 0 AND NOT a.attisdropped)
   AND NOT EXISTS (SELECT 1 FROM tenant_table t WHERE t.table_name = c.oid);

-- ─── the root is isolated by its own key, and the person-scoped table by its own kind ────────
SELECT apply_tenant_isolation('companies', 'tenant', 'id');
SELECT apply_tenant_isolation('product_attribute_profiles', 'shared');
SELECT apply_tenant_isolation('product_attribute_profile_roots', 'shared');
SELECT apply_tenant_isolation('user_preferences', 'subject', 'keycloak_user_id');
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
| T6 | The tenant setting is transaction-local | after commit the connection **no longer carries the tenant**: `current_setting('app.company_id', true)` answers `''` on a pooled session that set it once and `NULL` on one that never did, and `nullif(..., '')::uuid` filters every row in both cases |
| T7 | A shared row is readable by all, an override only by its owner | the `NULL`-owner profile is visible to A and to B; the override only to its tenant |
| T8 | The sequence grant is not an afterthought | as `lifecontrol_app` scoped to A, insert a row whose `id` comes from an identity column: succeeds |

The parent decision's suite, T2–T5, runs as `lifecontrol_app` on Testcontainers (`build.gradle:85-86`).

## Migration from the current tree

One migration per group, in this order, because a policy on a table whose `company_id` is still nullable
hides the rows it cannot attribute:

1. **Roles and grants.** Create `lifecontrol_owner` and `lifecontrol_app`, plus the
   `lifecontrol_bootstrap` maintenance credential (item 3), move ownership to `lifecontrol_owner`, point
   `DATABASE_USERNAME` at `lifecontrol_app`. Flyway runs as the owner. `CREATE EXTENSION IF NOT EXISTS
   btree_gist` runs **before** the owner loses `SUPERUSER`, because the reverse order fails the first
   migration of every fresh database: `V21:27` needs an extension, and afterwards only the bootstrap role
   can create one.
2. **Columns first.** A table on the list needs `company_id NOT NULL` before it gets a policy. The
   contract migrations did this for products, variants, suppliers and the store hierarchy
   (`V40:101-102`, `V49:71`); a group still nullable is closed in its own migration, never by guess.
3. **Identity, store hierarchy and catalog**: `companies` (isolated by `id`, the root —
   `apply_tenant_isolation('companies', 'tenant', 'id')`, the one call whose key is not `company_id`),
   `company_countries`, `company_regions`, `company_zones`, `company_stores`, `products`,
   `product_variants`, `product_categories`, `suppliers`, `product_suppliers`, the two override catalogs
   with `kind => 'shared'`, and `user_preferences` with `kind => 'subject', key_column =>
   'keycloak_user_id'` — the one table compared to `app.user_id` and not to a company.
4. **Documents and operations**: the purchase and sales documents with their lines, the goods receipts,
   `inventory_movements`, `product_variant_locations`, `product_variant_store_stock`, the employee and
   org tables, and `tax_rate` once ADR-0002 exists.
   **This group is blocked on a column, not on a policy.** Measured in this tree:
   `purchase_order_details` (`V1:341`), `goods_receipts`/`goods_receipt_items` (`V12:19,39`),
   `inventory_movements` (`V10:30`), `product_variant_locations` (`V10:18`),
   `product_variant_store_stock` (`V13:23`), `sales_orders`/`sales_order_items` (`V1:468,496`),
   `employee_contracts` (`V21:29`), `positions` (`V19:42`), `access_provisioning_tasks` (`V22:36`),
   `employee_scope_assignments` (`V26:37`) and `customers`/`promotions`/`shifts` (`V1:381,424,448`) carry
   **no `company_id`**, so none of them can be armed by group 2's rule as written. Giving them the column
   is a data-model change with its own backfill and its own unit; until it lands, this group is the
   deferred half of this ADR and the assertion must not claim those tables.
5. **Assert, then narrow.** The assertion runs inside the migration and T2–T5 are the gate; nothing else
   lands on top until they are green.

Each group is one `PERFORM apply_tenant_isolation(...)` per table: no hand-written `CREATE POLICY`
anywhere, which is what makes "a new table is a list entry" true rather than aspirational. These land
**before** every other ADR's, so they take the lowest free versions — `V52` is already spent on the
machinery — and ADR-0001's range shifts by
however many this one takes. The other order writes every policy twice.

## Rejected alternatives

| Alternative | Why rejected |
| --- | --- |
| A schema per tenant | One schema is one migration set, so every migration becomes N migrations plus a loop that skips a failure on tenant 3 of 40. A cross-tenant report becomes a `UNION` over an unbounded number of schemas, and the pool still pins a schema per request — the same `SET LOCAL` this ADR needs, with more moving parts |
| Keep the 150 checks and add more discipline | That is the status quo, measured: 150 correct checks and nothing that makes the 151st correct. A review process is not an enforcement mechanism, and the failure it produces is silent |
| A Hibernate filter or a Spring Data interceptor | It covers only what goes through the ORM. A native query, a JPQL constructor projection (`ProductVariantRepository.java:152-170` is one) or a `JdbcTemplate` call bypasses it without a warning. A defence an already-present pattern silently skips is not a barrier |
| Policies plus an extra `BYPASSRLS` role for admin and batch work | One role that can see everything is one credential that can see everything. The audited service context below is the deliberate alternative. It is not `lifecontrol_bootstrap`, which has no password, no table grant and no way in over TCP |

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

## Corrections applied after acceptance

The decision was implemented on 2026-10-29, and implementation executed its statements against
PostgreSQL instead of reading them. Three of those statements are wrong — factually, not
stylistically — and they are corrected here, in the shape of the section above. **The status stays
`accepted`**: an amendment for factual accuracy does not un-accept a decision. What changes is what the
document says, not whether it binds; every later slice cites the items as they read now.

1. **Decision item 3 was short one role, and the missing one is a maintenance credential.** `initdb`
   creates exactly one superuser and, as this item originally assumed, it would be `POSTGRES_USER` — the
   owner (**corrected on 2026-10-31, and the correction is the entry at the end of this section**:
   `POSTGRES_USER` is the maintenance role instead, because the role `initdb` creates can never be
   demoted). Item 3 drops that flag, because
   a superuser bypasses RLS and the owner runs Flyway; from the **second** run onward the role script
   then has no role that can do what it does on the first. `ALTER ROLE lifecontrol_app ... PASSWORD`
   needs `CREATEROLE` or superuser, `ALTER ROLE lifecontrol_owner NOSUPERUSER` needs superuser, and
   `CREATE EXTENSION` needs superuser — which `V21__employee_contracts.sql:27` already requires for
   `btree_gist`. The failure mode is `deploy.sh <env> start` aborting on its second run against a
   database that has no superuser left in it at all. Item 3 is now three roles, three jobs, and the
   third is `lifecontrol_bootstrap`: `SUPERUSER LOGIN`, **no password** (the container's local socket is
   `trust`, and a role without a password cannot authenticate over TCP, where the image requires
   `scram-sha-256`), no grant on any table, and named by a compose file only as the role the database is
   initialized with — never as a login. The rule that follows is the point of the correction: the bootstrap role is for the DDL
   PostgreSQL reserves to superuser — creating a role, moving a role's attributes, creating an
   extension — and **never** for a migration that touches tenant rows, because that connection bypasses
   every policy, which is exactly what invariant T5 exists to catch.

2. **`shared_rows BOOLEAN` could not express one of the list's own cases.** A boolean says "the row may
   have no owner", which is the two override catalogs and nothing else: `product_attribute_profiles`
   (`V37:71`) and `product_attribute_profile_roots` (`V37:140`). `user_preferences` (`V1:368`) is a
   third shape. Its `company_id` is nullable and holds the user's *chosen* context, and the row is read
   during authentication before any company is known, so a tenant predicate empties the table exactly
   when it is needed — and a boolean has no value for that. The list is now
   `tenant_table(table_name regclass, policy_kind text NOT NULL CHECK (policy_kind IN ('tenant',
   'shared', 'subject')), key_column text NOT NULL DEFAULT 'company_id')`, and
   `apply_tenant_isolation(target regclass, kind text DEFAULT 'tenant', key_column text DEFAULT
   'company_id')` builds the predicate from the kind: `tenant` is the ordinary one, `shared` prefixes
   `IS NULL OR`, and `subject` compares the key against `app.user_id` with **no** `::uuid` cast, because
   `user_preferences.keycloak_user_id` is a `VARCHAR` and `''` already matches no row. That kind needs
   its own setting, so `set_user_scope` joins `set_tenant_scope`: the two facts are independent and a
   request may legitimately carry one alone. `user_preferences` is the `subject` table; `companies` is
   isolated by `id` and not by `company_id`. That pair is what `key_column` is for, and the helper no
   longer guesses — a policy naming a column the table does not have matches no row in silence, so the
   call raises before it alters anything.

3. **The predicate text raised instead of filtering, on the connection that matters.** The spelling
   `current_setting('app.company_id', true)::uuid` is correct only in a session that never set the
   parameter. Measured on `postgres:16-alpine`, in one session: a fresh session answers `NULL`; after
   **one** transaction-local `set_config` and its `COMMIT` the parameter exists and equals the **empty
   string**, so the bare cast raises `invalid input syntax for type uuid: ""` (SQLSTATE 22P02), while
   the read itself is `NULL` and filters. `RESET ALL` and `DISCARD ALL` do not undo it, so that is the
   steady state of every pooled connection after its first request: an exception where decision 2 and
   invariant T6 promise zero rows. Every predicate that reads `app.company_id` now reads
   `nullif(current_setting('app.company_id', true), '')::uuid`, and T6 says what PostgreSQL does instead
   of what the ADR hoped — after commit the connection **no longer carries the tenant**, and the value
   that remains is an empty string that filters every row. The `subject` predicate is unchanged and
   needs no `nullif`: it compares a `VARCHAR`, and `''` already matches no row.

The three were found against the database and the runbook, not against the prose: item 1 by walking the
role script's second run on a fresh volume, which is where its probe stops finding a superuser; item 2
by writing the policy list for the fifteen tables that carry `company_id`; item 3 by a session that set
its scope once and then read a live policy. The SQL in this document now matches
`V52__tenant_isolation_machinery.sql` token for token — the helper's signature, the closed
`policy_kind` vocabulary, the three predicate strings and the bootstrap role are the same text —
because an ADR that disagrees with the migration is a decision nobody can implement. The per-slice
evidence is recorded in `odd/tasks/tenant-isolation-rls.md` (L4, L6, L10, L11); that record is not
edited here.

**Four statements the same evidence made false, corrected with them.** They are not new decisions; they
are consequences of the three above that the document would otherwise keep asserting:

1. **Decision 1 named a mechanism instead of a requirement.** It said "a filter sets `app.company_id`",
   and this tree has no transaction hook a filter could use: no `TransactionManager`, no
   `TransactionTemplate`, no `StatementInspector`, and both existing servlet filters run before the
   security chain, so they cannot read the principal either. The claim that survives is the behavioural
   one — inside the transaction, before its first query — and it is now what the item states, with the
   forbidden shape named: a `SET LOCAL` issued on a connection other than the request's is a no-op
   dressed as enforcement.
2. **Migration group 4 described work its tables cannot accept.** Thirteen of them carry no `company_id`
   in this tree, so "arm them" is only true after a data-model change that this ADR never names; the
   group now says so, and says the assertion must not claim them.
3. **The extension ordering is load-bearing.** `btree_gist` has to be created before the owner loses
   `SUPERUSER`, or the first migration of every fresh database fails; step 1 says it.
4. **The version arithmetic expired.** `V52` is spent on this ADR's machinery, so the sentence that
   reserved `V52…V55` for ADR-0001 was describing a range that no longer exists.

**2026-10-30 — the scope source, and the two consequences of the fail-closed rule.** Implementing item 1
made one sentence false: "an absent or multi-valued tenant claim is a refusal". The claim is not the source
of the scope. Measured in this tree: `@RequestHeader` appears nowhere, 109 endpoints carry a `{companyId}`
path variable and 166 service methods take one, and the Keycloak mapper is multivalued **because** one
person may belong to several companies. Deriving the scope from the claim would refuse the case the mapper
exists for. The item now says what the implementation does: the value is the tenant the operation names,
verified against the claims, and what fails closed is an operation whose tenant cannot be determined at
all. Two consequences the ADR had left implicit follow, and both are narrow on purpose:

- **One asynchronous reader cannot derive its tenant**, because the table that would carry it is scanned
  across tenants by design. It gets a single `SECURITY DEFINER` function — one fixed query, one uuid back,
  owned by the maintenance superuser because ownership is what grants the bypass — rather than a column, a
  second policy kind, or a role that sees every row.
- **A token with no `company_id` reads no tenant data**, which breaks two paths that are not reads of a
  tenant: creating a company states the id the application generates, so the row's tenant is its own
  identity, and listing companies resolves the caller's claims one at a time instead of widening a policy.
  A user who belongs to no company gets an empty list, which is the honest answer.

**2026-10-31 — the role `initdb` creates is the maintenance one, not the owner.** Bringing the dev stack
up after the delivery half of item 3 (issue #720) measured the arrangement as impossible: `POSTGRES_USER`
named `lifecontrol_owner`, so `initdb` created **the owner** as its bootstrap superuser, and the role
script's `ALTER ROLE lifecontrol_owner NOSUPERUSER` was then refused — `ERROR: permission denied to alter
role / DETAIL: The bootstrap superuser must have the SUPERUSER attribute` — on a brand-new data directory
exactly as on an old one. Nothing could initialize: no fresh environment, and no `db-roles.sh` run against
an existing volume either. The 23 offline `docker/scripts/tests` suites could not see it, because they pin
the text of these scripts and never ask a server to accept the SQL.

The decision is unchanged — three roles, three jobs — and what changes is which one `initdb` makes. Item 3
and `## Schema` now say: `POSTGRES_USER` is `lifecontrol_bootstrap`, the maintenance role; the owner is
**created** `NOSUPERUSER` by the role script, with its password taken from the owner's secret, and it
takes ownership of the database, because `pg_database_owner` resolves to `POSTGRES_USER` and a database
left owned by the bootstrap role refuses the owner's first `CREATE TABLE`. The bootstrap's password is
dropped (`PASSWORD NULL`) so nothing can log in as it over TCP, and the two compose files name it — the
one exception to "never named by a compose file", since `initdb` needs it.

A data directory whose `initdb` role already was the owner cannot be repaired: PostgreSQL will not demote
that role, so the volume has to be recreated once. That is a consequence of this decision, not a defect of
the script that applies it.
