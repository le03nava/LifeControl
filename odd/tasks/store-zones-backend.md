# ODD feature: store-zones-backend

**Repository**: LifeControl — module `life-control-api/`
**Status**: merged — PR #103 (`feat/store-zones-backend` @ `2c21fd57e`), 2026-09-18. No work left.
**Created**: 2026-02-XX

## Objective

Add the level-3 node of the store location tree: the `store_zones` entity with full CRUD backend
(migration, entity, DTOs, repository, service, controllers, exceptions, tests) inside the existing
`com.lifecontrol.api.store.*` package tree, plus the cascade rule that disabling an area disables
its zones.

## Problem

`StoreArea` (level 2) exists, but the tree cannot continue: there is no `StoreZone` entity, no
persistence table and no API to manage zones under an area. The tree `Store → Area → Zone →
Location` is documented in `StoreArea.java:8` and `V5__store_areas.sql:1`, yet only levels 1 and 2
are implemented. Verified: zero matches for `StoreZone|store_zones|storeZone` in the whole repo,
and Flyway stops at `V5`.

## Why this shape

- **Schema and API mirror `StoreArea` exactly** (same nested path pattern, same soft-delete
  semantics, same scope-verification mechanism) so the tree stays uniform and the frontend can
  reuse its cascade navigation. This is the same rationale applied to `store-areas-backend`.
- **Naming must not collide with `CompanyZone`.** In the store module `zoneId` already means
  *company zone*: the path segment `zones`, the JWT claim `company_zone_id`, `ScopeLevel.ZONE`
  (`ScopeLevel.java:34-35`) and `Roles.COMPANY_ZONE`/`COMPANY_ZONE_READ` all use the bare term.
  A store zone therefore uses the distinct identifier **`store-zones` / `storeZoneId`**.
- **Authorization reuses `CurrentUserContext.verifyCompanyStoreAccess`.** The zone lives *inside*
  the store, and the store is already the finest level of the scope hierarchy, so no new
  `ScopeLevel`, claim or Keycloak role is introduced. Inventing one would fork the access model
  for no requirement.
- **Package placement stays in `store.*`** so the domain boundary remains "store".

## Scope

### In scope
- `V6__store_zones.sql` migration (table + unique constraint + index).
- `StoreZone` entity; `CreateStoreZoneRequest` / `UpdateStoreZoneRequest` / `StoreZoneResponse` DTOs.
- `StoreZoneRepository`, `StoreZoneNotFoundException`, `DuplicateStoreZoneException`.
- `StoreZoneService` (list / get / create / update / soft delete / enable + flat get by id).
- `StoreZoneController` at the nested area path + `StoreZoneFlatController` at `/api/store-zones`.
- Cascade: `StoreAreaService.deleteArea()` must also set `enabled = false` on the area's zones.
- Tests: service unit, standalone controller, security role-matrix, Testcontainers integration,
  plus a regression test for the cascade.
- One atomic conventional commit: `feat(store): add store zones CRUD backend`.

### Out of scope (explicitly not implemented)
- Leaf `store_locations` (level 4), default-branch auto-provisioning, inventory/stock/movements,
  goods receipt, sales integration, Angular frontend, new Keycloak roles, new `ScopeLevel` claim.

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
- No edits to already-applied Flyway migrations (`V1`–`V5` are frozen).
- Run `spotlessApply` before verification (CI runs `spotlessCheck` + `spotbugsMain`).

## Decisions (user-locked, this session)

- **D1 — Resource name is `store-zones` / `storeZoneId`.** No `ScopeLevel.STORE_ZONE`, no new
  claim, no new Keycloak role: `COMPANY_STORE` / `COMPANY_STORE_READ` are reused.
- **D2 — Uniqueness is `UNIQUE(store_area_id, zone_code)`.** The code is unique inside its area;
  two areas of the same store may reuse `Z1`. Mirrors `store_areas` being unique per store.
- **D3 — Cascade on area soft-delete.** Disabling an area also disables all its zones. This is a
  real behaviour change to `StoreAreaService.deleteArea()`, so it ships with a regression test.

### Accepted deviations (with rationale)

- **D4 — List endpoints are not paginated.** `AGENTS.md` documents `Pageable`/`Page<T>` as the API
  convention, but the immediate sibling (`StoreAreaController`, added in `V5`) returns a plain
  `List` with only `?includeDisabled`. The closer, more recent precedent wins so that the two
  levels of the same tree stay symmetric and the Angular cascade form can reuse its shape. If
  pagination is wanted, it should land for `areas` and `store-zones` together — not on level 3 alone.
- **D5 — `PATCH /{storeZoneId}/enable`.** Areas use `.../{areaId}/enable` while company zones use
  `.../{id}`. The area form is adopted so the store subtree has one consistent re-enable verb
  instead of adding a third convention.
- **D6 — Review workload exceeds the 400-line budget.** `store-areas-backend` shipped as one atomic
  commit of ~1000 production + ~1800 test lines (PR #98) and was accepted, because the
  `@PreAuthorize` matrix dominates the diff and splitting the tree's levels across PRs breaks the
  uniform pattern. Kept atomic for consistency; surfaced to the user at close with the natural
  split option (controllers + security test vs. the rest).

### Follow-up decisions (user-locked, second round)

- **D7 — The "disabled parent" invariant covers the whole store subtree (store → area → zone).**
  Creating or re-enabling a node requires its **entire enabled ancestor chain up to the store** to
  be enabled. That means areas check the store, and zones check the area *and* the store. Fixing it
  only at the zone level would have made level 3 stricter than level 2 — trading one inconsistency
  for another. The `company → … → zone` subsystem stays as-is (separate, pre-existing, larger blast
  radius, not explored to the same depth).
- **D8 — Re-enable does NOT cascade.** Soft-delete cascades down (D3 and F3), re-enable never does:
  each node is reactivated explicitly, in order (store → area → zone). Cascade-enable would silently
  revive zones somebody deliberately disabled, and combined with D7 it would make operation order
  irrelevant in a way that is hard to reason about.
- **D9 — F4 was a FALSE FINDING; no production code is written for it.** `GlobalExceptionHandler`
  already maps `DataIntegrityViolationException` → 409 (`GlobalExceptionHandler.java:60-65`), with a
  comment stating it exists exactly for the race between `existsBy…` and `save`. The independent
  verifier's claim that a concurrent duplicate "would surface as 500" was wrong. The DB constraint
  protects integrity and the advice returns 409. F4 is closed with **regression tests only** that
  pin the behaviour: the service must let the exception propagate (not swallow it), and the
  controller layer must render it as 409.

## Tasks

- [x] T1 — Migration `V6__store_zones.sql` (`store_zones`, `UNIQUE(store_area_id, zone_code)`, `idx_store_zones_area`).
- [x] T2 — `StoreZone` entity (Auditable, lazy `@ManyToOne` to `StoreArea`, builder, no Lombok).
- [x] T3 — DTOs: `CreateStoreZoneRequest`, `UpdateStoreZoneRequest`, `StoreZoneResponse` (carries `storeAreaId` + full company chain).
- [x] T4 — `StoreZoneRepository` (5 derived queries) + `StoreZoneNotFoundException`, `DuplicateStoreZoneException`.
- [x] T5 — `StoreZoneService` (`resolveArea` reusing `verifyCompanyStoreAccess`, list/get/create/update/soft-delete/enable, flat `getZoneById`, `toResponse`).
- [x] T6 — `StoreZoneController` (nested) + `StoreZoneFlatController` (`/api/store-zones/{storeZoneId}`), read/write role lists, OpenAPI, 200/201/204.
- [x] T7 — Cascade in `StoreAreaService.deleteArea()` (disable child zones) using a `StoreZoneRepository` finder.
- [x] T8 — `StoreZoneServiceTest` (Mockito, `@Nested` per operation).
- [x] T9 — `StoreZoneControllerTest` + `StoreZoneControllerSecurityTest` (`@WebMvcTest` + role matrix) + `StoreZoneFlatControllerTest`.
- [x] T10 — `StoreZoneIntegrationTest extends AbstractPostgresIntegrationTest` (Testcontainers + Flyway V6) + cascade regression test.
- [x] T11 — Verification: `spotlessApply`, `./gradlew test --tests "com.lifecontrol.api.store.*"`, `./gradlew test`, `./gradlew bootJar --no-daemon -x test`.
- [x] T12 — Commit `feat(store): add store zones CRUD backend` (atomic). *(note: the original plan said "awaiting user request"; the user requested the follow-up fixes instead, so the commit is deferred until after this round)*

### Follow-up round (F1–F4)

- [x] T13 — `DisabledParentException extends ConflictException` (409, the project's "state conflict" category) in `store/exception/`.
- [x] T14 — F1/F2 guards: `StoreAreaService.createArea`/`enableArea` require an enabled store; `StoreZoneService.createZone`/`enableZone` require an enabled area **and** an enabled store.
- [x] T15 — F3 cascade: extract the area→zone cascade into one private helper in `StoreAreaService`, expose `disableAreasOfStore(UUID companyStoreId)`, and call it from `CompanyStoreService.deleteStore` so store→areas→zones is disabled in one transaction.
- [x] T16 — F4 regression tests only: `StoreZoneServiceTest` asserts `DataIntegrityViolationException` propagates; `StoreZoneControllerTest` asserts it renders as **409**.
- [x] T17 — Verification of the follow-up round: `spotlessCheck --rerun-tasks`, `spotbugsMain --rerun-tasks`, scoped tests, full suite, `bootJar`.

### Third round (F8–F10, from independent verification)

- [x] T18 — F8: `StoreAreaIntegrationTest` deletes `store_zones` before `store_areas` (FK has no `ON DELETE CASCADE`).
- [x] T19 — F9: pin D8 with a unit `never()` assertion plus an integration `deleteStore → enableStore → children stay disabled` test; proven non-vacuous by mutation testing.
- [x] T20 — F10: correct the OpenAPI 409 contract on 4 endpoints (area/zone CREATE descriptions + the previously undeclared 409 on both ENABLE endpoints), annotation-only.

## Acceptance criteria

1. `store_zones` exists with the documented DDL shape and `V6` is the only new migration.
2. All 6 endpoints work under
   `/api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores/{storeId}/areas/{areaId}/store-zones`
   plus the flat `GET /api/store-zones/{storeZoneId}`, with the documented status codes.
3. Read roles include `lc-company-store-read`; write roles do not.
4. Duplicate `zone_code` inside the same area → 409; the same code in a *different* area is allowed.
5. Unknown zone / unknown area / broken hierarchy → 404 with the matching domain exception.
6. `DELETE` sets `enabled = false`; `GET ?includeDisabled=true` returns disabled rows;
   `PATCH /{storeZoneId}/enable` sets `enabled = true`.
7. Disabling an area sets `enabled = false` on all of its zones.
8. Scoped access is verified through `CurrentUserContext.verifyCompanyStoreAccess` only.
9. No Lombok, no hardcoded role literals, no new `GlobalExceptionHandler` handlers.
10. All three verification commands pass.

## Checks / evidence

| Check | Command | Result |
| --- | --- | --- |
| Formatting | `./gradlew spotlessApply` (writer) | PASS |
| Formatting (fresh) | `./gradlew spotlessCheck --rerun-tasks` | PASS — exit 0, 3 tasks executed |
| Static analysis (fresh) | `./gradlew spotbugsMain --rerun-tasks` | PASS — exit 0, 0 `BugInstance` in `main.xml` |
| Scoped tests | `./gradlew test --tests "com.lifecontrol.api.store.*"` | PASS — **272 tests**, 0 failures, 0 errors, 0 skipped |
| Full suite | `./gradlew test` | PASS — **1637 tests**, 0 failures, 0 errors, 0 skipped |
| Package | `./gradlew bootJar --no-daemon -x test` | PASS — `life-control-api-0.0.1-SNAPSHOT.jar` |

The non-store test count is 1365 before and after the follow-up rounds, so every new test lives in
the store package and no test was dropped anywhere.

Docker was available (`docker info` OK) and the store integration tests ran for real on
`postgres:16-alpine`: the shared context logged `Migrating schema "public" to version "6 - store
zones"` / `Successfully applied 6 migrations ... now at version v6`, and the store-cascade test
emitted real SQL (`insert into store_zones`, `update store_areas set ... enabled=?`, `update
store_zones set ... enabled=?`) plus the service log
`StoreAreas soft-deleted by store cascade: storeId=..., areas=1, cascadedZones=2`. Not H2, not skipped.

Independent verification, rounds 1 and 2 (read-only `gentle-ai-verify`, fresh command runs, no file
edits): **no blocking findings in either round.** Round 1 confirmed all 10 acceptance criteria and
every constraint rule PASS, and that the cascade diff touched only the import, field, constructor
parameter and `deleteArea` body. Round 2 added findings F7–F10 and ran an explicit honesty audit
with the verdict that **no existing assertion was changed, removed or relaxed anywhere**
(`StoreAreaControllerTest` +23/−0, `StoreAreaServiceTest` +119/−0, `CompanyStoreServiceTest` +4/−0);
the one shared-fixture adjustment (`StoreZoneIntegrationTest.seedCompanyHierarchy` re-enabling the
store before each method) was judged legitimate fixture hardening rather than concealment, because
the cascade that disables the store is itself asserted in the same file.

## Follow-ups (status after the third round)

- **F1 — RESOLVED by T14.** `enableZone` now requires an enabled area *and* an enabled store
  (`DisabledParentException`).
- **F2 — RESOLVED by T14.** `createZone` applies the same ancestor guard.
- **F3 — RESOLVED by T15.** `CompanyStoreService.deleteStore` now cascades to areas and, through the
  single shared helper, to their zones.
- **F4 — CLOSED as a false finding (D9).** No production change; `GlobalExceptionHandler` already
  returned 409. Behaviour is now pinned by regression tests (T16).
- **F8 — RESOLVED by T18.** `StoreAreaIntegrationTest.setUp()` now deletes `store_zones` before
  `store_areas`, so its `@BeforeEach` survives the FK that has no `ON DELETE CASCADE`. Verified by
  running that class alone (15 tests, 0 failures) and sharing one JVM with the zone class
  (34 tests, 0 failures across 3 runs). Limitation of the evidence: the adverse class order was NOT
  reproduced end-to-end (forcing JUnit class order would need build-config edits), so the fix is
  proven defensive rather than proving the pre-fix failure.
- **F9 — RESOLVED by T19.** D8 (re-enable never cascades) is now pinned at two levels: a unit
  `verify(storeZoneRepository, never()).saveAll(any())` in `enableArea`, and an integration test that
  disables then re-enables the store through the API and asserts areas/zones stay disabled. The
  unit pin was proven **non-vacuous by mutation testing**: injecting
  `storeZoneRepository.saveAll(...)` into `enableArea` made
  `should not cascade to zones when re-enabling the area` FAIL (`BUILD FAILED`); the injection was
  then reverted and the file restored byte-identical (md5 verified).
- **F10 — RESOLVED by T20.** The published OpenAPI contract now matches behaviour: the area and zone
  CREATE endpoints describe both 409 causes, and the previously undeclared 409 on both ENABLE
  endpoints was added. The `PUT`/update endpoints' 409 descriptions were deliberately left alone
  because `updateArea`/`updateZone` have no disabled-parent guard and cannot change `enabled`.
- **F7 — RECORDED as a known limitation (user decision, no code change).** The D7 guard is a
  lock-free check-then-act: T1's guard reads `enabled = true`, T2's `deleteStore` commits its cascade
  (whose select cannot see T1's uncommitted insert), then T1 commits — leaving an enabled area/zone
  under a disabled ancestor. No DB constraint catches it, unlike the accepted duplicate race in D9
  which the unique constraint backstops. Rejected fixes, with reasons: **pessimistic locking** of the
  parent store row across all seven mutating paths would close it but costs ~7 methods in 2 services
  plus 3 test classes, needs `@Lock` repository methods, imposes deadlock-ordering discipline, and
  cannot be proven correct by deterministic tests — and the module has no locking anywhere today;
  **optimistic `@Version`** would need a `V7` migration and would make every `CompanyStore` write
  subject to lock conflicts, a far larger blast radius than the defect. **Post-write re-verification
  does NOT close it under READ COMMITTED** (if T2 commits after T1's re-read, MVCC gives T1 the old
  version), so that cheaper option was ruled out rather than left implied. Accepted impact:
  cosmetic — an area reported active inside a closed store. It is not an authorization breach, since
  access is enforced by `verifyCompanyStoreAccess`, which never consults `enabled`. Re-issuing
  `DELETE` on the store re-runs the cascade and heals the row.

Still open (out of scope, pre-existing, not addressed here):

- **F5 — The `company → … → zone` subsystem does not enforce the same invariant.** Whether a store
  may be created or re-enabled under a disabled company zone, and whether disabling a company zone
  should cascade to its stores, is untouched by D7. Deferred deliberately: it is a separate
  subsystem with a much larger blast radius.
- **F6 — `enableStore` does not cascade to areas/zones, by design (D8).** Reactivation is explicit
  and ordered: store → area → zone. Now pinned by T19.

## Progress

- Exploration done (read-only): `StoreArea`, `StoreAreaService`, `StoreAreaController`,
  `StoreAreaFlatController`, `StoreAreaRepository`, all 3 area DTOs, both area exceptions,
  `V5__store_areas.sql`, `CurrentUserContext.verifyCompanyStoreAccess`, `ScopeLevel`, `Roles`,
  migration inventory, existing store tests, `odd/tasks/store-areas-backend.md` (template).
- Design decisions D1–D3 locked with the user before any write.
- T1–T11 implemented by one bounded writer; 16 new files plus 2 modifications, all inside the
  declared edit surfaces (18 paths in `git status --porcelain`, nothing outside).
- T1–T11 independently verified green. `V6` is the only new migration; `V1`–`V5` are byte-identical
  to `HEAD`.
- Note: this document marked T1–T11 as `[x]` before any code existed. The writer reported the
  stale state (it could not edit this file — outside its surfaces). The marks are accurate now that
  the work is done and verified, but the sequencing was wrong and is recorded here rather than hidden.
- Round 2 (T13–T17, user-requested): the four follow-ups F1–F4 were fixed. F4 turned out to be a
  false finding and produced tests only. Decisions D7 (invariant covers the store subtree), D8
  (re-enable never cascades) and D9 (F4 false) locked with the user before any write in that round.
- Round 2 verification found F7–F10. The user chose to record F7 as a known limitation (no code
  change); F8–F10 were closed in round 3.
- Round 3 (T18–T20) changed 2 controllers (annotations only) and 4 test files. Proof that the
  controllers changed only annotations: the `StoreAreaController` diff is exactly 2 hunks (one 409
  description, one added 409), and `StoreZoneController` was structurally diffed by reverse-applying
  the two edits — the non-annotation lines are identical.
- Mutation testing performed by the orchestrator to prove the D8 pin is not vacuous: a
  `storeZoneRepository.saveAll(...)` injected into `enableArea` made
  `should not cascade to zones when re-enabling the area` FAIL (`BUILD FAILED`, exit 1); the file was
  then restored and verified byte-identical by md5 (`8c0c4c3b69ed5292af00ea44dea2d684` before and
  after), and `grep -c MUTATION` returns 0.
- Final state: 24 paths in `git status --porcelain` (7 modified, 17 untracked), **nothing staged and
  nothing committed**. No `git add` was run at any point.

## Delivery (commit + push + PR)

| Step | Detail |
| --- | --- |
| Commit | `08e123a` — `feat(store): add store zones CRUD backend`, 24 files, +3962/−4. Only the store-module paths were staged; `git diff --cached --name-only` confirmed exactly 24 paths and nothing else. `odd/` and `life-control-api-improvement-prompts.md` are gitignored (`.gitignore:142-143`), so the task document stayed out of the commit, matching the `store-areas-backend` precedent. |
| Branch | `feat/store-zones-backend` created from the commit; local `main` was then moved back to `origin/main` (`a72de5f`) with `git branch -f` so the feature commit lives only on the branch. No working-tree file was touched by that move. |
| Push | `git push -u origin feat/store-zones-backend` — accepted on the first attempt (no interactive guard this time). |
| PR | **#103** — https://github.com/le03nava/LifeControl/pull/103, base `main`, state `OPEN`, `mergeable: MERGEABLE`. |
| CI | Run 35288911996 — `Check` **pass** in **1m59s**, all steps green (`Static analysis`, `Run tests`, `Upload test report`). Same gates as `api-ci.yml` that were run locally with `--rerun-tasks`. |
| Labels | None. PR #98 (the direct sibling) also carries no labels, and the repository has no `type:feature` label to apply — only `type:chore`, which would be wrong for a feature. There is no PR template and no issue-linkage workflow in `.github/workflows/` (only `api-ci.yml` and `angular-ci.yml`), so the issue-first rules from the branch-PR skill do not apply here. |
| Merge | `gh pr merge 103 --merge --delete-branch` → PR #103 state **MERGED** at 2026-09-18T01:35:43Z, merge commit **`2c21fd57e20b2313baf0c770f9fba9543ae006e1`**. Merge-commit strategy, matching the repository convention and the #98 precedent. |
| Branch after merge | Deleted on the remote (`git ls-remote --heads origin feat/store-zones-backend` returns empty) and locally. |
| Post-merge check | `./gradlew test --tests "com.lifecontrol.api.store.*"` on `main` at `2c21fd5` → BUILD SUCCESSFUL in 38s, **272 tests, 0 failures, 0 errors, 0 skipped**. Working tree clean, `V6__store_zones.sql` present. |

## Next step

**Delivered and merged into `main` (PR #103 / `2c21fd5`).** The feature is complete: levels 1-3 of the
store location tree now exist in code, not just in a comment.

Optional follow-ups, none started:

- **Level 4 — `store_locations`.** The only remaining level of the documented tree. Same shape again:
  `V7` migration, entity under `StoreZone`, and the same decisions (naming vs. `CompanyZone`, unique
  scope, cascade, D7 invariant, D8 re-enable semantics).
- **Angular frontend for store zones.** Nothing exists yet; mirror `store-area.service.ts`,
  `store-area-form` and the `store-areas` routes.
- **F5 — the `company → … → zone` subsystem** does not enforce the D7 invariant, and disabling a
  company zone does not cascade to its stores.
- **F7 — the TOCTOU race**, currently a recorded known limitation. Only worth revisiting if strict
  consistency becomes a requirement.
- **Pagination** for the `areas` and `store-zones` list endpoints, ideally for both at once.
