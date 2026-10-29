# ODD feature: supplier-catalog-tenancy

**Repository**: LifeControl — `life-control-api/` (the supplier module, two migrations, and the two consumers
that name a supplier: purchase orders and product→supplier links) and `life-control-app-angular/` (the
supplier screens, the supplier pickers and their routes). No `api-gateway` change: it declares no
`/api/suppliers` route today, so there is nothing to remove there.
**Status**: implemented — a supplier belongs to exactly one company: the path carries it, the row stores it,
its RFC is unique per company, and both consumers refuse a supplier of another company. Nothing is deferred.
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
| T1 | S4 | EXPAND: `V41` adds a nullable `suppliers.company_id`, its FK and the `UNIQUE (company_id, id)` target the composite keys will need, and nothing else | inline (parent) | pending |
| T2 | S2, S4 | The move: `/api/companies/{companyId}/suppliers` with `verifyCompanyAccess`, the company stored on every write, RFC unique per company | inline (parent) | pending |
| T3 | S2 | Consumers: a purchase order and a product→supplier link resolve their supplier **with** the document's company | inline (parent) | pending |
| T4 | S2, S4, S5 | CONTRACT: `V42` makes the column `NOT NULL`, replaces the global RFC key with `(company_id, rfc)` and adds the composite FKs, discarding rows it cannot attribute | inline (parent) | pending |
| T5 | S2 | Frontend: the supplier screens take their company the way the products area does, and the two pickers offer only that company's suppliers | inline (parent) | pending |
| T6 | S1–S6 | This record, its evidence and what it does not cover | inline (parent) | pending |

## Log

**L1** — the request that opened the unit, verbatim (2026-10-29): «si abre el issue con la migracion». The
question before it («hay algun pendiente de cambiar los proveedores por empresa?») was answered from the tree
and the records, not from memory: `suppliers` had no `company_id`, `rfc` was globally unique, and the
deferral was written down in `product-catalog-tenancy` S4/L12.

**L2** — approval and start, verbatim: «si aprubalo», then «Arrancar ahora». The label was applied through one
atomic `PUT /issues/672/labels` carrying the complete set (`enhancement`, `status:approved`), so no
intermediate status ever existed, and the post-read confirmed it.
