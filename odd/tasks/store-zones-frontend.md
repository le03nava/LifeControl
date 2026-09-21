# ODD feature: store-zones-frontend

**Repository**: LifeControl — frontend `life-control-app-angular/`, with a prerequisite in `api-gateway/`
**Status**: in progress
**Created**: 2026-09-17
**Predecessors**: `odd/tasks/store-zones-backend.md` (merged, PR #103 @ 2c21fd5) and
`odd/tasks/store-areas-frontend.md` (merged, PRs #99/#100 — the pattern this feature mirrors)

## Objective

Ship the Store Zones management UI (level 3 of the store location tree): a list page whose filter
cascade includes the **area** level, a create/edit page that resolves its chain from the flat
lookup, a dashboard card, and navigation from the header menu through a new submenu mechanism.

## Problem

The backend for store zones is merged but the frontend cannot manage them:

1. **Zero frontend code exists** for store zones (grep of `src` for `store-zone`/`StoreZone` is
   empty). Without this feature, level 3 is unreachable from the UI even though levels 1–2 are.
2. **Blocker (verified) — the flat lookup is unreachable through the gateway.**
   `api-gateway/src/main/java/com/lifecontrol/gateway/routes/Routes.java` routes
   `/api/store-areas/**` (line 74) but has **no** `/api/store-zones/**` route. The nested path is
   covered by `/api/companies/**` (line 39), so listing works, but
   `GET /api/store-zones/{storeZoneId}` — the only way to rebuild the company → … → area chain on
   a cold load (deep link / refresh) — 404s at the gateway. This is the same class of blocker that
   forced a backend + gateway work unit in `store-areas-frontend`.
3. **No menu entry path exists for a third level of the store tree.** The header module menu is
   flat, one item per module (`header.ts:63-88`, rendered by a single `@for` in `header.html:12-28`),
   and the only `mat-menu` is the user menu. Store areas shipped with a dashboard card + a
   `stores-card` action instead.

## Why this shape

- **Mirror `store-areas` 1:1.** Same file layout, same component split, same service surface, same
  spec conventions. The two levels of the same tree must stay symmetric so the next level
  (locations) is a copy, not a redesign.
- **Named `store-zones` / `storeZoneId` everywhere.** In this repo `zone`/`zoneId` already means
  *company zone* (`CompanyZone`, `ScopeLevel.ZONE`, claim `company_zone_id`, the `/companies/zones`
  route). A store zone is a different entity, so it never reuses the bare term — same rule the
  backend locked in D1 of the predecessor.
- **The chain travels on the response.** `StoreZoneResponse` already carries
  `storeAreaId, companyStoreId, companyId, companyCountryId, regionId, zoneId`, so the flat lookup
  alone lets the edit page build every nested URL. `history.state` is a paint optimization only.
- **The gateway route is a prerequisite, not a nicety.** Without it the edit page cannot work on a
  cold load, and there is no honest workaround: `companyStoreId` alone cannot be mapped back to the
  store → area chain, and no flat `GET /api/stores/{id}` exists.

## Scope

### In scope

- **Gateway**: `/api/store-zones/**` route in `Routes.java`.
- **Models**: `stores/models/store-zone.models.ts` (`StoreZone` incl. `storeAreaId` + chain,
  `CreateStoreZoneRequest`, `UpdateStoreZoneRequest`, `StoreZoneControl`).
- **Data**: `stores/data/store-zone.service.ts` + spec (list / flat get / create / update / soft
  delete / enable).
- **Form**: `stores/components/store-zone-form/*` (`zoneCode`, `zoneName`, `description`,
  `displayOrder`) + spec.
- **List page**: `stores/pages/store-zones-page/*` — six-level cascade
  (Empresa → País → Región → Zona → Tienda → **Área**) loading zones for the selected area, plus a
  "show disabled" toggle and per-card enable/disable/edit actions + spec.
- **Edit page**: `stores/pages/store-zones-edit/*` — create + edit, chain from the flat lookup in
  edit mode and from query params in create mode + spec.
- **Routing**: `path: 'store-zones'` block in `companies.routes.ts` under `STORE_ROLES`.
- **Header submenu**: an optional `children` array on the menu item model, a caret trigger opening
  a `mat-menu`, and the "Store Zones" child under "Companies" + spec updates.
- **Dashboard**: a "Store Zones" card + subtitle update, and the `companies-admin` spec assertions
  moved to the new card count.
- **Barrels**: `models/index.ts`, `data/index.ts`, `components/index.ts`, `pages/index.ts`.

### Out of scope (explicitly not implemented)

- Leaf `store_locations` (level 4), inventory/stock/movements, goods receipt, sales integration.
- New Keycloak roles, new `ScopeLevel`, new JWT claims.
- Any backend change other than the gateway route. In particular the 409 body is **not** enriched
  (see D3).
- A per-area "Zonas" action on the `store-areas-page` cards (see D6).
- Read-only role support (see D2).

## Constraints (non-negotiable)

- Standalone components, `ChangeDetectionStrategy.OnPush`, no NgModules.
- `@if` / `@for` control flow only.
- Signals for UI state; RxJS only for HTTP; `rxResource` for cascaded lists in pages.
- Typed reactive forms via `NonNullableFormBuilder`.
- API URLs from `ConfigService.apiUrl`; never hardcode URLs.
- SCSS `@use` only; reuse `@shared/styles/variables`.
- Code/identifiers in English; **in-page** UI copy in Spanish.
- Co-located `*.spec.ts`; keep coverage thresholds green
  (statements 80 / branches 60 / functions 75 / lines 80).
- Never name a store-tree route or export `zones`/`Zone` — that term belongs to `CompanyZone`.

## Decisions

### User-locked (this session)

- **D1 — Menu access is a nested submenu under "Companies"**, not a new top-level item and not only
  a dashboard card. Rationale: the store tree keeps growing (areas shipped, zones now, locations
  later), so the submenu mechanism is a one-time durable investment. The user explicitly selected
  this over the two cheaper alternatives.

### Defaults locked by the parent, surfaceable at close

- **D2 — `lc-company-store-read` is NOT granted access in the frontend.** The role exists on the
  backend read endpoints and is referenced **nowhere** in the frontend. Adding read-only support
  means hiding create/edit/disable actions, and no read-only pattern exists in this app (the
  sibling `store-areas` page always renders the actions). Assuming a read-only population would be
  inventing a requirement. Consequence: a read-only user can reach the list via the route but sees
  the actions and gets 403 on write. Follow-up if such a user actually exists.
- **D3 — No field-level 409 mapping.** Verified: `GlobalExceptionHandler.handleConflict` builds the
  body with `buildErrorResponse(status, message)` — **no `errors` map** — so the edit page's
  existing `ApiError.errors` → `serverErrors` path cannot fire for a duplicate. The 409 message
  surfaces in the general error banner, exactly as store areas behaves. Enriching the 409 body is a
  *backend* change and stays out of scope.
- **D4 — Toggle write failures are surfaced, not swallowed.** The sibling `store-areas-page`
  subscribes enable/disable with only a `next` callback, so an error is silently dropped. That is
  tolerable for areas; for zones the cascade (`StoreAreaService.deleteArea()` disables its zones)
  and the `DisabledParentException` 409 make a failed enable an *expected* flow ("enable the area
  first"). Silently swallowing it would look like a broken button. One small, bounded improvement
  over the mirror: route both toggle outcomes through an error signal shown by the page.
- **D5 — No new role gating for the submenu child.** The header's `isCompanyRole` is already
  exactly the six `STORE_ROLES`, which is also the route guard, so the child inherits the parent's
  visibility and menu/route stay consistent. No new signal.
- **D6 — No "Zonas" action on the area cards.** The D1 submenu is the entry point; adding a third
  path would widen the diff for no requirement. Natural follow-up.
- **D7 — "Companies" keeps navigating to `/companies`.** The parent stays a link and gains a
  separate caret button that opens the submenu. Turning the parent into a pure trigger would
  regress the existing click-to-dashboard behavior for every company user.
- **D8 — The dashboard card is added.** Module consistency (every level has one) and it is the
  in-module menu. Cost: the 7th card re-touches `companies-admin.component.spec.ts` count
  assertions, which already drifted once when the 6th card landed.
- **D9 — Header/card labels stay English, page copy stays Spanish.** Exactly the sibling
  convention: nav items ("Companies", "Sales") and card titles ("Store Areas") are English while
  page internals ("Deshabilitar área") are Spanish. The child is therefore labeled **"Store
  Zones"**, not "Zonas de tienda".
- **D10 — Card icon is `grid_view`.** `map` is taken by company Zones and `account_tree` by Store
  Areas. `grid_view` is present in the classic Material Icons set, unlike newer names such as
  `shelves`, which would render as text if the app ships an older icon font.

## Work units and tasks

| # | Task | Surface | Status |
| --- | --- | --- | --- |
| 1 | Gateway route `/api/store-zones/**` | `api-gateway/.../routes/Routes.java` | done |
| 2 | Store zone models + models barrel | `stores/models/store-zone.models.ts`, `models/index.ts` | done |
| 3 | `StoreZoneService` + spec + data barrel | `stores/data/store-zone.service.ts` (+spec), `data/index.ts` | done |
| 4 | `StoreZoneForm` + spec + components barrel | `stores/components/store-zone-form/*`, `components/index.ts` | done |
| 5 | `StoreZonesPage` (six-level cascade, area filter) + spec | `stores/pages/store-zones-page/*` | done |
| 6 | `StoreZonesEdit` (cold-load chain via flat lookup) + spec | `stores/pages/store-zones-edit/*` | done |
| 7 | Pages barrel | `stores/pages/index.ts` | done |
| 8 | Route block `path: 'store-zones'` | `features/companies/companies.routes.ts` | done |
| 9 | Header submenu mechanism + "Store Zones" child + specs | `core/layout/header/header.ts`, `header.html`, `header.scss`, `header.spec.ts` | done |
| 10 | Dashboard card + subtitle + spec counts | `companies-admin.component.ts` / `.html` / `.spec.ts` | done |
| 11 | Verification pass (lint, tests, coverage, build, gateway compile) | — | done |
| 12 | Close: doc, memory, delivery decision | — | done |

## Verification plan

| Check | Command |
| --- | --- |
| Frontend lint | `npm run lint` (in `life-control-app-angular`) |
| Frontend unit tests | `npm test` |
| Frontend coverage gate | `npm run test:coverage:check` |
| Frontend build | `npm run build` |
| Gateway compile | `./gradlew compileJava --no-daemon` (in `api-gateway`) |

## Verification evidence (executed 2026-09-17)

All gates run on the uncommitted working tree (base `2c21fd5`), and re-run after the naming round:

| Check | Result |
| --- | --- |
| `npm run lint` | `All files pass linting.` (0 errors, 0 warnings) |
| `npm run test:coverage:check` | `Test Files 96 passed (96)`, `Tests 1573 passed (1573)`; statements 91.04% / branches 73.44% / functions 86.18% / lines 91.04% — all OK |
| `npm run build` | success, **zero `NG####` warnings**; 4 pre-existing budget warnings on files this change does not touch |
| `./gradlew compileJava --no-daemon` | `BUILD SUCCESSFUL` (route confirmed present in the compiled `Routes.class`) |
| `tsc --noEmit -p tsconfig.spec.json` | exit 0 |

**Independent verification (read-only, separate agent, two rounds).** Round 1 checked the change
against this document item by item (gateway, models, service surface, form, both pages, routing,
header, dashboard, constraints, naming) and confirmed every acceptance item with file:line
evidence, plus the D4 difference from the sibling area page (zone toggles surface errors, area
toggles swallow them). It found **one candidate-caused blocker**: the store-zone surface reused the
bare `zone`/`zones` term — `StoreZoneService.zones`/`getZones` directly collided with
`CompanyZoneService.zones`/`getZones`, and `StoreZonesEdit.zoneId` shadowed `chain.zoneId` inside
one class. That violates the hard constraint in this document (the term belongs to the company
zone).

**Correction round (bounded rename, zero behaviour change).** Every store-zone-meaning identifier
moved to the `storeZone*` vocabulary: `_storeZones`/`storeZones`/`storeZonesUrl`/`getStoreZones`,
the form input `storeZone` (with the edit page's `[storeZone]` binding and every `setInput` key),
`StoreZonesEdit.storeZoneId`/`storeZone`, the `history.state` key `storeZone` (writer and reader
agree), `initialCompanyZoneId`, `onCreateStoreZone`/`onEditStoreZone`/`onToggleStoreZone`, and the
page's CSS classes (`store-zones-grid`, `store-zone-card`, `store-zone-code-badge`,
`company-zone-selector`). The company-zone contract was deliberately left untouched and re-verified
intact: the nested URL path segment `/zones/${zoneId}`, the `zoneId` method parameters and
`StoreZoneChain.zoneId`, the `'zoneId'` query-param key that round-trips list → edit, and the
`StoreZone.zoneId/zoneCode/zoneName` model fields.

Two supporting fixes landed in the same round: the pages barrel gained the missing
`StoreZonesEdit` export (sibling parity), two non-asserting lines were deleted
(`expect(zoneService.updateZone).toBeDefined()` and a discarded mock statement, plus the orphaned
injection the first one left behind), and `header.html` dropped a redundant `?? []` that the
Angular compiler flagged as `NG8102`.

Round 2 re-verified closure: the blocker, the barrel and the dead lines are all CLOSED with no
collateral assertion loss (test count unchanged at 1573), the company-zone contract is intact, and
the gates are green. Verdict: **no candidate-caused blockers remain**; room for human review.

Unverified by construction: no browser render of the header submenu in the collapsed drawer
(reachability is confirmed structurally — one `<ul>`, no media query hides the trigger), and the
initial-bundle budget warning cannot be attributed to base or change without a base build.

## Risks

- **Review workload.** Mirroring `store-areas` (3,066 lines) plus the header submenu lands around
  3,300–3,500 lines, far over the 400-line budget; specs dominate and cannot be trimmed to fit
  without losing the behaviour-focused coverage the repo expects. To be surfaced at close with the
  natural split (gateway / data+form+edit / list page), mirroring how `store-areas-frontend` was
  accepted as an atomic UI PR with an explicit `size:exception`.
- **Header is shared infrastructure.** The submenu touches every module's navigation. Mitigated by
  keeping `children` optional (the 20 existing `items()` assertions keep passing) and by not
  changing what "Companies" does (D7).
- **Mobile drawer.** The same `<ul>` renders desktop and the collapsed drawer, so the submenu
  overlay must be checked in the drawer, not only on desktop.

## Surfaced at close (previously locked defaults, plus follow-ups)

- **D2 read-only role gap.** `lc-company-store-read` gets no special treatment; a read-only user
  reaches the list and gets 403 on write. Real follow-up only if such a user exists.
- **D3 409 body.** No field-level duplicate mapping, by verified backend limitation
  (`handleConflict` carries no `errors` map). Enriching it is a backend change.
- **D6 no "Zonas" action on the area cards.** The D1 submenu is the entry point; a third path is a
  natural follow-up.
- **Naming purity (cosmetic).** `StoreZoneService.updateZone`/`removeZone`/`enableZone` coincide with
  `CompanyZoneService`'s method names; the call site is always `storeZoneService.…` and the shape
  mirrors `StoreAreaService`, so this was left as-is rather than widened into another rename round.
- **Review workload.** The change is roughly 3,900 lines (13 modified + 15 new files), far over the
  400-line budget; the specs dominate and cannot be trimmed without losing behaviour coverage. The
  natural split, mirroring `store-areas-frontend`, is: (1) gateway route + models + service,
  (2) form + edit page, (3) list page, (4) header submenu + dashboard card.

## Delivery — chained PRs (stacked to main)

Delivered as 4 stacked PRs, each cut from the previous one and each verified green **in isolation**
in a `git worktree` of its own branch (the main working tree still held the later slices as untracked
files, which `vitest` and `ng build` can see — so per-branch runs there would have proved nothing):

| PR | Branch | Base | Slice | Lines | Isolated evidence | CI |
| --- | --- | --- | --- | --- | --- | --- |
| [#104](https://github.com/le03nava/LifeControl/pull/104) | `feat/store-zones-data` | `main` | gateway route + models + data service | +650 / -0 (6 files) | 93 files, 1465 tests; coverage 90.77/72.84/85.24/90.77; lint clean; 0 `NG####`; gateway compiles | pass |
| [#105](https://github.com/le03nava/LifeControl/pull/105) | `feat/store-zones-form` | `feat/store-zones-data` | form + create/edit page | +1355 / -0 (10 files) | 95 files, 1523 tests; 90.91/73.19/85.55/90.91; lint clean; 0 `NG####` | pass |
| [#106](https://github.com/le03nava/LifeControl/pull/106) | `feat/store-zones-list` | `feat/store-zones-form` | list page (six-level cascade) | +1884 / -0 (5 files) | 96 files, 1562 tests; 91.25/73.42/86.15/91.25; lint clean; 0 `NG####` | pass |
| [#107](https://github.com/le03nava/LifeControl/pull/107) | `feat/store-zones-ui` | `feat/store-zones-list` | routes + header submenu + dashboard card | +191 / -24 (8 files) | 96 files, 1573 tests; 91.04/73.44/86.18/91.04; lint clean; 0 `NG####` | pass |

Chain: `main` ← #104 ← #105 ← #106 ← #107. Every PR body carries the Chain Context table, the
dependency diagram, its scope/exclusions and its own isolated test plan. The test count rises
monotonically per slice (1465 → 1523 → 1562 → 1573), which is the proof that each slice carries its
own specs and passes without the later ones.

**Integrity of the split.** The union of the four commits reproduces the verified candidate exactly,
except that the repo's own `pre-commit` hook (`lint-staged` → `eslint --fix` + `prettier --write`) got
the last word on 3 files: `store-zone.service.ts`, `store-zone.service.spec.ts` and
`store-zones-edit.ts`. The committed content is therefore the repo-tooling-normalised version; the
final slice was re-verified green afterwards (lint, 1573 tests, coverage gate, build with 0 `NG####`),
so the delta is formatting-only and never behaviour. `pages/index.ts` needed a deliberate partial
stage so each barrel export lands with the file it points at.

**Not covered by CI:** `api-ci.yml` triggers on `push: [main]` unconditionally but only for
`pull_request` paths `life-control-api/**`, **and its job runs with
`defaults.run.working-directory: life-control-api`** — so it never compiles `api-gateway` at all,
not even on the post-merge push to `main`. The gateway route in #104 is therefore the one part of
this feature with zero CI coverage; its evidence is the manual `./gradlew compileJava --no-daemon`
in `api-gateway` plus the route being present in the compiled `Routes.class`. Worth a repo-level
follow-up (add `api-gateway/**` coverage) rather than a per-PR workaround.
PRs are opened without labels, matching the repo's own precedent (#100, #103 both have none); the
`type:feature` label does not exist in this repository.

## Merge

Merged in chain order with merge commits (the repo's convention — see #103), `main` was not
protected, so no bypass was needed:

| PR | Merge commit | Note |
| --- | --- | --- |
| #104 | `0058c3c` | into `main` |
| #105 | `8281e04` | retargeted to `main` first; GitHub showed the slice diff only (+1355/-0, not the cumulative one) |
| #106 | `56bbf8d` | retargeted to `main` first; slice diff +1884/-0 |
| #107 | `19faa3e` | retargeted to `main` first; slice diff +191/-24 |

All four branches deleted (remote and local). Consequences checked after the fact:

- **Tree integrity:** the 28 files on `main` are byte-identical to the verified branch build
  (sha256 manifest comparison, no diffs). `main` = `origin/main` = `19faa3e`.
- **Gates on `main`:** lint clean, **1573 tests passed** (96 files), coverage
  91.04/73.44/86.18/91.04 all above thresholds, build with **0 `NG####` warnings**, and
  `api-gateway` `compileJava` OK (manual, since CI does not cover it).
- **CI on `main`:** Angular CI and API CI both `success` on the #107 merge. The #106 Angular CI run
  shows `cancelled`, which is the workflow's own `concurrency: cancel-in-progress` superseding it
  with the newer commit, not a failure.
- No open PRs remain and the local working tree is clean.

## Status

**Merged and verified on `main`.** The four chained PRs are in `main` (`19faa3e`) with delete-on-merge
branches cleaned up, and the post-merge state was re-verified (integrity + gates + CI). Local
checkout is `main`, clean, in sync with `origin/main`. RDD is `off`, so no native review lineage
applies to this candidate. Remaining follow-ups are the ones listed under "Surfaced at close".
