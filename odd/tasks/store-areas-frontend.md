# ODD feature: store-areas-frontend

**Repository**: LifeControl — frontend `life-control-app-angular/`, with a prerequisite in
`life-control-api/` and `api-gateway/`
**Status**: in progress
**Created**: 2026-09-17
**Predecessor**: `odd/tasks/store-areas-backend.md` (merged, PR #98 @ 9135fa9)

## Objective

Ship the Store Areas management UI (level 2 of the store location tree): a list page with the
Empresa → País → Región → Zona → Tienda cascade, a create/edit page, a dashboard card, and a
per-store entry point on the store card.

## Problem

The backend for store areas is merged, but the frontend cannot manage them:

1. **Blocker found during exploration** — the feature prompt assumed a flat lookup
   `GET /api/store-areas/{areaId}` that returns the area **including** its chain
   (`companyId, companyCountryId, regionId, zoneId, companyStoreId`). That endpoint does not
   exist in any branch of `life-control-api`, and `StoreAreaResponse` carries no chain fields.
   Without the chain the edit page cannot build the nested update URL on a cold load
   (deep link / refresh), because `companyStoreId` alone cannot be mapped back to the chain
   (no flat `GET /api/stores/{id}` either).

2. `api-gateway` routes explicit path prefixes only; `/api/store-areas/**` is not routed, so a
   flat endpoint would be unreachable through the gateway the frontend talks to.

## Why this shape

- **The chain travels on the area.** `StoreAreaResponse` gains `companyId`,
  `companyCountryId`, `regionId`, `zoneId` (additive, backward compatible). One DTO, one
  frontend `StoreArea` type, and no duplicated mapping. This also makes the frontend type
  honest: the prompt's `StoreArea` interface declares those fields, so both the nested list and
  the flat lookup must populate them.
- **Flat lookup resolves, then authorizes.** The area is loaded first (404 when absent), its
  chain is derived through the JPA associations, and only then
  `CurrentUserContext.verifyCompanyStoreAccess` runs against the *resolved* ids — reusing the
  single existing access mechanism instead of forking a second one.
- **Two work units, two commits.** The backend endpoint + gateway route is a distinct module
  (Java) and a distinct concern from the Angular UI; bundling them would inflate the review
  surface and mix toolchains.

## Scope

### In scope

- **Work unit A — backend + gateway (prerequisite)**
  - `StoreAreaResponse` + chain fields, `StoreAreaService` chain helper + flat `getAreaById(UUID)`.
  - `StoreAreaFlatController` at `/api/store-areas` (`GET /{areaId}`), read roles incl.
    `lc-company-store-read`.
  - `api-gateway` route for `/api/store-areas/**`.
  - Backend tests updated/added (service, flat controller, security, integration).
  - Commit: `feat(store): add flat store-area lookup endpoint`.
- **Work unit B — Angular UI**
  - `store-area.models.ts`, `StoreAreaService` + spec, `StoreAreaForm` + spec.
  - `/companies/store-areas` routes; `StoreAreasPage`, `StoreAreasEdit` + specs.
  - Dashboard `STATIC_CARDS` entry + subtitle; `stores-card` "Áreas" action;
    `stores-page` handler.
  - Commit: `feat(stores): add store areas management UI`.

### Out of scope (explicitly not implemented)

- Zones, leaf locations, auto-provisioning, inventory/stock/movements, goods receipt, sales.
- Flat `GET /api/stores`, new Keycloak roles, new header menu entries.
- Migration changes: the flat endpoint needs no schema change.

## Constraints (non-negotiable)

### Backend
- NO Lombok; records for DTOs; constructor injection; `var` for obvious locals.
- `@PreAuthorize` references `Roles` constants via static import — no `lc-*` literals.
- Every endpoint carries `@Tag` / `@Operation` / `@ApiResponse`.
- No edits to applied Flyway migrations.

### Frontend
- Standalone components, `ChangeDetectionStrategy.OnPush`, no NgModules.
- `@if` / `@for` control flow only.
- Signals for UI state; RxJS only for HTTP; `rxResource` for lists/pages.
- Typed reactive forms via `NonNullableFormBuilder`.
- API URLs from `ConfigService.apiUrl`; never hardcode URLs.
- SCSS `@use` only; reuse `@shared/styles/variables` and `_form-layout.scss`.
- Code/identifiers in English; UI copy in Spanish.
- Co-located `*.spec.ts`; keep coverage thresholds green.

## Accepted deviations (with rationale)

- **Backend and gateway are no longer out of scope** (the original prompt excluded backend
  changes). The user explicitly selected "Agregar el endpoint flat al backend" after the
  blocker was reported, because the edit page cannot resolve the chain otherwise. The gateway
  route is a necessary consequence: without it the endpoint is unreachable from the frontend.
- **`StoreAreaResponse` is extended rather than duplicated.** The prompt asked for the chain on
  the flat lookup only; it is added to the shared response so the nested list and the flat
  lookup share one shape and the frontend keeps a single `StoreArea` type.
- **The edit page reads its chain from the flat lookup**, exactly as the prompt required, so
  `history.state`/query params are only a paint optimization, never the source of truth for the
  update URL.

## Delivery

| Step | Detail |
| --- | --- |
| Work unit A commit | `7278a86` — `feat(store): add flat store-area lookup endpoint` (9 files, +395/-11) |
| Work unit B commit | `ed331a4` — `feat(stores): add store areas management UI` (29 files, +3508/-25) |
| Base | `9135fa9` (merge of PR #98) on `main`, local == `origin/main` |
| Push | `feat/store-areas-flat-lookup` @ `7278a86` and `feat/store-areas-ui` @ `ed331a4`, both created from base `9135fa9` (local `main` moved back to `origin/main` with `git branch -f`, working tree untouched) |
| PRs | #99 `feat(store): add flat store-area lookup endpoint` and #100 `feat(stores): add store areas management UI` — both target `main`, no issue linkage (repo has no PR template and no issue workflow) |
| CI | PR #99 Check PASS (2m5s); PR #100 Check PASS + Lint, Build & Test PASS |
| Merge | #99 → merge commit `fd71cd9` (2026-09-17T16:48:56Z); #100 → merge commit `8ebb11b` (2026-09-17T16:50:00Z). Merge strategy `--merge`, source branches deleted (remote + local) |
| Post-merge check | on `main` @ `8ebb11b`: store-scoped backend tests 167/167 PASS (BUILD SUCCESSFUL in 48s); store-areas frontend specs 257/257 PASS (11 files) |
| Native review | not applicable: `gentle-ai review mode status` reports receipt-driven development off (global and clone-local unset) |

### Review workload

| Unit | Lines | Fit |
| --- | --- | --- |
| A (#99) | 395 | within the 400-line review budget |
| B (#100) | 3508 (1956 specs, 1051 TS, 280 HTML, 221 SCSS) | over budget — explicit maintainer-accepted `size:exception` after one honest slicing pass; no cohesive slice fits because specs dominate and tests cannot be trimmed to fit |

### Verification evidence

| Check | Command | Result |
| --- | --- | --- |
| Store-scoped backend tests | `./gradlew test --tests "com.lifecontrol.api.store.*" --no-daemon` (life-control-api) | PASS — 167 tests, 0 failures, 0 errors |
| Full backend suite | `./gradlew test --no-daemon` | PASS — 1532 tests, 0 failures, 0 errors (488 classes) |
| Backend static analysis | `./gradlew spotlessCheck spotbugsMain --no-daemon` | PASS |
| Gateway build | `./gradlew compileJava --no-daemon` (api-gateway) | PASS |
| Frontend lint | `npm run lint` | PASS — all files pass linting |
| Frontend unit tests | `npm test` | PASS — 1447/1447 (92 files) |
| Frontend coverage gate | `npm run test:coverage:check` | PASS — statements 90.63%, branches 72.49%, functions 85.10%, lines 90.63%, all within thresholds |
| Frontend build | `npm run build` | PASS — only pre-existing budget warnings (initial bundle, 4 SCSS budgets); no new warnings from store-areas files |

## Task log

### Work unit A — backend + gateway

- [x] Resolve the area by id and derive its chain before authorizing
      (`StoreAreaService#getAreaById(UUID)`, helper `chainOf(store)`).
- [x] Chain fields (`companyId`, `companyCountryId`, `regionId`, `zoneId`) on `StoreAreaResponse`,
      populated by every nested response so list and flat lookup share one shape.
- [x] `StoreAreaFlatController` at `GET /api/store-areas/{areaId}` with the same read roles as the
      nested controllers (`@PreAuthorize`, `Roles` constants, `@Tag`/`@Operation`/`@ApiResponse`).
- [x] Gateway route `/api/store-areas/**`.
- [x] Tests: `StoreAreaFlatControllerTest` added; service, controller, security and integration
      specs extended for the chain + flat lookup.

### Work unit B — Angular UI

- [x] Models (`store-area.models.ts`) and `StoreAreaService` + spec.
- [x] `StoreAreaForm` + spec.
- [x] `StoreAreasPage` (cascade + filters: store, code, name, enabled) + spec.
- [x] `StoreAreasEdit` (create/edit; chain from the flat lookup, never from in-memory state) + spec.
- [x] Routes under `/companies/store-areas` (list, `create`, `edit/:id`) guarded by `STORE_ROLES`.
- [x] Dashboard card `Store Areas` with the same role set as `STORE_ROLES` + subtitle update.
- [x] `stores-card` "Areas" action and the `stores-page` handler that carries the resolved chain.
- [x] Barrels updated (`components`, `data`, `models`, `pages`).

### Defect found and fixed during verification

- `companies-admin.component.spec.ts` still asserted the pre-existing 5-card dashboard
  (counts 5/3/2/1, the old subtitle and the old card/icon lists) and failed 11 tests once the new
  card landed. Fixed by moving the expectations to the 6-card set (`Store Areas` in every company
  role, subtitle including "store areas", `account_tree` icon). The card's `requiredRoles` were
  verified to match `STORE_ROLES` in `companies.routes.ts` exactly, so the source was correct and
  only the expectations had drifted.

## Status

Shipped. Both PRs merged to `main` (`8ebb11b`), branches deleted, post-merge checks green.

## Next step

None for this feature. Separate open item: the uncommitted purchase-orders refactor (nested detail
cards removal) still needs its own commit or discard decision.

## Out of scope (unchanged)

Zones, leaf locations, auto-provisioning, inventory/stock/movements, goods receipt, sales, flat
`GET /api/stores`, new Keycloak roles, new header menu entries.
