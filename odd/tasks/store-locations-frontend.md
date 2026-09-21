# ODD feature: store-locations-frontend

**Repository**: LifeControl — frontend `life-control-app-angular/`, with a prerequisite in `api-gateway/`
**Status**: **delivered — the chain is merged into `main` @ `c0cb1f5` and independently verified. Nothing open.**
**Created**: 2026-09-18
**Predecessors**: `odd/tasks/store-locations-backend.md` (merged, PR #108 @ `dd894a3`),
`odd/tasks/store-zones-frontend.md` (merged, PRs #104–#107 @ `19faa3e` — the pattern this feature mirrors)

## Objective

Ship the Store Locations management UI (level 4, the leaf of the store location tree): a list page
whose filter cascade includes the **store zone** level, a create/edit page that resolves its chain
from the flat lookup, a dashboard card, and a second child in the existing "Companies" header
submenu.

## Problem

The level-4 backend is merged but the frontend cannot manage locations:

1. **Zero frontend code exists.** A grep of `life-control-app-angular/src` for
   `store-location|StoreLocation` returns **zero hits**. Level 4 is unreachable from the UI even
   though levels 1–3 are reachable.
2. **Blocker (verified) — the flat lookup is unreachable through the gateway.**
   `api-gateway/src/main/java/com/lifecontrol/gateway/routes/Routes.java:74-75` routes
   `/api/store-areas/**` and `/api/store-zones/**` but has **no** `/api/store-locations/**` route.
   The nested path is covered by `/api/companies/**` (line 39), so listing works, but
   `GET /api/store-locations/{storeLocationId}` — the only way to rebuild the
   company → … → store-zone chain on a cold load (deep link / refresh) — 404s at the gateway. This
   is the same class of blocker that forced a gateway work unit in `store-zones-frontend`. There is
   no honest workaround: `companyStoreId` alone cannot be mapped back to the store → area → store
   zone chain, and no flat `GET /api/stores/{id}` exists.
3. **No menu entry exists for level 4.** The header submenu mechanism shipped with level 3
   (`header.ts:63-88` optional `children`, rendered by the `matMenuTriggerFor` block in
   `header.html`), but it holds exactly one child.

## Why this shape

- **Mirror `store-zones` 1:1.** Same file layout, same component split, same service surface, same
  spec conventions. Levels 2, 3 and 4 of one tree must stay symmetric; the level-3 document already
  recorded that "the next level (locations) is a copy, not a redesign".
- **Named `store-locations` / `storeLocationId` everywhere.** In this repository the bare terms
  already belong to other entities: `zone`/`zoneId` is *company zone* (`CompanyZone`,
  `ScopeLevel.ZONE`, claim `company_zone_id`, the `/companies/zones` route) and the bare `location`
  reads as *store* (the Stores card description literally says "operational store locations"). The
  level-3 delivery lost a full correction round to exactly this collision, so the constraint is
  treated as hard from the start.
- **The chain travels on the response.** `StoreLocationResponse` already carries
  `storeZoneId, storeAreaId, companyStoreId, companyId, companyCountryId, regionId, zoneId`, so the
  flat lookup alone lets the edit page build every nested URL. `history.state` is a paint
  optimization only.
- **The gateway route is a prerequisite, not a nicety.** Without it the edit page cannot work on a
  cold load.

## Scope

### In scope

- **Gateway**: `/api/store-locations/**` route in `Routes.java`.
- **Models**: `stores/models/store-location.models.ts` (`StoreLocation` incl. `storeZoneId` + the
  full chain, `CreateStoreLocationRequest`, `UpdateStoreLocationRequest`, `StoreLocationControl`).
- **Data**: `stores/data/store-location.service.ts` + spec
  (list / flat get / create / update / soft delete / enable).
- **Form**: `stores/components/store-location-form/*`
  (`locationCode`, `locationName`, `description`, `displayOrder`) + spec.
- **List page**: `stores/pages/store-locations-page/*` — seven-level cascade
  (Empresa → País → Región → Zona → Tienda → Área → **Zona de tienda**) loading locations for the
  selected store zone, plus a "show disabled" toggle and per-card enable/disable/edit actions + spec.
- **Edit page**: `stores/pages/store-locations-edit/*` — create + edit, chain from the flat lookup
  in edit mode and from seven query params in create mode + spec.
- **Routing**: `path: 'store-locations'` block in `companies.routes.ts` under `STORE_ROLES`.
- **Header**: a second child (`id: '2-2'`, "Store Locations", icon `shelves`) under "Companies" +
  spec updates.
- **Dashboard**: an 8th "Store Locations" card, the subtitle update, and the `companies-admin` spec
  assertions moved to the new card count.
- **Barrels**: `models/index.ts`, `data/index.ts`, `components/index.ts`, `pages/index.ts`.

### Out of scope (explicitly not implemented)

- Any backend change other than the gateway route. The 409 body is **not** enriched (see D4).
- Inventory / stock / movements, goods receipt, sales integration — level 4 still ships with zero
  consumers, recorded by the backend document.
- New Keycloak roles, new `ScopeLevel`, new JWT claims.
- A per-area "Zonas" or per-store-zone "Ubicaciones" action on the sibling cards (see D7).
- Read-only role support for `lc-company-store-read` (see D3).
- Backfilling a "Store Areas" child into the header submenu (see D8).
- e2e / Playwright coverage of the store tree — the existing `e2e/mocks/api.ts` does not cover
  stores, store areas or store zones either, so level 4 adds no e2e work (and inherits no safety net).

## Constraints (non-negotiable)

- Standalone components, `ChangeDetectionStrategy.OnPush`, no NgModules.
- `@if` / `@for` control flow only.
- Signals for UI state; RxJS only for HTTP; `rxResource` for cascaded lists in pages.
- Typed reactive forms via `NonNullableFormBuilder`.
- API URLs from `ConfigService.apiUrl`; never hardcode URLs.
- SCSS `@use` only (or a renamed copy, matching the level-2/3 precedent); reuse
  `@shared/styles/variables`.
- Code/identifiers in English; **in-page** UI copy in Spanish; nav labels and card titles in
  English (`Store Locations`), exactly the sibling convention.
- Co-located `*.spec.ts`; keep the coverage gate green
  (statements 80 / branches 60 / functions 75 / lines 80, measured as repo-wide totals by
  `scripts/check-coverage.mjs`).
- **Never** name a store-tree route, identifier or export `locations`/`location` bare, or
  `zones`/`Zone` bare. The vocabulary is `storeLocation*` and `storeZone*`.
- Never take the authoritative chain from `history.state` or query params in edit mode.

## Decisions (user-locked, this session)

- **D1 — Delivery is four chained (stacked) PRs**, mirroring `store-zones-frontend` (#104 → #105 →
  #106 → #107): (1) gateway route + models + data service; (2) form + edit page; (3) list page;
  (4) routes + header child + dashboard card. Each slice is verified green **in isolation** in its
  own `git worktree` before being pushed, because the later slices exist as untracked files in the
  main working tree and `vitest` / `ng build` can see them.
- **D2 — Both entry points are added**: an 8th dashboard card **and** a second header submenu child.
  Same choice as D1 + D8 of `store-zones-frontend`. Accepted cost: the assertions in
  `header.spec.ts` (`children?.toHaveLength(1)`) and `companies-admin.component.spec.ts`
  (7 → 8 cards, and every downstream title-array / count / icon assertion) must be updated.
- **D3 — Card and submenu-child icon is `shelves`.** Semantically precise for a physical location
  inside a zone. Verified available: the app self-hosts **Material Icons v145**
  (`public/assets/fonts/material-icons.css`), which is far newer than the D10 assumption recorded in
  `store-zones-frontend.md` ("`shelves` … would render as text if the app ships an older icon font")
  — that risk no longer applies to this repository. Already taken: `business`, `public`,
  `location_on`, `map`, `store`, `account_tree`, `grid_view`. Unverified by any test: the actual
  glyph render, which needs one visual check.
- **D4 — No field-level 409 mapping.** Verified in the level-3 delivery:
  `GlobalExceptionHandler.handleConflict` builds the body with `buildErrorResponse(status, message)`
  and carries **no `errors` map**, so the `ApiError.errors` → `serverErrors` path cannot fire for a
  duplicate. The 409 message surfaces in the general error banner. Enriching the body is a *backend*
  change and stays out of scope.
- **D5 — Toggle write failures are surfaced, not swallowed.** Mirror the **store-zones** page
  (`actionError` signal + `setActionError` + `error` callbacks on both writes,
  `store-zones-page.ts:105,438,457,465`), **not** the store-areas page, which subscribes with only a
  `next` callback and silently drops errors. This matters more at level 4: the write can be rejected
  because the store zone, **or** the area, **or** the store is disabled, so a failed enable is an
  *expected* flow ("enable the ancestor first") and a swallowed error looks like a broken button.
- **D6 — The list page's store-zone level always requests `includeDisabled=true`.** This is the
  single most error-prone deviation from the mirror. In `store-zones-page.ts:194` `getStoreZones` is
  the **final list** and respects the `showDisabled` toggle; at level 4 the same service method
  becomes the **7th filter level**, so it must behave like `storesResource` and `areasResource`
  (`:144`, `:168`), which pass `true` unconditionally so a pre-selected disabled ancestor can
  resolve. Only the new `storeLocationsResource` respects the toggle.
- **D7 — No "Ubicaciones" action on the store-zone cards.** The D2 submenu is the entry point; a
  third path would widen the diff for no requirement. Natural follow-up.
- **D8 — The header submenu keeps exactly two children** (`Store Zones`, `Store Locations`).
  Backfilling `Store Areas` was offered and **declined**: D6 of `store-zones-frontend` already
  decided against it, and reversing a closed decision is not part of this feature.
- **D9 — `lc-company-store-read` gets no special treatment in the frontend** (inherits D2 of
  `store-zones-frontend`). The role exists on the backend read endpoints and is referenced nowhere in
  the app; adding read-only support means hiding create/edit/disable actions and no read-only
  pattern exists. Consequence: a read-only user reaches the list via the route, sees the actions,
  and gets 403 on write. Follow-up only if such a user actually exists.
- **D10 — `history.state` seeding stays a paint-only optimization** and the chain is rebuilt
  exclusively from the flat lookup response, byte-for-byte the level-3 behaviour
  (`store-zones-edit.ts:69-87`).

- **D12 — The bare-`location` service method stems are accepted, not violations.** `StoreLocationService`
  exposes `getLocationById` / `createLocation` / `updateLocation` / `removeLocation` / `enableLocation`.
  These mirror the backend controller method names, are only ever called in qualified form
  (`storeLocationService.…`), and sit in the same shape as the *shipped* level 3, where
  `StoreZoneService.updateZone` / `removeZone` / `enableZone` coincide with `CompanyZoneService`'s
  names and were explicitly recorded as an accepted cosmetic follow-up in `store-zones-frontend.md`
  ("Surfaced at close → Naming purity (cosmetic)"). The hard constraint deliberately targets the
  names that can collide *unqualified in one scope* — signals, properties, component inputs/outputs,
  URL path segments, route paths, `history.state` keys and barrel exports. Applying it to qualified
  service method suffixes would retroactively flag shipped level-2/3 code. Recorded as **D12** after
  the slice-1 writer raised the tension, rather than left implicit in criterion 12.

### Accepted deviations (with rationale)

- **D11 — Review workload is ~10x the 400-line budget in total.** Amortized by D1's four slices, so
  no single PR carries the full ~4.100 lines. The specs dominate and cannot be trimmed without
  losing the behaviour coverage this repository expects.
- **The level ships with zero consumers**, inherited from the backend document. Nothing references a
  location yet; this is preparatory UI.

## Non-trivial differences from the mirror (the actual risk register)

1. **`getStoreZones` changes role** — see D6. A blind copy breaks the filter whenever a store zone is
   disabled.
2. **Seven-level cascade.** One more level must be threaded through the five one-shot
   pre-selection `effect`s, the five parent-change resets
   (`store-zones-page.ts:316-320`, `:341-378`) and `onSelectStoreZone`. This is the largest and
   least testable surface in the feature.
3. **Seven-field chain.** `StoreLocationChain` gains `storeZoneId`; create mode reads seven query
   params; `toQueryParams` maps `companyStoreId→storeId`, `storeAreaId→areaId`, `storeZoneId→storeZoneId`.
4. **Three possible disabled ancestors** in the 409 — see D5.
5. **SCSS is a renamed copy, not a shared `@use`.** Verified: `store-zones-page.scss` is a near-copy
   of `store-areas-page.scss` (the real diff is class renames plus two rules). The locations page
   follows the same convention.
6. **`StoreZoneService` is `providedIn: 'root'` with a shared `_storeZones` signal.** The locations
   page's filter level will write to it. Only one page renders at a time, so this is the same shape
   as the level-3 page writing `_areas`, and it is accepted — but the locations page must never read
   the list back from that signal.

## Work units and tasks

| # | Task | Surface | Status |
| --- | --- | --- | --- |
| T1 | Gateway route `/api/store-locations/**` | `api-gateway/.../routes/Routes.java` | done |
| T2 | Store location models + models barrel | `stores/models/store-location.models.ts`, `models/index.ts` | done |
| T3 | `StoreLocationService` + data barrel | `stores/data/store-location.service.ts`, `data/index.ts` | done |
| T4 | `StoreLocationService` spec | `stores/data/store-location.service.spec.ts` | done |
| T5 | **Slice 1 verification** (lint, test:coverage:check, build, gateway `compileJava`) | — | done |
| T6 | `StoreLocationForm` + scss + components barrel | `stores/components/store-location-form/*`, `components/index.ts` | done |
| T7 | Form spec | `stores/components/store-location-form/store-location-form.spec.ts` | done |
| T8 | `StoreLocationsEdit` (seven-field chain, flat lookup on cold load) + scss | `stores/pages/store-locations-edit/*` | done |
| T9 | Edit page spec | `stores/pages/store-locations-edit/store-locations-edit.spec.ts` | done |
| T10 | Pages barrel (partial export of the new edit page) | `stores/pages/index.ts` | done |
| T11 | **Slice 2 verification** in an isolated worktree | — | done |
| T12 | `StoreLocationsPage` (seven-level cascade, preselect, reset, toggle, `actionError`) + html + scss | `stores/pages/store-locations-page/*` | done |
| T13 | List page spec (cascade, preselect, reset, both toggles, error surfacing) | `store-locations-page.spec.ts` | done |
| T14 | **Slice 3 verification** in an isolated worktree | — | done |
| T15 | Route block `path: 'store-locations'` under `STORE_ROLES` | `features/companies/companies.routes.ts` | done |
| T16 | Header submenu second child + spec updates | `core/layout/header/header.ts`, `header.spec.ts` | done |
| T17 | Dashboard 8th card + subtitle + spec count/title/icon updates | `companies-admin.component.ts` / `.html` / `.spec.ts` | done |
| T18 | **Slice 4 verification** in an isolated worktree | — | done |
| T19 | Independent read-only verification of the whole candidate | — | done |
| T20 | Close: doc, memory, delivery decision | — | done |

## Verification plan

| Check | Command (in `life-control-app-angular` unless noted) |
| --- | --- |
| Frontend lint | `npm run lint` |
| Frontend unit tests | `npm test` |
| Frontend coverage gate | `npm run test:coverage:check` |
| Frontend build | `npm run build` (must have zero `NG####` warnings) |
| Gateway compile | `./gradlew compileJava --no-daemon` (in `api-gateway`) — slice 1 only |
| Types only | `npx tsc --noEmit -p tsconfig.spec.json` |

Per-slice runs must happen in a **`git worktree` of that slice's own branch**, not in the main
working tree, because the later slices sit there as untracked files that `vitest` and `ng build` can
see — runs in place would prove nothing (recorded lesson from `store-zones-frontend`).

## Acceptance criteria

1. `GET /api/store-locations/{storeLocationId}` is reachable through the gateway.
2. `StoreLocation` models carry `storeZoneId` plus the full chain; requests and the typed control map
   mirror level 3 field-for-field (`locationCode`, `locationName`, `description`, `displayOrder`).
3. The service exposes exactly list / flat get / create / update / soft delete / enable + `clearError`,
   with the nested URL built from seven ids and the flat URL from one.
4. The form validates `locationCode` (required, ≤10) and `locationName` (required, ≤100) against the
   backend `@Size` bounds, `description` ≤255, `displayOrder` ≥0, and preserves `displayOrder: 0`.
5. The list page cascades through **seven** levels and loads locations for the selected store zone.
6. The store-zone filter level always requests disabled rows; the locations list respects the
   "mostrar deshabilitadas" toggle (D6).
7. Deep-link / refresh on `/companies/store-locations/edit/:id` rebuilds the chain from the flat
   lookup alone, and never from `history.state` or query params (D10).
8. Create mode rejects a missing query param by navigating back to the list.
9. Toggle failures are surfaced to the user with the backend 409 message (D5), not swallowed.
10. The route is gated by `keycloakRoleGuard` with `data: { roles: STORE_ROLES, clientId: 'life-control-client' }`.
11. The header renders two children under "Companies" and the dashboard renders eight cards; all
    affected spec assertions are updated, not deleted.
12. No bare `location*` / `zone*` signal, property, component input/output, URL path segment, route
    path, `history.state` key or barrel export anywhere in the new surface (hard constraint; see D12
    for the explicitly accepted qualified-service-method exception).
13. `npm run lint`, `npm run test:coverage:check` and `npm run build` all pass, with zero `NG####`
    warnings.

## Analysis evidence (read-only, this session)

| Finding | Evidence |
| --- | --- |
| Level-4 backend merged | `odd/tasks/store-locations-backend.md` → PR #108, merge `dd894a3`; `git log --oneline -6` |
| Nested + flat endpoints | `life-control-api/.../store/controller/StoreLocationController.java` (list/get/post/put/delete/`PATCH /{id}/enable`), `StoreLocationFlatController.java` (`GET /api/store-locations/{storeLocationId}`) |
| Response carries the chain | `life-control-api/.../store/dto/StoreLocationResponse.java` |
| Gateway route missing | `api-gateway/.../routes/Routes.java:74-75` — only `/api/store-areas/**` and `/api/store-zones/**` |
| Gateway has no CI coverage | `api-ci.yml`: `paths: life-control-api/**` and `defaults.run.working-directory: life-control-api` |
| Zero frontend hits | grep `store-location\|StoreLocation` over `life-control-app-angular/src` → no matches |
| Mirror sources and sizes | `models/store-zone.models.ts` 49 · `data/store-zone.service.ts` 211 · `store-zone-form` 164+64+5 · `store-zones-page` 470+221+220 · `store-zones-edit` 200+17+5; specs 387 + 293 + 976 + 610 |
| Cascade shape | `store-zones-page.ts:108-243` (six `rxResource`s, guarded computed reads), `:316-320` (preselect flags), `:341-378` (parent resets), `:194` (only the final list respects `showDisabled`) |
| Chain from flat lookup only | `store-zones-edit.ts:66-87` (edit), `:93-121` (create via six query params), `:178-188` (`toQueryParams`) |
| Toggle error surfacing | `store-zones-page.ts:105,405-462,465` vs `store-areas-page.ts` (no `actionError`, no `error` callbacks) |
| Route convention | `companies.routes.ts:4-10` (`STORE_ROLES`), `:178-201` (`store-zones` block, lazy `loadComponent`, roles in `data`) |
| Submenu mechanism exists | `header.ts:63-88` (`children?:` on the item model, one child `2-1`), `header.html` (`matMenuTriggerFor` + `<mat-menu>`) |
| Header spec will break | `header.spec.ts:146` `children?.toHaveLength(1)`, `:147-151`, `:183-186` |
| Dashboard will break | `companies-admin.component.ts:22-99` (7 `STATIC_CARDS`), `companies-admin.component.spec.ts:95,100,105,122,123,131,146,147,155,170,171,179,212,219,230`; subtitle in `companies-admin.component.html:4` |
| Icon font version | `public/assets/fonts/material-icons.css:1` — "Google Fonts, v145", ligature-based `@font-face` |
| Coverage gate | `scripts/check-coverage.mjs` — 80/60/75/80 on `coverage/coverage-summary.json` totals |
| Test conventions | `angular.json` test target `runner: vitest`, `include: src/**/*.spec.ts`, `setupFiles: src/test-setup.ts`; no standalone `vitest.config.*`; `tsconfig.spec.json` provides `vitest/globals` |
| No e2e surface | grep for `store` in `e2e/` → only an "in-memory store" comment at `e2e/mocks/api.ts:104` |

## Risks

- **Slice 3 is the critical one.** Seven levels of cascade plus pre-selection plus reset is where the
  level-3 delivery lost a full correction round (naming) and where the `getStoreZones` role change
  (D6) can silently break the filter. It deserves the independent verification.
- **Shared-spec churn.** `header.spec.ts` and `companies-admin.component.spec.ts` already drifted
  once when the 6th card landed; D2 forces both again.
- **Gateway is unverifiable by CI.** The route's only evidence is a manual
  `./gradlew compileJava --no-daemon` in `api-gateway` plus the route appearing in the compiled
  `Routes.class`. A repo-level follow-up (add `api-gateway/**` to CI) is the durable fix.
- **No e2e safety net** for the store tree at all, at any level.
- **Review workload.** ~4.100 lines total (~1.700 production + ~2.300 spec + ~200 integration
  edits), amortized across D1's four slices; the natural split is the D1 split.

## Progress

- Analysis done (read-only). No source file was read into a write, nothing was created except this
  document and one memory entry.
- Decisions D1–D10 locked with the user before any write, per ODD step 5. **D12** added during
  slice 1 (see above).
- **Slice 1 (T1–T4) implemented** on branch `feat/store-locations-data` (cut from `main` @ `dd894a3`):
  the gateway route, the models + barrel, the data service + barrel and the service spec.
  `api-gateway/.../Routes.java` **+1/−0**; `models/index.ts` and `data/index.ts` **+1/−0** each; three
  new files (`store-location.models.ts`, `store-location.service.ts`, `store-location.service.spec.ts`).
- **Slice 1 verification (T5), fresh runs on the final revision:**

  | Check | Result |
  | --- | --- |
  | `npm run lint` | PASS — "All files pass linting." |
  | `npm run test:coverage:check` | PASS — **97 files, 1591 tests**, 0 failures; statements 91.20 / branches 73.74 / functions 86.30 / lines 91.20, all above thresholds |
  | `npx tsc --noEmit -p tsconfig.spec.json` | PASS — exit 0, no output |
  | `npm run build` | PASS — zero `NG####`; the 4 budget warnings are pre-existing and in files this change does not touch |
  | `./gradlew compileJava --no-daemon` (api-gateway) | PASS — `BUILD SUCCESSFUL`, 1 task executed |
  | Route actually compiled | `javap -c -p .../Routes.class` → `ldc #129 // String /api/store-locations/**` |

  All four checks were then **re-run in an isolated `git worktree`** (`git worktree add --detach`,
  `node_modules` symlinked) so only this slice's committed content was present, with identical results:
  lint clean, **97 files / 1591 tests**, coverage 91.20 / 73.74 / 86.30 / 91.20, build with
  **0 `NG####`**, and `javap` again showing the route constant. The worktree was removed and pruned.

  Baseline for comparison: `main` @ `dd894a3` held **1573 tests / 96 files**; the delta is exactly
  **+18 tests / +1 file**, which closes against the 18-test spec with no remainder and no test outside
  the store feature touched.
- Deviation reported by the parent, not hidden: the slice-1 writer added a *third* test to the flat-lookup
  block ("should GET the flat URL without mutating the store locations signal") that re-asserted, in
  combined form, the two properties already asserted separately by the block's other two tests. It was
  **removed by the parent** (17 lines) because it earned no coverage: test 178 already pins the exact
  flat URL + verb + response, and test 191 already pins the signal's non-mutation before/after. No
  assertion shape was lost — the spec is 18 tests, the same count as its mirror.
- **Slice 1 delivered (T5 done):** commit **`b77a280`** — `feat(stores): add store location data layer and
  gateway route`, **6 files, +779/−0**. Staged by explicit path and verified before committing:
  `git diff --cached --name-only` listed exactly the six declared surfaces, nothing outside them and
  nothing left unstaged. The repo's `pre-commit` hook (`lint-staged` → `eslint --fix` + `prettier --write`)
  ran on the 5 TS files; the four checks were **re-run on the committed revision** afterwards, so the
  committed content — not the pre-hook one — is what was verified green.
  Branch `feat/store-locations-data` cut from `main` **before** the commit, so local `main` was never
  advanced (`main == origin/main == dd894a3` before and after). Pushed, tracking set.
  **PR #109** — https://github.com/le03nava/LifeControl/pull/109, base `main`, **merged as `0bff973`**.
  **CI:** the single check, `Lint, Build & Test` (Angular CI, run 35362166101), **pass** in 1m58s. Note
  that no `API CI` check appears on this PR at all: `api-ci.yml` is path-filtered to
  `life-control-api/**` and runs with `working-directory: life-control-api`, so the gateway route this
  slice adds is **the one part of the feature with zero CI coverage** — exactly the gap the
  store-zones chain recorded for #104. Labels: none (the repo has no `type:feature` label).
- **Independently verified by the parent, not taken from the writer's report** (two findings, both closed):
  the writer's `./gradlew compileJava --no-daemon` claim was checked rather than trusted —
  `api-gateway/` is a **separate Gradle build** with its own *gitignored* wrapper (`gradlew`, `gradle/`,
  `bin/`, `build/` are all untracked there), which is why the isolated worktree checkout has no wrapper
  and the claim initially looked unreproducible. It is legitimate: `javap` on that build's
  `Routes.class` shows `ldc #129 // String /api/store-locations/**`, and the class mtime matches the
  run. Second finding: the writer added a *third* test to the flat-lookup block that re-asserted, in
  combined form, the two properties already asserted separately by that block's other two tests; the
  parent removed it (no coverage lost — 178 pins the exact flat URL + verb + response, 191 pins the
  signal's non-mutation), leaving 18 tests, the same count as the mirror.

### Slice 2 delivered (T6–T11 done)

- Commit **`dbfdf7f`** — `feat(stores): add store location form and create/edit page`, **10 files,
  +1429/−0**: the `StoreLocationForm` component (`.ts` 162, `.html` 64, `.scss` 5), its 22-test spec,
  the `StoreLocationsEdit` page (`.ts` 216, `.html` 16, `.scss` 4), its 37-test spec, and the two
  barrel appends. Staged by explicit path; `git diff --cached --name-only` listed exactly the ten
  declared surfaces and nothing was left unstaged. The `pre-commit` hook reformatted nothing this time
  (the form `.ts` is 162 lines, i.e. the 164 the writer reported minus the two lines removed below).
- Branch `feat/store-locations-form` cut from `main` @ `0bff973` **before** the commit; local `main`
  was not advanced. Pushed, **PR #110** — https://github.com/le03nava/LifeControl/pull/110 — CI
  `Lint, Build & Test` **pass** in 2m55s (run 35371208478), merged as **`fc1808a`** on the user's
  instruction. `git diff dbfdf7f fc1808a` empty, `fc1808a^{tree} == dbfdf7f^{tree}`, payload
  10 files / +1429, `main == origin/main == fc1808a`, clean.
- **Slice 2 verification (T11), fresh runs in an isolated `git worktree` at `dbfdf7f`** (the absence
  of `stores/pages/store-locations-page/` there was checked, proving slice 3's files were not present):

  | Check | Result |
  | --- | --- |
  | `npm run lint` | PASS |
  | `npx tsc --noEmit -p tsconfig.spec.json` | PASS — exit 0 |
  | `npm run test:coverage:check` | PASS — **99 files, 1650 tests**; statements 91.32 / branches 74.05 / functions 86.57 / lines 91.32, all above thresholds |
  | `npm run build` | PASS — exit 0, **0 `NG####`**, only the 4 pre-existing budget warnings |

  Baseline `main` @ `0bff973`: **97 files / 1591 tests**. Delta **+2 files / +59 tests**, closing
  exactly: 22 form tests (1:1 with the mirror) + 37 edit tests (the mirror runs 36).
- **Parity verified, not asserted.** A token-normalized diff against the mirrors is **empty** for the
  form `.ts`, both `.scss` files and the page `.scss`; the form `.html` differs only in its six Spanish
  strings and the placeholder; the page `.html` only in the class name, title, subtitle and component
  bindings (`[storeLocation]`, plus the unchanged `[serverErrors]` / `(save)` / `(cancelForm)`); and the
  page `.ts` diff is exactly the seven-field threading (`StoreLocationChain` +`storeZoneId`; the
  edit-mode `chain.set` maps `storeZoneId <- storeZoneId`; the create-mode param read and its
  missing-param guard grow to seven; both service call sites gain `chain.storeZoneId`; `onCancel` and
  `toQueryParams` gain a `storeZoneId` key).
- **The `it.each` growth was audited, not assumed.** The edit spec's missing-query-param table goes from
  6 entries to 7; the new one is `storeZoneId`, which slice 2 genuinely makes a required create-mode
  param. That is the entire +1 over the mirror — no padded or duplicate test, unlike the slice-1
  finding.

### Slice 2 findings and their disposition

| ID | Severity | Finding | Disposition |
| --- | --- | --- | --- |
| S1 | non-blocking (dead code, inherited) | `store-zone-form.ts:41` injects `DestroyRef` and never uses it (only two occurrences in the file: the import and the injection); `ng lint` does not flag unused private members. The writer mirrored it verbatim. | **Removed from the new file by the parent.** Copying a proven defect into new code is not what "mirror the sibling" means. The sibling's instance is outside this slice's surfaces and is left untouched — see follow-up F9. |
| S2 | process (parent brief error) | The slice-2 brief said to bind nullable controls with `{ nonNullable: false }` "exactly as the mirror does"; the mirror actually uses `this.fb.control<string \| null>(null, [...])`. | **No change needed** — the writer followed the mirror, which the brief itself made authoritative. Recorded so the error is not attributed to the writer. |
| S3 | informational (env) | The writer's first `npm run test:coverage:check` was OOM-killed (exit 137) on this 11 GB box after its output was piped into a command that closed the pipe early, leaving 11 reparented vitest workers at ~9 GB RSS and load ~90. The writer terminated its own orphans and re-ran cleanly. Confirmed by the parent after the fact: no orphaned workers remained, 8.6 GiB free. | **No repository impact.** Recorded because the suite runs close to the memory ceiling here; the parent re-ran the gate without such a pipeline and it passed. |


### Slice 3 delivered (T12–T14 done) — the riskiest slice

- Commit **`7239b16`** — `feat(stores): add store locations list page with store-zone-level cascade`,
  **5 files, +2189/−0**: `store-locations-page.ts` 550, `.html` 240, `.scss` 221, `.spec.ts` 1177
  (43 tests) and one barrel insertion placed before `StoreLocationsEdit` so the pair reads
  Page-then-Edit. Branch cut from `main` @ `fc1808a`; **PR #111**, CI **pass** in 2m39s (run
  35375311484), merged as **`1eb786d`** — payload 5 files / +2189, `git diff 7239b16 1eb786d` empty and
  the trees identical.
- **Slice 3 verification (T14), isolated `git worktree` at `7239b16`** (confirmed slice 4's wiring was
  absent: `store-locations` appeared **0 times** in both `companies.routes.ts` and `header.ts`):
  lint PASS · tsc PASS · **100 files / 1693 tests**, coverage 91.67 / 74.25 / 87.16 / 91.67 · build
  exit 0 with **0 `NG####`** and only the 4 pre-existing budget warnings. Baseline `main` @ `fc1808a`
  was 99 / 1650 → delta **+1 file / +43 tests**, closing exactly.
- **D6 verified by the parent in the code, not from the report:** `storeZonesResource` params key on
  `selectedArea()` only — no `showDisabled`, no `reload` — and its `getStoreZones(..., true)` last
  argument is a literal `true`; `storeLocationsResource` keys on the store zone + `showDisabled()` +
  `reload()` and passes `params.showDisabled`. The two D6 spec tests are count-based (they capture
  `mock.calls.length`, flip the toggle, and assert the filter count unchanged and the list count +1),
  not URL-based, and the parent read them rather than trusting the green run.
- **Reset matrix audited by the parent**: `onCompanyChange` → 6 clears, `onSelectCountry` → 5,
  `onSelectRegion` → 4, `onSelectCompanyZone` → 3, `onSelectStore` → 2, `onSelectArea` → 1
  (`selectedStoreZone`), `onSelectStoreZone` → leaf. Every level clears strictly below itself.

### Slice 4 delivered (T15–T18 done) — chain complete

- Commit **`c34ec32`** — `feat(stores): wire store locations into routes, header submenu and dashboard`,
  **6 files, +90/−23**. Branch cut from `main` @ `1eb786d`; **PR #112**, CI **pass** in 3m24s (run
  35376576109), merged as **`c0cb1f5`** — payload 6 files / +90/−23, `git diff c34ec32 c0cb1f5` empty
  and the trees identical. All four chain branches are gone (remote and local).
- This slice did **not** touch `header.html` or `header.scss`, unlike the level-3 wiring commit
  (`52c945f`, +19/+25), because the submenu mechanism already exists — exactly the "one-time durable
  investment" D1 of `store-zones-frontend` predicted.
- **Spec assertions were updated, never deleted.** Audited line by line by the parent: the dashboard
  spec's 21 deletions are all value changes that reappear as additions (counts 7→8, 5→6, 4→5, 3→4;
  title arrays + `Store Locations`; `icons` 7→8; `+ toContain('shelves')`; the exact subtitle string;
  four `it(...)` names). `header.spec.ts` has exactly one deletion (`toHaveLength(1)` → `2`) and the
  `children?.[0]` assertion is byte-identical to before. No `it` block was added or removed, so the
  suite total stays at 100 / 1693 — the correct shape for a slice that only re-points assertions.
- **The caret-trigger test was correctly left at 1** — it counts `.submenu-trigger` elements, i.e.
  *items* that have children, not children. Verified by reading the test, not assumed. **D8 respected:**
  no "Store Areas" child was backfilled.
- **Slice 4 verification (T18), isolated `git worktree` at `c34ec32`**: lint PASS · tsc PASS ·
  **100 files / 1693 tests**, coverage 91.47 / 74.27 / 87.18 / 91.47 · build exit 0, **0 `NG####`**,
  4 pre-existing budget warnings.
- **End-to-end bundle evidence (first slice where the wiring is observable in a real build):**
  `chunk-NKIN2QAE.js` is the named `store-locations-page` chunk (22.67 kB, contains the
  `Ubicaciones de Tienda` title); `chunk-XNJUPWYL.js` contains `store-locations-edit` and its unique
  subtitle `zona de tienda seleccionada`; `main-*.js` contains the route `companies/store-locations`
  plus the nav label `Store Locations` and the icon `shelves`. Both route children resolve to real
  chunks — the router entry and nav label are not merely source-level claims.

### Post-merge state

| Merge | Commit | Payload | Tree check |
| --- | --- | --- | --- |
| #109 | `0bff973` | 6 files, +779 | `tree == b77a280^{tree}`, `git diff` empty |
| #110 | `fc1808a` | 10 files, +1429 | `tree == dbfdf7f^{tree}`, `git diff` empty |
| #111 | `1eb786d` | 5 files, +2189 | `tree == 7239b16^{tree}`, `git diff` empty |
| #112 | `c0cb1f5` | 6 files, +90/−23 | `tree == c34ec32^{tree}`, `git diff` empty |

In every case the merged tree is byte-identical to the verified PR head across the whole repository,
so no merge introduced anything beyond the reviewed candidate. `main == origin/main == c0cb1f5`,
working tree clean, no chain branch left. Gates re-run on `main` as an **observation**, not a
deduction: **100 files / 1693 tests**, coverage 91.47 / 74.27 / 87.18 / 91.47, build exit 0 with
**0 `NG####`** and both new lazy chunks emitted.

### T19 — independent read-only verification of the whole candidate

A separate read-only verification agent checked the complete feature on `main` @ `c0cb1f5` against
this document's acceptance criteria, with fresh command runs and no edits.

**Verdict: no blocking findings. 13/13 acceptance criteria PASS.** Non-vacuity spot checks across all
four slices came back clean, and the D6 pair was confirmed to fail in both realistic revert
directions (flipping the literal `true` to `this.showDisabled()`, or adding `showDisabled` to the
params — the latter trips the call-count assertion). The reset matrix is a strict superset of level
3's with the seventh level added, and each row is asserted.

**Test-honesty verdict: clean.** Deletions across the whole feature `git diff dd894a3 c0cb1f5`:
`header.spec.ts` **1**, `companies-admin.component.spec.ts` **21**, the four new specs **0**. All 22
are value updates; no `expect` dropped, no matcher loosened, no assertion replaced by a vaguer one.
The two modified specs keep their exact test counts (41 + 1 `it.each`, and 14).

**Closure arithmetic:** literal `it(` +113 = 18 + 22 + 30 + 43; `it.each` +1 block expanding to 7
tests; new-feature tests 18 + 22 + 37 + 43 = **120**; 1573 + 120 = **1693**, exactly the observed
run. No test outside the store feature was touched.

**Naming audit (criterion 12 / D12): clean.** No unqualified `location*` identifier, signal, input,
output, URL path segment, route path, `history.state` key or barrel export. All 93 `zoneId`
occurrences verified to be the **company** zone, including the `/zones/${zoneId}` path segment and
the create-mode query param read only under the alias `companyZoneId`. Every service-method stem
using the bare `location` form is inside the D12 exemption and is always called qualified.

### T19 findings and their disposition

| ID | Severity | Finding | Disposition |
| --- | --- | --- | --- |
| V1 | non-blocking | The gateway route has **zero CI coverage and zero tests**: `api-ci.yml` is path-filtered to `life-control-api/**` and runs `working-directory: life-control-api`; `grep -rn 'api-gateway' .github/workflows/` has **no match**; `api-gateway/src/test` contains only `JwtIssuerAllowlistValidatorTests`, `SecurityConfigTests`, `ApiApplicationTests` with no reference to `Routes`/`RequestPredicates`. Slice 1's route is compile-verified only. | **Accepted as a pre-existing repo-level gap**, inherited by this feature. See follow-up F11. |
| V2 | non-blocking (UX, inherited) | A failed toggle sets `actionError` from the backend body **and** the service sets its own `error` signal, which the page renders separately — so the user sees the 409 message *and* `Error al deshabilitar la ubicación`. | **Inherited verbatim from level 3** (`store-zones-page.html` renders both surfaces the same way); the page-spec mock never sets the service signal so no test reveals it. Recorded as F10 rather than fixed here: the fix touches both pages and belongs in its own change. |
| V3 | process (doc) | This document still showed T12–T18 as `pending` and `Status: planned — nothing started` while slices 3–4 were merged. | **RESOLVED by T20** — this revision. |

### What the independent verification could not verify (recorded, none blocking)

- **Executed test counts at each slice commit** — reproducing them needs a worktree, which the
  read-only brief forbade. The closure was verified statically plus one executed run at HEAD. The
  per-slice executed counts in this document come from the parent's own isolated-worktree runs.
- **CI status of PRs #109–#112** — asserted in this document from `gh pr checks` output the parent
  observed; not re-checked by the verifier.
- **End-to-end gateway routing** of `GET /api/store-locations/{id}` — compile-level evidence only
  (source line + `javap` constant); no gateway test exists and no containers were started.
- **Visual render of the `shelves` glyph** — the font is confirmed self-hosted Material Icons v145,
  but no browser render was performed. This is D3's open visual check (F13).
- **The 409 body against a live backend** — proved against a stub body plus the handler's source shape.
- **Header DOM rendering of two `mat-menu-item`s** — the template loop is generic and unchanged, but
  no test opens the submenu; only the model is asserted (F12).

## Next step

**The feature is complete and merged.** Store levels 1-4 are all reachable and manageable from the UI:
`Store → Area → Zone → Location`, with a uniform surface, one cascade implementation per edge and the
same access model. Nothing is in progress and no follow-up blocks anything.

### Follow-ups recorded (none blocking)

- **F9** — remove the unused `DestroyRef` injection from `store-zone-form.ts:41` (the pre-existing
  sibling of slice-2 finding S1).
- **F10** — the duplicate error surface on a failed toggle (V2): both pages render the service `error`
  signal *and* `actionError` for the same failure. Fix both pages together.
- **F11** — `api-gateway/**` has no CI coverage at all (V1). A repo-level fix in `.github/workflows/`.
- **F12** — no spec references `companyRoutes`, so the route block is untested. The same gap exists
  for level 3 (`52c945f`), so this is the repository's convention, not a regression.
- **F13** — one visual check that `shelves` actually renders as a glyph rather than literal text (D3).
- **Pagination** for the `areas`, `store-zones` and `store-locations` list endpoints, still open from
  the level-3 deviation.
- **Level 3's F5/F7** — the `company → … → zone` subsystem does not enforce the disabled-ancestor
  invariant, and the lock-free TOCTOU race in the disabled-ancestor guard.
- **`lc-company-store-read`** — a read-only user reaches the list and gets a 403 on write (D9).
- **Level 4 still has zero consumers** — nothing in the schema references a location yet.

Merged as **`0bff973`** (`gh pr merge 109 --merge --delete-branch`), branch deleted on the remote and
locally. `git diff --shortstat dd894a3 0bff973` = **6 files, 779 insertions**, exactly the reviewed
candidate; `git diff b77a280 0bff973` is empty and `0bff973^{tree} == b77a280^{tree}` — the merge
introduced literally nothing beyond the verified PR head. `main == origin/main == 0bff973`, clean.
Gates re-run on `main`: **97 files / 1591 tests**, coverage 91.20 / 73.74 / 86.30 / 91.20 at
threshold. `spotless`/`bootJar`-style deductions do not apply here; the Angular gate was observed.
