# ODD feature: product-create-ux

**Repository**: LifeControl — frontend `life-control-app-angular/`. S1–S3 are frontend-only; only the
bulk-creation item in `## Backend dependencies` needs `life-control-api/` work, and it gates S4.
**Status**: **S1, S2a and S2b are merged into `main`** — S1 in PR #154, S2a in PR #155, S2b in
PR #156, the last two as merge commits `d85e9e2` and `d7c6e16`. `main` is at **`d7c6e16`**, the
anchor worktree is clean, and `git diff 4f6d2f1 main` is empty, i.e. `main`'s tree is byte-identical
to the verified S2b head. Gates on the S2b code tip `ae0cfb1`: lint clean, build 850.87 kB exit 0,
`test:coverage:check` **127 files / 2436 tests**, coverage **94.02/75.8/89.15/94.02** (thresholds
80/60/75/80). S2b measured 3.247 changed lines against D20's ~1.000 threshold, so the split was
proposed; the user chose one PR. The S2a and S2b sections below keep their "open PR" wording as the
record of what was true when they were written; this line is the correction.

**S3 is merged into `main`** as PR #157 (merge commit `a98426e`, branch `feat/product-create-stepper`,
created from `main` @ `d7c6e16` in the **reused** worktree
`~/workspace/LifeControl-worktrees/feat-product-create-ux` (D7)), as three commits: `9855f2c` (this
record's S3 plan and the status correction), `59d02bc` (the stepper, one work unit) and `1b8a507` (the
independent verifier's findings round). Gates on `1b8a507`: lint clean, build **851.05 kB** initial
exit 0, `test:coverage:check` **127 files / 2458 tests / 0 failures**, coverage
**94.03/75.80/89.19/94.03** (thresholds 80/60/75/80). S3 measured **579 changed lines of code and
specs** (221 source / 358 specs, 6 files) plus 203 lines of this record at the plan commit, so D20's
~1.000 threshold was not reached and no split was proposed. `main` is at **`edbfb46`** (PR #158, a
docs-only change to the convention skill).

**S4 is delivered on branch `feat/product-variant-tab-stock`**, created from `main` @ `edbfb46` in a
**fresh** worktree `~/workspace/LifeControl-worktrees/feat-product-variant-tab-stock` — fresh because
PR #157's merge deleted the S3 branch and `gh` removed its worktree. Scope is **T13 + T15**; **T14
stays blocked** on the unapproved `B1` (D30). Shape agreed with the user on 2026-09-23 before any code
was written: **per-row expansion** inside the Variantes table for T15 (D31) and **the dialog stays
open and resets in place** for T13 (D32), after a read-only two-scout mapping of the surface and a
`## S4 reconciliation` that corrects two record claims. Five commits: `ce88623` (this record's S4 plan),
`1ebc552` (T13/W1), `1b3fbe4` (T15/W2), `98081ae` (first findings round) and `0622ed5` (second findings
round). Two independent read-only verifications ran, at `1b3fbe4` and at `98081ae`. Gates on `0622ed5`:
lint clean, build **851.05 kB** initial exit 0, `test:coverage:check` **127 files / 2506 tests /
0 failures**, coverage **94.07/75.94/89.22/94.07** (thresholds 80/60/75/80). **S4 measured 1.403 changed
lines of code and specs** — above D20's ~1.000 threshold — so the split is proposed below; the user chose
**one PR**. **PR #159 is merged into `main`** as merge commit **`84a17ef`** (parents `edbfb46` +
`7a85a12`, a real two-parent merge commit, merged 2026-09-23T23:34:13Z); Angular CI *Lint, Build &
Test* was **green** on `d953e56` (run `35927601959`) before the merge. That merge deleted the S4
branch and its worktree, exactly as the S4 record forecast. Shape for the earlier slices was agreed the
same way ("Tabs + stepper con skip", "propuesta primero"; voseo adopted as the copy register). Product
`attributes` handling and the `Activo` toggle were descoped the same day (see
`## Descoped by user decision`).

**S5 is in progress on branch `fix/variant-list-host-guard`**, created from `main` @ `84a17ef` in a
**fresh** worktree `~/workspace/LifeControl-worktrees/fix-variant-list-host-guard` (fresh because the
S4 merge removed the previous one). Scope is **T16**, the only functional gap `### Raised by S4` left
open: the `edit/:id/variants` host route carries no `canDeactivate`, so an operator — the `lc-sales`
principal, for whom that route is the only variant entry point — can edit per-store stock and prices in
the expanded panel and navigate away with no prompt. Decisions **D38–D40** in `## S5 reconciliation`
below; the shape was agreed with the user on 2026-09-23 before any code was written.
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

**One finding raised by S2b's own verification and then refuted by reading the write path.** The W2
verifier reported that an operator with unsaved stock/prices on the per-store panel could change the
variant's barcode through the definition dialog and have those in-progress values written against
the wrong row, and the page briefly disabled the definition action for it. That hazard does not
exist: the panel's **write** is keyed by `variantId` + `storeId`
(`product-variant-store-stock.ts:211` → `PUT /api/variants/{variantId}/stores/{storeId}`), and only
its **read** is keyed by the barcode (`:124`). A barcode change re-keys the read to the same variant's
row, and the dirty form is preserved and written to the same row. The disable and its tooltip were
reverted rather than shipped as an unnecessary restriction; the second verification round confirmed
the write key independently.

## S3 reconciliation (2026-09-23, read-only exploration, user decisions taken)

S3 was re-scoped before any code was written. One task could not be executed as written, three claims
in the S2b reconciliation are now false, and the framework facts the design depends on were read from
the **installed** Material 20.2.10 and `@angular/cdk` sources, not from documentation.

**The blocking finding: T11's shape contradicts the reuse S2b just built.** T11 asks for "per-item
status, 'created with N of M', retry only the failed item", which presupposes a step that accumulates
several associations locally and submits them in one action. But every association carries its own
data (`purchaseCost`/`main`/`enabled` for a supplier link, `barCode`/`variantName` for a variant), so
uniform rows are meaningless; and S2b's dialogs already submit one item each and already own the
failure surface — on a failed write the dialog **stays open** with the error banner and keeps the
entered values (`product-supplier-dialog.ts:184`, `product-variant-dialog.ts:115-117`). A batch model
would therefore duplicate that logic and ship two different association UXes in one feature. The user
chose immediate per-item submit (D24) and T11 was re-scoped.

**The dialogs are page-agnostic, which is what makes D24 possible.** Neither dialog injects
`ActivatedRoute`, `Router`, a store context, or a parent reload callback; both read
`MAT_DIALOG_DATA` and own their own write. They need exactly one thing: `productId`. The proof is
already in the tree — `product-variant-edit.ts:130` opens `ProductVariantDialog` from a page that is
not a list.

**Three claims in the S2b reconciliation are false as of the S2b tip.** They were true when written
and S2b itself invalidated them; the record keeps them corrected rather than silently stale.

- "The supplier picker is a plain `<mat-select>` fed by an input, not the autocomplete the T8
  reference uses." **False.** `product-supplier-form.html:10-27` is a `matAutocomplete` with a
  searching row and a no-results row — that is what T8 shipped.
- "No dialog in the repo hosts a form component or returns an entity." **False.** The two S2b dialogs
  close with `ProductSupplier`/`ProductVariant` (D22).
- "Both form components own their own `<h2>`." **False.** Both templates open on `<div
  class="form-card">` with no heading; the `<h2 mat-dialog-title>` lives in the dialogs. The
  `:2-8` reference actually matches `products-form.html`.

**Confirmed by reading, not assumed.** `createProduct` has exactly one production call site
(`product-edit.ts:185`); `supplier-info-section` still has the un-`switchMap`ped `Subject` +
`debounceTime(300)` idiom the S2b record describes; the guard interface is
`hasUnsavedChanges(): boolean` and nothing more; there is **no** `beforeunload` and **no** post-persist
navigation block anywhere under `src`; and `products.routes.ts` already registers
`canDeactivate: [unsavedChangesGuard]` on `create`, so T12 needs no route change.

**Measured framework facts the design rests on** (`@angular/material` 20.2.10, `@angular/cdk`):

- `MatStepper` renders **every** step's content eagerly (`stepper.mjs:447`: the content container
  `@for`-loops all steps and projects `step.content`). Lazy content exists only through
  `<ng-template matStepContent>` (`MatStepContent`), whose portal is created on first selection and
  then **kept** (`stepper.mjs:259-262` guards on `!this._portal`), so a visited step is not
  re-created when the operator leaves it.
- `CdkStep.completed` is `_completedOverride ?? (interacted && (!stepControl || stepControl.valid))`,
  so a `[completed]` binding overrides the computed value without needing a `stepControl`.
- `CdkStep.isNavigable()` is `isCompleted || isSelected || !stepper.linear`.
- `CdkStepper.selectedIndex`'s setter refuses a forward move while any step's control is invalid or
  pending, and refuses a backward move into a step whose `editable` is false.
- `MatStepperIntl`'s "Optional" label is Material's English default and the repo has no provider for
  it, so `[optional]="true"` would print English inside Spanish copy.

## S4 reconciliation (2026-09-23, read-only exploration, user decisions taken)

S4 was re-scoped before any code was written, by two read-only mapping scouts that anchored every claim
to `file:line` at `edbfb46`. Two record claims are false at HEAD, one task is blocked by an unapproved
backend item, and the locked shapes need five engineering decisions the plan did not carry.

**Scope: T13 + T15. T14 stays blocked (D30).** `B1` (bulk variant creation) is not approved, so the
matrix generator has no endpoint to call; without it T13 is the honest ceiling, exactly as the task
table already says.

**Two record claims are false at HEAD, both invalidated by S2b's own D21.**

- **D17's "the redirect would break two live paths: the post-save/post-cancel return at
  `product-variant-edit.ts:195,206`, which navigates back to `edit/:id/variants`" is false.** That page
  now has exactly one `router.navigate`, `product-variant-edit.ts:104` → `['/products/list']`; D21
  deleted the inline definition form and with it the return path. The stale sentence survives verbatim
  as a comment in `pages/product-variant-list-host/product-variant-list-host.ts:16` and
  `products.routes.spec.ts:188`. D17's argument does not depend on it — the target of that redirect is
  `VARIANT_ROLES` while `edit/:id` is `PRODUCT_ADMIN_ROLES` — so the decision stands and only the
  evidence is corrected. **The two stale comments are corrected in S4.**
- **D8's anchor "`product-variant-store-stock.ts:130`" is now `:148`**, and S2b's
  "`product-variant-edit.html:26`" is now `:24`. Same claims, drifted lines.

**Confirmed by reading, not assumed.**

- `app-product-variant-store-stock` still has exactly **one** template host,
  `product-variant-edit.html:24`, and resolves its own store in its constructor
  (`product-variant-store-stock.ts:148`) with `VariantStoreContext` provided **per instance** (`:72`).
  Its read is keyed by `barCode` + `storeId` (`:124`) and its write by `variantId` + `storeId` (`:211`)
  — unchanged since S2b, and the write key is what makes an embedded instance safe.
- `ProductVariantList` resolves the same store from the same `?storeId=` snapshot (`:189`) and already
exposes it as `storeId`/`storeScoped` (`:126-141`). Its table declares a single `matRowDef`
  (`product-variant-list.html:181`): there is **no** expansion, selection or per-row state today, so
  the row detail is net-new work, not a port.
- `ProductEdit.hasUnsavedChanges()` reads **only** the product form (`product-edit.ts:338-340` →
  `return this.productForm().dirty;`), so a dirty panel embedded in a tab is invisible to the route
  guard until the shell aggregates it.
- The workspace tab route `edit/:id` is `PRODUCT_ADMIN_ROLES` (`products.routes.ts:45-47`) while the
  panel's only route is `VARIANT_ROLES` (`:72-74`). The tab is therefore **not** reachable by
  `lc-sales`, and the store-scoped page cannot be deleted without denying the sales principal the
  panel — the D17/D19 argument applied to T15. The page therefore **stays exactly as it is** (D36),
  and both of its entry paths survive: `product-variant-list.ts:230-235` (store-scoped row action) and
  `product-variant-stock-search.ts:156-159` (sales).
- The dialogs' dirty-close guard is the repo's own idiom for "do not discard typed input":
  `product-variant-dialog.ts:117-144` opens the shared `ConfirmDialog` before closing a dirty form.
  T15's expansion switch copies it instead of inventing a second contract (D33).
- `ProductVariantDialog`'s dirty signal is a bare `valueChanges` subscription
  (`product-variant-dialog.ts:91-94`) and `disableClose` is driven from it (`:96-100`), so a `reset()`
  that emits would immediately re-arm both. The child form's `serverErrors` effect
  (`product-variant-form.ts:66-110`) is not reset-aware either, so a stale server error would survive
  a reset unless the dialog clears it too. `ProductVariantForm` has no in-flight state
  (`product-variant-form.ts:49-55`), so a second click during a create POST is a second POST.
- `product-variant.service.ts:131-145` POSTs `{ barCode, variantName }` to
  `/products/{productId}/variants`; `ProductController.java:162-172` validates and creates with no
  session or one-shot constraint. **T13 needs no backend change**, and a repeat POST with a different
  barcode/name can only fail with the 409 the dialog already discriminates.
- No dialog in the repo resets a hosted form for a second entry. The closest precedent is the
  signal-based clear in `purchases/purchase-orders/components/detail-table/detail-table.ts:252-259`,
  which T13 follows in spirit: the reset is explicit state, not a framework `reset()` that would emit.

### Locked with the user (2026-09-23, S4)

- D30: **S4 is T13 + T15, and T14 stays blocked on `B1`.** `B1` is a separate decision and is not
  approved, so the matrix generator is not in this slice and no `life-control-api/` file is touched.
  Consequence: S4 stays frontend-only and the feature's risk stays **medium**; taking `B1` in the same
  branch is what would reclassify it to high.
- D31: **T15 embeds the per-store panel as a per-row expansion inside the existing variant table.** A
  leading expand toggle column is added to both column sets; the expanded detail row hosts
  `app-product-variant-store-stock` with the row's `variantId` and `barCode`. Rationale: the table is
  already the view the operator is in, the panel is already the editor for exactly this data, and the
  alternatives cost more for less — a master–detail side panel needs a new selection state, new layout
  and the same dirty plumbing, and making the store-scoped columns editable leaves the global view
  with no editor and duplicates the panel's validation and save surface.
- D32: **T13's shape is "the dialog stays open and resets in place".** `ProductVariantForm` gains a
  second submit output and a create-mode-only `Guardar y agregar otra` button; on success the dialog
  resets the group with `emitEvent: false`, clears `dirty`, `serverErrors` and `generalError`, and
  stays open for the next variant. The form spec is the evidence the task table already names.
  Rationale: it is the smallest change that removes the close-and-reopen cycle, it needs no backend
  change, and it keeps D22's close contract for the two existing consumers.

### Engineering decisions (S4)

- D33: **one expanded row at a time, and every path that destroys a dirty panel either asks first or
  clears through one channel.** The list holds `expandedVariantId`; toggling another row (or the same
  one) while the open panel reports `dirty` opens the shared `ConfirmDialog` with voseo copy and only
  then collapses, and the findings round extended the same guard to the four **user-driven** view
  changes that can drop the expanded row from the loaded page — pagination, the page-size change, the
  `Mostrar deshabilitadas` toggle and the store-scope toggle — restoring the rendered control
  (paginator, slide toggle) when the prompt is cancelled, so the control on screen matches the signals
  that did not move. The paths the operator does **not** drive — a read error, and a request change that
  unmounts the table while the new read is in flight — are handled by the same effect that clears the
  expansion, so the destroyed panel and the emitted `dirtyChange(false)` stay in step instead of
  stranding the shell's flag at `true` for an edit that no longer exists. The panel already emits
  `dirtyChange`, so this cost one helper and one extra condition, not a new state channel. Rationale: a
  collapsed panel is destroyed, so any silent destruction discards typed stock/prices with no prompt —
  the exact class of loss the two S2b dialogs and the route guard already refuse to allow. One
  destruction path is deliberately **not** guarded: `disableVariant` on the expanded row, accepted in
  `### Raised by S4` with its reason.
- D34: **the dialog remembers the last saved variant and closes with it on every exit path.** `null`
  still means "nothing was persisted". Without this, an operator who adds two variants and then closes
  the dialog would leave the list stale, because `ProductVariantList` reloads only on a truthy result
  (`product-variant-list.ts:252-261`) and the reset form makes the final `Cancelar` clean. The list's
  consumer therefore needs no change; the store-scoped page cannot reach the new button at all (it
  opens the dialog in edit mode), so its `saved.barCode` re-key is unaffected.
- D35: **`ProductVariantForm` gains a `saving` input and both submit buttons honour it.** T13 makes the
  recorded double-submit hazard materially worse — a double click on "y agregar otra" would create two
  rows instead of surfacing a 409 — so the in-flight state the S3 follow-up asked for is added here,
  scoped to the two buttons the form owns. The same input closes the gap for plain `Guardar`.
- D36: **the panel is hosted unchanged — no new input, no hoisted store context.** It resolves the
  store from its own `ActivatedRoute` snapshot, which inside the tab is the same `edit/:id` route with
  the same `?storeId=`, and it is provided per instance. Trade-off accepted and recorded: with one
  expansion at a time there is at most one extra `GET /api/profile` per expanded row, and the panel's
  store can in principle resolve differently from the list's store-scoped columns if the profile
  changes between the two resolutions. A `storeId` input on the panel is the durable fix if
  multi-expansion ever lands; it is deliberately not taken now because it would change a component the
  sales-reachable page also hosts, for a cost that one expanded row cannot currently exercise.
- D37: **the shell aggregates the embedded panel's dirty flag.** `ProductVariantList` gains a
  `dirtyChange` output fed by the expanded panel's own `dirtyChange` (and `false` when the panel is
  destroyed), `ProductEdit` binds it on both hosts, and `hasUnsavedChanges()` becomes
  `productForm().dirty || variantPanelDirty()`. Rationale: the route already carries
  `canDeactivate: [unsavedChangesGuard]`, and a panel whose edits the guard cannot see would let a
  stray navigation discard stock and prices silently — the same defect S2b's F-finding closed inside
  the dialogs.

## S5 reconciliation (2026-09-23, read-only exploration, user decisions taken)

**Trigger**: `### Raised by S4` records exactly one functional gap — "The `edit/:id/variants` host
route has no `canDeactivate`" — and the user authorized closing it on 2026-09-23 ("si dale por ahi",
after a read-only inventory of every pending ODD item). It is the only item in that inventory that
loses operator input.

**Measured at `84a17ef`** (read-only, `file:line` anchored):

- `products.routes.ts` registers `canDeactivate: [unsavedChangesGuard]` on `create` (`:41`),
  `edit/:id` (`:48`) and `edit/:id/variants/edit/:variantId` (`:75`). The sibling
  `edit/:id/variants` (`:89`) — the route that hosts `ProductVariantListHost` — carries `canActivate`
  and `data` only.
- The container already publishes the panel's dirty flag: `ProductVariantList` declares
  `readonly dirtyChange = output<boolean>()` (`product-variant-list.ts:121`) and emits it from
  `setPanelDirty` (`:323-326`), including `false` when the panel it destroys was dirty.
  `ProductEdit` binds it twice (`product-edit.html:34`, `:108`), aggregates it at
  `product-edit.ts:348-349` — `productForm().dirty || variantPanelDirty()` — and `variantPanelDirty`
  is declared at `:111`. The host binds `productId` only (`product-variant-list-host.html:11`), so on
  that route the container's flag never leaves the child.
- `unsavedChangesGuard` is `CanDeactivateFn<UnsavedChangesAware>` (`unsaved-changes.guard.ts:25`) and
  decides on `component?.hasUnsavedChanges?.()`. On this route the activated component is the
  **host**, not the container, so the host is the only object the guard can ask.
- The store-scoped search page (`product-variant-stock-search`) neither imports the container nor
  hosts the panel, so it is not a second surface for this gap.
- Spec surfaces to extend: `products.routes.spec.ts:114` (the `canDeactivate` block, which also pins
  at `:123` that `list` and `edit/:id/suppliers` stay unguarded) and
  `product-variant-list-host.spec.ts` (the host contract: header text, `productId` hand-down, exactly
  one header and one list).

**Decisions**

- **D38: the host forwards the container's dirty flag; the guard stays generic and untouched.** The
  host binds `(dirtyChange)="panelDirty.set($event)"` and implements
  `hasUnsavedChanges(): boolean { return this.panelDirty(); }`, mirroring `ProductEdit`'s aggregation
  (D37). The route gains exactly `canDeactivate: [unsavedChangesGuard]`. No new guard, no change to
  `unsavedChangesGuard`'s signature or its copy, and no change to the container: its `dirtyChange`
  contract is already the one the workspace consumes.
- **D39: the host holds no reset logic of its own.** The signal's lifetime is the route's and
  `canDeactivate` runs before destruction, so a reset-on-destroy hook would be dead code; the
  container already emits `false` when the panel it destroys was dirty (D37). Re-entering the route
  builds a new host that starts at `false` — the same property `ProductEdit.variantPanelDirty` relies
  on.
- **D40: the guard goes on the host route only.** The store-scoped page hosts neither the container
  nor the panel, and the variant edit route already carries the guard; widening `canDeactivate` to the
  product list or the supplier routes is a different decision with a different owner, and `:123`
  already pins the "unrelated routes stay unguarded" contract.

**Scope: T16 only.** No dialog, container, service or backend change; the panel's per-instance store
resolution (D36) and the two-affordance question stay open exactly as `### Raised by S4` records them.

**Risk: low.** One `canDeactivate` registration and one output binding on an existing route; no auth,
role-set or data-semantics change. The guard only tightens navigation on a route that already exists.

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

`/products/create` becomes a three-step stepper. Steps 2 and 3 are optional, and that optionality is
carried by the step label (`Proveedores (opcional)`) rather than by a second "Saltar" button: with
D24 there is nothing to submit in those steps, so advancing **is** skipping. Step 1 is not skippable —
it is the step that creates the product.

```text
 ① Datos ─── ② Proveedores (opcional) ─── ③ Variantes (opcional)
    ↓                    ↓                            ↓
 POST /products   POST …/suppliers (uno por    POST …/variants (uno por
                  diálogo, al agregar)          diálogo, al agregar)
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
- Each association is an independent request, and with D24 it is also an independent *action*: the
  operator adds one supplier or variant at a time through its dialog, which is where a failure is
  reported and where the entered values survive. A failure therefore never leaves a half-written row
  in the step's list, nothing is rolled back, and nothing is lost — the retry is the dialog the
  operator is still looking at. There is no batch, so there is no "created with N of M" summary.
- The step's list is the read of what already exists, exactly as the workspace tab reads it, so the
  step and the tab can never disagree.
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

### Locked with the user (2026-09-23, S3)

- D24: **the stepper's steps 2 and 3 reuse the existing list containers and the S2b dialogs; every
association is submitted immediately, one item per dialog.** Step 2 hosts `ProductSupplierList` and
step 3 hosts `ProductVariantList` — the same containers the workspace tabs use — and each one opens
its own dialog, which owns the write, the error banner and the dirty-close guard. Consequences the
design carries: **T11 is re-scoped** (there is no batch, no local queue and no "created with N of
M"; a failed item never enters the list because its dialog stays open and keeps the entered values,
which *is* the retry path for exactly the failed item), **T10's "skip" is expressed by the step
label** (`Proveedores (opcional)`) instead of a redundant "Saltar" button, because with nothing to
submit, advancing **is** skipping, and nothing is rolled back because every write is already
committed. Rationale: the alternative (accumulate rows locally, submit them in one action) requires
a new inline form host per step, a local queue model, a sequential submitter with per-item status
and retry, and a skip-with-pending-items warning — and it would duplicate the write, the 409
discrimination and the close contract that the dialogs already implement, shipping two different
association UXes inside one feature. A uniform batch is also semantically wrong here: every link
carries its own `purchaseCost`/`main`/`enabled`, and every variant its own `barCode`/`variantName`.
- D25: **the stepper lives in `ProductEdit`'s create branch; no new page component.** `ProductEdit`
already owns, for both modes, the form, the `createProduct`/`updateProduct` call, the 409→SKU
discrimination and the guard wiring; the `create` route already points at it with
`canDeactivate: [unsavedChangesGuard]`. Extracting a self-contained `ProductCreate` page would move
`createProduct`'s only production call site and either duplicate ~40 lines of error mapping or
force an extraction, and it would need its own TestBed plus stubs for the two list containers while
`product-edit.spec.ts` already drives the **real** containers against mocked services. This is D9's
measured argument applied to the page shell: splitting is not free, and the repo already paid for
that lesson once.
- D26: **returning to step 1 after the product exists edits it with `PUT`.** On a successful step 1 the
page writes the created id back into the form's `id` control, which flips `ProductsForm`'s own
`isEditMode` computed (`products-form.ts:62`, derived from `controls.id.value`) with no new input,
and `onSaveProduct`'s existing `productData.id === ''` branch already routes to `updateProduct`.
The form is marked pristine on success, so the write-back does not arm the guard. Rationale: the
objective is a *recoverable* flow, and forcing the operator to "Terminar", find the workspace and
come back to fix a typo would defeat it. The alternative (a read-only step 1) needs a `disabled`
mode `products-form` does not have.
- D27: **post-persist exit is "Terminar" → the product workspace, with no prompt.** Once the product
and its associations are persisted there is no unsaved state left, so the existing
`unsavedChangesGuard` is already correct and must **not** be given a second, post-persist contract:
it fires only for a dirty step-1 form (before the first save, or after a back-edit that was not
saved). "Cancelar" therefore exists only before the product is persisted and navigates to
`/products`; afterwards the same affordance is relabelled "Terminar" and navigates to
`/products/edit/:id`. The guard file is not touched by S3.
- D28: **stepper mechanics.** `[linear]="false"` plus `[completed]="productId() !== null"` on step 1,
and `<ng-template matStepContent>` on steps 2 and 3. `linear=false` is required for free
back-navigation: with `linear=true`, `isNavigable()` is `completed || selected`, so step 3 would be
unreachable until step 2 were marked completed, and marking it completed would paint a "done"
checkmark on a step nobody has done. Laziness is required by data, not by taste: both containers
declare `productId = input.required<string>()` and there is no id until step 1 persists, so an eager
step 2 would either throw or fire a request for a product that does not exist. `[optional]` is not
used because it renders Material's English "Optional" (no `MatStepperIntl` provider exists in the
repo — the same class of defect as the English `MatPaginatorIntl` follow-up), and each step's
content also carries an `@if (productId(); as id)` guard so a header click on step 2 before the
product exists shows an explicit hint instead of an empty step.
- D29: **no page header on step 1, and the product header appears once the product exists.** Create
mode has never rendered `app-page-header` ("no header, no tabs, the form exactly as it was"), and
`products-form` already prints its own `<h2>` (`Nuevo Producto` / `Editar Producto`). A second title
on step 1 would be the third one on screen. From step 2 onward — i.e. exactly when the operator
leaves the form — the shell shows the header with the created product's name and SKU, which is the
context steps 2 and 3 would otherwise lose, and which is also what the workspace shell shows.

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
| **S3** | T10 | Three-step create stepper on `/products/create`; steps 2 and 3 are optional and are expressed as such in the step label; `POST /products` at the end of step 1 | stepper spec |
| **S3** | T11 | **Re-scoped by D24**: no batch and no "N of M" — each association is submitted by its own dialog, which is the failure surface and keeps the entered values; the step lists what already exists, nothing is rolled back, and "Terminar" is always available | list + dialog specs (existing) |
| **S3** | T12 | Pre-persist leave confirmation and post-persist exit semantics wired to the guard (D27) | guard spec + `product-edit.spec.ts` |
| **S4** | T13 | Variant form: "Guardar y agregar otra" (no backend change). D32 shape, D34 close contract, D35 in-flight guard | form + dialog specs |
| **S4** | T14 | Variant matrix generation — **blocked** on B1, and on the future attributes redesign. **Not in S4** (D30) | stepper/workspace spec |
| **S4** | T15 | Embed per-store stock and prices in the Variantes tab. D31 per-row expansion, D33 one row at a time, D36 panel hosted unchanged, D37 dirty aggregation | list + shell specs |
| **S5** | T16 | Guard the `edit/:id/variants` host: bind the container's `dirtyChange`, implement `hasUnsavedChanges()` on `ProductVariantListHost` and register `canDeactivate` (D38/D39/D40) | host spec + `products.routes.spec.ts` |

**S2b work units** (D20, one work-unit commit each): **W1** = T7 for suppliers + T8 — the supplier
dialog (D22), the debounced server-side search (D23), the `suppliers/create` and
`suppliers/edit/:supplierId` redirects (D19) and the `remove-supplier-dialog` copy migration (D15).
**W2** = T7 for variants — the variant dialog, the store-scoped page reduction (D21) and the
`variants/create` redirect (D19). W2 stacks on W1 on the same branch; if D20's measurement forces the
split, W1 and W2 are the two PRs.

**S3 work units, corrected by the measurement: one work unit, not two.** The plan proposed
**W1** = T10 + T12 (the stepper shell) and **W2** = T11's re-scoped evidence as separate commits. The
code says otherwise: the stepper shell, the two step hosts, the step-1 host contract and the exit
semantics all live in the same three files (`product-edit.ts`, `.html`, `.scss`), and the
`the association steps reuse the workspace containers (D24/T11)` describe is a block in the same
spec file. Splitting them would mean splitting one spec file across two commits to manufacture a
boundary that does not exist in the code — the same conclusion D16 reached for T5/T6/T9. T10, T11 and
T12 therefore land as a single commit (`59d02bc`), and the plan's W1/W2 wording is kept above as the
record of what was forecast. A third commit (`1b8a507`) carries the independent verifier's findings
round, which is its own reviewable unit by the same argument the S2b slice used for `717686a`.

**S5 work units** (D20, one work-unit commit each): **W1** = T16 — the host's `dirtyChange` binding and
its `hasUnsavedChanges()`, the route's `canDeactivate`, and both spec surfaces in one commit, because
the route assertion and the host contract are the same change seen from two files and splitting them
would ship a guard whose only caller is untested. The record's own status correction and S5 plan land
first as a docs commit, per the S3/S4 precedent.

**S4 work units** (D20, one work-unit commit each): **W1** = T13 — the second submit path in
`product-variant-form`, its reset-and-stay-open handling and the in-flight guard in
`product-variant-dialog` (D32/D34/D35). **W2** = T15 — the expand column and detail row in
`product-variant-list`, the one-at-a-time switch with its confirmation (D33), the shell's dirty
aggregation (D37) and the two stale D17 comments the reconciliation corrects. W2 does not stack on W1
functionally: they touch disjoint files, so either order is reviewable, and W1 comes first only because
it is the smaller unit.

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

**S5**: the same three gates. The builder's `--include` overrides `angular.json`'s glob, so the
focused loop is `npx ng test --no-watch --include='src/features/products/products.routes.spec.ts'` and
the same with `.../pages/product-variant-list-host/product-variant-list-host.spec.ts`; measured at the
S5 base, the routes file alone takes ~18 s and reports `1 passed / 19 tests`. Both files are the
change's own surfaces, so run them together before the full gate.

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

#### S2b — measured, and the forecast was low again

Measured on `ae0cfb1` with `git diff 90ef886..HEAD --numstat`. The base is the S2a tip rather than
`main`, because S2a is still an open PR and has not reached `main`.

| Bucket | Files | Changed lines |
|---|---|---|
| Source | 18 | **1.283** (365 of them the four deleted `product-supplier-edit` files) |
| Specs | 10 | **1.821** |
| This record | 1 | **143** |
| **Total** | **29** | **3.247** (2.431 insertions / 816 deletions) |

Against the ~650 source / ~850 spec / ~1.500 total forecast above — low by ~2×, and this time **not**
because of reindentation: `git diff -w` moves 16 lines out of 3.247, so the whitespace discount that
dominated S2a's numbers is irrelevant here. Three reasons:

- **T7 is a replacement, not a move, on both sides.** W1 deletes a 365-line page plus its 110-line
  spec and writes a 283-line dialog plus a 565-line spec; W2 writes a 175-line dialog plus a 312-line
  spec and rewrites the store-scoped page (251 → 190) and its spec (360 → 287). A literal "move"
  would have cost the dialog and its spec; the deletions are the other half of the count.
- **The dialog is where the behaviour now lives**, so it carries the coverage the page used to hold:
  write ownership, the close contract (D22), the error discrimination, and — after the verifier's F1
  finding — the unsaved-input guard with its four paths.
- **Specs are ~1,4× the source**, above S2a's ratio, because both dialogs are new components with no
  prior spec and because the two verification rounds added tests for defects that did not exist
  before (`switchMap` response ordering, the stale-selection submit window, the guard's arming paths).

**D20's threshold was ~1.000 changed lines and the measurement is 3.247, so the split is proposed and
pending a user decision:** PR A = `ddc6898` + `53c779f` + `717686a`, the supplier flow (2.057 changed
lines); PR B = `ae0cfb1`, the variant flow (1.202), chained on PR A's branch. Nothing is pushed either
way. **Resolved:** the user chose one PR; PR #156 merged as `d7c6e16`.

#### S3 — measured before implementation

Measured on `main` @ `d7c6e16` by a read-only mapping scout, every claim `file:line`-anchored, plus
the installed-framework reads above.

| Work unit | Files | Source lines | Spec lines | Total |
|---|---|---|---|---|
| W1 (T10 + T12: the stepper shell) | 3 | ~110 | ~230 | ~340 |
| W2 (T11 re-scoped evidence) | 1–2 | 0 | ~90 | ~90 |
| **S3 total** | **3–4** | **~110** | **~320** | **~430** |

Against the 4–6 files / 400–600 line forecast in the S3 row above. The forecast was **high** this time,
and the reason is D24: reusing the two list containers and the S2b dialogs removes the entire
association UI from the slice, so S3 has no new component, no new service call and no new form. What
is left is the step shell, the step-1 host contract and the exit semantics. The 1:1 spec-to-source
ratio the S2 sections predicted holds — the specs are where the stepper's behaviour is pinned, and
`product-edit.spec.ts` already builds the page with the **real** list containers against mocked
services, so the step tests inherit a working harness instead of needing a new one.

#### S3 — measured, and this time the forecast held

Measured on `1b8a507` with `git diff main...HEAD --numstat`.

| Bucket | Files | Changed lines |
|---|---|---|
| Source | 4 | **221** (product-edit.html 86, product-edit.ts 98, product-edit.scss 23, products-form.ts 14) |
| Specs | 2 | **358** (product-edit.spec.ts 325, products-form.spec.ts 33) |
| This record | 1 | **203** at the plan commit (`9855f2c`) |
| **Total** | **7** | **782** (733 insertions / 49 deletions) |

Against the ~110 source / ~320 spec / ~430 total forecast. The code half measured 579 lines against a
~430 forecast — low by ~1,35×, and this time the forecast error is in the *right* direction: S3 is the
first slice of this feature whose forecast was not low by 2× or more. `git diff -w` moves 12 of the
782 lines, so — unlike S2a — reindentation is not a factor; the diff is what it looks like. Excluding
this record, the review load is **579 changed lines over 6 files**, comfortably inside a single review
and well under D20's ~1.000 threshold, so no split was proposed.

**Where the forecast was low, and why.** The plan assumed the step-1 host contract would be free
because `onSaveProduct` already branched on `productData.id`. It is not free, for two reasons the
recon did not anticipate: (1) `ProductsForm`'s `isEditMode` is a `computed` over the `formGroup`
**input**, while `controls.id.value` is a plain property, so the id write-back D26 depends on cannot
flip the form's copy — the fix is an explicit `editMode` input, which is what the verifier's F1
finding forced (see the evidence log); and (2) the header had to start reading a signal the create
path never populated before. The step shell itself measured about what was forecast.

**The independent verification earned its keep.** It raised four findings, all closed in `1b8a507`:
F1 (medium) the copy never flipped; F4 (low-medium) the header went stale after a back-edit `PUT`; F3
(low) the `[completed]` test did not actually pin the binding, because the CDK marks the outgoing step
`interacted` and a step with no `stepControl` is completed once interacted; F2 (low) Material's
English `editableLabel` (`'Editable'`, visually hidden) reached the screen and only *happened* to be
the right Spanish word. F1 is the one worth remembering: it is the same class of defect as the
`MatAutocompleteTrigger.writeValue` microtask from S2b — a framework value that looks reactive and is
not.

#### S4 — measured before implementation

Measured on `main` @ `edbfb46` by two parallel read-only mapping scouts, every claim
`file:line`-anchored, plus the store-resolution and dialog-guard reads above.

| Work unit | Files | Source lines | Spec lines | Total |
|---|---|---|---|---|
| W1 (T13: form + dialog) | 4 | ~55 | ~150 | ~205 |
| W2 (T15: list + shell) | 5–6 | ~180 | ~300 | ~480 |
| **S4 total** | **9–10** | **~235** | **~450** | **~685** |

Against the 4–8 files / 400–700 line forecast in the S4 row above. The forecast is low on files and
roughly right on lines, and the reason is D31: the expansion is new behaviour in a container that has
none today (`product-variant-list.html:181` is the only `matRowDef`), so the list gains a column, a
detail row, a switch handler and a confirmation before it gains a single embedded panel — and the shell
needs a dirty channel it does not have (`product-edit.ts:338-340`). D20's ~1.000 threshold is not
reached by the forecast, so no split is proposed; the measurement at the tip is what confirms or
corrects it, exactly as S3's did.

**W1 measured (T13): 6 files, 374 insertions / 12 deletions = 386 changed lines** (source 119, specs
267). Against the ~55 source / ~150 spec forecast: the source half is ~2,2× and the spec half ~1,8×,
for the reason S1 already recorded — the evidence travels with the slice. Two thirds of the spec lines
pin behaviours the forecast did not name: the stale-server-error strip the reset turned out **not** to
need (the form's `serverErrors` effect had to learn to *remove* a `serverError` key, not only apply one,
but `reset()` already dropped it — the branch's real driver is the generic/409 failure path, which
clears the map without resetting the form, so a field error from an earlier attempt would otherwise sit
under a field the newest failure never named), the in-flight
guard on both submit paths, and the four exit paths of D34's close contract. The `{ emitEvent: false }`
flag also needed a dedicated `valueChanges` spy test: with `dirty.set(false)` in the same synchronous
block, no behavioural test could tell a plain `reset()` from a silent one — the first mutation control
on that line passed, and the test was added until it failed.

**W2 measured (T15): 9 files, 611 insertions / 14 deletions = 625 changed lines** (source 233, specs 392,
3 of the removed lines comment-only corrections). Against the ~180 source / ~300 spec forecast: the
source half is ~1,3× and the spec half ~1,3×, the closest this feature's forecasts have come, and the
remaining error is the table mechanics the recon could not see from the template alone —
`multiTemplateDataRows` is mandatory for a second default `matRowDef` (the CDK throws without it), the
detail row therefore renders once per data row, and the collapsed one needs real SCSS to add no height
because the specs run with `NoopAnimationsModule` and cannot rely on Material's animation state. Two
existing list assertions changed honestly rather than being weakened: the two column-header arrays gained
the new leading empty header, and one row-count selector became `tr.mat-mdc-row:not(.detail-row)` because
the detail row legitimately carries `mat-mdc-row`.

#### S4 — measured, and the forecast was low by 2×

Measured on `0622ed5` with `git diff edbfb46..HEAD --numstat`.

| Bucket | Files | Changed lines |
|---|---|---|
| Source | 9 | **474** (436 insertions / 38 deletions) |
| Specs | 6 | **929** (923 / 6) |
| **Code + specs** | **15** | **1.403** (1.359 / 44) |
| This record | 1 | **252** (241 / 11) |

Against the ~235 source / ~450 spec / ~685 total forecast. Both halves are ~2× low, and unlike S2a the
reason is **not** reindentation: `git diff -w` moves 12 of the 1.403 lines. It is the verification that
did not exist in the forecast. The two planned work units measured 1.011 lines together (W1 386, W2 625,
the W2 figure already recorded above); the two findings rounds added the remaining ~392. That is the
honest shape of this slice: the embed is one work unit, and making the dirty contract actually hold
across every destruction path — the four user-driven view changes, the row leaving the page, a read
error, and a request change that unmounts the table — is a second one. Two independent verifiers found
four defects and one overstated test in that contract, all closed, and both were worth their cost: the
first found the missing `trackBy` (a silent loss on *every* reload), the second found the last unguarded
destruction path.

**D20's ~1.000 threshold is exceeded, so the split is proposed and awaits the user's decision.**
PR A = `ce88623` + `1ebc552`, T13 (386 changed lines of code and specs): the variant dialog's
"Guardar y agregar otra", the in-place reset and the in-flight guard. PR B = `1b3fbe4` + `98081ae` +
`0622ed5`, T15 (1.017): the per-row stock/prices expansion, the shell's dirty aggregation, and the two
findings rounds that make the dirty contract hold. The record's own plan lines would follow their slice,
and B chains on A's branch or on `main`, since the two touch disjoint files. The user chose **one PR** for
S2b at 3.247 lines, so one PR here is a legitimate choice too — but at 1.403 it is the reviewer's call
rather than an obvious one, and D20 exists to force the question. **Resolved:** the user chose one PR, and
PR #159 carries it.

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
| 2026-09-23 | **S2b plan** | `ddc6898` | This record's S2b section: the reconciliation (the single-host finding for `app-product-variant-store-stock`, the correction to the S2a dialog-copy note, `getAllSuppliers(0, 1000)`'s single call site, the missing form-dialog precedent), decisions D18–D23, the W1/W2 decomposition, the S2b forecast, and the S2a status correction this file needed since PR #155 opened. |
| 2026-09-23 | **S2b W1 (T7 suppliers + T8)** | `53c779f` | **17 files, 1.292 insertions / 453 deletions = 1.745 changed lines.** New `product-supplier-dialog` (283 + 26 + 565); the form's `<mat-select>` becomes a `mat-autocomplete` with `searching`/`supplierSearch` and a membership guard (74 + 21 + 234); the list opens the dialog and drops `Router` (29 + 47); `suppliers/create` and `suppliers/edit/:supplierId` become redirects (45); the route spec pins them (+140); `remove-supplier-dialog` copy to voseo (+11) with its first spec (+62); the 365-line `product-supplier-edit` page and its 110-line spec are deleted. RED observed on every behaviour (three compile-time, so three mutation controls); focused products suite 23 files / 317 tests → 24 / 357. |
| 2026-09-23 | S2b W1 independent verification | `53c779f` | A read-only `gentle-ai-verify` subagent checked ten claims against the installed Material and router sources. Upheld: no `getAllSuppliers` call site survives, the dialog owns no route, the form stays presentational, the `switchMap` ordering pin is real (reproduced with a direct rxjs probe), the redirects are legal and non-vacuous, effective authorization is unchanged, nothing outside the slice moved, and the copy register is voseo. **One Medium finding:** in edit mode a typed-over field could submit the pre-typing `supplierId` on Enter (Material suppresses the value-accessor write while typing and only resets on a panel-closing action), and the membership guard could not catch it because the dialog keeps the current selection in its options. Also low: the seed read bypassed the cancelling stream, the `4014` justification wrongly included `data`, one route-spec loop was vacuously satisfiable, and one spec comment was false. |
| 2026-09-23 | **S2b W1 fix round** | `717686a` | All six findings closed. The stale-selection window is now blocked by a second, visible-text check on top of the membership one (three new tests, RED observed as `expected "spy" to not be called at all`, plus a mutation control on the guard condition); the seed read was folded into the same `switchMap` stream with a late-seed ordering test; the `4014`/`data` rationale is exact; the route-spec loop asserts the route is found first; the false comment and the dead-file references are gone. 24 files / 357 → 361 tests. |
| 2026-09-23 | **S2b W2 (T7 variants)** | `ae0cfb1` | **15 files, 845 insertions / 357 deletions = 1.202 changed lines.** New `product-variant-dialog` (175 + 19 + 312) carrying the global-definition write, the D22 close contract and the page's 409/`errors`/generic discrimination with its copy migrated to voseo; the variant form loses its `<h2>` (−7); `edit/:id/variants/create` becomes a redirect to `edit/:id/variants` (D19, role-safe, `storeId` preserved, no `canActivate`/`data`); `ProductVariantEdit` is reduced to the store-scoped editor (251 → 190) and delegates identity editing to the dialog (D21); `ProductVariantList` opens the dialog from the global view and keeps navigating from the store-scoped one (D18). RED observed on every behaviour (three compile-time, so three mutation controls); focused products suite 24 files / 361 tests → 25 / 378. |
| 2026-09-23 | S2b W2 independent verification | `ae0cfb1` (uncommitted at the time) | A read-only `gentle-ai-verify` subagent checked twelve claims. Upheld: the redirect targets the role-safe host and not `edit/:id`; the sales principal's chain to the per-store editor is intact end to end (stock search → route → page → panel → `?storeId=` read); `loadedBarCode` still keys the panel read and is refreshed from the dialog's result; the dialog owns no route, no store and no panel; the 409 copy still names both uniqueness rules; the `<h2>` removal is safe (the form has exactly one consumer); the reformat is confined to the touched button; nothing outside the slice moved; the disposition of the deleted page-spec tests is honest. **Findings:** the definition form lost the unsaved-changes protection the guarded route used to give it (Medium), one route-spec test could throw instead of assert (Low), the page subtitle was an imperative rendered before the panel existed (Low), and a claimed barcode/write hazard on the panel (Low). |
| 2026-09-23 | **S2b W3 (findings round)** | `ae0cfb1` (folded in) | The Medium finding is closed in both dialogs, identically: a dirty signal derived from `valueChanges`, `MatDialogRef.disableClose` set reactively from it, and a Cancel that opens the shared `ConfirmDialog` and closes with `null` only on confirmation — four paths pinned per dialog, RED observed, plus a mutation control that reverts the `disableClose` wiring and fails exactly the two Esc/backdrop tests. Also: the route-spec assertion (F2), the page subtitle now describes the page (F3), and the F4 disable was **reverted** after the write key was read (`product-variant-store-stock.ts:211` writes by `variantId`, so the claimed hazard does not exist — see the reconciliation above). 25 files / 378 → 396 tests. |
| 2026-09-23 | S2b W3 independent verification | `ae0cfb1` (uncommitted at the time) | A second read-only `gentle-ai-verify` subagent checked eleven claims with its own throwaway probe. Upheld: an edit-mode dialog starts clean and neither `setErrors({ emitEvent: false })` nor `markAllAsTouched()` arms the guard; `disableClose` tracks the state both ways and cannot stick; all four cancel paths are pinned in both dialogs; the `ConfirmDialog` data follows the guard's precedent with voseo copy; the success contract is untouched; F2 and F3 are fixed; the reverted F4 hazard was **not real** (write keyed by `variantId` + `storeId`, read keyed by barcode) with no residual hazard; nothing outside the slice moved; no weakened or vacuous assertion anywhere. Three low findings remain open as comments-level contracts, recorded under `### Raised by S2b`. |
| 2026-09-23 | **S2b gates (tip)** | `ae0cfb1` | `npm run lint` → `All files pass linting.`; `npm run build` → bundle generation complete, **850.87 kB** initial (S2a: 850.87 kB), exit 0; `npm run test:coverage:check` → **127 files / 2436 tests / 0 failures**, coverage **94.02/75.8/89.15/94.02** (thresholds 80/60/75/80). Delta over the S2a tip (`9d5eb5c`: 125 files / 2357 tests, 93.38/75.34/88.52/93.38): **+2 files, +79 tests, +0.64/+0.46/+0.63/+0.64**. The branch-coverage margin the S2a section flagged as thin (75.34 against 75) is now 0.8 pp. |
| 2026-09-23 | **S2b measured** | `ae0cfb1` | 29 files, 2.431 insertions / 816 deletions = **3.247 changed lines** (source 1.283, specs 1.821, this record 143), 16 of them whitespace. See `#### S2b — measured` above. |
| 2026-09-23 | **S2b delivered** | `2c7761a` | PR **#156** opened against `feat/product-workspace-tabs`: S2a is still an open PR, so the base is the S2a branch and GitHub retargets it to `main` when #155 merges. 29 files, 2.518 insertions / 817 deletions, label `enhancement`, `MERGEABLE`. Angular CI "Lint, Build & Test" **success** (run `35891274123`, the workflow's `pull_request` trigger has no branch filter, so a stacked base still runs it). Per D20 the two-PR split was proposed on the measured 3.247 lines; the user chose **one PR**. This docs-only commit does not re-trigger CI (the workflow's path filter is `life-control-app-angular/**`), and the code bytes CI verified are unchanged. **Correction (S4, measured):** that last sentence is wrong — GitHub evaluates a `pull_request` event's `paths` filter against the **whole PR diff**, not against the pushed commit, so a docs-only push to a PR that touches the package **does** re-trigger the workflow. S4's own docs commit proved it (run `35928763944`), and the code bytes CI verified were indeed unchanged, so only the mechanism was mis-stated.

| 2026-09-23 | **S3 plan** | `9855f2c` | Read-only mapping of the S3 surface by a `gentle-ai-explore` scout, every claim `file:line`-anchored, plus the installed-framework reads (`@angular/material` 20.2.10 `stepper.mjs`, `@angular/cdk` `stepper.mjs`). The `## S3 reconciliation` above: the T11 shape conflict with the reuse S2b shipped, the three now-false S2b claims, the page-agnostic dialogs, the absence of any `beforeunload` or post-persist navigation block, the guard's single contract, and the CDK's `completed`/`isNavigable`/`selectedIndex` semantics. User decisions D24-D27 taken on 2026-09-23; D28/D29 recorded as engineering decisions. Also corrects the status header (S2a #155 and S2b #156 merged into `main` @ `d7c6e16`). No source written. |
| 2026-09-23 | **S3 W1 (T10 + T11 re-scoped + T12)** | `59d02bc` | **4 files, 433 insertions / 19 deletions = 452 changed lines.** The create branch becomes a `mat-stepper`: `[linear]="false"` (required — see D28), `[completed]="productId() !== null"` on step 1, `<ng-template matStepContent>` on steps 2 and 3, and `(selectedIndexChange)` writing the new `stepIndex` signal. Steps 2 and 3 host the **real** `ProductSupplierList` / `ProductVariantList` with an explicit `.step-hint` before the product exists, and each gets a footer (`Atrás` / `Terminar` / `Siguiente`). `adoptCreatedProduct` sets `product`, `productId` and the form's `id` control and advances to step 2; the `updateProduct` branch keeps the operator in the stepper on a back-edit; `finish()` and `cancelForm()` implement D27. Focused products suite 25 files / 396 → **413 tests**. |
| 2026-09-23 | **S3 W1 mutation controls** | `59d02bc` | Three, each restored byte for byte afterwards (`git status` clean). `[completed]="false"` → **1 failed** (exactly the completion test). `this.stepIndex.set(0)` in the adopt path → **8 failed**. Step 3 made eager (`<ng-container>` instead of `<ng-template matStepContent>`) → **2 failed**: the laziness pin plus the step-2 footer test, which is the same laziness observed one layer up. |
| 2026-09-23 | S3 independent verification | `59d02bc` | A read-only `gentle-ai-verify` subagent checked sixteen claims against the installed Material/CDK sources rather than the commit message. Upheld: no route or guard change and the `create` route still carries `canDeactivate: [unsavedChangesGuard]`; the create branch no longer navigates and the retained `isEditMode()` guard is **defensible, not dead** (an edit-route load failure leaves an empty-id form); the write-back cannot arm the guard (`setValue` never marks dirty, `markAsPristine` clears it); the page injects no association service and opens no dialog; the step containers are the real ones; `[linear]="false"` is mandatory (`isNavigable()` = `isCompleted \|\| isSelected \|\| !linear`, and `_anyControlsInvalidOrPending` is inert without `linear`); the laziness pin is real because the inner `@if` would already pass; edit mode is behaviourally identical to `main`; no descope leak; exactly the five expected files moved; both rewritten pre-existing tests preserved their intent. **Findings: F1 (medium) D26's copy never flipped — `ProductsForm.isEditMode` is a `computed` over the `formGroup` input while `controls.id.value` is a plain property, so the write-back was invisible and the form said "Nuevo Producto" beside a `PUT`; F4 (low-medium) the header went stale after a back-edit `PUT`; F3 (low) the `[completed]` test did not pin the binding, because the CDK marks the outgoing step `interacted` and a step with no `stepControl` is completed once interacted; F2 (low) Material's English `editableLabel` reached the screen visually hidden and only *happened* to be the right Spanish word.** Its `could not verify` list is the same manual-browser checklist S2a and S2b already carry. |
| 2026-09-23 | **S3 W3 (findings round)** | `1b8a507` | All four closed. **F1**: `ProductsForm` gains an optional `editMode` input that overrides the `id`-control derivation (documented as the host's escape hatch for exactly this non-reactivity) and the stepper binds it to `productId() !== null`, which is reactive; the false comment in the D26 test is corrected and the copy is pinned by a DOM test on the form's own `<h2>` and submit label. **F4**: the update branch adopts the response, and the header refresh is pinned. **F3**: the completion test now opens step 2 *before* the product exists — the state where the binding disagrees with the CDK's derivation and is therefore load-bearing. **F2**: the component declares its own `MatStepperIntl`, so no Material English default can reach the screen. Three mutation controls fail exactly one test each: dropping the `[editMode]` binding, dropping the `[completed]` binding, and dropping the `PUT` response adoption. |
| 2026-09-23 | **S3 gates (tip)** | `1b8a507` | `npm run lint` → `All files pass linting.`; `npm run build` → bundle generation complete, **851.05 kB** initial (S2b: 850.87 kB), exit 0; `npm run test:coverage:check` → **127 files / 2458 tests / 0 failures**, coverage **94.03/75.80/89.19/94.03** (thresholds 80/60/75/80). Delta over the S2b tip (`ae0cfb1`: 127 files / 2436 tests, 94.02/75.8/89.15/94.02): **+22 tests, +0.01/+0.00/+0.04/+0.01**, same file count. |
| 2026-09-23 | **S3 measured** | `1b8a507` | 7 files, 733 insertions / 49 deletions = **782 changed lines** (source 221, specs 358, this record 203 at the plan commit), 12 of them whitespace. See `#### S3 — measured` above. |
| 2026-09-23 | **S4 plan** | `ce88623` | Read-only two-scout mapping of the T13/T15 surface at `edbfb46`, every claim `file:line`-anchored. The `## S4 reconciliation` above: the two record claims D21 invalidated (D17's post-save return and D8's drifted anchor), the panel's single host and its per-instance store resolution, the list's single `matRowDef`, the shell's form-only `hasUnsavedChanges`, the tab/sales role asymmetry that keeps the store-scoped page alive, and the dialog's dirty/reset constraints. User decisions D30–D32 taken on 2026-09-23; D33–D37 recorded as engineering decisions. Also corrects the status header (S3 merged as `a98426e` via PR #157, `main` at `edbfb46`) and records that S4 runs in a **fresh** worktree because `gh` removed the S3 one on merge. No source written. |
| 2026-09-23 | **S4 W1 (T13)** | `1ebc552` | **6 files, 374 insertions / 12 deletions = 386 changed lines** (source 119, specs 267). `ProductVariantForm` gains `saveVariantAndContinue` and a `saving` input; both handlers early-return while it is true, and the create-only `Guardar y agregar otra` button sits between `Cancelar` and the submit. `ProductVariantDialog` gains the read-only `saving` signal, `lastSaved`, two entry points over one private `submit`, and `resetForNextEntry()` (`reset({ … }, { emitEvent: false })` plus `dirty`, `serverErrors` and `generalError` cleared). The form's `serverErrors` effect now also **removes** a `serverError` key when the map becomes empty. The
motivation first recorded here was wrong and is corrected: `reset()` alone already drops `serverError`,
because it recomputes `errors` from the validators. The branch is load-bearing for a different, real path
— the generic/409 failure branch, which clears `serverErrors` to `{}` **without** resetting the form, so a
field error applied by an earlier attempt would otherwise stay on the control. Focused products suite 25
files / 418 tests → 25 / 435. |
| 2026-09-23 | S4 W1 mutation controls | `1ebc552` | Fourteen, each restored byte for byte (`git status` clean). Form: dropping the in-flight guard (1 failed), dropping `[disabled]="saving()"` (1), reverting the empty-map branch (1), rendering the button in edit mode (1), routing the continue output through `saveVariant` (1). Dialog: dropping the submit guard (1), a plain `reset()` (see below), dropping `dirty.set(false)` (1), dropping `serverErrors.set({})` (1), dropping `generalError.set(null)` (1), dropping `lastSaved` (1), reverting either `cancel()` close value (1 each), dropping the `[saving]` binding (1). **The plain-`reset()` control passed on its first run** — `dirty.set(false)` in the same synchronous block masked the emission — so a direct `valueChanges` spy test was added until that mutation failed; the flag is now pinned by an assertion that can distinguish it, instead of by a comment. |
| 2026-09-23 | **S4 W2 (T15)** | `1b3fbe4` | **9 files, 611 insertions / 14 deletions = 625 changed lines** (source 233, specs 392, 3 of them comment-only corrections to the stale D17 comments). The table gains a leading `expand` column in both column sets and a `multiTemplateDataRows` detail row hosting the **real** panel with the row's stored barcode; `expandedVariantId`/`panelDirtyState` drive a `dirtyChange` output, and `ProductEdit.variantPanelDirty` is aggregated into `hasUnsavedChanges()` (D37). The store-scoped row action, the routes and the panel are untouched (D18/D36). Two existing assertions were retargeted honestly, not weakened: both header arrays gained the new empty header, and one row-count selector became `tr.mat-mdc-row:not(.detail-row)`. Focused list suite 51 → 66 tests, `product-edit` 54 → 57. |
| 2026-09-23 | S4 first independent verification | `1b3fbe4` | A read-only `gentle-ai-verify` subagent checked fifteen claims against the installed Material/CDK and `@angular/forms` sources and ran the three gates (all PASS at that tip: 127 files / 2493 tests, coverage 94.06/75.85/89.27/94.06). Upheld: the reset really cannot re-arm `dirty`/`disableClose` and its spy test is discriminating; the `lastSaved` close contract holds on every exit path and neither existing consumer can receive an unexpected entity; the in-flight guard is real on both sides; the empty-map branch removes only `serverError` and keeps other validators; no descope leak; the `multiTemplateDataRows` requirement; D18 and the comment-only corrections. **Findings: F1 (HIGH) the dirty-panel prompt existed only in the expand toggle, so pagination, page size, `Mostrar deshabilitadas` and the store-scope toggle all discarded a dirty panel with no prompt; F2 (LOW-MEDIUM) no `trackBy`, so every reload rebuilt every row view, destroying the open panel and stranding the shell's flag at `true`; F3 (LOW) an overstated test description; F4 (INFO) the W1 row's motivation for the empty-map branch was wrong.** It also reproduced F1/F2 with four throwaway probes and left the worktree byte-identical. |
| 2026-09-23 | **S4 findings round 1** | `98081ae` | F1–F3 closed, F4 corrected in this record. All four view changes now route through one `guardViewChange` helper, and a cancelled prompt restores the rendered control (`MatPaginator.pageIndex`/`pageSize`, `MatSlideToggle.checked` — checked against the installed sources to emit no `page`/`change`, so the revert cannot re-enter the handler). `trackByVariantId` plus keeping the loaded page mounted during a same-request reload (`isLoading() && !variants()`) fixes F2, and a read error now clears the expansion instead of stranding the flag. F3's description now claims only what jsdom observes. Also renames the duplicated `id="variantGrad"` the editor blocks on, and corrects the comment on the form's empty-map branch. Focused list suite 66 → 77, products 453 → 464. |
| 2026-09-23 | S4 findings round 1 mutation controls | `98081ae` | Three, each restored byte for byte: `guardViewChange` forced to always apply (**10 failed / 77** — the four new prompts, the three revert tests and the three pre-existing guard tests), the old `loading()` definition (1 failed, the panel-survival test), an unstable `trackBy` key (1 failed, the same test, `expected '' to be '9'`). |
| 2026-09-23 | S4 second independent verification | `98081ae` | A read-only `gentle-ai-verify` subagent checked the findings delta and ran three runtime-override mutation controls in a throwaway spec (no tracked file edited). Upheld: all four guards real and discriminating, the cancel path provably cannot re-enter its handler, `onRetry` genuinely needs no guard, the `loading()` change does **not** regress the params-change skeleton (verified against `@angular/core` `resource.mjs`: a request change drops the value), `trackBy` preserves the row view, the read-error branch is the only clearer in the error state, and the spec's only removed line is the old test title. **Findings: A (MEDIUM-LOW) a resolution-driven request change unmounted the table and destroyed the panel without clearing the expansion or the flag; B (LOW) `disableVariant` on the expanded row is a genuine silent-loss path; C–H record-only errors.** It also reproduced A end to end with a throwaway probe. |
| 2026-09-23 | **S4 findings round 2** | `0622ed5` | Finding A closed: "no loaded page" now counts as "the expanded row is not present" in the same clearing effect, so a request change that unmounts the table keeps the destroyed panel and the emitted `dirtyChange(false)` in step. B is recorded as a deliberately accepted consequence and the dialog-level test gap for the empty-map branch as a follow-up (both in `### Raised by S4`); C, D, H are corrected in this record and E in the form's comment. Focused list suite 77 → 79, products 464 → 466. |
| 2026-09-23 | **S4 gates (tip)** | `0622ed5` | `npm run lint` → `All files pass linting.`; `npm run build` → bundle generation complete, **851.05 kB** initial total, exit 0; `npm run test:coverage:check` → **127 files / 2506 tests / 0 failures**, coverage **94.07/75.94/89.22/94.07** (thresholds 80/60/75/80), `[check-coverage] Cobertura dentro de los umbrales. OK`. Delta over the S3 tip (`1b8a507`: 127 files / 2458 tests, 94.03/75.80/89.19/94.03): **+48 tests, +0.04/+0.14/+0.03/+0.04**, same file count. The branch margin S2a flagged as thin is now 0.94 pp. |
| 2026-09-23 | **S4 measured** | `0622ed5` | 15 files, 1.359 insertions / 44 deletions = **1.403 changed lines** (source 474, specs 929), plus 252 lines of this record. See `#### S4 — measured` above; D20's threshold is exceeded and the split is proposed. |
| 2026-09-23 | **S4 delivered** | `d953e56` | PR **#159** opened against `main`: `feat/product-variant-tab-stock` → `main`, 16 files / 1.644 insertions / 55 deletions, label `enhancement`, `MERGEABLE`, title `feat(products): embed the stock editor in the Variantes tab and add "Guardar y agregar otra" (S4)`. Angular CI *Lint, Build & Test* **success** on `d953e56` (run `35927601959`, 4m14s). The user chose **one PR** over the split this record proposed at 1.403 changed lines. Nothing is merged, and the branch and worktree stay until the merge decision, because `gh` deletes both on a merge that carries `--delete-branch`. |
| 2026-09-23 | S4 delivery docs commit | `49ba68e` | `odd/tasks/product-create-ux.md` only: the status header and the constraints corrected from "nothing is pushed and no PR is open" to the real state, and the S2b row's wrong claim about the CI path filter corrected (see that row). The commit re-triggered Angular CI despite touching no package file — run `35928763944` **success** — which is the measurement that proves the correction. |
| 2026-09-23 | **S4 merged** | `84a17ef` | PR **#159** merged into `main` as a real two-parent merge commit (parents `edbfb46` + `7a85a12`), 2026-09-23T23:34:13Z. The merge deleted the S4 branch and its worktree, as the record forecast; `origin` carries `main` only and no PR is open. |
| 2026-09-23 | **S5 plan** | (this commit) | Read-only inventory of every pending ODD item plus a read-only mapping of the `edit/:id/variants` surface at `84a17ef`, every claim `file:line` anchored. The `## S5 reconciliation` above: the four guarded routes and the unguarded sibling, the container's `dirtyChange` contract and the host's missing binding, the guard's `CanDeactivateFn<UnsavedChangesAware>` shape and the fact that the host — not the container — is the activated component. User authorization taken the same day; D38–D40 recorded as engineering decisions. Also corrects the status header and the constraints bullet from "PR #159 is open, nothing is merged" to the merge commit. No source written. |

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
- PR #159 is **merged** into `main` as `84a17ef`, and `gh` deleted both the S4 branch and its worktree
  as forecast. S5 runs in a fresh worktree, `~/workspace/LifeControl-worktrees/fix-variant-list-host-guard`,
  created with `herdr worktree create --base main --no-focus`; its own `node_modules` came from
  `npm ci --legacy-peer-deps` (777 packages, 14 s, shared `~/.npm` cache).

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
  in that slice rather than discovering it at the gate. **Closed in S2b:** the tip measures **75.8**,
  up 0.46 pp from S2a, so the thin margin is gone.

### Raised by S2b

- **The dialogs' unsaved-input guard is structural, not defensive.** Both dialogs arm their dirty
  signal from a bare `valueChanges` subscription (`product-supplier-dialog.ts:107-110`,
  `product-variant-dialog.ts:92-94`) and rely on "the form is never patched after construction". The
  per-store panel is defensive by contrast (`product-variant-store-stock.ts:169` patches with
  `{ emitEvent: false }`). A future programmatic `patchValue`/`setValue` without that flag would arm
  the close prompt on an untouched form. Verified clean today by an independent probe, and left as a
  comment-level contract rather than a new guard.
- **`disableClose` is pinned as a property, not as a dismissal.** The specs assert the flag both ways
  (`product-variant-dialog.spec.ts:240,247` and its supplier twin), but no test performs a real Esc or
  backdrop dismissal: the `MatDialogRef` is a mock. Source inspection shows the flag cannot stick
  (a confirmed cancel destroys the dialog), so this is a gap in the evidence, not in the behaviour.
- **Three redirect routes now have no real-router test.** D13's route, plus D19's two supplier ones
  and `edit/:id/variants/create`, are all pinned by invoking the `redirectTo` function directly under
  `runInInjectionContext`. That pins the function, never the recognizer's choice, and it is the same
  gap S2a raised — now four routes wide. A real `Router.navigateByUrl` test in this suite would close
  all four at once; the repo still has no precedent for one.
- **The workspace shell swallows a product-load failure.** `ProductEdit` reports it with
  `console.error` only (`product-edit.ts:131`), with no banner. The deleted `product-supplier-edit`
  page spec was the last test that a product-fetch failure surfaced to the operator; the behaviour
  moved to the shell in S2a and is now unpinned and invisible. S2a code, not S2b's diff.
- **The store-scoped page reaches its definition edit through the dialog.** By design (D21), but it
  means a deep link into `edit/:id/variants/edit/:variantId` shows the per-store panel plus one action,
  not an inline identity form. Worth a look when T15 moves the panel into the tab.
- **`MatPaginatorIntl` labels are still English repo-wide** (carried from S2a, unchanged by S2b).

### Raised by S3

- **The stepper's step is not in the URL, so the flow does not survive a reload.** `/products/create`
  after step 1 restarts at step 1 with an empty form while the product already exists and is listed.
  Accepted rather than accidental: putting the step in `?step=` would need a re-read of the product to
  rebuild step 1, which is the workspace's job. Recorded so a later slice can decide whether the create
  flow deserves resumability.
- **Double submit is still possible.** Neither `ProductsForm` nor the stepper disables the submit
  button while the create request is in flight, so a fast double click can POST twice and the second
  attempt surfaces a duplicate-SKU 409. Pre-existing on the create page; S3 neither worsens nor fixes
  it. The durable fix is an in-flight input on `ProductsForm`, which would also close the same gap in
  the two association forms.
- **`ProductsForm`'s default `isEditMode` derivation is still fragile.** `controls.id.value` is a plain
  property, so any future host that mutates the `id` control without replacing the form group will hit
  F1 again. The `editMode` input is the escape hatch; making the derivation reactive, or dropping it in
  favour of the input, is the durable fix.
- **The `MatStepperIntl` provider is component-local.** A future stepper will need its own, exactly as
  a future table needs `MatPaginatorIntl`. One repo-wide provider for both belongs in its own slice;
  S3 only stopped its own surface from depending on an English default.
- **The edit route with a failed product load still holds an empty-id form.** `ProductEdit` now binds
  `[editMode]="productId() !== null"`, so on that path the copy says "Editar Producto" while
  `onSaveProduct`'s `productData.id === ''` check would still POST. Pre-existing broken path — the load
  error is only `console.error`ed, itself a recorded follow-up — now marginally more visible.
- **No real-browser or real-router test covers the stepper either.** The step advance, the header click
  before the product exists, and the guard's behaviour on a route navigation are pinned at the
  component level only. The four redirect routes' real-router gap (S2a/S2b) is unchanged and remains
  the single highest-value test to add.

### Raised by S4

- **The `edit/:id/variants` host route has no `canDeactivate`.** `ProductVariantList` is hosted there
  (`pages/product-variant-list-host/`) and now carries the inline per-store panel, so an operator on that
  route can edit stock and prices and navigate away with no prompt: `hasUnsavedChanges()` exists only on
  `ProductEdit`, and that route does not load it. The sales principal reaches the panel through
  `edit/:id/variants/edit/:variantId`, which **is** guarded, so the gap is the list host specifically.
  Closing it means giving that host a guard of its own — a route-level change, deliberately outside
  T15's surface. **Closed in S5 (T16)**: the host forwards the container's `dirtyChange` and the route
  gained `canDeactivate`. The bullet stays as the record of the gap.
- **The store-scoped view now offers two affordances for the same edit.** The row action still navigates
  to the store-scoped page (D18, untouched) while the expand toggle opens the same panel in place. Both
  are honest; collapsing them into one needs a decision about the page's role for `lc-admin`, which D36
  deliberately left alone.
- **A collapsed detail row is still a DOM row.** `multiTemplateDataRows` renders one `tr.detail-row` per
  data row with an empty cell, so the table carries twice the rows and the specs must select data rows
  with `:not(.detail-row)`. Correct and cheap at 12 rows per page; worth revisiting if a page size grows.
- **`product-variant-list.html` had a duplicated `id="variantGrad"`** across its two empty-state SVGs.
  Pre-existing and unrelated to S4, but the editor blocks on it, so the findings round renamed the
  empty-state one to `variantGradEmpty` (2 lines, no behavioural effect). Recorded because it is scope
  the slice took on outside its named findings.
- **`disableVariant` on the expanded row is a silent-loss path, accepted on purpose.** Disabling the row
  whose panel is open — while `includeDisabled` is off, or in the store-scoped view, which always
  filters `enabled = true` — drops the row and destroys the dirty panel with no prompt about the pending
  stock/prices. Accepted rather than closed: the operator has just confirmed a destructive action on
  **that same variant**, the disable dialog is itself a confirmation, and the alternative is two stacked
  dialogs for one click. A later slice that wants it closed should extend the disable dialog's data with
  the pending-edit warning instead of layering a second prompt.
- **The empty-map `serverErrors` branch is pinned only at the form's unit level.** The dialog-level
  integration is reachable — attempt 1 returns an `errors` map, attempt 2 returns a generic 409, and
  `handleServerError` clears the map without resetting the form — and no test covers that sequence; the
  branch's own contract is pinned by `product-variant-form.spec.ts`.
- **The panel's store is still resolved per instance** (D36). With one expanded row at a time the cost is
  at most one extra `GET /api/profile` per expansion, and the panel's store could in principle differ
  from the list's store-scoped columns if the profile changes between the two resolutions. A `storeId`
  input on the panel is the durable fix if multi-expansion ever lands.

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
