# ODD feature: sales-scan-ux

**Status**: implemented — four work-unit commits on `feat/sales-scan-ux`, recorded in the evidence log
below. This header makes no claim about push or PR state; see the evidence log.
**Repository**: LifeControl — Angular frontend (`life-control-app-angular/**`) only. No backend edit, no
contract change, no schema, no auth, no route, no build config, no CI change.
**Created**: 2026-09-26
**Branch**: `feat/sales-scan-ux` · **Base**: `main` @ `37c1618` · **Worktree**:
`~/workspace/LifeControl-worktrees/feat-sales-scan-ux` (herdr `w1M`), created with the procedure in
`.agents/skills/project-conventions/references/worktrees.md`.
**Requested by**: the user, after being shown the root cause of the scan-time screen flash on the sales
order screen (2026-09-26). Scope fixed to frontend-only by explicit choice; the backend variant is a
separate follow-up.
**Risk**: **low in blast radius, high in operator impact.** No auth, no role, no guard, no contract, no
schema, no data migration. OnPush components, signals, Vitest specs; the change is a loading-state split
plus focus and feedback handling. Classified **medium** under
`assets/risk-classification-matrix.md` ("new feature behind an existing pattern") because it changes
runtime behavior of a screen the operator uses continuously; the required controls for medium are
applied and recorded in the evidence log.

> **How to read the line numbers.** Citations without a commit name are against the base commit
> `37c1618`. Post-commit line numbers move by the size of each hunk.

## Problem

Scanning a barcode on `/sales/orders/:id` makes the whole screen blink and the scan field lose focus.

Observed by the user: "cuando escaneo un producto se ve el loading por un instante y como que flashea
toda la pantalla."

## Root cause

Three unrelated loading concerns were fused into a single `loading` signal that gates the whole page.
Adding a line item therefore tears the page down and rebuilds it:

1. `sales-order-edit.ts:416` — the `addItem` success handler calls `this.loadOrder(orderId)`.
2. `loadOrder` (`:347-348`) sets `this.loading.set(true)`.
3. The template holds two mutually exclusive gates: `sales-order-edit.html:48` renders the skeleton
   only when `loading() && !loadedOrder()`, and `sales-order-edit.html:73` renders the form, the items
   table and the scan selector only when `!loading()`. Because `loadedOrder()` is already populated on
   a per-scan reload, the skeleton never appears: the content is simply **removed** and re-added with no
   transition. That is the flash, and it is a layout collapse, not a spinner artifact.
4. The rebuilt subtree is a **new** `ProductVariantSelector` instance, so the input loses focus, the
   autocomplete panel closes and `searchQuery` resets (`product-variant-selector.ts:110-113`). On a
   point-of-sale screen this is worse than the visual blip: the operator must click again before the
   next scan.
5. The row appended at `:413` from the POST response is discarded ~100 ms later by the reload that
   follows it.
6. For the same window, `savingIndex` is non-null, and the table disables **every** row's inputs and
   remove buttons via `isSaving() === i || isSaving() !== null`
   (`sales-order-item-table.html`), so the whole table reads as frozen.

Secondary findings, fixed or deferred as noted per task:

- `lineItems()` produces a new array per update and is bound as a plain array to `[dataSource]`, so
  `mat-table` re-renders every row on each add; with 20-30 items this amplifies the blink. — **T4**
- `ProductVariantSelector` posts two requests for one query in search mode, because the debounce
  `effect` and `onSearchEnter` both hit `/product-variants/search`. — **deferred**, follow-up F2.

## Design decisions

| # | Decision | Rationale |
| --- | --- | --- |
| D1 | The page-level gate becomes `initialLoading`, and it is set **only** by the bootstrap load (`ngOnInit`). No mutation path may set it again. | The invariant that removes the defect class, not just this instance: a mutation can never unmount the screen. |
| D2 | Background refreshes use a separate, **non-gating** `syncOrder(id)` that updates `loadedOrder` only. | The status chip and `isDraft` need to follow the server (Draft → Pending on the first item) without paying for a page rebuild. |
| D3 | `syncOrder` merges line items by stable key rather than replacing the array, and preserves values for rows with an in-flight edit. | A background GET must never overwrite bytes the operator is typing, nor resurrect a row they are deleting. |
| D4 | The scan input is never inside a gate that can unmount it, and the parent explicitly restores focus after an add. | Focus retention is the core POS requirement; the visual flash is a symptom of the same bug. |
| D5 | Confirmation feedback is local (row highlight, per-row pending) instead of global (page spinner, whole-table disable). | The operator's attention is at the row they just scanned; a page-level indicator is both slower to read and the thing that broke the layout. |
| D6 | Row identity for `trackBy` is the server `id`. No synthetic client-side key is introduced. | Every row on this screen arrives from the API — `addItem` returns the created item with its id, and the initial load returns ids — so a simulated key would be speculative generality for a path that does not exist. A plan revision that had specified one was dropped before any code was written; see the revision note below. |
| D7 | The Playwright acceptance criterion originally proposed for this fix is **not** included. | `e2e/mocks/api.ts` has no sales-order surface and `e2e/specs/` has no sales spec; building one is a separate unit of work (auth + API mocks), and both behaviors are asserted deterministically in Vitest. Recorded as follow-up F1 rather than done badly. |

## Plan revision

The first version of this record specified four tasks, the last of which introduced a synthetic `rowKey`
field on `ItemTableRow` for `trackBy` identity. That task was dropped before any code was written: no
path on this screen creates a row without a server id (`addItem` returns the created item, the initial
load returns ids), so the field would have been speculative generality. `trackBy` is folded into T3 and
keys on the server id. The plan is now three tasks; task numbering in this file is the final numbering.

## Tasks

### T1 — The page stops being the unit of loading

Introduce `initialLoading` (bootstrap-only, gates the template) and `syncOrder` (background, does not).
`loadOrder` is reached only from `ngOnInit`; the `addItem` success path calls `syncOrder`. Line-item
reconciliation merges by server id and preserves the row with an in-flight edit, instead of replacing the
array wholesale.

- Files: `pages/sales-order-edit/sales-order-edit.ts`, `.../sales-order-edit.html`, `.../sales-order-edit.spec.ts`
- Evidence: spec asserting `initialLoading()` stays `false` across an add, that the form stays mounted,
  and that the background sync issues its GET while the content remains rendered.

### T2 — The scan input never unmounts and never loses focus

`ProductVariantSelector` gains a public `focusInput()`; it restores focus after a selection and after an
auto-add. The parent holds a `viewChild` and calls it after every successful add.

- Files: `components/product-variant-selector/product-variant-selector.ts`, `.html`,
  `pages/sales-order-edit/sales-order-edit.ts`, `.html`, both specs
- Evidence: spec asserting `document.activeElement` is the scan input after a select and after an add.

### T3 — Local confirmation instead of a global freeze

Only the in-flight row is disabled; the newly added row gets a short highlight animation; the items area
reserves its height so nothing collapses; and `mat-table` tracks rows by server id so a single add stops
re-rendering every row.

- Files: `components/sales-order-item-table/sales-order-item-table.ts`, `.html`, `.scss`,
  `pages/sales-order-edit/sales-order-edit.ts`, `.html`, `.scss`, specs
- Evidence: spec asserting a row other than the in-flight one stays enabled and that the track-by key is
  the row id; SCSS animation present.

## Evidence log

Dated rows are appended as each task closes. Each row names the commit and the exact gate command with
its observed result.

| Date | Task | Commit | Gate evidence |
| --- | --- | --- | --- |
| — | — | — | — |

## Follow-ups opened by this record

| # | Follow-up | Why it is separate |
| --- | --- | --- |
| F1 | A Playwright spec for the sales order screen (scan → focus retained, no extra GET). | Needs a sales-order + Keycloak mock surface in `e2e/mocks/api.ts`; a mock-infrastructure unit of work, not a behavior fix. |
| F2 | De-duplicate the two `/product-variants/search` requests per query in search mode. | A separate behavior (request count, not UX stability); fixing it touches the debounce/Enter contract and its own specs. |
| F3 | Make `POST /sales-orders/{id}/items` return the resulting order status so the background GET disappears entirely. | A contract change: high risk, needs a compatibility note and the backend gate. Out of the frontend-only scope chosen for this record. |