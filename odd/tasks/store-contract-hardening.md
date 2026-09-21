# ODD feature: store-contract-hardening

**Repository**: LifeControl — module `life-control-api/` only (Slice 2, backend)
**Status**: delivered — PR #114 (`feat/store-contract-hardening` @ `dff6626`), based on `main` @ `c0cb1f5`
**Created**: 2026-09-18
**Predecessor**: `odd/tasks/store-p0-defects.md` (Slice 1, frontend)

## Objective

Harden the store Area/Zone/Location/CompanyStore backend contract: close validation gaps,
add optimistic locking, fix one read-semantics inconsistency, record the audit actor on soft
delete, and remove one N+1 on the store list path.

## Problem (confirmed evidence, re-verified against source by the orchestrator)

1. `CreateCompanyStoreRequest.java:14` / `UpdateCompanyStoreRequest.java:11` declare
   `AddressRequest address` with no `@Valid`, so every `@Size` in
   `common/address/dto/AddressRequest.java` is dead for store addresses.
2. `UpdateCompanyStoreRequest.java:8` `@Size(max = 255) String storeName` has no `min`, so `""`
   passes, while sibling `UpdateStoreAreaRequest.java:13` uses `@Size(min = 1, max = …)`.
3. `displayOrder` has no `@Min` anywhere (`CreateStoreAreaRequest:14`, `UpdateStoreAreaRequest:20`,
   `CreateStoreZoneRequest:14`, `UpdateStoreZoneRequest:20`, `CreateStoreLocationRequest:14`,
   `UpdateStoreLocationRequest:21`) while the UI enforces `Validators.min(0)`.
4. No optimistic locking: `common/model/Auditable.java` has no `@Version`, migrations stop at
   `V7__store_locations.sql`; concurrent updates silently lose writes.
5. `CompanyStoreService.java:96-104`: the store-scoped branch ignores `includeDisabled`; disabled
   stores leak into the "active" list for store-scoped users.
6. `CompanyStoreService.toResponse` (`:235-243`) walks zone → region → country → company per store
   with `open-in-view=false`: 3 lazy selects per element on the list path.
7. Soft-delete logs in `CompanyStoreService.deleteStore` and the area/zone/location delete paths
   record content but not the actor, although `CurrentUserContext.getUserId()/getUsername()` exist.
8. Flat lookups (`getAreaById(UUID)`, `getZoneById(UUID)`, `getLocationById(UUID)`) load first and
   authorize second, so a 404-vs-403 existence oracle exists for non-admins. Authorize-before-load
   is mechanically impossible here: the chain is derived from the loaded entity.

## Decisions (orchestrator, binding for this slice)

### D1 — Flat lookup ordering (Task 6): choose **(b) mask as 404**

Return the *same* not-found exception (same status, same message) when the resolved chain is not
accessible to the caller, instead of propagating `AccessDeniedException`. Denial semantics are
unchanged (still denied); only the response becomes indistinguishable from "missing".

Justification:
- The flat lookups are the id-addressed (untrusted-id) surface: the caller supplies an opaque id and
  the API answers "exists in another tenant" via 403 vs "does not exist" via 404. That is the exact
  shape OWASP API1:2023 (BOLA) recommends masking. UUIDv4 non-enumerability lowers likelihood, it
  does not remove the confirmation oracle once an id leaks.
- The *nested* endpoints authorize caller-supplied path components before any load, so they leak
  nothing; their 403 stays untouched. The asymmetry is principled: path-addressed lookups reveal
  nothing new, id-addressed ones do.
- Cost is local and mechanical: three existing unit tests that assert the propagated
  `AccessDeniedException` are rewritten to assert the not-found exception. No path, method, status
  for authorized callers, or response field changes. The `*FlatController` OpenAPI contract already
  documents 404 for the flat operation.
- Observability is preserved by a `WARN` log line on masking, so the authorization-failure signal
  that a 403 used to carry is not lost.
- Accepted residual: an authorized-but-out-of-scope caller now receives "not found" instead of
  "forbidden"; support flows must read the mask log.

### D2 — Dead nested read surface (Task 9): choose **deprecate, do not delete**

Mark the nested `GET /{id}` operations on `StoreAreaController`, `StoreZoneController`,
`StoreLocationController` as `@Deprecated` (Javadoc + `@Operation(deprecated = true)`), documenting
the flat `*FlatController` endpoint as the canonical read path for the frontend.

Justification:
- Re-verified: no spec under `openspec/`, `sdd/` or `life-control-api/sdd/` references the nested
  paths (broader pattern, zero hits) and no frontend caller exists for the nested `GET /{id}`.
- Still, both routes are publicly documented (springdoc) and reachable by clients outside this repo.
  Removing a route is an irreversible contract break that needs its own slice with a deprecation
  window and release note; this slice's own constraint freezes existing endpoints.
- Deprecation delivers the hardening intent (one canonical documented read path) without the break,
  and leaves removal as a cheap follow-up.

## Scope

### In scope
- `store/dto/**`: `@Valid` cascade on store addresses, `@Size(min = 1)` on update `storeName`,
  `@Min(0)` on the six `displayOrder` fields.
- `store/model/**`: `@Version` on `CompanyStore`, `StoreArea`, `StoreZone`, `StoreLocation`.
- `db/migration/V8__store_optimistic_locking.sql`.
- `store/repository/**`: `@EntityGraph` chain fetch for the list finders + enabled-aware
  store-scoped finder.
- `store/service/**`: consistent `includeDisabled` semantics, flat-lookup masking, delete-log actor,
  N+1 removal.
- `store/controller/**`: `@Deprecated` on the three nested `GET /{id}` operations.
- `src/test/java/com/lifecontrol/api/store/**`: validation, concurrency, store-scoped list, audit,
  flat lookup and query-count tests.

### Out of scope (explicitly not implemented)
- Pagination on the store list (breaking change requiring a frontend/UX decision).
- Any frontend change; any endpoint path, HTTP method or response field rename.
- `GlobalExceptionHandler` (outside the edit surfaces — see "Required change outside surfaces").
- Deleting nested `GET /{id}` routes.

### Required change outside surfaces (report, do not make)
`exception/GlobalExceptionHandler.java` must gain a handler mapping
`ObjectOptimisticLockingFailureException` to **409 Conflict**, consistent with the existing
`handleConflict`/`handleDataIntegrityViolation` rationale. Without it a version conflict is caught by
the `@ExceptionHandler(Exception.class)` fallback and surfaces as **500 "An unexpected error
occurred"**. That file is outside this slice's edit surfaces, so it is reported, not modified.

## Constraints (non-negotiable)

- NO Lombok: records + constructor injection. `var` for obvious locals.
- No edits to already-applied migrations `V1`–`V7`; `V8` is additive.
- Domain exceptions extend `ResourceNotFoundException` / `DuplicateResourceException`; never add
  per-class handlers to `GlobalExceptionHandler`.
- Do NOT commit. Do NOT touch `life-control-app-angular/**` (the worktree already carries unrelated
  uncommitted frontend changes from Slice 1 — leave them exactly as they are).
- Existing security tests stay green; the three flat-lookup access-denied unit tests are the only
  intentional rewrites.

## Tasks

- [x] T1 `@Valid` on `AddressRequest address` in `CreateCompanyStoreRequest` / `UpdateCompanyStoreRequest`.
- [x] T2 `UpdateCompanyStoreRequest.storeName` → `@Size(min = 1, max = 255)` (no `@NotBlank`).
- [x] T3 `@Min(0)` on `displayOrder` in the six DTOs.
- [x] T4 `@Version` on the four entities + `V8__store_optimistic_locking.sql`.
- [x] T5 `getAllStores` store-scoped branch honours `includeDisabled`, `storeIds` scoping preserved.
- [x] T6 D1: mask flat-lookup denial as not-found across the three services (+ WARN log).
- [x] T7 Audit actor in the four soft-delete log lines.
- [x] T8 N+1: `@EntityGraph` chain fetch on the list finders.
- [x] T9 D2: `@Deprecated` on the three nested `GET /{id}` operations.

## Verification

| Check | Command | Result |
| --- | --- | --- |
| Scoped tests | `cd life-control-api && ./gradlew test --tests "com.lifecontrol.api.store.*" --rerun` | PASS — BUILD SUCCESSFUL in 44s; aggregated from the XML: 436 tests, 100 classes, 0 failures/0 errors/0 skipped |
| Full tests | `cd life-control-api && ./gradlew test --rerun` | PASS — BUILD SUCCESSFUL in 1m 11s; 549 suites, 526 classes, 1801 tests, 0 failures/0 errors/0 skipped |
| Build | `cd life-control-api && ./gradlew build` | PASS — BUILD SUCCESSFUL in 17s; `spotlessCheck` + `spotbugsMain` executed |
| N+1 evidence | `CompanyStoreListQueryCountIntegrationTest` | PASS — 1 store = 8 statements, 4 stores = 8 statements; pre-fix (graph removed) 9 vs 12 |
| Static probe | pi-lens LSP over the 20 changed main-source files | clean — 0 diagnostics at warning severity |

Docker was up for every run (Testcontainers, `AbstractPostgresIntegrationTest`). Verification was re-run by the orchestrator with `--rerun` (the writer's runs reported `UP-TO-DATE`), and the aggregate counts were recomputed from `build/test-results/test/*.xml`, not from the writer's summary.

## Corrections to the original evidence (measured, not assumed)

- **N+1 was the `address`, not the chain.** The slice evidence predicted 3 lazy selects per store for
  `companyZone → companyRegion → companyCountry → company`. Measurement showed the chain is already in
  the persistence context because `resolveCompanyZone` loads it before the list query, so the real
  per-element select was `CompanyStore.address` (a lazy owning-side `@OneToOne` Hibernate cannot
  proxy): 9 → 12 statements for 1 → 4 stores. The `@EntityGraph` now covers the chain *and* the address
  (1 → 4 stores: 8 → 8). The repository Javadoc states the measured reality, not the hypothesis.
- **Wrong exception FQCN in the slice brief.** `org.springframework.dao.Object…` does not exist; the
  correct type is `org.springframework.orm.ObjectOptimisticLockingFailureException`.

## Status

Delivered as PR #114. The 32 files were split out of the shared dirty worktree and committed on
their own branch, because the working tree carried both this slice and the frontend slice
`store-p0-defects` at once. The split was verified two ways: every committed blob is identical to
the backed-up stash, and the union of both slices reproduces the original tracked state exactly.
The `Do NOT commit` constraint in this document was a writer-scope rule; the delivery was
authorized separately by the user.

Verification re-run independently in an isolated `git worktree` of `dff6626`, reproducing
`api-ci.yml`: `./gradlew spotlessCheck spotbugsMain --no-daemon` exit 0 with all 6 tasks executed
(neither task `UP-TO-DATE`) and 0 `BugInstance`; `./gradlew test --no-daemon` exit 0, aggregated
from `build/test-results/test/*.xml`: 549 suites, 1801 tests, 0 failures / 0 errors / 0 skipped.
Docker was up and Flyway applied `V8` under `ddl-auto=validate`.

Review budget, corrected: production is only **315** changed lines (inside the 400-line budget);
the 1460 total is dominated by 1145 lines of tests, so the PR recommends a `size:exception`.

## Next step

The only remaining item is the out-of-surface change: add the `ObjectOptimisticLockingFailureException`
→ 409 handler to `exception/GlobalExceptionHandler.java` (see "Required change outside surfaces" above),
which today makes a version conflict surface as 500. That is the natural first task of a follow-up slice,
because it is what turns this slice's new `@Version` columns into a correct status code instead of only
preventing lost writes. Separately, schedule the nested `GET /{id}` removal once the deprecation window
closes, and consider pagination as its own slice.
