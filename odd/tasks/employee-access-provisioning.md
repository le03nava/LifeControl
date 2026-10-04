# ODD feature: employee-access-provisioning

**Status**: **implemented in part — W1a landed**: `V22` with the two tables, the two entities, the two
repositories and the schema test, committed as `92026ac` on 2026-10-04; the remaining work is **W1b and
W2–W6** below. The **native review** of this candidate closed **`approved`** on 2026-10-04 with four
advisory findings and **no correction opened**; see the evidence log. **O1–O5 are closed** (2026-10-01): the activation link, the store-scoped auto-apply rule,
no self-approval, automatic revocation, and no read pair — **O1 moved SMTP into scope** (W6), so this record
is also the one that configures the invitation channel. This header makes no claim about push or PR state;
see the evidence log.
**Created**: 2026-10-01 · **Risk**: **high** — this is the only record in the chain that **writes to
another system**: it creates accounts, grants business roles and materialises the tenant claims. It is
also the convergence point of five records, and the one place where a mistake is an authorization defect
rather than a data defect.
**Repository**: LifeControl — `life-control-api/**` (Spring Boot, Java 21, PostgreSQL 18.1 + Flyway) and
`life-control-app-angular/**` (Angular 20.3 + Material/CDK 20). No gateway change.
**Migration**: **the next free number when this lands — not `V22` by right.** Measured on 2026-10-04 immediately
before writing it: `V19` is merged (`hr-org-structure`); **`V20` carries `employee-registry`'s W1a**, which
creates the `employees` table this record's `employee_id` foreign key points at; and **`V21` is
`V21__employee_contracts.sql`, taken by `employee-registry`'s W2** — **not** by `employee-store-assignments`,
which is the claim this line carried until it was measured. The next free number is therefore **`V22`**, and
`employee-store-assignments` takes whatever is free when it lands. The schema below keeps its original `V22`
label, which now **coincides with the measured head** instead of being a reservation.
**Sequencing, corrected 2026-10-02**: this record **does not share a branch** with the other three
employee records — `hr-org-structure` merged on its own branch, and `employee-registry`,
`employee-store-assignments` and this one are each **their own unit of work**. W1a is written on
`feat/employee-access-provisioning`; see the evidence log.
**Requested by**: the user — the membership flow of 2026-10-01 ("cuando se active [el contrato] se debe de
asignar la tienda y la posición … y en base a la relación de posiciones con roles se genera el usuario en
keycloak con los roles y atributos"). This record implements steps 3–5 of that flow.

## Origin

Four records named this one, each from its own side:

- `hr-org-structure` (**D6, D7, D8, D10**): the role **template** (`position_roles`) is a provisioning seed
  applied once on an explicit action, the grantable set is an **allowlist** from which `lc-admin` is
  absent, identity provisioning gets its **own role** (`lc-employee-access`) separate from editing HR data,
  and the provisioned password is **temporary with a forced change**.
- `employee-registry` (**G7**, **T17**): nobody revokes anything when an employee becomes `Terminated`, and
  `keycloak_user_id` must never be operator-typable — which is the security precondition of the auto-apply
  policy below.
- `employee-store-assignments` (**T6/T9**): it produces the **derivation** — the five id sets, in lists,
  never a partial chain — and owns the fact; the projection is explicitly **not** its job.
- `company-scope-local-fallback` (**D3, D6, D1 = A, T9, T11–T13**): who asserts the membership, the outbox
  boundary, the reference-not-instruction rule, the reconcile-not-replay worker, the visible states, the
  gate as a policy over the diff, and the claim shape (top-level, multivalued, complete chain).

And one distinction from that last record decides the whole shape of this one: those records make a
distinction between the **fact** (who works where) and the **projection** of it into the IdP. This record
is the projection.

## What this record is responsible for

| # | Responsibility | Comes from |
| --- | --- | --- |
| 1 | **The Keycloak account**: create it, or **link** an existing one, keyed on the generated corporate email and `employees.keycloak_user_id` | `employee-registry` D1/D2/T9, its F13 |
| 2 | **The roles**: derive them from the position template of the current contract, apply the **diff**, converge | `hr-org-structure` D6/D7, `position_roles` (its W1b-2) |
| 3 | **The membership attributes**: write the five claim ids derived from the store assignments | `employee-store-assignments` T6/T9, `company-scope-local-fallback` T9 |
| 4 | **The durable intent**: the outbox row in the same transaction as the fact, and the worker that reconciles | `company-scope-local-fallback` T11/T12 |
| 5 | **The gate**: approval for the grants that carry the risk, and the record of who approved what | `company-scope-local-fallback` D6(b)/T13 |
| 6 | **The revocation**: disable, remove roles, remove attributes — the same pipe, the same key | `employee-registry` G7 |

## Decisions (user-owned — **all closed**, 2026-10-01)

| # | Decision | Recommendation and consequence |
| --- | --- | --- |
| **O1** | **How does the person actually get in?** — **CLOSED: an activation link, by email.** Not a password handed over | The account is created **without a usable password**: `enabled = true`, `emailVerified = false`, `requiredActions = [VERIFY_EMAIL, UPDATE_PASSWORD]`, and Keycloak emails the **invitation link** (T14). The person sets their own password, so **no secret ever travels through this application** — which is strictly better than the show-once alternative, at the cost of making SMTP part of this record (**W6**) instead of its own slice. Three consequences recorded rather than discovered: the **Frontend URL** the link must point at is realm configuration (defaulting to Keycloak's own host, which would send people to the wrong address), the **address must be deliverable**, which upgrades `employee-registry`'s G6 into a hard requirement (**G9** here), and the link expires, so the Access section needs a **resend** action. **T15** keeps the operator hand-over as the fallback for a company with no deliverable domain |
| **O2** | **Which grants need approval?** — **CLOSED: a rule, not a list** | Auto-apply only the roles that are in **`ScopeLevel.STORE.roleNames()`** (F5) — they are meaningless without an assignment and they are the operational set — and require approval for **everything else** (the company-scoped catalogs, the HR read/write pair, anything broader). A list would rot; the rule is derivable from code that already exists, and it puts the gate where the blast radius is: a role that reads salaries and birth dates is not `lc-sales` |
| **O3** | **May the requester approve their own request?** — **CLOSED: no** | The gate exists to put a second pair of eyes on the grant. Honest consequence, accepted: in a small company where one person holds `lc-employee` and `lc-employee-access`, the approver must be someone else (an `lc-admin`) or the gate is turned off for the auto-apply set. Self-approval would make the gate audit theatre, which is worse than not having it |
| **O4** | **Is revocation automatic?** — **CLOSED: automatic** | The same pipe produces a `DEACTIVATE` task that disables the account, removes the roles the template owns and removes the claim attributes. `employee-registry`'s **G7** is exactly this hole, and doing it by hand is what keeps it open |
| **O5** | Does the access officer need a **read-only pair** (`lc-employee-access-read`)? — **CLOSED: no pair for now** | Reads are gated by `lc-employee-access` and `lc-admin`. The screen shows role **names**, not PII — the reason `employee-registry` needed `lc-employee-read` (birth dates, salaries) does not apply here. If the inbox is ever shown to someone who must not be able to apply, the pair is a two-line addition |

## Decisions (mine, technical — challenge them if you disagree)

| # | Decision | Why |
| --- | --- | --- |
| T1 | **The membership is projected as multivalued Keycloak user attributes** (`company_id`, `company_country_id`, `company_region_id`, `company_zone_id`, `company_store_id`) through `updateUserAttribute`, and **no new identity capability is needed** | F1/F2: the operation already exists in `IdentityProvider`, is implemented (`attrs.put(key, values)` **replaces the whole list**, which is exactly what a reconcile needs), and is already wired through `UsersAdminService` and an HTTP endpoint. The **mapper** is the only missing piece, and it is `company-scope-local-fallback`'s W0. This also settles that record's open sub-choice: **user attributes win over group attributes**, because the operations exist and the realm export proves the technique with its `locale` mapper — the `lc-company-*` groups stay an organizational mirror. **The list is not uniform, and D4 = no fixed where it collapses**: `company_id` derives from `employees.company_id` and is therefore always a **single element**, while `company_country_id`, `company_region_id`, `company_zone_id` and `company_store_id` remain **genuine** lists — several stores in several countries of that one company. A projection that emits **more than one** `company_id` is a **bug**, and the derivation must **fail closed** rather than write it, because this is an **authorisation input**. **Annotated 2026-10-02**: the mapper is no longer the missing piece — `company-scope-local-fallback`'s W0 landed (PR #222) — and `employees` exists too (W1a, `V20`); what is missing is this record's own W1–W6 |
| T2 | The outbox row **is** the task, and its state machine is the record of the decision: `PENDING` → `RUNNING` → `APPLIED` \| `FAILED` (with `attempts` and `last_error`), plus `APPROVAL_PENDING` and `REJECTED` when the gate applies. Schema `access_provisioning_tasks` | F1 of `company-scope-local-fallback`: durable, retryable, and readable by a screen. The states are the contract between the worker, the gate and the UI |
| T3 | The task carries a **reference**, never an instruction: the employee and the kind (`ACTIVATE`, `DEACTIVATE`, `RECONCILE`), never the role names | `company-scope-local-fallback`'s T12. With an instruction, whoever writes the row decides the grant; with a reference, the worker derives it from the contract, the assignment and the template, so the requester decides nothing |
| T4 | **Idempotency by construction**: the natural key is `employees.keycloak_user_id` (nullable and `UNIQUE`), and applying means "read what is there → compute the diff → converge", so a retry and a duplicate are both no-ops | `company-scope-local-fallback`'s reconcile-not-replay. It is also what makes the retry with backoff safe without a distributed lock |
| T5 | **Create or link, never hijack**: a 409 from Keycloak (the username/email is taken) is resolved by **linking only when the existing account's email matches the employee's corporate one**; otherwise the task fails **visibly** | `hr-org-structure`'s **T13** anticipated the 409 ("this person already has an account — link it instead of creating it"). The rule keeps the innocent case working and refuses the dangerous one: the account that gets roles must be the account of *this* person, and the check is the email the record already froze |
| T6 | **Never `deleteUser`**: `Terminated` means `updateUser(enabled = false)`, the template's roles removed and the claim attributes deleted | `activity_logs.user_id` holds the Keycloak `sub` (F6), so a deleted account orphans its own audit trail. Deactivating also keeps the `sub` stable if the person is rehired |
| T7 | **No local mirror of the current roles.** The diff is read **live** from Keycloak; what is stored is the **applied snapshot** as immutable history attached to the task | `hr-org-structure`'s T12 refused a fourth source of truth, and a mirror of "what they have" is exactly that. The distinction that makes the snapshot legitimate: it answers *"what did we do, when, on whose order"* — history — not *"what do they have now"*, which is only Keycloak's to answer |
| T8 | The applied snapshot is a **child table** (`access_provisioning_applied_roles(task_id, role_name)`), not JSON | The repository has exactly **one** `jsonb` column (`products.attributes`, F6) and no array columns; the snapshot is a queryable set, and the allowlist test (T12) wants to read it as rows |
| T9 | **The task row is the audit.** It records `requested_by`, `requested_at`, `decided_by`, `decided_at`, the applied set and its timestamps | F6: the existing `activity_logs` is **HTTP-shaped** (`http_method`, `http_status`, `request_path`, `payload_json`) and cannot express "who approved this grant for this employee". Pointing the audit at it would produce a trail nobody can answer a question with |
| T10 | **A failure is visible and never silent.** A failed task stays in the Access section with its reason and is retried with backoff; no screen may claim access was granted while the task is not `APPLIED` | `company-scope-local-fallback`'s **G9** is the counter-example living in this repository: a listener that logs the failure and forgets it. That is the defect class this record must not reproduce, and the fix is not a better log line but a state the operator can see |
| T11 | The **gate is the task's status**, not a second flow, and the diff is computed and stored on every task | `company-scope-local-fallback`'s T13. A gated task is `APPROVAL_PENDING` with its diff frozen for review, and **re-derived when it is applied** — approving a snapshot from before a contract change would approve something that is no longer true |
| T12 | The **allowlist is enforced here** (`hr-org-structure` D7): only the roles the position template declares, of the same company, for an employee whose status allows it, and **`lc-admin` is never grantable** — with a test that fails if it is ever added to the list | This is the projection's blast radius, and the projection is the only thing that can widen access in this flow. Note that the users-admin surface's own protection is a **URL rule** (`/api/users-admin/**` → realm `admin`, F1), which does **not** apply to Java calls: the projection calling the service directly is the system acting, so this record's authorization (who may create a task, who may approve) plus the allowlist are the controls |
| T13 | The projection is **fail-closed**: any unexpected Keycloak error leaves the task `FAILED` with its reason and retries; it never degrades into "granted but unrecorded", and it never writes a partial state without recording it | Same class as T10, one step earlier: the invariant is that the record and reality move together, and when they cannot, the record says so |
| T14 | **The invitation is Keycloak's own execute-actions flow**, not a password this application generates or transmits: create the account with `requiredActions = [VERIFY_EMAIL, UPDATE_PASSWORD]`, `emailVerified = false` and no usable credential, then ask Keycloak to **send the actions email** with a bounded lifespan; the person opens the link, sets their own password and is verified in the same act | O1. It needs **one new method on `IdentityProvider`** (send the actions email for a user, with its lifespan) — the interface has `createUser`/`updateUser` but nothing that triggers an email today. The advantages are the reason to prefer it: **no secret passes through this application**, no password is ever seen by an operator, and the email verification comes free. The cost is W6 (the realm's SMTP, its Frontend URL, a dev mail container) and G9 (the address must be deliverable) |
| T15 | **The operator hand-over stays as the fallback, chosen by the data and not by a toggle**: when the company has no `email_domain` (or the address cannot be delivered), the task resolves to a temporary credential **shown once** to the operator with `setTemporary(true)` and `UPDATE_PASSWORD`, and the Access section says so plainly | O1 chose the email path, but `companies.email_domain` is **nullable** and `employee-registry`'s write path already fails closed without it. Without a fallback, a company that has not configured a domain simply cannot onboard anyone — an outage disguised as a policy. The operator must **see which path applies** rather than discover it from a bounced message |
| T16 | The aggregate lives in its **own top-level domain package**, `com.lifecontrol.api.provisioning` (`model`, `repository`, `service`, `exception`), **not inside `hr`** | Measured convention: this repository packages by domain at the top level (24 siblings, `hr`, `purchaseorder`, `goodsreceipt` and `usersadmin` among them), a domain's enums live in its `model` and its state-conflict exception in its `exception` (`hr/model/ContractType.java`, `purchaseorder/exception/InvalidStatusTransitionException.java`). This projection owns its own tables, its own state machine, its own worker (**W4**), its own gate (**W5**) and, from **W2** on, a writer into another system; none of that is employee CRUD, and W4's worker is a platform piece this record already asks to be reusable for the group mirror. The cost, declared rather than discovered later: the controller still nests under `/employees/{employeeId}/access` and the screens still live in the employee detail, so **the package boundary does not follow the URL** |

## Verified exploration evidence

Read-only verification ran on 2026-10-01 in this worktree; F1–F6 were read directly by the parent, F7–F11
are anchors inherited from the sibling records named in each row.

| # | Fact | Anchor |
| --- | --- | --- |
| F1 | **The identity capability this record needs already exists and is wired end to end**: `IdentityProvider` declares `getUserAttributes`, `updateUserAttribute(userId, key, List<String> values)` and `deleteUserAttribute`; `KeycloakIdentityProvider` implements them; `UsersAdminService:179-190` wraps them; and `UsersAdminController` exposes `GET /{id}/attributes`, `PUT /{id}/attributes/{key}` and `DELETE /{id}/attributes/{key}` under `/api/users-admin/users` | `usersadmin/identity/IdentityProvider.java:79-83`; `usersadmin/identity/keycloak/KeycloakIdentityProvider.java` (`updateUserAttribute`, `getUserAttributes`); `usersadmin/service/UsersAdminService.java:179-190`; `usersadmin/controller/UsersAdminController.java:38,138-155` |
| F2 | `updateUserAttribute` **replaces the whole list** for the key (`attrs.put(key, values); user.update(userRep)`), and reads it back as `Map<String, List<String>>` — so a multivalued claim can be written and reconciled, not appended | `KeycloakIdentityProvider.java` (`updateUserAttribute`, `getUserAttributes`) |
| F3 | The claim names and their requirements: `company_id` and `company_country_id` are **required** levels, the region/zone/store are optional; the parser accepts lists | `common/security/ScopeLevel.java:26,29,32,35,58`; `common/auth/CurrentUserContext.java:349-357`, `:408-457` |
| F4 | **The mapper does not exist**: no `company_*` protocol mapper in `docker/scripts/keycloak-setup.sh`, none in the k8s realm export, and no group-membership operation anywhere | `company-scope-local-fallback`'s E13–E15 |
| F5 | The store-scoped role set, which O2's rule uses as the auto-apply boundary: `lc-company-store`, `lc-company-store-read`, `lc-receiving`, `lc-sales`, `lc-scheduling`, `lc-scheduling-read` | `common/security/ScopeLevel.java:58-68` |
| F6 | Two schema facts that decide T8 and T9: the repository has exactly **one** `jsonb` column (`products.attributes`) and no array columns; and `activity_logs` is HTTP-shaped — `user_id VARCHAR(255)`, `username`, `activity_process_id`, `activity_event_id`, `http_method`, `http_status`, `request_path`, `ip_address`, `user_agent`, `payload_json`, `created_at` | `V1__baseline_schema.sql:191` (jsonb); `V1:445-463` (activity_logs); `activity/listener/ActivityLogEventListener.java:31` |
| F7 | **`lc-employee-access` does not exist anywhere yet** — not in `Roles.java`, not in the setup script — so this record has to register it | grep for `lc-employee-access`/`EMPLOYEE_ACCESS` over `src/main/java` → zero hits |
| F8 | The existing user-creation path is broken for this purpose: it generates a random password, marks it **non-temporary** and returns only the id, so the password is discarded and the created user cannot log in; there is **no SMTP configuration**, and the application **client id is not a configured property** | `employee-registry`'s F13/F12; `hr-org-structure`'s E15 |
| F9 | The identity rules this record must respect: the corporate email is generated with **R2** and frozen after provisioning, the Keycloak **username is the full email**, and `employees.keycloak_user_id` is a nullable `UNIQUE VARCHAR(36)` holding the `sub` | `employee-registry`'s D1/D2/T9/T17 and its V20 block |
| F10 | The projection's **input** for the claims is a pure derivation over the assignments valid today, returning the five id sets as lists and never a partial chain | `employee-store-assignments`' T6/T7/T9 and its "The derivation" section |
| F11 | What the repository has for the outbox's transport: **no** broker, **no** `@Scheduled`/`@EnableScheduling`, **no** retry machinery, and one `AFTER_COMMIT` listener that swallows its failure | `company-scope-local-fallback`'s E25–E27 |

## Scope of the projection

The projection **converges** these facts into Keycloak, and the table says where each one comes from, so
that nothing is invented here:

| Keycloak fact | Source of truth | Removed when |
| --- | --- | --- |
| the account exists, enabled | `employees.keycloak_user_id` + the employee's status | the employee is `Terminated` → `enabled = false` (T6) |
| the client roles | `position_roles` of the **position of the current contract**, filtered by the allowlist (T12) | the role leaves the template, the position changes, or employment ends |
| `company_id` … `company_store_id` attributes | `employee-store-assignments`' derivation (F10) | the assignments stop being valid today, or employment ends |
| nothing else | — | the projection touches **no** other attribute, role or setting: everything it did not write, it does not own |

## Schema — `V22`

> The `V22` in this heading is **the number the design was written against, not a reservation**: see
> the corrected `**Migration**` line in the header. What matters for this schema's shape is that
> `employee_id` now has a real target — `employees`, created by `employee-registry`'s W1a as `V20` —
> so the `REFERENCES employees(id)` below is applicable rather than aspirational. **Implemented
> statement by statement as `V22__employee_access_provisioning.sql` on 2026-10-04 (`92026ac`), with the
> DDL below unchanged**: the only differences between this block and the migration are the migration's own
> comments, and the repository's first **partial UNIQUE index** is the one this block declares.

```sql
-- ============================================
-- V22 — Employee access provisioning tasks
-- ============================================
-- The durable intent of the access flow: a row is written in the same transaction that asserts the
-- organisational fact (contract activation, a store-assignment change, a termination) and a worker
-- resolves it by RECONCILING Keycloak against the current truth. The row is also the audit: it
-- records who asked, who approved and what was applied, because the existing `activity_logs` is
-- HTTP-shaped (method, status, path) and cannot answer "who granted this".
-- `kind` and `status` are free-form VARCHAR validated by a Java enum: this schema has ZERO
-- `CHECK (col IN ...)` and zero native enums, and the established answer is the VARCHAR plus the
-- enum (inventory_movements.movement_type is the precedent).
-- The partial unique index is the schema's first: at most one OPEN task per employee, so a second
-- intent cannot race the first. Foreign keys are unnamed, matching the baseline and the V9..V21 style.
-- ============================================

CREATE TABLE access_provisioning_tasks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id UUID NOT NULL REFERENCES employees(id),
    kind VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(500),
    requested_by VARCHAR(36) NOT NULL,
    requested_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_by VARCHAR(36),
    decided_at TIMESTAMP,
    applied_at TIMESTAMP,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_access_provisioning_tasks_employee_id ON access_provisioning_tasks(employee_id);
CREATE INDEX idx_access_provisioning_tasks_status ON access_provisioning_tasks(status);
CREATE UNIQUE INDEX uq_access_provisioning_tasks_open
    ON access_provisioning_tasks(employee_id)
    WHERE status IN ('PENDING', 'APPROVAL_PENDING', 'RUNNING', 'FAILED');

-- The applied snapshot: immutable history, one row per role the task actually granted or removed.
-- It is a table and not a JSON column because the repository has exactly one jsonb column and no
-- array columns, and because the allowlist test wants to read it as rows.
CREATE TABLE access_provisioning_applied_roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id UUID NOT NULL REFERENCES access_provisioning_tasks(id),
    role_name VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_access_provisioning_applied_roles UNIQUE (task_id, role_name)
);
CREATE INDEX idx_access_provisioning_applied_roles_task_id ON access_provisioning_applied_roles(task_id);
```

No seed data. `access_provisioning_applied_roles` carries only `created_at` on purpose: it is an immutable
log line, and an `updated_at` would suggest a mutation that must never happen.

## API surface

| Method | Path | Roles |
| --- | --- | --- |
| `GET` | `/api/companies/{companyId}/employees/{employeeId}/access` | `lc-admin`, `lc-employee-access` |
| `POST` | `/api/companies/{companyId}/employees/{employeeId}/access/apply` | `lc-admin`, `lc-employee-access` |
| `POST` | `/api/companies/{companyId}/employees/{employeeId}/access/retry` | `lc-admin`, `lc-employee-access` |
| `POST` | `/api/companies/{companyId}/employees/{employeeId}/access/deactivate` | `lc-admin`, `lc-employee-access` |
| `POST` | `/api/companies/{companyId}/employees/{employeeId}/access/requests/{taskId}/approve` | `lc-admin`, `lc-employee-access` |
| `POST` | `/api/companies/{companyId}/employees/{employeeId}/access/requests/{taskId}/reject` | `lc-admin`, `lc-employee-access` |

`GET …/access` returns the linked account, the **required** roles (from the template), the **current** ones
(read live, T7), the **diff** and the open task with its history. `reject` takes a reason. Every method
resolves the company first — `verifyCompanyAccess` before the load (E12 of `hr-org-structure`) — and then
scopes the employee to that company.

**One endpoint outside this shape**: the employee's **`GET`** carries the access **state** (`pending`,
`failed`) as a field for holders of `lc-employee`, so the clerk who activated the contract can see that
something is waiting without holding the access role. The **role names** are not in that payload.

## Screens

| Where | Content |
| --- | --- |
| `/hr/employees/:id`, the **Access** section (planned by `employee-registry`'s W4 as this record's) | Account link state, required vs current roles with the diff, the five claim attributes as the token would carry them, the open task with its state and reason, and the actions (apply, retry, deactivate) |
| the same section, for a gated task | The **diff frozen for review**, with approve/reject and the requester's name. Re-derived at apply time (T11) |
| the pending-requests **inbox** | **A tab or filter inside `Empleados`** — decided 2026-10-01, closing the last item this record had left open. It is the least invasive home rather than a fourth child in the menu, because `hr-org-structure`'s D4 pinned the header ids. Appending `9-4` is legal under that decision, but it is a menu change and it is not needed to make the flow work |

## Work units

| | Content | Sizing note |
| --- | --- | --- |
| **W1** | **Split into W1a and W1b on 2026-10-04.** One row carrying a migration, two entities, two repositories, a service and a state machine is not reviewable as a unit | The split is by artifact and not by convenience: `spring.jpa.hibernate.ddl-auto=validate` means the schema cannot follow the model, and the state machine cannot precede the schema |
| **W1a** | `V22` + the two entities, their two repositories and the schema test: **the schema, the model and its pin** | Implements the `## Schema — V22` block below and adds **no** transition logic — the enums are the entity's column types, not the machine. **Measured while starting it**: **four** suites pin the Flyway head at `"21"` (`HrSchemaMigrationIntegrationTest:66`, `EmployeeSchemaMigrationIntegrationTest:89`, `EmployeeContractSchemaMigrationIntegrationTest:83`, `GoodsReceiptIntegrationTest:578`) and updating them is part of this row |
| **W1b** | The **state machine** and the service that owns its guard: every legal transition, and the illegal ones refused | **Declared over budget on its own**, and it is the part not to rush: the states are the contract with the worker, the gate and the UI. Precedent to mirror rather than invent: `PurchaseOrderService`'s explicit transition map (`:67-84`) with a guard that throws a `ConflictException` subclass (`purchaseorder/exception/InvalidStatusTransitionException.java`) |
| **W2** | The Keycloak side: **create or link** (T5), the **role diff** and convergence (T4/T7/T8/T12), and any application **client id property** this still needs (F8) | **Declared over budget**; the natural split is (a) the account lifecycle, (b) the roles. Needs `position_roles`, which is already built (`hr-org-structure`'s **W1b-2**, `2093fd8`), so the role source exists. **The client id property is no longer one of this slice's obligations**: `hr-org-structure`'s **T18** added it as `keycloak.app.client-id=${KEYCLOAK_CLIENT_ID:life-control-client}` (`application.properties:86`) with the `config/security/ApplicationClientProperties` binding — **W2 reads that property, it does not add it**. What remains of **F8** here is the *other* half: the account-creation path's discarded password, which is what **O1**'s activation link and this record's **W6** address |
| **W3** | The **membership projection**: write and delete the five attributes from the derivation (T1), with the tests that pin the list semantics | **Under budget** — the capability exists (F1/F2), and the derivation is already specified and tested in `employee-store-assignments`. Needs its `V21` |
| **W4** | The **worker**: the scheduler, the claim that stops two workers taking the same task, the retry with backoff, and the visible failure (T10/T13) | **Declared over budget.** It is also the piece that would repair `company-scope-local-fallback`'s G9 class of defect, so its shape should be reusable for the group mirror |
| **W5** | The **gate** (the approval status, the frozen diff, self-approval refused), the six endpoints, and the frontend (the Access section and the inbox) | **Declared over budget**: backend and frontend separable |
| **W6** | **The invitation channel**: the realm's **SMTP** configuration, the **Frontend URL** the activation link must point at, the `sendActionsEmail` method on `IdentityProvider` and its Keycloak implementation, a **dev mail container** so the flow is exercisable locally, and the procedure that verifies it end to end (an email that arrives, a link that works, a password set by the person) | **Not testable from this repository's test loop** beyond the interface method and its mocked test, which is why it is a row of its own. It is **the only row in this record that is infrastructure rather than code**, and it is the price O1 was chosen with its eyes open |

Dependency order: **W1 → W6 → W2/W3 → W4 → W5** — W6 precedes W2 because creating the account **sends the invitation**, so there is no usable account lifecycle until a channel exists. W3 needs `employee-store-assignments` implemented; W2 needs `position_roles`; and the whole flow also needs the **mapper** (`company-scope-local-fallback`'s W0) to be
reachable in a token at all. Nothing here can be verified end to end until that mapper exists in the
environment, which is why W4's tests must run against a **fake or embedded** identity provider rather than
a live realm.

## Gaps

| # | Gap | Note |
| --- | --- | --- |
| G1 | **No SMTP** — and O1 moved it **into scope** instead of deferring it | `employee-registry`'s G6. It is now **W6**: realm SMTP configuration, the Frontend URL the link must point at, a dev mail container, and the verification procedure. Not testable end to end from the test loop, which is why it is its own row |
| G2 | The application **client id is not a configured property**, and client roles cannot be assigned without it | `hr-org-structure`'s E15. Small, blocking for W2, and fixed inside this record |
| G3 | **No batch user fetch** in `IdentityProvider` | One Admin API call per employee. That is why the diff lives in the **employee detail** and not in the employee **list**: a list that showed access state would fan out one call per row |
| G4 | `employees.email` and the Keycloak login email do not synchronize | `employee-registry`'s G4. T5's link rule is the mitigation: the account that gets roles must be the one whose email matches |
| G5 | The template is a **seed, not live authority** (`hr-org-structure` D6), so after a `position_roles` change the diff shows divergence and **nothing acts on it** | The diff is informational today. Who closes that divergence — a periodic `RECONCILE` task, or a human acting on the diff — is **not decided here** and is not required for the flow to work |
| G6 | **Per-store role scoping is not expressible** | `employee-store-assignments`' G1, inherited: the roles this record applies are person-global, so a person with two stores and two roles has both capabilities in both |
| G7 | Offboarding is **not exhaustive**: the projection converges the template's roles, not the roles granted by hand outside it | An `lc-sales` granted manually in Keycloak survives a `Terminated` employee. Declared rather than half-solved: converging "every role the person holds" would mean the projection owns roles the template never declared, which contradicts D6 |
| G8 | The `lc-company-*` group mirror becomes **cosmetic** under T1 | `company-scope-local-fallback`'s **G9** (a group that may be missing, silently) no longer affects authorization, because the membership travels as user attributes. The divergence is still real, it is now just not a security concern — which lowers that gap's severity without closing it |
| G9 | **The generated corporate address may not be deliverable, and O1 made that a hard requirement** | `employee-registry`'s G6/T16 upgraded in severity: if the company does not control the domain — or has none, since `companies.email_domain` is nullable — the activation email never arrives and the person **cannot log in at all**. **T15** is the escape hatch, and the Access section must tell the operator which path applies instead of letting them discover it from a bounce |
| G10 | The email's **copy, language and branding** are Keycloak's defaults | The repository has no custom login theme and no realm locale decision, so the invitation arrives in whatever the realm is configured with, with no company branding. Declared because it is the first thing an operator will notice, and it is theme work rather than a defect |

## Cross-record dependencies

| Record | Relation |
| --- | --- |
| `hr-org-structure` | **Prerequisite**: `position_roles` (its W1b-2) is the role source, and D6/D7/D8/D10 are the rules it applies. Its E15 is G2 here |
| `employee-registry` | **Prerequisite**: `employees` (its `V20`), the generated email, `keycloak_user_id` (its T17 is the precondition of auto-apply), and its G7 is the hole this record closes |
| `employee-store-assignments` | **Prerequisite**: `V21` and its derivation (F10) are the input for the claim attributes. It declares that the projection is explicitly not its job |
| `company-scope-local-fallback` | The rules this record implements (its D3, D6, T9, T11–T13) and its **W2**, which is this record. Its **W0** (the mapper) is what makes all of this reachable in a token, and its T1's choke point is where the app reads the result |
| the `users-admin` surface | The attribute operations this record uses (F1) live there, protected at URL level by the realm `admin` role. This record calls the **service**, not the endpoint, so that rule does not apply to it — which is why T12 and the task's own authorization are the controls |
| Migration numbering | `V19`–`V21` are on this same branch, so `V22` is safe **here only**. If any of those records is split into its own branch, renumber before merge (`life-control-api/AGENTS.md:874`) |

## Non-goals

- **Not** SMTP and not an invitation flow *as this record's own idea* — **O1 said otherwise**, so the invitation channel is in scope as **W6** (realm SMTP, the Frontend URL, `sendActionsEmail`, a dev mail container). What stays out is a general notification system: this is the one email the flow needs.
- **Not** a local mirror of the current roles (T7), and not a second authorization source: Keycloak stays
  authoritative.
- **Not** per-store role scoping (**G6**), and not converging roles granted outside the template (**G7**).
- **Not** a change to the `users-admin` surface: it stays the platform admin's, and this record only
  consumes its service operations.
- **Not** user self-service: nobody views or edits their own access here.

## Task log

- [x] O1–O5 decided by the user (2026-10-01): an **activation link by email** (which moved SMTP into
  scope), the store-scoped auto-apply rule, no self-approval, automatic revocation, no read pair
- [x] W1a — `V22`, the two entities, the two repositories and the schema test — `92026ac` (2026-10-04)
- [ ] W1b — the state machine and the service that owns its guard
- [ ] W2 — the account lifecycle and the role diff
- [ ] W3 — the membership attributes from the derivation
- [ ] W4 — the worker: scheduler, claim, retry, visible failure
- [ ] W5 — the gate, the endpoints and the frontend
- [ ] W6 — the invitation channel: the realm's SMTP configuration, the Frontend URL the activation link must
      point at, `sendActionsEmail` on `IdentityProvider` and its Keycloak implementation, the dev mail
      container, and the procedure that verifies it end to end

## Deferred and blocking

- **Nothing blocks the design any more**: O1–O5 are closed. O1 put **SMTP in scope** (**W6**) rather than
  deferring it, so the first thing the account lifecycle needs is a channel that can deliver an email.
- **Blocked on its prerequisites, in order**: `position_roles` (W2), `V21` (W3), and the **mapper** for
  anything to be observable end to end (W3's output is invisible in a token until `company-scope-local-fallback`'s
  W0 exists).
- **Deferred**: the batch user fetch (G3), the divergence policy (G5), roles granted outside the template
  (G7), the email theme and language (G10), and the inbox's home in the menu.
- **Explicitly not blocked**: **W1b**, which is what is left of W1 — the state machine and the service that
  owns its guard. Neither W1b nor W1a needs Keycloak: the schema, the transitions and their tests are
  self-contained, and W4's tests will run against a fake identity provider.

## Evidence log

| Date | Evidence |
| --- | --- |
| 2026-10-01 | **The record reconciled with itself: the task log gained the W6 row its header and table already carried, the SMTP non-goal was inverted now that O1 said otherwise, the T5 citation was re-pointed to the record that contains it, and the inbox home was decided.** The task log had stopped at W5 while the header read "W1–W6", the table carried W6 and the dependency order was **W1 → W6 → W2/W3 → W4 → W5**; the non-goal still read "Not SMTP… unless O1 says otherwise" after O1 had moved SMTP into scope; **T5** attributed the 409 rule to `employee-registry` D9/T13, a record whose decisions stop at D6 and whose T13 is the contract-closure rule (the rule and the sentence are `hr-org-structure`'s **T13**); and the Screens table still marked the inbox home **Open** while this log read "no open decision" — now **a tab or filter inside `Empleados`**, as less invasive than a fourth header child under `hr-org-structure`'s D4. **No source line and no decision reversed** |
| 2026-10-01 | **O1–O5 closed by the user in one pass.** **O1 chose the activation link by email** over the operator hand-over, which **moved SMTP into this record** (the new **W6**) and produced **T14** (Keycloak's execute-actions flow with `VERIFY_EMAIL` + `UPDATE_PASSWORD`, `emailVerified = false`, no credential this application ever handles, plus one new method on `IdentityProvider` and a resend action) and **T15** (the hand-over stays as the fallback, selected by the data, because `companies.email_domain` is nullable and the write path already fails closed without it). It also upgraded `employee-registry`'s G6 into **G9** here — an undeliverable address now means the person cannot log in at all — and added **G10** (the email is Keycloak's default copy and language). **O2–O5 were accepted as recommended** and are recorded as closed: the store-scoped auto-apply rule, no self-approval, automatic revocation, no read pair. **No source line was written**, and the record now has no open decision. |
| 2026-10-01 | Record written on `feat/hr-org-structure`, after `company-scope-local-fallback` closed its D1 as **A** and `employee-store-assignments` closed D1–D4. F1–F6 were read in this worktree while writing it, and two of them changed the design: **F1/F2 removed a work unit** from `company-scope-local-fallback` (the attribute operations exist, are implemented, and replace the whole list, so no `addUserToGroup` is needed and the attributes-versus-groups sub-choice is settled) and **F6** decided T8 (a child table, because the repository has exactly one `jsonb` column) and T9 (the task row is the audit, because `activity_logs` is HTTP-shaped). F7–F11 are anchors inherited from the sibling records named in each row. **Nothing is implemented**, and no source line was written. |
| 2026-10-02 | **T1 gained the invariant `company-scope-local-fallback`'s D4 = no created.** The emitted set collapses on **one** level and not on the others: `company_id` derives from `employees.company_id` and is therefore always a **single element**, while `company_country_id`, `company_region_id`, `company_zone_id` and `company_store_id` remain **genuine** lists — several stores in several countries of that one company. A projection that emits **more than one** `company_id` is a **bug**, and the derivation must **fail closed** rather than write it, because this is an **authorisation input**. The decision lives in that record's **D4**, closed on 2026-10-02 as no — one company per person. **No source line was written** |
| 2026-10-02 | **The record's migration premise was false and is corrected here.** The header read "`V22` — `V19`–`V21` precede it on this same branch" and the readiness note claimed *"W1 can be built today"*. Measured against the tree on 2026-10-02: **`V19` is the head, `V20` and `V21` do not exist, and `employees` does not exist**, so the `employee_id UUID NOT NULL REFERENCES employees(id)` in the schema was **unapplicable** and this record was **not** the buildable first step — `employee-registry`'s **W1a** was. What is corrected: this record's number is **the next free one when it lands, not `V22` by right**; **`V21` stays claimed by `employee-store-assignments`**; and the `employee_id` foreign key now has a real target, because W1a creates `employees` as `V20` through `companies.email_domain` + `employees` + the `EMPLOYEE_STATUS` seed. The `## Schema — V22` block is left **byte-identical** as the design it was written against, with a note at its head saying exactly that. The correction was found while **starting `employee-registry` W1a** — the record this one depends on for `employees.email` and `keycloak_user_id` — which is the second time this chain has paid for a premise that was asserted rather than measured. **No source line was written, no decision was reversed, and nothing about the projection's shape changed** |
| 2026-10-04 | **The record's migration premise was measured before paying for it a third time, and it was stale again.** The `**Migration**` line claimed that `V21` was "claimed by `employee-store-assignments`, which is still unwritten"; measured against the tree, **`V21__employee_contracts.sql` exists and belongs to `employee-registry`'s W2**, so the next free number is **`V22`** — which is what the `## Schema` heading had said since the day it was written. The two earlier corrections in this log are why it was checked at all, and this is the third premise in the chain that a measurement refuted and the second time the refutation was cheap. **What else the pre-write measurement produced, and what it changed**: the module has **zero** `@EnableScheduling`, `@Scheduled`, `TaskScheduler`, `@Async` and no retry or queue dependency in `build.gradle`, so **W4's worker starts from nothing** — E25–E27 confirmed rather than assumed; **four** suites pin the Flyway head at `"21"` and belong to **W1a**; `employees` (V20) and `position_roles` (V19) exist, so W1a's `REFERENCES employees(id)` is applicable and W2's role source is already built; `lc-employee-access` appears **nowhere** outside `odd/tasks/*.md`, so this record must still register it; and `access_provisioning*` has **no** name collision in source, SQL or tests. Consequences recorded rather than carried in the head: **T16** fixes where the aggregate lives, and **W1 is split into W1a and W1b** (schema, model and repositories; then the state machine and its service) because the single row carried too much to review as one unit. **No source line was written, and no decision about the projection's shape changed** |
| 2026-10-04 | **W1a landed: `V22`, the two entities, the two repositories and the schema test.** Branch `feat/employee-access-provisioning` off `main @ b01adbf`: `b448cf1` is the tracking commit (the W1 split, the migration-premise correction and **T16**) and **`92026ac`** is the code — `V22__employee_access_provisioning.sql` implementing this record's frozen DDL **statement by statement**, plus `com.lifecontrol.api.provisioning.{model,repository}` and `AccessProvisioningSchemaMigrationIntegrationTest`. The **four** Flyway head pins moved from `"21"` to `"22"`, and their method and display names with them, so no pinned test is left lying about the head it asserts. **Two independent verifications, both green, and the second one on the finished artifact**: `./gradlew test --no-daemon` reported **2932 tests / 0 failures / 0 errors / 791 classes** on the writer's artifact, and **2934 tests / 0 failures** after the test file was strengthened — the +2 methods are the whole delta, and no file under `src/main` is newer than the first run. **The first verification raised four findings and all four are closed rather than declared**: the column assertions checked names and types only, so **nullability, the VARCHAR lengths and the default values** were asserted nowhere and `ddl-auto=validate` does not check them either; the **three declared indexes** were asserted nowhere, so dropping one kept the suite green; the partial index's predicate was asserted with `.contains("PENDING")`, which `APPROVAL_PENDING` satisfies on its own; and the suite's `@BeforeEach` deleted **every** row of both provisioning tables, which would have deleted W1b's and W4's fixtures. The predicate assertion now derives its expectation from `AccessProvisioningTaskStatus.values()` and matches the **quoted** form, so the enum and the index must agree in both directions. **One value was corrected because it was measured rather than assumed**: PostgreSQL reports the timestamp defaults as `CURRENT_TIMESTAMP`, not `now()`, so the test pins the migration's own written form — the first version of that assertion failed, and the failure is what produced the value. **One instruction of mine was wrong and the writer refused it, correctly**: it was told to use `protected` setters, measured the repository (**337** public setters against **2**, with `life-control-api/AGENTS.md:312-345` documenting public ones) and followed the repository while declaring the divergence. **The native review did not run on this candidate, and the reason is recorded rather than hidden**: the preflight found the target ready, and the consent envelope it created **expired unanswered after ten minutes without ever being presented to the human**, because two of the START invocations returned a blocked or erroring state instead of the envelope. The provider then recorded `declined_this_candidate` for target `sha256:f511ab72…` with `lineage_created: false` and `mutation_performed: false`, so nothing was authorised and nothing was burned, and the decline is **candidate-scoped**: a later candidate gets its own envelope. W1a's evidence is therefore two independent verifier runs and a green full suite, **not** a native review. **No decision about the projection's shape changed, and `src/main` still contains no transition logic and no identity code** |
| 2026-10-04 | **The native review ran on a new candidate and closed `approved` — and this row exists because the previous one could not be left standing alone.** The first candidate's envelope had expired unanswered (the row above), so once `28600b5` landed the tree changed, the target identity became `sha256:0a09ae10…`, and the fresh START **did not ask for consent again**: the host already held the grant for this repository, so the review was created directly in `reviewing` with tier **medium**, **one lens** (`review-reliability`) and a correction budget of 200. The single materialized reviewer slot was **forecast before it ran** (`pi_host_relay`, 1 model run, `mutation_outcome: none`) and only then authorized; it closed lineage `review-58ba81609a64b8a8` as **`approved`** with **no correction opened** — a 101,776-byte prompt against a 5,528-byte result, over the frozen candidate tree `ae3c7506…`. Its exact acknowledgement was obtained from bound STATUS and executed unchanged, so the authority is **burned** (`gentle-ai.review-acknowledged/v1`) and delivery stays where it belongs: **ordinary repository policy**, never the review. **Four advisory findings came back, all `informational` and none blocking, recorded here as separate later work rather than as a reason to re-run the review on this candidate**: `R3-1` (`WARNING`, `AccessProvisioningTask.java:191-197` — the public setters of the audit fields `requested_by`/`requested_at`), `R3-2` (`SUGGESTION`, `AccessProvisioningTaskRepository.java:30-33`), `R3-3` (`SUGGESTION`, `AccessProvisioningSchemaMigrationIntegrationTest.java:408-424`) and `R3-4` (`SUGGESTION`, `AccessProvisioningSchemaMigrationIntegrationTest.java:201-216` — the predicate loop, which is also where the second verifier saw the one remaining coverage gap: a seventh status added to the enum would be checked by neither branch). **The closure envelope carries the anchors and severities but not the reviewer's prose**, so only what the provider gave is recorded, and the reading of which code each anchor points at was measured in the tree afterwards. **No source line changed, and no decision about the projection's shape changed** |
