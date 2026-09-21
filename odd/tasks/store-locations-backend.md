# ODD feature: store-locations-backend

**Repository**: LifeControl — module `life-control-api/`
**Status**: in progress
**Created**: 2026-09-18

## Objective

Add the level-4 node of the store location tree: the `store_locations` entity with full CRUD backend
(migration, entity, DTOs, repository, service, controllers, exceptions, tests) inside the existing
`com.lifecontrol.api.store.*` package tree, plus extending the existing soft-delete cascade from
zone down to location.

## Problem

`StoreZone` (level 3) shipped in PR #103 / merge `2c21fd5`, but the tree cannot continue: there is no
`StoreLocation` entity, no persistence table and no API to manage locations under a zone. The tree
`Store → Area → Zone → Location` is documented in `StoreArea.java:8`, `V5__store_areas.sql:1` and
`StoreZone.java:8`, yet only levels 1-3 exist.

Verified before starting: grep case-insensitive for
`store_location|storeLocation|store-location|StoreLocation|location_code|locationCode` across the
whole repository (excluding `node_modules`, `build`, `bin`, `coverage`, `.git`) returns **0 hits**.
Flyway stops at `V6`. There is no `lc-*location*` role in `Roles.java` and no location scope in
`ScopeLevel` (STORE is the deepest level). Level 4 is greenfield.

## Why this shape

- **Schema and API mirror `StoreZone` exactly** (same nested path pattern, same soft-delete
  semantics, same single scope-verification mechanism) so the tree stays uniform and the future
  frontend cascade can reuse its shape. Same rationale applied to `store-areas-backend` and
  `store-zones-backend`.
- **Naming must not collide with `CompanyZone`.** In the store module the bare term `zone` already
  means *company zone* (`zones` path segment, the `company_zone_id` claim, `ScopeLevel.ZONE`,
  `Roles.COMPANY_ZONE`). Level 3 therefore used the hyphenated `store-zones` / `storeZoneId`; level 4
  uses **`store-locations` / `storeLocationId`** for the same reason.
- **Authorization reuses `CurrentUserContext.verifyCompanyStoreAccess`.** The location lives *inside*
  the store, and the store is already the finest level of the scope hierarchy, so no new
  `ScopeLevel`, claim or Keycloak role is introduced. Inventing one would fork the access model for
  no requirement.
- **Package placement stays in `store.*`** so the domain boundary remains "store".
- **The cascade seam moves to the child-owning service.** Today `StoreAreaService` (level 2)
  injects `StoreZoneRepository` and holds the single `disableZonesOfAreas` implementation
  (`StoreAreaService.java:303-308`). Adding locations there would make the level-2 service know the
  level-4 repository. Instead `StoreZoneService` owns the zone→location cascade and
  `StoreAreaService` delegates, keeping exactly one implementation per edge. No dependency cycle:
  `StoreZoneService` depends on `StoreAreaRepository`, never on `StoreAreaService`.

## Scope

### In scope
- `V7__store_locations.sql` migration (table + unique constraint + index).
- `StoreLocation` entity; `CreateStoreLocationRequest` / `UpdateStoreLocationRequest` /
  `StoreLocationResponse` DTOs.
- `StoreLocationRepository`, `StoreLocationNotFoundException`, `DuplicateStoreLocationException`.
- `StoreLocationService` (list / get / create / update / soft delete / enable + flat get by id).
- `StoreLocationController` at the nested zone path + `StoreLocationFlatController` at
  `/api/store-locations`.
- Cascade: disabling a zone also disables its locations, through a single implementation owned by
  `StoreZoneService` and shared by the zone soft delete and the area/store cascades.
- Tests: service unit, standalone controller, security role-matrix, Testcontainers integration,
  plus regression updates for the existing cascade tests.
- One atomic conventional commit: `feat(store): add store locations CRUD backend`.

### Out of scope (explicitly not implemented)
- Angular frontend, inventory/stock/movements, goods receipt, sales integration, default-location
  auto-provisioning, pagination, new Keycloak roles, new `ScopeLevel` claim.
- Pre-existing gaps deliberately not addressed: **F5** (`company → … → zone` does not enforce the
  disabled-ancestor invariant and company-zone disable does not cascade to stores) and **F7** (the
  lock-free TOCTOU race in the disabled-ancestor guard, accepted as a known cosmetic limitation).

## Constraints (non-negotiable)

- NO Lombok. Entities: manual getters/setters + static `Builder`. DTOs: Java `record`s.
- Constructor injection only (no `@Autowired`, no `@RequiredArgsConstructor`).
- `var` for obvious local types.
- Entities extend `com.lifecontrol.api.common.model.Auditable`.
- Domain exceptions extend `ResourceNotFoundException` / `DuplicateResourceException`;
  never add per-class handlers to `GlobalExceptionHandler` (it dispatches by inheritance).
- `@PreAuthorize` references `Roles` constants via static import — no `lc-*` literals.
- Every endpoint carries `@Tag` / `@Operation` / `@ApiResponse`.
- Soft delete = `enabled = false`; list supports `?includeDisabled=true`; re-enable via PATCH.
- No edits to already-applied Flyway migrations (`V1`–`V6` are frozen).
- Run `spotlessApply` before verification (CI runs `spotlessCheck` + `spotbugsMain`).

## Decisions (user-locked, this session)

- **D1 — Fields are an exact mirror of level 3.** `location_code VARCHAR(10)`,
  `location_name VARCHAR(100)`, `description VARCHAR(255)`, `display_order INTEGER`,
  `enabled BOOLEAN`, plus `UNIQUE(store_zone_id, location_code)` and `idx_store_locations_zone`.
  **No** `location_type` and **no** `barcode`: a physical-semantics field without a consumer is a
  speculative schema commitment. If SHELF/AISLE/BIN semantics are needed for inventory later, that
  is a separate `V8` with its own justification.
- **D2 — The soft-delete cascade continues down one more level.** Disabling a zone disables its
  enabled locations, extending the existing `store → area → zone` chain to
  `store → area → zone → location` in one transaction. Symmetric with D3 of `store-zones-backend`
  (cascade down on disable) and D8 (re-enable never cascades).
- **D3 — Delivery is one atomic PR**, `feat(store): add store locations CRUD backend`, accepting the
  review workload overrun for the third consecutive level, matching PR #98 and PR #103.

### Accepted deviations (with rationale)

- **D4 — List endpoints are not paginated.** Same deviation accepted at levels 2 and 3. Level 3
  recorded that "if pagination is wanted, it should land for `areas` and `store-zones` together" —
  adding it now would expand this deliverable into already-shipped code, so it stays a documented
  follow-up.
- **D5 — `PATCH /{storeLocationId}/enable`.** The store subtree uses one re-enable verb
  (`.../{id}/enable`), adopted at level 2 and kept at level 3, instead of the company hierarchy's
  bare `PATCH /{id}`.
- **D6 — Review workload exceeds the 400-line budget by roughly 10x.** Accepted explicitly by the
  user as the delivery mode for this level.

### Level-4-specific notes

- **D7 invariant extends to the new leaf.** Creating or re-enabling a location requires the **entire
  enabled ancestor chain** — zone **and** area **and** store — to be enabled, per D7 of
  `store-zones-backend`. `DisabledParentException` (409) is reused; no new exception type.
- **The level ships with zero consumers.** `sales_orders`, `purchase_orders`, `shifts` and
  `product_variants` reference `company_stores` directly, and stock is the `products.stock` column,
  not a table. Levels 1-3 hang off `company_store_id`; nothing references a location. This is
  preparatory infrastructure for inventory and changes no observable system behaviour. Recorded so
  the absence of integration touch-points is a conscious decision, not an oversight.

## Tasks

- [x] T1 — Migration `V7__store_locations.sql` (`store_locations`, `UNIQUE(store_zone_id, location_code)`, `idx_store_locations_zone`).
- [x] T2 — `StoreLocation` entity (Auditable, lazy `@ManyToOne` to `StoreZone`, builder, no Lombok).
- [x] T3 — DTOs: `CreateStoreLocationRequest`, `UpdateStoreLocationRequest`, `StoreLocationResponse` (carries `storeZoneId`, `storeAreaId` + full company chain).
- [x] T4 — `StoreLocationRepository` (derived queries, including the two cascade finders) + `StoreLocationNotFoundException`, `DuplicateStoreLocationException`.
- [x] T5 — `StoreLocationService` (`resolveZone` reusing `verifyCompanyStoreAccess`, list/get/create/update/soft-delete/enable, flat `getLocationById`, `assertAncestorsEnabled` over zone + area + store, `toResponse`).
- [x] T6 — `StoreLocationController` (nested) + `StoreLocationFlatController` (`/api/store-locations/{storeLocationId}`), read/write role lists, OpenAPI, 200/201/204.
- [x] T7 — Cascade seam: `StoreZoneService` owns `disableZone`/`disableZonesOfAreas` + private `disableLocationsOfZones`; `StoreAreaService.deleteArea`/`disableAreasOfStore` delegate and drop `StoreZoneRepository`.
- [x] T8 — `StoreLocationServiceTest` (Mockito, `@Nested` per operation). 26 tests, then 28 after T16.
- [x] T9 — `StoreLocationControllerTest` (20) + `StoreLocationFlatControllerTest` (2) (standalone MockMvc).
- [x] T10 — `StoreLocationControllerSecurityTest` (`@WebMvcTest` + read/write role matrix). 63 tests, 7 endpoints.
- [x] T11 — `StoreLocationIntegrationTest extends AbstractPostgresIntegrationTest` (Testcontainers + Flyway V7) + cascade regression test. 20 tests, then 21 after T16.
- [x] T12 — Regression updates: `StoreAreaServiceTest` (delegation), `StoreAreaIntegrationTest` and `StoreZoneIntegrationTest` cleanup order (`store_locations` before `store_zones` — no FK has `ON DELETE CASCADE`; this is finding F8 from level 3). `CompanyStoreServiceTest` needed no change (it mocks `StoreAreaService` as a whole).
- [x] T13 — Verification: `spotlessApply`, `spotlessCheck --rerun-tasks`, `spotbugsMain --rerun-tasks`, `./gradlew test --tests "com.lifecontrol.api.store.*"`, full `./gradlew test`, `./gradlew bootJar --no-daemon -x test`.
- [x] T14 — Independent read-only verification of the whole candidate: 11/11 acceptance criteria PASS, **no blocking findings**, test-honesty audit clean.
- [x] T16 — Close the two non-blocking coverage findings from T14: F1 (unit tests for the extracted cascade seam) and F3 (area-level broken-hierarchy 404 at unit and HTTP level). Store package 404 → 411 tests.
- [x] T15 — Commit + branch + push + PR (`feat(store): add store locations CRUD backend`, atomic). Commit `b63f460`, branch `feat/store-locations-backend`, **PR #108**, CI green.
- [x] T18 — Final full-suite re-run on the 411-test revision: **1776 tests** (411 store + 1365 non-store), 0 failures/errors/skipped, with Testcontainers confirmed running (Flyway V7 applied against `postgres:16-alpine`).

## Acceptance criteria

1. `store_locations` exists with the documented DDL shape and `V7` is the only new migration.
2. All 6 endpoints work under
   `/api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores/{storeId}/areas/{areaId}/store-zones/{storeZoneId}/store-locations`
   plus the flat `GET /api/store-locations/{storeLocationId}`, with the documented status codes.
3. Read roles include `lc-company-store-read`; write roles do not.
4. Duplicate `location_code` inside the same zone → 409; the same code in a *different* zone is allowed.
5. Unknown location / unknown zone / broken hierarchy → 404 with the matching domain exception.
6. `DELETE` sets `enabled = false`; `GET ?includeDisabled=true` returns disabled rows;
   `PATCH /{storeLocationId}/enable` sets `enabled = true`.
7. Disabling a zone sets `enabled = false` on all of its locations; disabling an area or a store
   reaches locations through the same chain. Re-enabling never cascades.
8. Creating or re-enabling a location under a disabled zone, area or store → 409
   (`DisabledParentException`).
9. Scoped access is verified through `CurrentUserContext.verifyCompanyStoreAccess` only.
10. No Lombok, no hardcoded role literals, no new `GlobalExceptionHandler` handlers.
11. All six verification commands pass.

## Checks / evidence

| Check | Command | Result |
| --- | --- | --- |
| Formatting (writer) | `./gradlew spotlessApply` | PASS — ran in every writer slice; no file outside the surfaces was reformatted |
| Formatting (fresh) | `./gradlew spotlessCheck --rerun-tasks` | PASS — exit 0, 3 tasks executed |
| Static analysis (fresh) | `./gradlew spotbugsMain --rerun-tasks` | PASS — exit 0, no `BugInstance` (only pre-existing deprecation warnings) |
| Scoped tests | `./gradlew test --tests "com.lifecontrol.api.store.*"` | PASS — **411 tests**, 0 failures, 0 errors, 0 skipped (404 before T16) |
| Full suite | `./gradlew test --rerun-tasks` | PASS — **1776 tests** = **411 store + 1365 non-store**, 0 failures, 0 errors, 0 skipped (547 result XMLs). This is the final revision (T18 done); the earlier run on the 404-test revision gave 1769 |
| Package | `./gradlew bootJar --no-daemon -x test` | PASS — `life-control-api-0.0.1-SNAPSHOT.jar` (~88 MB) |

Baseline for comparison: the store package held **272 tests** after level 3, and the full suite
**1637 tests** (0 failures, 0 errors, 0 skipped) on `main` at `2c21fd5`.

The decisive non-regression evidence: the non-store count stayed at exactly **1365**, and
`git diff --name-only 2c21fd5 HEAD -- life-control-api/src/test/ | grep -v '/store/'` is empty, so no
test outside the store package was added, changed or dropped. Inside the store package,
411 − 272 = +139, which closes exactly against the new location tests (26 + 20 + 2 + 63 + 20) plus
the 8 added tests in `StoreZoneServiceTest` and `StoreLocationServiceTest`/`StoreLocationIntegrationTest`
from T16 — no remainder.

Docker was available for every run: the integration classes executed for real against
`postgres:16-alpine` (`Migrating schema "public" to version "7 - store locations"`,
`Successfully applied 7 migrations`, `ddl-auto=validate` against the new table) and emitted real SQL
(`insert into store_locations ...`, `update store_locations set ...,enabled=?...`). Not H2, not skipped.

## Independent verification (T14) and findings

Read-only independent verification, fresh command runs, no file edits: **no blocking findings.**
All 11 acceptance criteria PASS, and the adversarial pass over cascade correctness, authorization,
the disabled-ancestor guard, uniqueness, status-code mapping and the F8 cleanup order came back clean.
Notable confirmed properties:

- `disableZone` is `private` and called from exactly two places (`deleteZone` and
  `disableZonesOfAreas`), and it always cascades, so **there is no path that disables a zone without
  disabling its locations**. `deleteStore` still reaches locations; each level is visited once.
- `@Transactional` propagation across the `StoreAreaService → StoreZoneService` delegation is correct
  (public method, proxy-mediated, joins the caller's REQUIRED transaction) and the full Spring context
  boots, so no bean cycle.
- The negative scope case is structural: the nested chain is resolved strictly top-down and the flat
  lookup authorizes against the location's own navigated chain, with the same single
  `verifyCompanyStoreAccess` call. No reparenting is possible (`UpdateStoreLocationRequest` has no
  `storeZoneId`).
- The guard is present on create and enable and absent on update, matching level 3 and its recorded
  F10 rationale.

**Test-honesty audit: no pre-existing assertion was deleted, weakened, relaxed or silently
redirected.** The four modified test files are additive except `StoreAreaServiceTest`, whose cascade
assertions were re-pointed to the new seam (`ArgumentCaptor` + `hasSize(n).containsExactly(...)`, which
pins cardinality and ordering that the old `saveAll(List.of(zone))` did not). The assertions that zone
entities actually flip to `enabled == false` moved out of the unit test and remain covered at
real-persistence level by the unmodified `StoreZoneIntegrationTest` plus the new
`StoreLocationIntegrationTest.deleteArea_DisablesItsZonesAndTheirLocations`.

### Findings from T14 and their disposition

| ID | Severity | Finding | Disposition |
| --- | --- | --- | --- |
| F1 | non-blocking (coverage) | The extracted cascade seam (`disableZonesOfAreas`/`disableZone`/`disableLocationsOfZones`) had no unit test of its own behaviour | **RESOLVED by T16** — new `DisableZonesOfAreasTests` nested class, 4 tests, asserting zones and locations flip to `enabled = false` and the returned counts |
| F2 | non-blocking (process) | This task document claimed nothing had been written while the tree contained everything | **RESOLVED by T17** — this revision |
| F3 | non-blocking (coverage) | The area-level broken-hierarchy 404 (`StoreAreaNotFoundException` from `resolveArea`) was untested | **RESOLVED by T16** — unit (`getAllLocations_AreaNotFound`, `createLocation_AreaNotFound`) + HTTP level (`createLocation_UnknownAreaReturns404`) |
| F4 | informational | `enableLocation` runs the ancestor guard before the location lookup, so an unknown id under a disabled zone returns 409 rather than 404 | **No change** — byte-for-byte the same ordering as the shipped level 3 (`StoreZoneService.enableZone`), so this is template consistency, not a regression |
| F5 | informational, pre-existing | `AbstractPostgresIntegrationTest`'s javadoc still says "`V1` to `V3`" while Flyway now applies 7 migrations | **Recorded, not changed** — pre-existing, outside this feature's surfaces, and unrelated cleanup does not belong in a feature commit |
| F6 | informational (doc) | This document said "11 new production files + `V7`", which reads as 12 | **RESOLVED** — it is 10 new `.java` files plus `V7` = 11 main-side paths (~985 lines) |
| F7 | informational (test strength) | `StoreAreaServiceTest`'s re-pointed never-pin now asserts "no delegation" instead of "no writes", so a future cascade added through a *different* collaborator method would not be caught | **Accepted** — the pin is still correct for the new dependency, and the real write-level behaviour is covered at persistence level |
| F8 | informational (coverage boundary) | The new seam unit tests cannot observe `@Transactional` atomicity, the derived-query semantics behind `findByStoreZoneIdAndEnabledTrue`, or save-order interleaving | **Accepted and recorded** — mock-level tests cannot reach these; the integration tests cover the still-enabled-rows query and the observable end state |

Also recorded as **unverified by this candidate** (none blocking): cascade rollback atomicity is
verified by inspection of `@Transactional` propagation rather than by an observed mid-cascade failure;
no *negative* scope-narrowing test exists (a user scoped to store B fetching store A's location → 403)
because the security matrix asserts roles, not scope claims — the same coverage shape as levels 2
and 3, not a regression; and no mutation run was performed, so the D8 pins are reasoned non-vacuous
rather than observed non-vacuous.

## Progress

- Analysis done (read-only). Decisions D1–D3 locked with the user before any write.
- Task document created before the first source write, per ODD step 5.
- **Slice 1 (writer 1, T1–T7 + the forced part of T12):** 11 new production files + 2 modified
  services + `V7`; `StoreAreaServiceTest` and `StoreZoneServiceTest` adjusted for the new seam
  (the latter only to wire the new `@Mock StoreLocationRepository`, which the new constructor
  parameter requires). Store package still at exactly 272 tests, so this slice added no behaviour
  assertions.
- **Slice 2 (writer 2, T8–T11 + T12's cleanup order):** 5 new test classes (131 tests) + 1 D8 pin,
  and the leaf-first cleanup order in all three integration classes. Store package 272 → 404.
- **Slice 3 (independent verification, T13/T14):** all six commands green with fresh execution;
  11/11 criteria PASS; no blocking findings; test-honesty audit clean. Full suite 1769 tests with the
  non-store count provably unchanged at 1365.
- **Slice 4 (writer 3, T16):** closed the two coverage findings F1 and F3 (7 tests). Store package
  404 → 411.
- Final state: **22 paths in `git status --porcelain`** (6 modified, 16 untracked), nothing staged and
  nothing committed. No `git add` was run at any point. New production code is **10 new `.java` files
  plus `V7` = 11 main-side paths, ~985 lines**; new test code is **5 new classes, ~3.9k lines** (3929);
  plus +278/−73 across the 6 modified files.
- **Slice 5 (final independent verification, T18):** the whole gate suite re-run on the exact final
  revision with fresh execution — `spotlessCheck --rerun-tasks` exit 0, `spotbugsMain --rerun-tasks`
  exit 0 with no `BugInstance`, store package 411/411, full suite **1776** tests 0/0/0, fresh
  88 MB `bootJar`. Verdict: ready to commit as one atomic PR. Findings F1 and F3 confirmed genuinely
  closed by non-vacuous tests; F4–F8 all informational.
- **Known accepted overrun (D6):** ~985 production + ~3.9k test lines against a 400-line review budget
  — roughly 10x, accepted by the user as the delivery mode for this level, third level in a row.
- Deviation reported by writer 3 rather than hidden: the F1 brief asked for a `saveAll` assertion on
  `storeZoneRepository`, but production `disableZone` persists zones one at a time via `save(zone)` and
  only locations go through `saveAll`. The test asserts the real behaviour (`save` ×2 on zones plus the
  location `saveAll`) instead of demanding a production change to match the brief.

## Delivery (commit + push + PR)

| Step | Detail |
| --- | --- |
| Commit | **`b63f460`** — `feat(store): add store locations CRUD backend`, **22 files, +5192/−73**. Staged by explicit path (the store package plus `V7__store_locations.sql`) and verified before committing: `git diff --cached --name-only` listed exactly 22 paths, nothing outside the declared surfaces, and nothing was left unstaged. `odd/` is gitignored (`.gitignore:142`), so this task document stayed out of the commit, matching the `store-areas-backend` and `store-zones-backend` precedent. |
| Branch | `feat/store-locations-backend`, created from `main` **before** the commit, so local `main` was never advanced: `main == origin/main == 19faa3e` before and after. |
| Push | `git push -u origin feat/store-locations-backend` — accepted on the first attempt, tracking set. |
| PR | **#108** — https://github.com/le03nava/LifeControl/pull/108, base `main`, state `OPEN`. |
| CI | Run **35313145188** — `API CI` — `Check` **pass** in **2m33s**, every step green (`Static analysis`, `Run tests`, `Upload test report`). |
| Labels | None — the repository has no `type:feature` label (only `type:chore`, which would be wrong) and no PR template or issue-linkage workflow, matching PR #98 and PR #103. |
| Merge | **MERGED.** `gh pr merge 108 --merge --delete-branch` at **2026-09-18T14:13:16Z** → merge commit **`dd894a3`** (parents `19faa3e` + `b63f460`). Merge-commit strategy, matching the repository convention and the #98/#103 precedent. Branch deleted on the remote and locally; the stale remote-tracking ref was pruned. |

### Post-merge verification (independent, read-only, fresh executions)

| Check | Result |
| --- | --- |
| Merge payload | `git diff --shortstat 19faa3e dd894a3` = **22 files, 5192 insertions, 73 deletions** — exactly the reviewed candidate, nothing outside the store package plus `V7` |
| Merge equivalence | **`git rev-parse dd894a3^{tree}` == `git rev-parse b63f460^{tree}` and `git diff b63f460 dd894a3` is empty** — the merged tree is byte-identical to the verified PR head across the whole repository, so the merge introduced literally nothing beyond the reviewed candidate |
| Migration integrity | `V1`–`V6` blob-identical to `19faa3e`; `V7__store_locations.sql` present on `main` |
| Non-store regression | `./gradlew test --rerun-tasks` on `main` → **1776 tests = 411 store + 1365 non-store**, 0 failures, 0 errors, 0 skipped, 547 XML suites |
| Store gate on `main` | `./gradlew test --tests "com.lifecontrol.api.store.*" --rerun-tasks` → **411 tests**, 0/0/0, 105 XML suites |
| Testcontainers | Really ran on merged `main`: Flyway `V1`→`V7` applied including `Migrating schema "public" to version "7 - store locations"`, non-zero integration timings |
| Working tree | Clean; no branch, no untracked `StoreLocation*` leftovers; `main == origin/main == dd894a3` |

`spotlessCheck`, `spotbugsMain` and `bootJar` were not re-run on `main`: they were green on the PR
head and on CI run 35313145188 over the *same tree*, and `dd894a3^{tree} == b63f460^{tree}` makes them
pure functions of identical inputs. Recorded as a deduction, not an observation.

## Next step

**Level 4 is shipped: merged into `main` (PR #108 / `dd894a3`), post-merge verified green.** The store
location tree is complete in code: `Store → Area → Zone → Location`, levels 1-4 all with full CRUD,
uniform naming, one cascade implementation per edge and the same access model.

Nothing is started. The natural next features are the Angular frontend for the subtree, or whatever
inventory/stock work introduces the first real consumer of a location.

## Follow-ups not addressed here (recorded, none blocking)

- **F4** — 404-vs-409 precedence on `PATCH /{storeLocationId}/enable` under a disabled ancestor.
  Template-consistent with level 3; change only if the whole store subtree's ordering is revisited.
- **F5** — `AbstractPostgresIntegrationTest`'s javadoc still claims `V1`–`V3`.
- **F7** — re-pointed never-pin semantics in `StoreAreaServiceTest`.
- **F8** — the mock-level coverage boundary around `@Transactional` atomicity.
- **Pagination** for the `areas`, `store-zones` and now `store-locations` list endpoints, ideally for
  all levels at once (level 3's accepted D4 deviation, still open).
- **F5 (level 3's, unrelated)** — the `company → … → zone` subsystem does not enforce the
  disabled-ancestor invariant and company-zone disable does not cascade to stores.
- **F7 (level 3's, unrelated)** — the lock-free TOCTOU race in the disabled-ancestor guard, inherited
  unchanged by this level.
- **Level 4 has zero consumers** — nothing in the schema references a location yet. The natural next
  feature is the Angular frontend for the subtree, then whatever inventory/stock work introduces the
  first real consumer.
