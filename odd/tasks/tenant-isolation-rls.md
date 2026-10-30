# ODD feature: tenant-isolation-rls

**Repository**: LifeControl — `life-control-api` (migrations, transaction scope, startup assertion), `docker/` (roles, secret)
**Branch**: `feat/tenant-isolation-rls` · **Worktree**: `~/workspace/LifeControl-worktrees/feat-tenant-isolation-rls`
**Status**: implemented — the slices T1–T6 of this record are the delivery; each one closes only when its
`S#` checks were observed, and any item still open is named in `## Log` with its reason. Nothing is
deferred except what S2 lists as out of scope.
**Created**: 2026-10-29
**Risk**: items 1, 2 and 5 — this changes the credential the application connects with, adds a scope to
every query path, and a missing scope fails **closed** (zero rows) instead of loudly.

## Objective

Implement accepted ADR-0005 (`docs/adr/0005-tenant-isolation-rls.md`) in `life-control-api`: PostgreSQL
enforces the tenant, not 150 call-site checks. Issue: **#696**.

## Specs

S1. `docs/adr/0005-tenant-isolation-rls.md`, **accepted 2026-10-29**, is the specification of this unit.
    Its decision items 1–6, the `## Schema` block, invariants T1–T8 and `## Migration from the current
    tree` are authoritative, including the three corrections recorded in its closing section.

S2. **Scope of the policy set, decided by the maintainer**: «Solo las 15 tablas que ya tienen
    company_id». The fifteen are `company_countries`, `user_preferences`, `departments`, `employees`,
    `product_categories`, `product_attribute_profiles`, `product_attribute_profile_roots`, `products`,
    `product_variants`, `suppliers`, `product_suppliers`, `purchase_orders`, `company_regions`,
    `company_zones`, `company_stores`. The thirteen tables the ADR's group 4 names but that carry **no**
    `company_id` column today (`purchase_order_details`, `goods_receipts`, `goods_receipt_items`,
    `inventory_movements`, `product_variant_locations`, `product_variant_store_stock`, `sales_orders`,
    `sales_order_items`, `employee_contracts`, `positions`, `access_provisioning_tasks`,
    `employee_scope_assignments`, `shift`/`customers`/`promotions`) are **out of scope**: giving them the
    column is a data-model change with its own backfill and belongs to its own unit. The startup
    assertion must therefore refuse only an unlisted table that has a `company_id`, and `## Log` records
    the deferred list so the next unit starts from it instead of from memory.

S3. **`user_preferences` is isolated by subject, not by tenant**, decided by the maintainer: «Policy por
    sujeto: keycloak_user_id = app.user_id». Its `company_id` is nullable and holds the user's *chosen*
    context, and the row is read during authentication before any company is known, so a tenant
    predicate would empty the table exactly when it is needed. It gets a second scope setting,
    `app.user_id`, and its policy is `keycloak_user_id = current_setting('app.user_id', true)`.

S4. **Two roles, decided by the maintainer**: «Owner NOSUPERUSER + app con secreto nuevo».
    `lifecontrol_owner` is `NOSUPERUSER`, owns the objects and runs Flyway from inside the API process;
    `lifecontrol_app` is `NOBYPASSRLS`, owns nothing, receives `USAGE` on the schema, `SELECT/INSERT/
    UPDATE/DELETE` on tables and `USAGE, SELECT` on sequences plus the matching
    `ALTER DEFAULT PRIVILEGES`, and is the only role the pool connects as. The app credential travels in
    its own secret file, never in a `.env`. Because `FORCE ROW LEVEL SECURITY` reaches the owner and
    invariant T5 pins it, holding the owner credential does not grant row visibility.

S5. **Issue #696 acceptance criteria** are binding: one test per invariant T1–T8, the application refuses
    to start when a `company_id` table is missing from the list, and the existing suite passes with the
    pool connected as `lifecontrol_app`.

S6. **Repository conventions** (`AGENTS.md`, `.agents/skills/project-conventions`): worktree per unit,
    exactly one `type:*` label per PR, slices of about 400 authored lines, tests and docs in the same
    slice as the behavior, ODD header rules, and no commit on the default branch.

## Tasks

Slice dependency order. Nothing here touches the 150 call-site checks, which stay as authorization on top
of isolation (S1, decision item 5).

| ID | S# | Work | Route | Commit |
| --- | --- | --- | --- | --- |
| T1 | S1, S4, S5, S6 | Roles and grants in delivery: postgres init script creating `lifecontrol_owner` (NOSUPERUSER, owner) and `lifecontrol_app` (NOBYPASSRLS), new secret `lifecontrol_app_password` with its template, `.env.template` keys, base and prod compose wiring, `setup-env.sh` materialization, `validate-env.sh` checks, and the docker suites that pin them | inline | — |
| T2 | S1, S5 | Migration `V52`: `set_tenant_scope`, `set_user_scope`, the `tenant_table` list (`tenant` / `shared` / `subject`), `apply_tenant_isolation(...)` with `key_column` and `shared_rows`, the `ALTER DEFAULT PRIVILEGES` pair, and `unprotected_tenant_tables()` as the assertion query | inline | — |
| T3 | S1, S2, S5 | Migration `V53`: the assertion wired as a startup refusal in Java, so a `company_id` table missing from the list stops the deployment instead of hiding rows | inline | — |
| T4 | S1, S5 | The transaction scope: `app.company_id` and `app.user_id` installed at the transaction boundary from the principal, failing closed and logging the request id when it refuses; plus the explicit scope the provisioning worker sets per task, since it reads `employees` outside any request | inline | — |
| T5 | S1, S2, S3, S5 | Policies: the catalog and hierarchy group (`companies` by `id`, `company_countries`, `company_regions`, `company_zones`, `company_stores`, `product_categories`, `products`, `product_variants`, `suppliers`, `product_suppliers`, the two override catalogs as `shared`, `user_preferences` as `subject`), then `departments`, `employees`, `purchase_orders` | inline | — |
| T6 | S5, S6 | The T1–T8 suite on Testcontainers as the gate, the PR chain with evidence, and the cleanup | inline | — |

## Log

L1 — the maintainer's request, verbatim: «que sigue?» → «Arrancar primero la implementación de 0005».
The three scope answers, verbatim: «Solo las 15 tablas que ya tienen company_id», «Policy por sujeto:
keycloak_user_id = app.user_id», «Owner NOSUPERUSER + app con secreto nuevo».

L2 — **measured before the first write** (read-only exploration, `grep`/`read`/`find`; CodeGraph was
unavailable: `codegraph binary was not found`). Five findings changed the plan and are the evidence
behind S2–S4:

1. `company_id` appears on exactly **15** tables, which reconciles the ADR's count, but **three** are
   nullable: `product_attribute_profiles` (`V37:71`) and `product_attribute_profile_roots` (`V37:140`)
   are deliberate, and `user_preferences` (`V1:368`) is narrowed by nothing — the ADR does not name it.
2. Thirteen tables the ADR's group 4 calls tenant rows carry **no** `company_id`:
   `purchase_order_details` (`V1:341`), `goods_receipts`/`goods_receipt_items` (`V12:19,39`),
   `inventory_movements` (`V10:30`), `product_variant_locations` (`V10:18`),
   `product_variant_store_stock` (`V13:23`), `sales_orders`/`sales_order_items` (`V1:468,496`),
   `employee_contracts` (`V21:29`), `positions` (`V19:42`), `access_provisioning_tasks` (`V22:36`),
   `employee_scope_assignments` (`V26:37`), `customer`/`promotions`/`shift` (`V1:381,424,448`). This is
   S2's boundary.
3. `POSTGRES_USER` is `lifecontrol_user` (`docker/.env.template:41-42`, `docker-compose.yml:54-55`) and a
   superuser **always** bypasses RLS, so policies alone would have enforced nothing. That is why S4 is
   not optional.
4. There is **no** transaction hook in the tree: no `TransactionManager` bean, no `TransactionTemplate`,
   no `StatementInspector`, no aspect over `@Transactional` — transactions are plain annotated service
   methods. `CurrentUserContext` is a request-scoped bean (`common/auth/CurrentUserContext.java:41-42`),
   and both app filters run **before** the Spring Security chain (`RateLimitFilter` at
   `HIGHEST_PRECEDENCE`, `ContentCachingFilter` at `+10`), so the ADR's "a filter sets `app.company_id`"
   cannot be implemented as written: a servlet filter has no transaction to be `LOCAL` in. T4 installs
   the scope at the transaction boundary instead.
5. `application-test.properties:2-5` makes **H2** the default test profile with Flyway disabled, while
   `AbstractPostgresIntegrationTest:33-50` runs `postgres:16-alpine` with Flyway enabled and 64 classes
   extend it. RLS only exists on the PostgreSQL path, so every T-test lives there; H2 suites cannot see a
   policy. Dev and prod run `postgres:18.6` (`.env.template:155`) against `16-alpine` in tests.

L3 — the asynchronous paths that T4 must cover, measured: `AccessProvisioningWorker.runPass()`
(`provisioning/service/AccessProvisioningWorker.java:159`) reads `employees` **outside any request**
(`:234`), scheduled from `config/provisioning/ProvisioningWorkerSchedulerConfig.java:88-90`; a daemon
`ScheduledExecutorService` in `config/provisioning/AccessProvisioningQueueMetrics.java:133-136` reads only
`access_provisioning_tasks`, which has no `company_id` and is out of S2's set. Without a scope the worker
would stop provisioning **silently** — the failure mode the ADR names in its consequences.

L4 — **parent review of T1 found one blocking defect, and it is a defect of ADR-0005's decision 3, not
only of the code.** The delivered SQL drops `SUPERUSER` from `lifecontrol_owner` and the wrapper picks
the first candidate that answers `SELECT 1`. On the second run `POSTGRES_USER` **is** `lifecontrol_owner`,
so the probe succeeds and the SQL then fails: `ALTER ROLE lifecontrol_app ... PASSWORD` needs
`CREATEROLE`/superuser and `ALTER ROLE lifecontrol_owner NOSUPERUSER` needs superuser — the owner has
neither, so `deploy.sh <env> start` would abort from the second run onward. And a `rolsuper`-checking
probe alone does not fix it: with `POSTGRES_USER=lifecontrol_owner`, `initdb` creates **only** that role
as superuser, so once the flag is dropped the container has no superuser at all and no future migration
could create an extension (`V21:27` shows extensions are part of this tree). The ADR's "two roles, two
jobs" is therefore short one role: an owner that never bypasses RLS cannot also administer roles and
extensions. Correction applied: a third, `SUPERUSER LOGIN` role with **no password** (socket-only, cannot
authenticate over TCP where the image requires `scram-sha-256`), never referenced by the API, the
exporter or any compose file, plus a probe that verifies `rolsuper` instead of connectivity and a suite
that pins both. **The ADR needs a one-line amendment** naming that maintenance role; it is recorded here
first so the amendment is not invented later from code. This is the second time the same class of defect
appeared — 0005 said the owner runs Flyway while `FORCE` filters the owner, and now 0005 gives the owner
every job while forbidding it the privileges those jobs need.

L5 — the parent review also found the slice at ~855 authored lines, roughly twice the ~400 review budget
in S6, with 425 of it the new offline suite. T1 splits into **T1a** (the credential: secret, template,
compose mounts, env keys, `validate-env.sh`, the API wrapper, the exporter consumer) and **T1b** (the
roles: `lifecontrol-roles.sql`, the init wrapper, `db-roles.sh`, the `deploy.sh` ordering and the suite),
in that order, because T1b's wrapper cannot run without T1a's secret file being mounted.

L6 — the bootstrap correction landed, and it changes two things beyond the bug. **(a)** L5's split of T1
into two *pull requests* does not survive contact with the suites: they cross-assert one single contract
(a compose file that mounts the secret, and a script that needs it), so each PR on its own would fail CI.
The split is kept as **two commits inside one pull request** — declaration first, creation second — which
is what "a unit whose Risk line names a high-risk item gets its own commit so review can take it alone"
asks for, and the pull request states its size instead of hiding it. **(b)** `lifecontrol_bootstrap` is now
the only role that can create an extension or alter a role, and Flyway runs as `lifecontrol_owner`, which
is neither. A future migration that creates an extension therefore has to connect as the bootstrap
superuser, and doing that for an ordinary migration would bypass RLS — the exact thing invariant T5 guards.
The rule for later slices: the bootstrap role is for DDL that PostgreSQL reserves to superuser, never for a
migration that touches tenant rows, and a migration that needs it must say so in its own file, the way
decision item 6 says.

L7 — operational consequences to state in the pull request, all measured in this tree. A `.env.<env>` that
already exists still names `lifecontrol_user` and nothing reconciles it: the API fails authentication and
the exporter reports `pg_up 0` until the two keys are edited, and `db-roles.sh` prints the exact `sed`
without touching the file. `docker/secrets/lifecontrol_app_password` does not exist in an existing clone
and every template in the repo ships `CHANGEME`, which the role wrapper refuses: one
`./docker/scripts/setup-env.sh <env>` plus one real value per environment is a required manual step, and
the API wrapper now refuses to start without that file. The postgres exporter also moves from a superuser
to a `NOSUPERUSER` owner, so its collectors need one look on a live stack — `deploy.sh health` would only
notice a completely down target.

L8 — the slice boundaries of T1, recorded because the harness measured the branch past the ~400-line
budget and the boundary has to live in the document and not in a session. T1 is **three commits in one
pull request**, each a reviewable unit: `declaration` (the record, the secret, the compose mounts, the env
keys, the wrapper, `validate-env.sh` and the three extended suites, 363 authored lines), `creation` (the
role SQL, the init wrapper, `db-roles.sh` and the `deploy.sh` ordering) and `suite` (the offline
`db-roles.test.sh` that pins both). The suite is its own commit on purpose: it is one readable artifact
and it asserts a contract that spans the other two, which is exactly why splitting it across pull requests
was rejected in L6.

L9 — **the task order in this record was wrong, and it is corrected here before T2 writes a line.** The
table lists T2 then T3 (the assertion as a startup refusal) then T4 (the scope) then T5 (the policies).
That order cannot work, for two independent reasons:

1. **A policy without a scope is zero rows everywhere.** The moment a `company_id` table carries a policy,
   every query on it needs `app.company_id` set, so T4 — the transaction scope — has to land **before or
   with** the first policy, never after it. Landing T5 first would take the running application to "reads
   nothing" on every tenant table.
2. **An assertion that refuses to boot must land when the tree satisfies it.** The assertion's own query
   returns every `company_id` table that is not on the list; until the policies exist that is all fifteen,
   so wiring it as a startup refusal in T3 would make the application refuse to start after every deploy
   between T2 and T5. It lands **last**, as a gate over a tree that already passes it.

Execution order, which supersedes the table's row order while keeping its IDs as identifiers:
**T2** (the machinery: scope functions, the list with its three kinds, `apply_tenant_isolation`, the
default privileges, and the assertion *query* — no enforcement) → **T4** (the transaction scope, from the
principal and from the worker) → **T5** (the policies, group by group) → **T3** (the assertion wired as a
startup refusal, now that the tree satisfies it) → **T6** (the T1–T8 suite as the gate, the PR chain and
the evidence).

One dependency T2 discovers by running, not by reading: the migration grants to `lifecontrol_app`, and the
test container starts with a single superuser (`AbstractPostgresIntegrationTest:33-37`, user `lifecontrol`
on `postgres:16-alpine`). Either the migration or the test base has to create the app role, and creating it
in a migration would put a role in version control without its credential. So the test base creates both
roles once, idempotently, and that is what lets T5's and T6's tests connect as the role that RLS actually
applies to — the `lifecontrol` superuser bypasses every policy, which is the same trap T1 removed from
production.

L10 — **T2 is written, and running it found a defect in ADR-0005's decision-2 predicate: on a pooled
connection the tenant predicate raises instead of filtering.** Measured in `postgres:16-alpine`, not
reasoned:

```sql
SELECT current_setting('app.company_id', true);                                  -- NULL, good
BEGIN; SELECT set_config('app.company_id', '<uuid>', true); COMMIT;
SELECT current_setting('app.company_id', true) = '';                             -- t
SELECT current_setting('app.company_id', true)::uuid;                            -- ERROR 22P02: invalid input syntax for type uuid: ""
SELECT nullif(current_setting('app.company_id', true), '')::uuid;                -- NULL, filters
```

Once a session has set the parameter — even transaction-locally — PostgreSQL keeps it **defined** and
resets it to the **empty string**, not to NULL. `RESET ALL` and `DISCARD ALL` do not undo that (measured),
so it is the steady state of every pooled connection after its first scoped transaction, and a transaction
that reaches a tenant table with no scope gets SQLSTATE **22P02** instead of the zero rows decision 2 and
invariant T6 promise. Consequences, in the order they matter:

1. **V52 as delivered builds the predicate exactly as the ADR states it** (`<key> =
   current_setting('app.company_id', true)::uuid`), because T2's brief fixes that text. The published
   `false`-safe form is a one-token change in the `tenant` and `shared` branches:
   `nullif(current_setting('app.company_id', true), '')::uuid`. `subject` needs nothing — it compares a
   `VARCHAR` with no cast, so `''` already matches no row and fails closed.
2. **It has to be decided before T5, not after.** V52 cannot be edited once applied, so the fix then costs
   a new migration that `CREATE OR REPLACE`s the function before fifteen policies are pinned on it.
3. **Decision 2 and invariant T6 need a wording amendment.** "The value is `NULL` after commit" is not
   what PostgreSQL does; what holds is "the setting no longer carries the tenant" — `NULL` only on a
   session that never set it, which is why the suite pins both cases on purpose
   (`theScopeSettingsDieWithTheTransaction` reads `null or empty`; `aSessionThatNeverSetTheScopeReadsNull`
   opens a fresh `DriverManager` session to show the `NULL` half).

Three smaller things T2 measured and the record did not say:

- **`shared_rows BOOLEAN` cannot express S3.** The ADR's `## Schema` block and this record's T2 row both
  describe the list as `(table_name, shared_rows BOOLEAN)`; the delivered list is
  `(table_name regclass, policy_kind text CHECK (...), key_column text)`, because a boolean has no value for
  the third kind and `subject` is the one S3 decided. The helper's signature follows:
  `apply_tenant_isolation(target regclass, kind text DEFAULT 'tenant', key_column text DEFAULT
  'company_id')`, and `tenant_table_policy_kind_closed` is the constraint a fourth kind fails on.
- **The test container's owner is `lifecontrol`, so `ALTER DEFAULT PRIVILEGES FOR ROLE CURRENT_USER` is
  load-bearing, not stylistic.** A literal `lifecontrol_owner` there would have granted for a role that
  creates nothing: the statement succeeds and the grant is inert, which no test could see. In the container
  the two roles come from `src/test/resources/test-container-roles.sql` via `withInitScript` (before
  Flyway, once per JVM, no password, `SET ROLE` for any suite that needs the app role); in delivery the
  file that owns the role shape stays `docker/postgres-init/lifecontrol-roles.sql`.
- **`GRANT ... ON ALL SEQUENCES` is unobservable today.** Every key in this tree is a UUID, so `public`
  holds no sequence at all; the observable half of that contract is the `ALTER DEFAULT PRIVILEGES`
  counterpart, which the suite proves by creating a table and a sequence inside its own transaction. The
  same suite also had to learn that `pg_class` carries the **TOAST** sequences of `pg_toast` and that WHERE
  evaluation order is not guaranteed: `has_sequence_privilege` needs a `CASE` guard or the query fails with
  `"pg_toast_16387" is not a sequence`.

L11 — **the predicate defect is real, independently reproduced, and fixed inside V52 rather than in a
follow-up migration.** Reproduced by the parent on `postgres:16-alpine` in one session: a fresh session
answers `NULL` for `current_setting('app.company_id', true)`; after one `set_config(..., true)` and its
`COMMIT` the parameter exists and equals the **empty string**; the ADR's bare `::uuid` then raises
`invalid input syntax for type uuid: ""` (SQLSTATE 22P02); `nullif(..., '')::uuid` answers `NULL`. The
failing case is therefore the ordinary pooled connection after its first request — the ADR's decision 2
and invariant T6 promise zero rows, and the ADR's own spelling delivered an exception. V52 now writes
`nullif(current_setting('app.company_id', true), '')::uuid` in the `tenant` and `shared` branches, and the
section-1 header states the measurement instead of the promise. Two cases in
`TenantScopeFunctionsIntegrationTest` model the pool literally — one connection, a committed scope
transaction, then a second transaction on the same session reading a live policy as `lifecontrol_app` —
and they were **run against the old spelling first**: two failures inside the read itself (`22P02`), with
the superuser control count proving the rows existed, then green after the `nullif` was restored (V52
`sha256 0de5764c…` identical before the temporary revert and after). `set_user_scope` needs no equivalent,
because its consumer compares a `VARCHAR` and `''` already matches no row.

Two residues recorded for the slices that follow. **(a)** The corrected predicate is what T5 inherits: the
only place a policy is ever created is `apply_tenant_isolation`, so the fail-closed behaviour of all
fifteen tables is decided by those two `format()` strings. **(b)** The suite leaves `app.company_id` defined
as the empty string on one pooled connection for the rest of the JVM — inert today, because no real table
carries a policy until T5, but T5's and T6's cases must `SET ROLE lifecontrol_app` explicitly: the
container's own user is a superuser and bypasses every policy, which is the same trap T1 removed from
production.

L12 — the slice boundaries of T2, recorded for the same reason as L8. T2 is **two commits and one tiny
third**: `af37e7a0` (V52, the test container's roles, the base-class hook and this record) is the unit that
changes what the database and the test JVM can do; `776a2dbd` is the 16-case suite, its own artifact
because it is one contract's pin and not a diff to read line by line; and this entry, so the boundary lives
in the document rather than in a session. 1031 authored lines in total, of which 654 are the suite. The
same reasoning as L6 applies: splitting the suite across pull requests would leave each partial PR red,
because both halves assert facts the other half introduces.

L13 — **T4's mechanism is decided, and the measurement is why.** The ADR (item 1, as amended) leaves the component open and states the requirement; three measurements from this tree pick the component:

1. **The company of an operation comes from the URL, not from the token.** `@RequestHeader` appears **nowhere** in `life-control-api/src/main/java`; `PathVariable` naming a company appears **109** times and `UUID companyId` is a parameter of **166** service methods. The token may legitimately carry several companies — the Keycloak mapper is multivalued and two stores is its documented case — and `verifyCompanyAccess` (`common/auth/CurrentUserContext.java:193`) checks membership per call. So deriving the scope from the claim would refuse a multi-company user, which is the case the mapper exists to support. **The scope comes from the operation's company**, and the ADR's "multi-valued claim is a refusal" is read as what it is: refuse when the operation's company cannot be determined, never widen.
2. **Almost nothing reads outside a transaction, and the exceptions are enumerable.** 57 classes inject a repository; 52 declare `@Transactional`; **0 of 50 controllers** reference one; `spring.jpa.open-in-view=false` (`application.properties:8`), so the tree already cannot depend on a session outside a transaction. The five without any annotation are `AccessProvisioningWorker` (`:108`), `AccessProvisioningQueueMetrics` (`:120`), `CompanyStoreFilterResolver` (`:26`), `CompanyStoreScopeResolver` (`:38`) and `AccessProvisioningProducer` (`:79`). Two of those are the non-request threads; the producer joins its caller's transaction by contract.
3. **Suspension would erase a request-wide scope.** `ProfileLocationResolver.resolve` (`:58`) is `@Transactional(propagation = NOT_SUPPORTED)` and calls `CompanyStoreService.getStoreById` (`:146`, `readOnly`), so a scope installed only by a per-request transaction disappears exactly there — while a scope installed at **each** transaction boundary is re-applied on the inner one.

**The design that follows, and it is one mechanism with one source of truth:** a `TenantScope` holder carries the operation's company for the duration of the request; a filter that runs **after** authentication reads `/api/companies/{id}` from the path, verifies the id belongs to the token's claims, and refuses otherwise (so the scope can never be set for a tenant the caller cannot claim — the fail-closed rule moves to the place that has the information); and **every transaction boundary installs the scope from that holder**, which is what survives `NOT_SUPPORTED` and what the asynchronous paths use explicitly, one tenant at a time. No whole-request transaction, so transaction semantics stay as they are today.

The one read T4 must fix by hand is `employeeRepository.findByIdWithCompanyAndStatus` (`AccessProvisioningWorker:234`): it reads `employees`, which will carry a policy, from a thread with no request. The worker sets the scope from the task's own company, and the other unlocked reads in that pass — `findDueTaskIds`, `findStaleRunningTaskIds`, `findById` — read `access_provisioning_tasks`, which has no `company_id` and is outside S2's list.

L14 — **T4 is written, and writing it found the one thing the ADR's own shape cannot express.** The slice is
Java only — no migration, no policy — and it installs the scope at four places that together are one
mechanism:

- `common/tenant/TenantScope`: the holder, a thread-confined static (the `SecurityContextHolder` pattern)
  carrying `(companyId, subject)` with `set`/`clear`/`current`/`with`. Static and not a bean because two of
  its three callers cannot inject anything: the transaction manager is a bean factory method `spring-boot-jpa`
  also defines, and the filter is built by the security chain.
- `common/tenant/TenantScopeFilter`: reads `/api/companies/{uuid}` from the URI, verifies it with
  `CurrentUserContext.verifyCompanyAccess` (the same call the 150 checks make, so an administrator is admitted
  by the same rule instead of a second one), and **refuses** a company the token cannot claim. A path with no
  company segment — including the literal `/api/companies/selector` — states the subject only; a path whose
  segment is not a UUID states no company at all rather than turning a 400 into a 403.
- `common/tenant/TenantScopeJpaTransactionManager` + `config/TenantScopeTransactionConfig`: a
  `JpaTransactionManager` subclass replacing the auto-configured one (`@ConditionalOnMissingBean(TransactionManager)`
  is what makes the replacement possible), whose `doBegin` issues `select set_tenant_scope(?)` and
  `select set_user_scope(?)` through the **transaction's own** EntityManager, after `super.doBegin` bound it.
- `AccessProvisioningWorker`: the employee read of each task runs inside `TenantScope.with(taskCompany, …)`.

**Three measurements T4 did not have when L13 was written.** *(a) The filter's anchor is not the one the
brief named.* `addFilterAfter(…, BearerTokenAuthenticationFilter.class)` puts the filter **outside**
`ExceptionTranslationFilter` in this stack, and so does `addFilterBefore(…, ExceptionTranslationFilter.class)`:
in both cases the `AccessDeniedException` propagates outward and never reaches the translator, so the refusal
surfaced as a servlet exception instead of the HTTP 403 the rest of the tree answers with. The anchor that
works — measured by the acceptance suite, which asserts the 403 — is `addFilterAfter(…,
ExceptionTranslationFilter.class)`, i.e. inside the translator and still long after the JWT was validated.
*(b) The dialect gate cannot be read from Hibernate in this stack.* `EntityManagerFactory.unwrap(SessionFactory.class)`
returns a JDK proxy that does not implement `SessionFactoryImplementor`, so the `getJdbcServices()` cast threw
inside `doBegin` and — worse — left the EntityManager holder bound, which fails the next transaction on the
thread with "already value … bound for key". The gate now asks the **database** through the pooled
`DataSource` once and caches the answer, and it is decided **before** `super.doBegin` so that a failure there
cannot leave a bound resource behind. *(c) The H2 no-op is load-bearing and measured in both directions:
removing the gate fails `TenantScopeH2NoOpTest` with `Function "SET_TENANT_SCOPE" not found`* — the exact
build-breaking failure the brief predicts — **and** the case has to state a scope to prove anything, because
an absent scope short-circuits the installer before the dialect is ever consulted.

**Trap 3 is answered, not "improved":** the installer skips a half it has no value for instead of writing
`NULL`, so a request with no company segment costs one round trip fewer and the pooled connection keeps the
empty string `nullif(…, '')` already filters every row with. `with(null, …)` keeps that shape — a tenant that
could not be determined installs nothing and the read finds zero rows.

**The finding T5 has to resolve, recorded before it bites.** `access_provisioning_tasks` carries no
`company_id` (S2), and the worker's tenant is *not* derivable from a tenant table: `employees` and `companies`
are both on the armed list, so the moment T5 creates their policies the worker's resolution
(`AccessProvisioningTaskRepository.findEmployeeCompanyIdByTaskId`, the single place the pass learns its tenant)
reads **zero rows with no scope**, and every claimed task fails with "Employee not found" — provisioning stops
loudly per task, in a retry loop, which is the ADR's named failure mode wearing a different mask. The fix is one
of two, and both are outside this slice: put `company_id` on `access_provisioning_tasks` (the data-model change
S2 defers to its own unit, and the shape a platform queue needs anyway), or resolve the tenant through a
`SECURITY DEFINER` function. **T5 does not start until one of them is chosen**, because arming `employees`
without it breaks the worker on deploy. The same reading applies to two adjacent facts T5 must decide rather
than discover: `POST /api/companies` names no company in its path, so with `companies` armed by `id` its
`WITH CHECK` refuses the insert (creating the first company needs an exception or a bootstrap path), and an
administrator whose token carries no `company_id` reads nothing on the endpoints whose company comes only from
the claim. Neither is T4's to solve — this slice adds isolation *under* authorization and changes no call site.

Authored lines, for the review budget: **7 main files** (four new — the holder, the filter, the boundary hook
and its `@Configuration` — plus `SecurityConfig`, the task repository and the worker) and **5 test classes**,
about 1 560 authored lines of which **993 are the five suites and this entry** (`wc -l`: 924 test + 69 record;
main is 471 new + 98 changed). T2's 658-line functions suite is excluded because it is not this slice's diff.

L15 — the slice boundaries of T4, as they actually are. Two commits: `989bb094` is the whole Java half —
the holder, the transaction-boundary manager, the bean config, the filter with its security anchor, and the
worker's per-task scope — 565 authored lines, and `9e680ba8` is the five test classes, 923 lines. The Java
half intended to split into "mechanism" and "the two places that state a value", and it did not: the branch
was already pushed, and re-splitting it needs a force-push, which this repository's policy blocks and which
is not worth asking for cosmetics. What matters for the reviewer is recorded instead: the mechanism is the
only code that runs on **every** transaction, so it is the file to read first
(`TenantScopeJpaTransactionManager`), and the other four files only decide *which* value it installs.
1563 authored lines in total; the size is stated in the pull request rather than hidden.
