# tax-rate-region-axis

**Status**: implemented — a tax rate gains the dimension it was missing: `tax_rate` can state a rate for one of the
company's **regions**, the resolver takes the store's region and prefers it, and a region that has stated rates for
a code stops falling back to the company-wide one — a gap of dates there is a 400 that names the region instead of
a rate invented from another jurisdiction. Issue #855. Branch `feat/tax-rate-region-axis`, base `origin/main` @
`40fcec6d`. Nothing is deferred: the axis, the precedence rule, the refusal and the ABM field land together, and the
line's snapshot keeps being `code + rate` by decision `D4`.
**Created**: 2026-11-12 · **Risk**: item 3 applies — `tax_rate`'s unique key changes shape, so the resource's
contract gains a field and the resolver's answer for a region-scoped company changes from "the latest rate" to
"the rate of this region, or a refusal". Item 1 does not: the migration is **additive** (`company_region_id` arrives
`NULL`, existing rows mean "company-wide") and no stored money or line is rewritten. Item 6 does not: every rule
below carries a test that fails on the previous tree (`S5`–`S8`).

## Specs

Quoted verbatim from issue #855 where it states the requirement; the decisions are the operator's, taken 2026-11-12.

- **S1** — "Two rates for the same code and the same date are representable for one company, and something other
  than \"the latest row wins\" decides which one a document gets."
- **S2** — "The line still stores code, rate and amount, and the stored pair identifies which of the two rates it
  came from — the snapshot is what an auditor reads months later."
- **S3** — "A company with a single `IVA` rate keeps today's behaviour, pinned by the existing tests that pass
  before and after."
- **S4** — "The migration that makes the unique key representable does not rewrite stored rates: existing lines keep
  what they were created with."
- **S5** — the axis: **the store's region**. A rate row names a `company_region_id` of its own company, or names
  none, which is the company-wide rate today's rows already are. The code stays where it always was — on the
  product (`products.tax_code`) — because *what* is sold is the product's fact and *how much* is the place's.
  Rejected: a second catalog code (`IVA_FRONTERA`) and a rate on the product, both of which put a location fact on
  an item fact (`D1`).
- **S6** — the precedence: a region that holds **no row at all** for that code resolves from the company-wide rows,
  so nothing that works today changes. A region that **holds rows** for that code is authoritative for it, and if
  none applies on the document's date the line is **refused** naming the code, the region and the date — never
  priced from the company-wide rate, which in the frontier case is exactly the 16% that must not be charged.
  Refusing unconditionally was rejected: with no region rows anywhere it would refuse every line in the tree (`D2`).
- **S7** — a rate for a region of **another company** is not representable: the write validates the region belongs
  to the company that owns the rate, because a foreign region id would make the resolver answer for a place this
  company does not have.
- **S8** — the store's region comes from the chain that already exists — `company_stores.company_zone_id` →
  `company_zones.company_region_id` — and both documents already carry the store
  (`sales_orders.company_store_id`, `purchase_orders.company_store_id`), so the callers pass a region they already
  own. No new claim, no new column on a document.

## Tasks

| ID | Spec | Route | Evidence |
| --- | --- | --- | --- |
| T1 | S5, S6, S8 | worker | `V95` adds `tax_rate.company_region_id` and the unique key that admits two rates for one date |
| T2 | S5–S7 | worker | the resolver prefers the region, falls back only while the region has no rows, and refuses a gap by name |
| T3 | S1, S5, S7 | worker | the ABM accepts, serves and validates the region |
| T4 | S8 | worker | both documents pass the store's region through their seven `lineTaxFor` call sites |
| T5 | S2, S3, S4 | worker | the line's snapshot and today's single-rate behaviour pinned by tests that pass on both trees |
| T6 | S1–S8 | parent | full suite, spotless, shell suites, and the record's own evidence |

## Log

**L1 (2026-11-12) — the request and the two decisions, in the operator's words.** «ok dale con tus recomendaciones»,
after the analysis of #855. **D1 — the axis is the store's region**, recommended because the frontier designation is
geographic and two stores of one region must not be able to disagree; the store itself was the alternative. **D2 —
no silent fallback**: my own recommendation («se niega nombrando la región») was refined before any code, because
"always refuse" would refuse every line in a tree where no region has rows; the rule that holds is *the region is
authoritative once it has stated rates for that code*.

**L2 (2026-11-12) — the surface measured before delegating, so the size was a number and not an impression.**
`TaxRateService.resolve` had exactly **two** production call sites (`SalesOrderService.java:1069`,
`PurchaseOrderService.java:1220`), both inside a private `lineTaxFor`; those two helpers serve **nine** call sites
across the two documents. Everything the resolver needs is already in the caller's hand: both documents carry
`company_store_id` (`V1:321`, `:403`) and the region is two hops away through entities the services already load
(`company_stores.company_zone_id` → `company_zones.company_region_id`, `V1:126-136`, `:110-116`). That is why `S8`
says "pass a region you already own": the unit adds no claim, no column on a document and no new module dependency.

**L3 (2026-11-12) — the migration's real risk was not the column, it was the key, and the obvious shape is wrong.**
Replacing `uq_tax_rate_company_code_from` with a composite `(company_id, tax_code, company_region_id,
effective_from)` would have **removed** the guarantee it exists for: PostgreSQL treats NULLs as distinct, so two
company-wide rows could share a day and "the latest rate" would depend on insertion order again — for every company
that never states a region, which is all of them today. `V95` therefore keeps two **partial unique indexes**, one
per scope, each stating its own case. The guard was already in the tree and needed no new test:
`TaxRateMigrationIntegrationTest#boundsTheRateAndTheEffectiveDate` runs `migrateToHead()` — now including `V95` — and
refuses a second company-wide rate for the same day, so a future return to the composite key fails there. What the
service-level cases add is the other half: two scopes on one code and one day are accepted and both rows land.

**L4 (2026-11-12) — the three steps, and the exception that keeps the 400 shape.** `resolve(companyId, taxCode,
regionId, documentDate)` asks the region first, then — only when the region holds **any** row for that code, `enabled`
and the date deliberately ignored — throws `TaxRateRegionGapException`, and otherwise asks the company-wide rows.
The exception extends `IllegalArgumentException`, which `GlobalExceptionHandler.handleBadRequest` already maps to
**400** with the message as the body — the same shape `InvalidTaxRateSupersessionException` uses, so no new mapping
and no new status code. Its sentence names the code, the region and the document's date; the region's name is read
once on the refusal path and falls back to the raw id if it cannot be read. A region of **another company** is
`CompanyRegionNotFoundException` → **404**, the shape `EmployeeScopeAssignmentService` already uses, checked through
`region → country → company` (`S7`).

**L5 (2026-11-12) — the ABM grew a scope and kept its two other rules.** `TaxRateRequest` gained an optional
`regionId` (absent = the company-wide rate, so the tree's existing "no value means no value" habit is kept) and
`TaxRateResponse` projects `regionId` + `regionName`. The same-day duplicate check is now per scope, and its 409
names which scope holds the day; the supersede path inherits `superseded.getCompanyRegion()` instead of accepting a
scope, so a company-wide succession cannot swallow a region's rows — the one reading of "supersede" that would
silently re-price the frontier.

**L6 (2026-11-12) — the observation behind the RED, and the case that is supposed to pass on both trees.** With the
previous resolution temporarily restored and the new suite in place, `TaxRateServiceTest` failed 3 of 8: the
precedence case as `expected: 0.080000 but was: 0.160000` — the exact silent 16% the issue describes — and both
refusal cases as "Expecting code to raise a throwable". The fallback case
(`regionWithNoRowsFallsBackToTheCompanyWideRate`) passed on both trees **on purpose**: it is the `S3` preserve case,
and a case that also fails on the old tree would be proving a behaviour change nobody asked for.

**L7 (2026-11-12) — what this unit does not carry, named rather than implied.** No screen: no catalog in this repo
has an ABM UI. No jurisdiction catalog: a region becomes authoritative because someone states a rate for it, not
because a table says it must. The two ABM read endpoints pay one lazy read per row for `regionName` on a company
that has region rows — inside the transaction, not a hot path, and a fetch-join is the fix if it ever matters. And
`TaxRateMigrationIntegrationTest` gained no `V95`-specific case: the index's two halves are already pinned by the
existing company-wide refusal plus the new two-scope acceptance, so a third case would restate them.

**L8 (2026-11-12) — the slice that did not compile alone, and the lesson the CI paid for.** The first delivery was a
three-slice chain — tax layer, documents, docs — which looked like the repo's own precedent (`purchase-order-line-tax`
shipped as three PRs). It was wrong, and the reviewer did not have to find out: `#873`'s `Static analysis` and `Test`
failed at `compileJava` on `SalesOrderService.java:1069` and `PurchaseOrderService.java:1220`, because **slice 1
changed `resolve`'s signature and both production callers live in slice 2**. The branch tip compiled; the slices did
not. The rule this unit learned, in the shape it will bite again: *a slice is built against its base alone, so a
layer-then-callers cut is only legal while the layer does not change a signature the callers share.* The three PRs
were closed and the unit re-delivered as one pull request — an honest large diff beats a chain whose first link cannot
compile. The 2091 authored lines are the cost of a behavior whose parts are a schema, a resolver, a write surface and
two documents; the alternative split that would have compiled (declare the rate first, resolve it second) was
rejected because the intermediate state silently prices a declared 8% at 16%, which is the bug this issue exists to
end.
