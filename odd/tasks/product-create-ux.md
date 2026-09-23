# ODD feature: product-create-ux

**Repository**: LifeControl — frontend `life-control-app-angular/`. S1–S3 are frontend-only; only the
bulk-creation item in `## Backend dependencies` needs `life-control-api/` work, and it gates S4.
**Status**: **S1 merged** (PR #154 → `main` @ `158a6b3`, 2026-09-23) as five work-unit commits plus
the gate evidence below. **S2a is delivered in PR #155** (`feat/product-workspace-tabs` → `main`,
open, `MERGEABLE`/`CLEAN`, CI green): work-unit commit `9d5eb5c` plus its evidence commit `90ef886`,
on a branch created from `main` @ `158a6b3` in the **reused** worktree
`~/workspace/LifeControl-worktrees/feat-product-create-ux` (D7), whose original branch
`feat/product-create-ux` stays untouched at its merged tip `648930b`. **S2b is in progress** on
`feat/product-association-dialogs`, stacked on the S2a tip `90ef886` (D6, D20). S3 and S4 are not
started. Shape agreed with the user on 2026-09-23 ("Tabs + stepper con skip", "propuesta
primero"; voseo adopted as the copy register). Product `attributes` handling and the `Activo` toggle
were descoped the same day (see `## Descoped by user decision`).
**Created**: 2026-09-23
**Risk**: **medium** — route and UI restructure over four existing pages. No auth, role-set or guard
*set* change: the `unsavedChangesGuard` addition only tightens navigation on two routes that already
exist. Reclassified to **high** only if the backend slices in `## Backend dependencies` are taken in
the same branch, since those change persisted data semantics.

## S2 reconciliation (2026-09-23, read-only exploration, user decisions taken)

S2 was re-scoped before any code was written. Three document claims did not survive contact with the
code, and one task could not be executed as written. Everything below was checked against the source,
not inferred.

**Corrections to `## Confirmed gaps`.**

- **Gap 4 is false at the service level.** `supplier.service.ts:37-44` already exposes
  `getSuppliers(page, size, search)` and appends `?search=` when a term is passed; the backend has
  `SupplierController.getAllSuppliers(..., String search, ...)` feeding
  `SupplierRepository.findBySearchTerm*` over `supplierName`/`rfc`/`razonSocial`/`email`. Server-side
  supplier search exists and is already wired. The only defect is that `product-supplier-edit.ts:77`
  calls `getAllSuppliers(0, 1000)` without a term. T8 therefore needs **no backend change**: the
  unbounded payload is a caller defect, not a missing endpoint.
- **The `## Why this shape` description of the pattern to copy is wrong in shape.**
  `supplier-info-section` does not use `valueChanges.pipe(debounceTime, distinctUntilChanged,
  switchMap)` and does not use `MatAutocompleteTrigger`. The real idiom there is a `Subject<string>` +
  `debounceTime(300)` + `subscribe` (`:94-101`) over `getSuppliers(0, 20, term)` (`:108-124`).
  `MatAutocompleteTrigger` appears only in the sales `product-variant-selector.ts`. T8 copies the
  former, and the source pattern has **no loading and no empty state** — those are new work.
- **T9's redirect scope is narrower than written.** Only the two *product-scoped* list routes can
  redirect into the workspace: `edit/:id/suppliers` (`products.routes.ts:120-129`) and
  `edit/:id/variants` (`:79-90`). The top-level `suppliers` (`:130`), `suppliers/create` (`:137`),
  `suppliers/edit/:id` (`:144`) and `variants` (`:92`) routes are a different thing — the global
  supplier ABM and the store-scoped stock search, which deliberately carries no product id at all
  (`product-variant-stock-search.ts:22-40`) — and cannot redirect anywhere. They keep working as-is.
- **T6 could not be executed as written.** "Extract the list pages into presentational components;
  keep the pages as thin route hosts" contradicts T9: if `edit/:id/suppliers` redirects, nothing
  renders `ProductSupplierList`. D9 resolves the contradiction.
- **T7 has no precedent for its result contract.** All ten `dialog.open` calls in `src` are boolean
  confirm dialogs. No dialog in the repo hosts a form component and returns an entity, so the close
  contract is new work; only the confirm-dialog shell is copyable.

**Measured, not forecast.** S2 is ~23 files and ~1.550 changed lines (≈740 source / ≈810 specs), not
the 8–12 files and 600–900 lines forecast below. That is why the user split it (D6).

## S2b reconciliation (2026-09-23, read-only exploration, user decisions taken)

S2b was re-scoped before any code was written. One claim in the S2 plan does not survive contact with
the code, and one engineering decision the plan left open turns out to gate a live feature. Every
claim below is anchored to `file:line`, verified by reading the source.

**The blocking finding: `product-variant-edit` is the only host of the per-store stock/prices
editor.** `app-product-variant-store-stock` is referenced in exactly one template in the repo —
`pages/product-variant-edit/product-variant-edit.html:26` — and that page is the only path a sales
principal has to it: `pages/product-variant-stock-search/product-variant-stock-search.ts:157`
navigates to `edit/:id/variants/edit/:variantId` with `?storeId=`, and its own docblock (`:28`) says
this page is the sales principal's only route to the per-store editor. The variant list's empty state
says the same thing in copy (`product-variant-list.html:245`). D8 keeps the panel *out* of the dialog
and hands it to T15 in S4, but it does not say who hosts it in between. Executing T7 as written —
"the variant edit page becomes a dialog host" — would therefore delete a live feature for one slice.
D18 and D21 resolve it.

**Correction to the S2a note about the confirmation dialogs.** The S2a section says "the two
confirmation dialogs (`remove-supplier-dialog.html`, `disable-variant-dialog.html`) are still English
on purpose". That is true for the first and false for the second: `ui/disable-variant-dialog/
disable-variant-dialog.html` is already Rioplatense voseo (`Deshabilitar variante`, `¿Estás seguro
que querés deshabilitar …?`, `Cancelar`, `Deshabilitar`). Only `ui/remove-supplier-dialog/
remove-supplier-dialog.html` is English (`Remove Supplier Assignment`, `Are you sure you want to
remove the assignment for …?`, `This action cannot be undone.`, `Cancel`, `Remove`). S2b's D15 copy
target is therefore exactly one file, not two.

**Confirmed, not assumed.** `getAllSuppliers(0, 1000)` has exactly one call site in `src` —
`pages/product-supplier-edit/product-supplier-edit.ts:77` — so T8 is a single-site replacement and
no other screen carries the unbounded read. `suppliers/data/supplier.service.ts:37` already exposes
`getSuppliers(page, size, search)` and `:82` forwards the term to it.

**The T7 result contract has no precedent, and that is now a decision rather than a risk.** All ten
`MatDialog.open` calls under `src` are confirmation dialogs, and every `afterClosed()` consumer reads
a boolean (`product-supplier-list.ts:92-107`, `product-variant-list.ts:234-252`,
`core/guards/unsaved-changes.guard.ts:32-44`). No dialog in the repo hosts a form component or returns
an entity. D22 fixes the contract.

**The forms can be hosted unchanged, but not for free.** Both form components are presentational — no
router, no HTTP, no `ActivatedRoute` — and both own their `.form-card` wrapper, their own `<h2>`
(`product-supplier-form.html:2-8`, `product-variant-form.html:2-6`) and their own `.form-actions` with
a `Cancelar` that emits `cancelForm` instead of closing anything. Neither exposes an in-flight state,
so a dialog cannot disable the submit button without new surface. The supplier picker is a plain
`<mat-select>` (`product-supplier-form.html:11-18`) fed by an input, not the autocomplete the T8
reference uses.

**Measured for S2b before implementation**, with the same two-scout method as S2 (see
`#### S2b — measured before implementation` under `## Review workload`).

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

### Locked with the user (2026-09-23, S2)

- D6: **S2 ships as two chained PRs.** S2a = T5+T6+T9 (workspace shell, tab content, redirects);
  S2b = T7+T8 (association dialogs, supplier search), stacked on S2a. Rationale: the measured ~1.550
  lines do not fit one review, and S2b's dialogs are a separable concern from the shell.
- D7: **S2a works in the existing worktree**, on a **new branch `feat/product-workspace-tabs` created
  from `main` @ `158a6b3`**. The worktree's original branch `feat/product-create-ux` stays untouched at
  its merged tip `648930b`. Cost accepted: the directory slug no longer matches the branch name.
  Rationale: that worktree already has `life-control-app-angular/node_modules` installed and a working
  toolchain, and a fresh worktree costs a full extraction-bound `npm ci`.
- D8: **the variant dialog (T7) covers the global definition only** — `barCode` + `variantName`. The
  per-store stock panel (`app-product-variant-store-stock`, which reads `?storeId=` from
  `ActivatedRoute` at `product-variant-store-stock.ts:130`) stays out and is covered by T15 in S4,
  where the document already puts it. Rationale: a dialog cannot inherit the opener's route context,
  and threading `storeId` through `MAT_DIALOG_DATA` plus integrating the panel's `dirtyChange` into
  the dialog's close would be net-new behaviour, not a port.

### Engineering decisions (S2a)

- D9 (rescopes T6): **the two product-scoped list components become the tab content in place**, rather
  than being split into a presentational table plus a container. They keep owning their data resource,
  their dialogs and their writes; they gain `productId = input.required<string>()`, lose their own
  `app-page-header` (the shell owns it) and the `productResource` that existed only to fill that
  header, lose the `no id → /products/list` self-redirect, and gain a `countChange` output.
  Rationale: the variant list carries two views, pagination, a disabled filter and a per-screen
  `VariantStoreContext`; a fully dumb version measures at ~13 inputs and ~8 outputs. That split would
  port `product-variant-list.spec.ts` (628 lines) into a new component for no functional gain, and the
  coverage gate (`test:coverage:check`) would feel the loss.
- D10: **the tab strip is `mat-tab-group`**, the only tab primitive with a repo precedent
  (`users-admin/pages/user-detail/user-detail.html:9`); `mat-tab-nav-bar` is used nowhere in the repo.
  Selection is `[selectedIndex]` + `(selectedIndexChange)` writing `?tab=`, and the param is read
  **reactively** (`toSignal(route.queryParamMap)`), never as a snapshot, or an in-place tab click would
  never be seen. The handler must not re-navigate when the requested tab already matches the param — a
  feedback-loop guard that a spec has to pin.
- D11: **tab content is eager** (no `<ng-template matTabContent>`, which is `mat-tab-group`'s default).
  Both containers therefore instantiate on workspace entry and emit their counts, so the labels carry
  a real count on first paint with no extra count read and no duplicated request. Cost accepted: the
  Variantes container reads its page and resolves the store context even when the operator never opens
  that tab. If that cost shows up, the fix is `matTabContent` plus shell-owned count reads.
- D12: **the tab count describes the tab's current view.** Suppliers: the loaded array length (no
  count endpoint exists anywhere). Variants: `totalElements` of the current read, so a store-scoped
  view counts that store's rows. A product-level variant count would need its own read and is not in
  S2a.
- D13: **the redirect is a function-valued `redirectTo`** on exactly the two product-scoped list
  routes, preserving `storeId` (`edit/:id/variants?storeId=X` → `edit/:id?tab=variantes&storeId=X`).
  The repo has no prior redirect of any kind (grep for `redirectTo|canMatch|pathMatch` across `src`
  returns nothing), so the mechanic is new and the route spec has to pin it. The sibling `create`/`edit`
  child routes keep loading their pages: `defaultUrlMatcher` requires full segment consumption and the
  segment counts differ (`products.routes.ts:53-55` already documents this for the variant routes).
- D14: `products.routes.spec.ts` derives `variantChildren()`/`nonVariantChildren()` by path substring
  and asserts `canActivate` + `data` on every match (`:12-19`). A redirect-only route has neither, so
  those derivations must skip redirect routes **in the same slice**, or the existing assertions fail
  for the wrong reason.
- D15: **each file S2a restructures has its user-facing copy migrated to voseo (D3) in the step that
  restructures it; files S2a does not open keep their copy untouched.** For the supplier list that is:
  both add affordances, the empty state, every column header, the `Main`/`Enabled`/`Disabled` state
  chips and the two action `aria-label`s — a half-Spanish table *is* the gap-7 defect, and an English
  `aria-label` next to Spanish visible copy is the same defect one layer down. Code comments stay in
  English (technical artifact). A repo-wide copy sweep stays out, per D3. The two confirmation dialogs
  these tables open (`remove-supplier-dialog`, `disable-variant-dialog`) are S2b's surface — S2b turns
  them into dialog hosts — so their copy migrates there, not here.
- D16 (corrects the S2a task decomposition): **T5, T6 and T9 are not independently committable and land
  as one work unit.** Removing the containers' `app-page-header` is only coherent once the shell owns
  the header (T5), and the shell's header is only non-duplicated once the containers stop rendering
  theirs (T6), and the containers are only header-less without a regression once the two product-scoped
  list routes redirect instead of rendering them (T9). Every ordering leaves an intermediate commit
  with a doubled or missing page title. They are therefore implemented in four delegated steps and
  land in a single work-unit commit:
  1. `product-supplier-list` becomes tab content (`productId` input, no header, `countChange`).
  2. `product-variant-list` becomes tab content (same contract, keeping its two views, pagination,
     disabled filter and `VariantStoreContext`).
  3. `ProductEdit` becomes the workspace shell (D10, D11, D12).
  4. The two product-scoped list routes redirect (D13, D14).
  Steps 1–2 leave the routed usage of those components without a `productId` input and without a
  title; that state is never committed.
- D17 (narrows T9 for variants; user-confirmed 2026-09-23): **`edit/:id/variants` does not redirect.**
  It is gated by `VARIANT_ROLES` (`[lc-admin, lc-sales]`) while its would-be redirect target `edit/:id`
  is `PRODUCT_ADMIN_ROLES` (`[lc-admin]`), and the route file states the intent outright: *"the variant
  screens exist for `lc-admin` and `lc-sales` alike, mirroring the backend `hasAnyRole('lc-admin',
  'lc-sales')` on every variant endpoint"* (`products.routes.ts:79-85`). Redirecting would deny
  `lc-sales` the variant list and break two live paths: the post-save/post-cancel return at
  `product-variant-edit.ts:195,206`, which navigates back to `edit/:id/variants`, and the sales entry
  at `product-variant-stock-search.ts:157`. The route therefore keeps resolving, now through a **thin
  route host** (`product-variant-list-host`) that owns the `app-page-header` the tab container no
  longer renders and passes `productId` down — the "thin route host" T6 originally described, kept
  only where it is still needed. `GET /api/products/{id}` carries no `@PreAuthorize`
  (`ProductController.java:70-74`), so the host's header fetch works for a sales principal exactly as
  the container's did before. **Only `edit/:id/suppliers` redirects**: it is admin-only on both sides,
  so no principal loses access.

### Locked with the user (2026-09-23, S2b)

- D18: **the variant dialog covers the global definition only, and the store-scoped page survives as
the per-store stock/prices editor.** The dialog edits `barCode` + `variantName` and nothing else. From
the **global** view of the Variantes tab, "Editar variante" opens the dialog; from the **store-scoped**
view, and from the stock-search screen, editing keeps navigating to `edit/:id/variants/edit/:variantId`
— the page that hosts `app-product-variant-store-stock` and is the sales principal's only route to it.
`edit/:id/variants/edit/:variantId` therefore **keeps resolving** and keeps its `canDeactivate` guard;
it is not converted into a redirect. Rationale: D8 deliberately kept the panel out of the dialog
(threading `storeId` through `MAT_DIALOG_DATA` and integrating the panel's `dirtyChange` into the
dialog's close is net-new behaviour, and the panel reads `?storeId=` from `ActivatedRoute` at
`product-variant-store-stock.ts:130`), and executing T7 as written would have made the per-store
editor unreachable until T15 landed in S4 — a functional regression, not a refactor. The rule the UI
expresses is: with a store in play, editing opens the full editor; without one, the dialog edits the
identity.
- D19: **the old create/edit URLs stay registered and redirect into the workspace** (the `## Target
experience` promise: no existing URL 404s). `edit/:id/suppliers/create` and
`edit/:id/suppliers/edit/:supplierId` redirect to `edit/:id?tab=proveedores` (admin-only on both
sides, so no principal loses access — the same argument as D13); `edit/:id/variants/create` redirects
to `edit/:id/variants` with `storeId` preserved, **not** to `edit/:id`, because the create route is
`VARIANT_ROLES` while `edit/:id` is admin-only — the D17 argument again, applied to the third route.
`edit/:id/variants/edit/:variantId` is the one create/edit route that keeps resolving (D18). A
deep link to a create/edit URL therefore lands on the list and loses its intent; that is accepted and
documented rather than paid for with a per-flow dialog-host component.
- D20: **S2b ships as one PR stacked on S2a, and is split only if the measurement says so.** The
measurement is taken on the finished branch, before the PR is opened: if the real changed lines
(discounting pure Prettier reindentation, the way the S2a section does it) exceed ~1.000, the slice is
proposed to the user as two chained PRs — W1 (suppliers) and W2 (variants) — which the work-unit
commits already separate.

### Engineering decisions (S2b)

- D21: **the store-scoped variant page delegates identity editing to the dialog**, instead of keeping
its own inline copy of the form. `ProductVariantEdit` drops the create branch (that route redirects,
D19) and the inline `app-product-variant-form`, keeps the `app-page-header` and
`app-product-variant-store-stock`, and gains one action that opens the variant dialog; the dialog's
result refreshes `loadedBarCode`, which is what keys the panel's read. Rationale: with D18 the
identity editor has two possible hosts, and leaving the page's inline form in place would ship two
editors for one field pair plus ~120 duplicated lines of form creation, load, save and error
handling. It also narrows the page's `hasUnsavedChanges()` to the panel's `storeStockDirty()`, which
is now the only state that page can lose.
- D22: **the association dialog closes with the saved entity, or `null`.** `afterClosed()` returns
`ProductSupplier | ProductVariant | null`: `null` on cancel or dismissal, the entity the service
returned on success. The list's consumer stays precedent-shaped — truthy means "reload the resource"
(`if (saved) this.resource.reload()`) — and the store-scoped page additionally uses the entity to
refresh its `barCode` input. The dialog owns the write (as the page it replaces did) and stays open
with the error banner on failure, so the list needs no error handling of its own.
- D23: **T8 copies the `supplier-info-section` idiom but fixes its two hazards.** The reference
(`supplier-info-section.ts:94-96`, `:108-125`) is `Subject<string>` + `debounceTime(300)` +
`subscribe`, with **no** `switchMap` and **no** `distinctUntilChanged`, so a slow earlier response can
overwrite a later one. S2b uses `debounceTime(300)` + `distinctUntilChanged()` + `switchMap` +
`takeUntilDestroyed`, which also cancels the in-flight request. Two more things the reference does not
have and T8 adds because the ODD record says they are new work: a searching indicator and a
no-results state. The picker also stops accepting free text as a supplier id: on `(input)`, a typed
value that is not the display label of the current selection clears the control, so `required` fails
instead of a garbage id reaching the API.

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
| **S2a** | T5 | `ProductEdit` becomes a tabbed workspace with deep-linkable `?tab=` state and counts | `product-edit.spec.ts` |
| **S2a** | T6 | Rescoped by D9: the two product-scoped list components become the tab content **in place** — `productId` as an `input()`, no own page header, no self-redirect, count emitted | list specs |
| **S2a** | T9 | Per-tab loading, error, empty and count states; the two product-scoped list routes redirect into the tab, preserving `storeId` | route spec |
| **S2b** | T7 | Move supplier and variant create/edit into `MatDialog` hosts over the existing form components (D8: global definition only for the variant) | form + dialog specs |
| **S2b** | T8 | Replace `getAllSuppliers(0, 1000)` with the `?search=` the service already exposes, using the `supplier-info-section` `Subject` + `debounceTime` idiom | picker spec |
| **S3** | T10 | Three-step create stepper, skip on steps 2 and 3, `POST /products` at the end of step 1 | stepper spec |
| **S3** | T11 | Partial-failure UX: per-item status, "created with N of M", retry only the failed item, no rollback; post-persist exit is "Terminar" | stepper spec |
| **S3** | T12 | Pre-persist leave confirmation and post-persist exit semantics wired to the guard | guard spec |
| **S4** | T13 | Variant form: "Guardar y agregar otra" (no backend change) | form spec |
| **S4** | T14 | Variant matrix generation — **blocked** on B1, and on the future attributes redesign | stepper/workspace spec |
| **S4** | T15 | Embed per-store stock and prices in the Variantes tab | tab spec |

**S2b work units** (D20, one work-unit commit each): **W1** = T7 for suppliers + T8 — the supplier
dialog (D22), the debounced server-side search (D23), the `suppliers/create` and
`suppliers/edit/:supplierId` redirects (D19) and the `remove-supplier-dialog` copy migration (D15).
**W2** = T7 for variants — the variant dialog, the store-scoped page reduction (D21) and the
`variants/create` redirect (D19). W2 stacks on W1 on the same branch; if D20's measurement forces the
split, W1 and W2 are the two PRs.

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

**S2a-specific manual checks** (every one of these was named by the independent verifier as
unverifiable without a runtime browser, and none of them is covered by automation):

- `/products/edit/X?tab=variantes` renders on a **fresh load** with the Variantes tab active (the specs
  drive the param through a subject, which is not a reload).
- **Back** after a tab click returns to the previous tab, and tab clicks push history entries.
- `/products/edit/X/suppliers` really lands on `/products/edit/X?tab=proveedores` **through the
  recognizer**, and `/products/edit/X/suppliers/create` still instantiates `ProductSupplierEdit`
  instead of being swallowed by the redirect. The spec invokes the redirect function directly, so the
  "cannot shadow its siblings" argument is a reading of `defaultUrlMatcher` plus the declaration order,
  not an observed navigation. This is the repo's first redirect of any kind and the one gap worth
  closing with a real-router test in a later slice.
- A dirty `Datos` form does **not** raise the discard prompt when switching tabs. The framework source
  says it cannot (query params do not participate in route reuse), but nobody has watched it happen.
- `/products/edit/X/variants` still renders for an `lc-sales` principal, header included.

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

### S2 — measured before implementation, and the forecast was low again

Measured on `main` @ `158a6b3` by two parallel read-only mapping scouts, every claim file:line-anchored:

| Slice | Files | Source lines | Spec lines | Total |
|---|---|---|---|---|
| S2a (T5, T6, T9) | ~14 | ~350 | ~400 | ~750 |
| S2b (T7, T8) | ~9 | ~200–350 | ~600–1.000 | ~800–1.350 |
| **S2 total** | **~23** | **~740** | **~810** | **~1.550** |

Against a forecast of 8–12 files and 600–900 lines. The forecast was low for the same reason as S1's —
it counted the functional diff, not the evidence — plus one it did not anticipate: the variant list is
not one list but two views over a paginated, store-scoped, disabled-filtered read, and that single
component is the largest cost in the slice. The user split S2 accordingly (D6).

#### S2a — measured, and the forecast was low by 4×

Measured on `9d5eb5c` with `git diff main...HEAD --numstat`:

| Bucket | Files | Changed lines |
|---|---|---|
| Source | 13 | **1576** |
| Specs | 5 | **1518** |
| **Total** | **18** | **3094** (2041 insertions / 1053 deletions) |

Against the ~350 source / ~400 spec / ~750 total forecast above. Two reasons, and only one of them is
real work:

- **982 of the 3094 lines are pure Prettier reindentation.** Dropping each list's page wrapper div
  re-indents the whole template: `product-supplier-list.html` is 427 changed lines raw and **47** with
  `git diff -w`; `product-variant-list.html` is 629 raw and **27** with `-w`. A reviewer should read
  both templates with `git diff -w` or the real change is invisible.
- The behavioural diff is therefore ≈2100 lines, still ~3× the forecast, because the forecast assumed
  the variant list could be made presentational in place. D9 kept it a container, and its spec alone
  (356 changed lines) plus the shell's (576) are the bulk.

**Carry forward for S2b:** forecast its source lines, then expect roughly 1:1 spec lines on top, and
add whatever Prettier reindentation the template restructuring costs.

#### S2b — measured before implementation

Measured on `90ef886` by a read-only mapping scout, every claim `file:line`-anchored. This is a
forecast with a method, not a guess: it is derived from the actual components T7 and T8 touch.

| Work unit | Files | Source lines | Spec lines | Total |
|---|---|---|---|---|
| W1 (T7 suppliers + T8) | ~8 | ~350 | ~400 | ~750 |
| W2 (T7 variants) | ~8 | ~300 | ~450 | ~750 |
| **S2b total** | **~16** | **~650** | **~850** | **~1.500** |

Against the ~9 files / ~200–350 source / ~600–1.000 spec / ~800–1.350 total forecast in the S2 table.
The two things the forecast missed, both found by the recon above: T7 for suppliers is a **delete plus
a rewrite** (the page and its spec go, the dialog and its spec arrive, so the changed-line count
carries both sides), and T7 for variants is not a port but a **split** (D18/D21: the page keeps the
panel and loses its form, the dialog takes the form).

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
| 2026-09-23 | **S2a work unit** | `9d5eb5c` | D16: T5+T6+T9 land as one commit because no ordering gives a coherent intermediate commit. **18 files, 2041 insertions / 1053 deletions = 3094 changed lines** (13 source / 1576; 5 specs / 1518), of which **982 are pure Prettier reindentation** — see `#### S2a — measured` above. Focused products suite went 22 files / 275 tests (S1 tip) → **23 files / 317 tests**. Steps: (1) supplier list becomes tab content, RED observed as a compile-time `TS2339` on `countChange`, 20 → 22 tests; (2) variant list becomes tab content, 32 → 47 tests, and because its RED was also compile-time the worker ran a **mutation control** (swapping `totalElements` for `content.length` and duplicating the empty-state affordance) and observed exactly those two tests fail, then reverted byte for byte; (3) the workspace shell, 25 → 35 tests, including a **probe that settled D11 empirically**: `mat-tab-group` instantiates inactive-tab children (`instances=1, domCount=0`), so the labels do carry counts before their tab is visited; (4) the redirect plus the variant route host, route spec 7 → 11 tests, RED observed as 4 real assertion failures. |
| 2026-09-23 | **S2a gates (baseline)** | `main` @ `158a6b3` | Already on record from S1: 124 files / 2315 tests, coverage 93.37/75.60/88.36/93.37. |
| 2026-09-23 | **S2a gates (tip)** | `9d5eb5c` | `npm run lint` → `All files pass linting.`; `npm run build` → bundle complete, 850.87 kB initial (S1: 850.86 kB), exit 0; `npm run test:coverage:check` → **125 files / 2357 tests / 0 failures**, coverage **93.38/75.34/88.52/93.38** (thresholds 80/60/75/80). Delta over baseline: +1 file, +42 tests, +0.01/**−0.26**/+0.16/+0.01. **Branches are the thin gate: 75.34 against a 75 threshold — a 0.34 pp margin, down 0.26 pp.** Not a blocker, but recorded so S2b does not inherit it blind. |
| 2026-09-23 | S2b plan | — | Read-only mapping of the T7/T8 surface by a `gentle-ai-explore` scout, plus the `## S2b reconciliation` above: the single-host finding for `app-product-variant-store-stock`, the correction to the S2a dialog-copy note, the confirmation that `getAllSuppliers(0, 1000)` has one call site, and the absence of any form-hosting dialog precedent. User decisions D18–D20 taken on 2026-09-23. No source written. |
| 2026-09-23 | S2a independent verification | `9d5eb5c` | A read-only `gentle-ai-verify` subagent ran the three gates (all PASS) and adversarially checked ten claims against the **installed** `@angular/router` 20.3.31 source rather than the commit message. Upheld: no effective authorization change; the redirect config is legal — `RuntimeError 4014` forbids `redirectTo` together with `canActivate`/`canMatch` (`router2.mjs:1953-1956`), so dropping both was **mandatory, not stylistic** — and the redirect function really does run inside an injection context (`router2.mjs:3830`, `runInInjectionContext(injector, …)`), which is what makes the route's `inject(Router)` safe; `?tab=` is read reactively with a working feedback-loop guard; **query params do not participate in route reuse** (`BaseRouteReuseStrategy.shouldReuseRoute` compares only `routeConfig`, and the default `paramsChange` mode compares only `params` + `url` segments), so a tab click cannot fire `unsavedChangesGuard`; the counts are real and the specs would fail if `totalElements` were swapped for `content.length`; no descope leak (`git diff main...HEAD -- components/products-form/*` empty); no vacuous assertion in the changed specs; the D17 regression guard works; no dead code and no second header fetch. **One wording corrected:** the claim "no other route's guards or `data` changed" is literally false — `edit/:id/suppliers` sheds both when it becomes the redirect. That is deliberate and required by `RuntimeError 4014`; effective authorization is unchanged because the target `edit/:id` keeps the admin gate, and the route spec pins `canActivate`/`data` as `undefined` on the redirect route on purpose. Its `could not verify` list is the S2a-specific manual checklist above; the redirect having no real-router navigation test is the one worth closing later. |

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

### Raised by S2a

- **The redirect has no real-router test.** The route spec invokes the `redirectTo` function directly
  under `TestBed.runInInjectionContext` and asserts the `UrlTree` it returns. That pins the function,
  not the recognizer's decision to pick that route — nor that `edit/:id/suppliers/create` still wins
  over it. The supporting argument (full segment consumption in `defaultUrlMatcher`, plus the sibling
  routes being declared first) is a reading of the framework, and the route spec is the only place it
  is pinned. Close it with a real-router navigation test; the repo has no precedent for one, which is
  exactly why the first redirect in the codebase is the wrong place to leave the gap.
- **`MatPaginatorIntl` labels are English repo-wide.** `mat-paginator`'s own strings ("Items per page",
  the range, prev/next) come from the Material default, not from this repo's copy, and `LOCALE_ID:
  'es-MX'` does not change them. D15 only migrates strings a file owns, so a Spanish variant table
  still ships an English paginator. One shared `MatPaginatorIntl` provider fixes every table at once;
  it is outside S2a's surface and belongs to its own slice.
- **`ProductEdit` reads `productId` from a route snapshot** (`product-edit.ts:52`), so a same-route
  param change (`edit/A` → `edit/B`) reuses the component without re-running `ngOnInit` and the
  workspace keeps showing product A. Pre-existing on `main` and neither fixed nor worsened here, but
  the tabbed workspace makes it far more visible, because the header now states product identity.
- **The two confirmation dialogs are still English** (`remove-supplier-dialog.html`,
  `disable-variant-dialog.html`). D15 deliberately left them to S2b, which turns those routes into
  dialog hosts and therefore opens those files. S2b must migrate them or the new dialogs will ship
  Spanish-trigger-to-English-confirmation.
- **Branch coverage sits 0.34 pp above its threshold** (75.34 against 75), down 0.26 pp from the S1
  baseline. S2b adds dialog hosts and form wiring, which is coverage-dense territory; re-measure early
  in that slice rather than discovering it at the gate.

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
