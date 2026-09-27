# ODD feature: sales-scan-ux

**Status**: implemented — the three work-unit commits and their gate results are in the evidence log below.
Open in this record: the manual scan-coherence pass (no automated check covers it), plus follow-ups
F1–F5. No claim is made here about push or PR state.
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
| D8 | In-flight writes are tracked as a **set of row ids**, not a single row index. | Two independent reasons, both found while implementing: (a) local row state only absorbs a write when its response arrives, so a second edit to the same row reads the pre-edit value and PATCHes it back, silently undoing the accepted write — the write lock must therefore stay on the row until its own write settles; and (b) a single index cannot represent two open writes, so settling one row would re-enable another whose write was still open. Keying by id also survives the index shift that removing another row causes. |
| D9 | The charge path calls `syncOrder`, not `loadOrder`. | It is a mutation path, and D1 says no mutation path may re-arm the page gate. Leaving it would have re-armed the invariant that D1 exists to establish and reproduced the same blink after charging. |

## Plan revision

Two revisions were made, both before the code they affected was committed:

1. **Four tasks to three.** The first version specified a fourth task introducing a synthetic `rowKey`
   field on `ItemTableRow` for `trackBy` identity. It was dropped before any code was written: no path on
   this screen creates a row without a server id (`addItem` returns the created item, the initial load
   returns ids), so the field would have been speculative generality. `trackBy` folds into T3 and keys on
   the server id.
2. **T3's write lock was re-specified mid-implementation.** The task as first written said "disable only
   the in-flight row (`isSaving() === i`)". Implementing it surfaced the stale-payload path in D8: that
   form re-enables a row whose own write is still open, and editing it sends the pre-edit value back. The
   user chose the row-id scoping over reverting to a global disable, so T3 carries a larger diff than
   originally scoped. The first form was never committed.

## Tasks

### T1 — The page stops being the unit of loading

Introduce `initialLoading` (bootstrap-only, gates the template) and `syncOrder` (background, does not).
`loadOrder` is reached only from `ngOnInit`; the `addItem` success path calls `syncOrder`. Line-item
reconciliation merges by server id and preserves the row with an in-flight edit, instead of replacing the
array wholesale.

- Files: `pages/sales-order-edit/sales-order-edit.ts`, `.../sales-order-edit.html`, `.../sales-order-edit.spec.ts`
- Evidence: spec asserting `initialLoading()` stays `false` across an add, that the form stays the same DOM
  node, and that the background sync issues its GET while the content remains rendered. Mutation-checked:
  reintroducing a gate on `syncing` fails it with `expected null not to be null`.

### T2 — The scan input never unmounts and never loses focus

`ProductVariantSelector` gains a public `focusInput()`; it restores focus after a selection and after an
auto-add. The parent holds a `viewChild` and calls it after every add, successful or not.

- Files: `components/product-variant-selector/product-variant-selector.ts`, `.html`,
  `pages/sales-order-edit/sales-order-edit.ts`, `.html`, both specs
- Evidence: spec asserting `document.activeElement` is the scan input after a select and after an add.
  Mutation-checked: removing `input.focus()` fails both with `expected <body> to be <input>`.

### T3 — A per-row write lock, and local confirmation

In-flight writes are a set of row ids (D8); only those rows' controls are disabled, and several may be
open at once. `mat-table` tracks rows by server id so a single add stops re-creating every row, and a
newly inserted row gets a short arrival highlight.

- Files: `components/sales-order-item-table/sales-order-item-table.ts`, `.html`, `.scss`,
  `pages/sales-order-edit/sales-order-edit.ts`, `.html`, specs
- Evidence: specs asserting that a row without an open write stays enabled, that two open writes are
  tracked independently, and that a second edit to a row with its own write open is blocked.
  Mutation-checked: removing `[trackBy]` fails the row-identity spec with `expected … to include <tr>`;
  forcing every row enabled fails the stale-payload spec with `expected false to be true`.
- **Not automated:** the arrival highlight is a pure CSS animation on `tr.mat-mdc-row`. No spec asserts
  it — a unit test there would assert an implementation detail, not behavior. It is verified by
  inspection and by the manual pass listed under the acceptance criteria. The `min-height` this task
  originally specified was dropped: `.add-line-item` sits above the table and the table grows downward,
  so nothing collapsed once T1 removed the page gate.

## Evidence log

The commits below are the gate-verified bytes: the `lint-staged` pre-commit hook ran at each commit and
left the working tree clean, so no byte changed after the run.

| Date | Task | Commit | Gate evidence |
| --- | --- | --- | --- |
| 2026-09-26 | T1 | `3f2b96f` (tree `b4ad519e`) | `npx ng test --include "…/sales-order-edit/*.spec.ts"` → 1 file / 72 tests pass. `npm run lint` → all files pass. |
| 2026-09-26 | T2 | `3975818` (tree `851216e5`) | `npx ng test --include "src/features/sales/**/*.spec.ts"` → 10 files / 218 tests pass. `npm run lint` → all files pass. |
| 2026-09-26 | T3 | `273b97d` (tree `5cadfa5d`) | `npx ng test --include "src/features/sales/**/*.spec.ts"` → 10 files / 225 tests pass. `npm run lint` → all files pass. |

### Gate results on the final commit (`273b97d`)

| Control | Command | Status | Observed result |
| --- | --- | --- | --- |
| Lint | `npm run lint` | **PASS** | `All files pass linting.` |
| Build | `npm run build` | **PASS** | `Application bundle generation complete. [10.569 seconds]`; `sales-order-edit` chunk 33.73 kB / 7.86 kB. |
| Unit + coverage gate | `npm run test:coverage:check` | **PASS** | 130 files / **2571 tests** / 0 failures; statements 94.11, branches 75.97, functions 89.28, lines 94.11 against the enforced 80 / 60 / 75 / 80 read from `scripts/check-coverage.mjs`. `[check-coverage] Cobertura dentro de los umbrales. OK`. |
| E2E | `npm run test:e2e` | **FAIL — pre-existing, environmental** | 4 failed / 10 passed. None of the failures is in a sales spec and none can be reached from this diff: the failing specs are `login.spec.ts` (2), `navigation.spec.ts` (1), `purchases-receipts.spec.ts` (1). Control run on clean `main` @ `37c1618` with the same command (`E2E_PORT=4311`, `--workers=2`): **the same 4 specs fail, 4 failed / 10 passed** — identical, so the failures are not attributable to this change. Two independent environment causes: (a) `reuseExistingServer: !CI` in `playwright.config.ts` makes Playwright reuse the Docker container `lifecontrol-dev-life-control-app-angular` on `:4200`, so the default run never serves this worktree's code at all; (b) on a fresh port the mocked Keycloak round trip does not complete and the run lands on the real sign-in page. |
| E2E relevance to this change | — | **GAP** | There is no sales spec in `e2e/specs/`, so the e2e suite exercises none of this diff. It is evidence for the environment, not for this change. See F1 and F5. |

### Manual verification still owed

These were promised as acceptance criteria and are **not** covered by any automated check:

- 10 consecutive scans without touching the mouse, with no visible flicker, on a running stack.
- The arrival highlight reads as "this row was just added" and does not replay on untouched rows.

## Review workload

`git diff --stat main...HEAD` → **11 files / +704 −99 = 803 diff lines**, against the repo's configured
review budget of **400** (`reviewBudgetLines` in `.pi/gentle-ai/sdd-preflight.json`). Per commit:

| Commit | Diff lines | Within budget |
| --- | --- | --- |
| `3f2b96f` (T1) | 297 | yes |
| `3975818` (T2) | 117 | yes |
| `273b97d` (T3) | 395 | yes |

Each commit is independently reviewable and each fits the budget; the branch as a whole does not. The
commits are ordered so that each one stands alone on `main`. Chained PRs (T1 → T2 → T3) are therefore the
recommended delivery shape, and this is surfaced rather than decided: push, PR and merge are the user's
call.

## Follow-ups opened by this record

| # | Follow-up | Why it is separate |
| --- | --- | --- |
| F1 | A Playwright spec for the sales order screen (scan → focus retained, no extra GET). | Needs a sales-order + Keycloak mock surface in `e2e/mocks/api.ts`; a mock-infrastructure unit of work, not a behavior fix. |
| F2 | De-duplicate the two `/product-variants/search` requests per query in search mode. | A separate behavior (request count, not UX stability); fixing it touches the debounce/Enter contract and its own specs. |
| F3 | Make `POST /sales-orders/{id}/items` return the resulting order status so the background GET disappears entirely. | A contract change: high risk, needs a compatibility note and the backend gate. Out of the frontend-only scope chosen for this record. |
| F4 | A row edit applied optimistically on input would make the merge's `savingRowIds` guard load-bearing; today it is defensive. The residual race is real either way: a `syncOrder` GET that started before a row's own PATCH settled can return the older row and overwrite it. | Ordering the reconciliation against in-flight writes is its own change with its own specs; the write lock from T3 removes the operator-reachable path, not the race. |
| F5 | `npm run test:e2e` is declared in `package.json` but has no step in `.github/workflows/angular-ci.yml`, and it cannot be trusted locally while `reuseExistingServer` points at the Docker container on `:4200`. | A CI/pipeline change: high risk under `assets/risk-classification-matrix.md`, needs a rollback plan and is independent of this fix. |