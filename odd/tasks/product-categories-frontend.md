# ODD feature: product-categories-frontend

**Status**: implemented — the Categories screen S3 deferred by `odd/tasks/product-categories-backend.md`
now exists: `/products/categories` lists the company's tree, and creates, edits, enables, disables and
soft-deletes its categories and subcategories through the endpoints that record shipped.
**Created**: 2026-10-24 · **Risk**: high — the screen writes a tenancy-scoped catalog (item 1 and item 2
of the Task Size list): a round trip that drops or invents `companyId`, or an edit that omits `enabled`,
is a cross-tenant write or a silent cascade, not a cosmetic defect. Both are pinned by tests.

## Objective

Ship the Angular screen of the product category tree: choose a company, read its categories as a
depth-first tree, and create, edit, enable, disable and soft-delete a category or a subcategory, with
the picker refusing the choices the API would refuse.

## Problem

The products administration dashboard already renders a `Categories` card that points at nothing —
`route: null, disabled: true` (`life-control-app-angular/src/features/products/pages/products-admin/products-admin.component.ts:36-42`)
— so the entry point exists and leads nowhere. The backend half of the same feature landed in
`odd/tasks/product-categories-backend.md` (PRs #610, #608, #609) and answers
`/api/companies/{companyId}/product-categories`, which no frontend code calls: `grep -rn categor`
over `life-control-app-angular/src` finds only that card.

## Why this shape

- **The company scope is chosen on the screen, like every other company-scoped catalog.** The API path
  is `/api/companies/{companyId}/product-categories`, so somebody has to name the company. The
  department catalog is the repository's answer (`department-list.ts:63-95`): an inline `mat-select`
  fed by `CompanyService.getCompanySelector`, seeded from the `companyId` query parameter and written
  back with `replaceUrl`, and **no request at all** until a company is chosen. `CompanyContextService`
  exists (`src/shared/data/company-context.service.ts`) but no catalog page reads it; introducing the
  second mechanism here would make the same question answerable two ways.
- **A table with indentation, not `mat-tree`.** The API returns the tree as a depth-first flat list
  that carries `depth` and `parentCategoryId` precisely so the client renders it without recomputing
  the hierarchy (`ProductCategoryResponse`), and the repo has no tree component anywhere
  (`grep MatTree|mat-tree` → 0 matches). A `mat-table` whose name cell indents by `depth - 1` and marks
  non-root rows is the same screen with none of the new machinery.
- **A dialog for the form, like its neighbours in the same feature.** `product-supplier-dialog` and
  `product-variant-dialog` are the products feature's form pattern, and this form is three inputs and a
  parent picker; a routed page (the HR pattern) would take the tree off screen to edit one name.
- **The dialog always sends `enabled`.** `ProductCategoryRequest` defaults an absent `enabled` to
  `true` (`life-control-api/.../dto/ProductCategoryRequest.java:43-48`), and `PUT` with `enabled=false`
  runs the disable cascade. So an edit that omitted the flag would **re-enable a disabled category and
  its whole subtree**; the dialog copies the row's current value into the payload and a regression test
  pins it.
- **The picker refuses what the API refuses.** Parent options come from the loaded tree; when editing,
  the node itself and every descendant are excluded (the API answers 409), and a level-3 node is a
  disabled option rather than a rejected request. The server keeps its own enforcement — this is
  courtesy, not the rule.
- **Writes are `lc-admin`.** The endpoints are `hasRole('lc-admin')` and no `lc-product-category` role
  exists (backend record L5), so the route carries `PRODUCT_ADMIN_ROLES` and the action buttons render
  from `canWrite = hasAnyClientRole([LC_ADMIN])`.
- **Spanish copy, English code.** Every products *page* is Spanish (`product-list.html:2`,
  `supplier-list.html:2`); only the dashboard card titles are English. The screen follows the pages.

## Specs

- **S1** (verbatim, L1): "Seria crear la pantalla de Categorias dentro del menú de productos para
  agregar, editar, habilitar/inhabilidar categorias y subcategorias".
- **S2** (verbatim, L2): "puedes continuar con S3 de feat-product-categories-backend".
- **S3**: the screen is reachable from the existing `Categories` card of the products dashboard, at
  `/products/categories`, admin-only, and the card stops being a placeholder — its `route` names the
  screen and `disabled` is `false`.
- **S4**: the screen reads `GET /api/companies/{companyId}/product-categories` for a company chosen on
  the screen; `companyId` lives in the URL query string so a shared link reproduces the screen; the
  `includeDisabled` toggle reaches the request instead of filtering a list the API already truncated.
- **S5**: creating and editing go through one dialog that posts `parentCategoryId`, `categoryName`,
  `description`, `displayOrder` and the row's own `enabled`; a new category under a level-3 node is
  never offered, and the node itself or a descendant is never offered as a parent.
- **S6**: enable/disable is `PATCH /{id}/enable` with the explicit flag, delete is a soft `DELETE`
  behind a confirmation, and both reload the tree because both cascade on the server.
- **S7**: the failure the caller sees names the rule that refused — a duplicate sibling name, a
  disabled parent, a cycle or a fourth level all arrive as 409 and must not render the generic
  "the data changed while you were editing" copy `httpErrorMessage` returns for that status.

## Rules this slice implements

| # | Rule | Failure mode it prevents |
| --- | --- | --- |
| F1 | No request is issued before a company is selected, and every request carries that company | A tree read or a write against a company nobody chose |
| F2 | The tree renders in the order the API returned, indented by `depth` | A hierarchy the operator cannot read |
| F3 | The parent picker offers only nodes that can accept a child, excluding the edited node and its subtree | A 409 the screen could have avoided, and a category nested in itself |
| F4 | An edit sends the row's own `enabled` | Editing a name re-enabling a disabled subtree |
| F5 | Disable and delete are destructive actions behind a confirmation that says the subtree follows | A whole branch disabled by one stray click |
| F6 | A 409 renders the category rule copy, not the version-precondition copy | An operator told to reload a page where reloading changes nothing |
| F7 | Only `lc-admin` sees the write controls | A button the API would refuse |

## Tasks

| ID | S# | Route | Commit |
| --- | --- | --- | --- |
| T1 | S4 | `product-category.models.ts` + `ProductCategoryService` (+ `HttpTestingController` spec) | `feat(product): add the product category client` |
| T2 | S3 | `/products/categories` route under the admin gate, and the dashboard card pointed at it | same commit as T1 |
| T3 | S4 | `ProductCategoryList`: company selector, include-disabled toggle, indented tree, load/empty/error states | `feat(product): render the product category tree` |
| T4 | S6, F5 | Row actions: enable/disable, soft delete behind a confirmation, reload after each | same commit as T3 |
| T5 | S5, F3, F4 | `ProductCategoryDialog`: create and edit, parent picker, explicit `enabled`, 409 copy | `feat(product): create and edit product categories` |
| T6 | F1, F2, F7 | List spec: no request before a company is chosen, indent by depth, action gating | commit of T3 |
| T7 | F3, F4, F6 | Dialog spec: parent exclusions, the explicit `enabled` regression, 409 copy | commit of T5 |
| T8 | S3 | Route spec + dashboard spec: the gate, the roles, and the card that stopped being a placeholder | commit of T1 |

## Scope

### In scope
- `src/features/products/models/product-category.models.ts`, `data/product-category.service.ts`.
- `src/features/products/pages/product-category-list/*`.
- `src/features/products/components/product-category-dialog/*`.
- The `/products/categories` route, the `Categories` dashboard card, and the specs of both.
- Barrel exports for the new model and service.

### Out of scope (deliberately not implemented)
- The product↔category assignment UI and `product_category_links` (backend S7): there is no bridge
  table yet, so there is nothing to assign.
- The `Product Types` card: still a placeholder, a different feature.
- A new Keycloak role: the endpoints are `lc-admin` and the screen follows them.
- Drag-and-drop reorder: `displayOrder` is a number the operator types, exactly as the department form
  treats it; there is no `@Version` on this catalog, so there is no 412 path to build.
- Pagination: the tree is capped at three levels and loaded whole, by construction.

## Constraints (non-negotiable)

- Standalone components, `ChangeDetectionStrategy.OnPush`, signals, `rxResource` for the list — the
  feature's own conventions.
- `inject()` for dependencies, `var`-free TypeScript with explicit types on public members, no `any`
  outside the `$any($event.target)` template idiom the other pages use.
- Every component and service option carries a doc comment only where the reason is not obvious; the
  Spanish user copy is a hardcoded literal, as the rest of the app does (no i18n mechanism exists).
- Specs are co-located `*.spec.ts`, Vitest globals, `TestBed` with stubbed services — the department and
  supplier specs are the shape to match.
- `npm test`, `npm run lint` and `npm run build` must pass; `npm run test:coverage:check` must not
  regress.

## Log

- **L1** (user, verbatim, inherited from the backend record): "Seria crear la pantalla de Categorias
  dentro del menú de productos para agregar, editar, habilitar/inhabilidar categorias y subcategorias".
- **L2** (user, verbatim, this unit): "puedes continuar con S3 de feat-product-categories-backend".
- **L3** (decision, company selection): the department catalog's inline `mat-select` seeded from the
  `companyId` query parameter, not `CompanyContextService`. The latter exists in `src/shared/data` but
  no catalog page reads it, and adding a second answer to "which company" is how two screens end up
  disagreeing about scope.
- **L4** (decision, tree rendering): `mat-table` with a `depth`-driven indent over the API's depth-first
  flat list. No `mat-tree`: the repo has none, and the response was designed to be rendered as-is.
- **L5** (decision, form surface): one `MatDialog` for create and edit, matching
  `product-supplier-dialog`, rather than the routed create/edit pages the HR catalog uses.
- **L6** (defect prevented, F4): `ProductCategoryRequest.enabled` defaults to `true` server-side, so a
  form that did not carry the flag would re-enable a disabled category on rename. The dialog sends the
  row's value and the regression test asserts the payload, not the rendered form.

## Delivery

The unit is cut into **three reviewable commits** on `feat/product-categories-frontend`, delivered in
one pull request, so each commit is something a reviewer can hold on its own:

| # | Commit | Scope | Authored lines |
| --- | --- | --- | --- |
| 1 | `feat(product): add the product category client` (`474f6853`) | the response model, the request model and the thin service, with its `HttpTestingController` spec | 317 |
| 2 | `feat(product): add the product category form dialog` (`17710133`) | the create/edit dialog, its parent-picker rules and its spec | 598 |
| 3 | `feat(product): add the Categories screen and route it` | the tree page with its actions and spec, the `/products/categories` route, the dashboard card and this record | ~1100 |

**Why one request and not a three-PR chain.** The system's advisory budget is ~400 authored lines, and
this unit is ~2000, so the budget is being spent deliberately rather than being met — the same call the
backend record made and for the same reason: the repository's own standard for **this** artifact is the
`Department` company-scoped catalog, which ships whole at 1752 lines across eight files and carries no
tree, no depth rule, no cycle rule and no cascade. Splitting further would have to put the page's
parent-picker rule in one request and the test that pins it in another, because the dialog is what the
page spec imports to assert the picker it opens — pairs that are only reviewable together. The three
commits are the honest cut; the pull request is the smallest request that keeps every rule with its
test.

- **L7** (verification, measured): full `npx ng test --no-watch` — **195 test files, 3950 tests, 0
  failed**. The feature's own slice is 84 of them: 10 service (verbs, URLs, `includeDisabled`, the
  untouched payload), 21 page (company scope, indent, gating, the two cascading writes, the 409 copy,
  retry and empty states), 17 dialog (both modes, the picker's four refusals and its one exception, the
  `enabled` regression, both refusal copies, cancel) and 22 route tests (the pre-existing variant and
  supplier gating unchanged, plus the new child), of which the dashboard spec contributes 14.
- **L8** (verification, measured): `npm run test:coverage:check` exits 0 with statements 86.64 %,
  branches 86.09 %, functions 79.74 % and lines 89.41 %, every one inside its threshold; `npm run lint`
  reports "All files pass linting"; `npm run build` completes bundle generation. Prettier flagged six
  of the new files on a formatting check and they were reformatted, after which the full suite above and
  the focused run were re-executed on the reformatted tree — the numbers in L7 are that tree.
- **L9** (mutation check, F4): the regression is verified by breaking it. Replacing the payload's
  `enabled: this.data.category?.enabled ?? true` with a constant `true` fails exactly one test —
  "should put the saved category's own enabled flag in the payload" — and leaves the other 16 dialog
  tests green, so the test pins the behavior and not the rendered form.
- **L10** (defect found and fixed before the first commit): the list's `rxResource` swallowed read
  failures with `catchError(() => of([]))`, copied from `DepartmentList` — whose service owns the error
  signal this one deliberately does not have. With a thin service there was no error left to render, so
  a failed `GET` displayed "No hay categorías registradas": an empty tree, the one message that must not
  appear when the API did not answer. The spec's retry test caught it; the resource now lets the error
  through and `loadError` renders the retry state with the shared copy.
- **L11** (decision, 409 copy): the conflict message names the three rule families the API reports as
  409 — sibling name, disabled ancestor, depth — instead of guessing which one fired, because the
  backend collapses them into one status. It says "el padre tiene que estar habilitado (una categoría
  deshabilitada no admite subcategorías habilitadas)" so a refused **re-enable**, which is the only 409
  the toggle can produce, reads as the rule it is.

- **L12** (verification, limits observed and not papered over): two instruments were unavailable and the
  claims above are bounded accordingly. The independent verifier was **not admitted** — runtime policy
  admits one only for a task that declares a named high-risk item as `Risk: item N`, and this task
  declares its risk as a stated reason instead of that token; manufacturing the token to unlock a
  verifier is exactly the loophole that gate exists to close, so verification stayed inline (L7-L9). The
  native review assessment could not see a candidate at all: with every line committed it reports "the
  review assess candidate has no pending changes" and repeats that unchanged when handed the base ref,
  so it returned `nativeRisk: unassessable` and no review was started — nothing here claims otherwise.
  What *was* re-confirmed on the committed tree, after the Prettier reformat: `npm run lint` reports
  "All files pass linting" (exit 0) and `npm run build` completes bundle generation.
