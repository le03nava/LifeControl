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
| T3 | S1, S2, S5 | Migration `V54`: the assertion wired as a startup refusal in Java, so a `company_id` table missing from the list stops the deployment instead of hiding rows | inline | — |
| T4 | S1, S5 | The transaction scope: `app.company_id` and `app.user_id` installed at the transaction boundary from the principal, failing closed and logging the request id when it refuses; plus the explicit scope the provisioning worker sets per task, since it reads `employees` outside any request | inline | — |
| T5a | S1, S2, S3, S5 | The two claim-less paths and the worker's tenant: `task_employee_company(uuid)` in the roles script, the worker through it, the company create stating its own id, and the per-claim list (records L16–L19) | inline | — |
| T5b | S1, S2, S3, S5 | The mechanism for the paths that name no company: one claim is the tenant, several refuse, a closed tenant-agnostic list, the worker's whole task under its tenant, the administrator's list over its claims, and the subject of a user created mid-call (record L20) | inline | — |
| T5c | S1, S2, S3, S5 | Migration `V53`: the sixteen tables armed with `apply_tenant_isolation` — the catalog and hierarchy group (`companies` by `id`, `company_countries`, `company_regions`, `company_zones`, `company_stores`, `product_categories`, `products`, `product_variants`, `suppliers`, `product_suppliers`, the two override catalogs as `shared`, `user_preferences` as `subject`), then `departments`, `employees`, `purchase_orders` — plus the two assertions that flip with it (record L20's handoff) | inline | — |
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

L16 — **the two T5 blockers are decided.** The maintainer chose the *narrow* escape and the *strict* rule.

1. **The worker's tenant comes from one `SECURITY DEFINER` function**, «Función SECURITY DEFINER acotada»:
   `task_employee_company(task uuid) RETURNS uuid` with a fixed body (`SELECT company_id FROM employees
   WHERE id = (SELECT employee_id FROM access_provisioning_tasks WHERE id = $1)`) and `EXECUTE` for
   `lifecontrol_app` only. No column, no backfill, no second policy kind, and the bypass is one query that
   returns one uuid instead of a connection that sees every row.
   **It cannot live in a migration**, and that is the part worth remembering: Flyway runs as
   `lifecontrol_owner`, which is `NOSUPERUSER`, so it cannot create an object owned by
   `lifecontrol_bootstrap` — and the owner is exactly the entity whose bypass this function must not
   have. It belongs in `docker/postgres-init/lifecontrol-roles.sql`, the one script that runs as the
   maintenance superuser, which `db-roles.sh` re-applies on every deploy; a plpgsql body is parsed lazily,
   so creating it there — before `employees` exists on a fresh volume — is legal and stays idempotent.
   Consequence to state in that slice: the roles script now owns one schema object, and the reason is
   that ownership is the only thing that grants the bypass, deliberately.

2. **A token with no `company_id` reads no tenant data**, «Falla cerrado, sin excepción». Two paths need an
   explicit answer rather than a policy that can never match:
   - **Creating a company**: the id is generated by the application, so the service states it —
     `TenantScope.with(newId, () -> save(...))` — and the `companies` policy's `WITH CHECK` passes on a row
     whose tenant *is* its own id. A path that names no company can never satisfy a policy; an operation
     that knows the value can.
   - **Listing companies**: the list is the caller's claims, so it is resolved per claim —
     one scoped read per claimed company — and never by widening the policy. A user with two companies
     gets two reads; a token with none gets an empty list, which is the honest answer and not an
     exception.
   The measurement that makes this safe: `0 of 50` controllers read a repository directly, so there is no
   other claim-less path hiding behind the list.

What this leaves for T5, in order: the resolver function in the roles script; the company create/list
paths; and then the policies, group by group, with `employees` now armable because its reader has a
resolver. `access_provisioning_tasks` stays off the list on purpose — its due-task scan is cross-tenant
and arming it would stop the worker from finding work at all — so it is a `company_id`-free table and the
assertion's rule ("a `company_id` table must be listed") does not apply to it.

L17 — **T5a is written: the two helpers, and no policy.** The resolver, the worker's one read through it,
and the two claim-less admin paths. Five measurements decided the shape, and two of them contradict what
this record assumed.

**The columns are the ones L16 guessed, and the join is FK-backed.** `access_provisioning_tasks`
(`V22__employee_access_provisioning.sql:36-46`) is `(id, employee_id → employees(id), kind, status, …)`
with `employee_id UUID NOT NULL REFERENCES employees(id)`, and `employees` (`V20:33-38`) is
`(id, company_id UUID NOT NULL REFERENCES companies(id), …)`. So the body is the fixed join L16 wrote, and
the task→employee→company path cannot dangle. `access_provisioning_tasks` still carries **no**
`company_id`, and nothing in the tree suggests it should: the due-task scan (`findDueTaskIds`) and the
stale scan are cross-tenant **by design** (the worker must find work from every tenant), so a policy on
that table would need a scope the pass cannot have before it reads a task. The column is the shape a
platform queue would want, but it would have to arrive *with* a way to scan across tenants, which is the
problem it looks like it solves.

**`LANGUAGE sql` cannot be created where the function has to live.** On a fresh volume
`/docker-entrypoint-initdb.d` runs **before** Flyway, so `employees` does not exist yet; a SQL-language
body is parse-analysed at creation time and fails with `relation "access_provisioning_tasks" does not
exist`. The delivered body is `LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog,
public, pg_temp`, which resolves names on first execution — legal before the tables, idempotent after
them — and the file says so in a comment. The rest is as L16 decided: `CREATE OR REPLACE`, then
`ALTER FUNCTION … OWNER TO lifecontrol_bootstrap`, `REVOKE ALL … FROM PUBLIC`, `GRANT EXECUTE … TO
lifecontrol_app`, and a read-back that raises when the owner is not the maintenance role, when the
function is not `SECURITY DEFINER`, when `PUBLIC` still reaches it (`aclexplode … grantee = 0`, or a NULL
acl, which is the default grant) or when the app role cannot. The file now owns exactly one schema object,
and the header says why: ownership is the bypass.

**The company id was *not* generated in Java, and the entity had to change.** `Company.id` was
`@GeneratedValue(strategy = GenerationType.UUID)` and `companies.id` (`V1:46`) has **no database
default**, so the value came from Hibernate at persist time — inside the transaction, after `doBegin`
read the scope. Hibernate 7 cannot be talked out of it: `AbstractSaveEventListener.generateId` passes the
assigned value only when `generator.allowAssignedIdentifiers()` is true, and `UuidGenerator.generate`
ignores it (`hibernate-core-7.4.13.Final-sources.jar`, measured). So `Company` now has a plain `@Id` plus
a `@PrePersist` that assigns a UUID **only when the caller stated none**, which keeps every other
`save` path (fixtures, the employee and provisioning suites) working and lets the service pass the id the
scope names. The service generates it, wraps the whole insert in `TenantScope.with(id, …)` and drives the
transaction **itself** (`TransactionTemplate`), because a `@Transactional` method's body runs after the
boundary it has to feed: the id and the scope are therefore stated in `createCompany(CompanyRequest)`, so
the controller and the one test that calls the service directly both get it. The `CompanyCreatedEvent`
still fires inside that transaction, which is what its `AFTER_COMMIT` listener needs.

**The list is one read per claim, and that costs the database-side page.** `companies` is armed by `id`,
so a non-admin read needs that claim as the scope; one transaction cannot state two tenants and widening
the policy is forbidden. `findAllByIdIn` and `findBySearchTermAndIdIn` are gone, and the claim path reads
`findById(claim)` inside `TenantScope.with(claim, …)`, projects inside the transaction (`address` is lazy)
and pages the collected list. One nuance worth stating instead of discovering: `CurrentUserContext
.getCompanyIds()` returns a **`HashSet`** (`common/auth/CurrentUserContext.java:463-466`), so "claim order"
is the claim *set's* iteration order — deterministic, but not the JWT's own order. A `LinkedHashSet` there
would make it the claim's order, and that file is outside this slice's surface. The admin branch is
untouched on purpose: under the armed policy its unscoped `findAll` is answered by the policy, which is
T5b's question and not this one.

**The test container has no resolver function, and that is the residual this slice hands to T5b.** The
function is created by `docker/postgres-init/lifecontrol-roles.sql`; the Testcontainers instance runs
`src/test/resources/test-container-roles.sql`, which creates the two roles and nothing else, and that file
is outside T5a's edit surface. Three worker suites run the real pass on that container, so the repository
cannot simply call the function: it asks the **database** (dialect first, then
`to_regprocedure('public.task_employee_company(uuid)')`, through the transaction's own connection) and
falls back to the employee join only where no ADR-0005 machinery exists. That fallback is fail-closed in
the direction that matters — under an armed policy it reads zero rows rather than another tenant's — but
**T5b must give the container the function before it arms `employees`**, or its own worker suites go red.
The two are one deployment story: the container is the only PostgreSQL this tree has that never runs the
roles script.

Evidence, as observed. **Resolver**: `docker/scripts/tests/db-roles.test.sh` grew a section 1b (17
assertions: signature, body, `plpgsql`, ownership `ALTER` and read-back, `SECURITY DEFINER` read-back,
`REVOKE`/`GRANT`, no `TO PUBLIC`, and the two orderings — the function after `CREATE ROLE
lifecontrol_app` at line 42 > 28 and after `lifecontrol_bootstrap` at 42 > 17). **RED**, measured against
the base file (`git show HEAD:…` piped through the suite's own `sql_code` filter): all five needles
absent. **GREEN**: `db-roles: 111 passed, 0 failed` (94 before this slice). **The pair**, as one test:
`TenantTaskResolverIntegrationTest.theResolverAnswersWhereThePolicyHidesTheRow` arms the real
`apply_tenant_isolation('employees', 'tenant')` in a transaction it rolls back, shows the superuser
control (1 row), then as `lifecontrol_app` the **plain read returns 0** while
`task_employee_company(task)` returns the company. **The worker's path**: the same class' second case
creates the fixture, the policy, the function and its grant on its own transaction's connection,
`SET LOCAL ROLE lifecontrol_app`, and asserts the **repository** (`findEmployeeCompanyIdByTaskId`) returns
the company and nothing for an unknown task. **RED** for both Java pieces was run with three temporary
reverts — `usesResolver()` forced to the join, `createCompany` without `TenantScope.with`, and
`apply_tenant_isolation('companies', 'tenant')` without its key column — and produced exactly the three
expected failures: `Optional.empty()` where the company was expected (the join returns nothing under the
policy), a `null` scope at the transaction, and `apply_tenant_isolation: companies has no column
company_id`, which is the helper refusing the wrong key. **GREEN** after restoring: 4/4 in the two new
classes, then `./gradlew test --tests '*TenantScope*' --tests '*Company*'` green, and the four worker
suites plus `StarterProductCategoryCompanyCreationIntegrationTest` green as the PRESERVE half (the
fallback branch, the assigned-id create and its `AFTER_COMMIT` seed, and `CompanyServiceTest`'s claim
cases). `spotlessApply`/`spotlessCheck` and `spotbugsMain` green; all 22 `docker/scripts/tests/*.test.sh`
suites green.

The company-write case is two cases on purpose, because the claim has two halves that cannot be observed
per stack: the policy half runs on one connection under `apply_tenant_isolation('companies', 'tenant',
'id')` as `lifecontrol_app` — the row the creating tenant writes is readable by it, invisible to another
tenant's scope, and a write naming another tenant is refused with SQLSTATE 42501 — and the Java half runs
through the real service and observes the scope the transaction was opened with plus the row it
committed, because arming a policy outside a transaction would leak into the 60-odd suites that share the
container. Authored lines: 8 modified files (this entry aside) and 4 new (2 main, 2 test) — measured,
`git diff --numstat` on the code alone is 412 insertions / 98 deletions, plus 680 new file lines of which
520 are the two suites. This entry is 97 of the 509 insertions `git diff --stat` reports for the branch.

L18 — **the test container cannot create an object before Flyway without changing one property, and the
symptom names neither.** The first attempt put `task_employee_company` in
`src/test/resources/test-container-roles.sql`, which runs before the context: Flyway then reads a `public`
schema that already holds an object, treats it as an existing database, **baselines at version 1** and
records `V1__baseline_schema.sql` as applied without running it. The first migration to fail is
`V2__seed_countries.sql` with `relation "countries" does not exist` — a message that names neither Flyway
nor the fixture, which is why the resolution is a comment and not only a line. The test profile now sets
`spring.flyway.baseline-version=0`: for a database the suite owns and just created, "nothing is applied
yet, apply everything" is the only true statement, and it changes nothing for an empty container because
the baseline does not trigger there. Measured both ways (`baselined schema with version: 1` → V2 dies;
`version: 0` → V1 … V52 apply), and two offline assertions in `db-roles.test.sh` now pin the property and
the symptom so the line cannot be reverted quietly.

Two smaller facts from the same round worth keeping. **(a)** The resolver's fallback for non-PostgreSQL is
the employee join, and PostgreSQL **without** the function is now an `IllegalStateException` naming
`./docker/scripts/db-roles.sh <env>`: the reachable silent case was never a test container but a restored
cluster, whose Flyway history already contains V52 while the roles — cluster globals — do not travel in a
schema dump. Spring re-wraps that exception as `InvalidDataAccessApiUsageException` at the repository
proxy; the message survives, and the worker stores it as `last_error` per task, which is where an operator
reads it. **(b)** `Company.id` had to stop being `@GeneratedValue`: Hibernate generated it at persist time,
*inside* the transaction, after the boundary hook had already read the holder, so the scope could never
have been the new company — measured in `hibernate-core` 7.4.13 (`UuidGenerator` ignores an assigned id).
The entity is now `@Id` with a `@PrePersist` fallback and the service generates the id before the
transaction it states.

L19 — the slice boundaries of T5a: three commits plus this note. `8f261666` is the delivery half (the
resolver in the roles script and the nine offline assertions that pin it — the part that must exist before
any policy), `bab5bd31` is the Java half (the worker's resolution and its loud failure, the company create
path with its measured entity change, and the per-claim listing), and `12647096` carries the test fixtures,
the two integration classes and the record. 1448 authored lines with this note, which the harness measured
past the review budget; the boundaries are recorded here for the same reason as L8, L12 and L15, and the
size is stated in the pull request rather than hidden. The part a reviewer should read first is not the
largest: `AccessProvisioningTaskTenantResolutionImpl`'s three-way decision is the only place where a
missing deployment step can be read as a policy result.

L20 — **T5b is the mechanism, and a measurement turned it from a migration into a slice.** The plan was
one migration of sixteen `apply_tenant_isolation` calls. Read before writing, the tree says that arming
them is not a migration but the moment **every** path has to state its tenant, and the paths that name
none were six, not one. The maintainer decided the split and the two questions the measurement opened,
verbatim:

1. «Dos slices: mecanismo, después migración» — T5b states the tenant; the migration is **T5c**, and it
   takes `V53`, which pushes T3's assertion to `V54`.
2. «Un claim ⇒ es el tenant; varios ⇒ 403» — the tenant of a company-less path is the caller's single
   `company_id` claim; two claims and no company in the path is a refusal, not a wider scope.
3. «Sólo los claims, como el resto» — an administrator's company list is its claims, exactly like every
   other caller's.

**What arming the sixteen tables would have done to a tree that was not ready, measured one reader at a
time.** These are the six that decided the split:

1. **The provisioning worker, past the resolver.** Only the employee read was scoped
   (`AccessProvisioningWorker.java:297`). `AccessProvisioningGateService.java:190`,
   `AccessProvisioningRoleService.java:187,204`,
   `AccessProvisioningMembershipService.java:166-175` and `AccessProvisioningAccountService.java:98` run
   with no scope and read or write `employees`, `departments` and `companies`, so every claimed task would
   end `FAILED` in the retry loop — the ADR's named failure mode, one layer below where T5a fixed it.
2. **`UsersAdminService.createUser`** (`:71`) inserts `user_preferences` for the **new** user's id and the
   `subject` policy compares it to `app.user_id`, which is the administrator's: the insert is refused
   (42501), so creating a user breaks where nobody would look for it.
3. **The id-addressed endpoints**, whose first read is an armed table and whose path names no company:
   `/api/stores/{id}` (`CompanyStoreService.getStoreById:147-149`), `/api/store-zones/{id}`,
   `/api/store-areas`, `/api/store-locations`, `/api/variants`, `/api/product-variants/search`
   (`ProductVariantService.searchVariants:451-457`), `/api/purchase-orders/**` and
   `/api/goods-receipts/**`. With no scope they answer 404 or an empty page for **everyone**.
4. **The administrator's company list and the header's company selector.** `CompanyService:112`'s
   unscoped `findAll` answers zero rows under the armed `companies` policy, and `CompanyServiceTest:374`
   pinned «admin sees every company» as intended behaviour.
5. **`StarterProductCategoryApplier.java:73-77`**, the `AFTER_COMMIT` listener that reads `companies` and
   writes `product_categories`: correct only on the `createCompany` path, whose `TenantScope.with` is
   still active, and empty on its `fallbackExecution` path.
6. **Two assertions that pin the unarmed state**: `TenantScopeFunctionsIntegrationTest:181` (the
   assertion returns exactly the fifteen names) and `:606/611` (`tenant_table` is empty right after the
   migration). They flip with the migration, not before it.

**What T5b changed, and why each piece is where it is.** `TenantScope` gained
`withSubject(String, Supplier)` — the one write whose subject is created inside the call — and the
save/restore both scoped helpers use is now one private `runWith`, because two copies of the reused-thread
rule are two places to get it wrong. `TenantScopeFilter` states, for a company-less path and an
authenticated caller: the single claim, no company when there is no claim, and a logged
`AccessDeniedException` **before** any scope when there are several. `CompanyService` lost its
administrator branch entirely — `isAdmin()` is no longer consulted for the list, which the new case pins
with `verify(never())`. `AccessProvisioningWorker.process` resolves the task's company **once, after the
claim**, and runs the whole body inside `TenantScope.with`: the employee read, the re-gate, the apply, the
`markApplied` and the failure branch. `UsersAdminService.createUser` lost its `@Transactional`, because the
subject does not exist when a boundary would be entered; the Keycloak user is created first and the single
insert states the id it received.

**The tenant-agnostic list is the one new closed list, and its failure direction is loud.**
`/api/profile`, `/api/countries`, `/api/measure-units`, `/api/payment-methods`, `/api/statuses`,
`/api/status-types`, `/api/seniority-levels`, `/api/activity-logs`, `/api/users-admin` and
`/api/companies`, matched segment-safely, are the paths whose reads touch no tenant table. A path missing
from it does not read nothing in silence: a multi-company caller gets a 403 an operator can see. That
direction is deliberate — the alternative (a list of *tenant* paths) would fail silently for exactly the
endpoint somebody forgot. The consequence to state in the pull request: a caller whose token carries two
companies can no longer use a company-less path that does read tenant tables, which is what the maintainer
chose over a silent empty page.

Evidence, as observed. **RED**, in five failures the writer produced before the change: `CompanyServiceTest`
2 cases (`NullPointerException` on the removed branch's unstubbed `findAll`), `UsersAdminServiceTest` 1
(`expected Scope[companyId=null, subject=kc-user-id-123] but was: null`),
`TenantScopeFilterIntegrationTest` 2 (`expected Scope[companyId=<uuid>…] but was Scope[companyId=null…]`
and `Status expected:<403> but was:<200>`), and `ProvisioningWorkerTenantScopeIntegrationTest` 1
(`expected Scope[companyId=<uuid>, subject=null] but was: null`). **GREEN**: `spotlessApply spotlessCheck
compileJava compileTestJava`, the three unit suites, the three tenant suites (27 tests) and the four
PRESERVE suites (120 tests, `*PurchaseOrder*`, `*TenantTaskResolver*`, `*CompanyTenantScope*`) all
`BUILD SUCCESSFUL`.

**The whole backend suite ran once, because this slice changes a filter that every request crosses:**
`./gradlew test` — **4 225 tests, 0 failed** — with `spotbugsMain` and `spotlessCheck` green and the
repository's eleven shell suites green (385 assertions). The focused suites above are the ones that pin
the rules; this is the one that answers "and nothing else moved".

**A pre-existing defect the new observation exposed, repaired in place.** The worker suite's employee spy
used `invocation.callRealMethod()` on a Spring Data proxy whose methods are final; Mockito left
`Cannot call abstract real method on java object!` as the run's `last_error`, so **the pass never reached
its later stages** and the suite was green because it only asserted the scope at the first read. The spy
now delegates to a real repository built from the same `EntityManager`, the employee fixture links no
Keycloak account so the in-memory provider creates one, and the new case asserts the scope is still the
task's company at `AccessProvisioningWorkerHandler.apply` **and** that the task reaches `APPLIED` — a pass
that never arrives proves nothing about the far end of it. The task service could not be the observation
point: it is `@Transactional`, so Spring hands out a CGLIB proxy with final methods and a `@MockitoSpyBean`
answer on it never runs.

**Handoff to T5c, in order.** The migration `V53` arming the sixteen tables; the two assertions in
`TenantScopeFunctionsIntegrationTest` flipped to the armed state (the list holds the sixteen rows, the
assertion returns nothing); and `CompanyRepository.findBySearchTerm` is now dead for the company page and
belongs to a cleanup, not to this slice. What T5c must not assume: the test container's pool is still a
superuser, so **no suite observes a policy** until T6 connects as `lifecontrol_app` — arming in T5c without
that is production enforcement nobody's tests can see.

The slice boundaries of T5b: three commits. `1242df82` is the delivery half — the five main files, and
within them the filter's claim-derived tenant with its closed list, the worker's whole-task scope, the
user-creation subject and the claim-only company list — `2b61e0ec` is the test half (five suites, 279
authored lines), and this commit carries the record. 783 authored lines with this entry, which the
harness measured past the ~400 review budget; the boundaries are recorded here for the same reason as L8,
L15 and L19, and the size is stated in the pull request rather than hidden. The file a reviewer should
read first is `TenantScopeFilter`: `companyOfTheClaims` and `TENANT_AGNOSTIC_PATHS` are the only place
where an answer is turned into a 403, a tenant, or no scope at all.

L21 — **T5c is the arming, and it was three files: the migration, one flipped suite and one new one.** No
Java main code changed, and that is the slice's own check: T5b had already made every path state its
tenant, so arming the tables was a migration and not a hunt for readers.

**What `V53` does.** Sixteen `SELECT apply_tenant_isolation(...)` calls and **not one hand-written
`CREATE POLICY`** — the property the ADR asks for, and what keeps "a new tenant table is a new list entry"
true instead of aspirational. The sixteen are S2's fifteen plus `companies`, which leads the file because
it is the one call that passes a key column: `apply_tenant_isolation('companies', 'tenant', 'id')`. The
header states the three things the calls cannot: why the root takes a key column (it has no `company_id`;
the ADR's original helper hardcoded one, which is why the helper gained `key_column` before acceptance) and
`user_preferences` takes a kind (`subject`, its key is a VARCHAR and it is read before a company is known);
and decision item 6 as a **rule for every migration after this one** — `FORCE` applies the policies to the
owner, `lifecontrol_owner` is the role Flyway runs as, so a later migration that `UPDATE`s or `DELETE`s
rows on an armed table affects **0 rows and reports success**; it calls `set_tenant_scope('<uuid>')` first,
or it runs in `lifecontrol_bootstrap`'s elevated connection and says why in its own file. `TRUNCATE` is
untouched, which is exactly why the `V14`/`V40` shape hides the trap.

**The index measurement, because the ADR's `## Consequences` makes `(company_id, …)` load-bearing and says
"the rest get one in their group's migration".** Measured against the migrations, not assumed: thirteen of
the sixteen already have the key column leading an index or a unique constraint, and three had nothing
usable. The three are not accidents — `product_suppliers` and `purchase_orders` got `company_id` in V43 as
a column carried by a composite FOREIGN KEY, and a referencing-side foreign key creates **no index at all**
(the index a foreign key needs is on the REFERENCED side); `product_attribute_profile_roots` has a unique
index on `COALESCE(company_id, '000…')`, which is an EXPRESSION index and cannot answer `company_id =
<uuid>`. `V53` adds exactly those three, `IF NOT EXISTS`, and nothing else.

| table | key column | covered by | added |
| --- | --- | --- | --- |
| `companies` | `id` | `companies_pkey` (V1:46) | no |
| `company_countries` | `company_id` | `idx_cc_company` (V1:86), `uq_company_countries_company_id` (V44:50) | no |
| `company_regions` | `company_id` | `uq_company_regions_company_id` (V44:56) | no |
| `company_zones` | `company_id` | `uq_company_zones_company_id` (V46:37) | no |
| `company_stores` | `company_id` | `uq_company_stores_company_id` (V48:44) | no |
| `product_categories` | `company_id` | `uq_product_categories_company_id` (V31:50), `idx_product_categories_company_id` (V31:66) | no |
| `products` | `company_id` | `uq_products_company_id` (V40:105), `uq_products_company_sku` (V40:120) | no |
| `product_variants` | `company_id` | `uq_product_variants_company_id` (V40:106) | no |
| `suppliers` | `company_id` | `uq_suppliers_company_id` (V41:49), `uq_suppliers_company_rfc` (V42:73) | no |
| `product_suppliers` | `company_id` | nothing: V1:216-217 index `product_id` and `supplier_id`, V43 adds composite FKs | **yes** |
| `purchase_orders` | `company_id` | nothing: V1:330-336 index six other columns, V43/V51 add composite FKs | **yes** |
| `departments` | `company_id` | `idx_departments_company_id` (V19:28), `uq_departments_company_*` (V19:25-26) | no |
| `employees` | `company_id` | `idx_employees_company_id` (V20:51), `uq_employees_company_*` (V20:45-46) | no |
| `product_attribute_profiles` | `company_id` | `idx_product_attribute_profiles_company_id` (V37:89) | no |
| `product_attribute_profile_roots` | `company_id` | only the `COALESCE(...)` expression index (V37:149) | **yes** |
| `user_preferences` | `keycloak_user_id` | `user_preferences_keycloak_user_id_key` (V1:368), `idx_up_keycloak_user` (V1:376) | no |

**The migration ends with the assertion the ADR's group 5 asks for**: a `DO $$ … $$` block that raises when
`SELECT count(*) FROM unprotected_tenant_tables()` is not zero, with the offending tables aggregated by
`string_agg` — "one or more tables are unprotected" is not a message an operator can act on. A table
forgotten here fails the migration that arms the others, which is the half a startup refusal (T3, `V54`)
cannot cover: by the time the application boots, the omission is applied and the backup is the only way
back.

**The two assertions T5b's handoff named, flipped rather than deleted**, in
`TenantScopeFunctionsIntegrationTest`. `unprotectedTenantTablesAreExactlyTheFifteenTheRecordNames` became
`unprotectedTenantTablesReturnsNothingOnceThePoliciesAreArmed`: the query returns **nothing**, and the same
case states the flip positively — `tenant_table`'s names contain all fifteen S2 names and are
`S2_TENANT_TABLES.size() + 1`, because `companies` is the sixteenth and is deliberately outside that
query's scope (it has no `company_id`). `theListIsEmptyRightAfterTheMigration` became
`theListHoldsExactlyTheSixteenArmedTables`, and it pins a **set of `table:kind:key` strings**, not a count:
a policy filtering the wrong column passes any count, and `apply_tenant_isolation` refuses exactly that. The
third family is the one the handoff called "every case that asserts the count is zero" — there were three
(`aFourthPolicyKindIsRefusedByTheCheckConstraint`, `aTableWithoutTheKeyColumnIsRefused`,
`anUnknownKindIsRefused`), and each now captures the count **before** the refused call and asserts it is
unchanged: "the refused row stored nothing" was true when the list was empty and is false now, while
"unchanged" says the same fact in both worlds. The probe-table cases are untouched — they roll back and are
still where the helper's three kinds are proved.

**The armed-state suite, and why it can only exist here.** `TenantPoliciesIntegrationTest` (PostgreSQL,
`@SpringBootTest`, extends `AbstractPostgresIntegrationTest`) **arms nothing**: the policies are the
migration's, so a forgotten table fails a suite instead of a request. Every behavioural case inserts its
fixtures as the container's superuser — which bypasses every policy by attribute, the same trap T1 removed
from delivery — then `SET LOCAL ROLE lifecontrol_app`, and rolls the whole transaction back in `@AfterEach`
because sixty-odd suites share this container. Seven cases, one per rule: (1) all sixteen carry
`relrowsecurity` and `relforcerowsecurity`, exactly one `p_tenant_<table>` policy, `cmd = ALL`, `qual =
with_check`, and the `tenant_table` row carries the expected kind and key — one loop, so a failure names the
table; (2) `company_countries` hides another tenant's row and its PK read is empty under A's scope, with the
superuser count as the control; (3) a cross-tenant insert answers **42501** through a savepoint, and the row
is read back as the superuser to prove nothing was stored; (4) `companies` by its own `id` — only A's row
visible, an insert naming B refused, and the case states T5b's consequence (the unscoped administrator read
is gone, so the list is one read per claim); (5) a global profile and A's override both visible to A, only
the global one to B; (6) `user_preferences` by subject, with a tenant scope alone answering **nothing**;
(7) invariant **T5**.

**T5 needed one measurement to be observable at all.** `FORCE` is a fact about the table's **owner**, and
in this container the tables are owned by `lifecontrol`, a superuser that bypasses RLS by attribute — so
"the same SELECT as `lifecontrol_owner` returns zero rows" was not true as written: `lifecontrol_owner` owns
nothing there and holds no grant, and its SELECT fails with `permission denied` rather than filtering. The
case therefore moves the ownership to `lifecontrol_owner` **inside its own rolled-back transaction**
(`GRANT CREATE ON SCHEMA public`, `ALTER TABLE company_countries OWNER TO lifecontrol_owner`, both
transactional), which is the only way a non-superuser can be the owner here, and then asserts the pair: the
owner reads A's row under A's scope (so the zero is the policy and not a privilege error) and reads **zero**
rows under B's scope. Without `FORCE` it would read A's row, and the role that runs every migration would
see every tenant.

Evidence, as observed. **RED first**, against the tree without `V53`: `./gradlew test --tests
'*TenantScopeFunctionsIntegrationTest' --tests '*TenantPoliciesIntegrationTest'` → **23 tests, 9 failed**,
and the failures are the ones the slice predicts — `all sixteen tables …` stops at `companies: Expecting
actual: [false, false] to contain exactly [true, true]` (the table has no policy at all), `the list holds
exactly the sixteen … : Expecting actual: [] to contain exactly in any order: ["companies:tenant:id", …]`,
`unprotected_tenant_tables() returns nothing …` with the fifteen names in `expected: []`, and the six
behavioural cases reading two tenants' rows where one is expected. **GREEN** with `V53` in place: the same
command → **23 tests, 0 failed** (16 + 7). Then `spotlessApply spotlessCheck spotbugsMain` **BUILD
SUCCESSFUL**, `./gradlew test --tests '*TenantScope*' --tests '*CompanyTenantScope*'
--tests '*TenantTaskResolver*' --tests '*ProvisioningWorkerTenantScope*'` → **44 tests, 0 failed**, and
`./gradlew test` over the whole backend → **4 232 tests in 1 019 classes, 0 failed, 0 skipped** (4 225
before this slice; the seven new ones are the armed-state suite), which is the number that matters because
the migration runs for every PostgreSQL suite in the shared container. The repository's eleven shell suites
→ **385 assertions, 0 failed**, which includes the record's own header guard.

The slice boundaries, for the same reason as L8/L12/L15/L19: `V53` and the two test files are the whole
change — 3 files, and the record. It splits into two commits: the migration (the delivery half, ~230
authored lines with its header) and the two suites with this entry. No commit is prepared here; the
transaction controller owns them.

**Handoff.** To **T3** (`V54`): `unprotected_tenant_tables()` is now the empty set on this tree, so the
startup refusal it wires can only pass — the reason the record's L9 ordered it last, and the reason its
suite must still prove the refusal by creating a `company_id` table it does not list. To **T6**: the test
container's pool still connects as the superuser `lifecontrol`, so **no suite observes a policy** —
`TenantPoliciesIntegrationTest` is the only class that switches roles, and until T6 points the pool at
`lifecontrol_app` the T1–T8 suite proves the schema and not the deployment. Two residues, both small: the
`V53` file is the only place a future tenant table is added to, so the list and the assertion cannot drift;
and `TenantScopeBoundaryIntegrationTest`'s cleanup comment still names the display name this slice renamed
("the list is empty right after the migration"), which is cosmetic and outside T5c's edit surface.

The two commits those boundaries name exist: `2fb94620` is `V53` with its header, and this commit carries
the two suites and this entry. Measured on the branch: 167 the migration, 573 the armed-state suite, 118
the flipped one (84 insertions, 34 deletions) and 132 this entry — 990 in the two commits, which the harness
measured at almost two and a half times the ~400 review budget; the pull request states the size. The file a
reviewer should read first is not the largest one: `TenantPoliciesIntegrationTest`'s case 7, because it is
the only place where `FORCE` — the invariant that keeps the owner honest — is observed at all, and it has
to transfer ownership inside its rolled-back transaction to do it.

L22 — **T3 is the wiring of an assertion that already existed, and there is no `V54`.** The task table gives
this slice a migration, because when the table was written the assertion was still going to be SQL; by the
time the slice ran, `unprotected_tenant_tables()` was a function T2 had shipped in `V52` and T5c had already
satisfied, so what was left was the process refusing to start. T3 wrote three files and no migration:
`TenantIsolationStartupCheck` (208 lines), `TenantIsolationStartupCheckIntegrationTest` (129) and
`TenantIsolationStartupCheckH2Test` (71). `V54` does not exist and stays free for the next migration that
needs a version; the record's row for T3 keeps its planning text for the same reason L20 keeps its own.

**What the guard is.** A `@Component` implementing `InitializingBean`, next to `TenantScope`,
`TenantScopeFilter` and `TenantScopeJpaTransactionManager`. `afterPropertiesSet` reads
`unprotected_tenant_tables()` through the pooled `DataSource` and throws `IllegalStateException`, naming
every table, when the result is not empty. The list of protected tables is **not** restated in Java: V52's
query is the definition of "unlisted", so the fact has one definition and no second one to forget. The
failure text carries the names, aggregated, one per line — a count sends the operator to `psql` for names
the process already read — and says what to do: `apply_tenant_isolation('<table>')` once per name, which
arms the table and records it in `tenant_table`, or, for a table that is deliberately not a tenant table,
record that decision in `tenant_table` and in this record. It fails and does not warn, because a warning
leaves the deployment serving every tenant's rows while looking healthy — the failure mode the ADR exists
to remove — and the only moment the omission is cheap to fix is before the process accepts a request.

**Why the hook orders after Flyway, and why that ordering is the requirement.** The bean takes the JPA
`EntityManagerFactory` as a constructor argument and never stores it: the dependency *is* the ordering.
Spring Boot makes `entityManagerFactory` depend on database initialization (`FlywayMigrationInitializer`),
so Spring cannot create this bean before the migrations of the schema it asserts, and the refusal happens
during context refresh — before the servlet container starts, so no request is ever served by a process
that has not passed the check. The two wrong answers a differently placed check gives are both silent: run
before Flyway, a fresh deployment either refuses over tables that do not exist yet or — worse, and this is
the one that would have shipped — reads `to_regprocedure` as `null` and passes; run only on the first
start, a restart passes a schema whose policies were never armed. A `SpringApplication` runner hook was
rejected for the second half of that choice: it runs after the port is bound.

**Why the probe asks the database, twice, and caches the answer.** `application-test.properties` makes H2
the default stack with Flyway disabled and sixty-odd suites boot there; H2 has neither
`unprotected_tenant_tables()` nor `to_regprocedure`, so the precise question alone throws a syntax error on
**every** one of those contexts — a build that breaks rather than a policy that fails. The probe therefore
asks two things of the pooled `DataSource`, once, and caches them in a `volatile Boolean` (the same shape
as `TenantScopeJpaTransactionManager.installsScope`, so the two components cannot disagree about which
stack they are on): the product name, which the sibling already asks, and then
`to_regprocedure('public.unprotected_tenant_tables()')`, which is the precise question and is *read*
rather than *called* — a probe that calls what it probes cannot tell "absent" from "failed", and a
PostgreSQL database without `V52` is answered "no assertion query" instead of being reported as broken.

**Evidence, as observed.** **RED**: `./gradlew compileTestJava` → `cannot find symbol: class
TenantIsolationStartupCheck`, 4 errors, `BUILD FAILED in 13s`. A component that does not exist cannot fail
behaviourally, so that is the honest shape of the RED for a new file, and the refusal itself is proved by
the second case below rather than by the missing symbol. The first GREEN attempt then failed *usefully*:
`./gradlew test --tests '*TenantIsolationStartupCheck*'` → 4 tests, 2 failed, the PostgreSQL context ending
`Caused by: org.postgresql.util.PSQLException: ERROR: column t.table_name does not exist` — which is also
the **ordering evidence**, because the check reached the assertion query at all: had it run before `V52`,
the probe would have answered `null` and the context would have booted green. The cause is that a `SETOF
regclass` function's output column is named after the function, so the read is
`select unprotected_tenant_tables::text`. A second, smaller failure followed (the control expected the two
probe tables in creation order; the query orders by `relname`) and the case now compares them as a set.
**GREEN**: the same command → `BUILD SUCCESSFUL`, 4 tests (2 PostgreSQL, 2 H2). `spotlessApply
spotlessCheck spotbugsMain` first **failed** on `VA_FORMAT_STRING_USES_NEWLINE` — the refusal was written
with `\n` in a format string — and the message now builds its breaks with `%n`; that was a real catch, not
noise, because the separators of an operator-facing message should not depend on the host. Then `BUILD
SUCCESSFUL`.

**The suites that boot the PostgreSQL context, run because the hook is in their startup path.**
`./gradlew test --tests '*TenantIsolation*' --tests '*TenantScope*' --tests '*TenantPolicies*'` → **11
classes, 53 tests, 0 failed**. Then the whole backend suite, since this changes the startup path of every
context: `./gradlew test` → **exit 0, 4 236 tests in 1 021 classes, 0 failed, 0 skipped, `BUILD SUCCESSFUL
in 4m 19s`** (4 232 in 1 019 before this slice; the four new ones are the two suites). The repository's
eleven shell suites → **385 assertions, 0 failed**, the record's own header guard among them.

**What each case asserts, and why only it can.** (1) *PostgreSQL passes*: the suite asserts
`check.appliesToThisDatabase()` is `true` — the answer the startup path itself computed and cached — and
re-runs `afterPropertiesSet` without an exception. The absence of a failure is also what a check that never
ran produces, so the passing half is stated instead of inferred, and a `true` is only possible if the check
ran after `V52`; every sibling PostgreSQL suite is an implicit copy of this case, which is why it is
written down once. (2) *the refusing half*, and this is the case that would have caught the six readers a
slice too late: two `company_id` tables created inside a transaction that is rolled back — because
sixty-odd suites share the container — the control read of the assertion query on that same connection
first, then `assertNoUnprotectedTenantTable(connection)`, asserted to throw, to name **both** tables and to
name `apply_tenant_isolation`; and after the rollback the same check on the same connection passes, so the
refusal was the table and the container is left as it was found. It takes the connection because
uncommitted DDL is invisible to any other one, which is exactly why the production path opens its own. (3)
*H2 is a no-op*: `appliesToThisDatabase()` is `false` and `afterPropertiesSet` returns — plus a second case
pinning that H2 cannot answer the precise question at all, which is the measured reason the probe has two
halves and not one.

**What this does not cover, said plainly.** The check is per process, not per statement: a table created
after startup is not caught until the next start, and that is T2's `DO $$` block's territory — it refuses
the forgotten table at the migration that arms the others, which is the half a restart cannot reach. And
the test container's pool still connects as the superuser `lifecontrol`, so nothing in this slice observes
a policy; `TenantPoliciesIntegrationTest` remains the only class that switches roles.

**Commit boundaries**, for the same reason as L8/L15/L19/L21: `TenantIsolationStartupCheck.java` is the
delivery half (208 authored lines) and the two suites with this entry are the test half (200 before this
entry). No commit is prepared here: the transaction controller owns them, and the pull request states the
total the harness measured, which is past the ~400 review budget. The file a reviewer should read first is
the component's javadoc, because
the three decisions this slice makes (the hook, the probe, the refusal) are each a rejected alternative
that is not visible in the code: a runner hook, a dialect read, and a warning.

**Handoff to T6**, which is what is left of the unit: the T1–T8 suite on Testcontainers with the pool
connected as `lifecontrol_app` — until then no suite observes a policy, and this guard is the only thing in
the tree that would notice a table nobody armed — the PR chain with its evidence, `Closes #696`, and the
two residues T5c named: the `V53` list as the one place a tenant table is added, and
`TenantScopeBoundaryIntegrationTest`'s stale comment naming the renamed display name. What T6 must not
assume: the guard's own verdict is cached per bean, so a suite that creates a table and expects the
**startup** path to refuse it will not see it — the refusal is observed by calling
`assertNoUnprotectedTenantTable` on the connection that created it, which is what the case does and why.

**The native review found one critical defect in the first version of this guard, and it was right.**
`afterPropertiesSet` held a pooled connection while the probe asked the pool for a **second** one, so a
deployment with `maximum-pool-size=1` blocked until the pool timeout and then failed a startup whose schema
was clean — a guard that breaks a healthy deployment, which is worse than the warning it replaced. The
probe now answers on the connection the check already holds, and
`TenantIsolationStartupCheckH2Test#theCheckAsksThePoolForOneConnectionOnly` pins it by counting the
requests: one, where the old shape made two. RED measured by reverting that one call: 3 tests, 1 failed,
`expected: 1 but was: 2`.

L23 — **T6a is the fixtures, not the call sites: 64 suites now state a company's tenant the way production
does.** Measured with the Testcontainers pool connected as `lifecontrol_app` — the role the sixteen
ADR-0005 policies apply to — **556 of 4 237 backend tests failed**. The failures group like this:

| Group | Failures | Cause | Slice |
| --- | --- | --- | --- |
| A fixture inserts a `companies` row without stating a tenant | **537** | `companies` is isolated by `id = current_setting('app.company_id', true)` for both `USING` and `WITH CHECK`, so PostgreSQL refuses the insert with SQLSTATE 42501 | T6a |
| …of those, with **no production stack frame** | **535** | production already does it right in `CompanyService.createCompany`; only the fixture did not | T6a |
| …of those, **raw `JdbcTemplate`** rather than JPA | **46–48** | the same missing tenant, written as `INSERT INTO companies` | T6a |
| Machinery suites that switch roles, connections and policies on purpose | **19** (556 − 537) | the tests own the connection and the role; the pool switch is what changes their premise | T6b |

**Why the fixtures and not the call sites.** The 150 call-site checks are authorization and stay exactly
as they are (S1, decision item 5). A call site cannot fix an insert: the refusal happens before any
controller or service method the fixture calls, and in 535 of the 537 failures there was no production
frame to blame at all. The production path already generates the identity in memory, states it with
`TenantScope.with(id, …)` and drives the transaction itself (`CompanyService.createCompany`), because the
boundary hook reads the scope in `doBegin`, i.e. before a `@Transactional` method body runs. The tests
were the only writers that did the same work in the wrong order, and a fixture is exactly what a test
teaches.

**The helper.** `support/TenantFixtures` has one rule and four entry points. The rule, stated in its
javadoc with the measurement above: *a company's tenant is its own id, so a fixture that persists one must
state it before the insert, exactly as `CompanyService.createCompany` does.* The entry points are
`persist(CompanyRepository, Company)`, `persistAndFlush(CompanyRepository, Company)`,
`persist(EntityManager, Company)` and the two `insertCompany(JdbcTemplate, …)` overloads (generated id, and
a caller-chosen id for the fixtures that mirror a migration). Each assigns the id **in memory** when the
caller stated none — the entity's `@PrePersist` fallback runs inside the transaction, one boundary too late
for the scope — and then wraps the save or the raw `INSERT` in `TenantScope.with(id, …)`. There is
deliberately **no test-only bypass**: no `SET LOCAL ROLE`, no `ALTER TABLE … NO FORCE`, no wider policy.
A fixture that states its tenant is the model the tests should teach, and the pool still connects as the
container's superuser, so the stated scope is inert until T6b.

**What was adopted.** **64 top-level classes and 73 fixture lines**, from these greps over
`src/test/java`: `companyRepository.save(`, `companyRepository.saveAndFlush(`, `INSERT INTO companies`,
`Company.builder()`, `em.persist(company)` / `entityManager.persist(company)` and
`.save(Company.builder(`. The sweep after the batches found no DB-backed class with `Company.builder()`
left unadopted; the only remaining repository saves are Mockito stubs in `CompanyServiceTest`, and the only
remaining raw inserts are `TenantPoliciesIntegrationTest`, `CompanyTenantScopeIntegrationTest` and
`TenantTaskResolverIntegrationTest`, which switch roles and connections on purpose and belong to T6b's
machinery group. The three deciders the brief named are among the adopted:
`GoodsReceiptIntegrationTest#seedCompanyHierarchy`, `SupplierSchemaMigrationIntegrationTest#insertCompany`
(raw), `CompanyCountryScopedReadHttpIntegrationTest#seed` (EntityManager) and
`SalesOrderIntegrationTest:311` / `EmployeeScopeAssignmentHttpIntegrationTest:210`. Production code is
untouched, including every service that creates its own company, and so are
`test-container-roles.sql`, `AbstractPostgresIntegrationTest`'s datasource properties and the migrations.

**Evidence, as observed.** RED: `./gradlew compileTestJava` → `cannot find symbol: variable
TenantFixtures`, 3 errors, `BUILD FAILED in 3s` — the honest RED for a new helper, because the contract it
has to satisfy is the suite that will call it. GREEN: `./gradlew test --tests '*TenantFixturesTest'` →
`BUILD SUCCESSFUL`, 3 tests: the id is assigned before the save and the scope carries it; a caller-stated
id is kept; the raw insert states the generated id. Three cases, so the whole-suite count moves by exactly
three (below). Batches, each run after its own edits: store
(`--tests '*CompanyStore*' --tests '*StoreArea*' --tests '*StoreLocation*' --tests '*StoreZone*'
--tests '*StoreOptimisticLocking*' --tests '*StoreHierarchy*'`) `BUILD SUCCESSFUL in 1m 6s`; hr
(`--tests '*hr.*'`) `BUILD SUCCESSFUL in 1m 11s`; product (`--tests '*product.*'`)
`BUILD SUCCESSFUL in 47s`; provisioning (`--tests '*provisioning.*'`) `BUILD SUCCESSFUL in 43s`;
purchaseorder/salesorder/scheduling/goodsreceipt/inventory `BUILD SUCCESSFUL in 1m 12s`; the final five
(supplier, company country scope, provisioning worker scope, tenant filter) `BUILD SUCCESSFUL in 45s`.
Then the whole suite: `./gradlew test` → **exit 0, 4 240 tests in 1 022 classes, 0 failed, 0 skipped,
BUILD SUCCESSFUL in 4m 36s**. The baseline was 4 237 in 1 021 classes; the +3 tests are this entry's
helper suite and the +1 class is its file. `./gradlew spotlessApply spotlessCheck spotbugsMain` → exit 0,
`BUILD SUCCESSFUL in 26s`. The repository's eleven shell suites → **385 assertions, 0 failed**, every
suite returning 0 and no `FAILED` line.

**Deviations and the one risk this slice cannot close.** The helper states the tenant the way production
does, but for a **raw `JdbcTemplate`** statement that statement still opens its own pooled connection and
never passes through `TenantScopeJpaTransactionManager.doBegin`, so a stated `TenantScope` is not installed
on that connection — inert today, and still insufficient if T6b only swaps the credential. The 46–48 raw
fixtures therefore also need a connection-level installation (or the raw helper driving a
`TransactionTemplate` whose `doBegin` runs inside the scope), and that is T6b's to choose and observe; this
slice could not observe it without switching the pool. The second, smaller deviation is coverage: our
greps found **64 classes / 73 lines** where the measurement found 63 / ~71, because the sweep also caught
`CompanyStoreScopeProjectionTest`, `CompanyStoreScopeAgreementTest` and the two `saveAndFlush` attribute
suites; the measurement's list was a starting point, and the run was the authority.

**Handoff to T6b** — the pool switch: point `AbstractPostgresIntegrationTest`'s datasource at
`lifecontrol_app`, grant the container user `CREATE` (and the sequence/default-privilege half the role
script carries) so Flyway's `V52` grants and the container's own setup agree, and take the **19 machinery
failures** — `TenantPoliciesIntegrationTest`, `CompanyTenantScopeIntegrationTest`,
`TenantTaskResolverIntegrationTest`, `TenantScopeBoundaryIntegrationTest` and
`TenantScopeFunctionsIntegrationTest` switch role or own a connection on purpose, so their premise changes
with the credential. Re-measure the 556: the 535 fixture failures should be gone, and the raw residue is
the item named above. **Handoff to T6c** — the production credential: the wrapper's `DATABASE_APP_PASSWORD`
slice, the delivery path that makes `lifecontrol_app` the pool's only principal, and `Closes #696` in the
PR. What T6a does not claim: no policy was observed by this slice (the superuser still bypasses them), and
the fixtures are ready for the switch rather than proof of it.

L24 — **T6b's premise was falsified by its own first run, and the slice is now four.** The handoff in L23
predicted nineteen failures once the pool logged in as `lifecontrol_app`. Measured: **545 of 4 240 tests
across 133 classes**, and the four causes that carry 96% of them are none of the nineteen:

| # | failures | cause |
| --- | --- | --- |
| A | 376 | a find-or-create seed reads `companies` by its natural key with no tenant stated, sees nothing — the previous test in the same class already inserted that row — and inserts it again: `duplicate key value violates unique constraint "companies_company_key_key"` |
| B | 26+ | `@Transactional` integration tests: Spring's test transaction begins **before** `@BeforeEach`, so `doBegin` already ran and the scope the fixture states afterwards is never installed |
| C | ~83 | per-class seeds outside `TenantFixtures`: raw inserts of suppliers, products, categories, employees, departments and attribute profiles, and services called directly with no scope |
| D | 32 | fallout of A: the parent company was never persisted, so the child fails with a transient reference |

None of the four is a production defect, and that is the useful finding: the application code is already
right, and what T6a taught the company fixtures the rest of the suite still has to learn.

**The split, decided by the maintainer**, verbatim: «A: cuatro slices, switch tras una property» and «Id
determinístico desde la key». The pool switch therefore lands **behind an environment variable**,
`TEST_POOL_APP_ROLE=true ./gradlew test`, off by default: an environment variable and not a system property
because Gradle forwards neither to a forked test JVM except by inheriting the environment, and no build file
should change for a switch scheduled to disappear. Each slice reports the number the other way, and the last
one makes the application role the default and removes the switch.

> **The switch is gone (T6b-4, L29).** `TEST_POOL_APP_ROLE` no longer exists and no invocation below needs it:
> the pool logs in as `lifecontrol_app` in every run, so `./gradlew cleanTest test` with no environment
> variable is the acceptance. The paragraphs around this note stay as they were written, because the reason
> the switch existed is evidence.

The find-or-create rule the 376 failures ask for, recorded before it is implemented: **a seed that resolves a
company by its natural key derives the id from that key** (`UUID.nameUUIDFromBytes(companyKey.getBytes(UTF_8))`,
or the same value the fixture writes) and states it **before the read**, because a company's tenant is its own
id. It is a test-only convention; production keeps generating its ids.

What this slice lands, and what it deliberately does not. `AbstractPostgresIntegrationTest` takes the pool's
credential from `TEST_POOL_APP_ROLE` and always gives Flyway the container's superuser through
`spring.flyway.url/user/password` — measured in the previous round as required and honoured, where Boot's own
Flyway properties override the datasource for Flyway alone. `test-container-roles.sql` gains the role's
password and a `GRANT CREATE ON SCHEMA public`, **container only**: three suites own the DDL they assert on
and would fail on a fixture limit rather than a policy finding, and delivery must not grant it, because a role
that can create tables can build structures no row of `tenant_table` knows about — precisely what the startup
assertion exists to refuse. The wiring carries no assertion that the gate is met; it carries the measurement
that says it is not yet.

Evidence as observed: `./gradlew test` (default, superuser pool) green — **4 240 tests, 0 failed** — and
`TEST_POOL_APP_ROLE=true ./gradlew test` — **4 240 tests, 545 failed** — which is the baseline the next four
slices have to move. `spotlessCheck`, `spotbugsMain` and the eleven shell suites are green. The startup path
was confirmed on the application role in the previous round: the guard runs on the pool and
`unprotected_tenant_tables()` answers it with nothing, and `permission denied for schema public` appears zero
times with the container grant in place.

Handoff: **T6b-1** the 376 find-or-create seeds with the derived id; **T6b-2** the raw fixtures through a
`TransactionTemplate` (the invariant `TenantFixtures` documents but cannot enforce alone) and the ~83
per-class seeds; **T6b-3** the `@Transactional` suites, where the fix is a `REQUIRES_NEW` fixture write
because removing the annotation would cost those classes their rollback; **T6b-4** the machinery suites
(`TenantScopeBoundaryIntegrationTest`'s control counts assume a superuser pool, `CompanyTenantScopeIntegrationTest`
re-arms a table the migration already armed, and one constraint assertion now meets the policy first), then the
default flips and S5 closes. **T6c** stays as L23 left it: `DATABASE_USERNAME` still names the owner, and
`Closes #696` waits for the whole chain.

**And the gate itself needed one line.** The first measurement of this slice died twice with
`java.lang.OutOfMemoryError` — on CI for PR #711 before any assertion, and locally here, where the run stopped
after 1 903 of the 4 240 tests — because the `test` task never set `maxHeapSize` and the fork ran on Gradle's
default 512 MB. The suite caches one Spring context and one Hikari pool per distinct `@SpringBootTest`
configuration, so the heap it needs grows with the number of configurations and not with the code under test —
the same reason `AbstractPostgresIntegrationTest` raises the server's connection ceiling. 1 GB is the smallest
value that carried the full run; a gate that OOMs one run in three cannot measure four slices.

**One more measurement, and it is a pre-existing flake rather than this slice's.** The first full default run
after the heap bump reported `TenantScopeH2NoOpTest` failing with an H2 syntax error on
`select count(*) from companies` — the table was gone. The class alone is green, and a second full run executed
the whole suite again: **4 240 tests, 0 failed**. So the failure is order-dependent, and the mechanism is
visible in the configuration rather than guessed: every H2 context shares `jdbc:h2:mem:testdb` while
`ddl-auto=create-drop` drops the schema when **any** context closes, so a cached context that outlives another
context's shutdown loses its tables. It is not this unit's to fix — the honest fix is one database per context
(`spring.datasource.generate-unique-name` with no explicit URL) and it deserves its own measurement — but it is
recorded here for two reasons: a gate that fails by ordering cannot measure four slices, and this slice exists
to make the gate measurable. The numbers that belong to T6b-0 stand as observed: default **4 240 / 0**, app role
**4 240 / 545**, with zero `OutOfMemoryError` after the heap bump.

L25 — **T6b-1 lands the derived id, kills the 376 duplicate-key cause, and the suite count does not move: the 376
were a mask, not the cause.** The rule is the maintainer's (L24, verbatim «Id determinístico desde la key»),
implemented once in `support/TenantFixtures`: a seed that resolves a company by its natural key derives the
company's id from that key, states that id as the tenant scope **before** the read, and inserts at the same id
when the read finds nothing. `TenantFixtures.derivedId(companyKey)` is
`UUID.nameUUIDFromBytes(companyKey.getBytes(UTF_8))` — name-based v3 (MD5), stable across JVMs and runs, pinned
by a literal in `TenantFixturesTest` so that changing it is a visible change to every seeded id — and it is the
only derivation: `findOrCreate(CompanyRepository, String, String, String)`, `findOrCreate(repository, Company
prototype)` and `findOrCreateAndFlush(...)` all go through it, and the raw `insertCompany(jdbcTemplate, key, …)`
overload now derives too instead of generating a random id. Production is untouched: `CompanyService#createCompany`
keeps stating the identity of the company a real caller created, because nothing outside the tests needs two
writers of one key to agree.

**Why a derivation and not a convenience, said in the helper's javadoc.** A company's tenant is its own id, so the
derived id can be stated before the read and the read then agrees with the policy that filters it. Two seeds agree
only if **every** creator of that key derives it the same way; a class that wrote `TEST-COMPANY` with a random id
and another that derives the id from `TEST-COMPANY` would still collide, with a different id — the same bug in a
new message. That is why the derivation lives in one named method instead of at the call sites.

**The agreement check, and it is the substance of the slice.** Four greps over `src/test/java`
(`findByCompanyKey`, `findByCompanyKeyIgnoreCase`, `orElseGet`, `existsByCompanyKey`) found **50 find-or-create
sites in 44 files** and **51 `findByCompanyKey` calls, one of which is a read-only cleanup** in
`ProductAttributeControllerTest:106`; there are **zero** in `src/main`. All 50 were adopted: 48 mechanically
rewritten to the `(key, name, rfc)` overload and 2 (`EmployeeCrudIntegrationTest`, which also sets
`emailDomain`) to the prototype overload, so the `find()` in the helper is the only way any suite reads a company
by key. Then a value-based audit (a throwaway script, not committed) resolved every key — literals, `static final
String` constants, and the 8 keys that reach the helper through a parameterized `findOrCreateCompany(key, …)` /
`companyId(key, …)` helper — to **42 distinct keys**, and listed every occurrence of each of those 42 keys in the
whole test tree. Result: every write of every one of the 42 keys is inside a `TenantFixtures.findOrCreate*` call.
The only writers left outside it are `ProductVariantServiceTest:215` and `SalesOrderServiceTest:168`, which build
`Company.builder().id(UUID.randomUUID()).companyKey("TEST-KEY")` in memory under `@ExtendWith(MockitoExtension)`
and never touch a database, plus `SupplierCompanyScopeHttpIntegrationTest#insertCompany`, which writes keys of its
own. No migration, no `src/test/resources` fixture and no HTTP-body `companyKey` mentions any of the 42. That
closes the agreement requirement **for the keys the suite seeds today**; the next key a suite adds is the next
audit, which is why the rule is in the helper's javadoc rather than in a comment at the call site.

**Evidence, as observed.** Tests first, with the one honest exception: the new `TenantFixturesTest` cases (6 new,
`derivedId` purity + pinned value + null refusal, the scope stated at the read **and** at the save, reuse of the
found row without a second insert, the prototype's columns kept while its random id is replaced, and the flushing
overload) were written **after** the helper, so no RED run exists for them — the honest RED for this slice is the
app-role suite itself, whose 376 duplicate-key failures I re-measured on the base commit before writing anything.
`./gradlew test --tests '*TenantFixturesTest'` → `BUILD SUCCESSFUL`, 9 tests. `./gradlew spotlessApply
spotlessCheck spotbugsMain` → exit 0, `BUILD SUCCESSFUL in 18s`.

**The two measurements, and they are the deliverable.** Default (superuser pool): `./gradlew test` → **exit 0,
4 246 tests in 1 022 classes, 0 failed, 0 skipped** (4 240 + the 6 new fixture tests). Application role:
`TEST_POOL_APP_ROLE=true ./gradlew cleanTest test` → **exit 1, 4 246 tests, 545 failed** — the same 545 as the
baseline, and the parent's expectation of ~169 is therefore **falsified by the run**. What did move is the
composition, and to prove it I re-measured the baseline instead of trusting the brief: `git archive a36b14b2`,
`TEST_POOL_APP_ROLE=true ./gradlew test` in that scratch tree → exit 1, **4 240 tests, 545 failed**, buckets
`duplicate-key` **376**, `unsaved-transient` 32, `rls-42501` 66, raw `INSERT INTO companies` 46, other raw inserts
13, machinery 8, other 4.

| bucket | base `a36b14b2` | after T6b-1 |
| --- | --- | --- |
| `duplicate key … companies_company_key_key` | **376** | **5** |
| Hibernate unsaved-transient probe on a detached parent | 32 | **321** |
| `rls-42501` (JPA insert into `companies` with no scope) | 66 | 66 |
| raw `INSERT INTO companies` (`TenantFixtures.insertCompany`) | 46 | 46 |
| raw `INSERT INTO` employees / categories / departments / products / profiles | 13 | 62 |
| unscoped read returned nothing (`NoSuchElementException`) | 2 | 21 |
| unscoped company lookup inside a service (`CompanyNotFound`) | 1 | 15 |
| machinery / assertion | 8 | 8 |
| other | 4 | 1 |

Test method by test method: **0 became green, 0 newly failed, and 371 changed bucket** — `duplicate-key` →
`unsaved-transient` 289, → raw employees 21, → `NoSuchElement` 19, → `CompanyNotFound` 14, → raw categories 13,
→ raw departments 10, → raw products 4, → raw profiles 1. **The five `duplicate-key` that remain are not
find-or-create**: `AccessProvisioningWorkerApplyIntegrationTest`, `AccessProvisioningEnqueueRaceIntegrationTest`
and `AccessProvisioningProducerTransactionIntegrationTest` insert unconditionally with `TenantFixtures.persist`
and rely on a `deleteOwnFixtures()` cleanup whose `deleteAll()` runs with no scope stated, so the row survives the
cleanup and the next method's insert collides.

**Where the 376 came from, and why fixing them moved nothing.** They were the **first** failure of a chain, not a
failure of their own: with the company seed refused, no suite ever reached the statement after it. Once the seed
finds its row, every one of those 371 tests runs one statement further and meets the same missing-scope rule one
level down — and the largest single form of it is Hibernate's own existence probe. `CompanyCountry.save(...)`
(295 of 321), `ProductCategory` (21), `Department` (4) and `Employee` (1) reference a **detached** `Company`;
Hibernate 7.4's `ForeignKeys.getEntityIdentifierIfNotUnsaved` → `AbstractEntityPersister.isTransient` finds no
`unsaved-value` strategy able to answer for an application-assigned UUID and falls through to
`getDatabaseSnapshot`, i.e. a `select … from companies where id = ?` issued **with no `app.company_id` stated**.
`companies`' policy hides the row, Hibernate concludes "unsaved transient instance", and the child insert aborts
with `TransientPropertyValueException`. The 32 transient failures the baseline already had are that same probe;
the difference is that 289 more tests now reach it. Which is the useful finding of this slice: the child seeds
need the scope as much as the company seed did, and no amount of care inside the company helper can state a scope
on a statement the caller owns.

**Deviations.** (1) The advertised number did not move — reported instead of chased, per the brief. (2) One adopted
class is also group B: `StarterProductCategoryMigrationIntegrationTest` is `@Transactional`, so its derived read now
runs inside Spring's test transaction, whose `doBegin` already happened before `@BeforeEach`; it stays red until
T6b-3 and its adoption is correct but unobservable. (3) Beyond the letter of the brief, the raw
`insertCompany(jdbcTemplate, key, companyName, rfc)` overload now derives the id too, because a raw fixture that
inserted a key at a random id would be the second creator the agreement requirement forbids — the explicit-id
overload the migration mirrors keep is untouched. (4) `TenantFixturesTest` grew from 3 to 9 tests, which is the
whole of the 4 240 → 4 246 delta.

**Handoff to T6b-2 — and its size is now measured, not estimated.** The next slice is the scope around the **child**
seeds and their raw statements: 321 unsaved-transient failures across **92 classes** (the read of `companies` that
the probe issues has to see the parent), 46 raw `INSERT INTO companies` where `TenantFixtures.insertCompany` states a
scope on a connection that never installs it, 62 raw `INSERT INTO` employees/categories/departments/products/profiles,
21 `NoSuchElementException` from unscoped reads and 15 `CompanyNotFoundException` from `EmployeeService#resolveCompany`
reading `companies` with no scope. The cheapest shape, to be measured before it is trusted: wrap each
`seedXHierarchy()` body in `TenantScope.with(company.getId(), …)` — or add the scoped child-save the raw helper
documents it cannot do alone — and re-measure the 545, since the mask has now been removed and this slice's numbers
are the real starting line. **T6b-3** is the 8 `@Transactional` top-level classes carrying 57 failures, whose fix is
the `REQUIRES_NEW` fixture write L24 named. **T6b-4** is the 8 machinery/assertion failures
(`TenantScopeBoundaryIntegrationTest`, `CompanyTenantScopeIntegrationTest`, `TenantScopeFunctionsIntegrationTest`,
`TenantTaskResolverIntegrationTest`, `StarterProductCategoryCompanyCreationIntegrationTest`). **T6c** is unchanged.
What T6b-1 does not claim: no test became green, the application role is still not the default, and the 42-key
agreement holds for the keys that exist today.

L26 — **T6b-2 is the boundary, and its first shape broke the mode that was already green.** The baseline was
measured before a line was written: **4 246 tests, 545 failed** with `TEST_POOL_APP_ROLE=true`, default mode
green. Grouped by root cause rather than by class, the 545 were **five** causes wearing many names — 320
`TransientPropertyValueException` (a child entity referencing a `Company` whose row the flush's unsaved-value
probe cannot see), 163 `42501` on an armed table, 36 `NoSuchElementException`/`CompanyNotFoundException`, 26
`@Transactional` suites whose transaction began before the fixture stated a scope, and 19 machinery cases.
Every one of them reduces to one fact: **a scope declared with no transaction installs nothing** —
`set_config(..., true)` is `SET LOCAL`, and a `JdbcTemplate` statement that borrows its own pooled connection
opens no transaction to put it in.

**The refutation, and it came from the mode that was green.** The first shape put a `REQUIRES_NEW` boundary
inside *every* fixture entry point, and the default run broke at once: `CompanyCountryScopedReadHttpIntegrationTest`
failed with `companies_company_key_key` because committing a fixture inside a `@Transactional` suite moves the
row past the rollback that was cleaning it up. No reviewer would have caught that by reading; the two-mode run
did. The corrected rule is the one the tree now states, and the distinction is measured rather than stylistic:

- **A repository entry point already has a boundary.** `persist(CompanyRepository, …)`,
  `persistAndFlush(…)` and both `findOrCreate` shapes keep `TenantScope.with(id, …)` and no template: Spring
  Data's own `@Transactional` runs `doBegin`, and it runs it inside the scope the helper stated **before** the
  call — which is exactly what T6a's 535 fixture failures needed and what the superuser pool had been hiding.
  Wrapping it again would have committed the row and bought nothing.
- **A raw entry point has none.** `insertCompany(JdbcTemplate, …)` and the new
  `TenantFixtures.inTenant(UUID, Supplier/Runnable)` open a `REQUIRES_NEW` transaction whose `doBegin` installs
  the scope; inside it the `JdbcTemplate` reuses the transaction's connection, which is the only way
  `SET LOCAL` reaches the statement.

**The binding, and the two holes it had.** The manager is bound once per PostgreSQL suite, because threading a
`PlatformTransactionManager` through sixty-five call sites would be sixty-five edits to pass a constant. Two
defects came out of that, both measured: (1) a `@SpringBootTest`-less pair — `EmployeeStoreScopeBackfillMigrationIntegrationTest`
and `ProductAttributeUpgradeOnPopulatedDbIntegrationTest`, which migrate their own superuser scratch database —
extends the base for its container and never receives a manager, so the base now binds only what it has and
those two bind a `DataSourceTransactionManager` over their own `DataSource`; (2) the binding is a JVM-wide
static while Spring **caches** a context, so an autowired method runs at creation and not on reuse —
`TenantFixturesTest` unbinds on purpose, which unbound every later suite whose context was already built
(**4 failures, order-dependent**). The base class now re-binds in a `@BeforeEach`, which runs before every
subclass's own. A raw write with nothing bound **refuses** instead of falling back: a silent fallback inserts
with no scope and turns a fixture mistake into a policy finding.

**What was adopted** (second commit of the slice, eleven suites). Every raw `JdbcTemplate` statement on one of
the sixteen armed tables runs inside `inTenant(<the row's company>, …)`: the writes, the `COUNT(*)` read-backs
the assertions depend on, and the `@AfterEach` cleanups — an unscoped `DELETE` on an armed table deletes **zero
rows without an error**, which is the failure mode that looked like a duplicate key two classes later. The
legible shape is one boundary per per-class seed helper (`insertSupplier`, `insertCategory`, `insertStore`),
not one per statement. `SupplierSchemaMigrationIntegrationTest` and `StoreHierarchy*` now track
`InsertedRow(companyId, id)` so their cleanups know each row's tenant; `ProductCategorySchemaMigrationIntegrationTest`
resets per tenant; the `'shared'` catalog rows outside a company were deliberately **left alone**, because a NULL
owner is visible without a scope (V53's `shared` kind) and wrapping them would assert the opposite of the policy.

**One defect the cross-check caught, and it is the kind that only a second mode finds.** The first version of
the cross-tenant count helpers summed one *unfiltered* `COUNT(*)` per tenant. On the app-role pool that reads
each tenant's rows and happens to be right; on the superuser pool each count sees everything and the sum
double-counts — **4 failures in default mode**. Fixed by naming the tenant in the predicate so one statement
answers correctly on both pools, which is also the shape that reads like the SQL it is.

**Evidence, as observed.** Helper suite: `TenantFixturesTest` **9 → 14** cases, and the RED for them was the
honest one for a new helper — `cannot find symbol` ×4, `BUILD FAILED in 10s`. Adoption, per suite, app role
before → after: `StoreHierarchyCompanyScopeSchemaMigration` 18/18 → **0/18**,
`ProductCategorySchemaMigration` 11/16 → **0/16**, `StarterProductCategoryApplier` 4/5 → **0/5**,
`ProductAttributeWritePath` 10/10 → **0/10**, `ProductAttributeSchemaMigration` 2/21 → **0/21**,
`ProductSupplierCompanyScope` 3/3 → **0/3**, `SupplierSchemaMigration` 15/15 → **1/15**,
`ProductCatalogCompanyScopeSchema` 7/9 → **2/9**. Whole suite, `spotlessApply` clean:
**default `4251 tests, 0 failed`, exit 0**; **app role `4251 tests, 478 failed`, exit 1** — **545 → 478**, the
+5 tests being the new helper cases.

**Residuals, each with its owner and no euphemism.** 321 `TransientPropertyValueException` (unchanged) and 34
`NoSuchElementException`/`CompanyNotFoundException` are the child seeds whose probe and reads still run outside a
boundary — **T6b-2b**. 105 `PSQLException` are almost all the `@Transactional` suites (**T6b-3**, where the
boundary exists and cannot be retro-fitted with a scope) plus the machinery that owns its connections
(**T6b-4**). 18 assertions are machinery. **One finding is new and belongs to no earlier slice**: under the app
role an `INSERT` that omits `company_id` is refused by the **policy** (`42501`) before the `NOT NULL` constraint
fires, so four assertions that read "not-null" now read the policy's message. That is the order ADR-0005 chose —
the tenant is checked first — so the assertion has to name the policy's refusal when the pool is the application
role; it is deferred to **T6b-2b** with that reason rather than patched to accept any exception.

What this slice does not claim: **S5 is not met**, the application role is still not the default, and 478 tests
still fail without it. The gate moved by 67 and it is now measurable per cause instead of per class.

**The slice's delivery, stated rather than left to be discovered.** Two work-unit commits, so a reviewer can
read them apart: the boundary (349 authored lines: `TenantFixtures`, its suite, the base class and the two
context-less bindings) and the adoption (1 183: eleven suites). One pull request for the slice, as this
feature's chain has done since T1, with the size named here instead of discovered in the diff: the review
budget is per slice, not per commit, and the adoption half is one mechanical rule applied eleven times —
read one class of each shape (`SupplierSchemaMigrationIntegrationTest` for the `InsertedRow` cleanup,
`ProductCategorySchemaMigrationIntegrationTest` for the per-tenant counts) and the other nine follow.

L27 — **T6b-2b is the child's tenant, and it is the parent's: 478 → 100.** The two groups this slice owned
were one mechanism seen from two ends. A fixture that saves a child row (`CompanyCountry`, `Department`,
`Employee`, `ProductCategory`, a store level, a purchase-order line) writes a row whose tenant **is its
parent's**, and it fails twice for the same missing fact: Hibernate runs its unsaved-value probe
(`select … from companies where id = ?`) because the referenced `Company` is detached, and the child's own
`INSERT` meets the armed table's `WITH CHECK`. `TenantFixtures.inTenant(parentCompanyId, …)` answers both,
because the probe and the insert then run inside the transaction whose `doBegin` installed that tenant. The
34 `NoSuchElementException`/`CompanyNotFoundException` were the same omission on the read side: a lookup or
an assertion that ran with no tenant stated and therefore read zero rows.

**The second-largest cause was the cleanup, and it is the one that hides.** An unscoped `DELETE` on an armed
table removes **zero rows and reports success**, so a previous method's rows survived into the next and
surfaced as a duplicate key or a transient reference somewhere else entirely. `PurchaseOrderDetailVariantIntegrationTest`
now seeds its reference data before cleaning, for exactly that reason.

**Two deviations, both deliberate and both reported rather than smoothed.** (1) Fixture principals in nine
classes now carry a `company_id` **claim**. A company-less endpoint takes its tenant from the claims (T5b), so
a claim-less admin token legitimately sees nothing — the fixture was asserting against a caller that cannot
exist in production. `inTenant` cannot help there: the request owns its own transaction. The store-claim case
that must stay 403 keeps carrying no store claim. (2) `StoreOptimisticLockingIntegrationTest` uses
`TenantScope.with` per worker thread instead of `inTenant`, because the scope is thread-confined and the
test's own two `TransactionTemplate`s have to remain the boundaries — the lost-update probe it exists for
would otherwise be defeated by the wrapper.

**One assertion was rephrased, and it is the only one.** `CompanyStoreHierarchyFilterIntegrationTest#noLevelConstrainsNothing`
compared one flat `contains` across two tenants; on the superuser pool that reads every tenant's rows and
cannot mean what it says. It is now `contains` per tenant plus a `containsExactly` on a narrowed predicate —
the same defect L26 recorded for the cross-tenant count helpers, and the same fix: name the tenant.

**Evidence, as observed.** Default mode before and after: **4251 tests, 0 failed, exit 0** — the differential
gate, and the run that caught the rephrasing above. App role: **478 → 100**, exit 1. Per package before →
after: salesorder 40 → 0, scheduling 31 → 0, purchaseorder 31 → 2, goodsreceipt 23 → 0, inventory 18 → 0,
hr 95 → 1, store 85 → 0, product 57 → 3, provisioning 8 → 0. `spotlessApply spotlessCheck spotbugsMain`
clean. `src/main` and `support/` untouched: the slice is call sites.

**What the 100 are, and four of them are findings that need a human, not a fixture.**
- **83 are `@Transactional` suites** — their transaction begins before the fixture can state a scope; the
  fixture write has to move to its own transaction, with the declared cost that it survives the rollback.
  **T6b-3.**
- **10 are machinery** that owns its roles and connections, including one I could not explain and did not
  patch: `EmployeeCrudIntegrationTest$IsolationTests#everyReferencingTableIsCleaned` reads
  `information_schema.table_constraints` and gets **zero rows under the application role** although the role
  holds `SELECT` on every table and `information_schema.columns` answers. **T6b-4**, with the catalog
  behaviour named rather than guessed.
- **3 assertions read a message the policy now preempts**: an `INSERT` that omits `company_id` is refused with
  `42501` before `NOT NULL` fires (the order ADR-0005 chose), so the assertion has to name the policy's
  refusal when the pool is the application role. Not patched, because widening it to "any exception" is how
  the signal gets lost.
- **1 is a semantic shift the maintainer should decide**: a store of another company now answers **404**
  instead of an empty page, because the store is invisible before the company predicate can exclude it.
- **2 are a product question**: `PurchaseOrderListFilterIntegrationTest` asserts both an admin token claiming
  company A asking for company B, and an unfiltered admin page expected to contain company B's order. A
  company-less path **refuses** a two-company token (T5b), so a cross-company administrator view is not
  expressible at all — that is a decision about the product, not a fixture to bend.

What this slice does not claim: **S5 is not met**, the application role is still not the default, and 100
tests still fail without it. The gate moved 545 → 478 → 100, and every remaining failure now has a named
owner or a question the maintainer owns.

L28 — **T6b-3 found two production defects, and they are the slice's real result: 100 → 29.** The 83
`@Transactional` failures were a fixture problem with a mechanism nobody had: Spring's test transaction begins
**before** `@BeforeEach`, so `doBegin` already ran with no scope and nothing can be retro-fitted at that
boundary. L24 predicted the fix would be a `REQUIRES_NEW` fixture write; measured, that is half a fix — it
would open a *different* transaction while the test's own reads and every MockMvc request joining it stayed
unscoped, and it would commit fixture rows past the rollback the suite relies on. The mechanism that works is
narrower and already existed in the database: `set_tenant_scope(uuid)` is `set_config(…, true)`, i.e.
`SET LOCAL`, so it can run **inside the transaction the suite already owns**, on that transaction's own
connection. `TenantFixtures.stateTenantWithinTheOpenTransaction(companyId, jdbc)` is that call, it refuses
when no transaction is active (outside a transaction block `SET LOCAL` is a success that does nothing), and it
is not a bypass: no role change, no widened policy, same SQL production's `doBegin` runs. The five suites that
use it issue `SET LOCAL` and nothing else — **no suite now commits a row it used to roll back**, and the
default-mode run is the evidence.

**The first production defect: a read with nowhere to put its tenant.** `AccessProvisioningWorker.process`
stated its tenant and called `EmployeeRepository.findByIdWithCompanyAndStatus`, a declared `@Query` finder that
owns **no transaction** — and the scope is installed by `doBegin`, so the statement ran unscoped, the policy
answered zero rows, and the worker concluded "Employee not found" for every claimed task. In delivery that
provisions nobody while looking like a data problem: the silent failure ADR-0005 exists to remove, and it was
invisible on the superuser pool. Measured before the fix: under the same stated scope the finder answered
`present=false` while the inherited `findById` answered `present=true`, and the SQL log showed the employees
select with no `set_tenant_scope` anywhere before it. Fixed with `@Transactional(readOnly = true)` on the finder
— the boundary lives with the query, its one production caller is the worker, and `AccessProvisioningWorkerTest`
stayed untouched because a mock does not read annotations.

**The second, and it is the general consequence of arming the tables.** With the read fixed, one case advanced
and failed differently: `No row with the given identifier exists for entity [Department]`.
`AccessProvisioningRoleService.requireOwnedPosition` decided ownership by walking
`position.getDepartment().getCompany().getId()` — a lazy to-one into `departments`, one of the sixteen armed
tables. For a position of **another company** that row is invisible, and Hibernate turns "invisible" into a
mapping exception before any code can name the rule; the same request would 500 in delivery. The rule the ADR
implies has to be said plainly: **code may not decide by reading a row it is not allowed to see.** The method
now asks the identical question as an id predicate
(`positionRepository.findByDepartmentCompanyIdAndId(employeeCompanyId, position.getId())`) and throws the same
exception with the same message — deterministic on both pools, no foreign row read, and the integration case
went green **without touching its assertion**. `Position` and `Department` carry no `company_id` column (they
are the S2 out-of-scope tables), which is why the question had to become a finder rather than a comparison
against a column on the entity.

**The four decisions the maintainer took, implemented as decided.** A request must **name its company** — there
is no cross-company administrator view, so the two `PurchaseOrderListFilterIntegrationTest` cases now ask for A
or for B by name and receive that company's rows, plus a new case that a caller naming a company outside its
scope is never given that company's orders. The three assertions that read a message the policy preempts now
assert **both halves** through a `poolIsTheApplicationRole()` predicate — the policy's `42501` under the app
role, the constraint's message under the superuser — and `ProductVariantStoreFilterIntegrationTest` asserts
**404** for a store of another company and says in its own words why that is the answer that discloses least.
Two `store/` suites outside the earlier surfaces got the same one-line mechanism (5 failures), and the three
provisioning suites that had missed T6b-2's rule for their raw seeds and cleanups got it.

**Evidence.** App role, per class before → after: `CompanyCountryScopedReadHttp` 26 → 0,
`SupplierCompanyScopeHttp` 7 → 0, `PositionRole` 5 → 0, `StarterProductCategoryMigration` 3 → 0, the three
provisioning worker suites 8 → 0, `AccessProvisioningWorkerApply` 3 → 0, `SupplierSchemaMigration` 1 → 0,
`ProductCatalogCompanyScopeSchema` 2 → 0, `ProductVariantStoreFilter` 1 → 0, `PurchaseOrderListFilter` 2 → 0,
`CompanyStoreScopeProjection` 3 → 0, `CompanyStoreScopeAgreement` 2 → 0. Whole suite: **default 4254/0, exit 0**
(the differential gate, and the run that proves no rollback was lost); **app role 4254/29, exit 1**.
`spotlessCheck` and `spotbugsMain` clean. This is the chain's only `src/main` change: 3 files, 57 insertions.

**The 29 that remain are T6b-4's, and one of them is unexplained.** 28 are the machinery suites that own their
roles and connections (`TenantScopeFilter` 8, `TenantPolicies` 6, `ProvisioningWorkerTenantScope` 3,
`TenantTaskResolver` 3, `TenantScopeFunctions` 2, `TenantScopeBoundary` 2,
`StarterProductCategoryCompanyCreation` 2, `CompanyTenantScope` 2), plus the L27 finding that
`information_schema.table_constraints` answers zero rows under the application role. S5 is therefore **still
not met**, and the role is still not the default.

**And a survey of the same shape, which is the next real work and is not this slice's.** A static reading of
`src/main` found the identical traversal pattern — ownership decided by walking a to-one into an armed table —
**reachable with a caller-supplied id** in `hr/service/PositionService.java:128` and four times in
`hr/service/EmployeeScopeAssignmentService.java:337,352,367,381` (a `companyCountry` from a request-supplied
id, where the intended `CompanyCountryNotFoundException` becomes a mapping exception under the policy). Nine
more occurrences are currently unreachable because the row is already company-scoped, three of them
(`PurchaseOrderService:879-882`, `ProductSupplierService:71-73/192-194`,
`GoodsReceiptService:549-551`) resting on foreign keys that do not constrain the referenced row to the same
company — unverified, and therefore listed rather than trusted. None was fixed here: each is a production
change with its own evidence, and doing them inside a fixture slice would hide them.

L29 — **T6b-4 takes the 29 to zero and the application role becomes the pool's only login: S5 closes.** The 29
were the nine classes that own their roles, connections, policies and roles-of-the-catalogue on purpose, and
each one was judged on what it actually asserts rather than on the class it lives in.

**Three directions, and no assertion was weakened or deleted.**

- **The policy applies, so the case runs as `lifecontrol_app` and its fixture states its tenant.**
  `TenantScopeFilterIntegrationTest` (8) is `@Transactional`, so its transaction — and `doBegin` — begins before
  `@BeforeEach` and both `companies` inserts met V53's `WITH CHECK`; the fixture now states each row's own id on
  the open transaction through `TenantFixtures.stateTenantWithinTheOpenTransaction`, the T6b-3 mechanism, and
  the eight cases keep proving which requests the filter refuses. `ProvisioningWorkerTenantScopeIntegrationTest`
  (3) seeded `employees` and cleaned it with unscoped raw statements; the inserts and the three deletes now run
  inside the tenant whose rows they name, which is also what stops a `DELETE` from removing zero rows and
  reporting success.
- **The mechanism, so the case gets the connection it needs, stated explicitly.** `TenantPoliciesIntegrationTest`
  (6), `TenantScopeFunctionsIntegrationTest` (2), `CompanyTenantScopeIntegrationTest` (1) and
  `TenantTaskResolverIntegrationTest` (3) open their own transactions on the container's own login
  (`DriverManager`, or a `DataSource` bean from `containerSuperuserPool()`) because their premise is a
  connection no policy filters and, for three of them, `ALTER TABLE … FORCE ROW LEVEL SECURITY`, which needs the
  table's owner. `TenantScopeBoundaryIntegrationTest` (2) is the same fact one layer down: the boundary hook is
  observed through transactions the suite opens, so its **pool** declares the credential. Its two failing cases
  were the “both rows exist” controls, which are unfiltered by design and had been reading `0` and `1` under the
  application role — the policy applying to the probe table's own owner. The role is now a parameter of each
  suite, argued in its javadoc, instead of an ambient fact of the run.
- **A production behaviour the policy now filters, stated as the tenant the request is about.**
  `CompanyTenantScopeIntegrationTest#theServiceStatesTheIdItsRowWillCarry` (1) counted the committed row through
  the pool with no scope and read `0`; a company's tenant **is** its own id, so the count states it.
  `StarterProductCategoryCompanyCreationIntegrationTest` (2) counted the seeded catalog the same way, and its
  cleanup deleted on armed tables with no scope — three deletes that removed nothing. Both now read and clean
  inside the company's tenant.

**The `information_schema` question, answered with the measurement that was missing.** The unexplained case was
`EmployeeCrudIntegrationTest$IsolationTests#everyReferencingTableIsCleaned`, whose join over
`information_schema.table_constraints` answered **zero rows** under the application role. Measured on this
container with the role holding `SELECT, INSERT, UPDATE, DELETE` on every table: `table_constraints` answers
**99** foreign-key rows, `has_table_privilege(…, 'INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER')` and
`has_any_column_privilege(…)` are both **true**, `pg_has_role(<owner>, 'USAGE')` is **false** — and
`information_schema.constraint_column_usage` answers **0 rows**, because its definition filters on
`pg_has_role(c.relowner, 'USAGE')` alone: it lists columns only of tables the caller **owns**, and this role owns
nothing (S4). The join was therefore empty for the role and non-empty for the superuser the pool used to be. The
resolution is the honest one: the rule is read from `pg_catalog.pg_constraint`, which answers for any role that
may connect, and the case still proves exactly what it proved — every table with a foreign key to `employees`
must be emptied by the shared helper, and a new one fails here.

**The switch, deleted rather than defaulted.** `AbstractPostgresIntegrationTest` gives the pool
`lifecontrol_app` and only that: `POOL_IS_THE_APPLICATION_ROLE`, `TEST_POOL_APP_ROLE` and the two conditional
accessors are gone, and the Flyway split (owner/superuser migrates, the application role queries) is untouched
because it is also delivery's shape. `TenantFixtures` lost the paragraph that said the pool was not switched yet.

The six assertions that stated **both halves** of a refusal — `ProductCatalogCompanyScopeSchemaMigration` (2),
`ProductVariantStoreFilter` (1), `SupplierSchemaMigration` (1) and `PurchaseOrderListFilter` (2) — asserted
the policy's answer under this pool and the constraint's under the old superuser one, branched through a
`poolIsTheApplicationRole()` predicate. With one mode left the predicate is deleted and each site keeps only the
application-role assertion, unchanged in its message, its matcher and its `doesNotContain`, so the reader finds
one fact instead of a mode that no longer exists. The only `else` branch deleted was unreachable; every
surviving assertion text is the text that was already there.

**One production gap found and reported, not patched.** `StarterProductCategoryApplier.applyTo` opens a
`REQUIRES_NEW` transaction and takes the company id as an argument, but never states it: it reads
`companies` and writes `product_categories` on whatever scope the caller happened to carry. The
`AFTER_COMMIT` path from `CompanyService.createCompany` works because the service states the new company's id
around its transaction, and that is why the first case is green; a publisher with **no** scope — the
`fallbackExecution` case, an operator script, a bulk import — gets `CompanyNotFoundException`, a logged `ERROR`
and no catalog. The case pins the listener's fallback with the tenant stated, which is the fact the listener
needs; closing the gap is a `src/main` change (state `companyId` inside `applyTo`, as L16 did for the create
path) and belongs to its own slice.

**Evidence, as observed.** The nine classes, same invocation before and after: **64 tests, 29 failed** → **64
tests, 0 failed**. Whole suite with no environment variable at all: **4 254 tests, 0 failed, exit 0** — S5's
acceptance. `spotlessApply spotlessCheck spotbugsMain` clean. The machinery suites on their own —
`TenantPoliciesIntegrationTest`, `TenantScopeFunctionsIntegrationTest`, `TenantScopeBoundaryIntegrationTest` —
green, each for the reason its javadoc now states.

L30 — **T6c makes delivery match what the suite already proved: the pool connects as `lifecontrol_app` and
only Flyway holds the owner.** Until this slice the split existed in three files that agreed with each other
and disagreed with ADR-0005 decision 3. The test container gave the pool `lifecontrol_app` and Flyway the
owner (`AbstractPostgresIntegrationTest:98-112`), while delivery exported both secrets under names that meant
the same thing: `DATABASE_PASSWORD` carried the **owner's** password, and `DATABASE_APP_PASSWORD` had no
reader in any file of the tree. Every policy the four S5 suites prove was therefore enforced in the container
and nowhere a customer could see it, which is the failure mode this unit exists to remove rather than a
cosmetic naming problem.

**The split, as it now exists.** Four env vars, two secrets, two consumers, and no var that means both:

| env var | value comes from | consumer |
| --- | --- | --- |
| `DATABASE_USERNAME` | compose, `${LIFECONTROL_DATABASE_USERNAME:-lifecontrol_app}` — a role **name**, never a credential | `spring.datasource` |
| `DATABASE_PASSWORD` | `docker/secrets/lifecontrol_app_password`, read by the wrapper | `spring.datasource` |
| `FLYWAY_USERNAME` | compose, `${LIFECONTROL_POSTGRES_USER:-lifecontrol_owner}` — the role postgres is initialized with | `spring.flyway` |
| `FLYWAY_PASSWORD` | `docker/secrets/lifecontrol_postgres_password`, read by the wrapper | `spring.flyway` |

**Why these names, and why no new `.env` key.** `DATABASE_PASSWORD` kept its name and changed its meaning,
because `spring.datasource.password` already read it and a rename would have moved a working binding for a
cosmetic reason; what it carries now is the pool's credential, which is the only thing that name should ever
have meant. The owner's secret got a **new** name, `FLYWAY_PASSWORD`, because its job changed: it is not "the
database password", it is Flyway's login, and the wrapper is the one place that decides which secret goes to
which var. No password key was added to `.env.template` and none may be: role **names** are declared there,
credentials never (the rule `AGENTS.md` already states). `FLYWAY_USERNAME` was deliberately bound to
`LIFECONTROL_POSTGRES_USER` instead of a new template key — both name "the role that owns the schema", and a
fifth knob would be a fifth thing to get stale for no gain.

**Flyway needed a third property, not a second process.** `spring.flyway.user` alone would have left
Boot building Flyway's connection with no url, so `spring.flyway.url` is set to the same `${DATABASE_URL:…}`
expression the datasource uses, and the suite asserts the two agree. `spring.flyway.password` defaults to
**empty** on purpose: outside the container Flyway is disabled (H2 test profile) or the caller supplies its own
credential, while in delivery the wrapper refuses to start without the owner secret and an empty password
cannot authenticate over `scram-sha-256` — so a lost split fails loudly instead of migrating as the wrong role.
The shape is exactly the one the Testcontainers pool has used since L24, which is why "the Flyway split is also
delivery's shape" is now a statement about code instead of an intention.

**The wrapper's own paragraph is now the description of what it does.** The sentence that said which secret
feeds which property "is the next slice, not this wrapper's decision" is gone, and so is the twin comment in
`docker-compose.prod.yml`. What replaced them is the table above, in the two files where a reader looks for it.
The three mount paths became overridable (`LIFECONTROL_OWNER_PASSWORD_FILE`, `LIFECONTROL_APP_PASSWORD_FILE`,
`KEYCLOAK_ADMIN_CLIENT_SECRET_FILE`) with the real `/run/secrets/...` path as the default, following the same
pattern `postgres-init/10-lifecontrol-roles.sh` already uses — that is what lets a POSIX-shell wrapper be
*executed* by an offline suite instead of grepped. The cardinal rule is untouched and asserted: the wrapper
carries no secret content, and every credential it exports is a `$(cat …)` of a file.

**Files touched, and why.**

| File | Change |
| --- | --- |
| `docker/entrypoints/lifecontrol-app-entrypoint.sh` | exports `DATABASE_PASSWORD` from the app secret and `FLYWAY_PASSWORD` from the owner's, refuses a missing file for either, drops `DATABASE_APP_PASSWORD`, documents the split |
| `life-control-api/src/main/resources/application.properties` | `spring.datasource.username` defaults to `lifecontrol_app`; adds `spring.flyway.url/user/password` so Flyway logs in as the owner inside the same process |
| `docker/docker-compose.yml` | `DATABASE_USERNAME` defaults to the application role, new `FLYWAY_USERNAME` from `LIFECONTROL_POSTGRES_USER`, both secret mounts documented |
| `docker/docker-compose.prod.yml` | the stale "pool switch is the next slice" comment replaced by which credential is which; prod inherits both role names from the base file |
| `docker/scripts/db-roles.sh` | the stale-name report is now **per key**: `LIFECONTROL_POSTGRES_USER` must name the owner and `LIFECONTROL_DATABASE_USERNAME` must name the app role, so the pre-split value `lifecontrol_owner` in the pool key is reported as stale too — it authenticates fine and silently disables every policy |
| `docker/scripts/tests/app-credential-split.test.sh` | new gate, 56 assertions |
| `docker/scripts/tests/db-roles.test.sh` | its pins moved with the wrapper and with the per-key report; the pool-key case and the pre-split-value case added |
| `odd/tasks/tenant-isolation-rls.md` | this entry and the header |

**The gate, and what it would catch.** `app-credential-split.test.sh` runs the wrapper against a throwaway
secrets directory with a stub `java` that records the env it would have received, and asserts that
`DATABASE_PASSWORD` is the **app** secret, `FLYWAY_PASSWORD` is the **owner's**, and the two differ. Swapping
the two files back — the exact regression — fails there and nowhere else: the Java suite cannot see it (it
configures its own pool) and every other shell suite greps. It also pins that the wrapper rewrites neither role
name (a sentinel passes through untouched), that `DATABASE_APP_PASSWORD` is gone, that both missing-secret
cases exit 1 without launching Java, that `application.properties` binds each pair and never names the other
pair's variable, that compose and the properties agree on both role defaults (a container and a bare `bootRun`
must not connect as different roles), that prod re-points neither role, and that `.env.template` declares the
app role while carrying no credential.

**Evidence, as observed.** The gate costs one differential run, and it was taken: the pre-change wrapper
(`git show HEAD:…`, secret paths rewritten to a `mktemp -d` only so the old file could be driven at all — it
has literals) received `DATABASE_PASSWORD=owner-secret-1`, `FLYWAY_PASSWORD=<unset>`,
`DATABASE_APP_PASSWORD=app-secret-2`; the delivered one receives `DATABASE_PASSWORD=app-secret-2`,
`FLYWAY_PASSWORD=owner-secret-1`. That is the RED the suite encodes, measured after the change rather than
before it: the suite and the edit were written in the same pass, so no pre-implementation RED was observed and
this entry claims none. GREEN: `./docker/scripts/tests/app-credential-split.test.sh` → **54 passed, 2 failed**,
and the two failures are one item, named below. `./docker/scripts/tests/db-roles.test.sh` → **127 passed, 1
failed**, the same item. Whole repository suites: `.github/scripts/tests/*.test.sh` → exit 0, **385 assertions,
0 failed**; `docker/scripts/tests/*.test.sh` → **23 suites**, 21 exiting 0, **1 100 assertions** with the 3
failures above. Backend: `cd life-control-api && ./gradlew test --no-daemon` → `BUILD SUCCESSFUL in 3m 40s`,
**4 254 tests in 1 022 classes, 0 failed, 0 errors, 0 skipped, exit 0** (unchanged from L29, as expected: no
test reads these files). Compose still parses in all three combinations (`docker compose config -q` over base,
base+prod and base+override, exit 0 each) — read-only, no stack started. `validate-env.sh dev` → exit **1**,
`[ERROR] …/docker/.env.dev not found!`: it needs a materialized env file and this worktree has none, which is
the trap `AGENTS.md` documents, so none was created.

**What still contradicts S4, named rather than glossed.** One delivery file could not be written by this
session: `docker/.env.template:56` still reads `LIFECONTROL_DATABASE_USERNAME=lifecontrol_owner` (and the
comment above it still describes the older split). The harness's safety policy refuses every tool access to a
`.env*` path, so those two blocks are the only gap: a human applies the edit — `lifecontrol_owner` → the app
role in that key, and the comment rewritten to say which secret feeds which consumer — and the three red
assertions (`app-credential-split` ×2, `db-roles` ×1) go green with no code change. The consequence of
shipping without it is concrete: `setup-env.sh` materializes a new env file from this template, the pool key
would name the owner, the owner authenticates, and every policy silently stops applying to the application —
with only `db-roles.sh`'s warning between that and production. Four documentation files also still describe
the pre-split wiring and are outside this unit's edit surface: `AGENTS.md:296` and `:298` (the wrapper "exports
both", `DATABASE_PASSWORD` "is still the owner's"), `README.md:189` and `life-control-api/README.md:81` /
`life-control-api/AGENTS.md:1014` (`DATABASE_USERNAME` default listed as `lifecontrol_user`, a role that no
longer exists). **Both gaps closed on 2026-10-31, once the parent extended the edit surface**: the maintainer
applied the two `.env.template` blocks — `app-credential-split` **56 passed, 0 failed** and `db-roles` **128
passed, 0 failed**, with no code change, which is the whole of what the three red assertions were — and those
four documents now state the two jobs (pool on `DATABASE_PASSWORD`/`spring.datasource`, Flyway on
`FLYWAY_PASSWORD`/`spring.flyway`), with `AGENTS.md`'s docker-glob row carrying the measured count, **23 suites**
read from a run of the glob.

**And the step this record does not claim.** The live smoke — `./docker/scripts/setup-env.sh dev`,
`./docker/scripts/deploy.sh dev up`, `deploy.sh dev health` — was **not run**: it needs a docker stack, and
this unit was scoped offline. So nothing here asserts that a real container logs in, that Flyway migrates as
the owner on a filled volume, or that `version`/`health` come back green; those are the maintainer's. `shellcheck`
is not installed in this environment (`command not found`, exit 127), so the repo's shell lint was also not
run here; `bash -n`/`sh -n` over the four touched scripts passed, and the CI job that runs `shellcheck
--severity=warning` over every shell file by glob remains the gate for it.

**`Closes #696` now legitimately claims**: ADR-0005 decision 3 is wired end to end — the roles SQL, the two
secret files, the wrapper, `spring.datasource` on the application role and `spring.flyway` on the owner — so
the pool holds no credential that can bypass a policy and an offline suite fails if that ever regresses; what
remains is one `.env.template` edit, the four documentation lines above, and the maintainer's live smoke.
