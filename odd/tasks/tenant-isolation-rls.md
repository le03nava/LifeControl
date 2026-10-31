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
