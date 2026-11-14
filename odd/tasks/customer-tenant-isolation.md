# ODD feature: customer-tenant-isolation

**Repository**: LifeControl — `life-control-api` (the `customers` table, the sale's customer pointer, the customer endpoints) and `life-control-app-angular` (the sales-order page that fetches a customer by a fixed id), plus `odd/tasks/`
**Branch**: `feat/customer-tenant-isolation`, cut from `origin/main` at `d598ea8d`
**Status**: **deferred** — the unit arms `customers`, the only tenant table in this tree without a `company_id` and without a policy, and removes the placeholder row the sale depended on. Three slices: A and B land together because arming a table without scoping its readers breaks every reader, and C follows. Nothing is blocked and nothing is uncertain.
**Created**: 2026-11-14 · **Risk**: items 1, 2 and 3 — item 1 because the migration rewrites `sales_orders.customer_id` and assigns existing customers to a company, and a wrong assignment is a customer of one tenant belonging to another; item 2 because this is the tenant boundary itself, and the defect it closes is a cross-tenant read of names, emails, phones and RFCs; item 3 because `sales_orders.customer_id` becomes nullable, which is a change to a contract the Angular app and the printed sale already consume.

## Objective

Make `customers` a tenant table like every other one — `company_id`, armed, every reader scoped — and delete the fictitious "Cliente General" the sale was forced to name, so that a counter sale states **no receiver** and an invoice states one.

## Specs

**S1** — `customers` gains `company_id UUID NOT NULL REFERENCES companies (id)`, is armed with `apply_tenant_isolation` in the migration that adds it, and gains `uq_customers_company_id UNIQUE (company_id, id)` so a pointer to a customer can be composite. The three existing indexes on the table stay: they are the reads the customer list already makes.

**S2** — The walk-in placeholder disappears, in this order: `sales_orders.customer_id` becomes **nullable**, the seeded `'Cliente General'` row (`00000000-0000-0000-0000-000000000001`, `V3__seed_reference_data.sql:402`) is removed after every reference to it is nulled, and the migration **refuses** if any reference survives. `NULL` means a counter sale: no receiver, no fiscal profile, nothing to invoice. `SalesOrderRequest` stops defaulting to it (`sales_order/SalesOrderRequest.java:13`), and the Angular sales-order page stops fetching it by that id. `V3` is not edited: a migration cannot be rewritten once applied, so the same file that adds the column removes the row, which is also what makes a **fresh** install clean.

**S3** — Existing customers are assigned to a company **by proof, not by guess**: the company named by the `sales_orders` that reference them. A customer referenced by orders of **more than one** company, or by **none**, is refused loudly — the count and up to five `id`/`name` samples — because that is exactly the cross-tenant state the missing column allowed, and the operator decides those rows once, with the list in hand. The alternative refused is a default company for orphans: it is the same guess that produced the defect, one layer down.

**S4** — Every customer read and write is scoped by the caller's tenant: the repository finds by `(company_id, id)`, so an id belonging to another company is a **404** and not a 403 (the row is not there in this company, which is the tree's convention), the list is the caller's company's customers, and search does not cross the boundary. The scope comes from `TenantScope`/`CurrentUserContext`, the same way every other read path takes it.

**S5** — Every table that names a customer gains the **composite** pointer `(company_id, customer_id) → customers (company_id, id)` — `sales_orders` first, and whatever else the migration measures by querying `pg_constraint` instead of assuming. The reason is the one `derived-accounting` S7 states: a referential check bypasses row security, so an id-only pointer lets one tenant name another's row while the row it writes is legitimately its own. A `NULL` customer stays legal, because `MATCH SIMPLE` skips a composite key with a null in it.

**S6** — The sale contract says what it means: `SalesOrderRequest.customerId` and its response carry `NULL` as "mostrador", a sale still needs no customer, and whatever prints a sale shows **no receiver** instead of a fictitious one. The Angular page renders the selector's empty state as a counter sale.

## Tasks

| ID | Specs | Task | Depends | Route | Commit |
| --- | --- | --- | --- | --- | --- |
| T1 | S1, S2, S3 | The migration: nullable `customer_id`, the placeholder removed with a surviving-reference refusal, `company_id` backfilled by proof with a loud refusal for the ambiguous rows, `NOT NULL`, the policy, the unique key | — | worker | `feat(customers)` |
| T2 | S4 | The scoping of the customer endpoints: `(company_id, id)` reads, 404 across companies, a scoped list and search | T1 | worker | `feat(customers)` |
| T3 | S5 | The composite pointers, measured from `pg_constraint` rather than assumed, with the policies re-checked around the window | T1 | worker | `feat(customers)` |
| T4 | S6 | The sale states a customer or none: the request's default removed, the response and the printed sale, the Angular page and its specs | T2, T3 | worker | `feat(customers)` |
| T5 | — | Verification across the whole API suite, the Angular unit suite and lint, and the record's delivery entry | T4 | inline | `test(customers)` |

**Total task count: 5.**

## Slices

| Slice | Tasks | What it makes true | Ships alone? |
| --- | --- | --- | --- |
| **A** The table and its pointers | T1, T3 | S1, S2, S3, S5. The hole is closed at the schema and the ambiguous rows are refused | No — the readers are unscoped and would read nothing |
| **B** The readers | T2 | S4. Every endpoint is scoped and the list stops crossing tenants | No — it needs A's policy to mean anything, so A and B land in **one** pull request |
| **C** The sale states none | T4 | S6. The placeholder is gone from the API and the page | Yes, once A+B land |

## Log

**L1 (2026-11-14) — why this unit exists, and the two decisions already taken.** While slicing A1 of `odd/tasks/cfdi-fiscal-document.md`, the receiver's fiscal profile (`S2` of that record) ran into this table: `customers` is the only tenant table in this tree with no `company_id` and no policy — absent from `V53`'s armed list — and `GET /api/customers` returns **every tenant's** customers to any `lc-sales` caller, with name, email, phone and `rfc`. The maintainer decided, first, to arm the table as its own unit before the fiscal profile lands on it («Armar customers primero, como unidad propia»), and second, asked what happens to the seeded `'Cliente General'`, chose to **delete the placeholder** and make the sale's customer optional («Recomendado: cliente nulo = mostrador»). The second decision is what `S2` and `S6` implement, and it is the reading the fiscal record's own `S2` argues for: "a cash sale needs none and an invoice cannot exist without one" — the fictitious customer existed to satisfy a `NOT NULL`, not a fact of the business.

**L2 (2026-11-14) — `S2` and `S6` are one delivery, and the slice boundary above was wrong about it.** Slice A+B came back green and with a defect the plan created: `V105` deletes the seeded placeholder while `SalesOrderRequest` still defaults a missing `customerId` to that row, so between A+B and C a sale that omits the customer answers **404** and the Angular page that fetches the fixed id gets a 404 of its own. The coupling is not incidental — the placeholder exists **because** the column was `NOT NULL` and the request defaulted to it, so making the customer optional (`S6`) and removing the row (`S2`) are one fact in two halves. The boundary therefore moves: **A, B and C land in one pull request**. The alternative — keeping the row until C lands — is impossible, because once `customers.company_id` is `NOT NULL` the placeholder has to belong to some company, and choosing that company is exactly the guess `S3` refuses. Recorded so the next plan does not cut a slice through a row that another slice's reader still names.

**L3 (2026-11-14) — the unit is written as one code commit and one record commit, and three things the plan did not predict.** The slices `A`, `B` and `C` are one commit, not three, for the reason `L2` states: a commit whose tip deletes the placeholder while the request still defaults to it has no reader that works. Four findings are recorded rather than smoothed over. (a) **`companies` needed a `NO FORCE` window too**, and a failing run is what taught it: `ALTER TABLE … ADD CONSTRAINT FOREIGN KEY` validates its rows by **reading the referenced table**, and with the policy in front of that read and no scope stated every company looks absent, so the constraint refused the migration and blamed the data. `V105` therefore windows three tables — `sales_orders`, `customers` and `companies` — and says why. (b) **`scheduling_appointments` references customers and cannot take the composite pointer**: it has no `company_id` (its tenant derives from `company_store_id`) and no policy arms it, so `V106` names the exception, asserts the table is still unarmed, and would refuse a future arming that forgot it — a measured omission, not a silent skip. (c) **The customer UI did not exist**: `CustomerSelector` and the header form were rendered nowhere in the app, so the fixed-id fetch was pre-selecting a control no template displayed — the fabricated receiver was invisible on screen and visible only in the database. The page now renders the selector and states «Mostrador» when the sale names nobody, and the operator can finally pick a receiver. (d) **`npm ci` in the Angular package rewrites the clone-shared `core.hooksPath`** to `life-control-app-angular/.husky/_` through its `prepare` script, which silently disables the documented `.githooks` default-branch guard for the whole clone; it was restored by hand and the fix belongs in that package's `prepare`, not in this unit.

**Evidence.** `spotlessCheck` clean; the whole API suite **1131 classes / 4905 tests / 0 failures** in 18m20s (measured once, alone: the count is `main`'s 1130 plus the one new suite, and no test class was deleted); the Angular unit suite **200 files / 4234 tests / 0 failures** with the coverage gate at exit 0, plus `prettier --check` and `npm run lint` clean. The first Angular run had 10 failures across 7 files, every one a **20-second timeout** in specs this unit does not touch — they were run while the API suite held the machine, and the isolated rerun passed all 200 files, which is why contention is recorded here as a false failure and not as a defect.
