# ODD feature: derived-accounting

**Repository**: LifeControl — `life-control-api` (migrations, a new `accounting` module, the `salesorder` and `inventory` read paths), `odd/tasks/`
**Branch (planned)**: `feat/derived-accounting`, cut from `origin/main` after `docs/adr-fiscal-and-accounting` merges
**Status**: **deferred** — the unit implements `docs/adr/0008-accounting-is-a-projection.md`, accepted 2026-11-12, and no task of it has been written: what remains is the whole unit, in the five slices `## Slices` orders. Nothing is blocked and nothing is uncertain — the deferral is sequencing, not doubt. The first slice (B1, T1–T3) has no open decision and no dependency, which is why it is the one to start.
**Created**: 2026-11-12 · **Risk**: items 1, 4 and 6 — item 1 because the book is append-only financial data where a wrong entry is corrected by a reversing entry and never by an edit, so a defect is not deletable; item 4 because postings fire inside the sale's own transaction, and a trigger that blocks or deadlocks the charge breaks the money path that `V87`'s guard exists to protect; item 6 because a posting rule that is missing produces no error at read time — the books simply come out short, and no test that only reads what was posted can tell that from a period with less revenue.

## Objective

Turn the append-only facts this tree already writes — `sale_payment`, `stock_movement`, `stock_allocation`, `status_transition` — into a double-entry book derived in the database, with the chart of accounts and the posting rules declared as data, a cost-center axis on every line, and read models for the sales journal, the trial balance and gross margin by document and by store.

## Problem — measured in the tree, not quoted from the ADR

| Fact | Evidence |
| --- | --- |
| There is no accounting at all | Zero matches for a chart of accounts, journal entry, debit/credit pair, trial balance or cost-of-goods-sold table across `life-control-api/src/main` |
| The cost of every sale is computed and never read | `stock_allocation.unit_cost` is frozen and append-only, and no Java code reads the table; `sales_order_items` has no cost column |
| Four tables are already a book of record | `sale_payment` and `sale_payment_void` refuse `UPDATE`/`DELETE` by trigger **and** by privilege; `stock_movement` and `stock_allocation` the same; `status_transition` carries `occurred_at` and is append-only |
| The sales document is not | `sales_orders` is editable until terminal, soft-deleted, re-enablable, and its line set is replaced wholesale by the `PUT` |
| No report knows a date range | The sales controller is paginated CRUD with a search on the number; no query groups by day, month or store |
| A report read as the owner is silently empty | `sales_orders` is under `ENABLE`+`FORCE` RLS, so the owner reads zero rows and prints a clean dataset — the reason `docker/scripts/_preflight_reader.sh` refuses the owner as a reader |
| The costing policy is per tenant and not retroactive | `companies.costing_method` is `FIFO` by default and switching it does not rewrite an allocation already written |

## Specs

**S1** — Six tables, and every one that belongs to a tenant carries `company_id` and is armed with `apply_tenant_isolation` in the file that creates it: `account`, `cost_center`, `accounting_period`, `posting_rule`, `account_entry`, `account_entry_line`. The line carries its own `company_id` rather than deriving it through the entry — the hole `sales_order_items` has, where the absence of the column is the absence of the policy. The seventh catalog of the ADR's schema, `department`, is **not created**: it reuses `departments` (`V19__hr_org_structure.sql:15-30`), already company-scoped and already armed (`V53:132`), and the migration adds `uq_departments_company_id UNIQUE (company_id, id)` to it so a pointer can be composite — the shape `V88:51-52` wrote for `tax_rate`.

**S2** — `account_entry` and `account_entry_line` are append-only on the same two halves `V83` uses for payments: a trigger refusing `UPDATE` and `DELETE`, and a `REVOKE`/`GRANT` pair leaving the pool's role with `SELECT, INSERT`. Both halves are required: the pool inherits all four privileges, so the restrictive half has to be written or it does not exist.

**S3** — Every entry balances. `SUM(debit) = SUM(credit)` per entry is a **deferred constraint trigger** at commit, not a service check, so a batch that writes an entry across several statements is judged on the state it commits rather than on each intermediate one.

**S4** — Every entry has at least two lines, enforced in the same pass as S3.

**S5** — No line carries both a debit and a credit, and none is zero: two `CHECK`s on the row.

**S6** — One posting per `(company_id, source_type, source_id, posting)`, as a `UNIQUE` constraint. Replaying a job is a no-op, never a second entry.

**S7** — A pointer that bypasses row security is never an id: `cost_center` names `departments (company_id, id)`, and `account_entry_line` names `account_entry (company_id, id)`, `account (company_id, id)` and `cost_center (company_id, id)`, each through a composite foreign key over a `UNIQUE (company_id, id)` on the target. The reason is the same for all four: a referential check bypasses row security, so an id-only pointer lets one tenant name another's row, and the RLS policy on the referring table cannot see it because the row it writes is legitimately its own. S7 originally named only the department and the cost center; the other two are the same invariant over the same kind of column, and closing two of four would leave the hole open exactly where a report joins.

**S8** — A cost center is unique per company by store and department, and a center with no store is unique by department alone: two **partial** unique indexes, because PostgreSQL treats nulls as distinct and one composite key would admit a second storeless center for the same department — the defect `V95` closed for a tax rate whose region is null.

**S9** — The catalogs are seeded, not user-invented: the minimum chart of accounts and the posting rules, applied by the migration for existing tenants and by the same seed when a company is created, so a tenant added tomorrow does not arrive without a book. There is no starter department catalog here, because the department is `departments` and its seeding is the HR catalog's own concern.

**S10** — A table that moves money and has no `posting_rule` row stops the application at startup, the shape ADR-0005 already uses for tenant arming. A missing wiring is invisible at read time and expensive at audit time.

**S11** — Revenue posts from the transition to `COMPLETED` in `status_transition`, not from the order row: the transition is append-only and the order is not, so the anchor is the transition. The entry carries the document's date, its currency, and the revenue, `IVA trasladado` and receivable parts the document already states.

**S12** — Collection posts from a `sale_payment` row, net of `sale_payment_void`: a voided payment is excluded by the same predicate `netPaid` already uses. A refund, when a writer exists, reverses through the same rule and never edits the original entry.

**S13** — Cost of goods sold posts from the allocation sum: `Σ quantity × unit_cost` over the `stock_allocation` rows of the outbound movement whose `source_type`/`source_id`/`write_seq` name the sale line. No cost column is added to `sales_order_items`: the frozen fact already exists and a second copy is a second thing that can disagree with it.

**S14** — The cost center is pre-filled from the store the fact already carries, and the department is a selection: it is the one value on this book a person supplies. A posting whose source fact carries no store leaves it null instead of guessing.

**S15** — A sale charged before the ledger filled has **no** cost, and the report says unknown rather than zero. Its cost must not be inferred from `product_variants.cost_price`, which holds today's number and would silently rewrite a past margin.

**S16** — A posting whose business date falls in a closed `accounting_period` is refused. A period is a row per company and month with `closed_at`; closing is a state, never a delete.

**S17** — A correction is a reversing entry that names the entry it reverses, and it lands in the first open period. Nothing in the book is ever amended — the shape `sale_payment_void` already established for payments.

**S18** — The three read models — sales journal by date range, trial balance by period, gross margin by document and by store — read under the transaction scope the pool installs, never as `lifecontrol_owner`, because a report as the owner returns zero rows and looks clean. Each states its own limitation when the underlying data does not reach that far.

## Tasks

| ID | Specs | Task | Depends | Route | Commit |
| --- | --- | --- | --- | --- | --- |
| T1 | S1, S7, S8 | Migration: the six tables, `company_id` on every tenant table, the policies, the three composite keys (including `uq_departments_company_id` on the catalog that already exists) and the two partial indexes | — | worker | `feat(accounting)` |
| T2 | S2 | The append-only pair on the two entry tables, trigger plus grant, copied from the payment tables rather than re-invented | T1 | inline | `feat(accounting)` |
| T3 | S3, S4, S5, S6 | The constraint triggers and `CHECK`s: balance, minimum two lines, debit/credit exclusivity, one posting per source | T1 | worker | `feat(accounting)` |
| T4 | S9 | The starter seed, per tenant and on company creation | T1 | inline | `feat(accounting)` |
| T5 | S10 | The startup assertion that refuses a money-moving table with no rule | T3, T4 | inline | `feat(accounting)` |
| T6 | S11 | The revenue rule, anchored on the transition to `COMPLETED` | T3, T5 | worker | `feat(accounting)` |
| T7 | S12 | The collection rule, anchored on the payment row and net of its void | T3, T5 | worker | `feat(accounting)` |
| T8 | S13, S14, S15 | The cost-of-sales rule, the cost-center pre-fill, and the unknown-cost case for pre-cut-over sales | T6 | worker | `feat(accounting)` |
| T9 | S18 | The sales journal and the trial balance, under the tenant scope | T6, T7, T8 | worker | `feat(accounting)` |
| T10 | S18 | Gross margin by document and by store | T8, T9 | inline | `feat(accounting)` |
| T11 | S16, S17 | Period close and the refusal it causes | T9 | worker | `feat(accounting)` |
| T12 | S17 | The reversal path, and the read model that names what an entry reverses | T11 | worker | `feat(accounting)` |

**Total task count: 12.**

## Slices

Five chains, in the order that minimizes rework. Each slice is verified on its own tip before its pull request opens, and each stays under the ~400-authored-line review budget.

| Slice | Tasks | What it makes true | Ships alone? |
| --- | --- | --- | --- |
| **B1** The book | T1, T2, T3 | S1–S8. An empty book that cannot be unbalanced, amended or cross-tenant | Yes — no posting yet, nothing changes for a user |
| **B2** Catalogs | T4, T5 | S9, S10 | Yes |
| **B3** The three rules | T6, T7, T8 | S11–S15 — revenue, collection and cost, with the analytic axis | No, and it is the slice that delivers the value: gross margin from data that is already stored |
| **B4** Reports | T9, T10 | S18 | No |
| **B5** Close | T11, T12 | S16, S17 | No — closing an empty book proves nothing, which is why it is last |

## Log

**L1 (2026-11-12) — the request that produced this unit, in the operator's words.** «puedes analizar el modulo de ventas y ver que le faltaria para generar una factura electronica, y en temas de contabilidad que falta crear o mejorar, simplificar». The "simplificar" is load-bearing: the shape chosen derives the book instead of capturing it, refuses a receivables table in favour of a balance, and refuses a cost column in favour of the allocation that already holds it.

**L2 (2026-11-12) — the decision, and where it lives.** `docs/adr/0008-accounting-is-a-projection.md`, accepted by the maintainer that day, together with `docs/adr/0007-fiscal-document-is-separate.md`. Both shipped in the pull request that carries this record.

**L3 (2026-11-12) — the two corrections the maintainer made while the ADR was being written, and what each cost.** «pueden ser varios centros de costros por tienda» turned the catalog's key from the store into the pair, which is why S8 is two partial indexes rather than one composite. «no hay datos productivos por lo que no hay que preocuparse por migración de datos» removed the whole backfill question: the book starts at its first posting, and S15 survives only as a caveat for the development volume.

**L4 (2026-11-12) — the department, decided in the operator's words.** «Hacer del departamento un catálogo por empresa, y que el centro apunte a él». The alternative was free text with a normalising check; it was refused because a typo does not fail there, it creates a second center and splits a report with nothing raising. The cost is one more catalog to seed, which is S9.

**L5 (2026-11-12) — why this record opens with `deferred`.** Its work is fully specified and none of it is written, so `implemented` would be false in the one place nobody revisits; and nothing is `blocked`, because the ADR is accepted and B1 has no dependency and no open decision. The header states what the unit is and what remains; delivery evidence belongs in dated rows appended here, one per slice as each lands.

**L6 (2026-11-12) — the department catalog is the one that already exists, decided before B1 was written.** The ADR's schema created a `department` table; the tree already has `departments` (`V19__hr_org_structure.sql:15-30`), company-scoped, unique by `(company_id, department_code)` and `(company_id, department_name)`, armed by `V53:132`, with a live CRUD at `DepartmentController.java:28`. The ADR was written without naming it — its evidence table claims zero accounting matches and never looks at V19. The maintainer's answer, verbatim: «Reusar `departments` y corregir ADR-0008 + S1/S9». Consequence: `cost_center.department_id` points at `departments` through a composite key, `departments` gains `uq_departments_company_id UNIQUE (company_id, id)` (the `V88:51-52` shape), S1 names six tables instead of seven, S9 drops the starter department catalog, and the ADR carries a dated amendment. The alternative refused was a second catalog with the same meaning, which is the defect decision 10 argues against one layer up: an employee would hang from `departments` and a cost center from `department`, and nothing would make them agree.

**L7 (2026-11-12) — S7 closes four pointers, not the two it named.** The ADR argues composite keys from one premise — *a referential check bypasses row security* — and stopped at the department and the cost center. `account_entry_line` also names an `account_entry` and an `account`, and those are the same premise: an id-only pointer resolves across tenants while the row it writes is legitimately its own, so the policy on the referring table has nothing to refuse. Stated before the migration, because adding a composite key to a table with rows in it is the expensive version. Cost: three redundant `UNIQUE (company_id, id)` keys and two more foreign keys.
