# ODD feature: products-company-filter

**Repository**: LifeControl — `life-control-app-angular/` only: the products feature's routes, the company
source used by its screens, the hub, the list filter and the consumers that build product URLs. No
`life-control-api/` change, no migration, no gateway route, no docker file.
**Status**: implemented — the products area is company-free in its URLs again and the company is chosen
inside the screen, as `ProductCategoryList` and `ProductVariantStockSearch` already do. Nothing deferred.
**Created**: 2026-10-28 · **Issue**: **#667** (`feat: choose the products' company inside the screens instead of the
URL`, `status:approved`) · **Branch**: `feat/products-company-filter` · **Supersedes**: the client URL
contract of the previous unit (its `c/:companyId` segment), not its wire contract · **Risk**: **item 3
(contract)** only in the narrow sense that the product URLs are bookmarks and dashboard cards already
consumed; every legacy URL stays registered as a redirect that carries its company, so no consumer breaks.

## Specs

**S1** — *the user, 2026-10-28, verbatim*: «no me gusto como quedó /products/c/ creo que no entendi cuando
lo preguntaste, la idea seria que cuando le presiones al menu de Products aparezcan las opciones de
productos All Products, New Product etc... y ya en la pantalla de All Products poner el filtro de la
empresa». Two requirements in one sentence, and the second is the one the previous shape got wrong: the
menu's destination is the products **hub**, and the company is a **filter inside a screen**, never a
question that stands in front of the area.

**S2** — *the user's answer, 2026-10-28, verbatim*: «Los productos de la empresa elegida en el filtro».
"What does All Products show with the company filter applied" is one company at a time. A cross-company
listing is explicitly **not** the request, so no backend work: `GET /api/companies/{companyId}/products`
stays the only list endpoint and there is no new "all companies" read.

**S3** — *the user's answer, 2026-10-28, verbatim*: «Sólo dentro de All Products». The selector lives in
the All Products screen. The hub carries **no** company selector: its cards are company-free, and the
company is asked where it is needed — the list (filter) and the create screen (the form's `Empresa`
field, preselected from `?companyId=` when the operator arrived from the list).

**S4** — the precedent this unit aligns with, measured in the tree and not invented here:
`ProductCategoryList` (`product-category-list.ts:89-155`) and `ProductVariantStockSearch` already choose
the company inside the page with a `mat-select` fed by `CompanyService.getCompanySelector`, seeded from
the `companyId` **query parameter** and written back with `replaceUrl`. Products was the only area whose
company was a path segment. The acceptance criterion is therefore "the same shape as those two screens",
which is verifiable by reading them side by side.

## Tasks

One line per task: ID, specs, route, commit. Route is `inline` unless a Delegation Trigger fired.

| ID | What | Specs | Route | Commit |
|----|------|-------|-------|--------|
| T1 | Route shape (`/products` hub, `/products/list`, `/products/create`, `/products/edit/:id…`), `productsCompanyId` reads `?companyId=`, the picker page is deleted, the hub's cards are company-free and the list gains the `Empresa` filter | S1, S2, S4 | inline | `3042177b` |
| T2 | The scoped screens take the company from `?companyId=` (create enables the form's `Empresa` field; edit requires it), and every consumer that builds a product URL carries it | S1, S3 | inline | `163dc87d` |

## Log

- **L1** — the user's request, verbatim: «no me gusto como quedó /products/c/ creo que no entendi cuando lo
  preguntaste, la idea seria que cuando le presiones al menu de Products aparezcan las opciones de
  productos All Products, New Product etc... y ya en la pantalla de All Products poner el filtro de la
  empresa». This supersedes the URL contract the previous unit chose (company as a path segment) for the
  **client**; the wire contract (`/api/companies/{companyId}/products`) is untouched.
- **L2** — the three scoping answers, verbatim: «Los productos de la empresa elegida en el filtro» (so the
  list stays per-company, no cross-company endpoint), «Sólo dentro de All Products» (so the hub has no
  selector; create asks in its form) and «Sí, y aprobalo» (a new issue, with `status:approved`).
- **L3** — what "the diff is bigger than the visible change" means here, measured: 17 non-spec navigations
  build `/products/c/...`, `productsCompanyId()` is read by four screens (`product-list`, `product-edit`,
  `product-variant-list-host`, `product-variant-edit`), and two child lists (`product-variant-list`,
  `product-supplier-list`) already take `companyId` as a required input and therefore need nothing.
- **L4** — the services are **not** touched: `ProductService` and friends already take `companyId` as an
  argument, so only the source of that value moves (path → query param). That is the whole reason this
  stays a frontend unit.
- **L5** — what the review found that is **not** this unit's to fix, recorded so it is not lost:
  `ProductEdit` reads `productId` and the company through `snapshot`, so navigating from
  `/products/edit/A?companyId=X` to `/products/edit/B?companyId=Y` reuses the component instance and leaves
the old values in place. It is **not** a regression: the previous `c/:companyId/edit/:id` shape carried the
  same two params through the same snapshot reads. It is a real latent defect and belongs to its own unit.
- **L6** — review workload, measured: 30 files, `1188 insertions / 903 deletions`, of which 392 lines are
  the routes spec rewritten and 277 the routes. That is roughly three times the ~400-line budget, so the
  delivery is a **chain**, not one pull request: slice A is the route shape + the company source + the list
  filter (the pivot), slice B is the scoped screens and their consumers. Only the final tree was verified
  whole (lint + 192 suites / 3987 tests); a split branch must be re-gated before it is pushed. The
  boundary the chain uses, and the reason it is that one: **slice A** is `3042177b` — the routes, the
  company source, the hub and the list filter, i.e. the pivot that makes the user-visible request true
  — and **slice B** is `163dc87d` — the scoped screens and their consumers, which only *follow* a
  company that is already in the URL. Slice B cannot stand alone (its screens read the query parameter
  slice A introduces), so the chain is ordered A → B and never the other way.
