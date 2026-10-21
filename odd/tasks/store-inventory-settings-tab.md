# Store inventory settings move into the store edit screen

**Status**: implemented — the per-store inventory settings are the `Configuración` tab of
`/companies/stores/edit/:id`; the standalone route and the store-list entry point are gone.

Feature: `life-control-app-angular` — company stores. Worktree branch `feat/store-inventory-settings-tab`.

## Specs

S1. `/companies/stores/edit/:id` renders a tab bar with exactly two tabs, `Datos` first and
`Configuración` second; create mode (`/companies/stores/create`) renders the form with no tab bar.

S2. The store list is no longer an entry point: the `Configurar inventario` action on the store card
and the `/companies/store-inventory-settings` route are removed, so the tab is the only access.

S3. The `Configuración` tab hosts the inventory settings panel. It reads and writes the existing
`…/stores/{storeId}/inventory-settings` endpoint through the five-level chain derived from the store
being edited (`companyId`, `companyCountryId`, `regionId`, `zoneId`, `storeId`), and it never reads
the route or guesses a store: the page renders it only once the store is known.

S4. `?tab=configuracion` opens the second tab directly; a missing or unknown value falls back to
`Datos`. Changing tabs writes `?tab=` back with `queryParamsHandling: 'merge'` and
`replaceUrl: true`, so the create-mode parameters survive and Back leaves the screen instead of
walking the tabs.

S5. Switching tabs never discards state: the tab group keeps both bodies mounted
(`preserveContent`), so the store form's `FormGroup` and the panel's selections survive a visit to
the other tab.

S6. Unsaved panel selections still block navigation: the edit route carries the shared
`unsavedChangesGuard`, and the page reports the panel's dirty state.

S7. All the inventory-settings behavior the panel had as a page is preserved: 404 → empty form
ready to create, `version` sent only when a row was loaded, 409 → reload and clear the guard, 400 /
403 / 404 copy mapping, stale-location warnings, read-only principals never offered the save, and the
save notification `Configuración de inventario guardada.`

## Tasks

| ID | Spec | Route | Commit |
| --- | --- | --- | --- |
| T1 | S3 | move `pages/store-inventory-settings` → `components/store-inventory-settings-panel`, chain becomes a required input; spec adapted | `c9e1040` |
| T2 | S1, S4, S5, S6 | RED tests in `stores-edit.spec.ts` | this commit |
| T3 | S1, S2, S4, S5, S6 | implement the tab shell in `stores-edit`, wire the panel, `?tab=`, guard delegation | this commit |
| T4 | S2 | remove the card action, the page handler and the route; fix the barrels | `c9e1040` |
| T5 | S7 | gates: focused specs, full unit suite with coverage, lint, build | this commit |

## Log

L1 (user, verbatim): «quiero cambiar /companies/store-inventory-settings para que en lugar de que se
abra desde la lista de tiendas mejor que se edite desde la pantalla de edicion de la tienda
/companies/stores/edit/ agregando una pestaña de Configuración»

L2 (user decision, verbatim option label): «Eliminarla: la pestaña es el único acceso» — the old route
is deleted rather than kept alive without a list button.

L4 (gate evidence, T5): `npx ng test --no-watch --no-coverage --include='**/stores-edit.spec.ts'
--include='**/stores-page.spec.ts' --include='**/store-inventory-settings-panel.spec.ts'` → 3 files /
126 tests passed; `npm run test:coverage:check` → 184 files / 3689 tests passed, coverage
86.57 / 85.68 / 79.14 / 89.22, floors OK; `npm run lint` → all files pass; `npm run build` → bundle
generated with no error.

L3 (observation, T3): the tab bodies mount eagerly in Angular Material 22 with non-lazy content (the
same thing `product-edit` documents), so page specs must provide the panel's collaborators —
`StoreInventorySettingsService`, `StoreLocationLookupService` and `Keycloak`. Keeping them eager is
deliberate: it is one mechanism, and it lets the page answer the guard without the operator ever
visiting the tab.

L5 (slice boundary, T6): the work is three work-unit commits — `c9e1040` (292 authored lines: the
panel extraction and the removal of the old entry point), `43a11c8` (~454: the tab shell, its tests
and this record) and this one (the component guide, which documented the removed route in four
places). The middle one exceeds the ~400-line advisory on purpose, because the tab and the removal
it depends on are one behavior; a chained delivery would use one slice per commit.

L6 (docs, T5): `life-control-app-angular/AGENTS.md` described `/companies/store-inventory-settings` as
a leaf route gated at `STORE_ROLES`, listed it among the `canWrite` files and used it as the
read+write-form precedent. All three are false after the move; the guide now names the tab, the
required `chain` input, `?tab=` and `preserveContent`, and records why a page spec must provide the
panel's collaborators. Prettier reports that file unformatted on `origin/main` too, so it is
hand-maintained and was left that way.
