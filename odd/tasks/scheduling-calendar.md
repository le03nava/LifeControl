# ODD feature: scheduling-calendar

**Status**: **W2 delivered** — the Activity CRUD is reachable end to end: `V16`, the role pair with
its `ScopeLevel.STORE` registration, the store-scoped service carrying the version precondition, the
controller and the `/api/scheduling/**` gateway route are implemented and gated (see the evidence
log). W3–W7 are planned, not implemented. This header makes no claim about push or PR state; see the
evidence log.
**Repository**: LifeControl — spans `life-control-api/**` (Spring Boot, Java 21, PostgreSQL +
Flyway), `api-gateway/**` and `life-control-app-angular/**` (Angular 20.3 + Material/CDK 20).
**Created**: 2026-09-27 · **Risk**: **high** out of the gate — new domain with four tables, a new
authorization role pair, a new gateway route, a booking path that serializes concurrent writers, and
a calendar UI built from scratch because no date/calendar primitive exists in the app. No existing
contract changes and no data migration.
**Branch**: `feat/scheduling-calendar` · **Base**: `main` @ `091660a` · **Worktree**:
`~/workspace/LifeControl-worktrees/feat-scheduling-calendar` (herdr `wC`), created with the procedure
in `.agents/skills/project-conventions/references/worktrees.md`.
**Requested by**: the user — "el user_id es el empleado que atiende y se le pueden asignar tareas,
comenza con la exploracion para empezar el odd" (2026-09-27), closing the open question this feature
had carried since 2026-09-26.

## Origin

The feature was first explored on 2026-09-26 in a different host (an OpenCode session). That
exploration produced a plan and nothing else: no file, no branch, no code. Its `mem_save` **failed**
("several active sessions match the project; the runtime requires a `session_id` I do not have"), so
the design survived only in that session's chat. It was recovered on 2026-09-27 from
`~/.local/share/opencode/opencode.db` (`message`/`part` tables) and persisted as Engram observation
**2467** before this record existed. The three late-2026-09-26 decisions (mixto actor, three phases,
slots + capacity) come from that recovered material and were re-confirmed by the user on
2026-09-27.

## Decisions (user-owned, closed)

| # | Decision | Consequence |
| --- | --- | --- |
| D1 | `user_id` on an activity is the **employee who attends it**; the appointment **is** the task assigned to an employee | Four tables total; no fifth "task" entity. Whole model is `activities → availability → slots → appointments` |
| D2 | Phase 1 is **internal**; the appointment may carry an **optional** `customer_id` | `customer_id` nullable FK to the existing `customers` table; no customer portal, no public surface |
| D3 | Roles: **`lc-scheduling`** (write) + **`lc-scheduling-read`** (read) | New pair, mirroring `lc-company-store` / `lc-company-store-read` |
| D4 | Calendar UI in phase 1: **week/day grid + day agenda list**; month view is a later slice | W6 stays reviewable; protects the 400-line review budget |
| D5 | Phasing overall: 1) internal → 2) customer with Keycloak account → 3) public booking without account | Phases 2 and 3 are out of scope here and are not designed in this record |

## Decisions (mine, technical — challenge them if you disagree)

| # | Decision | Why |
| --- | --- | --- |
| D6 | Package and route prefix **`scheduling`**, not `activity` | `com.lifecontrol.api.activity` **already is the audit trail** (AOP `ActivityLogAspect` + `ActivityLog/Event/Process`). A business domain named `activity` collides head-on |
| D7 | Endpoints are **flat** (`/api/scheduling/...` with `storeId` as a query/body parameter) and scope via `CurrentUserContext.verifyCompanyStoreAccess` | The nested 5-level path (`/api/companies/.../stores/{storeId}/...`) exists, but every recent business domain (`goodsreceipt`, `purchaseorder`, `salesorder`) is flat with the store derived; the flat shape keeps the calendar's range queries sane |
| D8 | Appointment status via the **`status_types`/`statuses` catalogue** with a new type `APPOINTMENT` | Consistent with `sales_order` / `purchase_order`; `Shift`'s raw strings are the older habit |
| D9 | Booking takes a **pessimistic row lock** (`@Lock(LockModeType.PESSIMISTIC_WRITE)`) on the slot, on top of `@Version` | The repo has a real precedent (`PurchaseOrderRepository.java:60-62`, `ProductVariantStoreStockRepository.java:43-50`) and the pattern is documented as a lock-order contract (`GoodsReceiptService.java:40-44`). Capacity is a counter, so optimistic-only would let two writers both see room |
| D10 | Slots are **materialized lazily by idempotent upsert** keyed on `UNIQUE(activity_id, start_at)`, expanded from availability windows | Stable slot identity without a scheduler; no background job, no unbounded pre-generation |
| D11 | Slot step == `duration_minutes` of the activity; `capacity_per_slot` lives on the activity | The simplest rule that satisfies "slots + capacidad" (D4 of the user's earlier round). A configurable step is deferred |
| D12 | Time is `LocalDateTime` + `TIMESTAMP` (no TZ), matching every existing entity | Repo-wide convention. **Declared gap**: slot boundaries are store-local wall-clock with no conversion, so a DST jump is unmodelled. Not inventing a TZ layer this slice |
| D13 | The calendar is a **projection endpoint**, never a table | `GET` over a range joins slots + appointments + activity + customer + status |
| D14 | The frontend calendar is **built with CDK** (no new dependency) | No calendar library and no date library exist in `package.json`; adding FullCalendar is a dependency decision the user has not made |

## Verified exploration evidence

Two read-only scouts ran on 2026-09-27 against `main @ 091660a`. Every line below is anchored; none
is inferred.

### Backend / gateway (hard constraints)

| # | Fact | Anchor |
| --- | --- | --- |
| E1 | Next migration must be **`V16`** (V15 is the highest); Flyway `baseline-on-migrate=true`, `baseline-version=1`, `ddl-auto=none` (`validate` in IT) | `db/migration/V1..V15`; `application.properties:8,20-21`; `AbstractPostgresIntegrationTest.java:40` |
| E2 | Idempotent seed idiom: `INSERT ... SELECT gen_random_uuid(), ... WHERE NOT EXISTS (LOWER(...))` | `V3__seed_reference_data.sql:11-13`; domain-local example `V12__goods_receipts.sql:57-62` |
| E3 | A new `/api/scheduling` route is a **hard gate**: `GatewayRouteCoverageTests` compares the first path segment of every `@RequestMapping("/api/...")` against `Routes.java` and fails listing the missing route | `GatewayRouteCoverageTests.java:41-48,76-113` |
| E4 | …and the gate is blind to a two-line `@RequestMapping`: the regex only matches the string on the same line as the paren | `StoreInventorySettingsController.java:38-41` (two lines, undiscovered) |
| E5 | New routes go inside `lifeControlApiRoute()` and share `lifeControlApiCircuitBreaker` | `Routes.java:57-79` |
| E6 | Without an `activity_processes` row named `SCHEDULING`, the audit trail **silently skips** the domain (`log.warn` + `return`, best-effort) | `ActivityLogService.java:52-62`; process derived from the package segment `ActivityLogAspect.java:183`; seed idiom `V4__seed_activity_processes.sql:16-69` |
| E7 | New role procedure is 4 steps: `Roles.java` constant → `keycloak-setup.sh` client-role loop → `@PreAuthorize` with the constant → and, for store-scoped roles, `ScopeLevel.STORE.roleNames()` | `Roles.java:40-60`; `keycloak-setup.sh:153-157`; `ScopeLevel.java:60-72` |
| E8 | `verifyCompanyStoreAccess(companyId, countryId, regionId, zoneId, storeId)` throws `AccessDeniedException` (403) and resolves the broadest granted scope | `CurrentUserContext.java:282-296,359` |
| E9 | `user_id` for a Keycloak user is a `String` (claim `sub`), exactly as `Shift` does it; `Customer` is a separate UUID entity with no Keycloak link | `Shift.java:19-20`; `Customer.java:12-14`; `CurrentUserContext.java:299` |
| E10 | Pessimistic-lock precedent exists (JPQL `@Query` + `@Lock(PESSIMISTIC_WRITE)`; no literal SQL `FOR UPDATE` anywhere) | `PurchaseOrderRepository.java:60-62`; `ProductVariantStoreStockRepository.java:43-50` |
| E11 | `@Version` exists only in the store tree + settings; optimistic failure maps to **409** | `CompanyStore.java:39-41`; `V8__store_optimistic_locking.sql:9-12`; `GlobalExceptionHandler.java:83-87` |
| E12 | **412** is reserved for the version precondition and is *not* a `ConflictException` subclass; 409 is duplicates/transitions/races | `GlobalExceptionHandler.java:51-63,83-87` |
| E13 | Error body is fixed: `ErrorResponse(status, message, path, timestamp, correlationId)` | `GlobalExceptionHandler.java:216-227` |
| E14 | Status resolution by name: `findByTypeNameAndStatusName(type, name)` + `StatusValidator.requireStatusOfType` | `StatusRepository.java:27`; `StatusValidator.java:16-26`; `SalesOrderService.java:156-159` |
| E15 | Integration tests use a shared **Testcontainers** Postgres base class and run in CI (same `./gradlew test`, no filter) | `AbstractPostgresIntegrationTest.java:21-40`; `GoodsReceiptIntegrationTest.java:101-104`; `.github/workflows/api-ci.yml:30-33` |
| E16 | Gradle gates are `spotlessCheck spotbugsMain` + `test` | `.github/workflows/api-ci.yml:30,33` |
| E17 | All business entities use `LocalDateTime` and `TIMESTAMP`; **no** timezone or Jackson config exists anywhere (both `unverified` at runtime) | `Auditable.java:12,15`; `V12__goods_receipts.sql:28-31` |

### Frontend (hard constraints)

| # | Fact | Anchor |
| --- | --- | --- |
| E18 | **No date or calendar primitive exists**: no `MatDatepicker`, no `MAT_DATE_LOCALE`, no `input type="date"`, no `date-fns`/`dayjs`/`luxon`/`moment` | grep over `src/` and `package.json` — all negative |
| E19 | `LOCALE_ID = 'es-MX'`, currency `MXN`; displayed dates use `DatePipe 'dd/MM/yyyy'` | `app.config.ts:33-34`; `receipt-list.html:79` |
| E20 | No shared table, paginator, select, autocomplete, empty-state or snackbar component: raw Material + inline markup; toasts come from the app's own `NotificationService` | `src/shared/ui/index.ts:2-17`; `notification.ts:28-55` |
| E21 | Aliases are exactly `@shared/*`, `@features/*`, `@core/*`, `@app/*` | `tsconfig.json:25-28` |
| E22 | Route guard is `keycloakRoleGuard` with `data.roles` + `data.clientId`; `clientId` switches to client roles | `auth-keycloak-guard.ts:50-54`; `products.routes.ts:13-14` |
| E23 | New top-level section touches three files: `app.routes.ts`, the feature routes file, and the header menu | `app.routes.ts:13-28`; `header.ts:65-140,175-185` |
| E24 | Reads use `rxResource`; services return `Observable`; base URL always from `ConfigService.apiUrl` | `product-list.ts:62-70`; `product.service.ts:31-43`; `config.service.ts:57-60` |
| E25 | `unsavedChangesGuard` + `UnsavedChangesAware` is the shared dirty-guard contract | `unsaved-changes.guard.ts:8-9,26-44` |
| E26 | There **is** a dialog precedent returning an entity (not just a boolean): `ProductVariantDialog` closes with the saved entity or `null` | `product-variant-dialog.ts:69,123,171`; caller `product-variant-list.ts:420-431` |
| E27 | Gates: `lint` + `build` + `test:coverage:check` with floors **80 / 60 / 75 / 80**, specs colocated as `*.spec.ts` | `scripts/check-coverage.mjs:8-13`; `angular.json:84-93` |
| E28 | E2E mocks live in **one shared file** (`e2e/mocks/api.ts`) that lists endpoints, plus `e2e/mocks/keycloak.ts`; specs live in `e2e/specs/` | `e2e/mocks/api.ts:306-340`; `e2e/fixtures/app.ts:21-31` |

### Coexisting patterns that force an explicit choice

| Choice | Options found (evidence) |
| --- | --- |
| FK style | `@ManyToOne` (`GoodsReceipt.java:39-51`) vs raw `UUID` (`SalesOrder.java`). **Chosen: raw UUID + explicit joins**, matching the newer sales/purchase-order habit and avoiding lazy-loading traps in projections |
| Precondition signature | `(Long, long)` primitive (`CompanyStoreService.java:271`) vs `(Long, Long)` wrapper (`StoreInventorySettingsService.java:211`). **Chosen: primitive**, the wrapper's reference-equality trap is already documented (`version-precondition-store-tree.md:233-235`) |
| Unsaved-changes guard | shared `@core` version vs a duplicated local copy in purchases. **Chosen: the shared one** |
| Store in URL | nested path + `resolveStore` vs flat + derived store. **Chosen: flat** (D7) |

## Domain model (the contract W1+ implements)

```
scheduling_activities        the catalogue: what can be booked, per store
  id, company_store_id FK, user_id (attending employee, nullable),
  activity_name, description, duration_minutes, capacity_per_slot,
  enabled, version, created_at, updated_at
  UNIQUE(company_store_id, activity_name)

scheduling_availability      the template: when it can be booked
  id, activity_id FK, day_of_week SMALLINT 0..6, start_time TIME, end_time TIME,
  valid_from DATE, valid_to DATE, enabled, version, created_at, updated_at
  UNIQUE(activity_id, day_of_week, start_time)

scheduling_slots             the bookable instance (lazily materialized)
  id, activity_id FK, start_at TIMESTAMP, end_at TIMESTAMP,
  capacity, booked, status, enabled, version, created_at, updated_at
  UNIQUE(activity_id, start_at)          <- the idempotent upsert key
  CHECK(booked <= capacity), CHECK(booked >= 0), CHECK(capacity > 0)

scheduling_appointments      the appointment (== the task assigned to the employee)
  id, slot_id FK, activity_id FK (denormalized), company_store_id FK,
  user_id (assigned employee, nullable), customer_id FK nullable,
  status_id FK (APPOINTMENT type), notes, enabled, version, created_at, updated_at
  -- deliberately NOT unique on slot_id: capacity > 1 means several appointments per slot
```

Schema travels with the slice that uses it, which is this repo's own habit (`V11`…`V15` are each
named after one feature), so the four tables land in three migrations:

- `V16__scheduling_activities.sql` (W1) — `scheduling_activities` + the `activity_processes` row
  `SCHEDULING` (E2 idiom, E6). Without that seed row the whole domain is silently unaudited.
- `V17__scheduling_availability_slots.sql` (W3) — `scheduling_availability`, `scheduling_slots`.
- `V18__scheduling_appointments.sql` (W4) — `scheduling_appointments` + `status_types` row
  `APPOINTMENT` and its statuses `Scheduled`, `Confirmed`, `Completed`, `Cancelled`, `NoShow`.

Default status on booking = `Scheduled`.

**Booking flow (W4)**: lock the slot row → assert security scope → assert the slot belongs to the
activity and the store → assert `booked < capacity` → insert the appointment with `Scheduled` →
`booked += 1` → commit. Cancel/release decrements. Both paths are the only writers of `booked`, so
the counter cannot drift from the appointment count.

**Slot materialization (W3)**: for a requested `[from, to)` range, expand the activity's enabled
availability windows intersected with `valid_from/valid_to`, step by `duration_minutes`, and upsert
on `UNIQUE(activity_id, start_at)` — insert if absent, never touch an existing row's `booked`.

## API surface (phase 1)

```
GET    /api/scheduling/activities?storeId=&includeDisabled=     [lc-scheduling-read | lc-scheduling]
POST   /api/scheduling/activities                               [lc-scheduling]
GET    /api/scheduling/activities/{id}                          [read]
PUT    /api/scheduling/activities/{id}                          [write]  (+ version precondition)
PATCH  /api/scheduling/activities/{id}/enable                    [write]  (re-enable)
DELETE /api/scheduling/activities/{id}                          [write]  (soft delete)
GET    /api/scheduling/activities/{id}/availability             [read]
PUT    /api/scheduling/activities/{id}/availability             [write]
GET    /api/scheduling/slots?activityId=&from=&to=              [read]
GET    /api/scheduling/appointments?from=&to=&storeId=&userId=  [read]
POST   /api/scheduling/appointments                             [write]  (booking)
PUT    /api/scheduling/appointments/{id}                        [write]  (reschedule)
PATCH  /api/scheduling/appointments/{id}/status                 [write]  (status transition)
DELETE /api/scheduling/appointments/{id}                        [write]  (cancel)
GET    /api/scheduling/calendar?from=&to=&storeId=&userId=      [read]   (projection)
```

Every `/api/scheduling/**` path needs the single gateway route (E3, E5), and the controller's
`@RequestMapping` must be written on **one line** (E4).

## Slices

Review-budget forecast is a forecast, not a commitment: the repo's strategy is `ask-on-risk` with a
400-line budget (`.pi/gentle-ai/sdd-preflight.json`), so the split below is what I propose and the
user decides packaging at delivery.

| # | Slice | Scope | Forecast |
| --- | --- | --- | --- |
| **W0** | spec + record | this file + Engram mirror. **Done** | ~200 lines (doc) |
| **W1** | activities table + access | `V16__scheduling_activities.sql` (table, indexes, checks, `SCHEDULING` audit seed), `Roles.java` pair (`lc-scheduling`, `lc-scheduling-read`), `ScopeLevel.STORE`, `keycloak-setup.sh` | ~120 (mostly SQL) |
| **W2a** | Activity domain layer | entity, repository, DTO records, the two exceptions, service (store-scoped + version precondition + soft-delete + re-enable), 26 unit tests | **delivered** — 8 files, **+1154** |
| **W2b** | Activity HTTP surface + route | controller, standalone contract test, method-security slice, the single gateway route | **delivered** — 4 files, **+629** |
| **W3** | availability + slots | `V17__scheduling_availability_slots.sql`, availability read/write, window validation, range expansion + idempotent upsert, `GET slots` | ~500 → **may split** |
| **W4** | appointments + calendar | `V18__scheduling_appointments.sql` (table + `APPOINTMENT` status type and statuses), booking with pessimistic lock, status transitions, reschedule, cancel, `GET calendar` projection, Testcontainers concurrency test | ~600 → **may split** (booking / calendar projection) |
| **W5** | frontend activities | feature skeleton (`scheduling.routes.ts`), activities list + form, availability editor | ~500 |
| **W6** | frontend calendar | week/day grid + day agenda + appointment dialog (CDK, no new dependency) | ~600 → **may split** (grid / dialog) |
| **W7** | wiring + closure | `app.routes.ts` entry, header menu, `roles.ts`, e2e mocks + spec, ODD closure | ~250 |

Ahead-of-schedule note: W1 is not a user-visible unit on its own — it is the table plus the access
plumbing that W2 consumes. It is still the natural first work unit: it touches no existing contract,
irreversibly unblocks everything else, and its whole risk surface is reviewable by eye (one table,
two role constants, one script entry).

**Measured review load so far, for the packaging decision** (the repo's budget is 400 lines per PR):
W1 is 77 lines of production change plus 249 lines of record; W2a is **+1154** and W2b is **+629**, and
both are over budget on their own. The split was chosen for the *implementation*, not for the review:
W2a and W2b together are one coherent feature increment (an entity the API cannot reach is not
deliverable on its own). Packaging them into chained PRs — or into one PR with the reviewer warned —
is the user's call at delivery, and the honest number to decide with is this one.

## Gates

- Backend: `cd life-control-api && ./gradlew spotlessApply spotlessCheck spotbugsMain test --no-daemon`
  (E16), including Testcontainers integration tests where behaviour is transactional (capacity,
  booking races).
- Gateway: `cd api-gateway && ./gradlew test --no-daemon` — `GatewayRouteCoverageTests` is the gate
  that proves W1's route is wired (E3).
- Frontend (from W5): `npm run lint`, `npm run build`, `npm run test:coverage:check` (floors
  80/60/75/80), `npm run test:e2e`.
- Conventions: `project-conventions` — ODD record per slice, one worktree per unit, chained PRs,
  security gates, no Lombok, records for DTOs, constructor injection.

## Task log

| # | Task | State | Evidence |
| --- | --- | --- | --- |
| W0 | Spec + record + mirror | **done** | this file; Engram mirror `odd/scheduling-calendar/tasks`; worktree `wC` verified (`git worktree list` + `.git` file) |
| W1 | activities table + access | **done** | 6 files, **`bcff2b4`** (+77 −11): `V16__scheduling_activities.sql` (new, 47 lines), `Roles.java` (+2 constants), `ScopeLevel.java` (STORE list + javadoc), `keycloak-setup.sh` (2 client roles), and the two pinned tests updated under explicit authorization. Gates: `spotlessApply`/`spotlessCheck`/`spotbugsMain`/`test` all successful, 2154 tests / 0 failures; independent verification 9/9 upheld |
| W2 | Activity CRUD + route | **done** | Two work units: **`8cd5a25`** (W2a — 8 files, +1154: entity, repository, DTOs, exceptions, service, 26 unit tests) and **`8e344ad`** (W2b — 4 files, +629: controller, contract test, security slice, gateway route). Gates: API 2200 tests / 0 failures / 0 errors / 0 skipped, gateway 7/7 including `GatewayRouteCoverageTests`, `spotlessCheck`+`spotbugsMain` green |
| W3 | availability + slots | pending | — |
| W4 | appointments + calendar | pending | — |
| W5 | frontend activities | pending | — |
| W6 | frontend calendar | pending | — |
| W7 | wiring + closure | pending | — |

## Evidence log

| Date | Evidence |
| --- | --- |
| 2026-09-26 | Original design explored in an OpenCode host (`ses_f1e7f9c00ffeOQDaPOBWZE7rMv`); its Engram write failed, so nothing was persisted. Three decisions taken there: mixto actor, three phases, slots + capacity. |
| 2026-09-27 | Design recovered verbatim from `~/.local/share/opencode/opencode.db`; saved as Engram observation **2467** (the earlier loss is the reason this record exists at all). |
| 2026-09-27 | User closed the open `user_id` question (D1) and authorized the ODD start. |
| 2026-09-27 | Two read-only scouts mapped backend/gateway (E1–E17) and frontend (E18–E28) against `main @ 091660a`. |
| 2026-09-27 | The two read-only scouts' findings were folded into this record as E1–E28 with `file:line` anchors before any code was written. |
| 2026-09-27 | **W1 implemented** by one scoped writer: `V16__scheduling_activities.sql` (47 lines), `Roles.java` (+2 constants), `ScopeLevel.java` (STORE list + javadoc) and `keycloak-setup.sh` (2 client roles), plus two tests that pinned the state this slice changes — updated under explicit authorization, as an entirely mechanical 11-line diff. Committed as **`bcff2b4`** (6 files, +77 −11). |
| 2026-09-27 | **W1 gates**: `./gradlew spotlessApply`, then `spotlessCheck spotbugsMain`, then `test` — all BUILD SUCCESSFUL, **2154 tests / 0 failures / 0 errors / 0 skipped**, migration head read as `16` with `pending()` empty on the Testcontainers PostgreSQL with `ddl-auto=validate`. |
| 2026-09-27 | **Independent read-only verification**: 9/9 claims UPHELD — the migration applies on a real PostgreSQL, the table matches this record's model column by column, the seed matches V4's shape and is guarded, the roles are present in all three places, no `lc-*` literal entered a `@PreAuthorize`, `bash -n` on the script is clean, the two test diffs stayed mechanical, the blast radius is exactly the six paths, and the anchor is still clean on `main`. |
| 2026-09-27 | **W2a implemented**: the domain layer — `SchedulingActivity` (raw-UUID store FK, `@Version`), repository, the two DTO records, the two exceptions extending the existing generic categories (so no handler change), and `SchedulingActivityService` with `resolveStore` + `verifyCompanyStoreAccess` on the flat derivation, the primitive-form version precondition, soft-delete and re-enable. Committed as **`8cd5a25`** (8 files, +1154). Gates: 2180 tests / 0 failures (baseline 2154 + 26 new); independent verification returned 8 of 9 claims upheld. |
| 2026-09-27 | **The one partial claim in W2a was my wording, not the code.** I claimed `verifyCompanyStoreAccess` runs before any read of scheduling data on *every* path; on the by-id paths the activity is loaded first and the store is resolved after. That is exactly what `goodsreceipt/service/GoodsReceiptService.java:297-303` already does, so the ordering matches the repo's precedent and was kept deliberately: changing it would diverge from the codebase for no security gain (the ids are UUIDv4 and the by-id paths still authorize before any write). The claim was wrong; the code is not. |
| 2026-09-27 | **W2b implemented**: `SchedulingActivityController` (thin passthrough — the verifier confirmed no re-derived scope, no second guard, no duplicated precondition), a standalone MockMvc contract test that captures the `Pageable` and asserts 404/412 at the HTTP boundary, a method-security slice pinning the read/write role split, and one line in `Routes.java`. Committed as **`8e344ad`** (4 files, +629). Gates: API 2200 tests / 0 failures / 0 errors / 0 skipped, gateway 7/7. |
| 2026-09-27 | **The gateway gate was reproduced, not trusted**: the verifier re-ran `GatewayRouteCoverageTests`' own two regexes over their real trees and computed 23 API prefixes (including `scheduling`) against 24 gateway prefixes, with `unreachable = ∅`, both above the guard's 15/15 positive-control minimums. A passing test on a prefix the guard cannot see would have been the false green this check exists to prevent. |
| 2026-09-27 | **Design decisions kept from the two verifications**: `create` answering 400 on a null `companyStoreId` has precedent (`GlobalExceptionHandler.java:58` plus `InventoryService`/`PurchaseOrderService`/`StatusValidator` throwing it for invalid input), and `@PageableDefault(size = 12)` matches the dominant convention (10 of 12 paginated controllers) rather than being an outlier. |

## Open gaps (declared, not hidden)

| # | Gap | Why it is not fixed here |
| --- | --- | --- |
| G1 | **No timezone handling** (D12): slots are store-local wall-clock `TIMESTAMP`, no conversion, no DST rule. Neither a Jackson config nor a JDBC timezone parameter exists in the repo (E17, both `unverified` at runtime). | Fixing it repo-wide is a separate change and touches every existing entity. A booking product *will* eventually need it; it deserves its own record rather than being smuggled into this one. |
| G2 | **Slot step is not configurable** (D11): the grid is `duration_minutes`. | Simplest rule that meets the agreed requirement; revisit when a real store asks for 15-minute services inside a 60-minute capacity window. |
| G3 | **No notification / reminder** on booking or cancellation. | Out of phase 1 scope; needs a delivery channel decision that does not exist yet. |
| G4 | **Appointment status transitions are not yet enumerated** as a `Map<String, Set<String>>`. | It belongs to W4 where it is tested; declaring it here without evidence would be a guess. |
| G5 | Phase 2 and phase 3 (customer account, public booking) are undesigned. | Deliberate: `D5` phases them, and designing them now would freeze choices phase 1 has not validated. |
| G6 | The frontend has **no date-entry primitive at all** (E18): the availability editor's time inputs are new ground. | Accepted cost of D14 (no new dependency). If it proves painful, the alternative is a dependency decision by the user, not a silent `npm install`. |
| G7 | `GoodsReceiptIntegrationTest` pins the Flyway head as the literal string `"16"`, so V17 (W3) and V18 (W4) must edit that test again, and its `@DisplayName`/comment must move with it. The assertion itself earns its keep (an unapplied migration leaves `pending()` non-empty); the literal is the maintenance cost. | Deriving the expected head from the migration directory changes an existing test's contract, which is its own work unit rather than a rider on W1. Recorded so the next two slices expect the edit instead of being surprised by it. |
| G8 | W1 could not validate `scheduling_activities` against a JPA entity: none exists until W2, so `ddl-auto=validate` proves only that Flyway applied V16, not that an entity matches the table. | By construction of the slice split. **Closed by W2a**: the entity now maps column for column and the integration tests start the context with `ddl-auto=validate`. |
| G9 | The method-security slice test pins the **annotations**, not production's `@EnableMethodSecurity` in `config/security/SecurityConfig.java`, nor the JWT→`ROLE_*` mapping that makes `lc-scheduling` resolvable. Removing that enablement from production would not fail any test, in either module. | Same limitation as the pre-existing `StoreInventorySettingsControllerSecurityTest` this test mirrors, so it is the repo's accepted level of proof rather than a regression introduced here. A test that asserts the production security configuration is its own work unit. |
| G10 | `SchedulingActivityControllerTest` stubs the service, so it cannot catch entity→DTO mapping drift, and it asserts only some response fields (`description`, `userId`, `version`, `createdAt`, `updatedAt` are unasserted). | The mapping is covered by the committed `SchedulingActivityServiceTest`; the unasserted fields are the ordinary cost of a contract test. Declared so a future rename is not mistaken for a covered change. |
