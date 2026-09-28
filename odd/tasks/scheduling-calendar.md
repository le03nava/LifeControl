# ODD feature: scheduling-calendar

**Status**: **W4 delivered** — appointments are bookable and readable end to end. `V18` creates
`scheduling_appointments` and seeds the `APPOINTMENT` status family;
`POST`/`GET`/`PUT`/`PATCH .../status`/`DELETE /api/scheduling/appointments` book a slot, read one
appointment, reschedule it, move its status and cancel it; and `GET /api/scheduling/appointments`
plus `GET /api/scheduling/calendar` project a store's range, the second answering one entry per
materialized slot with its own appointments. Booking locks the slot row and keeps `booked` equal to
the appointments holding capacity, and neither read materializes a slot, so this domain's write volume
stays attached to `GET /slots`. All under the existing `lc-scheduling` / `lc-scheduling-read` pair.
W3 preceded it and is merged: PR **#198** (`feat/scheduling-availability` @ `7e5920c`, merge
`a28f28d`) and PR **#199** (`feat/scheduling-slots` @ `77efff6`, merge `016cdd6`), 2026-09-28. What
remains: **W5**/**W6** (frontend, which inherit **G18**'s duplicated range guard, **G19**'s
materialize-first contract and **G20**'s invisible-orphan behaviour) and **W7** (wiring and closure).
This header makes no claim about push or PR state; see the evidence log.
Gaps G1–G17 stay as declared, with **G13** and **G14** closed by W3b. Gate results, verification
rounds and commit identities are in the evidence log.
**Repository**: LifeControl — spans `life-control-api/**` (Spring Boot, Java 21, PostgreSQL +
Flyway), `api-gateway/**` and `life-control-app-angular/**` (Angular 20.3 + Material/CDK 20).
**Created**: 2026-09-27 · **Risk**: **high** out of the gate — new domain with four tables, a new
authorization role pair, a new gateway route, a booking path that serializes concurrent writers, and
a calendar UI built from scratch because no date/calendar primitive exists in the app. No existing
contract changes and no data migration.
**Branches**: one per slice, all merged — `feat/scheduling-foundation` (W1),
`feat/scheduling-activity-domain` + `feat/scheduling-activity-api` (W2), `feat/scheduling-availability`
(W3a) and `feat/scheduling-slots` (W3b); see the task log. · **Base**: `main` @ `091660a` (W0) · **Worktree**:
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
| D15 | `PUT .../availability` **replaces the whole window set** of the activity (delete-then-insert in one transaction) | The template is a set of windows; a merge protocol would need per-window identities the model does not carry. Whole-set validation (day 1..7, `end_time > start_time`, `valid_from <= valid_to`, no overlap inside one `day_of_week`) is then checkable in a single pass |
| D16 | `GET .../slots?from=&to=` **materializes** the requested range lazily via the native `INSERT … ON CONFLICT (activity_id, start_at) DO NOTHING` idiom that already exists (`ProductVariantStoreStockRepository.java:63-69`), guarded by a **maximum range** rule | D10's lazy materialization needs a trigger and there is no scheduler. The guard keeps an absurd range from turning one read into an unbounded insert. Consequence declared in **G11** |
| D17 | `day_of_week` is **ISO-8601 `1..7` (`MONDAY=1` … `SUNDAY=7`)**, in the column and in the API, instead of the `0..6` this record sketched | No day-of-week convention exists anywhere in the repo — backend, gateway and frontend greps are all negative — so the sketch's `0..6` had no origin and `0` could mean Sunday or Monday. ISO needs no conversion in Java (`LocalDate.getDayOfWeek().getValue()`), and the choice is pinned at the HTTP boundary by the DTO's `@Min(1) @Max(7)` for **both** ends of the range (`0` and `8` → 400). The day→date mapping — a `MONDAY` window landing on a Monday — is **W3b's** to prove with the materialization tests and is not asserted by W3a. The frontend slices (W5, W6) map `Date.getDay()` with one documented `0 → 7` rule |
| D18 | `PUT .../availability` carries **no version precondition**; the whole window set is last-writer-wins | The record's own API surface marks the activity `PUT` with `(+ version precondition)` and the availability `PUT` without it, because the set has no single version to assert. Each window row keeps its own `version` for future per-window operations |
| D19 | Replacing the window set **deletes the activity's unbooked slots** (`booked = 0`, past ones included) in the same transaction; booked slots survive and are lazily re-materialized from the new windows | G14's reconciliation without building a reconciliation engine: every unbooked slot is *derived*, so dropping it and letting the next read restore what the new windows imply is exact, and it is deterministic because no clock is consulted. `booked > 0` is the one boundary that must hold, because a real appointment depends on that row. Consequence: slot identity is not stable across a schedule edit (**G17**) |
| D20 | The materialization range guard is concrete: `to` strictly after `from`, and a span of at most **90 days**, otherwise 400 | D16's "maximum range" needs a number. The guard plus W3a's 50-window cap is the entire bound on how many rows one read can create, so no second cap is needed: at most 50 windows × the 13 occurrences of a weekday inside 90 days |
| D21 | The slot response carries `available = capacity - booked`, **derived and never a column** | One source of truth: a stored `available` would be a third counter to drift from the other two. W4's booking path can expose the same derivation with no migration |
| D22 | The appointment maps `status_id` as a **raw `UUID statusId`**, not an association; the response exposes `statusId` + `statusName`, resolved in the service by one batch `findAllById` over the distinct ids of the page | The three existing scheduling entities all keep raw UUID FKs with the service loading explicitly, so an association here would be the package's only exception. The batch read is what keeps a list read from becoming N+1 |
| D23 | Status family: type **`APPOINTMENT`** with statuses `Scheduled`, `Confirmed`, `Completed`, `Cancelled`, `NoShow`; booking always creates `Scheduled` and the create request carries no status | Closes **G4**. A **holding** status occupies capacity: `Scheduled`, `Confirmed`, `Completed`. The two releasing ones are `Cancelled` and `NoShow`. Booking cannot take a non-holding status, so the increment is on a constant edge |
| D24 | Transitions are a `Map<String, Set<String>>` keyed by status **name**, terminality = empty set: `Scheduled → {Confirmed, Completed, Cancelled, NoShow}`, `Confirmed → {Completed, Cancelled, NoShow}`, the other three → `{}`; an invalid edge is 409 | Exactly `SalesOrderService.SO_TRANSITIONS` + `validateSOTransition` (`:856-863`), and the same `InvalidStatusTransitionException` extends `ConflictException` → 409 (`GlobalExceptionHandler.java:51-53`). No new exception, and `StatusValidator` (type membership) is used for the create path, not for transitions — that split is the repo's |
| D25 | Capacity is released **by status, exactly once**: `+1` on booking, `-1` only on an edge from a holding status into `Cancelled`/`NoShow`. `DELETE /appointments/{id}` is that same release plus `enabled = false` | One rule, two entry points, one counter. Because both releasing statuses are terminal, an appointment can only be released once, so the decrement is idempotent by construction and `DELETE` on an already-cancelled row does not decrement twice |
| D26 | `scheduling_slots.status` becomes `'Available'`/`'Full'`: written by the booking path, `Full` exactly when `booked == capacity`, back to `Available` when a release drops it below | Closes **G16** with the string column `V17` already created (D8 scopes the catalogue to appointments; `Shift.status` is the plain-string precedent). A derived `available` (D21) stays derived |
| D27 | Lock order for the slice: **appointment rows before slot rows**, and a transaction touching two slots locks them in ascending `(start_at, id)` | Every write path except booking is addressed by an appointment id, so the appointment is always identified first, and booking has no appointment to lock. The order is cycle-free: no path ever holds a slot lock and then waits for an existing appointment row (booking holds a slot only to insert). The two-slot case is real — two opposite reschedules (A→B while B→A) would deadlock without a global order — and `start_at` of an existing slot never changes (materialization only inserts absent rows, D10), so it orders two locks without reading under them. Mirrors the documented lock-order contract of `GoodsReceiptService.java:41-64` |
| D28 | The pessimistic lock is the transaction's **first** database interaction, and the `booked < capacity` guard runs **inside** it, before the insert | Same rule `GoodsReceiptService` documents for its purchase-order lock (`:152-163`). The counter is the concurrency control here, not `@Version`: two writers must not both see room. The guard cannot move outside the lock or the race returns |
| D29 | Reschedule is allowed **only while the appointment's status is non-terminal** in the transition map (`Scheduled`, `Confirmed`); a terminal one (`Completed`, `Cancelled`, `NoShow`) is 409 | Moving a `Completed` appointment would rewrite what already happened and would double-count capacity — it would take room in a new slot while the completed one still occupies its own. A `Cancelled` or `NoShow` one already released its seat, so moving it would take room it does not hold. The authority is the same map D24 defines, **not** a second list: changability is terminality, holding is capacity, and `Completed` is the one status where the two differ (holding and terminal) |
| D30 | `user_id` on an appointment is a **`VARCHAR(255)` string** (the Keycloak `sub`), nullable, **no FK and no existence check**; an omitted value stays unassigned | Matches `scheduling_activities.user_id` and `shifts.user_id`; the schema has **no `users` table** (grep over `db/migration/**` is negative), so an existence check is not expressible. Not defaulting it to the caller: the record's own model makes it optional, and inventing a default would silently assign work |
| D31 | `customer_id` is validated **for existence only** (`CustomerNotFoundException` → 404), never for store or company membership | `customers` has no store/company column and no FK to one (`V1__baseline_schema.sql:381-390`), so membership is not expressible; `SalesOrderService.validateCustomerExists` (`:792-795`) is the exact precedent |
| D32 | Range reads (`GET /appointments`, `GET /calendar`) share one guard: `to` strictly after `from` and at most **90 days**, else 400 through a new `InvalidSchedulingRangeException` | One documented bound for every range query in the domain. The new exception deliberately coexists with W3b's slot-scoped one rather than renaming a merged contract — declared as **G18** |
| D33 | `GET /appointments` and `GET /calendar` are **read-only**: they project materialized slots and never insert one | Keeps this domain's write volume attached to one endpoint (`GET /slots`, D16, already reviewed and merged). A store-wide calendar that materializes every enabled activity's range would amplify one read into a write across the whole catalogue. Declared as **G19** |
| D34 | `GET /calendar` answers **one entry per materialized slot** in the range, each carrying the slot's own facts plus its own appointment list | A calendar grid must render an empty slot and its remaining room, which a flat appointment list cannot express. The slot's `available` is D21's derivation and is not stored |
| D35 | The projection resolves names in **three batched reads** — slots, then appointments by slot ids, then status and customer names by distinct ids — never one query per row | The single-read cost of a week view is what a per-row lookup would turn into N+1 on the endpoint a UI calls most. W4a's appointment read already resolves status names in one batch (D22) for the same reason |
| D36 | The calendar carries **soft-deleted appointments with their `enabled` flag** instead of filtering them out, and its `userId`/`activityId` filters are optional | By the DELETE rule a soft-deleted `Completed` appointment still holds capacity and is still counted by `booked`, so hiding it would make the calendar contradict the counter it renders. Which rows a client draws is a client decision, declared here because W6 inherits it |
| D37 | The calendar **never filters by the activity's `enabled` flag**: it renders every materialized slot of the store in the range and exposes the activity's `enabled` on the entry | D36's reason applies unchanged — `booked` counts a booked slot that `DELETE /activities/{id}` never touched, because that endpoint only flips the flag and deletes no slot at all (the availability `PUT` is what removes unbooked slots, D19) — so hiding a booked slot because its activity was retired would make the calendar contradict the counter it renders. Which activities a client draws, or offers for booking, is W6's decision, and the flag is exposed for exactly that |

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
  id, activity_id FK, day_of_week SMALLINT 1..7 (ISO-8601, MONDAY=1 … SUNDAY=7 — D17),
  start_time TIME, end_time TIME, valid_from DATE, valid_to DATE (both required),
  enabled, version, created_at, updated_at
  UNIQUE(activity_id, day_of_week, start_time)
  CHECK(day_of_week BETWEEN 1 AND 7), CHECK(end_time > start_time), CHECK(valid_from <= valid_to)
  INDEX(activity_id)

scheduling_slots             the bookable instance (lazily materialized)
  id, activity_id FK, start_at TIMESTAMP, end_at TIMESTAMP,
  capacity, booked, status, enabled, version, created_at, updated_at
  UNIQUE(activity_id, start_at)          <- the idempotent upsert key
  CHECK(booked <= capacity), CHECK(booked >= 0), CHECK(capacity > 0), CHECK(end_at > start_at)
  INDEX(activity_id, start_at)

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

## W4 design — booking, lifecycle and the capacity invariant

**Booking flow (W4a)**: take the slot's **row lock** (D28) → read the activity from the locked slot →
assert the caller's scope on the activity's store → assert `booked < capacity` (else 409) → resolve the
`Scheduled` status **by name** from the catalogue → insert the appointment → `booked += 1` → set the
slot's `status` to `Full` when `booked == capacity` (D26) → commit.

**Capacity invariant (D25)**: `slot.booked` equals the number of appointments on that slot whose
status **holds** capacity (`Scheduled`, `Confirmed`, `Completed`). The counter moves on exactly two
edges — booking (`+1`, always into a holding status) and releasing (`-1`, only when a transition moves
an appointment **out of** a holding status into `Cancelled` or `NoShow`) — so the counter cannot drift
from the appointment rows. `DELETE /appointments/{id}` is that release plus `enabled = false`, so
cancelling twice does not decrement twice.

**Status transitions (D23, D24)**: `Scheduled → {Confirmed, Completed, Cancelled, NoShow}`,
`Confirmed → {Completed, Cancelled, NoShow}`, `Completed → {}`, `Cancelled → {}`, `NoShow → {}`. The map
is keyed by status **name** and terminality is an empty set, exactly the shape
`SalesOrderService.SO_TRANSITIONS` (`:65-70`) already uses; an invalid edge is a **409** through the
shared `InvalidStatusTransitionException`. Booking always creates `Scheduled`: the create request
carries no status.

**Reschedule (D29)**: `PUT /appointments/{id}` moves a **non-terminal** appointment to another slot — lock the appointment, then the two slots in ascending `(start_at, id)` order (D27), release on the source, take room on the target with the same `booked < capacity` guard, keep the status. A terminal appointment is a 409, and `Completed` — holding but terminal — is the case that pins the difference between the two authorities.

**Response shape (W4a)**: `id`, `slotId`, `startAt`/`endAt` (read from the slot — an appointment's time
**is** its slot's), `activityId`, `companyStoreId`, `userId`, `customerId`, `statusId` + `statusName`,
`notes`, `enabled`, `version`, `createdAt`/`updatedAt`. Activity and customer **names** join in W4b's
calendar projection, not here.

**Reads are reads (D33)**: `GET /appointments` and `GET /calendar` (W4b) project what is materialized
and never insert a slot. `GET /slots` stays the one bounded materializing read (D16), so the write
volume of this domain is still capped by one endpoint. Consequence declared in **G19**.

**W4b design — the two reads.** `GET /appointments?from=&to=&storeId=&userId=` is the flat, filtered
list (slot start inside `[from, to)`, ordered by start, `userId` optional). `GET /calendar` is the
projection of D13 in D34's shape: one entry per materialized slot of the store inside the range —
whatever its activity's `enabled` flag says (D37) — each with its activity name and flag, capacity,
`booked`, derived `available`, slot status, and its appointments. Both take `storeId` as an explicit parameter and verify the caller's scope
against it directly (no activity to derive it from), both apply D32's range guard, and neither
materializes (D33). Writes are W4a's and are not touched here.

**Slot materialization (W3)**: for a requested `[from, to)` range, expand the activity's enabled
availability windows intersected with `valid_from/valid_to`, step by `duration_minutes`, and upsert
on `UNIQUE(activity_id, start_at)` — insert if absent, never touch an existing row's `booked`. The
requested range is rejected with 400 when it is inverted or wider than the materialization guard
(D16).

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
| **W3a** | availability template + `V17` | `V17__scheduling_availability_slots.sql` (both W3 tables, so `V18` stays free for W4), the availability entity/repository/DTOs/validation, `GET`+`PUT /activities/{id}/availability`, the `GoodsReceiptIntegrationTest` head bump | ~350 |
| **W3b** | slot materialization + reconciliation | slots entity + repository (native idempotent upsert), window→slot range expansion service, `GET /slots` with the range guard (D20), and the window→slot reconciliation the `PUT` owes (D19, closing G14) | ~300 |
| **W4a** | appointments + booking lifecycle | `V18__scheduling_appointments.sql` (table + `APPOINTMENT` status type and its five statuses), entity/repository (the `FOR UPDATE` slot finder), DTOs, the transition map and the capacity invariant, booking, status transition, reschedule, cancel, `GoodsReceiptIntegrationTest` head bump, Testcontainers booking-race test | ~1700 → **over budget, knowingly** |
| **W4b** | appointment reads + calendar projection | `GET /appointments` (filters) and `GET /calendar` (the joined projection of D13), their DTOs and the shared range guard | ~800 → **over budget, knowingly** |
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
| W3a | availability template + `V17` schema | **done** | **`5760620`** (15 files, **+1942 −5**): `V17__scheduling_availability_slots.sql` (67 lines, both W3 tables), the availability entity, repository, four DTO records and `InvalidSchedulingAvailabilityException`, `SchedulingAvailabilityService` (whole-set replace, `deleteByActivityId` → `flush()` → `saveAll`), `SchedulingAvailabilityController` (`GET`/`PUT .../availability`, one-line `@RequestMapping`), four test classes, and the `GoodsReceiptIntegrationTest` head literal `16` → `17`. Gates: API **2240 tests / 0 failures / 0 errors / 0 skipped**, gateway 7/7, `spotlessCheck`+`spotbugsMain` green. Two independent read-only verifications: the first upheld 9 of 14 claims (the one refutation was this record's, not the code) and all five findings were fixed; the second re-adjudicated every one of them **upheld** |
| W3b | slot materialization + `GET slots` + reconciliation | **done** | **`2b89cd0`** (12 files, **+1663 −1**): `SchedulingSlot` and its repository (the native `INSERT … ON CONFLICT (activity_id, start_at) DO NOTHING`, the ordered range finder, the `booked = 0` delete), `SchedulingSlotResponse` with the derived `available`, `InvalidSchedulingSlotRangeException`, `SchedulingSlotService` (window expansion, the 90-day guard, store scope), `SchedulingSlotController`, four test classes, and the D19 reconciliation inside `SchedulingAvailabilityService`. Gates: API **2268 tests / 0 failures / 0 errors / 0 skipped** (the whole suite, not just scheduling), gateway 7/7, `spotlessCheck`+`spotbugsMain` green. Two independent read-only verifications: the first upheld 8 of 12 claims and its five findings were fixed; the second re-adjudicated all five **upheld** |
| W4a | appointments + booking lifecycle | **done** | **`f364e6a`** (19 files, **+3027 −5**): `V18__scheduling_appointments.sql` (table, four indexes, the idempotent `APPOINTMENT` status family), `SchedulingAppointment`, its repository, a `findByIdForUpdate` added to `SchedulingSlotRepository`, four DTO records, four exceptions, `SchedulingAppointmentService` (transition map, the two counter edges, the lock-order contract, the capacity invariant), `SchedulingAppointmentController` (one-line `@RequestMapping`), four test classes, and the `GoodsReceiptIntegrationTest` head literal `17` → `18`. Gates: API **2346 tests / 0 failures / 0 errors / 0 skipped**, `spotlessCheck --rerun-tasks` executed clean, `spotbugsMain` green. Two independent read-only verifications: the first upheld 13 claims and raised four findings (one medium, on the booking-race test's load-bearing power); all four were fixed and the second re-adjudicated every one **upheld**, with no findings |
| W4b | appointment reads + calendar projection | **done** | **`77f5088`** (18 files, **+2159 −0**): `SchedulingRangeGuard` + `InvalidSchedulingRangeException` (D32's guard, the G18 note carried on it), the appointment list read and its two finders, `SchedulingCalendarService` + `SchedulingCalendarController` + `SchedulingCalendarSlotProjection` + the two nested response records (D34–D37), and four test classes including a two-activity filter case, the unmaterialized-range proof and the exact-ordering case. Gates: API **2391 tests / 0 failures / 0 errors / 0 skipped**, `spotlessCheck --rerun-tasks` executed clean, `spotbugsMain` green. Two independent read-only verifications: the first upheld 11 claims and raised three low findings (a whole-list 404 from a single orphaned appointment, a vacuous no-materialization assertion, an untested `activityId` filter); all three were fixed and the second re-adjudicated every one **upheld**, with no findings |
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

| 2026-09-28 | **W3a implemented** on branch `feat/scheduling-availability` off `main @ 92b483e` and committed as **`5760620`** (15 files, **+1942 −5**): `V17__scheduling_availability_slots.sql` (both W3 tables with their CHECKs, UNIQUEs and indexes), `SchedulingAvailability` (raw-UUID activity FK, ISO `short dayOfWeek`, `LocalTime`/`LocalDate`, `@Version`), its repository, four DTO records, `InvalidSchedulingAvailabilityException` (400 by inheritance from `IllegalArgumentException`), `SchedulingAvailabilityService` (store scope re-derived from the activity's own store, whole-set replace with `deleteByActivityId` → `flush()` → `saveAll`), `SchedulingAvailabilityController`, four test classes, and the `GoodsReceiptIntegrationTest` head literal `16` → `17`. |
| 2026-09-28 | **W3a gates**: `spotlessCheck spotbugsMain test` — BUILD SUCCESSFUL, **2240 tests / 0 failures / 0 errors / 0 skipped**; gateway `test` **7/7** including `GatewayRouteCoverageTests`; the new controller's `@RequestMapping` re-read as a single line, so that gate still sees the mapping rather than passing blind. |
| 2026-09-28 | **The writer reported a defect against its own work**: `PUT .../availability` echoed the request order through `saveAll` while `GET` answered sorted, so one contract had two orders. Fixed before the first gate run by reading the response back through the same ordered finder the read path uses, with a unit test and the PostgreSQL round trip pinning the equality for an unsorted body. |
| 2026-09-28 | **First independent verification: 9 of 14 claims upheld, 1 refuted, 4 partial.** The refutation was this record's, not the code's: D15 still said `day 0..6` and D17 claimed tests that did not exist (an `8` rejection, and a `MONDAY` window landing on a Monday). Both were corrected here, and the day→date proof was **moved to W3b** rather than left as an unearned claim. |
| 2026-09-28 | **The four code-side findings were fixed, not declared away.** The `flush()` hazard was white-box only — a single `PUT` into a table `setUp` had already emptied, where Hibernate could reorder the pending inserts ahead of the queued delete unnoticed — so a **second `PUT` reusing the same `(day_of_week, start_time)` keys** now proves it against real PostgreSQL. The `400` of `InvalidSchedulingAvailabilityException` was never exercised at the HTTP boundary (the standalone slice stubs the service, so its 400s were bean validation), so a controller-slice case now throws it through `GlobalExceptionHandler` and asserts the message; the `8` end of the range was added alongside the `0` case. `authorizesStoreBeforeWriting` only verified the call despite its name, and now asserts the order with `InOrder`. The entity javadoc claimed `ddl-auto=validate` catches type drift, which it does not. |
| 2026-09-28 | **Second independent verification re-adjudicated all of it: upheld.** It reported the one stale fact fixed in G7 above (the head literal), and left two declared, non-blocking notes: whether the explicit `flush()` is strictly load-bearing rests on Hibernate's action ordering rather than on a mutation anyone ran, and the corrected javadoc's "only asserts the column exists" slightly **understates** `validate` — an understatement deliberately preferred over the overstatement it replaced. |

| 2026-09-28 | **W3b implemented** on the same branch and committed as **`2b89cd0`** (12 files, **+1663 −1**): the `SchedulingSlot` entity, its repository (the native idempotent upsert mirroring the two existing constructs, the ordered range finder and the `booked = 0` delete), `SchedulingSlotResponse` with the derived `available`, `InvalidSchedulingSlotRangeException` (400 by inheritance), `SchedulingSlotService` (window expansion stepping by `duration_minutes`, `[from, to)` clipping, the 90-day guard, store scope), `SchedulingSlotController` (`GET /api/scheduling/slots`, one-line `@RequestMapping`), four test classes, and the D19 reconciliation added to `SchedulingAvailabilityService`. |
| 2026-09-28 | **W3b gates**: the **full** API suite — `spotlessCheck spotbugsMain test` — **2268 tests / 0 failures / 0 errors / 0 skipped**, gateway **7/7**. The full suite earned its keep here: a focused scheduling run was green while the whole suite had one failure (next row). |
| 2026-09-28 | **One full-suite failure, diagnosed as a pre-existing flake and not a W3b regression**: `CompanyStoreVersionPreconditionIntegrationTest` failed once at its `updatedAt`-is-newer assertion, passed **5/5 in isolation on the same tree**, and the next full run was green. My first explanation — DB-stored timestamp versus JVM timestamp, with the container clock ~1 s ahead — was **falsified by the verification**: `Auditable` stamps `updatedAt` from `LocalDateTime.now()` in the JVM on both persist and update, the mapped field is always written so the column's `DEFAULT CURRENT_TIMESTAMP` never fires, and PostgreSQL therefore produces neither value. **The mechanism is not established.** The best remaining candidate is a backwards host clock step between the two samples, and it is unproven. Recorded rather than explained away, and a candidate follow-up work unit. |
| 2026-09-28 | **First independent verification of W3b: 8 of 12 claims upheld.** Its five findings were fixed rather than declared: the slice's central guarantee (re-materialization never touching `booked`) had **no** behavioural proof, so a booked row now survives a second materialization of the same range against real PostgreSQL; the `validTo` upper bound was unasserted, so one fixture now has both an inside and an after-validity day; the range guard's 400s were mock-level only, so inverted, over-90 and exactly-90 now run through the real service to HTTP; the `available` fixture (`capacity = 4, booked = 2`) was mutation-blind, so it is now `4 / 1 / 3`; and the guard's comment justified itself with an unverifiable product claim about booking horizons, which the checkable window-cap reason replaced. |
| 2026-09-28 | **Second independent verification re-adjudicated all five: upheld**, confirming the re-materialization proof re-runs the same `ON CONFLICT` path over a `booked > 0` row (`created=0` on the second materialization), and that no existing assertion was weakened. It also reported the stale `Status` header this commit fixes, and named two remaining mutation-blind spots kept deliberately rather than hidden: the `materializesRangeWithExactDateTimes` fixture keeps `4 / 0 / 4`, so a substitution of `capacity` for `available` survives *there* (the other two fixtures reject it), and `validateRange`'s first javadoc sentence mostly re-reads its two `if`s (its second sentence carries the inclusive-end and exactly-90 facts). |
| 2026-09-28 | **Measured review load**: W3b is **+1663 −1** in one commit, also about four times the 400-line budget. W3 as a whole, including this record, is **26 files / +3653 −17** across two slices and four commits (two of code, two of record), so packaging is a real decision rather than a formality. |

| 2026-09-28 | **W3 delivered and merged.** Packaged as **two stacked PRs** to `main`, the same pattern W2 used: **#198** (`feat/scheduling-availability` @ `7e5920c`, merge **`a28f28d`**) for W3a and **#199** (`feat/scheduling-slots` @ `77efff6`, merge **`016cdd6`**) for W3b. The retarget of #199 to `main` after #198 landed was verified, not assumed: its visible diff stayed exactly the 13 W3b paths with **no W3a file** in it — a polluted diff would have been a branching bug. CI green on both (2 of the 4 workflows run; the Angular and Docker ones are path-filtered). Review load accepted knowingly at **1997** and **1693** changed lines against the repo's 400-line budget. |
| 2026-09-28 | **W4 was designed before any code**, as D22–D33 in this record, from one read-only scout's anchored mapping of the repo's status catalogue, transition maps, pessimistic-lock precedents, counter-guard patterns, projection style and test harnesses. The scout's own report named the absences that shaped the decisions: no `APPOINTMENT` status family, **no `users` table** (so `user_id` is a free `VARCHAR(255)` Keycloak `sub` with no existence check), **no store or company column on `customers`** (so a customer is validated for existence only), no `@Version`-guarded entity that also mutates a counted column, no interface projections, and no `@Query` range read over two `LocalDateTime` bounds. |
| 2026-09-28 | **The slice was split W4a/W4b by user decision** (booking and lifecycle / appointment reads and the calendar projection), and the open docs PR **#200** was merged first as **`376f699`** precisely so this record would not collide with it. |
| 2026-09-28 | **W4a implemented** by one scoped writer on `feat/scheduling-appointments` off `main @ 376f699`, committed as **`f364e6a`** (19 files, **+3027 −5**). |
| 2026-09-28 | **W4a gates**: focused scheduling run **192 tests / 0 failures / 0 errors / 0 skipped**; `spotlessCheck --rerun-tasks` **3 tasks executed**, BUILD SUCCESSFUL (a forced pass, not an up-to-date one); `spotbugsMain` green; **full API suite 2346 tests / 0 failures / 0 errors / 0 skipped**. |
| 2026-09-28 | **The writer reported a divergence against its own work and it was rejected.** `DELETE` moved a `Completed` appointment to `Cancelled` so that "holding rows == `booked`" would hold for every row — which silently rewrites a status the transition map declares terminal, the same rewrite D29 forbids on reschedule. The rule became **the transition map as the single authority for DELETE's release**: a terminal appointment is only soft-deleted (`Completed` keeps its status and keeps occupying its slot, `Cancelled`/`NoShow` are not decremented twice) and `DELETE` never answers 409. |
| 2026-09-28 | **First independent verification: 13 claims upheld, 2 partial, 4 findings.** One medium — the booking-race test asserted only `[201, 409]` plus the post-state, so removing *either* the pessimistic lock *or* the room guard still produced 409 through `SchedulingSlot`'s `@Version` or `V17`'s `ck_scheduling_slots_room`: it proved that serialization happened, not that the application's own control caused it. Three low — the `APPOINTMENT`-type guard was exercised only as a stubbed `IllegalArgumentException`, three 409s were mock-level only, and one transition test stubbed both slot finders so it could not detect a lost lock. |
| 2026-09-28 | **All four findings were fixed, not declared away.** The race test now asserts that the losing 409's body carries the application guard's message — the two database backstops answer with different messages (`GlobalExceptionHandler.java:72-75` for the CHECK, `:84-87` for `@Version`), so the assertion is a real discriminator rather than a second status-code check. A service-level **and** an HTTP-level wrong-status-type case now run through the real `StatusValidator`, real Testcontainers cases cover a disabled slot, an invalid transition and a terminal reschedule, and the transition test's stub set became asymmetric on purpose. |
| 2026-09-28 | **The verification forced a decision this record had left ambiguous, and the record was wrong.** D29 said "only while the appointment holds capacity", but `Completed` holds capacity and is terminal, so that wording allowed rescheduling a completed appointment — which would rewrite what happened and take room in a new slot while the completed one still occupies its own. D29 now reads **non-terminal**, the guard reads the same transition map D24 defines (changability is terminality; holding is capacity; `Completed` is the one status where they differ), and the stale javadoc and stale OpenAPI description that still said "holds capacity" were corrected in the same round. |
| 2026-09-28 | **Second independent verification: no findings.** All four closures and the D29 change were re-adjudicated **upheld**, the DELETE rule and the round-1 upheld claims were confirmed unregressed, and it reported one limit it could not close rather than smoothing it over: removing the pessimistic lock makes the race test fail only when the two transactions actually overlap, so the **guard's** removal is proven deterministically while the **lock's** is an argument. Recorded as such. |
| 2026-09-28 | **Measured review load**: W4a is **19 files / +3027 −5** in one commit, about 7.5 times the repo's 400-line budget — the honest number for the packaging decision, alongside W3's recorded 1997 and 1693. |
| 2026-09-28 | **W4b designed before any code** as D34–D37: one entry per materialized slot (a grid must render an empty slot and its remaining room, which a flat list cannot express), three batched reads with no per-row lookup, soft-deleted appointments carried with their flag because `booked` still counts them, and the range guard D32 shares across both reads. |
| 2026-09-28 | **W4b implemented** by one scoped writer, committed as **`77f5088`** (18 files, **+2159 −0**). |
| 2026-09-28 | **The implementation's own report exposed a contradiction the design had not caught**: the calendar's slot query filtered `a.enabled = true`, so a **booked** slot vanished from the calendar the moment its activity was soft-deleted — hiding exactly the rows the projection's own `booked`/`available` numbers render, which is the contradiction D36 exists to prevent. D37 now forbids filtering by the activity flag and exposes it on the entry instead, and a test pins both values so a hard-coded constant fails. |
| 2026-09-28 | **W4b gates**: focused scheduling run **237 tests / 0 failures / 0 errors / 0 skipped**; **full API suite 2391 tests / 0 failures / 0 errors / 0 skipped**; `spotlessCheck --rerun-tasks` **3 tasks executed**, BUILD SUCCESSFUL; `spotbugsMain` green. |
| 2026-09-28 | **First independent verification of W4b: 11 claims upheld, 3 findings, all low.** A concurrent availability `PUT` deleting a `booked = 0` slot between the list's two statements made **the whole store's appointment list answer 404** for one orphaned row; the "the calendar does not materialize" assertion was vacuous, because `ON CONFLICT DO NOTHING` means re-materializing the same range changes no row count; and the `activityId` filter was proven only by finder selection on a single-activity fixture. |
| 2026-09-28 | **All three were fixed rather than declared away.** The list now drops an appointment whose slot is gone instead of failing, which is exactly what **G20** already promised; the no-materialization proof reads a range nobody asked `GET /slots` for and asserts zero rows, so a calendar that materializes fails it; and a two-activity case pins the filter on ids in both directions. Two declared test-strength notes were closed alongside (a fixture where `available` and `booked` were interchangeable, and the untested `(start_at, id)` tie-break), and the D37 texts stopped citing D19 as the cause of a fact D19 does not cause. |
| 2026-09-28 | **Second independent verification of W4b: no findings.** Every closure was re-adjudicated **upheld** and the round-1 claims confirmed unregressed. It also established that the new ordering case is deterministic rather than best-effort: `ORDER BY … a.id ASC` is a total order and the test compares `UUID.toString()` in natural order, which matches PostgreSQL's byte-wise uuid ordering — the signed `UUID.compareTo` trap is called out in the test itself. Declared and left: the ordering by `activityName` has no direct test, and the orphan path is covered at unit level only. |
| 2026-09-28 | **Measured review load**: W4b is **18 files / +2159 −0**, about 5.4 times the budget. **W4 as a whole (W4a + W4b) is +5186 changed lines** across two code commits plus the record — recorded before any packaging decision is made, so the decision is made on the real number. |
| 2026-09-28 | **W4 packaged as two chained PRs by user decision**: **#201** (`feat/scheduling-appointments` @ `1543090`, W4a) and **#202** (`feat/scheduling-calendar-projection` @ `b44873f`, W4b, based on `feat/scheduling-appointments` and to be retargeted to `main` once #201 lands), the same pattern W3 used with #198/#199. W4 was **pushed but not merged**, and no PR was merged by the agent: the merge is the user's decision. |

## Open gaps (declared, not hidden)

| # | Gap | Why it is not fixed here |
| --- | --- | --- |
| G1 | **No timezone handling** (D12): slots are store-local wall-clock `TIMESTAMP`, no conversion, no DST rule. Neither a Jackson config nor a JDBC timezone parameter exists in the repo (E17, both `unverified` at runtime). | Fixing it repo-wide is a separate change and touches every existing entity. A booking product *will* eventually need it; it deserves its own record rather than being smuggled into this one. |
| G2 | **Slot step is not configurable** (D11): the grid is `duration_minutes`. | Simplest rule that meets the agreed requirement; revisit when a real store asks for 15-minute services inside a 60-minute capacity window. |
| G3 | **No notification / reminder** on booking or cancellation. | Out of phase 1 scope; needs a delivery channel decision that does not exist yet. |
| G4 | **Appointment status transitions are not yet enumerated** as a `Map<String, Set<String>>`. | It belongs to W4 where it is tested; declaring it here without evidence would be a guess. **Closed by W4a**: D24 names the five statuses and their edges, and the map is validated against the catalogue by the transition tests. |
| G5 | Phase 2 and phase 3 (customer account, public booking) are undesigned. | Deliberate: `D5` phases them, and designing them now would freeze choices phase 1 has not validated. |
| G6 | The frontend has **no date-entry primitive at all** (E18): the availability editor's time inputs are new ground. | Accepted cost of D14 (no new dependency). If it proves painful, the alternative is a dependency decision by the user, not a silent `npm install`. |
| G7 | `GoodsReceiptIntegrationTest` pins the Flyway head as the literal string **`"17"`** after W3a, so V18 (W4) must edit that test again, and its `@DisplayName`/comment must move with it. The assertion itself earns its keep (an unapplied migration leaves `pending()` non-empty); the literal is the maintenance cost. | Deriving the expected head from the migration directory changes an existing test's contract, which is its own work unit rather than a rider on W1. Recorded so the next slice expects the edit instead of being surprised by it. |
| G8 | W1 could not validate `scheduling_activities` against a JPA entity: none exists until W2, so `ddl-auto=validate` proves only that Flyway applied V16, not that an entity matches the table. | By construction of the slice split. **Closed by W2a**: the entity now maps column for column and the integration tests start the context with `ddl-auto=validate`. |
| G9 | The method-security slice test pins the **annotations**, not production's `@EnableMethodSecurity` in `config/security/SecurityConfig.java`, nor the JWT→`ROLE_*` mapping that makes `lc-scheduling` resolvable. Removing that enablement from production would not fail any test, in either module. | Same limitation as the pre-existing `StoreInventorySettingsControllerSecurityTest` this test mirrors, so it is the repo's accepted level of proof rather than a regression introduced here. A test that asserts the production security configuration is its own work unit. |
| G10 | `SchedulingActivityControllerTest` stubs the service, so it cannot catch entity→DTO mapping drift, and it asserts only some response fields (`description`, `userId`, `version`, `createdAt`, `updatedAt` are unasserted). | The mapping is covered by the committed `SchedulingActivityServiceTest`; the unasserted fields are the ordinary cost of a contract test. Declared so a future rename is not mistaken for a covered change. |
| G11 | **A read-scoped caller can create rows**: `GET /api/scheduling/slots` materializes what it is asked for (D16), so `lc-scheduling-read` performs inserts. The rows are purely derived from availability the write roles declared, are idempotent, and never touch `booked` — but the privilege boundary is no longer "reads only read". | The alternative (materialize on the availability write, or a scheduled job) contradicts D10's "no background job, no unbounded pre-generation", or needs a scheduler this repo does not have. The max-range guard bounds the insert per request. Revisit if the read role ever reaches an untrusted audience. |
| G12 | `LocalTime`/`LocalDate` crossing HTTP in a **response** body is new ground: no `spring.jackson.*` setting, no `@JsonFormat` and no HTTP `ObjectMapper` bean exist repo-wide (the one Jackson wiring is the Redis cache serializer), and no existing response field carries those types. | Pinned by an explicit JSON-format assertion in the availability controller test instead of left to the Spring Boot default: if the wire format is not the ISO one, the test fails rather than the frontend discovering it later. |
| G13 | `V17` creates `scheduling_slots` with no JPA entity until **W3b**, so during W3a `ddl-auto=validate` proves only that Flyway applied the migration, not that an entity matches that table. **Closed by W3b**: the entity maps the table column for column and the slot integration test boots the real context with `ddl-auto=validate`. | Same shape as **G8** in W1, and it closes the same way (W3b's entity maps it column for column). Splitting `V17` into two migrations would push `V18`/`V19` onto W4's appointments, which this record's migration plan does not have. |
| G14 | W3a's `PUT .../availability` replaces the window set but **does not reconcile already-materialized slots**: removing a window leaves the future, still-unbooked slots it produced, and they stay bookable. **Closed by W3b** under D19 — the replace drops the activity's unbooked slots in the same transaction and leaves the booked ones. | W3a has no slots entity to reconcile against (G13), so the rule lands in **W3b** with the table's first consumer. Declared now so W3a's semantics are not mistaken for the final ones. |
| G15 | `scheduling_availability.enabled` is stored `true` on every `PUT` and is not settable through the API: under whole-set replacement, omitting a window is how you disable it. | Keeps the repo-wide `enabled` + `includeDisabled` soft-delete convention and every table's uniform shape, and leaves a per-window `PATCH .../enable` as a pure code change with no migration. Called out so the column does not read as an unreachable trap. |
| G16 | `scheduling_slots.status` (`VARCHAR(50) NOT NULL DEFAULT 'Available'`) is created by `V17` but nothing in W3 ever changes it: the row is inserted with the default and availability is derivable from `capacity`/`booked`. | The column is in this record's own model and D8 scopes the status catalogue to appointments, so a plain string is the consistent shape (`Shift.status` is the precedent). W4's booking path is its first real writer (full ↔ available). Declared so W3 does not read as if it maintains a status it does not maintain. **Closed by W4a**: D26 makes the booking path its writer, `Full` iff `booked == capacity`. |
| G17 | Slot identity is **not stable across a schedule edit**: by D19 the `PUT .../availability` removes the activity's unbooked slots, so a client holding a slot id from before the edit can find it gone — and a booked slot whose window was removed survives until its appointment is cancelled or released. | Preserving ids for windows that did not change would need a reconciliation engine matching old rows against new windows, and booking-by-id is W4's contract to design. Declared so W4 assumes neither a stable slot id nor that removing a window cancels an existing appointment. **Closed by W4a on the client's side**: booking takes a slot id, so a stale id from before a template edit answers 404 (`SchedulingSlotNotFoundException`, thrown by the locking finder that is the booking path's first database interaction) instead of booking some other slot. |
| G18 | **Two range-guard exceptions coexist**: W3b's `InvalidSchedulingSlotRangeException` (slot materialization) and W4's `InvalidSchedulingRangeException` (appointment and calendar reads). Both mean "bad `[from, to)` range" and both answer 400. | Merging them would rename a contract W3b already shipped and reviewed (`2b89cd0`), which is its own work unit rather than a rider on W4b. Declared so the duplication is a known cost instead of an accident. |
| G19 | **A calendar read shows only materialized slots.** `GET /calendar` never materializes (D33), so a range nobody has asked `GET /slots` for yet renders empty. | The alternative — a store-wide read that inserts every enabled activity's range — amplifies one read into a write across the whole catalogue, and the bounded write path was deliberately attached to a single endpoint (D16). The cost lands on W6's contract: the week/day view asks `GET /slots` for the activities it renders, then reads the projection. Declared now, before any frontend code depends on it. |
| G20 | **An appointment whose slot row is gone can no longer be found by either range read.** D19 deletes an activity's slots with `booked = 0`, and a `Cancelled`/`NoShow` appointment releases exactly that, so the slot can be dropped while the appointment row survives. Both W4b reads range on the slot's `start_at` through a join, so that appointment disappears from the appointment list and from the calendar. | The appointment's time **is** its slot; there is no second time to range on, which makes this a consequence of D19 + D25 rather than a defect in W4b. The audit trail (the `SCHEDULING` process, E6) is this repo's authoritative history and keeps the cancellation, so the loss is confined to the two read projections. If a future requirement needs cancelled history inside the calendar, the candidate fix is to denormalize `start_at`/`end_at` onto the appointment — a schema change that is its own work unit, not a rider on a read slice. Declared so W6 meets it as a documented behaviour instead of a surprise. |
