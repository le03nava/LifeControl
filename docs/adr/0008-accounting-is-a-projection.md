# ADR 0008 — Accounting is a projection of the ledger, not a second book

- **Status**: accepted
- **Date**: 2026-11-12
- **Accepted**: 2026-11-12, by the maintainer, after the three corrections recorded under *Corrections applied before acceptance*
- **Decision id**: ADR-0008, written from the sales/fiscal/accounting review of 2026-11-12
- **Depends on**: ADR-0001 (the inventory ledger is the only place a consumed unit's cost exists), ADR-0002
  (money in parts, currency on the document, append-only payment rows), ADR-0005 (the tenant machinery every
  table here has to be armed in), ADR-0007 (the fiscal document, which this ADR deliberately does **not**
  treat as an accounting event)
- **Blocks**: nothing yet. It is the last model-level decision of the commerce core and it consumes the other
  four
- **Replaces**: nothing. There is no accounting code in this tree to replace

## Context

The sales module records money. It does not record what that money means, and the difference is not a gap in
a module — the module does not exist. Eight facts, measured on `main` @ `715f5d6`, decide the shape:

| # | Evidence | What it means |
| --- | --- | --- |
| 1 | Any chart of accounts, journal entry, debit/credit pair, trial balance or cost-of-goods-sold table: **zero grep matches** across `life-control-api/src/main` | There is nothing to migrate. This ADR closes a model, it does not refactor one |
| 2 | `V60__stock_ledger_schema.sql:182` creates `stock_allocation` with `quantity` and `unit_cost`, and `:280` arms it append-only; `V61__stock_ledger_engine.sql:567-570` writes it with `COALESCE(v_average, v_supply.unit_cost)` | The consumed cost of every unit is already frozen, per outbound/inbound pair, and cannot be rewritten afterwards |
| 3 | `V83__sale_payment.sql:143-167` closes the same loop for money twice over: a trigger that refuses `UPDATE`/`DELETE` and a `REVOKE`/`GRANT` that leaves the pool's role with `SELECT, INSERT` only | "Append-only" in this tree is not a convention, it is an enforced pair, and the pattern to copy already exists |
| 4 | `V84__sales_orders_total_is_its_parts.sql:82-85` adds `ck_order_total`, the equality of the four parts of a document | The document states its own arithmetic, in the database, verified by constraint. An accounting entry would be a third statement of the same numbers |
| 5 | `V1__baseline_schema.sql:470-482` declares `sales_orders` with `enabled`, `created_at` and `updated_at`; the entity refuses edits only once a document is terminal, lines are replaced wholesale by the `PUT`, and a deleted order can be re-enabled | A sales order is **not** a book of record. It is a working document that becomes stable, and the difference matters to anyone who wants to post from it |
| 6 | `V67__status_transition.sql:48` creates `status_transition`, which is append-only and carries `occurred_at` | The moment a document changed state is a fact that cannot be edited, which is the only anchor an accrual can trust |
| 7 | `V60__stock_ledger_schema.sql:250` adds `companies.costing_method` with `DEFAULT 'FIFO'`, and ADR-0001 states that changing it is not retroactive | The costing policy is a tenant decision, and the frozen allocations honour the policy that was in force when they were written |
| 8 | `stock_valuation` holds `total_value` and `avg_unit_cost` and carries **no date column**, and the pre-`V62` path wrote `ADJUSTMENT_IN` rows only, so no sale charged before that cut-over has a `SALE` movement or an allocation | A valuation "as of a date" is a replay, not a read; and any backfill of cost covers post-`V62` sales only |

Two consequences of that table are the whole argument:

**The cost of a sale is computed and thrown away.** `stock_allocation` holds it, frozen and append-only, keyed
by an outbound movement whose `source_type = 'SALES_ORDER_ITEM'` points at the exact sale line. No Java code
reads that table, and `sales_order_items` has no cost column, so the gross margin of every sale in this tree
is currently unreadable without a hand-written query.

**The only accounting-grade tables are the ones nobody edited.** Payments, stock movements, allocations and
state transitions are append-only and therefore trustworthy as a source. Orders and their lines are mutable
working documents and are therefore not. A design that posts from the document inherits the document's
mutability; a design that posts from the append-only facts inherits their immutability. Only one of those can
be reconciled a year later.

## Decision

**Accounting in this system is a projection derived in the database from the append-only facts, never a set of
documents a person captures and edits.**

1. **Two tables are the book: an entry and its lines.** `account_entry` carries the business date, the tenant,
   the source fact it derives from and the rule that produced it. `account_entry_line` carries the account,
   the debit or the credit, and the currency. There is no third table and no header that is not an entry.

2. **The book is append-only, enforced the way `sale_payment` is.** Both tables refuse `UPDATE` and `DELETE`
   by trigger, and are granted `SELECT, INSERT` to the pool's role. A mistake is corrected by a new entry
   that reverses the old one. An entry is never amended, because an amended entry is a book with two
   histories.

3. **Every posting derives from an append-only source, never from a mutable document.** The anchors are:
   the transition to a terminal state for revenue, a payment row for collection, a `stock_allocation` row
   for cost of goods sold. Each entry names its source with `(source_type, source_id)` and each rule may
   post **once** per source — a unique constraint, not a convention, so a replayed job cannot double-book.

4. **The posting rules are declared as data, in one table, and a money-moving table nobody posts is refused
   at startup.** `posting_rule` maps a source type plus a tenant to the debit account, the credit account and
   the amount selector. The startup assertion mirrors ADR-0005's: a table that moves money and has no rule
   fails the application's boot rather than silently leaving the books short. This is the same shape the tree
   already uses for tenant arming, for the same reason — a missing wiring is invisible until it is expensive.

5. **Revenue accrues at the sale, not at the invoice.** The document is `COMPLETED` when the goods left and
   the money was taken, and that is the accrual event. ADR-0007 makes the fiscal document a separate
   artefact; it is the fiscal evidence of a sale, not the reason revenue exists. Consequences, all intended:
   stamping an old sale in a later month does not move revenue, and a sale that is never invoiced still has
   revenue.

6. **Accounts receivable is a balance, not a table.** Collection is a second posting against the same
   receivable account. The outstanding amount of a sale is the difference between its revenue entry and its
   collection entries. No `accounts_receivable` table, no aging table, no `due_date` column exists, because a
   balance over an append-only book cannot disagree with the book. If credit sales are ever enabled, they
   arrive as a later collection posting, not as a new schema.

7. **Cost of goods sold is derived from the allocation, and no cost column is added to the line.** The
   allocation is already frozen and append-only, and a second copy of a frozen fact is a second thing that
   can disagree with it — exactly what ADR-0001 refused for balances. The join is exact:
   outbound movement, `source_type`/`source_id`/`write_seq`, sum of `quantity × unit_cost`. A per-line cost
   snapshot is refused for the same reason it is refused for stock balances: the projection is the answer,
   and the ledger is the truth.

8. **A posting is refused when the period is closed.** A `accounting_period` row per tenant and month carries
   `closed_at`. An entry whose business date falls in a closed period raises an error; a correction lands in
   the first open period and names the entry it corrects. This is the cheapest moment to declare it — the
   alternative, adding a lock to a book with history in it, cannot be done later without rewriting dates.

9. **Every accounting table carries `company_id` and is armed, and the reports read under the tenant scope.**
   The lesson is already in this tree: `sales_order_items` has no `company_id` and therefore no policy, so
   tenancy has to be derived through its parent. Accounting does not repeat that. And a report over
   `sales_orders` run as the owner reads **zero rows and looks clean**, which is why the report surfaces read
   under the transaction scope the pool installs, never as `lifecontrol_owner`.

10. **The analytic axis is a column on the line, and a cost center is a store plus a department.** Several
    centers belong to one store, so the catalog holds the pair — a store and a department — and the store is
    nullable for the center that belongs to none. The department is a **catalog per company**, never free
    text, because a typo in a text column does not fail: it creates a second center and splits a report in
    two with nothing raising. The axis sits on `account_entry_line`, nullable, with a composite key to the
    tenant, never on the entry header: one entry can split across units and a header cannot grow that later.
    Filling it is a selection, not a derivation — the store the fact already carries is pre-filled, and the
    department is chosen by a person. It is the one fact on this book a human supplies, and the ADR says so
    rather than pretending the whole axis is derived.

## Schema

```sql
CREATE TABLE account (
    id          UUID PRIMARY KEY,
    company_id  UUID NOT NULL REFERENCES companies (id),
    code        VARCHAR(20)  NOT NULL,
    name        VARCHAR(120) NOT NULL,
    kind        VARCHAR(10)  NOT NULL
                CHECK (kind IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    enabled     BOOLEAN NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (company_id, code)
);

-- The department catalog is NOT created here: it is `departments`, which `V19__hr_org_structure.sql:15-30`
-- already creates company-scoped, unique by `(company_id, department_code)` and `(company_id, department_name)`,
-- and which `V53:132` already arms. The one thing a composite pointer needs and V19 does not state is the
-- redundant-but-necessary key, exactly what `V88:51-52` added to `tax_rate` for the same reason.
ALTER TABLE departments
    ADD CONSTRAINT uq_departments_company_id UNIQUE (company_id, id);

CREATE TABLE cost_center (
    id               UUID PRIMARY KEY,
    company_id       UUID NOT NULL REFERENCES companies (id),
    code             VARCHAR(20)  NOT NULL,
    name             VARCHAR(120) NOT NULL,
    company_store_id UUID,                 -- null when the center belongs to no store
    department_id    UUID NOT NULL,
    enabled          BOOLEAN NOT NULL DEFAULT true,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (company_id, code),
    FOREIGN KEY (company_id, department_id) REFERENCES departments (company_id, id)
);

-- Several centers per store, so the pair is the key and not the store. Two partial indexes instead of one
-- composite: nulls are distinct to a unique constraint, so the composite would silently admit a second
-- storeless center for the same department.
CREATE UNIQUE INDEX uq_cost_center_store_department
    ON cost_center (company_id, company_store_id, department_id) WHERE company_store_id IS NOT NULL;
CREATE UNIQUE INDEX uq_cost_center_company_department
    ON cost_center (company_id, department_id) WHERE company_store_id IS NULL;

CREATE TABLE accounting_period (
    id          UUID PRIMARY KEY,
    company_id  UUID NOT NULL REFERENCES companies (id),
    period      DATE NOT NULL,          -- first day of the month
    closed_at   TIMESTAMPTZ,
    closed_by   VARCHAR(255),
    UNIQUE (company_id, period)
);

CREATE TABLE posting_rule (
    id              UUID PRIMARY KEY,
    company_id      UUID NOT NULL REFERENCES companies (id),
    source_type     VARCHAR(40) NOT NULL,
    posting         VARCHAR(40) NOT NULL,   -- 'REVENUE', 'COLLECTION', 'COST_OF_SALES', 'REVERSAL'
    debit_account_id  UUID NOT NULL REFERENCES account (id),
    credit_account_id UUID NOT NULL REFERENCES account (id),
    enabled         BOOLEAN NOT NULL DEFAULT true,
    UNIQUE (company_id, source_type, posting)
);

CREATE TABLE account_entry (
    id           UUID PRIMARY KEY,
    company_id   UUID NOT NULL REFERENCES companies (id),
    entry_date   DATE NOT NULL,
    source_type  VARCHAR(40) NOT NULL,
    source_id    UUID NOT NULL,
    posting      VARCHAR(40) NOT NULL,
    reverses_entry_id UUID REFERENCES account_entry (id),
    memo         TEXT,
    actor        VARCHAR(255) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (company_id, source_type, source_id, posting)
);

CREATE TABLE account_entry_line (
    id          UUID PRIMARY KEY,
    company_id  UUID NOT NULL REFERENCES companies (id),   -- never derived through the entry
    entry_id    UUID NOT NULL REFERENCES account_entry (id),
    account_id  UUID NOT NULL REFERENCES account (id),
    cost_center_id UUID,                   -- the analytic axis; the store is the usual value
    debit       NUMERIC(19,4) NOT NULL DEFAULT 0,
    credit      NUMERIC(19,4) NOT NULL DEFAULT 0,
    currency    CHAR(3) NOT NULL REFERENCES currency (code),
    CHECK (debit >= 0 AND credit >= 0),
    CHECK ((debit = 0) <> (credit = 0)),
    CHECK (debit + credit > 0)
);
```

The two partial indexes above are not a stylistic choice. A unique constraint treats nulls as distinct, so
one composite key over a nullable scope admits duplicates exactly where there is no value to compare — the
defect `V95__tax_rate_company_region.sql` closed for a `tax_rate` whose `company_region_id` is null, with the
same pair of partial indexes. The catalog inherits that decision instead of rediscovering it.

The composite key to `departments` follows `V88__variant_listing_tax_rate.sql`, which first wrote
`uq_tax_rate_company_id` on `tax_rate` so a pointer could be composite and therefore tenant-safe. The catalog
reuses it for the same reason, and `account_entry_line` reuses it for the cost center.

Every table above that belongs to a tenant is armed with `apply_tenant_isolation` in the file that creates
it — the rule ADR-0005 already made a startup assertion.

Both entry tables get the append-only pair, copied rather than re-invented: `V83__sale_payment.sql:143-167`
refuses `UPDATE` and `DELETE` on `sale_payment` and `sale_payment_void` with a trigger, and takes those two
privileges away from the pool's role so that only `SELECT` and `INSERT` remain.

The two catalogs, `posting_rule` and `account`, are editable on purpose and get neither half.

## Invariants (each one testable)

| # | Invariant | Enforced by |
| --- | --- | --- |
| A1 | Every entry balances: `SUM(debit) = SUM(credit)` per entry | Deferred constraint trigger at commit, not a service check |
| A2 | Every entry has at least two lines | Constraint trigger, same pass as A1 |
| A3 | No line carries both a debit and a credit, and none is zero | `CHECK` on the row |
| A4 | An entry, once written, cannot be updated or deleted | Trigger plus `REVOKE`, exactly as payments |
| A5 | One posting per `(source_type, source_id, posting)` — replay is a no-op, never a second entry | `UNIQUE` constraint |
| A6 | An entry whose `entry_date` falls in a closed period is refused | Trigger against `accounting_period` |
| A7 | Every entry line carries the company of its entry, and a foreign line cannot be inserted | `company_id` on the line plus the RLS policy that reads it |
| A8 | A table that moves money has a `posting_rule` row, or the application does not start | Startup assertion, the shape `V52` already uses for tenant arming |
| A9 | A cost center belongs to the company of the line that names it, and another tenant's is unreachable even by id | Composite foreign key on `(company_id, cost_center_id)`, plus the policy on both tables |
| A10 | A center is unique per company by store and department, and a center with no store is unique by department alone | The two partial unique indexes, which one composite key could not express |
| A11 | A center's department belongs to the same company, and a department of another tenant is unreachable by id | Composite foreign key on `(company_id, department_id)` |

## Migration from the current tree

Nothing is rewritten and nothing is backfilled by this ADR itself. It orders five slices, each one delivering
value on its own:

| Step | Slice | What it makes true |
| --- | --- | --- |
| 1 | Schema, RLS, append-only pair, the two constraint triggers, and the department and cost-center catalogs | A1–A4 and A7–A11. No posting yet, so the tables are empty and every invariant is testable on them |
| 2 | The `department` starter catalog, and `account` and `posting_rule` seeded per tenant with the closed minimum vocabulary: cash, bank, accounts receivable, IVA trasladado, IVA acreditable, inventory, cost of sales, revenue. The same seed runs when a company is created | A8 can fire, and A11 has departments to point at — the departments the tenant already has, since the catalog is `departments` and not a second one. The vocabulary is seeded, not user-invented, so no tenant starts with a chart of accounts it cannot post to — and a tenant added tomorrow does not arrive without a book |
| 3 | The three rules that close gross margin: `REVENUE` from the transition to `COMPLETED`, `COLLECTION` from a payment row, `COST_OF_SALES` from the allocation sum, each filling the analytic axis from the store the fact already carries | Revenue − cost = margin, per document, per period and per store. This is the step that turns the existing data into accounting |
| 4 | Read models: sales journal by date range, trial balance by period, margin by document. Read under the tenant scope; never as the owner | The book becomes readable. The date-range query does not exist today at all — sales is paginated CRUD with a search on the number |
| 5 | `accounting_period` close | A6, and the only affordable moment to introduce it |

Three limits are stated here rather than discovered later:

- **There is no production dataset to premise, and that is the cheapest fact in this ADR.** The tree is
  pre-production, so the book starts at its first posting and there is no historical reconstruction to
  design. The one caveat that survives belongs to the development volume alone: sales charged before the
  ledger filled have no movement and no allocation, so a local margin report reads them as unknown and never
  as zero. Nothing is backfilled, because nothing is live.
- **A valuation as of a date is a replay.** `stock_valuation` is a current projection with no date dimension;
  the report either replays movements up to an instant or states that it is current-only.
- **One currency.** Entry lines carry the currency ADR-0002 put on the document. No functional-currency
  translation and no exchange rate, because ADR-0002 left those out on purpose.

## Rejected alternatives

| Alternative | Why it is refused |
| --- | --- |
| A `journal_entry` CRUD with an editable póliza and a posting UI | It is a second source of truth beside the facts it claims to describe, and it needs reversals, permissions, closed-period guards and an auditor to be trustworthy. The tree already chose the other shape for money: the database derives, the service reads back |
| Posting in Java, in the services that already write money | The rule would live beside each call site and a missed one is a silent hole with no failing test. Line money and purchase money are already derived by trigger, and a second convention for the same problem is how two conventions start disagreeing |
| A `cost_price` column on `sales_order_items` | The frozen cost already exists in `stock_allocation` and is append-only. A copy is a second thing that can drift from the first, which is exactly what ADR-0001 refused for stock balances |
| `accounts_receivable` as a table with `due_date` and aging | A balance over an append-only book cannot disagree with the book; a table can. Aging is a report over dates that already exist, not a column to maintain |
| Deriving revenue from the invoice instead of the sale | It couples the books to a fiscal artefact whose stamp can happen in another month, and it would make revenue disappear when an invoice is cancelled. Revenue is an economic event; the CFDI is its evidence |
| Posting from `sales_orders` directly, without the transition anchor | The order is editable until terminal and re-enablable after deletion, so a posting keyed on it can be invalidated by an edit that produces no new fact to correct |
| A configurable chart of accounts per tenant, editable through the UI in the first slice | No consumer. The chart is seeded and closed; a screen for it is a decision with its own cost and no evidence to justify it yet |
| Multi-currency accounting with a functional currency and revaluation | ADR-0002 left exchange rates out of scope and this ADR does not reopen it. One document, one currency, one entry |

## Out of scope, named

Not deferred by accident — refused here, so that a later slice can name this ADR as the reason it is not
building them:

- **Bank reconciliation, fixed assets, payroll accounting, budgets and consolidation.** Each is its own
  domain with its own decision.
- **A third and a fourth analytic dimension.** One axis, one column. A project or a customer as an
  independent dimension on the same line, beside the store and the department, needs a dimension table and an
  extra join in every report; keeping the axis a column is the whole reason it is affordable before the first
  entry exists.
- **Mexican electronic accounting export** (`contabilidad electrónica`, the SAT XML and the account grouping
  code). The entry and account shapes do not forbid it; a column for the grouping code can be added when
  there is a filing to produce, and asserting the catalog values before then would be a guess.
- **Tax declarations** (the DIOT and any periodic return). This ADR accrues the liability; filing it is a
  different product.
- **Drawer reconciliation** — opening float, counted cash, variance. ADR-0002 deferred it to its own ADR and
  this one confirms the deferral: `shifts` has no money columns at all, so the decision is not a table away.
- **Immutability of the sales order itself.** The order stays a working document. What is immutable is the
  transition, the payment, the movement and the allocation, and that is enough to post from. Making the order
  immutable is a separate decision with its own cost — an operator who cannot fix a line before charging.
- **A refund and void writer.** The schema supports `REFUND` and `sale_payment_void` has no Java writer at
  all, so a reversal posting has a schema but no trigger. Named here so step 3 is not assumed to cover it.

## Consequences

**What becomes true**

- Gross margin per sale, per day and per period becomes readable from data that already exists, without
  capturing anything new.
- A fiscal document and an accounting entry can each be cancelled, corrected or late without corrupting the
  other, because they derive from the same facts and neither from the other.
- The book survives an operator editing a document, because the book did not read the document.
- The report surfaces force the tenant question now, on tables that were designed with `company_id`, instead
  of inheriting the hole `sales_order_items` has.

**What becomes expensive**

- Every future money-moving table owes a `posting_rule` row, or the application refuses to start. That is the
  point, and it is also a real tax on the next feature: a purchase settlement, a refund, a discount document
  each arrive with their posting decision attached.
- The append-only pair must be written per table, on purpose — `V83__sale_payment.sql:143-167` says why: the
  pool's role inherits all four privileges, so the restrictive half has to be stated or it does not exist.
- Correcting an entry means writing a reversing entry. Anybody expecting to "fix a typo in the books" will be
  told no, and the interface has to say that well.

**What breaks if this is skipped**

- A second money system grows in spreadsheets, keyed by hand from a screen that shows a total, and it will
  disagree with the database the first month one document is re-enabled.
- The cost of every sale stays unreadable, which is the status today, while `stock_allocation` pays for the
  storage of a number nobody reads.

## Corrections applied before acceptance

**2026-11-12 — cost centers moved into the schema.** The first draft refused them as a later decision and
said so under *Out of scope*, while noting that they would change every entry line and were therefore the
one thing to settle before entries existed. The maintainer answered that question — they will be wanted — so
the axis became a nullable column on the line plus a catalog, which costs one column now and a rewrite of
the whole book later.

**2026-11-12 — a center is a store plus a department, several per store, chosen by a person.** The first
correction made the catalog one row per store and called the axis derived. The maintainer corrected both: a
store holds several centers, the second coordinate is the department, and the value is selected manually for
now. Four consequences are recorded rather than smoothed over: the unique key became two partial indexes
because nulls are distinct; the axis became the one fact on this book a human supplies; the dev-data cost
caveat stopped being a migration risk; and the catalog stopped pretending a store is a center.

**2026-11-12 — the department became a catalog per company.** The previous correction left the department as
free text and named the consequence instead of fixing it: two spellings of one department become two centers,
and no unique index can stop it because the values genuinely differ. The maintainer chose the catalog, so the
center now points at a `department` row with a composite key to the tenant, and the typo is refused at write
time instead of discovered in a report. It costs one more catalog to seed per company; the tree already seeds
a starter catalog per tenant elsewhere, so the pattern exists.

Everything above is part of the accepted text. The maintainer accepted this ADR on 2026-11-12 with these
three corrections in place, which is the only thing that turns a draft into authority.

## Amendments after acceptance

**2026-11-12 — the department catalog is `departments`, decided while B1 was being written.** The schema above
created a `department` table with `(company_id, code, name)`. The tree already has exactly that catalog:
`departments` (`V19__hr_org_structure.sql:15-30`), company-scoped, unique by code and by name within the
company, armed by `V53:132`, with a live CRUD at `DepartmentController.java:28`. The draft was written without
looking at V19 — its evidence table measures the absence of accounting tables and never asks whether a
department catalog was already there. The maintainer chose the existing one, so `cost_center.department_id`
points at `departments` through a composite key, `departments` gains the redundant
`uq_departments_company_id UNIQUE (company_id, id)` that V88's shape requires, and no `department` table or
starter department catalog is created. The reason is decision 10 itself: a second catalog with the same meaning
splits a report with nothing raising, and here it would split it between the department an employee hangs from
and the department a cost center names. Recorded in `odd/tasks/derived-accounting.md` as L6.
