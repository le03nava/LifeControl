# ODD feature: product-create-ux

**Repository**: LifeControl — frontend `life-control-app-angular/`. S1–S3 are frontend-only; only the
bulk-creation item in `## Backend dependencies` needs `life-control-api/` work, and it gates S4.
**Status**: **S1 delivered, uncommitted to a PR** (started 2026-09-23). Branch `feat/product-create-ux`,
worktree `~/workspace/LifeControl-worktrees/feat-product-create-ux`, base `main` @ `80f44c3` (clean at
branch time). S1 is four work-unit commits plus the gate evidence below; S2–S4 are not started and no
PR is open. Shape agreed with the user on 2026-09-23 ("Tabs + stepper con skip", "propuesta primero";
voseo adopted as the copy register). Product `attributes` handling and the `Activo` toggle were
descoped the same day (see `## Descoped by user decision`).
**Created**: 2026-09-23
**Risk**: **medium** — route and UI restructure over four existing pages. No auth, role-set or guard
*set* change: the `unsavedChangesGuard` addition only tightens navigation on two routes that already
exist. Reclassified to **high** only if the backend slices in `## Backend dependencies` are taken in
the same branch, since those change persisted data semantics.

## Objective

Make creating a product and associating its suppliers and variants a single, guided, recoverable
flow, instead of today's four disconnected route trees where the next step after "Guardar" is
invisible.

## Confirmed gaps (evidence)

Verified by reading the code; every line reference was checked, not inferred.

1. **The create flow dead-ends.** After `POST /api/products` succeeds, `product-edit.ts:99`
   navigates to `/products/edit/:id` and stops. That page offers exactly one affordance — a lone
   `Manage Suppliers` button rendered only in edit mode (`product-edit.html:12-18`).
2. **Variants are unreachable from the product.** `product-edit.html` has no variants entry point at
   all. The only way in is a row action on the products list (`product-list.ts:117`), so the user has
   to know that a list page carries the action.
3. **Every association costs a full page round trip.** Product → supplier list → `suppliers/create` →
   back (`product-supplier-edit.ts:189,197`). Five suppliers means five route cycles with no product
   context retained.
4. **The supplier picker does not scale.** `product-supplier-edit.ts:77` loads
   `getAllSuppliers(0, 1000)` into a plain `mat-select`. No server-side search, an unbounded payload
   per dialog open.
5. **Duplicate SKU never reaches the SKU field.** The 409 body carries `message` only, with no
   `errors` map, so `product-edit.ts:125-127` routes it to the general banner instead of the control
   the user is editing.
6. **No unsaved-changes protection on the product routes.** `canDeactivate: [unsavedChangesGuard]`
   is registered on the variant routes (`products.routes.ts:57,67`) but not on `create`/`edit`
   (`:31-46`). A half-filled new product is discarded by any stray navigation.
7. **Mixed language in one feature.** `products-form.html` is Spanish, `product-supplier-form.html`
    and its `defaultErrorMessages` (`product-supplier-form.ts:43-47`) are English, and the newer
    variant/store surfaces use Rioplatense voseo (`product-variant-form.ts:59-60`: "No podés superar
    los N caracteres"; `product-variant-list.html:44,47`). Three registers coexist.
8. **Form register drift inside one file pair.** `products-form.ts:33` says "Este campo es
    obligatorio." while `product-variant-form.ts:58` says the same thing in neutral Spanish and
    `:59-60` in voseo. No rule is written down anywhere.

## Target experience

### Product workspace (S2)

`/products/edit/:id` stops being "a form plus a stray button" and becomes the product's workspace:
three tabs, each deep-linkable, each with a live count.

```text
┌ Producto: Zapatilla Runner  (SKU: ZAP-001) ──────────────────┐
│ [ Datos ]  [ Proveedores (2) ]  [ Variantes (5) ]            │
├──────────────────────────────────────────────────────────────┤
│  …tab content…                                               │
└──────────────────────────────────────────────────────────────┘
```

- `Datos` reuses the existing `app-products-form` unchanged.
- `Proveedores` and `Variantes` embed the existing list surfaces as presentational components.
- Association create/edit happens in a **dialog**, not a page navigation. The supplier and variant
  edit pages become dialog hosts over the same form components, so the forms themselves are not
  rewritten.
- Tab state is a query parameter (`?tab=proveedores`) so a link is shareable and the existing
  back-button behaviour stays predictable. `suppliers`, `variants` and their `create`/`edit` child
  routes stay registered and redirect into the workspace tab, so no existing URL 404s.

### Create stepper (S3)

`/products/create` becomes a three-step stepper. Steps 2 and 3 are skippable.

```text
 ① Datos ─── ② Proveedores ─── ③ Variantes
    ↓              ↓                ↓
 POST /products  POST …/suppliers  POST …/variants
```

**This is a committed wizard, and that is deliberate.** The backend nests both associations under
`/api/products/{productId}/…` and validates the product's existence first
(`ProductSupplierService.java:64-65`, `ProductVariantService.java:135-136`), and there is no batch
endpoint for either. So step 1 must persist the product. The alternative — collect everything in
memory and POST at the end — needs a manual rollback with no transactional endpoint and loses the
whole entry if the last request fails. Rejected.

Consequences the design must carry:

- After step 1 the product exists. "Cancelar" from step 2 onward cannot delete it; the copy must say
  so, and the exit affordance becomes "Terminar", not "Cancelar".
- Each association is an independent request. A failure part-way (product created, variant 3 of 5
  failed) leaves a real, listed product with the successful associations stored. The UI reports that
  state item by item and offers a retry of only the failed item. Nothing is rolled back, and nothing
  is lost.
- `unsavedChangesGuard` (added in S1) covers the pre-persist part of step 1.

## Why this shape

- **The product, its suppliers and its variants are one object in the user's head and three route
  trees in the code.** Tabs close that gap; a stepper closes the cold-start gap. Both reuse the
  presentational components that already exist.
- **Reuse over invention.** There is no `MatStepper` anywhere in the repo (grep for
  `mat-stepper|CdkStepper` returns nothing), so the stepper is new; everything else is not. The
  debounced supplier search already exists at
  `features/purchases/purchase-orders/components/supplier-info-section/supplier-info-section.ts`
  (`debounceTime(300)`, `MatAutocompleteModule`). The step-ladder idea exists at
  `features/companies/stores/pages/store-cascade-page.ts` (`CascadeStep`, `resetAbove()`).
- **S1 is deliberately separable.** The two defects it fixes (gaps 5–6) plus the copy items
  (gaps 7–8) and the discoverability fix (gaps 1–2) are worth shipping on their own, and they are
  small. The workspace and the stepper are the expensive part and can wait.
- **`mat-dialog` over a side sheet for associations.** The repo already uses `MatDialog` for
  `delete-product-dialog` and `disable-variant-dialog`, so no new layout primitive is introduced.

## Decisions

### Locked with the user (2026-09-23)

- D1: tabs workspace **plus** create stepper with skip. Not the stepper alone, not the workspace
  alone.
- D2: proposal document first, implementation after.
- D3: **form register — Rioplatense voseo.** Confirmed by the user on 2026-09-23. It is the register
  the newest surfaces already use (`product-variant-form.ts:59-60`, `product-variant-list.html:44,47`)
  and the one the user writes in. Older neutral copy is migrated slice by slice, not in one sweep: a
  repo-wide copy sweep would inflate S1 for no functional gain.
- D4 (S1, confirmed by the user on 2026-09-23): **the `product-edit.html` action block goes fully to
  voseo.** T2 adds the Variantes entry point and renames the existing `Manage Suppliers` to
  `Administrar proveedores` in the same edit. Rationale: shipping a new Spanish button beside an
  English one is gap 7 reproduced on the very page S1 touches, and it costs two lines. The block is
  `product-edit.html:12-19`; the variant/supplier list pages keep their own copy untouched.
- D5 (S1, engineering decision, 2026-09-23): **the 409 → SKU mapping is message-discriminated, not
  status-discriminated.** `GlobalExceptionHandler` answers 409 from two different sources: an explicit
  duplicate check (`DuplicateProductException` → `"Product with SKU 'X' already exists"`) and the
  uncaught `DataIntegrityViolationException` path (`"The operation conflicts with an existing resource
  or violates a data constraint"`). Mapping every 409 to the `sku` control would mark the wrong field
  for the second class. T3 therefore maps the conflict onto `sku` only when the payload identifies a
  SKU conflict, and keeps the general banner otherwise. The inline message is voseo copy
  (`Ya existe un producto con ese SKU.`), not the English server string, for register consistency.

## Descoped by user decision

### Product `attributes` — out of scope (user decision, 2026-09-23)

The JSON textarea, its missing validation and the backend merge semantics are **excluded from this
feature**. The user's instruction: *"el tema del json dejalo fuera, eso va a cambiar en un
futuro"*. The attributes model is scheduled for a redesign, so fixing its current behaviour would be
buying a rewrite. Recorded here so the finding is not lost:

- `products-form.ts:104-110` discards invalid JSON silently: on `JSON.parse` failure
  `parsedAttributes` stays `undefined` and the form submits anyway. The
  `json: () => 'Ingrese un JSON válido.'` message at `products-form.ts:39` is declared and no
  validator is registered (`product-edit.ts:78-89` and `:58-69` add none).
- Attributes cannot be cleared or have a key removed from the UI. An empty textarea sends
  `undefined`, which JSON-serialisation drops, so the backend receives `null` = "preserve"
  (`ProductService.java:75,85`). Non-empty values are shallow-merged with `putAll`
  (`ProductService.java:79-82`), so a key deleted in the textarea reappears on the next reload.
  The UI implies full replace; the contract is merge-or-preserve.
- Nothing in S1–S4 touches `attributes`, its control, its validator or its wire format.

### Product `Activo` toggle — out of scope (user decision, 2026-09-23)

The user's instruction, verbatim (2026-09-23): *"D4 deja fuera los cambios del toogle"*. `D4` was the
id of the decision this document no longer carries. The toggle stays exactly as it is in both
modes; no task, no decision and no backend dependency in this feature touches it. Recorded here so
the finding is not lost:

- The toggle is decorative in both modes. `products-form.html:69-71` always renders it and
  `products-form.ts:121` always sends `enabled`, but `ProductRequest` declares no `enabled` field,
  `createProduct` hardcodes `.enabled(true)` (`ProductService.java:45`) and `updateProduct` never
  sets it (`:66-70`). The only product disable path is `DELETE /api/products/{id}` (soft delete), so
  the control writes nothing while telling the user it did.
- The prerequisite the toggle work would have needed is also out: there is no
  `PATCH /api/products/{id}/enable`, and asking for one is not part of this feature. If it ever
  lands, the enable/disable UX deserves its own decision rather than being re-attached to this
  document.
- `products-form` is therefore untouched by S1–S4, except that S2 renders it unchanged inside the
  `Datos` tab.

## Non-goals

- No new backend endpoint unless listed in `## Backend dependencies` and separately approved.
- No change to `attributes` handling: not the JSON textarea, not its validation, not the backend
  merge semantics. Descoped above, awaiting the future attributes redesign.
- No change to the `Activo` toggle, and no product enable/disable endpoint: both descoped above.
- No change to the products list, the variant store-stock screen, or the purchases/sales pickers.
- No attributes on variants: the variant model has no attribute field, and adding one is a schema
  change outside this feature.
- No role or route-gate change: the product ABM stays admin-only.
- No redesign of the shared page-header, table or card primitives.

## Task list

| Slice | Id | Task | Evidence |
|-------|----|------|----------|
| **S1** | T1 | Register `canDeactivate: [unsavedChangesGuard]` on `create` and `edit/:id`; implement `hasUnsavedChanges` on `ProductEdit` | guard spec + `products.routes.spec.ts` |
| **S1** | T2 | Add a **Variantes** entry point on `product-edit.html` beside the suppliers button, so the dead end closes before the workspace exists (D4: both labels in voseo) | `product-edit.spec.ts` |
| **S1** | T3 | Map a 409 with no `errors` map (duplicate SKU) onto the `sku` control instead of the general banner (D5) | `product-edit.spec.ts` |
| **S1** | T4 | Translate `product-supplier-form` (labels + `defaultErrorMessages`) to Rioplatense voseo (D3) | new `product-supplier-form.spec.ts` |
| **S2** | T5 | `ProductEdit` becomes a tabbed workspace with deep-linkable `?tab=` state and counts | `product-edit.spec.ts` |
| **S2** | T6 | Extract the supplier and variant list pages into presentational components reused by the tabs; keep the pages as thin route hosts | list specs |
| **S2** | T7 | Move supplier and variant create/edit into `MatDialog` hosts over the existing form components | form + dialog specs |
| **S2** | T8 | Replace `getAllSuppliers(0, 1000)` with the existing debounced autocomplete pattern | picker spec |
| **S2** | T9 | Per-tab loading, error, empty and count states; old `suppliers`/`variants` routes redirect into the tab | route spec |
| **S3** | T10 | Three-step create stepper, skip on steps 2 and 3, `POST /products` at the end of step 1 | stepper spec |
| **S3** | T11 | Partial-failure UX: per-item status, "created with N of M", retry only the failed item, no rollback; post-persist exit is "Terminar" | stepper spec |
| **S3** | T12 | Pre-persist leave confirmation and post-persist exit semantics wired to the guard | guard spec |
| **S4** | T13 | Variant form: "Guardar y agregar otra" (no backend change) | form spec |
| **S4** | T14 | Variant matrix generation — **blocked** on B1, and on the future attributes redesign | stepper/workspace spec |
| **S4** | T15 | Embed per-store stock and prices in the Variantes tab | tab spec |

## Backend dependencies

Each item is a separate decision. None is in scope until approved.

- **B1 — bulk variant creation.** No batch mapping and no matrix expansion anywhere in the product
  package. Needed for T14; without it T13 is the honest ceiling.
- **B2 — supplier-link metadata.** The link carries only `purchaseCost`, `main`, `enabled`. No
  `currency`, `leadTime` or `supplierSku`. If any is wanted, it is a schema change.

## Verification plan

Per slice: `npm run lint`, `npm run build`, `npm test` in `life-control-app-angular/`. Because
`angular-ci.yml` runs `test:coverage:check` on PRs touching the package, run
`npm run test:coverage:check` too and treat a threshold failure as a blocker, not a warning.

Existing specs that will need updating, not just passing: `product-edit.spec.ts` (243 lines) and
`products.routes.spec.ts`. `products-form.spec.ts` (187) must keep passing **unchanged** — that it
still passes is the signal that neither the descoped attributes work nor the descoped toggle work
leaked into S1.

**Correction (2026-09-23, verified by read-only exploration).** The plan above assumed
`product-supplier-form` specs already existed. They do not: the component ships `.ts`, `.html` and
`.scss` and **no** `.spec.ts`, and `product-supplier-edit.spec.ts` covers the page, not the form's
message map. `products.routes.spec.ts` also does not assert `canDeactivate` on any route today, so it
pinned nothing about T1. Both gaps are closed by this slice: T4 creates a focused
`product-supplier-form.spec.ts`, and T1 adds the missing route assertions.

**Vacuous-assertion rule carried in from `product-variant-admin-ui.md`:** never assert a
possibly-undefined route property with `toContain` (it passes when the property is `undefined`).
T1's assertions use `toEqual([...])`.

Manual checks that automation will not cover: tab state survives reload and back-navigation; the old
`suppliers`/`variants` URLs still resolve; the stepper's partial-failure path is exercised by
cancelling a request mid-step.

## Review workload

Forecast before implementation, to be corrected with the measured range the way
`product-variant-admin-ui.md` does it (there, a declared 500–800 became a measured 3490):

| Slice | Files | Forecast changed lines | Concern |
|-------|-------|------------------------|---------|
| S1 | 3–5 | 100–250 | Two defects + copy unification to voseo + the variants entry point |
| S2 | 8–12 | 600–900 | Route restructure, component extraction, dialogs |
| S3 | 4–6 | 400–600 | New stepper + partial-failure semantics |
| S4 | 4–8 | 400–700 | Blocked on B1 for T14 |

S1 is one reviewable PR. S2 and S3 exceed a comfortable review and should ship as chained PRs against
the same feature branch, not as one.

### S1 — measured, and the forecast was low

Measured with `git diff main...HEAD --stat` on `b885578`: **9 files, 572 insertions / 19 deletions
(591 changed lines)** against a forecast of 3–5 files and 100–250 lines. The reason is that the
forecast counted the functional diff and the slice carries its evidence with it:

| Bucket | Files | Changed lines |
|---|---|---|
| Source (routes, page, form copy) | 6 | **82** |
| Specs | 3 | **509** |
| **Total** | **9** | **591** |

86% of the diff is test code, and the largest single file is the new
`product-supplier-form.spec.ts` (250 lines) that T4 required because the component had no spec at all.
The functional change a reviewer must judge is 82 lines. **Carry this forward for S2–S4: the forecasts
there are source-line forecasts too, and they should be restated as source + evidence.**

## Evidence log

| Date | Slice | Commit | Evidence |
|---|---|---|---|
| 2026-09-23 | proposal | — | Read-only exploration of the four product pages, the routes and the backend contract. Every gap in `## Confirmed gaps` was checked against the code; `attributes` and the `Activo` toggle were descoped by the user. |
| 2026-09-23 | S1 setup | — | Worktree `~/workspace/LifeControl-worktrees/feat-product-create-ux` from clean `main` @ `80f44c3`; `npm ci --legacy-peer-deps --prefer-offline` → exit 0. Read-only mapping of the S1 surface (routes, guard, specs, form copy, test infrastructure). No source written. S1 scope decisions D4 and D5 recorded above; the two verification-plan gaps (no `product-supplier-form.spec.ts`, no `canDeactivate` assertion in `products.routes.spec.ts`) confirmed. |

Rows below are added as each task closes, with the commit that carries it.

| Date | Slice | Commit | Evidence |
|---|---|---|---|
| 2026-09-23 | S1 T1 | `b20c56c` | `npx ng test --no-watch --no-code-coverage --include "src/features/products/**/*.spec.ts"` → 21 files / 246 tests / 0 failures (baseline 21/239, +7). eslint + prettier clean on the four files. The RED run aborted at compile time (`TS2339: Property 'hasUnsavedChanges' does not exist`), so the route assertions were never observed failing; they were falsified by mutation instead (next row). |
| 2026-09-23 | S1 T1 control | `b20c56c` | Mutation: `canDeactivate: [unsavedChangesGuard]` deleted from the `create` route, then `--include ".../products.routes.spec.ts"` → **1 failed | 6 passed (7)**, `AssertionError: expected undefined to deeply equal [ [Function unsavedChangesGuard] ]`. Source restored byte for byte (`git checkout`, `git diff` empty, spec back to 7/7). The assertion is not vacuous. |
| 2026-09-23 | S1 T2 | `1fd9bb7` | Focused products run → 21 files / 250 tests / 0 failures (+4). RED observed: 3 failed | 17 passed (20) with `expected [] to have a length of 1 but got +0` and `expected undefined to be defined` on both click paths. DOM assertions pin the rendered labels. |
| 2026-09-23 | S1 T3 | `aea27d4` | Focused products run → 21 files / 255 tests / 0 failures (+5). RED observed: 3 failed | 252 passed (255) with `expected {} to deeply equal { sku: 'Ya existe un producto con ese SKU.' }` on create and update, plus `expected undefined to be 'Ya existe un producto con ese SKU.'` on the field-reach case. |
| 2026-09-23 | S1 T4 | `b885578` | Focused products run → **22 files / 275 tests / 0 failures** (+1 file, +20 tests). RED observed: 9 failed | 11 passed (20), each failure the exact English→voseo string diff. eslint + prettier clean. |
| 2026-09-23 | **S1 gates (baseline)** | `main` @ `80f44c3` | Measured on the anchor worktree, unchanged `main`: `npm run test:coverage:check` → **123 files / 2279 tests / 0 failures**, coverage **93.06/75.38/87.85/93.06**. |
| 2026-09-23 | **S1 gates (tip)** | `b885578` | `npm run lint` → `All files pass linting.`; `npm run build` → bundle generation complete, 850.86 kB initial, no errors; `npm run test:coverage:check` → **124 files / 2315 tests / 0 failures**, coverage **93.37/75.60/88.36/93.37** (thresholds 80/60/75/80, read from `scripts/check-coverage.mjs`). Delta over the baseline: +1 file, +36 tests, +0.31/+0.22/+0.51/+0.31. |
| 2026-09-23 | S1 independent verification | `65e9ffc` (pre-amend tip) | A read-only `gentle-ai-verify` subagent ran the three gates (all PASS) and adversarially checked six claims: guard wiring, the 409 discrimination, copy-only for T4, test non-vacuity, no descope leak (`git diff main...HEAD -- components/products-form/` empty) and no path/role/`canActivate` change. All upheld; two nits were raised — a non-discriminating assertion in the new spec (fixed in `b885578`) and the measured workload (corrected below). Its `could not verify` list: no end-to-end route+component navigation test, no `beforeunload` coverage, backend 409 by reading only. |

## Constraints

- `odd/tasks/` is tracked (`.gitignore` uses `odd/*` + `!odd/tasks/`), so this document is committed
  with the work.
- **The document was written on `main` @ `d7317f6` and moved to the branch at implementation start.**
  `main` was still dirty at writing time; by 2026-09-23T03:2xZ it was clean at `80f44c3` (PR #153),
  so the branch and worktree were created from that clean base, following
  `.agents/skills/project-conventions/references/worktrees.md`: worktree at
  `~/workspace/LifeControl-worktrees/<slug>`, sibling of the repo, never inside it. The move preserved
  the file content; the earlier "main is dirty" constraint no longer applies.
- One agent per worktree. The toolchain is Angular 20, zoneless, signals, standalone, Material M3.
- This worktree needs its own `node_modules`: `npm ci --legacy-peer-deps --prefer-offline` (mirrors
  `angular-ci.yml`). `~/.npm` is shared, so the install is extraction-bound. The **root** `gradlew`
  is absent in a fresh worktree (`.gitignore:33` ignores it, lines 40/43 re-include only the module
  wrappers), so Gradle commands run from `life-control-api/`. S1 is frontend-only and needs neither.
- Nothing is pushed and no PR is open.

## Follow-ups (not in this feature)

- **The register sweep is unfinished outside S1's surfaces.** `product-supplier-list.html:49` still
  renders a `Purchase Cost` column header in English, and `product-edit.ts:150` keeps the neutral
  `'Error inesperado. Intente de nuevo más tarde.'` fallback next to the new voseo conflict message.
  Both are the same D3 problem on pages this slice deliberately did not open. Migrate them slice by
  slice, as decided.
- **The `valueChanges` cleanup subscription in `product-supplier-form` looks unreachable.** The effect
  sets `serverError` with `emitEvent: false`, and on a later value change Angular recomputes `errors`
  from the validators *before* it emits `valueChanges`, so the branch that strips `serverError` never
  runs. Measured with a throwaway probe spec (deleted afterwards): inside a `valueChanges` subscriber,
  `setValue(-5)` on a control carrying `{ min, serverError }` already reports only
  `{"min":{"min":0,"actual":-1}}`. S1 pins the observable contract (a stale server error must not
  survive an edit) but not the mechanism, because the mechanism does nothing. Verify and remove it in
  a later slice; the same effect shape is copied in `products-form.ts` and possibly elsewhere.
- **`loadProduct` can still lose typed input silently.** The initial GET replaces `productForm()`
  wholesale (`product-edit.ts:50`), so anything typed before it resolves is destroyed without a
  prompt, and the form comes back pristine — the new guard cannot see that race. Pre-existing, not
  introduced by S1, and not covered by `canDeactivate` either (a hard refresh or tab close never is).
- **The 409 predicate is a substring match on a backend-owned English string** (`/sku/i`,
  `product-edit.ts:134`). It is correct for the two 409 sources that exist today and fails safe
  (a non-matching conflict still reaches the banner), but a generic conflict message that ever names
  the constraint (`products_sku_key`) would mark the SKU field. Revisit if the backend gains a stable
  error code.
- Supplier-side view of the association. `GET /by-supplier/{supplierId}` already exists and the
  supplier detail screen does not use it.
- Register a project-wide copy convention in `.agents/skills/project-conventions/` once D3 is
  settled, so the next screen does not re-open the question.

## Relevant files

Frontend — `life-control-app-angular/src/features/products/`:
`products.routes.ts`, `pages/product-edit/*`, `pages/product-list/product-list.ts`,
`pages/product-supplier-list/*`, `pages/product-supplier-edit/*`, `pages/product-variant-list/*`,
`components/products-form/*`, `components/product-supplier-form/*`,
`components/product-variant-form/*`, `data/product.service.ts`,
`data/product-supplier.service.ts`, `data/product-variant.service.ts`, `models/product.models.ts`.
Shared: `src/core/guards/unsaved-changes.guard.ts`, `src/shared/ui/*`.

Reusable pattern: `src/features/purchases/purchase-orders/components/supplier-info-section/*`.

Backend — `life-control-api/src/main/java/com/lifecontrol/api/product/`: `dto/ProductRequest.java`,
`service/ProductService.java`, `service/ProductSupplierService.java`,
`service/ProductVariantService.java`, `controller/ProductController.java`.
