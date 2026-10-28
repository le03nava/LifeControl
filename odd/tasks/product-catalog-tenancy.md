# ODD feature: product-catalog-tenancy

**Repository**: LifeControl — `life-control-api/` (two migrations, the `product` module's entities, services,
repositories and controller, and the lookups in `inventory/`, `purchaseorder/` and `salesorder/`) and
`life-control-app-angular/` (the product feature's service and routes). No supplier change, no store-tree
change, no image pin, no delivery script.
**Status**: implemented — `S1`–`S6` are the branch's content: the schema, the scoped API, the per-company keys, the
four consumers, the operational lookups and the client all follow the same owner. Nothing is deferred.
**Created**: 2026-10-27 · **Issue**: **#660** (`feat: scope the product catalog per company`, `status:approved`)
· **Branch**: `feat/product-catalog-tenancy` · **Risk**: **item 1 (data)**, **item 2 (security)** and **item 3
(contract)** — `products` is the aggregate four other modules resolve by bare UUID, the change moves a tenancy
boundary, and it moves `/api/products` out of the wire contract the Angular app already consumes.

## Specs

**S1** — *the user, 2026-10-27, verbatim*: «ahorita el catalogo de productos es global o es por empresa?» The
question that opened the unit. The answer measured from the tree: `products` and `suppliers` are global
(`V1:184-196`, `V1:145-165`), `product_categories` is per company (`V31:41-50`), and the attribute catalog is
global with a per-company override seam nobody uses (`V37:100-130`).

**S2** — *the user, 2026-10-27, verbatim*: «si evalualo por la migración de datos no te preocupes no hay una
version productiva». The data consequence is accepted: no backfill, and rows that cannot be attributed to a
company may be discarded rather than migrated.

**S3** — *the user, 2026-10-27, verbatim*: «si aprueba y arranca» — the authorization to approve **#660** and
start the work.

**S4** — *the user's three scoping decisions, 2026-10-27, quoted from the answers*: `products + variants
(barcode por empresa)`, `Mover a /api/companies/{companyId}/products`, `No, unidad aparte` (suppliers). Read
together: a product and a variant each belong to one company, SKU and barcode are unique **per company**, the
company travels in the path like every other scoped module, and `suppliers` stays global as its own later unit.

**S5** — the acceptance criteria are the ones **#660** carries; this record does not restate them. Where the
two disagree, the issue wins and the record is corrected.

## Tasks

One line per task: ID, specs, route, commit. Route is `inline` unless a Delegation Trigger fired.

| ID | What | Specs | Route | Commit |
| --- | --- | --- | --- | --- |
| T1 | Expand: V39 adds nullable `company_id` to `products` and `product_variants`, the entity fields, and the writes that populate them (a product takes its category's company; a variant takes its product's) | S2, S4 | inline | `1bb45dac` |
| T2 | Move: `/api/companies/{companyId}/products` with `verifyCompanyAccess`, the company required on write, the category required to belong to it, duplicate SKU per company | S4 | delegated (`gentle-ai-worker`) | `e9f9ecba` |
| T3 | Variants: barcode unique per company, variant search scoped by the store's company | S4 | delegated (`gentle-ai-worker`) | `f25de7d2` |
| T4 | Contract: V40 makes both columns `NOT NULL`, adds the composite FKs, drops the global unique keys and replaces them per company | S2, S4 | delegated (`gentle-ai-worker`) | `155d13ae` |
| T5 | Consumers: `InventoryService`, `PurchaseOrderService`, `SalesOrderService` resolve a variant **with** the document's company | S4 | delegated (`gentle-ai-worker`) | `25cb646f` |
| T6 | Frontend: the products area takes its company from the route (`/products/c/:companyId`), the `categoryCompanyId` workaround goes away, and the dead `/api/products/**` gateway route is removed | S4, L12 | delegated (`gentle-ai-worker`) | `9470c903` |

The slices are ordered so each one leaves the suite green: a `NOT NULL` column cannot arrive before the write
surface knows where the company comes from, and the API cannot move before the column exists. That is why the
plan is six slices and not the five the issue's scope statement supports — the constraint is CI, not scope.

**Slice boundary and delivery strategy.** T2 is **oversized** and is recorded that way rather than dressed up:
`e9f9ecba` is ~1.400 authored lines against a ~400-line review budget, because the contract move is atomic.
Every handler of one controller moves, every service signature it calls changes, and every suite that names
the old path follows; the only split that would fit the budget is a dual surface — the old `/api/products`
alive beside the new path for one pull request — and that intermediate state is exactly the cross-tenant hole
this unit closes, served under the contract the unit replaces. Delivery is therefore a chain of pull requests
(`type:breaking-change` for T2, `type:feature` for the rest), and T2 is the slice the reviewer should read
first and alone.

## Log

- **L1** — the request, 2026-10-27, verbatim: «ahorita el catalogo de productos es global o es por empresa?»
- **L2** — the request, 2026-10-27, verbatim: «si evalualo por la migración de datos no te preocupes no hay una
  version productiva».
- **L3** — the evaluation, 2026-10-27. What the tree says, with the evidence the plan rests on:
  `ProductController` maps `/api/products` and never calls `verifyCompanyAccess`, so a role is the only gate;
  `products.sku` (`V1:186`) and `product_variants.bar_code` (`V14:74`) are globally unique; `products.category_id`
  is a plain FK by decision (`V36:19-24`); every operational lookup resolves a variant by bare UUID after
  checking only the store (`InventoryService:132-137`, `:241-246`, `PurchaseOrderService:689-697`,
  `SalesOrderService:858-862`, `ProductVariantService.searchVariants:386-397`); and the client already carries
  `categoryCompanyId` because `/api/products` has no company segment (`product.models.ts:24-28`). Two facts
  cheapen the unit: `V31:50` already created `UNIQUE (company_id, id)` on the category tree, which is the target
  a composite FK needs; and every operational document already carries `company_store_id NOT NULL`, so the
  company is derivable at every call site without touching those tables. One thing cannot be closed
  structurally — "a variant of A stocked in a store of B" — because `company_stores` has no `company_id`
  (the company is derived through zone → region → country, `V1:127-136`); it stays a service rule plus a test.
- **L4** — approval and start, 2026-10-27. Issue **#660** created from `feature.yml` and approved with
  `status:approved` on the maintainer's instruction (S3). Worktree
  `~/workspace/LifeControl-worktrees/feat-product-catalog-tenancy`, branch `feat/product-catalog-tenancy`, cut
  from `main` at `cc0ff642` with `./.github/scripts/new-unit.sh`.
- **L5** — T1 evidence, 2026-10-27. Red first: `should take the company of the category it is classified under` and
  `should give the variant the company of its product` failed with `WantedButNotInvoked` before the services set
  the column, and passed after. Gate run `./gradlew spotlessCheck spotbugsMain test --no-daemon` in
  `life-control-api`: BUILD SUCCESSFUL, 4046 tests. One pre-existing assertion failed on the way and was fixed
  rather than bumped: `ProductAttributeUpgradeOnPopulatedDbIntegrationTest` asserted the literal count `2`
  migrations from a populated V36 database, so V39 — which has nothing to do with attributes — made a test about
  the V37/V38 upgrade path fail. It now derives the applied range (`startsWith("37")` … `endsWith(latestMigrationVersion())`),
  which is the same duplication the Flyway head removed from seven suites.
- **L6** — T2 evidence, 2026-10-27, commit `e9f9ecba` (delegated to `gentle-ai-worker`). The path is now
  `/api/companies/{companyId}/products` and the company in it is the owner: a category of another company is a
  404 with nothing stored, another company's product is a 404 on read/update/delete (scoped in the query, not
  loaded and compared), the SKU key is per company, the by-supplier read is company-scoped and
  `ProductResponse` reports the owner. Proof of the tenant rules: 17 new cross-tenant assertions observed RED
  before the rules existed and GREEN after, plus a new real-SQL suite
  (`ProductSupplierCompanyScopeIntegrationTest`) that proves the by-supplier predicate is a query filter and not
  a post-filter. Gate: `./gradlew spotlessCheck spotbugsMain test --no-daemon` → BUILD SUCCESSFUL, 4070 tests.
- **L7** — three follow-ups T2 surfaced and did not take, each with its owner slice: `api-gateway` still
  declares a `/api/products/**` route that nothing serves (dead, harmless — removed with T6, which also moves the
  client off the old path); the per-company SKU key is a service rule until V40 replaces the global unique index
  and a same-SKU insert across two companies would still be refused by the database (T4); and
  `ProductVariantService` scopes the variant endpoints through their product, while the barcode key and the
  search predicate stay global (T3).
- **L8** — T3 evidence, 2026-10-27, commit `f25de7d2` (delegated). The barcode gate is the company's and the
  search is predicated on the store's company, derived once in `companyIdOf(store)` so the access guard and the
  queries cannot disagree. The search proof is real SQL: `VariantSearchCompanyScopeIntegrationTest` builds two
  companies with one store each plus a variant of company B stocked at a store of company A, and the search
  through company A's store answers exactly one row — observed RED as `expected:<1> but was:<2>` before the
  predicate existed. Gate: BUILD SUCCESSFUL, 4079 tests. Left open and recorded rather than implied: the store
  stock row that pairs a variant of A with a store of B is still writable (issue non-goal — `company_stores` has
  no `company_id`); T3 makes both store-scoped reads blind to it instead of refusing the write.
- **L9** — a premise the next slice has to correct, found while preparing T4. `ProductAttributeUpgradeOnPopulatedDbIntegrationTest`
  asserts that the rows it seeds into a populated V36 database **survive** the migration to head. T4's V40
  discards every row that cannot be attributed to a company, so that premise stops holding at head: the test
  must be retargeted to **V38**, the last migration whose premise it describes, and pin exactly the two versions
  it targets. The derived form L5 introduced was right while V39 was purely additive and becomes wrong in T4 —
  the record says so instead of leaving a test to be discovered failing.
- **L10** — T4 evidence, 2026-10-27, commit `155d13ae` (delegated). V40 drops
  `products_sku_key`, `uq_product_variants_bar_code`, `fk_products_category` and
  `product_variants_product_id_fkey`, and adds `uq_products_company_id`, `uq_product_variants_company_id`,
  `uq_products_company_sku`, `uq_product_variants_company_bar_code`, `fk_products_category_same_company` and
  `fk_product_variants_product_same_company`. The discard is one `TRUNCATE` over the product/operational chain
  with V14's shape and banner. RED before the migration: 7 of the 9 new schema assertions failed against the
  V39 head (two `NOT NULL` assertions, two `DuplicateKeyException`s where the global keys refused the second
  tenant, two composite-FK assertions, and the presence check); GREEN after: 9/9. Gate: BUILD SUCCESSFUL, 4088
  tests. L9's correction landed as planned: the V36 upgrade test now targets V38 and pins `containsExactly("37",
  "38")`, which cannot drift because the target is written in the test.
- **L11** — review-workload note, recorded because it is the maintainer's call and not mine to bury: the branch
  is now **~3.700 authored lines** across seven commits, and two slices (T2 ≈1.400, T4 ≈600) exceed the ~400-line
  budget. T1, T3 and the docs commits are inside it. The delivery strategy in §Tasks stands — a chain of pull
  requests with T2 read first and alone — but if the maintainer prefers, T4 can be split at the migration
  boundary from its fixture updates, and the remaining slices stay small.
- **L12** — the maintainer's decision for T6, 2026-10-27, verbatim: «Segmento /products/c/:companyId/...». The
  products area takes its company from the **route**, not from a query parameter: `/products` becomes a company
  picker, the product workspace lives under `/products/c/:companyId`, and the paths the app already links
  (`/products/list`, `/products/create`, `/products/edit/:id`, …) redirect rather than 404. Two areas stay outside
  the segment because they are not a company's workspace: `/products/categories` (a company-independent browser
  that chooses its company inside the page — its own documented decision) and `/products/suppliers` (suppliers
  are global by decision; only the product's *links* to them are company-scoped). The rejected alternatives were
  the `?companyId=` query parameter — which `product-edit` uses today, and which would have been the smaller diff
  — and an app-wide company context in the shell, which does not exist yet and is its own unit.
- **L13** — T5 evidence, 2026-10-27, commit `25cb646f` (delegated). All four lookups moved in one slice because
  the same question was being asked at four different depths: the purchase order bound the variant to a product,
  the sales order asked for a bare id (`existsByIdAndEnabledTrue` — the weakest check in the codebase), the
  inventory engine asked for a bare id, and the store-stock write never asked. `CompanyStoreOwnerResolver` is
  now the single store → zone → region → country → company derivation, so the guard and the queries cannot
  derive different tenants. RED observed for each rule as a *behavioral* failure (a foreign line was accepted and
  the order saved; a receipt moved another tenant's stock; the store-stock pair answered 200), GREEN 4104 tests.
  The item L8 recorded as open — the store-stock pair — is closed on the write path, and the record keeps the
  distinction the schema still cannot make.
- **L14** — T6 evidence, 2026-10-27, commit `9470c903` (delegated). The client now takes the company from the
  route: `/products` is a picker, the workspace lives under `/products/c/:companyId`, and every legacy company-free
  URL redirects instead of 404ing. `categoryCompanyId` is deleted — it existed only because `/api/products` carried
  no company segment, which is the workaround this unit exists to remove. The gateway's `/api/products/**` route is
  gone and a test pins its absence. Gates, run by the parent on the final tree: `npm run lint` → "All files pass
  linting"; `npm test` → 192 files, 3974 tests, exit 0; `api-gateway ./gradlew test` → BUILD SUCCESSFUL.
- **L15** — a gate that was cited but could never have run, corrected rather than inherited. The first T6 report
  listed `npm test -- --run` as its test gate; `@angular/build:unit-test` rejects the flag ("Error: Unknown
  argument: run") and the repo's script is plain `ng test`, a single run. The re-run recorded the real command and
  its real output, and L14 carries only what was actually executed. Same class of defect as a duplicated Flyway
  head: a gate named wrongly reads as green while measuring nothing.
- **L16** — surface extension accepted, with the reason. Closing T6 needed eight paths outside the original list:
  two purchase-order call sites (the reads that had been company-blind) plus the binding path that carries the
  order's company into the line-item picker (`detail-table.ts/.html/.spec.ts`, `purchase-order-edit.html`). The
  picker's `companyId` is a required input, so the host chain has to bind it or the app does not compile; the
  alternative — a company-blind overload on the services — is the requirement this unit exists to remove. Both
  reads fail closed (no request) while the order has no company.
- **L17** — delivery plan, final numbers. The branch is `5968 insertions / 1367 deletions` over 97 files in eleven
  commits. Three slices exceed the ~400-line review budget: **T2** (`e9f9ecba`, ~1.400), **T4** (`155d13ae`, ~600)
  and **T6** (`9470c903`, ~1.700). Chain the pull requests in this order, each `type:feature` except where noted:
  T1 · **T2 (`type:breaking-change`, read first and alone)** · T3 · T4 (`type:breaking-change`) · T5 · T6. If T6
  must be cut further, the seam is already chosen and its halves are file-disjoint: (a) the route shape —
  `products.routes.ts` + its spec, the picker page, `products-admin.component.*`, `products-company.ts`; (b) the
  signature migration — the three services, their specs, and every component that now passes `companyId`. Only the
  final tree was verified as a whole, so a split commit must be re-gated before it is pushed.
