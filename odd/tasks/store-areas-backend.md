# ODD feature: store-areas-backend

**Repository**: LifeControl — module `life-control-api/`
**Status**: merged
**Created**: 2026-02-XX

## Objective

Add the level-2 node of the store location tree: the `store_areas` entity with full CRUD
backend (migration, entity, DTOs, repository, service, controller, exceptions, tests) inside
the existing `com.lifecontrol.api.store.*` package tree.

## Problem

`CompanyStore` (level 1: Store) exists, but the tree cannot be continued because there is no
`StoreArea` (level 2: Area) entity, no persistence table, and no API to manage areas under a
store. Zones, leaf locations, inventory and the Angular frontend depend on this node existing
first.

## Why this shape

- Schema and API must mirror `CompanyStore` exactly (same nested path pattern, same soft-delete
  semantics, same scope-verification mechanism) so the tree stays uniform and the frontend can
  reuse its cascade navigation.
- Authorization must reuse `CurrentUserContext.verifyCompanyStoreAccess` — inventing a second
  mechanism would fork the access model.
- Package placement stays in `store.*` (no new `inventory` package) so the domain boundary
  remains "store" until inventory actually lands.

## Scope

### In scope
- `V5__store_areas.sql` migration (table + unique constraint + index).
- `StoreArea` entity, `CreateStoreAreaRequest` / `UpdateStoreAreaRequest` / `StoreAreaResponse` DTOs.
- `StoreAreaRepository`, `StoreAreaNotFoundException`, `DuplicateStoreAreaException`.
- `StoreAreaService` (list / get / create / update / soft delete / enable).
- `StoreAreaController` at the nested store path + areas.
- Unit tests (service), controller tests (standalone + security), Testcontainers integration test.
- One atomic conventional commit: `feat(store): add store areas CRUD backend`.

### Out of scope (explicitly not implemented)
- `store_zones`, leaf `store_locations`, default-branch auto-provisioning,
  `CompanyStoreCreatedEvent` listener for areas, inventory/stock/movements, goods receipt,
  sales integration, Angular frontend, new Keycloak roles.

## Constraints (non-negotiable)

- NO Lombok. Entities: manual getters/setters + static `Builder`. DTOs: Java `record`s.
- Constructor injection only (no `@Autowired`, no `@RequiredArgsConstructor`).
- `var` for obvious local types.
- Entities extend `com.lifecontrol.api.common.model.Auditable`.
- Domain exceptions extend `ResourceNotFoundException` / `DuplicateResourceException`;
  never add per-class handlers to `GlobalExceptionHandler`.
- `@PreAuthorize` references `Roles` constants via static import — no `lc-*` literals.
- Every endpoint carries `@Tag` / `@Operation` / `@ApiResponse`.
- Soft delete = `enabled = false`; list supports `?includeDisabled=true`; re-enable via PATCH.
- No edits to already-applied Flyway migrations.

### Accepted deviations (with rationale)

- **D1 — Logger is not constructor-injected.** The task asked for `Logger` among the service's
  constructor dependencies, but no `Logger` bean exists in the application context (verified:
  no `@Bean Logger`, no constructor `Logger` parameter anywhere in `main`), so constructor
  injection would break context startup and every `@SpringBootTest`. The service follows the
  repository-wide convention instead: `private static final Logger logger =
  LoggerFactory.getLogger(StoreAreaService.class);` — identical to `CompanyStoreService`.
- **D2 — `UpdateStoreAreaRequest` blank rejection uses `@Size(min = 1, max = N)`.** The task asked
  to mirror `UpdateCompanyStoreRequest` (`@Size(max = N)` only) *and* to reject blank values when
  non-null. `@Size(max = N)` alone accepts `""` (length 0). `@Size(min = 1, max = N)` keeps the
  same upper bound, keeps `null` valid (partial update), and rejects blank with a 400. Chosen over
  a custom compact-constructor throw to stay inside Jakarta Bean Validation.
- **D3 — Access check passes the real `storeId`.** The task asked to replicate
  `CompanyStoreService.resolveCompanyZone` (which passes `storeId = null`). Here the store id is
  always present in the request path, so `verifyCompanyStoreAccess(..., storeId)` is passed
  non-null: for `lc-company-store*` roles this verifies the store claim against the actual store
  (strictly stronger, same mechanism); for broader roles it is ignored.
- **D4 — Role matrix adds no new role.** `lc-company-store*` roles are kept from
  `CompanyStoreController`; no `lc-store-area*` role is introduced (out of scope: new Keycloak
  roles).

## Tasks

- [x] T1 — Migration `V5__store_areas.sql` (table `store_areas`, `UNIQUE(company_store_id, area_code)`, `idx_store_areas_store`).
- [x] T2 — `StoreArea` entity (Auditable, lazy `@ManyToOne` to `CompanyStore`, builder, no Lombok).
- [x] T3 — DTOs: `CreateStoreAreaRequest`, `UpdateStoreAreaRequest`, `StoreAreaResponse`.
- [x] T4 — `StoreAreaRepository` (5 derived queries) + `StoreAreaNotFoundException`, `DuplicateStoreAreaException`.
- [x] T5 — `StoreAreaService` (`resolveStore` reusing `verifyCompanyStoreAccess`, list/get/create/update/soft-delete/enable, `toResponse`).
- [x] T6 — `StoreAreaController` (nested path, read/write role lists, OpenAPI, 200/201/204).
- [x] T7 — `StoreAreaServiceTest` (Mockito, `@Nested` per operation: create ok, duplicate, get not found, update, soft delete, enable).
- [x] T8 — `StoreAreaControllerTest` (standalone MockMvc + `GlobalExceptionHandler`: 200/201/204/400/404/409) and `StoreAreaControllerSecurityTest` (`@WebMvcTest` + `@WithMockUser` role matrix).
- [x] T9 — `StoreAreaIntegrationTest extends AbstractPostgresIntegrationTest` (Testcontainers + Flyway V5: create, list active only, includeDisabled, duplicate 409, soft delete + enable).
- [x] T10 — Verification: `./gradlew test --tests "com.lifecontrol.api.store.*"`, `./gradlew test`, `./gradlew bootJar --no-daemon -x test`.
- [x] T11 — Commit `feat(store): add store areas CRUD backend` (atomic; migration + model + dto + repo + service + controller + exceptions + tests only).

## Acceptance criteria

1. `store_areas` exists with the exact DDL shape requested and is the only new migration.
2. All 6 endpoints work under
   `/api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores/{storeId}/areas`
   with the documented status codes.
3. Read roles include `lc-company-store-read`; write roles do not.
4. Duplicate `area_code` inside the same store → 409 (`DuplicateStoreAreaException`).
5. Unknown area / unknown store / broken hierarchy → 404 with the matching domain exception.
6. `DELETE` sets `enabled = false`; `GET ?includeDisabled=true` returns disabled rows;
   `PATCH /{areaId}/enable` sets `enabled = true`.
7. Scoped access is verified through `CurrentUserContext.verifyCompanyStoreAccess` only.
8. No Lombok, no hardcoded role literals, no new `GlobalExceptionHandler` handlers.
9. All three verification commands pass.

## Checks / evidence

| Check | Command | Result |
| --- | --- | --- |
| Scoped tests | `./gradlew test --tests "com.lifecontrol.api.store.*"` | PASS — BUILD SUCCESSFUL, 156 tests, 0 failures |
| Full suite | `./gradlew test` | PASS — BUILD SUCCESSFUL, 1521 tests, 0 failures, 0 errors |
| Package | `./gradlew bootJar --no-daemon -x test` | PASS — BUILD SUCCESSFUL (`life-control-api-0.0.1-SNAPSHOT.jar`) |

Environment note: Docker was verified available (`docker info` OK) and the Testcontainers
integration test executed against `postgres:16-alpine` with Flyway enabled, so `V5` was applied
for real. 76 new tests: service 15, standalone controller 17, security 31, integration 13.

Commit: `ced2564` — `feat(store): add store areas CRUD backend` (14 files, 2500 insertions).

## Delivery (PR + merge)

| Step | Detail |
| --- | --- |
| Branch | `feat/store-areas-backend` (created from local `main` at `ced2564`, then local `main` was moved back to `origin/main` with `git branch -f`; no working-tree file was touched) |
| Push | `git push -u origin feat/store-areas-backend` — needed interactive confirmation by the harness `gitPush` guard |
| PR | #98 `feat(store): add store areas CRUD backend` — https://github.com/le03nava/LifeControl/pull/98 |
| CI | `Check` (api-ci.yml: `spotlessCheck spotbugsMain` + `test`) — **pass** in 1m35s |
| Merge | `gh pr merge 98 --merge --delete-branch` → merge commit `9135fa9`, 2026-09-17T06:06:02Z |
| Branch after merge | deleted (remote and local) per user decision |
| Post-merge check | `./gradlew test --tests "com.lifecontrol.api.store.*" --no-daemon` on `main` → BUILD SUCCESSFUL in 38s |

Decision record (user-confirmed before executing): no linked issue — the repository has no PR template and
no issue-validation workflow, and the recent API PRs (#88–#97) do not link issues either, even though older
frontend PRs (#36→#35, #42→#43, #68→#67, #71→#70, #82→#81, #84→#83) did; merge commit strategy (repository
convention); wait for CI green before merging; delete the source branch after merge.

Review workload note: the commit is ~1000 lines of production code plus ~1800 lines of test
boilerplate (the `@PreAuthorize` matrix dominates). It was kept atomic as instructed; if a
smaller review is preferred, the natural split is controller + security test vs. the rest.

## Progress

- Exploration done: read `life-control-api/AGENTS.md`, `CompanyStore` (model/dto/repo/service/
  controller/exceptions), `Roles`, `ScopeLevel`, `CurrentUserContext`, `Auditable`,
  `GlobalExceptionHandler`, `V1__baseline_schema.sql` (company_stores DDL), existing store tests,
  `AbstractPostgresIntegrationTest`, `SalesOrderIntegrationTest`.
- All 11 tasks complete; three verification commands green; commit `ced2564` created.
- Defect found and fixed during verification: `StoreAreaServiceTest.mockStoreResolution()`
  originally returned `new Company()` / `new CompanyCountry()` placeholders, so the service passed
  `null` parent ids and Mockito strict stubbing failed with `PotentialStubbingProblem` (15 failing
  tests). Fixed by building the hierarchy with real ids in `setUp()` and reusing those entities.
- Not committed on purpose (kept out of the atomic feature commit): the unrelated pre-existing
  Angular working-tree changes, the untracked `life-control-api-improvement-prompts.md`, and this
  ODD task document.
- `spotlessApply` (palantirJavaFormat 2.98.0) was run over the new sources before verification.
- Native review preflight was not applicable: `gentle-ai review mode status` reports
  receipt-driven development **off** (global and clone-local unset).

## Next step

Delivered and merged into `main` (PR #98 / `9135fa9`). Optional follow-ups (out of today's scope): level 3
(`store_zones`), leaf `store_locations`, inventory/stock, and the Angular navigation for areas.
