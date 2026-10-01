# ODD feature: employee-registry

**Status**: planned, nothing implemented — the remaining work is W1–W4 below. This header makes
no claim about branch, push or PR state; see the evidence log.
**Created**: 2026-09-30 · **Risk**: **high** — the first real person→company model in the schema,
a generated identity whose rule is a one-way door, and the repository's first exclusion constraint
(which needs its first PostgreSQL extension). No existing contract changes beyond one added column
on `companies`.
**Repository**: LifeControl — spans `life-control-api/**` (Spring Boot, Java 21, PostgreSQL 18.1 +
Flyway) and `life-control-app-angular/**` (Angular 20.3 + Material/CDK 20). No gateway change.
**Migration**: **`V20`** (V19 belongs to `hr-org-structure`; V18 is the current head).
**Base**: `main` @ `274c67f` · **Branch**: `feat/hr-org-structure` · **Worktree**:
`~/workspace/LifeControl-worktrees/feat-hr-org-structure` (herdr `wM`). This record shares the
branch with `hr-org-structure` deliberately: **the two migrations are designed together**, because
every contract row points at a `positions` row that V19 creates. If the review load proves too
high, the split point is between the two records, not inside one.
**Requested by**: the user — the employee model request of 2026-09-30 and the follow-up decision
"el email debe ser autogenerado … y ese mismo usarlo como usuario".

## Origin

`hr-org-structure` builds the org chart — departments, positions, salary bands, and the role
template of a position. **None of it is about a person.** This record adds the person: the HR
record, the employment contract history, and the generated corporate identity.

It is also the answer to a gap the repository has carried since before the scheduling domain: there
is **no authorized source of "who"**. Every existing person reference is a free-text Keycloak `sub`
in a `VARCHAR` column (`shifts.user_id`, `sales_orders.user_id`, `scheduling_activities.user_id`,
`scheduling_appointments.user_id`), with no entity, no list, and no validation. `hr-org-structure`'s
G7 and the scheduling record's G21/G31 are the same hole seen from two sides.

## Decisions (user-owned, closed)

| # | Decision | Consequence |
| --- | --- | --- |
| D1 | The corporate email is generated with rule **R2** — `first_name + "." + paternal_last_name`, normalized — over the company's configured domain | `juan.perez@<company-email-domain>`. **This rule is a one-way door**: renaming an email is renaming a login. Once people are using it, changing it means coordinating with each person and migrating accounts in Keycloak. It is frozen by this record, not revisited by a later slice |
| D2 | The Keycloak **username is the full email**, not the generated local part | The generated token is the local part; the username is built from it plus the domain. The reason is structural and recorded as T6 |
| D3 | The provisioned password is **temporary, with a forced change** | Owned by `employee-access-provisioning` (D10 there). This record only guarantees that `employees.email` exists and is frozen before provisioning can happen |
| D4 | `employees` carries **no position and no department** — both come from the current contract | A person's position is a fact about a contract, not about a person. Duplicating it on the employee would be a second truth, desynchronized by the first promotion |
| D5 | `employee_number` is **typed by the operator**, never generated | It comes from payroll. The repository's generated numbers (`PO-…`, `SO-…`) are computed as max+1 **without a lock** and their record already declares that race; it is not inherited here. The per-company `UNIQUE` constraint is the real guarantee |
| D6 | A person holds **one position**: `employee_contracts.position_id` stays a single required column. A plural model ("the position or positions") was considered on 2026-10-01 and **rejected** by the user | Not a preference — a consequence worth stating: with the exclusion constraint forbidding two overlapping contracts of one employee (T12) and a new contract closing the previous one the day before (T13), **"two positions at once" is not expressible without a child table**. A promotion is a new contract, and the role template a person receives is the union of exactly one position. If multi-position is ever needed it arrives as `employee_contract_positions`, never as two contracts |

## Decisions (mine, technical — challenge them if you disagree)

| # | Decision | Why |
| --- | --- | --- |
| T1 | `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`, `Auditable`, `enabled BOOLEAN NOT NULL DEFAULT true` | Same as `hr-org-structure` T1; the repository's universal shape |
| T2 | **`employees` carries `version`; `employee_contracts` does not** | `version` belongs to mutable edited aggregates — exactly 9 tables in the repository, and no catalog. An employee is a rich edited aggregate with a form; a contract is history whose only mutation is being closed once |
| T3 | `employment_status_id` resolves through the **`statuses` catalogue** with a new seeded type **`EMPLOYEE_STATUS`**, following the appointment decision lineage (D8/D23 of the scheduling record) | The repository has **zero** native enums. `enabled` is a different fact from the life cycle: `enabled = false` means the row was deleted, while a `Terminated` employee stays `enabled = true` because their contracts and history must remain readable |
| T4 | Status names are **English** (`Active`, `Inactive`, `OnLeave`, `Terminated`), with the Spanish labels in the frontend | Every seeded status in the repository is English (`Draft`, `Registered`, `Scheduled`); the Spanish label map lives client-side, exactly as `APPOINTMENT_STATUS_LABELS` does |
| T5 | The status is settable on create **and** on update, with `StatusValidator.requireStatusOfType(…, "EMPLOYEE_STATUS")`, and defaulting to `Active` | Unlike a booking (which is always pinned to `Scheduled`), an HR roster is legitimately pre-loaded with people who have not started. Two service rules ride along: `Terminated` **requires** `termination_date`, and any other status **requires it to be absent** |
| T6 | The generated local part uses `Normalizer.normalize(x, NFD)` + `\p{M}` stripping, `toLowerCase(Locale.ROOT)`, and removal of everything outside `[a-z0-9]` | Two classic defects are prevented by construction: without the normalizer `Pérez` becomes `prez`, and `toLowerCase()` under a Turkish default locale turns `I` into `ı` (`Locale.ROOT` is the fix). The strip handles `De la Cruz`, `O'Connor` and `Peña-Ríos`. Only the **first given name** is used ("Juan Carlos Pérez" → `juan`), parts are length-capped, and an empty result falls back to `employee_number` |
| T7 | Collisions are resolved by **attempting the write and retrying with the next numeric suffix** (from 2), never by computing a free suffix first | The repository's max+1-without-a-lock pattern is a declared race in purchase and sales orders. Here the retry loop plus `UNIQUE (company_id, email)` is race-safe without a lock, and exhausting the attempts is a 409 |
| T8 | The **suggest endpoint reserves nothing** | `GET …/employees/suggest-email` returns the next free candidate and takes no lock. Two operators can be shown the same candidate; the `UNIQUE` catches it at save time and the 409 is the honest outcome. The UI must present it as a suggestion, not a reservation |
| T9 | The email is **editable while `keycloak_user_id IS NULL`** and **frozen after** | Changing the address after provisioning desynchronizes the login. This single rule removes a whole class of incident |
| T10 | `employees.email` duplicates Keycloak's login email, and **neither synchronizes the other** | Keycloak is the authority for login; `employees.email` is the HR record of the corporate address. Reading it from Keycloak instead would cost one Admin API call per row — `IdentityProvider` has no batch fetch. Declared as **G4**, not resolved |
| T11 | The contract's **current** row is the one whose date range contains today (`start_date <= CURRENT_DATE AND (end_date IS NULL OR end_date >= CURRENT_DATE)`) | The original draft defined "current" as `end_date IS NULL`, which conflates *open-ended* with *current*: a fixed-term contract with a known end date would silently stop being the current one, and a second "current" contract would be insertable. Separating the two facts is what makes T12 expressible |
| T12 | Overlap is prevented by an **exclusion constraint** on `daterange(start_date, end_date, '[)')` per employee, **`WHERE (enabled)`** | This is the correct invariant — "no two contracts of one employee cover the same day" — and it subsumes "at most one current contract" without a NULL-means-current convention. It **replaces** the partial unique index floated during the design round. The `WHERE (enabled)` predicate matters: it is a *partial* exclusion constraint, so a soft-deleted contract stops blocking its replacement, which a plain partial index would not do |
| T13 | Opening a new contract **closes the previous one on the day before the new start date**, in the same transaction | The service computes it and the exclusion constraint proves it: an overlapping write fails with 409 instead of silently creating two overlapping contracts. A new contract starting on or before the previous one's start date is a 400 |
| T14 | The salary is **warned about, never blocked**, when it falls outside the position×level band | The band is policy and the contract is the real agreement; blocking would make the band authoritative over the person's actual contract, and real life has legitimate exceptions. The UI shows the band next to the field and warns. This is a deliberate non-blocking check, not an omission |
| T15 | `employees` is company-scoped and the contract's position must belong to the **same company** as the employee | The database cannot express it without composite foreign keys, so it is a service-level check (G5) |
| T16 | The generated email is a **login identity, possibly not a real mailbox** | If the company does not control the domain, nothing will ever arrive. The repository has no SMTP configuration at all, so nothing is sent today; but if an invitation flow is ever added, the domain must be deliverable. Declared as **G6** |

## Verified exploration evidence

Read-only exploration ran on 2026-09-30 against `main @ 274c67f`. Anchors shared with
`hr-org-structure` (E1–E16 there) are not repeated; these are this record's own.

| # | Fact | Anchor |
| --- | --- | --- |
| F1 | **No HR model exists in any form.** A whole-word sweep for `empleado`, `sueldo`, `salario`, `departamento`, `puesto`, `contrato`, `nómina`, `employee`, `salary`, `department`, `position`, `contract`, `seniority` over the backend returns only prose and test fixtures | `life-control-api/src`; `scheduling/model/SchedulingActivity.java:23`, `V16:6-8` |
| F2 | **No person→company model exists.** The closest is `user_preferences`: `keycloak_user_id VARCHAR(36) NOT NULL UNIQUE` plus five nullable `company_*` columns, one row per user, created lazily, and written **only** by `ProfileService.updateProfile` with **no validation** beyond the FK | `V1__baseline_schema.sql:364-376`; `ProfileService.java:51-55, 96-100, 114-118` |
| F3 | Every existing person reference is a free-text `VARCHAR` holding a Keycloak `sub`, with no FK and no existence check | `V1:451` (`shifts`), `V1:474` (`sales_orders`), `V16:26`, `V18:36` |
| F4 | `companies` has **no domain column**: `company_key`, `company_name`, `rfc VARCHAR(13) NOT NULL UNIQUE`, `phone`, `email VARCHAR(255)` nullable, address both inline and via `address_id` | `V1__baseline_schema.sql:45-67` |
| F5 | `addresses` exists with the proven link pattern `@OneToOne(cascade = {PERSIST, MERGE}, fetch = LAZY)`, reused by `Company`, `CompanyStore` and `Supplier` | `V1:25-37`; `CompanyStore.java:30-34`; `Company.java:63-67` |
| F6 | Status resolution: `StatusValidator.requireStatusOfType` on the create path and `StatusRepository.findByTypeNameAndStatusName` for a pinned default; the seeded type families are `PURCHASE_ORDER`, `SALES_ORDER`, `GOODS_RECEIPT`, `APPOINTMENT` | `StatusValidator.java:16-26`; `StatusRepository.java:18-25`; `V3`, `V12:57-69`, `V18:53-77` |
| F7 | Seeding idiom: `INSERT … SELECT gen_random_uuid(), <literal>, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP WHERE NOT EXISTS (SELECT 1 … WHERE LOWER(col) = LOWER('<literal>'))` — natural-key guard, no hardcoded ids | `V3:11-12, 24-28`; `V12:57-69` |
| F8 | **No `CREATE EXTENSION` anywhere**, and no exclusion constraint, no `gist` index other than the GIN one on `products.attributes`. Dev and prod run `postgres:18.1`; integration tests run `postgres:16-alpine` — both support the constraint and the extension | `db/migration/**`; `docker/docker-compose.yml:15,47`; `docker/docker-compose.prod.yml:25,263`; `AbstractPostgresIntegrationTest` |
| F9 | **No partial index exists** (`CREATE INDEX … WHERE` returns zero matches), so T12's predicate is also the schema's first | `db/migration/**` |
| F10 | `DATE` columns are mapped to `LocalDate`; the only precedent is V17's `valid_from`/`valid_to`, which the migration itself documents as the schema's first `DATE` columns | `V17:36-37, 14-17`; `SchedulingAvailability.java:53-58` |
| F11 | The generated-number precedent is max+1 in Java with **no lock**, and its own record declares the race | `PurchaseOrderService.java:711-731`; `SalesOrderService.java:876-896`; `GoodsReceiptService.java:455-463` (the one that *is* protected, by the order lock) |
| F12 | The identity operations this record's data will feed already exist, and three are missing | `IdentityProvider.assignRoleToUser/removeRoleFromUser/getUserRoles/updateUser/createUser` (`KeycloakIdentityProvider.java:272, 294, 86, 50`); enum `RoleScope{REALM, CLIENT}`. **Missing**: group-membership operations, a batch user fetch, and the application client id as a configured property |
| F13 | The existing user-creation path generates a random password, marks it **non-temporary**, and returns only the id — so the password is discarded and the created user cannot log in | `UsersAdminService.java:56-73`; `CreateUserResponse` |

## Schema — `V20`

```sql
-- ============================================
-- V20 — Employee registry and contract history
-- ============================================
-- This is the schema's first real person->company model: every previous person reference is a
-- free-text Keycloak `sub` in a VARCHAR column with no FK and no existence check.
-- `employees` is the HR record and is NOT a login: `keycloak_user_id` is a nullable link to an
-- existing Keycloak account (only a realm `admin` can create one, and only when a person already
-- has an account), so an employee without an account is legal and an account without an employee
-- is legal too.
-- `employees.email` is generated from the person's names over `companies.email_domain`, and it is
-- simultaneously the Keycloak username. The rule is a one-way door: renaming an email is renaming
-- a login.
-- `employee_contracts` is the history, and the exclusion constraint below is the invariant that
-- matters: no two enabled contracts of one employee may cover the same day. It is a PARTIAL
-- exclusion constraint (`WHERE (enabled)`) so a soft-deleted contract stops blocking its
-- replacement. "Current contract" is the row whose range contains today, which is why `end_date`
-- does not mean "current" and why the original draft's definition was replaced.
-- This migration creates the first extension and the first exclusion constraint in the schema:
-- `btree_gist` supplies the `=` operator class for `uuid` inside a GiST index.
-- Foreign keys are unnamed, matching the baseline and the V9..V18 style.
-- ============================================

CREATE EXTENSION IF NOT EXISTS btree_gist;

-- The corporate email domain of a company: the address an employee identity is built on.
-- Nullable, and its absence fails the employee create path closed (a company without a domain
-- cannot generate an address; the operator types one instead).
ALTER TABLE companies ADD COLUMN email_domain VARCHAR(255);

CREATE TABLE employees (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL REFERENCES companies(id),
    employee_number VARCHAR(30) NOT NULL,
    first_name VARCHAR(100) NOT NULL,
    paternal_last_name VARCHAR(100) NOT NULL,
    maternal_last_name VARCHAR(100),
    email VARCHAR(255) NOT NULL,
    phone_number VARCHAR(50),
    birth_date DATE NOT NULL,
    hire_date DATE NOT NULL,
    termination_date DATE,
    address_id UUID REFERENCES addresses(id),
    employment_status_id UUID NOT NULL REFERENCES statuses(id),
    keycloak_user_id VARCHAR(36) UNIQUE,
    version BIGINT NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_employees_company_number UNIQUE (company_id, employee_number),
    CONSTRAINT uq_employees_company_email UNIQUE (company_id, email),
    CONSTRAINT ck_employees_termination_after_hire
        CHECK (termination_date IS NULL OR termination_date >= hire_date),
    CONSTRAINT ck_employees_birth_before_hire CHECK (birth_date < hire_date)
);
CREATE INDEX idx_employees_company_id ON employees(company_id);
CREATE INDEX idx_employees_employment_status_id ON employees(employment_status_id);
CREATE INDEX idx_employees_address_id ON employees(address_id);

CREATE TABLE employee_contracts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id UUID NOT NULL REFERENCES employees(id),
    position_id UUID NOT NULL REFERENCES positions(id),
    seniority_level_id UUID NOT NULL REFERENCES seniority_levels(id),
    contract_type VARCHAR(30) NOT NULL,
    monthly_salary DECIMAL(12,2) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_employee_contracts_salary CHECK (monthly_salary >= 0),
    CONSTRAINT ck_employee_contracts_dates CHECK (end_date IS NULL OR end_date >= start_date),
    CONSTRAINT ex_employee_contracts_no_overlap
        EXCLUDE USING gist (employee_id WITH =, daterange(start_date, end_date, '[)') WITH &&)
        WHERE (enabled)
);
CREATE INDEX idx_employee_contracts_employee_id ON employee_contracts(employee_id);
CREATE INDEX idx_employee_contracts_position_id ON employee_contracts(position_id);
CREATE INDEX idx_employee_contracts_seniority_level_id ON employee_contracts(seniority_level_id);

-- The EMPLOYEE_STATUS family, with the idempotent natural-key guard V3/V12/V18 use.
INSERT INTO status_types (id, status_type_name, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'EMPLOYEE_STATUS', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM status_types WHERE LOWER(status_type_name) = LOWER('EMPLOYEE_STATUS'));

-- Repeated for 'Active', 'Inactive', 'OnLeave' and 'Terminated'.
INSERT INTO statuses (id, status_name, status_type_id, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Active', st.id, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM status_types st
WHERE LOWER(st.status_type_name) = LOWER('EMPLOYEE_STATUS')
  AND NOT EXISTS (SELECT 1 FROM statuses s WHERE s.status_type_id = st.id AND LOWER(s.status_name) = LOWER('Active'));
```

`contract_type` is a `VARCHAR(30)` validated by a Java enum, following the
`inventory_movements.movement_type` + `MovementType` precedent — **no `CHECK`**, because this schema
contains none and the free-form-string-with-a-Java-enum is its established answer.

## API surface

| Method | Path | Roles |
| --- | --- | --- |
| `GET` | `/api/companies/{companyId}/employees?search=&statusId=&includeDisabled=` | `isAuthenticated()` |
| `GET` | `/api/companies/{companyId}/employees/{id}` | `isAuthenticated()` |
| `GET` | `/api/companies/{companyId}/employees/suggest-email?firstName=&paternalLastName=` | `isAuthenticated()` |
| `POST` | `/api/companies/{companyId}/employees` | `lc-admin`, `lc-employee` |
| `PUT` | `/api/companies/{companyId}/employees/{id}` | `lc-admin`, `lc-employee` |
| `DELETE` | `/api/companies/{companyId}/employees/{id}` | `lc-admin`, `lc-employee` |
| `PATCH` | `/api/companies/{companyId}/employees/{id}/enable` | `lc-admin`, `lc-employee` |
| `GET` | `/api/companies/{companyId}/employees/{employeeId}/contracts` | `isAuthenticated()` |
| `POST` | `/api/companies/{companyId}/employees/{employeeId}/contracts` | `lc-admin`, `lc-employee` |
| `PATCH` | `/api/companies/{companyId}/employees/{employeeId}/contracts/{id}/close` | `lc-admin`, `lc-employee` |

`PATCH …/close` takes an optional end date and defaults to today. **No contract `PUT` exists**: a
salary change is a new contract, which is the reason the history table exists at all.

`suggest-email` answers `email: null` with a reason when the company has no `email_domain`, so the
form can switch to manual entry instead of showing an error toast. The **write** path fails closed
with a 400 in that case (T8).

Roles follow `hr-org-structure` D2 for the catalogs. For the employee record this record proposes
**`lc-employee` (write) plus `lc-employee-read` (read)**, which is a deliberate departure: the
repository has no catalog-style `-read` pair, and the justification is that this table carries
**dates of birth and the contract history links to salaries**. `lc-employee-access` (identity
provisioning) is `employee-access-provisioning`'s, not this record's.

## Screens

| Route | Page | Content |
| --- | --- | --- |
| `/hr/employees` | `EmployeeList` | Employee number, full name, email, **position** (from the current contract), status, hire date; search; status filter; "show disabled"; create / edit / terminate. **Department and position are read through the current contract, never a column on the employee** (D4) |
| `/hr/employees/create`, `/hr/employees/edit/:id` | `EmployeeEdit` | Personal data (number, names, email, phone, birth date) and employment data (hire date, termination date, address, status). The **email is suggested live** from the names, editable while `keycloak_user_id IS NULL`, read-only after (T9). No company domain ⇒ "Configurá el dominio de correo de la empresa" and manual entry |
| `/hr/employees/:id` | `EmployeeDetail` | Header plus the **Contracts** section: the history table (position, level, type, salary, validity) with **"Nuevo contrato"** and **"Cerrar contrato vigente"** |

Components: `employee-form`, `contract-dialog`, `contract-history`. The dialog shows the effect
before saving ("Esto cierra el contrato vigente el <fecha>") and shows the position×level band next
to the salary field (T14).

**One existing screen changes**: `/companies/edit/:id` gains a **"Dominio de correo"** field, and
`CompanyResponse` gains `emailDomain`. Declared, because it is a change to a merged surface.

The employee detail's **Access** section (create the Keycloak user, apply the position's roles,
deactivate) belongs to `employee-access-provisioning`. This record does not build it and does not
claim it.

## Work units

| | Content | Sizing note |
| --- | --- | --- |
| **W1** | `companies.email_domain` + `employees` + the `EMPLOYEE_STATUS` seed + the backend CRUD + the **email generator** as a pure helper with its own spec (T6) | Declared **over** the 400-line budget. The generator's spec is the part that must be thorough: accents, `Locale.ROOT`, particles, hyphens and apostrophes, a single given name from several, empty results, and length caps |
| **W2** | `employee_contracts` + the contract endpoints + the close-the-previous rule (T13) + the exclusion constraint (T12) | Includes the integration test proving that an overlapping insert is refused by the database, and that a soft-deleted contract stops blocking |
| **W3** | `EmployeeList` + `EmployeeEdit` with the live suggestion, plus the companies form field | The suggestion is a read that reserves nothing (T8), and the spec must pin that the form falls back to manual entry without a domain |
| **W4** | `EmployeeDetail` + `contract-history` + `contract-dialog` | The dialog carries no `MatDialog` in the presentational children: the page opens it and reloads, following the scheduling domain's D71 |

Dependency order: **W1 → W2 → W3 → W4**. W1 must land before W3, because the form's suggestion and
the frozen-email rule both need the employee endpoints.

## Gaps

| # | Gap | Note |
| --- | --- | --- |
| G1 | These are company-scoped endpoints in a repository whose claim path is emitted by no code | Same blocker as `hr-org-structure` G1, closed by `company-scope-local-fallback`. **Blocking for merge, not for development** |
| G2 | No currency anywhere | `monthly_salary` carries the single implied currency, like every other amount in the schema |
| G3 | Salary bands have no effective dating | The band is current policy; the salary **history** is the contract table, which is why this gap is survivable here and not in payroll |
| G4 | `employees.email` duplicates Keycloak's login email and **nothing synchronizes them** | The rule is stated in T10. The alternative — reading the email from Keycloak — costs one Admin API call per row, because `IdentityProvider` has no batch fetch |
| G5 | Three invariants the database cannot express | The contract's position must belong to the employee's company; a contract cannot be opened for a `Terminated` employee; and the email's domain must match the company's configured one |
| G6 | The generated address may not be a real mailbox | And the repository has **no SMTP configuration**, so an invitation flow is not expressible today (F13 shows the existing path even discards the generated password) |
| G7 | No offboarding | Nobody revokes anything when an employee becomes `Terminated`. That is `employee-access-provisioning`'s deactivate step, and the HR record alone cannot close it |
| G8 | No store assignment | `employee-store-assignments` owns it. Note the consequence for the ordering: store-scoped roles are meaningless without it, because the parent levels are verified against claims |
| G9 | No part-time or hours field | `contract_type` distinguishes the legal form, not the workload. A `weekly_hours` column is deferred until payroll needs it |
| G10 | No payroll and no tax identifiers | `companies.rfc` exists but an employee has no `tax_id`/`social_security_number`. Deliberately omitted: they are personal data with a legal regime, and they are needed only when payroll is real work |
| G11 | `birth_date` is sensitive personal data | Read-gated by `lc-employee-read` (or the write role). Whoever holds that role reads every date of birth in the company — that is the intended trade for the read/write split this record proposes |

## Cross-record dependencies

| Record | Relation |
| --- | --- |
| `hr-org-structure` | **Prerequisite**: every contract points at a `positions` row. Same branch, and V20 follows V19 |
| `company-scope-local-fallback` | **Blocking** for this record's merge (G1) |
| `employee-store-assignments` | Depends on this record (it needs the employee to assign) |
| `employee-access-provisioning` | Depends on this record (email + `keycloak_user_id`), on `hr-org-structure` (`position_roles`) and on `employee-store-assignments` (the scope the granted roles need) |
| `scheduling-calendar` | Merged and paused. Its free-text `user_id` is what this record replaces |

## Task log

- [ ] W1 — `companies.email_domain`, `employees`, `EMPLOYEE_STATUS` seed, backend CRUD, email generator
- [ ] W2 — `employee_contracts`, contract endpoints, close-the-previous rule, exclusion constraint
- [ ] W3 — `EmployeeList`, `EmployeeEdit` with the live suggestion, companies form field
- [ ] W4 — `EmployeeDetail`, `contract-history`, `contract-dialog`

## Deferred and blocking

- **Blocked on `company-scope-local-fallback`** for merge (G1). Development is not blocked.
- **Blocked on `btree_gist` being creatable**: the exclusion constraint needs the extension, and the
  migration creates it. If a target environment forbids extensions, the documented fallback is a
  partial unique index on the open-ended contract plus a service-level overlap check — strictly
  weaker, and the reason this is stated rather than assumed.
- **Deferred**: payroll and tax identifiers (G10), workload hours (G9), offboarding (G7).

## Evidence log

| Date | Evidence |
| --- | --- |
| 2026-09-30 | Design closed in a working session against `main @ 274c67f`. Own anchors F1–F13 read from the working tree; the shared convention anchors are E1–E16 of `hr-org-structure`. No code written. |
| 2026-10-01 | **D6 decided** while designing the membership bridge (`company-scope-local-fallback`, its D3): the user described the flow as "assign the store and the position or positions when the contract is activated" and, on review, chose the singular. V20 therefore stays exactly as designed — the rejection cost nothing because the migration has no source line yet. The same session recorded, in that record, why the store stays **out** of the contract (a transfer must not close a legal contract, T13) and why the store assignment becomes the load-bearing row of the flow. No source written. |
