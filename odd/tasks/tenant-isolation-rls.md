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
