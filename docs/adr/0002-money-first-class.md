# ADR 0002 — Money is a first-class value

- **Status**: proposed
- **Date**: 2026-10-29
- **Decision id**: D2 of `odd/tasks/architecture-redesign-adrs.md`
- **Depends on**: ADR-0001 (stock and money book in one transaction), ADR-0003 (it puts
  `sales_orders.company_id` on the document, which the tenant key of these payments references — a
  payment is not a numbered document), ADR-0005 (a tenant row carries a policy; `currency` is a
  platform catalog with none)
- **Replaces**: the `payment_method_id` shape of `V1__baseline_schema.sql:468-482`

## Context

A sale is not a financial record today; it is a document with a number on it.

```java
// SalesOrderService.java:386-446 — chargeSalesOrder, in full
so.setPaymentMethodId(request.paymentMethodId());
so.setStatusId(completedStatus.getId());
salesOrderRepository.save(so);
```

There is a status flip and a foreign key to a catalog row. Everything below is therefore absent,
measured in the tree:

| Missing | Consequence |
| --- | --- |
| A payment row (amount, tender, reference, state) | No mixed tender, no change for cash, no cash-drawer reconciliation, no way to void one payment without voiding the sale |
| Any currency column | `grep -c currency src/main/resources/db/migration/*.sql` returns **0** in 51 migrations. Every amount is a bare `DECIMAL(12,2)` |
| Tax breakdown | `products.sat_code` exists and no rate or amount does. A total is a single number with no parts |
| Scale | `12,2` cannot hold a unit price such as 0,0375 per gram, nor a quantity such as 0,375 g, so the rounding lands on the line instead of the document |

`promotions` (V1:424-437) stores a `discount_type`/`discount_value`, and `sales_order_items` stores
`list_price`, `discount_applied` and `final_price` (`V1:499-509`). Those two facts are the good half:
the line already snapshots its price. What is missing is the layer above and below it.

The money that exists outside a sales document is measured here and scoped out, because this ADR is
one decision and not five:

| Column | Table |
| --- | --- |
| `purchase_cost` | `products` (`V1:208`) |
| `list_price`, `cost_price` | `product_variants` (`V1:407-408`) |
| `unit_price`, `total` | `purchase_order_items` (`V1:346-347`) |
| `discount_value` | `promotions` (`V1:428`) |

`purchase_orders` already carries `company_id` and pins it to its store with
`fk_purchase_orders_store_same_company (company_id, company_store_id) -> company_stores (company_id, id)`
(`V51:86-90`). `sales_orders` does not carry `company_id` at all (`V1:468-482`). This ADR needs both
facts: the pattern to copy, and the column it does not have yet.

## Decision

**Every monetary value on a sales document carries the document's currency, every document carries its
own breakdown, every payment is a row with a direction, and every void is an appended row.**

1. **Amounts are `NUMERIC(19,4)`, and so is `quantity`.** Four decimals absorb a unit price that two
   cannot, and a quantity that a per-gram price implies. The currency decides the display and the
   rounding: the rounding to its `minor_units` happens **once, at the document boundary** — never per
   line, which is how a total stops matching the paper.
2. **A currency is a row, not a string.** `currency` holds the ISO code and the number of minor units
   used to round and print. It is a platform catalog: no `company_id`, no policy, the same treatment
   ADR-0005 gives it. A second currency is a row plus a rate — and the rate table is **not** part of
   this ADR, because a document cannot be in a currency other than the company's operating currency
   until a rate exists to convert it, so shipping the rate now would ship a table with no writer.
3. **A document states its parts.** `subtotal`, `discount_total`, `tax_total`, `rounding_adjustment`
   and `total_amount`, in the document's currency. The invariant
   `total_amount = subtotal − discount_total + tax_total + rounding_adjustment` is a `CHECK` with
   **exact** equality, not a tolerance: every part is `19,4`, so a difference of a ten-thousandth is
   a bug and not a rounding artifact.
4. **A line snapshots everything a reprint needs**: unit price, quantity, subtotal, discount, tax code,
   tax rate, tax amount, line total, and the product/variant names. A ticket printed today and
   reprinted in a year reads the same, whatever the catalog says then. The line carries **no pointer
   to the rate row** and **no currency column**: the first cannot be pinned to the tenant without a
   second `company_id` on the line, and the second could only ever hold the document's value.
5. **A payment is a row, and a sale may have many.** Cash and card in one sale are two rows, each with
   a `direction` of `CHARGE` or `REFUND`. A void is **its own appended row**, never a status column and
   never a deletion: a payment is void if and only if a `sale_payment_void` row names it, so the
   drawer's history is auditable and a void with no replacement is expressible — which the sale
   requires, because a payment taken with the wrong tender has no replacement, only a correction. The
   void row **is** the payment's transition: ADR-0004's `status_transition` records documents that have
   a state column, and a payment deliberately has none, so there is no second row to append.
6. **Tax is a rate snapshot, not a calculation in the UI.** The rate is resolved server-side from the
   latest `tax_rate` row with `effective_from ≤ the document's date` for the company and tax code, and
   is copied onto the line at write time, so a rate change never rewrites history.
7. **The charge is one transaction, serialized on the order.** `chargeSalesOrder` locks the order row,
   validates the state guard of ADR-0004, inserts the payments, computes the breakdown, books the stock
   through ADR-0001's API and appends the transition, all in one transaction. The lock is what makes a
   retried request a no-op instead of a second payment: the retry serializes behind the commit, sees
   `COMPLETED`, and the guard refuses it.

## Schema

```sql
CREATE TABLE currency (
    code         CHAR(3) PRIMARY KEY,           -- ISO 4217
    minor_units  SMALLINT NOT NULL CHECK (minor_units BETWEEN 0 AND 4),
                                                -- 2 for ARS/MXN/USD, 0 for JPY, 3 for KWD/BHD/TND
    symbol       VARCHAR(6),
    enabled      BOOLEAN NOT NULL DEFAULT true);

CREATE TABLE tax_rate (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id     UUID NOT NULL REFERENCES companies (id),
    tax_code       VARCHAR(20) NOT NULL,
    rate           NUMERIC(9,6) NOT NULL CHECK (rate >= 0 AND rate <= 1),   -- 0.210000
    effective_from DATE NOT NULL,
    enabled        BOOLEAN NOT NULL DEFAULT true,
    UNIQUE (company_id, tax_code, effective_from));

-- ADR-0003 owns `doc_type`, `seq_period` and `seq_value`: a document's identity is allocated there,
-- and this ADR only reads it. One ALTER per column, one owner. ADR-0003 also adds `company_id`,
-- because its `(company_id, doc_type, doc_number)` key needs it — so the column is **not** added
-- here; the two constraints that consume it are, and they land after 0003's ALTER.
ALTER TABLE sales_orders
    ADD COLUMN currency       CHAR(3) NOT NULL REFERENCES currency (code),
    ADD COLUMN subtotal       NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN discount_total NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN tax_total      NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN rounding_adjustment NUMERIC(19,4) NOT NULL DEFAULT 0,
    ALTER COLUMN total_amount TYPE NUMERIC(19,4),   -- it exists: DECIMAL(12,2) at V1:478
    ALTER COLUMN total_amount SET NOT NULL,
    ADD CONSTRAINT uq_sales_orders_company_id UNIQUE (company_id, id),
    ADD CONSTRAINT fk_sales_orders_store_same_company
        FOREIGN KEY (company_id, company_store_id)
        REFERENCES company_stores (company_id, id),          -- the V51:86-90 pattern
    ADD CONSTRAINT ck_order_total
        CHECK (total_amount = subtotal - discount_total + tax_total + rounding_adjustment),
    ADD CONSTRAINT ck_order_parts_not_negative
        CHECK (subtotal >= 0 AND discount_total >= 0 AND tax_total >= 0 AND total_amount >= 0),
    -- the local half of M8: one minor unit is the widest bound any currency allows, so a larger
    -- value is wrong in every currency without reading the catalog
    ADD CONSTRAINT ck_order_rounding_bounded CHECK (abs(rounding_adjustment) <= 1);

ALTER TABLE sales_order_items
    ALTER COLUMN quantity         TYPE NUMERIC(19,4),
    ALTER COLUMN list_price       TYPE NUMERIC(19,4),
    ALTER COLUMN discount_applied TYPE NUMERIC(19,4),
    ALTER COLUMN discount_applied SET NOT NULL,   -- nullable today: a NULL would make ck_item_total pass as NULL
    ALTER COLUMN final_price      TYPE NUMERIC(19,4),
    ADD COLUMN subtotal        NUMERIC(19,4) NOT NULL DEFAULT 0,   -- round(list_price * quantity, 4)
    ADD COLUMN tax_code        VARCHAR(20) NOT NULL DEFAULT 'NA',
    ADD COLUMN tax_rate        NUMERIC(9,6) NOT NULL DEFAULT 0,
    ADD COLUMN tax_amount      NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN product_name_snapshot VARCHAR(255),
    ADD COLUMN variant_name_snapshot VARCHAR(255),
    ADD COLUMN attributes_snapshot   JSONB,
    ADD CONSTRAINT ck_item_subtotal CHECK (subtotal = round(list_price * quantity, 4)),
    ADD CONSTRAINT ck_item_tax
        CHECK (tax_amount = round((subtotal - discount_applied) * tax_rate, 4)),
    ADD CONSTRAINT ck_item_total
        CHECK (final_price = subtotal - discount_applied + tax_amount);

CREATE TABLE sale_payment (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id      UUID NOT NULL,
    sales_order_id  UUID NOT NULL,
    method_id       UUID NOT NULL REFERENCES payment_methods (id),
    direction       VARCHAR(8) NOT NULL CHECK (direction IN ('CHARGE', 'REFUND')),
    amount          NUMERIC(19,4) NOT NULL CHECK (amount > 0),   -- the direction carries the sign
    tendered_amount NUMERIC(19,4),                               -- cash only
    change_amount   NUMERIC(19,4),                               -- cash only
    reference       VARCHAR(100),                                -- card auth, transfer id, last four
    paid_at         TIMESTAMPTZ NOT NULL,
    actor           VARCHAR(255) NOT NULL,                       -- who took it; the order's user_id is mutable
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- tenant and order pinned by the schema, not by the service: a payment cannot name another
    -- tenant's order, and cannot disagree with it about the company
    CONSTRAINT fk_payment_order_same_company FOREIGN KEY (company_id, sales_order_id)
        REFERENCES sales_orders (company_id, id),
    CONSTRAINT uq_sale_payment_company_id UNIQUE (company_id, id),
    -- self-consistent on its own; "cash only" is a domain guard, because a PostgreSQL CHECK cannot
    -- read the payment-method catalog (a STABLE function is not admissible inside one)
    CONSTRAINT ck_payment_tender CHECK (
        (direction = 'REFUND' AND tendered_amount IS NULL AND change_amount IS NULL)
        OR (direction = 'CHARGE' AND (
               (tendered_amount IS NULL AND change_amount IS NULL)
            OR (tendered_amount IS NOT NULL AND change_amount IS NOT NULL
                AND tendered_amount >= amount
                AND change_amount = tendered_amount - amount)))));

CREATE INDEX idx_sale_payment_order    ON sale_payment (company_id, sales_order_id);
CREATE INDEX idx_sale_payment_day      ON sale_payment (company_id, paid_at);
CREATE INDEX idx_sale_payment_method   ON sale_payment (company_id, method_id, paid_at);

CREATE TABLE sale_payment_void (
    payment_id             UUID PRIMARY KEY,
    company_id             UUID NOT NULL,
    replacement_payment_id UUID,                                 -- NULL when nothing replaces it
    actor                  VARCHAR(255) NOT NULL,
    reason                 VARCHAR(255) NOT NULL,
    occurred_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_void_payment_same_company FOREIGN KEY (company_id, payment_id)
        REFERENCES sale_payment (company_id, id),
    CONSTRAINT fk_void_replacement_same_company FOREIGN KEY (company_id, replacement_payment_id)
        REFERENCES sale_payment (company_id, id),
    CONSTRAINT ck_void_replacement_differs
        CHECK (replacement_payment_id IS DISTINCT FROM payment_id));

-- append-only twice, exactly as ADR-0004 does it: this trigger, and a grant of INSERT, SELECT only
-- to the application role
CREATE FUNCTION sale_payment_void_append_only() RETURNS trigger AS $$
BEGIN RAISE EXCEPTION 'sale_payment_void is append-only'; END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_sale_payment_void_append_only
    BEFORE UPDATE OR DELETE ON sale_payment_void
    FOR EACH ROW EXECUTE FUNCTION sale_payment_void_append_only();

ALTER TABLE payment_methods ADD COLUMN is_cash BOOLEAN NOT NULL DEFAULT false;
```

The "cash only" half of M4 is a domain guard, not a constraint: a `CHECK` cannot read another table,
so `method_is_cash(method_id)` is not admissible inside one. `payment_methods` is a global catalog
today (`V1:302-309`, no `company_id`), so `is_cash` is a platform fact and not a per-tenant one; the
guard of ADR-0004 reads it. What the constraint still guarantees is that tender and change agree with
the amount, so a card payment cannot carry a change value even by mistake.

## Invariants

| # | Invariant | Where it is enforced |
| --- | --- | --- |
| M1 | `total_amount = subtotal − discount_total + tax_total + rounding_adjustment` | `ck_order_total`, exact equality, checked on every write |
| M2 | `subtotal = Σ line.subtotal`, `discount_total = Σ line.discount_applied`, `tax_total = Σ line.tax_amount` | recomputed in the same transaction as the line write, under the order's row lock (M10). A cross-row sum is not expressible as a `CHECK`, so this is a service invariant with a named lock, not a wish |
| M3 | A document transitions to `Completed` only when `Σ CHARGE − Σ REFUND ≥ total_amount` over its **non-voided** payments | the domain guard of ADR-0004. Voided rows are excluded by `NOT EXISTS (SELECT 1 FROM sale_payment_void …)`, and a refund subtracts |
| M4 | Change is returned only for a cash method, never on a refund, and never exceeds the tender | `ck_payment_tender` holds the arithmetic; `payment_methods.is_cash` plus the ADR-0004 guard make it cash-only |
| M5 | A void is never an `UPDATE` and never a `DELETE`; a payment is void if and only if a void row names it | the trigger, the grants, and `payment_id` as the primary key |
| M6 | Every amount **on a sales document** carries a currency | `NOT NULL` on `sales_orders.currency`; the line and the payment inherit it by construction (M7). The four money columns listed in the Context are out of scope and named there |
| M7 | Two currencies on one document are impossible | there is no second currency column: neither the line nor the payment has one, so there is nothing to disagree |
| M8 | A rounding difference larger than one minor unit is refused | `ck_order_rounding_bounded` (≤ 1, currency-agnostic) plus the domain guard against `currency.minor_units`, because a `CHECK` cannot read the catalog |
| M9 | A payment belongs to its own tenant and its own order | `fk_payment_order_same_company`; without it a mis-stated `company_id` would not be rejected but **hidden**, because ADR-0005's policy filters the row instead of failing the write |
| M10 | One charge per order, even under concurrent retries | `chargeSalesOrder` takes `SELECT … FOR UPDATE` on the order row and writes the state conditionally on it; the second request sees `COMPLETED` and the guard refuses it |

## Migration from the current tree

1. **Seed `currency`** with the company's operating currency and the codes the product intends to
   support. Every existing money column gets that currency; there is exactly one, so the backfill is a
   constant.
2. **Pre-flight, before any constraint.** Report every order whose `total_amount` disagrees with its
   line sum, because those are the rows `ck_order_total` will refuse:

   ```sql
   SELECT o.id, o.order_number, o.total_amount,
          coalesce(sum(i.subtotal_candidate), 0) AS line_subtotal,
          coalesce(sum(i.discount_applied), 0)   AS line_discount,
          o.total_amount - (coalesce(sum(i.subtotal_candidate), 0)
                            - coalesce(sum(i.discount_applied), 0)) AS drift
   FROM sales_orders o
   LEFT JOIN (SELECT sales_order_id, list_price * quantity AS subtotal_candidate, discount_applied
                FROM sales_order_items WHERE enabled) i ON i.sales_order_id = o.id
   GROUP BY o.id, o.order_number, o.total_amount
   HAVING abs(o.total_amount - (coalesce(sum(i.subtotal_candidate), 0)
                                - coalesce(sum(i.discount_applied), 0))) > 0.0001;
   ```

   A drift within one minor unit is `rounding_adjustment` on that row. A larger drift is a manual
   decision per row, taken before the migration runs and not by the migration. Without this step the
   constraint aborts the deploy on production data instead of on a report.
3. Add every column **nullable first**, backfill, and only then apply the `NOT NULL`s and the
   `CHECK`s: a constraint added before its backfill refuses the rows it is meant to accept. Backfill
   `subtotal` from `round(list_price * quantity, 4)`, `discount_total` from the line discounts,
   `tax_total` to 0 and `rounding_adjustment` from step 2's drift. `total_amount` keeps its value.
   `sales_order_items.discount_applied` is nullable today, so `NULL` becomes 0 **before** the
   `NOT NULL` lands, and `total_amount`'s `NULL` becomes 0 for a document with no total yet — a
   `NULL` in a `CHECK` is not a refusal, it is a pass, and that is the one way a breakdown can be
   bypassed without an error.
4. **For orders already `Completed`, insert one `sale_payment` per order** with `amount = total_amount`,
   `direction = 'CHARGE'`, the order's `payment_method_id` and `paid_at = coalesce(o.updated_at,
   o.order_date)` — **not `now()`**, which would date every historical payment the day of the deploy
   and make every cash report by date wrong from that minute on. The `actor` is the order's `user_id`,
   or the literal `'unknown:migration'` where it is null, because the column is `NOT NULL` and a
   backfill is not allowed to invent a person. Nothing else is invented: the row says "one payment,
   whole amount, method as recorded".
5. Copy `product_name`/`variant_name` into the snapshots from the current catalog. `tax_code` stays
   `'NA'` and `tax_rate` stays 0 until a rate row exists, and `ck_order_total` still holds because
   `tax_total` is 0.
6. Drop `sales_orders.payment_method_id` once the payment rows are populated and every reader has
   moved.
7. `chargeSalesOrder` is rewritten as decision 7 states: lock the order, validate the state guard,
   insert the payments, compute the breakdown, book the stock through ADR-0001's API in the **same**
   transaction, append the transition of ADR-0004, publish `SaleCharged` for the audit listener.

The schema migrations land after ADR-0001's, because the charge transaction must book stock and money
together, and after ADR-0005's, because `sale_payment` and `sale_payment_void` are tenant tables that
must be on its policy list from their first row.

## Rejected alternatives

| Alternative | Why rejected |
| --- | --- |
| Keep one `total_amount` and one `payment_method_id` | Every later requirement — mixed tender, change, drawer close, partial refund, tax report — reopens this exact decision. It is the cheapest thing to fix now and the most expensive later |
| Store payments as a JSONB array on the order | Querying "all cash sales of the day", a drawer report, or a per-method total needs the rows indexed, and the indexes are declared above: `(company_id, paid_at)` and `(company_id, method_id, paid_at)`. A JSONB column is a document, not a ledger |
| Integer minor units everywhere instead of `NUMERIC` | Correct for money and wrong for a unit price that legitimately has four decimals; converting per read spreads a scale decision across every layer. The currency's `minor_units` gives the same guarantee at one boundary. Note that this is also why `total_amount` is `19,4` and not `19,2`: a fixed scale of 2 truncates KWD, BHD and TND, which have three minor units |
| Compute tax in the Angular form | The form is not an integrity boundary: any other client would bypass it. The rate lands on the line server-side |
| A `status` column plus `voided_by_payment_id` on the payment | Two representations of one fact, and the `(status = 'VOIDED') = (voided_by_payment_id IS NOT NULL)` pair makes a void without a replacement impossible — the exact case a wrong-tender payment needs. The append-only row of ADR-0004 keeps the actor, the reason and the moment, which a boolean does not |
| Negative `amount` rows for refunds | It puts the sign in two places at once (`amount` and `direction`) and makes every sum a `CASE`. `amount > 0` plus `direction` keeps one meaning per column, and M3's sum states the sign exactly once |
| A `currency` column on the line and on the payment | Under M7 it could only ever hold the document's value, so it adds a failure mode and no information. The first real second-currency requirement adds the column **and** a rate row together, which is the pair that makes it mean something |
| A `tax_rate_id` pointer on the line | The line cannot be pinned to a rate row without a second `company_id` on the line, and the snapshot (`tax_code`, `tax_rate`) already answers the reprint and the report. The pointer would buy traceability that the audit log of ADR-0004 gives more cheaply |
| Electronic invoicing (CFDI) as part of this ADR | It is a separate integration with a provider and its own document lifecycle. This ADR leaves it expressible — the breakdown, the currency and the tax snapshot are exactly its inputs — and does not implement it |

## Out of scope, named

- **Exchange rates.** No table, no column, no writer: a document is in the company's operating
  currency. Decision 2 says "a row plus a rate" as the shape of the *next* decision, not as content of
  this one.
- **Refund approval workflow.** A refund is a row with `direction = 'REFUND'`; who authorizes it, and
  against which original payment, is a workflow and not this schema.
- **Drawer close.** `shifts` exists (`V1:448-458`) and `sales_orders.shift_id` links a sale to it, so
  a drawer report is a join today. The session-level reconciliation — opening float, counted cash,
  variance — is its own decision and gets its own ADR.
- **The four money columns listed in the Context** (`products`, `product_variants`,
  `purchase_order_items`, `promotions`). They need the same currency and scale treatment and they are
  a separate slice with its own migration.

## Consequences

- **Good**: a sale becomes a financial record. Reports answer per method, per day, per tax rate, and a
  reprint is reproducible.
- **Good**: `total_amount` stops being the only number that can be wrong, because three `CHECK`s
  compare it with its parts and the line with its own arithmetic.
- **Good**: voiding, refunding and re-taking a payment are three different rows, so the drawer has a
  history instead of a state.
- **Cost**: every write path that touches an amount moves. `SalesOrderRequest`, `SalesOrderItemRequest`,
  `ChargeSalesOrderRequest`, both responses, the POS UI and every report change shape.
- **Cost**: the scale change from `12,2` to `19,4` reaches the DTOs, the Angular forms and `quantity`,
  and existing tests asserting two decimals need to assert the rounding boundary instead.
- **Risk**: this is the highest-risk ADR of the set for whatever data exists, because a mis-stated
  total is a financial number, not a display. That is an argument for landing it before production,
  not for deferring it — and the pre-flight of migration step 2 is what turns that risk into a report
  instead of a failed deploy.
