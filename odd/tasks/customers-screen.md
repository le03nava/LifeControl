# ODD feature: customers-screen

**Repository**: LifeControl — `life-control-app-angular` (a new `customers` feature, its route and its menu entry), `odd/tasks/`
**Branch**: `feat/customer-fiscal-profile`, cut from `origin/main @ cb7616d8`
**Status**: **implemented** — the first half of issue #940: the `customers` feature exists as a list over the endpoint the API already serves, with its route file, its menu entry and the role gate the API's own annotations state. The **create and edit pages, and with them the receiver's fiscal profile**, are the sibling unit of the same issue (`odd/tasks/customers-fiscal-form.md`) and are not claimed here. Nothing else is deferred: no fiscal vocabulary is touched by this unit, and the sale's `customer-selector` keeps its own narrow row type.
**Created**: 2026-11-17 · **Risk**: none of the six. It adds a route, a menu entry and a read-only screen; nothing existing changes shape, no contract moves, and the only shared file it edits (`header.ts`) gains one gated entry beside fifteen others that keep their gates.

## Objective

Give the customer a place in the product. `#940` exists because the receiver's fiscal profile cannot be captured anywhere: the app has no customer screen at all, so `FiscalReceiverProfileIncompleteException` refuses every fiscal document and the operator has no path to satisfy it. A form needs a place to live, and a customer needs to be findable before it can be edited.

## Problem — measured, not assumed

| Fact | Evidence |
| --- | --- |
| There is no customer screen | no `features/customers`, no route in `src/app/app.routes.ts`, no menu entry |
| The API already serves everything a list needs | `CustomerController.java:49-51`: `@PageableDefault(size = 12) Pageable` + `@RequestParam(required = false) String search`, answering Spring `Page<CustomerResponse>` |
| Search matches name and email only | `CustomerRepository.java:44-52`: `LOWER(name) LIKE %s% OR LOWER(email) LIKE %s%`, `ORDER BY c.createdAt DESC` |
| Both roles read and write | `@PreAuthorize("hasAnyRole('lc-admin','lc-sales')")` on all six methods, `CustomerController.java:44,55,66,83,100,111`; no read/write split |
| The menu has no per-entry role list | `header.ts:77-84`: one flat `items()` computed of `{id, routeLink, textLink, icon}`, visibility decided by boolean role signals (`:54-60`) |
| A sibling screen exists to model after | `companies/pages/company-list/company-list.ts:75-84` (`rxResource` over page/size/search) and `.html:9-30,196-207` (search field, paginator gated on `totalPages > 1`) |

## Specs

Quoted from issue #940's acceptance criteria, verbatim; the derived consequences say so.

**S1** — "A `customers` feature: list with search, … a route file under `features/customers/`, and a menu entry guarded by the roles the API's own `@PreAuthorize` on `CustomerController` states — read it, do not guess." The "create and edit" clause of that sentence belongs to `odd/tasks/customers-fiscal-form.md`.

**S2** — (derived from S1, read from the code) The list mirrors the endpoint: `page`/`size` with the API's default of 12 and an optional `search`, over the Spring `Page` envelope's own field names.

**S3** — (derived from S1) The roles are `lc-admin` and `lc-sales`, taken from the controller's annotations rather than from the feature's own taste. The entry is hidden in the header **and** the route is gated: `header.ts` decides visibility, `keycloakRoleGuard` decides access, and a screen that does only one of the two is a screen a user can reach and not use, or sees and cannot open.

**S4** — (derived from the ABM precedent) The screen speaks Spanish, like `company-list` and `supplier-list`, because it is the same kind of screen. The search hint says what the query actually matches — name and email — and does not promise RFC, which `CustomerRepository` never searches.

**S5** — (derived from S1) This unit adds no write path: no create, no edit, no delete, no state change. It reads one endpoint.

## Tasks

| ID | Specs | Task | Depends | Route | Commit |
| --- | --- | --- | --- | --- | --- |
| T1 | S2, S4 | The service and the list page: `rxResource` over page/size/search, debounce, the three states (loading skeleton, error with retry, empty with search-aware copy), paginator, Spanish copy | — | inline | `feat(customers)` |
| T2 | S1, S3 | The route file with `keycloakRoleGuard` and the two roles, the lazy entry in `app.routes.ts`, and the header entry behind `isAdmin() \|\| isSalesRole()` | T1 | inline | `feat(customers)` |
| T3 | S5, S2 | The list spec: the resource call with its page/size/search, the debounce, the paginator gating, and the empty and error states. The cards carry **no action** in this unit — the edit page arrives with the form, so a click that navigates nowhere is not built and not asserted | T1, T2 | inline | `feat(customers)` |

## Log

**L1 (2026-11-17) — the maintainer's request, verbatim.** «aprueba el 940 y continua con ese». `status:approved` was applied to #940 on that instruction, with the provenance recorded on the issue.

**L2 (2026-11-17) — the unit is split in two, and this is the first half.** After the map, #940 measures at roughly double the review budget if it lands whole: a feature that does not exist (list, route, menu, guards) plus a form with a seven-field fiscal block and a 25-code vocabulary. The maintainer approved the issue, not a single pull request, so it is delivered as the two units its own acceptance criteria already separate — the screens (#940's S1 first clause) and the profile (its remaining criteria). Each gets its own record and its own pull request, and this record never claims the form.

**L3 (2026-11-17) — four decisions the tree forced, and the reason for each.**
(a) **`salesChannel` is a required free-text field, not a select.** `CustomerRequest` carries `@NotBlank("salesChannel is required")` and the column is `VARCHAR(50) NOT NULL` with no enum, no `CHECK` and no catalog endpoint; the only value the tree seeds anywhere is `'TODOS'` (`V3__seed_reference_data.sql:404`). A closed select would be a vocabulary invented in the UI, and a default would be that invention one layer down. So the field states what it is, the hint names `'TODOS'` as the only value the tree seeds, and the choice stays the operator's — belonging to the sibling unit, which is where the form lives.

(b) **Cards, not a Material table.** Both precedents exist (`company-list` renders cards, `sales-order-list` a table). The card list is the closer sibling — same kind of screen, same language, same paginator shape — and the table's advantage (many columns) is not a customer's problem. Choosable either way; chosen once and written down so the next reader does not re-litigate it.

(c) **The roles come from the annotation, not from the feature's judgment.** `hasAnyRole('lc-admin','lc-sales')` on all six methods means there is no read-only role to hide the screen from. The header entry is therefore visible to exactly those two, and the route carries the same pair. The consequence is stated because it is uncomfortable: `lc-sales` can delete a customer, and this screen does not invent a stricter rule than the API enforces.

(d) **The search hint does not promise RFC.** `CustomerRepository.java:53-62` matches `name` and `email` only, while `company-list.html:17` — the screen this one is modelled after — promises "nombre, RFC, razón social o email" and does not deliver it. Copying that line would copy a lie.

**L4 (2026-11-17) — what this unit deliberately does not touch.** `src/shared/fiscal/regimen-fiscal.ts` (delivered by #941 and in `main`), the sale's `customer-selector` and its narrow `CustomerOption` row, and every endpoint. The selector reads four fields of the same `Page` this list reads; adding fiscal columns to the response changes nothing for it, so it is left alone rather than edited for consistency.

**L5 (2026-11-17) — what building it added to the record, and the citations it had to correct.**

(a) **The card is its own component, and the page header carries no action.** `companies-card` is a component, not markup inside `company-list.html`, and it owns ~80 lines of badge and field styling that a page-level copy would have had to duplicate. So the card landed as `components/customer-card/` — with the sibling's `mat-card-actions` block, its two outputs and its click handlers **removed**, because this unit has no write path (`S5`). The header states the absence instead of leaving an empty action row: `app-page-header … [showActions]="false"`, the form `user-list.html:2` already uses for a read-only screen.

(b) **`Page<T>` is declared here and the duplication is stated in the file.** `models/customer.models.ts` copies the eight-field Spring envelope from `company.models.ts:86-95` rather than importing it, so the customers feature does not depend on the companies feature to describe a page of its own rows. The comment names the source it duplicates and says why; both are pinned by their own service spec.

(c) **The list is `/customers`, not `/customers/list`.** The child route is `path: ''` inside the gated parent, so the menu entry and the screen are the same URL. `companies` uses `/companies/list` because its landing owns `/companies`; the customers feature has no landing to protect, and the sibling unit's `create` and `edit/:id` compose beside the `''` child without a second parent.

(d) **The role constants come from `@core/security/roles`.** `CUSTOMER_ROLES = [LC_ADMIN, LC_SALES]` and `CLIENT_ID`, exported from `customers.routes.ts` the way `companies.routes.ts:5-12` exports its own sets, but built from the module that is the frontend's single source of truth for the `lc-*` names instead of repeating the literals. The header entry gates on the two signals that already exist (`isAdmin() || isSalesRole()`, the form the `Stock por tienda` entry uses), so no signal was added that duplicates one.

(e) **A defect in the screen this one was modelled after, avoided here and not fixed there.** `company-list.ts:91-97` resets the page inside an `effect` that **reads** `pageIndex()` to decide, which makes the effect depend on the value it writes: `pageIndex.set(1)` marks it dirty, and the next change detection sets it back to 0. Its spec never runs change detection after paging, which is why it stays green. The customer list declares the same reset with the `pageIndex` read inside `untracked()`, so the effect depends only on the debounced term, and `customer-list.spec.ts` pins the half the sibling spec does not: `onPageChange` **survives** change detection and the request follows it. `company-list` is outside this unit's surface and is left as a follow-up, not silently patched.

(f) **Three citations in this record were wrong on the first pass, and were corrected before it landed.** The `@PreAuthorize` annotations are at `CustomerController.java` **44, 55, 66, 83, 100 and 111** — the table cited 47, 56, 64, 96, 112 and 121, numbers taken from the exploration report rather than from the file. The search query is at `CustomerRepository.java:44-52` (the table said 53-62, which is the signature and what follows it). Both rows were corrected in place and the code cites the verified numbers; `CustomerController.java:49-51` and `CustomerResponse.java:19-34` were exact as first written. Recorded rather than silently repaired because a record that cites lines nobody re-read is how the next reader stops trusting all of them.

(g) **The route gate is pinned by its own spec.** `S3` states that the entry is hidden **and** the route is gated; a declaration nobody asserts is a comment, and `src/app/app.routes.spec.ts` only checks that the lazy entry exists. `customers.routes.spec.ts` therefore asserts the guard and the role pair on the parent **and** on the list child, and that the child answers `/customers` rather than `/customers/list`.

(h) **Verification, and one environment note.** `npm ci` in a fresh worktree runs the repo's `prepare` script, which rewrites `core.hooksPath` to `life-control-app-angular/.husky/_`; it was restored to `.githooks` before any other command. `npx ng test --no-watch --include='src/features/customers/**/*.spec.ts' --include='src/core/layout/header/header.spec.ts'` fails first (RED) with the two new spec files unresolvable and eight header assertions about the missing entry, then passes 117 tests across the four files that cover the change; the whole suite reports **208 files / 4334 tests passed**, `npx ng lint` reports "All files pass linting", and `npx prettier --check` is clean over every file this unit touched.
