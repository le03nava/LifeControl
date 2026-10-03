# ODD feature: employee-registry

**Status**: **W1a and W1b are implemented** (V20 with `companies.email_domain` and `employees`, the
`Employee` model, `EmployeeRepository` and their tests; and the employee CRUD — the seven employee
routes, `EmployeeService`, `EmployeeEmailGenerator` and their tests) — **W2–W4 remain below**.
W1b's scope and the decisions it could not inherit were settled on 2026-10-03, before its first
source line (`D7` roles, `D8` a company without an `email_domain`, `D9` one slice with a size
exception), and the decisions its own implementation forced are `T18`–`T26` and `G12`/`G13` below.
This header makes no claim about branch, push or PR state; see the evidence log.
**Created**: 2026-09-30 · **Risk**: **high** — the first real person→company model in the schema,
a generated identity whose rule is a one-way door, and the repository's first exclusion constraint
(which needs its first PostgreSQL extension). No existing contract changes beyond one added column
on `companies`.
**Repository**: LifeControl — spans `life-control-api/**` (Spring Boot, Java 21, PostgreSQL 18.1 +
Flyway) and `life-control-app-angular/**` (Angular 20.3 + Material/CDK 20). No gateway change.
**Migration**: **`V20`** — it adds `companies.email_domain`, creates `employees` and seeds the
`EMPLOYEE_STATUS` family. `V19` belongs to `hr-org-structure`, which merged on its own branch, so
**this record no longer shares a branch with it**; the two migrations were designed together only
because every contract row points at a `positions` row that V19 creates. **`V21` is not reserved
here:** it stays claimed by `employee-store-assignments` (still unwritten), and `employee_contracts`,
its exclusion constraint and the `btree_gist` extension move to **W2's own migration**, whose number
is the next free one when it lands.
**Work-unit split**: **declared 2026-10-02, before the first source line.** `W1` was measured over
budget, so **the split point is inside `W1`** — `W1a` (schema, model, repository) and `W1b` (the CRUD
service and the email generator) — superseding the earlier note that the split belonged between this
record and `hr-org-structure`.
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
| D3 | The provisioned password is **temporary, with a forced change** | **Superseded on 2026-10-01 by `employee-access-provisioning`'s O1**: the person receives no password at all — the account is created with `UPDATE_PASSWORD` and `VERIFY_EMAIL` and Keycloak emails an activation link, so no secret travels through either application. The decision belongs to `hr-org-structure`'s **D10**, which carries the same annotation; the original "D10 there" pointed at a row `employee-access-provisioning` does not have (it has O1–O5). This record only guarantees that `employees.email` exists and is frozen before provisioning can happen |
| D4 | `employees` carries **no position and no department** — both come from the current contract | A person's position is a fact about a contract, not about a person. Duplicating it on the employee would be a second truth, desynchronized by the first promotion |
| D5 | `employee_number` is **typed by the operator**, never generated | It comes from payroll. The repository's generated numbers (`PO-…`, `SO-…`) are computed as max+1 **without a lock** and their record already declares that race; it is not inherited here. The per-company `UNIQUE` constraint is the real guarantee |
| D6 | A person holds **one position**: `employee_contracts.position_id` stays a single required column. A plural model ("the position or positions") was considered on 2026-10-01 and **rejected** by the user | Not a preference — a consequence worth stating: with the exclusion constraint forbidding two overlapping contracts of one employee (T12) and a new contract closing the previous one the day before (T13), **"two positions at once" is not expressible without a child table**. A promotion is a new contract, and the role template a person receives is the union of exactly one position. If multi-position is ever needed it arrives as `employee_contract_positions`, never as two contracts |
| D7 | **Roles, settled 2026-10-03 because the record contradicted itself**: the reads are `isAuthenticated()` and the writes are `lc-admin` + `lc-employee` — exactly the API table below and **not** the `lc-employee-read` pair the prose two paragraphs under it proposed. `lc-employee` is a **functional** role, declared in `Roles.java` next to `lc-admin` and **deliberately absent from `ScopeLevel` and `GrantablePositionRoles`**: employee-record write is not a tenancy level and must not be delegable by a position, which is the same argument `Roles.SENIORITY_LEVEL` already carries | The `-read` half of the proposal is **withdrawn, not deferred**. The honest consequence, stated because G11 rests on it: **`birth_date` is now read-gated by company scope alone** (`verifyCompanyAccess`) plus `isAuthenticated()`, so any authenticated caller with the company claim reads every date of birth in the company — the trade the `-read` pair existed to prevent. Accepted against the alternative because every other company-scoped read in this repository is already `isAuthenticated()`, and the `-read` pair would have been a first-with-no-precedent that buys nothing while G1 means no caller reaches these endpoints by claim yet. `G11` is annotated accordingly. `lc-employee-access` stays `employee-access-provisioning`'s and is not created here |
| D8 | **A company without `email_domain` fails the employee write path closed with a 400**, settled 2026-10-03 because the record said two opposite things: the V20 comment says "the operator types one instead" and the API table says the write path fails closed | The V20 comment is the stale half (written 2026-09-30, before O1 turned the address into a real login) and **it cannot be corrected in place**: `V20` is an applied Flyway migration whose checksum a target environment has already recorded, so editing it would break every environment that ran it. The correction lives here and in the code path instead, and the migration keeps the sentence it shipped with. The rule is the coherent one: `G5` requires the address's domain to equal the company's configured domain, and with no configured domain there is nothing to equal — so there is no "manual entry" mode, and the W3 screen's fallback becomes a blocking instruction ("configurá el dominio de correo de la empresa") rather than an alternative |
| D9 | **W1b stays one slice and takes a size exception**, settled 2026-10-03 on the W1a precedent (~805 lines over the 400-line budget even after the split) | The endpoints and the generator are one diff on purpose: T7's collision retry **is** the contact point between them, and a reviewer reading the retry needs the generator's suffixing in the same view. Declared as an exception rather than split into a `W1b-1`/`W1b-2` pair whose boundary would fall inside a single method |

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
| T17 | **`keycloak_user_id` is never operator-typable.** The column stays nullable and unique, but it is written **only** by the access-provisioning flow (create the account, or link the existing one); no `EmployeeRequest` field and no edit-form control accepts it, and it is read-only wherever it is displayed | It is the security precondition of the auto-apply policy `company-scope-local-fallback` closed as its **D6(b)**: with auto-apply, whoever can create an employee + a contract + a store assignment can cause the system to grant the roles of that position. If the same operator could also point the record at an arbitrary Keycloak account — **their own included** — the system would grant them the roles of a position they chose, bounded only by D7's allowlist. Making the column unwritable from the record path removes the path without changing the schema |

## Decisions settled while implementing W1b (mine, technical — 2026-10-03)

Five of the record's `T`-decisions were amended and four extended during W1b, all before its
commit. They are recorded here rather than rewritten into the rows above, because the amended row is
the reasoning that produced the code, and a reader is owed both.

| # | Decision | Why |
| --- | --- | --- |
| T18 | The generator's caps are **40 characters per part** and **64 for the joined local part**, and the collision ceiling is **20 attempts** (the plain candidate plus suffixes 2..20) | The record fixed neither number ("parts are length-capped", "exhausting the attempts is a 409"); both are now pinned by tests instead of merely described |
| T19 | **Amends T6.** The empty-local-part fallback runs through the same sanitizing rule as the names (`localPartFromToken`: NFD plus `\p{M}` strip, `Locale.ROOT`, everything outside `[a-z0-9]` removed, same part cap), and when that fallback is empty too the write is a **400** instead of reaching the database | Independent verification found the fallback was only trimmed and lowercased, so an HTTP-reachable `employeeNumber` of `a@b` or `EMP 001` stored `a@b@<domain>` / `emp 001@<domain>` as the **login username**; a number of `!!!` in turn produced a null address and a raw constraint 409 |
| T20 | **Amends T6.** `EmployeeRequest.email` carries `@Email`; `null` and empty stay valid and still mean "generate" | Every other email field in the repository carries it (`CompanyRequest`, `ProfileUpdateRequest`, `CreateUserRequest`, `SupplierRequest`), and without it `@<domain>` and `a@b@<domain>` were accepted as a login identity |
| T21 | **Amends T7 — the mechanism only, not the goal.** There is **no retry after a unique violation**: the service pre-checks candidates with `existsByCompanyIdAndEmail` and `UNIQUE (company_id, email)` stays the final authority, so a lost race is the repository's ordinary 409 | In PostgreSQL the **first** constraint violation aborts the whole transaction, so no later statement of that transaction can run: an in-transaction retry is not expressible without savepoints or a per-attempt transaction, and both are worse than accepting the race this repository already accepts in its max+1 numbering |
| T22 | **Amends T8.** The suggest endpoint never throws: it answers a third reason, **`NO_FREE_CANDIDATE`**, when all 20 candidates are taken. The write path still throws | A GET documented as 200 must not return 409, and the `reason` field exists so the form is told instead of being shown an error |
| T23 | **Amends T9.** When the stored row already has a `keycloakUserId` **and** the request leaves the email blank, the stored address is kept verbatim instead of being re-resolved | The frozen check compared a freshly resolved candidate against the stored value, and the candidate depends on which suffixes other rows currently occupy: a **no-op update of a provisioned employee could 409** because an unrelated row had freed the plain candidate |
| T24 | The requested email is **pre-checked for uniqueness on both paths** (create and update, excluding self), throwing `DuplicateEmployeeException` with the **full address** | Otherwise a duplicate on the generated path named a field while a duplicate on the explicit path produced the advice's generic text — two different messages for one rule |
| T25 | The list is ordered **lexicographically by `employee_number`** (`EMP-9` sorts after `EMP-10`) | Deterministic, and it invents no numeric semantics the column does not have. The test pins the lexicographic order with values that discriminate it from a numeric one, so the pinned rule is the real one |
| T26 | An `addressId` that does not resolve is a **400** | No sibling service resolves an address by id and no `AddressNotFoundException` exists; a 400 with a message beats the foreign-key violation the write would otherwise produce |

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
    company_id UUID NOT NULL REFERENCES companies(id), -- the single-valued company is the DECIDED ceiling of company-scope-local-fallback's D4 = no (2026-10-02), not an accident of the design
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

Roles follow `hr-org-structure` D2 for the catalogs. **Closed as `D7` on 2026-10-03**: for the
employee record the reads are `isAuthenticated()` and the writes are `lc-admin` + `lc-employee`. This
paragraph used to propose **`lc-employee` (write) plus `lc-employee-read` (read)** as a deliberate
departure justified by this table carrying **dates of birth and the contract history links to
salaries**; **the `-read` half is withdrawn** (its consequence for `birth_date` is recorded in `D7`
and `G11`), and `lc-employee` follows `Roles.SENIORITY_LEVEL`'s precedent of a functional role held
deliberately outside `ScopeLevel`. `lc-employee-access` (identity provisioning) is
`employee-access-provisioning`'s, not this record's.

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
| **W1a** | `companies.email_domain` + `employees` + the `EMPLOYEE_STATUS` seed + the `Employee` model + `EmployeeRepository` + the migration and persistence tests. **Scope settled 2026-10-02**: `V20` carries exactly this and **not** `employee_contracts` | Measured at **~805 added lines across seven files**, so the split did **not** bring it under the 400-line budget: it remains a **size exception**, and the reviewer gets the migration, the model and their tests in one diff. `W1b` carries the rest of the original `W1` |
| **W1b** | The **employee** backend CRUD and the **email generator** as a pure helper with its own spec (T6). **Scope settled 2026-10-03**: the seven employee routes of the API table and nothing else — `EmployeeRequest`/`EmployeeResponse`/`EmployeeEmailSuggestionResponse`, `EmployeeService`, `EmployeeController`, `EmployeeNotFoundException`/`DuplicateEmployeeException`, `EmployeeEmailGenerator`, the `EmployeeRepository` list query and the `Roles.EMPLOYEE` constant; the **four contract routes stay in W2**, which is also why `EmployeeResponse` carries **no position and no department** (D4 reads them through the current contract, and that table does not exist yet — a placeholder field would be a claim a reviewer has to check). The list is an unpaginated `List<EmployeeResponse>` with `search` + `statusId` + `includeDisabled`, following every other company-scoped list in the repository; it is the repository's first company-scoped list that filters, so the query itself has no precedent to copy | The generator's spec is the part that must be thorough: accents, `Locale.ROOT`, particles, hyphens and apostrophes, a single given name from several, empty results, and length caps. **`D9`**: one slice, size exception declared to the reviewer. **Declared, not built here**: `CompanyResponse.emailDomain` and the companies form field are W3's |
| **W2** | `employee_contracts` + **its own migration** + the contract endpoints + the close-the-previous rule (T13) + the exclusion constraint (T12) and the `btree_gist` extension it needs | **Revised 2026-10-02**: the table, the constraint and the extension moved out of `V20` into W2's own migration, because a single Flyway file cannot be half-written across two work units; the number is the next free one at that point. Includes the integration test proving that an overlapping insert is refused by the database, and that a soft-deleted contract stops blocking |
| **W3** | `EmployeeList` + `EmployeeEdit` with the live suggestion, plus the companies form field | The suggestion is a read that reserves nothing (T8), and the spec must pin that the form falls back to manual entry without a domain |
| **W4** | `EmployeeDetail` + `contract-history` + `contract-dialog` | The dialog carries no `MatDialog` in the presentational children: the page opens it and reloads, following the scheduling domain's D71 |

Dependency order: **W1a → W1b → W2 → W3 → W4**. W1b must land before W3, because the form's
suggestion and the frozen-email rule both need the employee endpoints.

## Gaps

| # | Gap | Note |
| --- | --- | --- |
| G1 | These are company-scoped endpoints in a repository whose claim path is emitted by no code | Same blocker as `hr-org-structure` G1, closed by `company-scope-local-fallback`. **Blocking for merge, not for development** |
| G2 | No currency anywhere | `monthly_salary` carries the single implied currency, like every other amount in the schema |
| G3 | Salary bands have no effective dating | The band is current policy; the salary **history** is the contract table, which is why this gap is survivable here and not in payroll |
| G4 | `employees.email` duplicates Keycloak's login email and **nothing synchronizes them** | The rule is stated in T10. The alternative — reading the email from Keycloak — costs one Admin API call per row, because `IdentityProvider` has no batch fetch |
| G5 | Three invariants the database cannot express | The contract's position must belong to the employee's company; a contract cannot be opened for a `Terminated` employee; and the email's domain must match the company's configured one |
| G6 | The generated address may not be a real mailbox | And the repository has **no SMTP configuration**, so an invitation flow is not expressible today (F13 shows the existing path even discards the generated password). **Annotated on 2026-10-01**: `employee-access-provisioning`'s **O1** answered both halves — it puts the invitation in scope as its **W6** (realm SMTP, the Frontend URL, `sendActionsEmail`, a dev mail container), and it promotes this gap to a **hard requirement** there (**G9**), because with an activation link an undeliverable address means the person cannot log in at all |
| G7 | No offboarding | Nobody revokes anything when an employee becomes `Terminated`. That is `employee-access-provisioning`'s deactivate step, and the HR record alone cannot close it |
| G8 | No store assignment | `employee-store-assignments` owns it. Note the consequence for the ordering: store-scoped roles are meaningless without it, because the parent levels are verified against claims |
| G9 | No part-time or hours field | `contract_type` distinguishes the legal form, not the workload. A `weekly_hours` column is deferred until payroll needs it |
| G10 | No payroll and no tax identifiers | `companies.rfc` exists but an employee has no `tax_id`/`social_security_number`. Deliberately omitted: they are personal data with a legal regime, and they are needed only when payroll is real work |
| G11 | `birth_date` is sensitive personal data | **Revised 2026-10-03 by `D7`**: read-gated by company scope (`verifyCompanyAccess`) plus `isAuthenticated()`, **not** by a `lc-employee-read` role, because that role was withdrawn. Anyone who passes the company check reads every date of birth in the company. Recorded as the accepted state, not as an oversight; a dedicated read role is the change that would close it |
| G12 | A **soft-deleted row keeps both natural keys.** `uq_employees_company_number` and `uq_employees_company_email` carry no `WHERE enabled` predicate, and the existence probes that guard them are unfiltered | Intended — the row is not gone — and now **pinned by two tests** so it is a decision rather than a surprise: after a soft delete the same `employee_number` is a 409 ("already exists for this company") while the default list shows no such employee, and the same address is silently suffixed to `…2@`. The recovery path is `PATCH …/{id}/enable`, and the service javadoc says so |
| G13 | The list response reads `address.getId()` on a **lazy owning-side `@OneToOne`**, so the list may issue one extra SELECT per row | Declared, not measured: pinning it needs a query-count assertion this slice does not have. `status` is fetched explicitly in the list query; `address` is not |

## Cross-record dependencies

| Record | Relation |
| --- | --- |
| `hr-org-structure` | **Prerequisite**: every contract points at a `positions` row. `V19` is merged and `V20` follows it; the shared branch this row used to claim was dropped on 2026-10-02 |
| `company-scope-local-fallback` | **Blocking** for this record's merge (G1) |
| `employee-store-assignments` | Depends on this record (it needs the employee to assign) |
| `employee-access-provisioning` | Depends on this record (email + `keycloak_user_id`), on `hr-org-structure` (`position_roles`) and on `employee-store-assignments` (the scope the granted roles need) |
| `scheduling-calendar` | Merged and paused. Its free-text `user_id` is what this record replaces |

## Task log

- [x] W1a — `companies.email_domain`, `employees`, the `EMPLOYEE_STATUS` seed, the `Employee` model
      and `EmployeeRepository` (implemented; the evidence log carries the measurements)
- [x] W1b — the backend CRUD service and the email generator (implemented; the evidence log carries
      the measurements, the amendments `T18`–`T26` and the gaps `G12`/`G13`)
- [ ] W2 — `employee_contracts`, contract endpoints, close-the-previous rule, exclusion constraint
- [ ] W3 — `EmployeeList`, `EmployeeEdit` with the live suggestion, companies form field
- [ ] W4 — `EmployeeDetail`, `contract-history`, `contract-dialog`

## Deferred and blocking

- **Blocked on `company-scope-local-fallback`** for merge (G1). Development is not blocked.
- **Blocked on `btree_gist` being creatable**: the exclusion constraint needs the extension, and
  **W2's own migration** creates it (it left `V20` on 2026-10-02). If a target environment forbids extensions, the documented fallback is a
  partial unique index on the open-ended contract plus a service-level overlap check — strictly
  weaker, and the reason this is stated rather than assumed.
- **Deferred**: payroll and tax identifiers (G10), workload hours (G9), offboarding (G7).

## Evidence log

| Date | Evidence |
| --- | --- |
| 2026-09-30 | Design closed in a working session against `main @ 274c67f`. Own anchors F1–F13 read from the working tree; the shared convention anchors are E1–E16 of `hr-org-structure`. No code written. |
| 2026-10-01 | **D6 decided** while designing the membership bridge (`company-scope-local-fallback`, its D3): the user described the flow as "assign the store and the position or positions when the contract is activated" and, on review, chose the singular. V20 therefore stays exactly as designed — the rejection cost nothing because the migration has no source line yet. The same session recorded, in that record, why the store stays **out** of the contract (a transfer must not close a legal contract, T13) and why the store assignment becomes the load-bearing row of the flow. No source written. |
| 2026-10-01 | **T17 added** as the security precondition of the auto-apply policy that `company-scope-local-fallback` closed as its D6(b): `keycloak_user_id` is written only by access provisioning, never by the employee record path. The reasoning is in that record's `The asynchronous boundary`, and it is a path removal, not a schema change — the column keeps its `UNIQUE` and stays nullable. The same pass added the note that the store stays outside the contract and that the position stays singular (**D6** above). No source written. |
| 2026-10-02 | **V20's single `company_id` is now sanctioned by `company-scope-local-fallback`'s D4 = no rather than merely assumed.** D4 closed on 2026-10-02 as **no — one company per person**, so the `NOT NULL` company column and the `UNIQUE (company_id, employee_number)` / `UNIQUE (company_id, email)` pair are the **decided** ceiling of the model, not an accident of the design; the one-line SQL comment on the column records it where a reader of the migration will see it, and V20's shape is otherwise unchanged. **No source line was written** |
| 2026-10-02 | **W1a implemented from `main @ 6072d65`, and the premise that made it the first step turned out to be a defect in another record.** `employee-access-provisioning` assumed `V19`–`V21` preceded it and that its `W1` was buildable today; measured against the tree, **`V19` was the head, `V20` and `V21` did not exist, and `employees` did not exist**, so that record's `REFERENCES employees(id)` was unapplicable and **this** record's W1 was the real prerequisite. **Scope settled before the first line**: `V20` carries `companies.email_domain` + `employees` + the `EMPLOYEE_STATUS` seed **only**, with `employee_contracts`, the exclusion constraint and the `btree_gist` extension moved to **W2's own migration** — one Flyway file cannot be half-written across two work units — and **`V21` deliberately left claimed by `employee-store-assignments`** so no other record renumbers. **The split was chosen by the user the same day**: `W1` → **`W1a`** (schema, model, repository) + **`W1b`** (CRUD and generator). **Two consequences the slice forced into the open**: adding `V20` broke **two** suites that pin the Flyway head (`HrSchemaMigrationIntegrationTest` and `GoodsReceiptIntegrationTest`), both re-pinned to `20` with method and display names renamed so no `V19` claim survives; and `spring.jpa.hibernate.ddl-auto=validate` means **an entity without its table breaks every integration test in the module**, so the migration could not be deferred behind the model. **Evidence**: tests first with the RED observed (`relation "employees" does not exist`, and both head pins reading `19`), then GREEN; the full API suite at **738 reports / 2710 tests / 0 failures / 0 errors / 0 skipped** — the baseline plus exactly this class's ten tests; `spotlessCheck` and `spotbugsMain` green (both returned `UP-TO-DATE` on the final run, which is cached rather than fresh evidence); **seven files changed and nothing else**, with the tree byte-identical before and after the verification run. An independent verification pass **refuted nothing** in the migration, the entity mapping or the T17 path removal, but found **two real test-value holes and one false claim**, all fixed before the commit: the boundary case now inserts `birth_date = hire_date` (the equality its display name always claimed, and the only input that discriminates `<` from `<=`), the class javadoc now says the `@BeforeEach` deletes the **whole** `employees` table, and it now says the seed guard's idempotency is **inherited from the V3/V12/V18 idiom rather than exercised by any test in this repository**. **Declared, not fixed**: the seed guard is pinned nowhere in the repository (a measurement, not an omission here); foreign-key enforcement, column defaults and the `address` association are not pinned by this suite; and W1a is **~805 added lines across seven files, over the 400-line budget even after the split**, so a size exception is owed to the reviewer. **No contract table, no extension, and no source line of any other record was written** |
| 2026-10-03 | **W1b's scope was settled before its first source line, and settling it pulled three contradictions out of this record.** Read-only exploration ran against `main @ e66d5a4` (W1a merged as PR #229). **Measured**: `EmployeeService`, `EmployeeController`, its DTOs, its exceptions and any email helper **do not exist**; `EmployeeRepository` carries exactly three derived methods (`findByIdAndCompanyId`, `existsByCompanyIdAndEmail`, `existsByCompanyIdAndEmployeeNumber`) and **no list query**; `lc-employee` appears **nowhere** outside this record, neither in `Roles.java` nor in `keycloak-setup.sh`'s client-role loop; **no company-scoped endpoint in the repository combines `search` + `statusId` + `includeDisabled`**, and no company-scoped list is paginated, so the list query has **no precedent to copy**; `Employee` has no public setter for `keycloakUserId` (T17 already implemented by omission) and `Auditable` stamps its timestamps through JPA callbacks, not the DB defaults. **Three contradictions found and closed by the user the same day** — `D7` (the API table said `isAuthenticated()` reads while the prose two paragraphs below proposed an `lc-employee-read` pair; the table won and the pair is withdrawn with its consequence for `birth_date` recorded, `Roles.SENIORITY_LEVEL` being the existing precedent for a functional role held outside `ScopeLevel`, which keeps `CurrentUserContextTest:680` and `GrantablePositionRolesTest` green because nothing enters a scope level); `D8` (the V20 comment says a company without a domain lets the operator type one, the API table says the write fails closed — closed as **fail closed**, with the note that the migration comment **cannot be corrected in place** because `V20` is an applied Flyway migration whose checksum a target environment already recorded, so the correction lives in this record and in the code path); and `D9` (**one slice, size exception**, on the W1a precedent). **One implementation trap identified before the writer ran**: T7's retry after a unique violation cannot live inside a single PostgreSQL transaction — the first violation aborts it, so every later statement in that transaction fails — which means the retry needs its own transaction boundary per attempt or a pre-existing-candidate check with the `UNIQUE` as the final authority, and either form owes a test that proves a collision produces `juan.perez2@…` rather than a 409. **No source line was written by this act**: the settlement itself is the artifact. |
| 2026-10-03 | **W1b implemented and verified — the seven employee routes plus the pure email generator, committed as `ed58b64`.** **What it adds**: `EmployeeController` (list with `search`/`statusId`/`includeDisabled`, `GET /{id}`, `GET /suggest-email`, `POST`, `PUT`, `DELETE` soft, `PATCH /{id}/enable`), `EmployeeService`, the pure `EmployeeEmailGenerator`, `EmployeeRequest`/`EmployeeResponse`/`EmployeeEmailSuggestionResponse`, `EmployeeNotFoundException`/`DuplicateEmployeeException`/`EmployeeEmailFrozenException`, the `EmployeeRepository` list query with `cast(:search as string)` and the two `…AndIdNot` probes, and `Roles.EMPLOYEE` plus its literal in `keycloak-setup.sh`'s client-role loop. **The four contract routes are not here** (W2), there is **no migration** (the Flyway head stays `20` and both head pins still read it), `CompanyResponse` is untouched (W3), and `keycloak_user_id` is not writable from any of these paths (T17). **Evidence**: tests were written first, with the RED observed as a compilation failure against the not-yet-written helper and service, then GREEN. The **full API suite is 769 reports / 2829 tests / 0 failures / 0 errors / 0 skipped** — `+31` reports and `+119` tests over the 2710 baseline, closing exactly against the six new/changed classes (CrudIntegration 14, ControllerSecurity 19, Controller 21, EmailGenerator 23, ScopedReadCache 1, Service 41) with no pre-existing class changing count; `spotlessCheck` forced with `--rerun-tasks` (3 executed, fresh) and `spotbugsMain` executed with **0 `BugInstance`**; `bash -n` clean and ShellCheck (`koalaman/shellcheck:stable`, `--severity=warning`, the workflow's own invocation) exit 0 over all 9 scripts; the offline `keycloak-setup.test.sh` harness **22 passed / 0 failed**; `CurrentUserContextTest` and `GrantablePositionRolesTest` green, which is the measurement that `lc-employee` stayed out of `ScopeLevel` and the frozen allowlist. **An independent read-only verification pass ran before the commit** and confirmed all ten conformance points (no hole in T9, D8 fails closed, G5, T17, D7, D4, the list predicates) while finding **fourteen substantiated issues, all fourteen adjudicated**: the fallback that stored `a@b@<domain>` as a login (fixed, T19), the missing `@Email` (T20), the no-op update of a provisioned employee that could 409 (T23), the duplicate-email message that differed by path (T24), the GET that could 409 (T22), the unpinned 20-attempt ceiling, the missing D8-with-explicit-email and update-path status assertions, the unpinned company predicate where a cross-tenant leak would have passed (a second company now exists in the fixture), the lexicographic ordering pinned with discriminating values (T25), an unreachable trailing-dot branch removed, the uncached-reads claim now pinned by `EmployeeServiceScopedReadCacheTest` as its four siblings are, two false javadocs corrected (the repository's withdrawn-retry claim and the schema test's "no other suite writes `employees`"), and the soft-delete natural-key consequence now pinned by two tests and recorded as **G12**. Three loose ends the writer raised were closed by the parent: `NO_FREE_CANDIDATE` is documented in the DTO and the controller's `@Operation`, and a request whose names **and** employee number carry no `[a-z0-9]` character is a 400 instead of a null-address constraint failure. **Declared, not fixed**: `@Email` rejects a whitespace-only email, so the service's blank branch is reachable only with `null` or `""`; the 400 for an underivable local part and the null-email path are pinned at the service level and generically in the handler, not end to end over HTTP; and **G13** records that `address` is read on a lazy owning-side `@OneToOne`, so the list may issue one SELECT per row. **Size, measured over the commit**: 19 files, `+3388/−13 = 3401` lines — **944 in 11 production files, 2455 in 7 test files, 2 in the shell script** — which is the size exception `D9` already declared, owed to the reviewer at these numbers and **not** the ~805 of W1a. **No source line of another record was written**, and W2–W4 remain. |
| 2026-10-03 | **The native review was declined for this candidate, and the record says so instead of leaving it implicit.** The preflight ran twice: the first call, made against the session's own working directory (the anchor clone, clean on `main`), correctly reported **no candidate** — the work at that moment existed only in the worktree — and the second, with `workspaceRoot` pointed at the worktree, returned `ready` over **20 paths**, base tree `7b285d83…` (the V20 merge) and candidate tree `110809bb…`, at risk **high**, on the evidence *security in `common/security/Roles.java`* and *shell scripting in `docker/scripts/keycloak-setup.sh`*. The offered START route (`--base-ref=e66d5a44…`, `--committed-only=true`, `--consent=relay`) was then invoked, and the **interactive host resolved the consent envelope before the model saw it**: `action: declined`, `consent: declined_this_candidate`, **`lineage_created: false`, `mutation_performed: false`**, no lenses selected and an empty binding list. The consequence, stated plainly: **no lens ran, no lineage exists, and the correction budget was zero** — the candidate is unreviewed, and this is a **candidate-scoped decline, not the kill switch**. Delivery therefore follows the repository's ordinary policy, and the evidence that supports it is the implementation's own verification plus the independent read-only verification recorded in the row above. Deliberately **not** claimed here: any PR or merge state — a delivery row belongs to the act of delivering, and the header makes no claim about it either. |
