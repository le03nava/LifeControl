# ODD feature: hr-org-structure

**Status**: **W1a is implemented and green on `feat/hr-org-structure`** — `V19` plus the three
catalogs, tasks 1–5, commits `34bc26c` through `1fc98e2`. The remaining work is **W1b, W2 and W3**
below, and the merge stays **blocked** on `company-scope-local-fallback` (G1). This header claims no
push, PR or merge state; see the evidence log.
**Created**: 2026-09-30 · **Risk**: **medium–high** — five new tables plus a role template, the
repository's first self-referencing foreign key, and its first company-scoped catalog (no
precedent exists). No existing contract changes and no data migration: all five tables are new and
nothing references them yet.
**Repository**: LifeControl — spans `life-control-api/**` (Spring Boot, Java 21, PostgreSQL 18.1 +
Flyway) and `life-control-app-angular/**` (Angular 20.3 + Material/CDK 20). No gateway change: the
routes land under the existing `/api/**` prefix and no new first path segment is introduced.
**Migration**: **`V19`** (V18 is the current head).
**Base**: `main` @ `274c67f` · **Branch**: `feat/hr-org-structure` · **Worktree**:
`~/workspace/LifeControl-worktrees/feat-hr-org-structure` (herdr `wM`), created with the procedure
in `.agents/skills/project-conventions/references/worktrees.md`.
**Requested by**: the user — "quiero empezar primero con lo relacionado a los empleados de la
compañía", 2026-09-30.

## Origin

The `scheduling` domain was delivered and merged (W1–W7, PRs #198–#215) and then paused: its
employee link is `scheduling_activities.user_id` / `scheduling_appointments.user_id`, a free-text
Keycloak `sub` with **no authorized source** — the gap its own record declared as **G21** and
**G31**. The user reframed the next step as the missing "who": the company's employee model. This
record is that model's **org-structure half**; the employee and contract tables are
`employee-registry`, and the access half is `employee-access-provisioning`.

The four tables and the role template below were designed across a working session on 2026-09-30,
starting from a MySQL-flavoured draft the user wrote independently and adapting it to this
repository's conventions. Every adaptation below cites the convention it derives from.

## Decisions (user-owned, closed)

| # | Decision | Consequence |
| --- | --- | --- |
| D1 | `seniority_levels` is **global reference data**, not company-scoped | It follows `measure_units`/`payment_methods`, not `departments`. Consequences: its role stays **unscoped** (no `ScopeLevel` entry), and disabling a level affects every company. The real per-company policy lives in `position_salary_bands`, which is company-scoped through its position |
| D2 | **One role per catalog**, following the repository's stated convention | `lc-department`, `lc-position`, `lc-seniority-level`. Each is registered in four places: `Roles.java`, `docker/scripts/keycloak-setup.sh`, the write `@PreAuthorize` of every endpoint, and — for the two company-scoped ones — `ScopeLevel.COMPANY.roleNames()` |
| D3 | Company scope through `verifyCompanyAccess` **plus a local fallback** (option (b) of the design round) | `lc-department`/`lc-position` join `ScopeLevel.COMPANY`, and the fallback is its own record (`company-scope-local-fallback`). **Blocking for merge**: without it a caller holding only those roles is denied whenever the token lacks the `company_id` claim — and no code in this repository emits that claim (E12) |
| D4 | A new top-level header entry **`Recursos Humanos`** with three children — Departamentos, Puestos, Empleados — appended **last** | Id `9`, children `9-1`/`9-2`/`9-3`. Appending preserves the pinned product-order assertion (`indexOf('/products') < indexOf('/purchases') < indexOf('/users-admin')`) that the scheduling parent already respected (E14) |
| D5 | Seniority levels are a **secondary screen reached from Puestos**, not a fourth child | This repository's global catalogs have **no menu entry** at all — `countries`, `measure_units` and `payment_methods` are absent from the header |
| D6 | `position_roles` is a **provisioning seed, never a live authority** (model A) | The roles are applied once, on an explicit action; **Keycloak stays authoritative** afterwards. Changing a position's template does not alter anyone's existing access, and the API exposes a **diff** instead of overwriting silently. Derived authority (model B) is a later evolution for which this is a prerequisite |
| D7 | The grantable role set is an **allowlist** of business roles, and **`lc-admin` is never grantable** | Without it, `lc-employee` would be an escalation to full admin: `isAdmin()` short-circuits **every** `verifyCompany*Access`, so an admin reads every company's data including salaries. A test must fail if `lc-admin` is ever added to the allowlist |
| D8 | Identity provisioning gets its **own role** (`lc-employee-access`), separate from `lc-employee` | "Editing HR data" and "granting system access" are different privileges; sharing them would let every HR clerk grant roles |
| D9 | The corporate email is generated with rule **R2** — `first_name + "." + paternal_last_name`, normalized — and the Keycloak **username is the full email** | `juan.perez@<company-domain>`. The generated token is the local part; the username is built from it plus the domain (see T13 for why the bare local part is not usable as a username) |
| D10 | The provisioned password is **temporary, with a forced change** | Not `setTemporary(false)` as the existing users-admin path does (E15). **Answered on 2026-10-01** by `employee-access-provisioning`'s **O1**: the person does not receive a password at all — the account is created with `UPDATE_PASSWORD` and `VERIFY_EMAIL` as required actions and Keycloak emails an **activation link**, so the person sets their own password and no secret travels through this application. That record's **W6** carries the SMTP configuration this needs, its **T15** keeps the operator hand-over as the fallback for a company whose domain cannot deliver mail, and its **G9** records the new hard requirement: an undeliverable address now means the person cannot log in |
| D11 | The original **W1 is cut in two** — **W1a** (the three catalogs) and **W1b** (salary bands and the role template) — and the branch delivers W1a first | Decided by the user on 2026-09-30, before the first source line, at the cut point this record's own sizing note had already named. **The migration is not cut**: `V19` creates all five tables in one file, so W1b adds an API over tables that already exist, not a migration |

## Decisions (mine, technical — challenge them if you disagree)

| # | Decision | Why |
| --- | --- | --- |
| T1 | `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`, entities extend `Auditable`, soft delete through `enabled BOOLEAN NOT NULL DEFAULT true` | The repository's universal shape: all 39 existing tables have UUID primary keys and **zero** use `SERIAL`/`AUTO_INCREMENT`/`CREATE SEQUENCE` (E2); `Auditable` supplies `created_at`/`updated_at` (E1); `DELETE` soft-deletes (E4) |
| T2 | **No `version` column on any of the five tables** | The repository's rule is visible in its data: `version` sits on **9** mutable edited aggregates (store tree, inventory settings, the four scheduling tables) and on **no catalog** (E5). These are catalogs. The consequence is worth stating plainly: **these forms carry no 412 precondition and are simpler than the store-tree ones** |
| T3 | `enabled` is the only state column here; **no native enum and no `CHECK (col IN ...)`** | The repository contains **zero** `CREATE TYPE ... AS ENUM` and **zero** membership `CHECK`s (E6). The `status_types`/`statuses` catalogue is reserved for entity lifecycles and is used by the employee record, not by these catalogs |
| T4 | Money is `DECIMAL(12,2)` mapped to `BigDecimal`, with a single implied currency | Every monetary column in the schema is `DECIMAL(12,2)` and **no currency column or catalogue exists anywhere** (E7). Declared as **G2** rather than half-solved here |
| T5 | `positions.reports_to_position_id` is a **self-referencing foreign key** — a first for this repository — guarded by a named `CHECK` for the trivial cycle, with deeper cycles validated in the service | No `parent_id` self-FK exists anywhere; the established hierarchy pattern is a **chain of distinct tables**, and no `WITH RECURSIVE` exists in the codebase (E8). An org chart cannot be a chain of N tables, so the new pattern is taken deliberately and declared as **G5** |
| T6 | **`hierarchy_level` is dropped.** Depth is derivable from the tree | The original draft carried both a parent pointer and a stored depth: two truths for one fact, desynchronized by the first reorganization. If it is ever needed for performance it must be a declared cache with an update rule, not a plain column |
| T7 | `positions` inherits the company through `department_id` and **never duplicates `company_id`** | The repository's habit is to derive the ancestor chain rather than denormalize it; denormalizing would create a second truth for a fact the join already answers |
| T8 | `position_roles` carries **no scope or client column** | Every business role in this repository is a client role of the application client, and the realm roles are legacy or `admin` (E11). Modelling scope would prepare a case that must not be permitted. A note for any future widening: adding a nullable `client_id` to that `UNIQUE` would silently stop deduplicating, because PostgreSQL treats `NULL`s as distinct — `UNIQUE NULLS NOT DISTINCT` (PG 15+) would be required. The simplified table needs neither |
| T9 | **"Configuración del puesto" is one page with two independent sections** — Tabuladores and Roles del sistema — each with its own save and its own endpoint | This refines the earlier "Tabuladores gets its own route" recommendation: with **two** position policies, two separate routes are worse for the administrator. The actual requirement recorded by D42 of the scheduling record is that two write contracts **do not share one save button**, not that they cannot share a screen |
| T10 | The salary-band save is an **upsert keyed on `(position_id, seniority_level_id)`**, never a delete-then-insert | These rows have a **natural key**, unlike the scheduling windows. The delete-then-insert idiom is exactly what destroyed unbooked slots in the merged scheduling domain (its D19 and G17); it is not repeated where it is not needed |
| T11 | `PositionList` is a flat list with a department filter; nested navigation is not used | A position belongs to exactly one department. The store tree nests because an area owns N zones; here flattening loses nothing and avoids a second navigation level |
| T12 | **No local mirror of assigned roles.** The diff is resolved live against Keycloak | A table of "roles this user has" would be a **fourth** source of truth (Keycloak, the position template, a mirror, the token). Cost, declared: the employee Access section depends on the Keycloak Admin API and needs its own error state while the HR data still renders |
| T13 | The Keycloak **username is the full email**, not the generated local part | Keycloak usernames are unique **per realm**, and this repository has a single realm for every company, while `employees.email` is unique **per company**. A bare `juan.perez` username would therefore collide across companies **deterministically**, not rarely. The full email carries the company domain and is realm-unique by construction while the domains differ. It also makes the login the least surprising one. If two companies share a domain, the 409 from Keycloak means "this person already has an account — link it instead of creating it" |
| T16 | **Company-scoped catalog reads are not cached** in this slice, while the global one is | A `@Cacheable` on a method whose body starts with the scope check serves a cache **hit** without executing that check at all — the annotation wraps the whole method. So caching a scoped read trades its authorization for a cache lookup. `CompanyRegionService` (`:58`, `:85`) and `CompanyZoneService` (`:66`, `:92`) do exactly that today, which is declared as **G12** instead of copied. `seniorityLevels` keeps its cache: its reads have no scope check to bypass and its data is global |
| T15 | `lc-department` and `lc-position` **stay** in `ScopeLevel.COMPANY.roleNames()`, but for a narrower reason than D3 states, and the record says so | The premise of D3 does not survive reading the code: `verifyCompanyAccess` is `isAdmin()` plus a check of the `company_id` **claim** — it never consults `roleNames()` (E19). So the registration does not make the HR endpoints reachable, and their gate is the endpoint's `@PreAuthorize`. What the registration really does is resolve these two roles to `COMPANY` on the **broadest-granted path**, which is the path behind `verifyCompanyCountry|Region|Zone|StoreAccess` — meaning an HR-only caller with the claim is verified at company breadth on those deeper checks. That is the intended consequence of being a company-scoped role, and it is now pinned by a test. Both roles are kept: dropping them would give their holders no scope in range at all, which is a worse failure mode than an honest denial |
| T14 | The role constants are registered in **two commit halves, not one**: the backend registration (`Roles.java`, `ScopeLevel.COMPANY`, `keycloak-setup.sh`) lands in **W1a** because W1a's `@PreAuthorize` expressions reference it, and the frontend `core/security/roles.ts` constants land in **W2** because that is where the first guard reads them | This refines W4. Shipping unused frontend constants in W1a would be dead code no test can justify; shipping the backend ones late would not compile. W4 survives as the *checklist* of the four registration sites, with its two halves attached to the slices that need them |

## Verified exploration evidence

Read-only exploration ran on 2026-09-30 against `main @ 274c67f`. Every line is anchored.

| # | Fact | Anchor |
| --- | --- | --- |
| E1 | `Auditable` is a `@MappedSuperclass` with `created_at`/`updated_at` as `LocalDateTime`, written by `@PrePersist`/`@PreUpdate` | `common/model/Auditable.java:11-28`; DDL twin `V16__scheduling_activities.sql:34-35` |
| E2 | All 39 tables in `db/migration/**` use UUID primary keys. **Zero** `SERIAL`, `AUTO_INCREMENT` or `CREATE SEQUENCE`. 28 declare `DEFAULT gen_random_uuid()`, 10 are bare `UUID PRIMARY KEY`, 1 uses a FK as its PK | `db/migration/**`; `V1__baseline_schema.sql:11-18`, `:445-463`; `V11__store_inventory_settings.sql:21-22` |
| E3 | Catalog shape: `<entity>_name` plus `<entity>_code`, `enabled`, no `display_order` (only the store tree has it) | `V1:11-18` (`countries`), `V1:255-262` (`status_types`), `V1:284-295` (`measure_units`), `V1:302-309` (`payment_methods`); `V5:15`, `V6:16`, `V7:16` for `display_order` |
| E4 | `enabled BOOLEAN DEFAULT true NOT NULL` is the universal soft-delete flag and `DELETE /{id}` sets it to `false` | `CountryController.java:64-69`; `CountryService.java:94-97`. Hard deletes exist only for M:N join rows (`ProductSupplierService.java:120-128`) |
| E5 | `version BIGINT NOT NULL DEFAULT 0` exists on **exactly 9** tables: `company_stores`, `store_areas`, `store_zones`, `store_locations`, `store_inventory_settings`, `scheduling_activities`, `scheduling_availability`, `scheduling_slots`, `scheduling_appointments`. No catalog carries it | `V8:11-14`, `V11:25`, `V16:32`, `V17:39,58`, `V18:41` |
| E6 | State is modelled three ways, and two of the three are absent: the `status_types`/`statuses` catalogue (`V3:11-12, 24-28`), a free-form `VARCHAR` with **no** `CHECK` (`shifts.status` `V1:455`, `inventory_movements.movement_type` `V10:35`), and **zero** `CREATE TYPE ... AS ENUM` and **zero** `CHECK (col IN (...))` in the whole schema | `db/migration/**`; `V12:44` is the representative comparison `CHECK` |
| E7 | Every monetary column is `DECIMAL(12,2)` → `BigDecimal`. **No currency column and no currency catalogue exist anywhere** | `V1:208, 346-347, 428, 433, 477, 500-503`; `V10:22, 36`; `V12:44`; `V13:27-29` |
| E8 | **No self-referencing foreign key exists anywhere**, and no `WITH RECURSIVE` exists in the codebase. The established hierarchy is a chain of distinct tables with traversal done iteratively in Java | `V5`, `V6`, `V7`; `StoreLocationService.java:107-131`; `PurchaseOrderService` chain walk |
| E9 | `addresses` exists and is linked with `@OneToOne(cascade = {PERSIST, MERGE}, fetch = LAZY)` — no `REMOVE`, no `orphanRemoval` | `V1:25-37`; `CompanyStore.java:30-34`; `Company.java:63-67` |
| E10 | Foreign keys are **unnamed and inline** in every migration, with no `ON DELETE`. `uq_*`/`ck_*` naming starts at V16 | `V16:14`, `V17:25-26` (the rule, verbatim), `V14:74-75` (named) vs `V12:19-31` (unnamed) |
| E11 | Catalog roles are one per catalog and **absent from `ScopeLevel`** — reads are `isAuthenticated()`, writes are `hasAnyRole(ADMIN, <catalog>)` | `Roles.java:38-45`; `PaymentMethodController.java`; `docker/scripts/keycloak-setup.sh` client-role loop; `ScopeLevel.java` declares only COMPANY…STORE |
| E12 | Company-scope authorization is `CurrentUserContext.verifyCompanyAccess(UUID)` called as the **first statement** of the service method, before the load. Store-scope levels are verified against **claims, never against parent roles**, so a store-scoped caller must carry the claim path — and **no code in the repository emits the `company_*` claims** | `CurrentUserContext.java:205, 285`; `CompanyService.java:74-76`; `ScopeLevel.java` STORE javadoc; `odd/tasks/store-claim-hardening.md:185` |
| E13 | `paymentmethod/**` is the catalog template to copy: the only catalog with the full test triad (service + controller + security). Package layout is `controller/ service/ repository/ model/ dto/ exception/`, DTOs are records, and **no `mapper/` layer exists anywhere** | `paymentmethod/**`; `AbstractPostgresIntegrationTest.java` for integration work |
| E14 | Frontend: header ids `2,2-1,2-2,3,4,5,6,7,8,8-1,8-2` are taken; the product order is pinned by `indexOf` assertions; `Calendario y citas` was appended last for that reason; role constants live in `core/security/roles.ts` and the menu reads them with `.some(...)` | `core/layout/header/header.ts`; `core/layout/header/header.spec.ts:125,196,216,493,523,620-633`; `core/security/roles.ts:95,109` |
| E15 | The mechanism for everything that `position_roles` will later feed already exists, but three capabilities are missing | `IdentityProvider.assignRoleToUser(userId, roleName, RoleScope, clientId)` (`KeycloakIdentityProvider.java:272`), `removeRoleFromUser` (`:294`), `getUserRoles`, `updateUser` (`:86`), enum `RoleScope{REALM, CLIENT}`. **Missing**: group-membership operations, a batch user fetch, and the application client id as a configured property (`life-control-client` appears only in javadoc; `JwtDecoderConfig` reads `resource_access.<azp>`) |
| E17 | **The integration stack runs the real migrations, and one test pinned the head version at exploration time.** `AbstractPostgresIntegrationTest` extends nothing but sets `spring.flyway.enabled=true` and `spring.jpa.hibernate.ddl-auto=validate`, and `GoodsReceiptIntegrationTest.flywayAppliesLatestAndSchemaValidates` asserted `flyway.info().current().getVersion()` was **`"18"`** — the only such assertion in the suite when this record was written. `V19` breaks it by construction, so updating it became part of task 1. **The "only" half of this fact expired during delivery**: task 1 added a second head pin to `HrSchemaMigrationIntegrationTest`, and the independent verification refuted the single-pin claim for exactly that reason. Two pins now exist, both reading `19`, and no test asserts a migration **count** | `support/AbstractPostgresIntegrationTest.java:29-43`; `goodsreceipt/GoodsReceiptIntegrationTest.java:574-579`; `hr/HrSchemaMigrationIntegrationTest.java:65-67`; `application-test.properties` (H2, Flyway off, `create-drop`) applies only to the non-integration slices |
| E18 | The role registration sites are the ones D2 lists, and the client-role loop is a single line-continued `for` in the setup script | `Roles.java`; `ScopeLevel.java`; `docker/scripts/keycloak-setup.sh:152-157` (client roles) and `:167` (realm roles, not this record's) |
| E19 | `verifyCompanyAccess` is **claim-only**: `isAdmin()` short-circuits, otherwise `verifyLevel(ScopeLevel.COMPANY, companyId)` compares the `company_id` claim set and denies. It never calls `hasAnyRole`/`broadestGrantedScope`, and `insufficientRole`'s message **`"Insufficient role for company access"` is unreachable** — every caller of `verifyBroadestGranted` passes `COUNTRY`, `REGION`, `ZONE` or `STORE` as the failure target, never `COMPANY`. The existing test `countryRoleCanAccessAssignedCompany` passes with the legacy `life-control-country` realm role, which is in no `roleNames()` list, and is the proof | `common/auth/CurrentUserContext.java:202-207` (`verifyCompanyAccess`), `:322-346` (`verifyBroadestGranted`/`broadestGrantedScope`/`insufficientRole`), `:376-378` (`hasAnyRole`); `common/auth/CurrentUserContextTest.java:625-645` |
| E16 | Frontend templates: `regions-list`/`regions-edit` for a catalog **without** a version precondition; `store-areas-edit` for a form **with** version + 412; `scheduling-activity-list` for a list with `canWrite` + `ConfirmDialog`; `AddressFormComponent` is already shared | `features/companies/.../regions-*`, `.../store-areas-edit`, `features/scheduling/pages/scheduling-activity-list`, `shared/ui` |

## Schema — `V19`

```sql
-- ============================================
-- V19 — HR org structure (departments, seniority levels, positions, salary bands, role template)
-- ============================================
-- `departments` is company-scoped: two companies may both have an "Operaciones", so every
-- uniqueness constraint is per company. `positions` hangs off a department and inherits the
-- company through it; `company_id` is never duplicated. `reports_to_position_id` is the first
-- self-referencing foreign key in this schema: the CHECK blocks only the trivial one-node cycle,
-- and the deeper cycles are validated in the service. `position_roles` is the role TEMPLATE of a
-- position — a provisioning seed, never the authority — and `role_name` is validated against a
-- declared allowlist in the service (`lc-admin` is deliberately absent).
-- Foreign keys are unnamed, matching the baseline and the V9..V18 style. The CHECKs and the
-- unique keys are named so a violation identifies the rule it broke.
-- ============================================

CREATE TABLE departments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL REFERENCES companies(id),
    department_code VARCHAR(10) NOT NULL,
    department_name VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    display_order INTEGER,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_departments_company_code UNIQUE (company_id, department_code),
    CONSTRAINT uq_departments_company_name UNIQUE (company_id, department_name)
);
CREATE INDEX idx_departments_company_id ON departments(company_id);

CREATE TABLE seniority_levels (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    level_code VARCHAR(10) NOT NULL UNIQUE,
    level_name VARCHAR(50) NOT NULL UNIQUE,
    rank INTEGER NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_seniority_levels_rank UNIQUE (rank),
    CONSTRAINT ck_seniority_levels_rank CHECK (rank > 0)
);

CREATE TABLE positions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    department_id UUID NOT NULL REFERENCES departments(id),
    position_code VARCHAR(10) NOT NULL,
    position_name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    reports_to_position_id UUID REFERENCES positions(id),
    display_order INTEGER,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_positions_department_code UNIQUE (department_id, position_code),
    CONSTRAINT uq_positions_department_name UNIQUE (department_id, position_name),
    CONSTRAINT ck_positions_not_self_reporting
        CHECK (reports_to_position_id IS NULL OR reports_to_position_id <> id)
);
CREATE INDEX idx_positions_department_id ON positions(department_id);
CREATE INDEX idx_positions_reports_to_position_id ON positions(reports_to_position_id);

CREATE TABLE position_salary_bands (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    position_id UUID NOT NULL REFERENCES positions(id),
    seniority_level_id UUID NOT NULL REFERENCES seniority_levels(id),
    minimum_salary DECIMAL(12,2) NOT NULL,
    maximum_salary DECIMAL(12,2) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_position_salary_bands_position_level UNIQUE (position_id, seniority_level_id),
    CONSTRAINT ck_position_salary_bands_range CHECK (maximum_salary >= minimum_salary),
    CONSTRAINT ck_position_salary_bands_non_negative CHECK (minimum_salary >= 0)
);
CREATE INDEX idx_position_salary_bands_position_id ON position_salary_bands(position_id);
CREATE INDEX idx_position_salary_bands_seniority_level_id ON position_salary_bands(seniority_level_id);

CREATE TABLE position_roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    position_id UUID NOT NULL REFERENCES positions(id),
    role_name VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_position_roles UNIQUE (position_id, role_name)
);
CREATE INDEX idx_position_roles_position_id ON position_roles(position_id);
```

No seed data: this migration creates empty catalogs. The `EMPLOYEE_STATUS` family is seeded by
`employee-registry`.

## API surface

All six endpoints of the first two groups nest under
`/api/companies/{companyId}/...`, resolved with a `resolveCompany`-style private method that calls
`verifyCompanyAccess(companyId)` first and then loads the entity chain, mirroring
`StoreLocationService.resolveStore` (E12). Seniority levels are **not** nested, because they are
global (D1).

| Method | Path | Roles |
| --- | --- | --- |
| `GET` | `/api/companies/{companyId}/departments?includeDisabled=` | `isAuthenticated()` |
| `GET` | `/api/companies/{companyId}/departments/{id}` | `isAuthenticated()` |
| `POST` | `/api/companies/{companyId}/departments` | `lc-admin`, `lc-department` |
| `PUT` | `/api/companies/{companyId}/departments/{id}` | `lc-admin`, `lc-department` |
| `DELETE` | `/api/companies/{companyId}/departments/{id}` | `lc-admin`, `lc-department` |
| `PATCH` | `/api/companies/{companyId}/departments/{id}/enable` | `lc-admin`, `lc-department` |
| `GET` | `/api/companies/{companyId}/positions?departmentId=&includeDisabled=` | `isAuthenticated()` |
| `GET` | `/api/companies/{companyId}/positions/{id}` | `isAuthenticated()` |
| `POST` | `/api/companies/{companyId}/positions` | `lc-admin`, `lc-position` |
| `PUT` | `/api/companies/{companyId}/positions/{id}` | `lc-admin`, `lc-position` |
| `DELETE` | `/api/companies/{companyId}/positions/{id}` | `lc-admin`, `lc-position` |
| `PATCH` | `/api/companies/{companyId}/positions/{id}/enable` | `lc-admin`, `lc-position` |
| `GET` | `/api/companies/{companyId}/positions/{positionId}/salary-bands` | `isAuthenticated()` |
| `PUT` | `/api/companies/{companyId}/positions/{positionId}/salary-bands` | `lc-admin`, `lc-position` |
| `GET` | `/api/companies/{companyId}/positions/{positionId}/roles` | `isAuthenticated()` |
| `PUT` | `/api/companies/{companyId}/positions/{positionId}/roles` | `lc-admin`, `lc-position` |
| `GET` | `/api/seniority-levels?includeDisabled=` | `isAuthenticated()` |
| `POST` `PUT` `DELETE` `PATCH` | `/api/seniority-levels[...]` | `lc-admin`, `lc-seniority-level` |

`PUT .../roles` rejects a `role_name` that is not in the allowlist (400) **or** that does not exist
as a client role of the application client (400), by validating against
`IdentityProvider.listClientRoles(clientId)` (E15).

## Screens

Feature folder `src/features/hr/`, routed through one lazy `loadChildren` entry at `/hr`.

| Route | Page | Content |
| --- | --- | --- |
| `/hr/departments` | `DepartmentList` | Code, name, position count, order, status; search; "show disabled"; create / edit / disable (confirm) / re-enable |
| `/hr/departments/create`, `/hr/departments/edit/:id` | `DepartmentEdit` | Code, name, description, order. **No version, no 412** (T2) |
| `/hr/positions` | `PositionList` | Code, name, department, reports-to, order, status; department filter; actions plus a per-row **Configuración** action |
| `/hr/positions/create`, `/hr/positions/edit/:id` | `PositionEdit` | Department, code, name, description, reports-to, order. **No version, no 412** |
| `/hr/positions/:id/settings` | `PositionSettings` | Two independent sections — **Tabuladores** (one row per global seniority level; the row set is the catalog, not free rows) and **Roles del sistema** — each with its own save and its own endpoint (T9) |
| `/hr/seniority-levels` | `SeniorityLevelList` | Rank, code, name, status. Secondary screen reached from Puestos; no menu entry (D5) and no company context |

The route guards follow the repository's own split: the route admits the read set and **the screen
decides what to render** through `canWrite = hasAnyClientRole(...)`, which gates actions and never
informative text.

## Work units

| | Content | Sizing note |
| --- | --- | --- |
| **W1a** | `V19` (all five tables — the migration is one unit) + the three catalogs end to end: entities, repositories, services, controllers, DTOs and exceptions, with the service/controller/security test triad per catalog, the `reports_to_position_id` guard (T5) and W4's backend constants (T14) | Declared **over** the 400-line review budget. The five tables were separated into two PRs by **D11**, taken before the first source line. The two tables left unmapped — `position_salary_bands`, `position_roles` — are legal here because `ddl-auto=validate` checks mapped entities, not the reverse, and nothing references them yet |
| **W1b** | `position_salary_bands` (the upsert of T10) and `position_roles` (the allowlist of D7) with their endpoints and test triad | The second half of the original W1. Depends on W1a: both rows point at a `positions` row |
| **W2** | The three catalog screens plus the header entry, the routes and the role constants | **The header entry ships here, not at the end.** The scheduling record's own E53 is the reason: "no rendered control reaches `/scheduling`" was found only after four frontend slices had merged, and a slice nobody can reach is a slice nobody can review by hand |
| **W3** | `PositionSettings`: the salary-band editor (upsert, T10) and the role-template editor with the allowlist check | The two sections are independent and could ship as two PRs |
| **W4** | The role registration **checklist**: `Roles.java`, `keycloak-setup.sh`, `ScopeLevel.COMPANY` and `core/security/roles.ts` | Split by **T14**: the backend three land inside **W1a** task 2, because W1a's guards reference them; `roles.ts` lands inside **W2**, where it is first read. W4 is not a slice of its own |

Dependency order inside the slice: **W1a → W1b → W2 → W3**, with W4's constants split as T14 describes.

**W1a's own task order** (each one a work-unit commit; the parent commits, the writers do not):

| # | Task | Why this order |
| --- | --- | --- |
| 1 | `V19` + the head assertion in `GoodsReceiptIntegrationTest` + a schema integration test for what the service cannot prove | Nothing compiles against the tables until they exist, and the head assertion is a **known breakage** (E17), not a discovery to make later |
| 2 | Role registration: `Roles.java`, `ScopeLevel.COMPANY.roleNames()`, `docker/scripts/keycloak-setup.sh` (T14) | The `@PreAuthorize` expressions of tasks 4 and 5 cannot compile without it |
| 3 | `seniority_levels` end to end | The one unscoped catalog: it exercises the catalog mold with the least machinery (no company scope, no self-FK) |
| 4 | `departments` end to end | Adds the company scope and the `resolveCompany` chain walk |
| 5 | `positions` end to end | Adds the self-FK, the deep-cycle service check and the same-company check (T5, G6). Last because it needs the department of task 4 |

## Gaps

| # | Gap | Note |
| --- | --- | --- |
| G1 | These are the repository's **first company-scoped catalogs**, and the only actor model that makes them safe is `ScopeLevel.COMPANY` plus the `company_id` claim — which **no code emits** | **Blocking for merge.** It is `company-scope-local-fallback`'s to close. Until then the choice is between a 403 for the intended role and a cross-tenant leak. **That record now exists** (written 2026-10-01 on its own branch, `feat/company-scope-local-fallback`, commit `7957c41`) and it does **not** conclude what this row's name suggests: it finds the local row unsafe as a source at all — `user_preferences` is written by the subject of the decision, with no validation — so it leaves the mechanism as an open user decision (its D1) and its recommendation is IdP-provisioned claims as the destination. Treat this G1 as blocking until that decision is taken, and read its "The escalation the name hides" before assuming a small local fallback closes it |
| G2 | No currency anywhere in the schema | Salary bands carry a single implied currency. Multi-currency is a platform-wide change, not this slice's |
| G3 | Salary bands have **no effective dating** | Editing a band rewrites current policy retroactively. The real salary history lives in `employee_contracts.monthly_salary`, so the band table is "current policy" and a dated history of bands is separate work |
| G4 | Only the **immediate manager** is rendered in phase 1 | The full org chart needs the repository's first recursive read (`WITH RECURSIVE`) or an iterative Java walk (E8) |
| G5 | `reports_to_position_id` is a **new pattern** for this repository | The deep-cycle guard is service-level; only the trivial cycle is a DB guarantee |
| G6 | Two invariants the database cannot express | A position may not report to a position of another company, and a salary band's position must belong to the company in the path. Both are service-level checks |
| G7 | No store assignment here | `employee-store-assignments` owns it, and it is what gives the store-scoped roles their scope (E12) |
| G8 | `position_roles` **cannot enforce the allowlist at the database level** | The allowlist lives in a service constant plus a test that fails if `lc-admin` is added (D7) |
| G9 | `display_order` is borrowed from the store-tree tables | No catalog in the repository has it (E3) |
| G10 | No shared table, paginator or empty-state component exists in the frontend | These pages use raw Material like every other feature (E16) |
| G11 | `verifyCompanyAccess` **never consults the role registry**, so on the company level the claim is the only thing separating two principals inside one company | Any authenticated caller whose `company_id` claim contains the company passes it; the *role* is enforced only by the endpoint's `@PreAuthorize`. Tightening it (routing `COMPANY` through `broadestGranted` like the deeper levels) would deny principals that pass today — the legacy `life-control-country` caller among them — so it is declared here and left alone rather than changed inside an HR slice (E19, T15) |
| G12 | **A pre-existing cross-tenant read: the company-scoped catalogs of the store tree cache their reads by a key that omits the company, with the scope check inside the cached method.** On a cache hit the check never runs, so a caller holding one of the admitted roles for company A is served company B's row or list once any legitimate read has warmed the entry. | **Not this slice's to fix, and not fixed here**: `life-control-api/src/main/java/com/lifecontrol/api/company/service/CompanyRegionService.java:58,85` and `CompanyZoneService.java:66,92` pair `@Cacheable` (keys `'all-' + #companyCountryId + '-' + #includeDisabled` and `#id`) with `verifyCompanyRegionAccess`/`verifyCompanyZoneAccess` *inside* the cached method, and the read endpoints admit a role set rather than a claim. Exploitability needs the row or parent UUID to be known or guessed, and the Redis region has a 1-hour TTL while the `SimpleCacheManager` fallback has **none** — entries live for the life of the process. Fixing it is its own record: the options (drop the cache, key by company, or verify outside the cached call through a second bean) change behaviour of merged endpoints, which is why it is declared and not patched inside an HR slice. This slice's catalogs are uncached instead (T16), and a proxy-based test pins that |
| G13 | **The three role literals are registered in four places and only two of them are coupled by a test.** `Roles.java` is pinned against itself (the `ScopeLevel` registry pin) and against the Java-side string literals the tests hardcode, but **nothing reads `docker/scripts/keycloak-setup.sh`**, so renaming a constant without renaming the script's client-role list is silent | Measured, not argued: renaming the `Roles.DEPARTMENT` **literal** to `lc-departments` fails 2 tests in `CurrentUserContextTest` and 4 in `DepartmentControllerSecurityTest` (all of them hardcoding `ROLE_lc-department`) while `companyScopeListsCompanyRoles` **stays green**, because it compares the constant to itself. The script is outside the test classpath, so closing this needs a deliberate mechanism (a test that reads the file by path, or generating the list) and that decision is a separate slice |
| G14 | **The three catalogs do not agree on the case sensitivity of their natural keys.** `seniority_levels` checks `existsByLevelCodeIgnoreCase` / `existsByLevelNameIgnoreCase` while `departments` and `positions` check the exact value, and `V19`'s unique keys are case-sensitive for all three | So `JUN`/`jun` is refused by one catalog and accepted by the other two, and the record never decided this. Both consistent options are cheap and they differ in product outcome: make all three case-insensitive (refuses two spellings of the same department) or all three exact. Left undecided rather than silently changed, because it is a naming policy, not a defect |
| G15 | **`life-control-api/AGENTS.md` documents the migration set as ending at `V15`**, and its Schema section lists no HR tables | Pre-existing drift, deeper than this record: `:694` and `:872` say the schema is built from `V1`→`V15`, and the migration table at `:865` ends at `V15__inventory_balance_reset.sql`, while the tree already carried `V16`–`V18` before this slice added `V19`. Recorded here because this slice compounds it; fixing it is doc hygiene of its own |

## Cross-record dependencies

| Record | Relation |
| --- | --- |
| `company-scope-local-fallback` | **Blocking** for this record's merge (G1). Written on 2026-10-01 on its own branch (`feat/company-scope-local-fallback`, `7957c41`), **not yet merged**: it maps the 37 scoped call sites and the five unprovisioned claims, and it concludes that the local row **cannot be the fallback source as it stands**. Its mechanism choice (D1) is still the user's, so this record's G1 stays open |
| `employee-registry` | Sibling. Depends on `departments`/`positions` existing for the contract's `position_id` |
| `employee-store-assignments` | Depends on `employee-registry`; not on this record |
| `employee-access-provisioning` | Depends on this record's `position_roles` (D6) **and** on `employee-store-assignments` |
| `scheduling-calendar` | Merged and paused. Its G21/G31 are the gaps this domain exists to close, and its `user_id` free text is what `employee-registry` replaces |

## Task log

- [x] W1a — `V19` and the three catalogs end to end (`34bc26c`…`1fc98e2`)
  - [x] 1 — `V19` migration, the Flyway head assertion, the schema integration test (`34bc26c`)
  - [x] 2 — role registration (`Roles.java`, `ScopeLevel.COMPANY`, `docker/scripts/keycloak-setup.sh`) (`66aefa1`)
  - [x] 3 — `seniority_levels` end to end (`8a461a1`)
  - [x] 4 — `departments` end to end (`a2967d7`)
  - [x] 5 — `positions` end to end, with the cycle and same-company service checks (`f1e29d1`)
- [ ] W1b — salary bands and the role template
- [ ] W2 — catalog screens, routes, header entry (plus the frontend role constants, T14)
- [ ] W3 — position settings (salary bands + role template)
- [ ] W4 — role registration, split by T14: backend half in W1a task 2, frontend half in W2

## Deferred and blocking

- **Blocked on `company-scope-local-fallback`** for merge (G1). Development is not blocked: the
  tables and endpoints can be built and tested against an admin principal, which bypasses the scope
  check entirely. **W1a was developed exactly that way**, and the `company_id` claim the two HR write
  roles need is still emitted nowhere.
- **Deferred**: the full org chart (G4), dated salary-band history (G3), currency (G2).

## Evidence log

| Date | Evidence |
| --- | --- |
| 2026-09-30 | Design closed in a working session against `main @ 274c67f`. Convention extraction and capability inventory recorded above as E1–E16; every anchor read from the working tree. No code written. |
| 2026-09-30 | Both HR records committed to `feat/hr-org-structure` as the branch's first commit, **`95ce632`** `docs(odd): plan the HR domain (org structure + employee registry)` (tree identity checked: `git write-tree` == `HEAD^{tree}`, the hook rewrote nothing). The git worktree was missing `life-control-app-angular/node_modules`, so the anchor's copy was linked. **W1 cut into W1a/W1b by the user (D11)** and W1a's task order fixed (T1–T5) before the first source line. Exploration re-run against the worktree head added **E17** (the integration stack runs Flyway and one test pins the head at `18`) and **E18** (the role registration sites). No source written. |
| 2026-09-30 | **W1a task 1** delivered as **`34bc26c`** `feat(hr): add V19 HR org-structure schema`: `V19__hr_org_structure.sql` verified **byte-identical** to this record's SQL block (`diff` of the extracted block against the file, empty), the Flyway head pin in `GoodsReceiptIntegrationTest` moved 18 → 19, and `HrSchemaMigrationIntegrationTest` added with 8 tests proving the five tables exist and that `ck_seniority_levels_rank`, `ck_position_salary_bands_range`, `ck_position_salary_bands_non_negative`, `ck_positions_not_self_reporting`, `uq_departments_company_code` and `uq_positions_department_code` each reject their invalid write and leave the table untouched (constraint names asserted from the PostgreSQL error text). Focused run: 8/8 new, 20/20 `GoodsReceiptIntegrationTest`; `spotlessCheck` and `spotbugsMain` green. |
| 2026-09-30 | **W1a task 2** delivered as **`66aefa1`** `feat(hr): register the HR catalog roles`, after the delegated writer **stopped to ask** instead of guessing: the task brief asked for a `"Insufficient role for company access"` pin, and that message is unreachable because `verifyCompanyAccess` is claim-only (E19). The parent confirmed the finding by reading the same lines, the pinning strategy was changed to the broadest-granted path (T15), and the record gained **E19**, **T15** and **G11** as a result. Focused run: `CurrentUserContextTest` 119 → 126 tests, 0 failures; `spotlessCheck`, `spotbugsMain` and `bash -n docker/scripts/keycloak-setup.sh` green. |
| 2026-09-30 | **W1a task 3** delivered as **`8a461a1`** `feat(hr): add the global seniority-level catalog`: the `SeniorityLevel` entity, its DTO pair, its 404/409 exceptions, its repository, a cache-aware service and the controller at `/api/seniority-levels`, with 56 new tests (20 service / 17 controller / 19 security) and the `seniorityLevels` cache region registered in `CacheConfig` — the `SimpleCacheManager` fallback throws for an unregistered region, which is why the region list is an edit outside the `hr` package. The entity mapping was validated by the existing `ddl-auto=validate` integration stack, not by inspection. Three deviations from the `paymentmethod` template, all deliberate: `DELETE` soft-deletes (E4, `country`) instead of hard-deleting, the list orders by `rank` and takes `includeDisabled` (this record's API table), and uniqueness covers code, name **and** rank. |
| 2026-09-30 | **W1a task 4** delivered as **`a2967d7`** `feat(hr): add the company-scoped department catalog`: the `Department` entity with a lazy `Company` association, one shared request DTO (no Create/Update split, because there is no version precondition to carry — T2), per-company 409s, a company-scoped repository, the service whose every public method resolves the company first (E12) and whose single-row lookups make another company's row a 404, and the controller at `/api/companies/{companyId}/departments`. 65 new tests, including six that assert `verifyCompanyAccess` runs **before** the company load, one per public method. Two findings came out of the delegated execution: the first version replicated the scoped-read cache of `CompanyRegionService`, which the writer flagged as a risk; the parent confirmed the mechanism and refused to copy it, which produced **T16** and **G12**, removed every cache annotation from the service and left `CacheConfig` byte-identical to task 3. The replacement pin is a Spring-proxy test whose sensitivity was proven by **re-adding** the `@Cacheable`, observing `TooFewActualInvocations` (2 expected, 1 actual, raised at `CurrentUserContext:203` from `DepartmentService:73`), and removing it again. |
| 2026-09-30 | **W1a task 5** delivered as **`f1e29d1`** `feat(hr): add the positions catalog with the self-referencing reports-to`: the `Position` entity with the schema's first self-association, its DTO pair, per-department 409s, a repository whose company-wide queries traverse the department, the service with `resolveReportsTo` and `verifyNoCycle` (a visited-set walk upward, so it rejects the self-reference, the direct hop and any deeper descendant, and terminates on an already-cyclic chain), the controller, 83 new tests and a real-PostgreSQL test for the lazy walk. The two `reportsTo` rules G5/G6 are **400** (`IllegalArgumentException`, the `StatusValidator` idiom) while a missing parent is **404**; the service rejects the self-reference before the DB CHECK can turn it into a 409, and the CHECK itself stays pinned by the schema test. 213 tests green in the package at that commit. |
| 2026-09-30 | **W1a independently verified** read-only by a fresh verifier run on `f1e29d1`: **46 files changed** against `274c67f`, of which **4 are modified and the rest added**; the gate declared in `.github/workflows/api-ci.yml` (`spotlessCheck spotbugsMain` then `test`) driven with a forced `cleanTest` → **717 XML files / 2611 tests / 0 failures / 0 errors / 0 skipped** for the full suite and **57 files / 213 tests** for `com.lifecontrol.api.hr.*`, aggregated from the XML rather than the console. Ten claims adjudicated: **eight verified, C2 refuted** — the slice added a **second** Flyway head pin, so this record's "the only such assertion" claim was stale, which is why **E17** was rewritten — and C3 verified only in the scope it was actually written for (6 of `V19`'s 11 named constraints, which is what the task-1 evidence named). The verifier also established by reading that nothing pre-existing was weakened: `GoodsReceiptIntegrationTest`'s head assertion is the only modified pre-existing assertion and it is **at least as strong**, and `CacheConfig` differs from `main` by one line. It flagged the unpinned script literals, the case-sensitivity split and the `AGENTS.md` migration drift, which are now **G13**, **G14** and **G15**. |
| 2026-09-30 | **The two coverage holes that verification found were closed in `1fc98e2`**, and the same pass falsified every load-bearing pin by mutation — each one applied, measured, and restored: re-adding `@Cacheable` to `DepartmentService.getDepartmentById` and to `PositionService.getPositionById` fails their two proxy tests with `TooFewActualInvocations` (2 wanted, 1 actual); dropping `Roles.DEPARTMENT` from `ScopeLevel.COMPANY.roleNames()` fails 3 routing tests; collapsing `verifyNoCycle` to a single hop fails **both** the three-level test and the new two-level one; a hard delete in `deleteDepartment` fails the soft-delete test; and the head pin back at `"18"` fails. Every mutation-only file came back **byte-identical** (`sha256sum` before and after) and the tree was left holding exactly the two intended test files. Renaming the `Roles.DEPARTMENT` **literal** measured **G13**: 6 tests fail while the registry pin stays green, because it compares the constant to itself. |
| 2026-09-30 | **Delivery numbers re-measured on the final commit `1fc98e2`** with a forced `cleanTest test`: **717 XML files / 2615 tests / 0 failures / 0 errors / 0 skipped**, and `com.lifecontrol.api.hr.*` → **57 files / 217 tests** (exactly +4 over the pre-verification figure; one class went 8 → 11 and another 41 → 42). A third figure a writer reported — 202 tests across 53 files — was **an aggregation artifact, not a run**: four HR XML files are named `TEST-c-<execId>.lifecontrol.api.hr...`, because Gradle's parallel executor rewrites the `com.` segment, so any filename filter keyed on `com.lifecontrol.api.hr` silently drops those 4 files / 15 tests (53+4 = 57, 202+15 = 217). Recorded because it is a trap for whoever measures this suite next: **aggregate by `classname`, never by filename**. `spotlessCheck` and `spotbugsMain` passed **UP-TO-DATE**, so that pass rests on the cached main-source run rather than a fresh measurement. |
