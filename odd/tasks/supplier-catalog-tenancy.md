# ODD feature: supplier-catalog-tenancy

**Repository**: LifeControl — `life-control-api/` (the supplier module, two migrations, and the two consumers
that name a supplier: purchase orders and product→supplier links) and `life-control-app-angular/` (the
supplier screens, the supplier pickers and their routes). No `api-gateway` change: it declares no
`/api/suppliers` route today, so there is nothing to remove there.
**Status**: implemented — a supplier belongs to exactly one company: the path carries it, the row stores it,
its RFC is unique per company, and both consumers refuse a supplier of another company. One piece is **deferred,
with its reason**: the store half of the purchase-order key — `(company_id, company_store_id) ->
company_stores (company_id, id)` — because `company_stores` has no `company_id` column (V1 never gave it one, and
V40 recorded the same limit), so it needs a store tenancy unit of its own instead of a ghost column here. That
unit is **#673**, opened from L12 with the full hierarchy measured.
**Created**: 2026-10-29 · **Issue**: **#672** (`feat: scope the supplier catalog per company`,
`enhancement` + `status:approved`) · **Branch**: `feat/supplier-catalog-tenancy`

## Specs

**S1** — *the request, verbatim*: «si abre el issue con la migracion» (2026-10-29), then «si aprubalo»
when asked for approval, then «Arrancar ahora» when asked whether to start. Read together: the unit is
issue **#672** plus the migration it names, approved, and started.

**S2** — *the requirement set is #672's, not this file's*: the acceptance criteria live in the issue and are
not restated here. Where this record and #672 disagree, **#672 wins and the record is corrected** — the same
rule `product-catalog-tenancy` used for #660.

**S3** — *the deferral this unit discharges, verbatim from the previous record*: «No, unidad aparte»
(`odd/tasks/product-catalog-tenancy.md`, S4 — the maintainer's answer of 2026-10-27) and «`suppliers` stays
global as its own later unit» (same file, L12). Nothing new is decided here: this is that unit.

**S4** — *the shape is the precedent's, not invented here*: two migrations, an EXPAND and then a CONTRACT,
with the suite green after each, because of what `V39` states verbatim — «a `NOT NULL` column cannot arrive
before the write surface knows where the company comes from». The same slice ordering follows: the column,
then the moved API that writes it, then the contract that enforces it, then the frontend that follows it.

**S5** — *the data consequence, in the issue's own words* (#672, approved): «The data consequence is stated in
the migration header the way `V40` states its `TRUNCATE`: a row that cannot be attributed to a company is
discarded rather than given an invented owner, and there is no backfill pretending otherwise.» The CONTRACT
migration is therefore destructive, and the maintainer approved that when approving the issue.

**S6** — *non-goals, from #672*: no redesign of the supplier screens (the company source changes, the form
does not); no supplier types, contacts or per-company price lists beyond `product_suppliers.purchase_cost`;
`supplier-info-section`'s layout is untouched; and `companies.rfc` — also globally unique, `V1:51` — is not
touched, because a company has no owner company and the per-tenant claim does not apply to it.

## Tasks

One line per task: ID, linked specs, what, route, commit. Route is `inline` unless a Delegation Trigger fired.

| ID | Specs | What | Route | Commit |
| --- | --- | --- | --- | --- |
| T1 | S4 | EXPAND: `V41` adds a nullable `suppliers.company_id`, its FK and the `UNIQUE (company_id, id)` target the composite keys will need, and nothing else | inline (parent) | `e3ef6d7c` |
| T2 | S2, S4 | The move: `/api/companies/{companyId}/suppliers` with `verifyCompanyAccess`, the company stored on every write, RFC unique per company | delegated (`gentle-ai-worker`) | `f1eef5e9` |
| T3 | S2 | Consumers: a purchase order and a product→supplier link resolve their supplier **with** the document's company | inline (parent) | pending |
| T4a | S2, S4, S5 | CONTRACT on the supplier table: `V42` makes the column `NOT NULL` and replaces the global RFC key with `(company_id, rfc)`, discarding the rows it cannot attribute | delegated (`gentle-ai-worker`) | `a1fd4775` |
| T4b | S2 | The database half of the two consumers: a `company_id` on `purchase_orders` and `product_suppliers`, and the composite keys that make naming another company's supplier impossible | delegated (`gentle-ai-worker`) | `7e5bc735` |
| T5 | S2 | Frontend: the supplier screens take their company the way the products area does, and the two pickers offer only that company's suppliers | delegated (`gentle-ai-worker`) | `b96c72ff` |
| T6 | S1–S6 | This record, its evidence and what it does not cover | inline (parent) | `45e859ac` |

**Slice boundary and delivery strategy.** T2 is **oversized** and is recorded that way rather than dressed
up: `f1eef5e9` is ~1150 changed lines against the advisory ~400-line budget, because the contract move is
atomic — every handler, its service signature and every suite that names the old path move together, and the
only split that would fit the budget is a dual surface with the unscoped `/api/suppliers` alive beside the
new path, which is exactly the cross-tenant hole this unit closes, served under the contract it replaces. Same
constraint and same shape as `product-catalog-tenancy`'s T2. Delivery is therefore a chain of pull requests:
slice A is T1–T3 (`type:feature`, the backend contract, read T2 first and alone), slice B is T4a + T4b
(`type:breaking-change`, the destructive migrations that turn the rule into a database rule and close the two
consumers at the same level), slice C is T5 (`type:feature`, the frontend), and T6's record travels in the last
slice.

## Log

**L1** — the request that opened the unit, verbatim (2026-10-29): «si abre el issue con la migracion». The
question before it («hay algun pendiente de cambiar los proveedores por empresa?») was answered from the tree
and the records, not from memory: `suppliers` had no `company_id`, `rfc` was globally unique, and the
deferral was written down in `product-catalog-tenancy` S4/L12.

**L2** — approval and start, verbatim: «si aprubalo», then «Arrancar ahora». The label was applied through one
atomic `PUT /issues/672/labels` carrying the complete set (`enhancement`, `status:approved`), so no
intermediate status ever existed, and the post-read confirmed it.

**L3** — T1's RED, measured before `V41` existed: three failures, one per new schema rule
(`uq_suppliers_company_id exists` → `expected: 1 but was: 0`; the `company_id` column query →
`EmptyResultDataAccessException: expected 1, actual 0`; the FK case → `BadSqlGrammarException`), while the
three pins passed (Flyway head, the null-company row, and the global RFC key still present). GREEN is the same
suite at 6/6 plus `MigrationHeadTest`.

**L4** — T2's RED, measured on the unmoved tree: 7 of 7 cases in
`SupplierCompanyScopeHttpIntegrationTest` failed, and the two answers that name the defect are `Status
expected:<201> but was:<404>` on `/api/companies/{id}/suppliers` and `Status expected:<404> but was:<200>` on
`/api/suppliers` — the unscoped surface was alive, not merely unguarded.

**L5** — one acceptance case was mine to fix, not the product's: `callerWithAForeignCompanyClaimIsDenied`
first used `ROLE_lc-admin`, and `CurrentUserContext.isAdmin()` short-circuits `verifyCompanyAccess` by design
(`CompanyCountryScopedReadHttpIntegrationTest` documents that an admin keeps the whole company). The oracle was
wrong, so the case now carries a company-scoped `lc-company` role and its two statuses are byte-identical:
200 for the company its claim names, 403 for the one it does not. Nothing about the endpoint changed.

**L6** — T2's green, measured: `com.lifecontrol.api.supplier.*`, `...product.supplier.*` and
`...purchaseorder.*` → **210 tests, 0 failures**, and `spotlessCheck` green (it was red on the base: the two
new test files were unformatted, and formatting was the whole of that fix).

**L7** — what T2 cannot prove yet, recorded so nobody reads its green as more than it is: the service refuses
a duplicate RFC **inside the company**, but `suppliers_rfc_key` is still global until V42, so a second
company's identical RFC is refused by the database, not by the rule — and inside the test transaction the two
refusals are indistinguishable. V42 is what makes the assertion name the rule it claims.

**L8** — T4a's RED, measured before `V42` existed, and the reason L7 is now closed: the rewritten contract
suite failed 6 of 9, and two of those failures are the record's own claim turned into an observation —
`the same RFC in two companies is accepted` failed with `duplicate key value violates unique constraint
"suppliers_rfc_key"` (the global key refusing a legitimate pair) and `the same RFC twice inside one company`
failed because the message named `suppliers_rfc_key` instead of `uq_suppliers_company_rfc` (exactly the
indistinguishability L7 predicted). GREEN is 9/9, and the whole API suite is **4129 tests, 0 failures**.

**L9** — the destructive decision, restated where the migration makes it real: every supplier row at `V42`
holds `NULL` because `V41` populated nothing, so *all* of them are unattributable and the discard is the whole
table. `TRUNCATE suppliers CASCADE` also removes `purchase_orders`, `product_suppliers` and what hangs off
them. This is S5 and it was authorized by approving #672; the migration's first three lines say so.

**L10** — why the composite keys are a separate migration instead of part of `V42`: they need a `company_id`
on tables that have none (`purchase_orders`, `product_suppliers`), and `purchase_orders`' version needs a
`UNIQUE (company_id, id)` target on `company_stores` that does not exist yet. Folding that into the supplier
table's contract would have made one migration touch the operational chain's shape and the catalog's rule at
the same time, and a reviewer could not tell which half a failure came from.

**L11** — T4b's RED, and the discovery that changed its shape: the prescribed `uq_company_stores_company_id`
turned out **not expressible**. `company_stores` (V1:127-136) carries `company_zone_id` and **no**
`company_id`, and `V40`'s own header had already recorded that limit — I checked the migration set myself
rather than taking the report's word for it. `V43` therefore landed the half the schema can hold: both
consumers store an owner derived from their own parent, and the composite keys make a foreign supplier
unrepresentable for the link and for the order. RED before `V43` was 6 failures of 15 — the two columns queried
as absent, the two positive inserts refused with `BadSqlGrammarException`, and the three composite-key cases
failing because the violation was a missing column rather than a constraint. GREEN is 15/15 and the whole API
suite **4136 tests, 0 failures**.

**L12** — the four ways to close the store half, and why this unit took the first: (1) **land `V43` as the
schema can hold it and state the limit** — chosen; (2) give `company_stores` a real `NOT NULL company_id`
derived from zone → region → country, which is the honest fix but is a **store tenancy unit** reaching
`store/**`, `company/**` and every `CompanyStore` write — not this feature; (3) a trigger-maintained column,
rejected because no migration in this repository uses a trigger and it contradicts the rule `V39`-`V42` state,
that the owner is stored by the write path rather than computed by the database; (4) drop the purchase-order
half, rejected because it reopens the hole #672 closes. The residual, stated plainly: code that writes
`purchase_orders` through the repository, bypassing `PurchaseOrderService`, could store a company that
disagrees with its store and then name that company's supplier. No API path can reach that state, and `V43`'s
header says so where a reader of the schema will meet it.

**L13** — T5's RED, measured: 26 TypeScript errors, one per new rule (the missing company-scoped URL, the new
`companyId` arguments, the `companyId` the response now reports, and the filter the list did not have). GREEN:
124 tests in the seven touched spec files, 1083 across `products` and `purchases`, and the whole app suite
**193 files / 4026 tests, 0 failures** with `ng lint` clean. Nothing about the wire contract changed here — the
frontend consumed the contract `f1eef5e9` had already fixed.

**L14** — the final gates, run in **this** worktree rather than in a copy, because the delegated reports are
evidence about their own runs and not about the tree a PR would carry: `life-control-api` → `./gradlew test
spotlessCheck` **1005 suites, 4136 tests, 0 failures, 0 errors**, and `life-control-app-angular` → `npm ci` +
`npx ng test --no-watch` **193 files, 4026 tests, 0 failures** plus `npm run lint` clean. The frontend needed its
own install in this worktree, which the repository convention expects and which the delegated runs had worked
around with a copy that hardlinked the anchor's `node_modules`.

**L15** — what this record does **not** cover, so nobody reads the green as more than it is. (a) The store half
of the purchase-order key, per L12: an order's company is tied to its supplier by the database and to its store
only by the service. (b) No browser walk: no Playwright spec exercises the supplier screens' company filter or
the two pickers against a running API, so those are pinned at component level and the API's refusals are pinned
at HTTP and schema level. (c) The client-side foreign-supplier refusal in both pickers only fires when the API
reports `companyId` in the response it filters; the server remains the boundary either way. (d) `companies.rfc`
is still globally unique (`V1:51`) — out of scope by S6, and it is not a cross-tenant hole, since a company has
no owner company.

**L16** — the commit order is not the order the tasks were worked in, and the record says so instead of
implying a tidier history: T5 was delegated in parallel with T4b and landed between T4a and T4b, which made
the approved slices non-contiguous. A pull request is a branch range, so the history was rebuilt into the three
slices the delivery strategy names — T1–T3, then T4a+T4b, then T5 — by replaying the same commits on fresh
branches. Only the order moved: the tree of the last slice is identical to the pre-reorder tree except in this
file, where the task table had to be repointed at the replayed commits. `d0079d2c` was already stale in this
table before that, from an amend that changed a commit message only, and is corrected here too.
