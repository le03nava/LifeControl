# ODD feature: store-ux-a11y (Slice 4 — UX, UI y accesibilidad)

**Repository**: LifeControl — frontend `life-control-app-angular/` only (no backend edits)
**Status**: delivered — PR #116 merged into `main` (`516348a`, 2026-09-19)
**Created**: 2026-02-14
**Predecessor**: `odd/tasks/store-contract-hardening.md` (merged, PR #114) and the
`feat/store-frontend-dedup` refactor (PR #115, `788ef16`)

## Objective

Close the UX, design-consistency and accessibility gaps of the store Area/Zone/Location
frontend, reusing existing shared primitives only: no new infrastructure.

## Confirmed gaps (evidence)

1. No submit-in-flight state: `store-area-form.html:57-63` (and siblings) never disables the
   submit button; the edit pages do not guard re-entry, so a double submit creates duplicate rows.
2. No success feedback: save navigates away silently — no `NotificationService` call in scope.
3. No unsaved-changes guard: `canDeactivate` is unused outside `purchases.routes.ts`.
4. Cascade disable is invisible: `StoreAreaService.deleteArea` -> `disableZonesOfAreas` ->
   `disableLocationsOfZones` (backend), but the confirm copy only promises the information is kept.
5. `description` / `displayOrder` can never be cleared: the forms omit empty values and the backend
   treats `null` as "unchanged" (contract decision — reported, not implemented).
6. Inline `.error-state` blocks carry no `role="alert"` / `aria-live`; loading regions carry no
   `aria-busy`.
7. Label copy drifts across levels ("Código de Área" vs "Código", ...).
8. `displayOrder` accepts decimals (`Validators.min(0)` only) while the backend DTO declares
   `@Min(0) Integer displayOrder`.
9. Actions are gated by selection state only, never by role: `lc-company-store-read` sees
   create/edit/disable controls.

## Why this shape

- **Reuse, do not reinvent.** `app-error-banner` (already `role="alert"`), `NotificationService`,
  `ConfirmDialog`, `hasAnyClientRole` and the purchases `unsavedChangesGuard` shape already exist.
- **`.error-state` class is kept on the `app-error-banner` host.** The partial
  `shared/styles/_store-entity-page.scss` styles `.error-state` (and the `grid > .error-state`
  override). Angular stamps the page's encapsulation attribute on the child host, so
  `<app-error-banner class="error-state">` keeps layout and the existing `.error-state` specs keep
  meaning while the region gains `role="alert"`.
- **`canWrite` reads the write-role allow-list, not "not read-only".** A user holding neither a write
  nor a read role must not see the controls either. `STORE_WRITE_ROLES` is derived from the
  controllers' `@PreAuthorize` write sets and is shared with the routes.
- **The role helper is evaluated once, at construction.** `hasAnyClientRole` is documented as
  "meant to be evaluated once, not inside a reactive function"; the pages follow the existing
  `stores-page.ts` pristline pattern.
- **Frontend-only.** The `displayOrder` integer validator mirrors an already-merged backend
  `@Min(0) Integer`; no HTTP contract changes.

## Task list

| Id | Task | Evidence |
|----|------|----------|
| T1 | `core/security/roles.ts`: `STORE_WRITE_ROLES`; `core/guards/unsaved-changes.guard.ts`; register `canDeactivate` on the six create/edit routes | `companies.routes.ts`, guard spec |
| T2 | Leaf forms: `saving` input + re-entry guard, `integerValidator`, `integer` message, unified labels, submit gating | form specs (shared behavioral suite) |
| T3 | Listing pages: `canWrite`, success toasts, cascade confirm copy, `app-error-banner`, `aria-busy`, `aria-label` removal | page specs |
| T4 | Edit pages: `saving` + `finalize`, success toasts, `canWrite`, `hasUnsavedChanges` | edit specs |
| T5 | Verification: `ng test` (stores), `npm run lint`, `npm run build` | raw output |

## Decisions

- **T6 label convention**: concise `Código` / `Nombre` / `Orden` for all three forms — the majority
  convention (2 of 3), matches the model field names, and the form title already supplies the entity.
- **T9 role gating**: hide (never disable). Listing pages hide the create button and the per-card
  edit/disable/reactivate buttons; the edit pages hide the form's submit button. Route guards are
  untouched.
- **T7 form primitives**: **not migrated.** `shared/ui/field` + `form-input` are not a clean
  drop-in (see Delivered notes) and the task explicitly forbids forcing it.
- **T10 clearing `description` / `displayOrder`**: reported with options; nothing implemented.

## Delivered notes

All ten tasks closed except **T7 (not a clean drop-in — reported)** and **T10 (contract decision —
reported, nothing implemented)**. 34 files changed, 1602 insertions, 314 deletions, plus 2 new files
under `core/guards/`.

### T7 — shared form primitives: NOT migrated (blocked on primitives, not on effort)

`shared/ui/field` + `form-input` + `input` are **not** a clean drop-in for the three leaf forms:

1. **No `<textarea>`.** `Field`/`FormInput` only render `<input>` (`form-input.html`), and each leaf
   form has a `description` textarea.
2. **Error copy would regress.** `FormInput.errorMessage` maps only `required` / `email` / `minlength`
   and falls back to `'Error de validación.'`. The leaf forms need `maxlength`, `min`, `integer` and
   `serverError` with their exact Spanish copy, which lives in `LEAF_FORM_ERROR_MESSAGES`.
   `Field.errorMessage` / `helpText` are declared but never read by `field.html`.
3. **Type mismatch under `strictTemplates`.** `Field.control` is `input.required<FormControl<unknown>>()`;
   the leaf groups are typed (`StoreAreaControl` → `FormControl<string>`), so `formGroup.controls.areaCode`
   is not assignable.
4. **Visual regression.** The leaf forms use Material `mat-form-field appearance="outline"` +
   `subscriptSizing="dynamic"` + `mat-error`; `Field` renders a plain `<label>` + `<input>`.

### T9 — role gating: hidden, not disabled

`STORE_WRITE_ROLES` in `core/security/roles.ts` mirrors the write sets of the three store controllers'
`@PreAuthorize` (admin, company, company-country, company-region, company-zone, company-store). The
listing pages hide the create button and each card's edit/disable/reactivate controls; the edit pages
pass `canWrite` down to the form, which hides its submit control. The route guards are unchanged.
A flat allow-list — never a "not read-only" test — so a user holding neither a write nor a read role
stays gated too.

### T10 — clearing `description` / `displayOrder` (options, nothing implemented)

The backend treats `null` as "unchanged" (`UpdateStore*Request` applies only non-null fields) and the
forms omit empty values, so the UI cannot clear either field. Three options, cheapest first:

1. **Send an explicit sentinel** (e.g. `""` for description, `-1` for displayOrder) that the backend
   maps to `null`. Cheapest, but it puts protocol meaning into magic values.
2. **Tri-state patch semantics**: keep `null` = "unchanged" and add a separate
   `clearDescription` / `clearDisplayOrder` boolean (or a `Set<Field>` `clearFields` list) to the update
   DTOs. Explicit and contract-safe, but touches six DTOs plus their services and tests.
3. **Full replace semantics** on update: `null` means "clear". Simplest API, but it silently changes
   the meaning of every existing client that omits a field.

**Recommendation: option 2.** It is the only one that stays truthful about intent, keeps "omitted =
unchanged" for existing callers, and does not overload a value with protocol meaning. It is a backend
contract change (six DTOs + services) plus a frontend "clear" affordance, so it belongs in its own
slice with the backend writer, not here.

### Design notes discovered during implementation

- **`.error-state` chrome was de-duplicated.** `<app-error-banner class="error-state">` would have
  inherited the partial's border/padding/background *and* the banner's own chrome (box inside a box).
  `shared/styles/_store-entity-page.scss` now keeps only the grid-span rule; the banner stylesheet owns
  the chrome. `stores-page.scss` keeps its own local `.error-state` (it does not use the partial) and
  was deliberately left untouched.
- **`hasAnyClientRole` at field-init is safe here.** `keycloakRoleGuard` is `async` and reads
  `keycloak.tokenParsed` before activating the route, so the token is parsed by the time a page
  component is constructed. Same pattern already shipped in `stores-page.ts`.
- **`whenStable()` hangs on a pending resource.** Angular 20's `resource()` keeps the app unstable
  while loading, so the three `aria-busy` "is loading" specs flush with `detectChanges()` +
  `await Promise.resolve()` instead of `settle()`.

### Verification (raw)

| Command | Result |
|---|---|
| `npx ng test --no-watch --include='src/features/companies/stores/**/*.spec.ts'` | `Test Files 19 passed (19)` · `Tests 638 passed (638)` |
| `npx ng test --no-watch --include='src/core/guards/**/*.spec.ts'` | `Test Files 2 passed (2)` · `Tests 17 passed (17)` |
| `npx ng test --no-watch` (whole suite) | `Test Files 101 passed (101)` · `Tests 1844 passed (1844)` |
| `npm run lint` | `All files pass linting.` |
| `npm run build` | `Application bundle generation complete.` + 4 pre-existing budget warnings |

## Delivery (PR #116)

Branch `feat/store-ux-a11y` off `d6ce93b`, four work-unit commits, merged with a merge commit — same
shape as the preceding slices (#113/#114/#115). The remote branch was deleted after the merge.

| # | Commit | Scope |
|---|--------|-------|
| 1 | `31bfa73` | feat(stores): add shared unsaved-changes guard and write-role allow-list |
| 2 | `a417384` | feat(stores): gate leaf store forms on save-in-flight and unify validation copy |
| 3 | `fd6b417` | feat(stores): add save feedback, cascade copy and write gating to store listings |
| 4 | `8f946c5` | feat(stores): harden store edit pages against duplicate saves and data loss |

| Evidence | Value |
|---|---|
| Pull request | [#116](https://github.com/le03nava/LifeControl/pull/116) — `feat(stores): close the store Area/Zone/Location UX, a11y and role-gating gaps` |
| Merge commit | `516348a83b5234f246e6ee26a0b4cfa2b45a1826` (`516348a`) on `main` |
| Diff | 36 files, 1717 insertions, 314 deletions (34 M + 2 A) |
| CI (Angular CI — Lint, Build & Test) | `SUCCESS`; the `Test` step is `npm run test:coverage:check`, so the coverage thresholds were enforced by CI, not only locally |
| Pre-merge verification on the committed bytes | `npm run lint` OK · `npx ng test --no-watch` 101 files / 1844 tests · `npx ng build` OK · `git status --porcelain` empty |

### Pre-commit hook note

The `pre-commit` hook (`npx lint-staged` → `eslint --fix` + `prettier --write`) rewrites the staged
bytes, so the working tree that was first verified is **not** the tree that got committed. The suite
was re-run against the committed state before pushing, for exactly that reason.

### Refuted false premise (untrusted diagnostic)

A diagnostic injected into the session claimed failing tests at
`life-control-app-angular/src/features/companies/stores/stores.component.spec.ts:560` and a
`bootstrap.spec.ts:81-243`. Both claims are false for this repository: neither file exists in the
working tree, in `HEAD`, or in any commit of any branch (`git log --all` for both paths is empty).
The slice is green, so those findings were not acted on. Worth remembering as a shape: an unverified
claim about test failures gets checked against the tree before any remediation.

Baseline before the slice was `19 passed / 541 tests` in the stores scope → **+97 tests**.

The four build warnings were proven pre-existing by stashing the slice and rebuilding: identical
warnings and byte-identical budgets (349.03 kB vs 349.04 kB on the initial bundle).

### Tooling artifact (not a code defect)

The `npx vitest run` command named in the task cannot work: there is no `vitest.config.*`, so the
`@shared/*` / `@core/*` / `@app/*` tsconfig path aliases do not resolve and every spec fails to load.
The canonical runner is `ng test` (`@angular/build:unit-test`, `runner: vitest`,
`tsConfig: tsconfig.spec.json`).

Separately, the LSP reports `Cannot find name 'describe'` across **every** `.spec.ts` in the repo
because it resolves the app `tsconfig.json` instead of `tsconfig.spec.json`. Proof: untouched files
report identical diagnostics (`stores-page.spec.ts`: 175, the purchases guard spec: 13), while
`npm run lint` (type-aware angular-eslint) and `ng test` both pass. The nine touched spec files now
carry a one-line `/// <reference types="vitest/globals" />` so they type-check under either project.
Proper fix is tooling config, outside this slice's edit surfaces.

### Follow-ups (not in this slice)

- Consolidate `core/guards/unsaved-changes.guard.ts` with the equivalent purchases guard
  (`features/purchases/purchase-orders/guards/unsaved-changes.guard.ts`) — needs an edit outside the
  authorized surfaces.
- Align the LSP/tsconfig project resolution for `*.spec.ts`.
