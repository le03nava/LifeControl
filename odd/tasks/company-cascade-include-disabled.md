# "Mostrar deshabilitadas" reaches the API on the company cascade

**Repository**: LifeControl — component `life-control-app-angular/`, issue **#509**
**Status**: implemented — the three leaf toggles (`/companies/zones`, `/companies/regions`, `/companies/stores`) send `includeDisabled` from the resource params, the filter levels of the store cascade request disabled ancestors, and each level is pinned by a test. Nothing is deferred.
**Created**: 2026-10-21 · **Risk**: medium — `includeDisabled` decides which rows the API returns, so a wrong value either hides a disabled row or lets one render while the toggle is off

## Specs

Verbatim from issue **#509** (`bug`).

**S1** — "`/companies/zones`: turning "Mostrar deshabilitadas" on issues `GET /api/companies/{id}/countries/{id}/regions/{id}/zones?includeDisabled=true`; turning it off issues `includeDisabled=false`."

**S2** — "`/companies/regions`: same two requests against `/regions`, with the same two outcomes."

**S3** — "`/companies/stores`: turning the toggle on re-issues `GET …/stores?includeDisabled=true` and the disabled store appears in the grid; turning it off re-issues `includeDisabled=false` and it disappears."

**S4** — "Deep link `…/companies/stores?companyId=…&countryId=…&regionId=…&zoneId=<disabled zone>` resolves `selectedZone` and loads its stores (today the zone selector never receives the disabled row and the cascade stays unresolved)."

**S5** — "The case that must keep working: with the toggle off, only enabled rows render on all three pages, and the create / edit / disable / re-enable actions keep their current behaviour."

**S6** — "`npx ng test --no-watch --coverage=false` passes, and the specs' service mocks honour `includeDisabled` instead of returning every row — that mock was what hid the defect."

**S7** — "A test that fails today is added per page, observing RED first."

**S8** — Root cause, from issue **#509**: "Angular runs the stream function inside `untracked(...)` (`@angular/core/fesm2022/_resource-chunk.mjs`, `ResourceImpl.loadEffect`), so that read creates no dependency: toggling never re-triggers the request." Only `params` reads are tracked.

## Tasks

| ID | Spec | Route | Commit |
| --- | --- | --- | --- |
| T1 | S1, S5, S6, S7, S8 | Inline — `zones-page` params + spec mock honouring the flag | `f6eb690` |
| T2 | S2, S5, S6, S7, S8 | Inline — `regions-page` params + spec mock | `f6eb690` |
| T3 | S3, S5, S6, S7, S8 | Inline — `stores-page` leaf params + spec mock | `367d61a` |
| T4 | S4, S5, S7 | Inline — filter levels request disabled ancestors (`stores-page` regions/zones, `StoreCascadePage` regions/company zones) + deep-link test | `367d61a` |
| T5 | S6 | Inline — gate: full `ng test` suite (3681 tests), `eslint`, `prettier --check` | `f6eb690`..`367d61a` |

## Log

**L1** — User request, verbatim: "puedes revisar /companies/zones cuando filtro con el mostrar deshabilitadas en el requeste siempre manda includeDisabled=false".

**L2** — Scope decision by the user, verbatim: "si incluyelos" — asked to include `companies/regions/pages/regions-page` and `companies/stores/pages/stores-page`, which carried the same defect.

**L3** — Scope decision by the user, verbatim: "si incluyelo" — asked to include the ancestor selectors that never request disabled rows (`stores-page` zone selector, `StoreCascadePage:147`). The region level of the same two resources was included with them: it is the same defect two lines above the zone one, and the same deep link fails without it.

**L4** — Evidence, 2026-10-21. RED observed before each fix: zones `expected last "vi.fn()" call to have been called with [ 'company-1', 'cc-1', 'reg-1', false ]` (received three arguments); regions the same for `getRegions`; stores `expected 1 to be 2` on the toggle and `expected undefined to be 'zone-2'` on the deep link; the shared base the same two request assertions. GREEN: `companies/{stores,zones,regions}` 32 spec files / 985 tests; full suite 184 files / 3681 tests, exit 0; `prettier --check` and `eslint` clean on every changed file.

**L5** — Differential, 2026-10-21: reverting the two hardcoded `true` arguments of `stores-page.ts` (and restoring them from a copy) turns the deep-link test red with `expected undefined to be 'zone-2'`, which proves the test pins the behaviour instead of the mock. An earlier mock default of `includeDisabled = true` passed without the fix; it was corrected to the service's real default `false`.
