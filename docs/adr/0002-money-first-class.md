# ADR 0002 — Money is a first-class value

- **Status**: proposed
- **Date**: 2026-10-29
- **Decision id**: D2 of `odd/tasks/architecture-redesign-adrs.md`
- **Depends on**: ADR-0001 (stock and money book in one transaction), ADR-0003 (payment documents are numbered per tenant)
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
| Scale | `12,2` cannot hold a unit price such as 0,0375 per gram, so the rounding lands on the line instead of the document |

`promotions` (V1:424-437) stores a `discount_type`/`discount_value`, and `sales_order_items` stores
`list_price`, `discount_applied` and `final_price` (`V1:499-509`). Those two facts are the good half:
the line already snapshots its price. What is missing is the layer above and below it.

## Decision

**Every monetary value carries a currency, every document carries its own breakdown, and every payment
is a row.**

1. **Amounts are `NUMERIC(19,4)`.** Four decimals absorb a unit price that two cannot, and the
   currency decides the display and the rounding. Unit prices and line amounts keep the scale; the
   rounding to the currency's `minor_units` happens **once, at the document boundary** — never per
   line, which is how a total stops matching the paper.
2. **A currency is a row, not a string.** `currency` holds the ISO code and the number of minor units
   used to round and print. A second currency is not a rewrite: it is a row plus a rate.
3. **A document states its parts.** `subtotal`, `discount_total`, `tax_total`, `total_amount`, each
   with `currency`. The invariant `total = subtotal − discount_total + tax_total` is a check, not a
   convention.
4. **A line snapshots everything a reprint needs**: unit price, discount, tax rate, tax amount, line
   total, currency, and the product/variant names. A ticket printed today and reprinted in a year
   reads the same, whatever the catalog says then.
5. **A payment is a row, and a sale may have many.** Cash and card in one sale are two rows. A voided
   payment is a new state plus a pointer, never a deletion, so the drawer's history is auditable.
6. **Tax is a rate snapshot, not a calculation in the UI.** The rate comes from a catalog row and is
   copied onto the line at write time, so a rate change never rewrites history.

## Schema

```sql
CREATE TABLE currency (
    code         CHAR(3) PRIMARY KEY,           -- ISO 4217
    minor_units  SMALLINT NOT NULL,             -- 2 for ARS/MXN/USD, 0 for JPY
    symbol       VARCHAR(6),
    enabled      BOOLEAN NOT NULL DEFAULT true);

CREATE TABLE tax_rate (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id   UUID NOT NULL,
    tax_code     VARCHAR(20) NOT NULL,          -- the `sat_code` family
    rate         NUMERIC(9,6) NOT NULL,         -- 0.210000
    effective_from DATE NOT NULL,
    enabled      BOOLEAN NOT NULL DEFAULT true,
    UNIQUE (company_id, tax_code, effective_from));

-- ADR-0003 owns `doc_type`, `seq_period` and `seq_value`: a document's identity is allocated there,
-- and this ADR only reads it. One ALTER per column, one owner.
ALTER TABLE sales_orders
    ADD COLUMN currency      CHAR(3) NOT NULL REFERENCES currency (code),
    ADD COLUMN subtotal      NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN discount_total NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN tax_total     NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN rounding_adjustment NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN total_amount  NUMERIC(19,2),
    ADD CONSTRAINT ck_order_total
        CHECK (abs(total_amount - (subtotal - discount_total + tax_total + rounding_adjustment)) < 0.01);

ALTER TABLE sales_order_items
    ADD COLUMN currency     CHAR(3) NOT NULL REFERENCES currency (code),
    ADD COLUMN tax_rate_id  UUID REFERENCES tax_rate (id),
    ADD COLUMN tax_rate     NUMERIC(9,6) NOT NULL DEFAULT 0,
    ADD COLUMN tax_amount   NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN product_name_snapshot   VARCHAR(255) NOT NULL,
    ADD COLUMN variant_name_snapshot   VARCHAR(255),
    ADD COLUMN attributes_snapshot     JSONB;

CREATE TABLE sale_payment (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id     UUID NOT NULL,
    sales_order_id UUID NOT NULL REFERENCES sales_orders (id),
    method_id      UUID NOT NULL REFERENCES payment_methods (id),
    amount         NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    currency       CHAR(3) NOT NULL REFERENCES currency (code),
    tendered_amount NUMERIC(19,4),               -- cash only
    change_amount  NUMERIC(19,4),                -- cash only
    reference      VARCHAR(100),                 -- card auth, transfer id, last four
    status         VARCHAR(12) NOT NULL DEFAULT 'APPROVED',
    paid_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    voided_by_payment_id UUID REFERENCES sale_payment (id),
    -- self-consistent on its own; "cash only" is a domain guard, because a PostgreSQL CHECK cannot
    -- read the payment-method catalog (a STABLE function is not admissible inside one)
    CONSTRAINT ck_payment_tender CHECK (
        (tendered_amount IS NULL AND change_amount IS NULL)
        OR (tendered_amount IS NOT NULL AND change_amount IS NOT NULL
            AND tendered_amount >= amount
            AND change_amount = tendered_amount - amount)),
    CONSTRAINT ck_payment_void CHECK ((status = 'VOIDED') = (voided_by_payment_id IS NOT NULL)));
```

The "cash only" half of M4 is a domain guard, not a constraint: a `CHECK` cannot read another
table, so `method_is_cash(method_id)` is not admissible inside one. The catalog reports which
methods are cash and the guard of ADR-0004 reads it:

```sql
ALTER TABLE payment_methods ADD COLUMN is_cash BOOLEAN NOT NULL DEFAULT false;
```

What the constraint still guarantees is that tender and change agree with the amount, so a card
payment cannot carry a change value even by mistake.

## Invariants

| # | Invariant | Where it is enforced |
| --- | --- | --- |
| M1 | `total = subtotal − discount_total + tax_total + rounding_adjustment` | `ck_order_total`, checked on every write |
| M2 | `subtotal = Σ line.subtotal`, `tax_total = Σ line.tax_amount` | recalculated in the same transaction as the line write |
| M3 | A document transitions to `Completed` only when `Σ payments.amount ≥ total` | the domain guard of ADR-0004, not a controller check |
| M4 | Change is returned only for a cash method, and never exceeds the tender | `ck_payment_tender` holds the arithmetic; `payment_methods.is_cash` plus the ADR-0004 guard make it cash-only |
| M5 | A voided payment is never deleted and always names its replacement | `ck_payment_void` |
| M6 | Every amount in the tree has a currency | `NOT NULL` on the column, in every table that holds money |
| M7 | Two currencies on one document are impossible | the document's currency is copied to the line and to the payment; a mismatch is refused |
| M8 | A rounding difference larger than one minor unit is refused | `rounding_adjustment` is bounded by the currency's `minor_units` |

## Migration from the current tree

1. Seed `currency` with the company's operating currency. Every existing money column gets that
   currency; there is exactly one, so the backfill is a constant.
2. Add every column **nullable first**, backfill, and only then apply the `NOT NULL`s and the
   `CHECK`s: a constraint added before its backfill refuses the rows it is meant to accept.
   Backfill `subtotal`/`total_amount` from the current `total_amount` and the line sums. For orders already `Completed`, insert **one** `sale_payment` per order with
   `amount = total_amount` and the order's `payment_method_id`. Nothing is invented: the row says
   "one payment, whole amount, method as recorded".
3. Copy `list_price`/`discount_applied`/`final_price` into the new line columns; `tax_rate` stays 0
   until a rate row exists, and `ck_order_total` still holds because `tax_total` is 0.
4. Drop `sales_orders.payment_method_id` once the payment rows are populated and every reader has
   moved.
5. `chargeSalesOrder` is rewritten as: validate the state guard, insert the payments, compute the
   breakdown, book the stock through ADR-0001's API in the **same** transaction, publish
   `SaleCharged` for the audit listener.

The schema migrations land after ADR-0001's, because the charge transaction must book stock and money
together.

## Rejected alternatives

| Alternative | Why rejected |
| --- | --- |
| Keep one `total_amount` and one `payment_method_id` | Every later requirement — mixed tender, change, drawer close, partial refund, tax report — reopens this exact decision. It is the cheapest thing to fix now and the most expensive later |
| Store payments as a JSONB array on the order | Querying "all cash sales of the day", a drawer report, or a per-method total needs the rows indexed. A JSONB column is a document, not a ledger |
| Integer minor units everywhere instead of `NUMERIC` | Correct for money and wrong for a unit price that legitimately has four decimals; converting per read spreads a scale decision across every layer. The currency's `minor_units` gives the same guarantee at one boundary |
| Compute tax in the Angular form | The form is not an integrity boundary: any other client would bypass it. The rate lands on the line server-side |
| Electronic invoicing (CFDI) as part of this ADR | It is a separate integration with a provider and its own document lifecycle. This ADR leaves it expressible — the breakdown, the currency and the tax snapshot are exactly its inputs — and does not implement it |

## Consequences

- **Good**: a sale becomes a financial record. Reports answer per method, per day, per tax rate, and a
  reprint is reproducible.
- **Good**: `total_amount` stops being the only number that can be wrong, because the check constraint
  compares it with its parts.
- **Cost**: every write path that touches an amount moves. `SalesOrderRequest`, `SalesOrderItemRequest`,
  `ChargeSalesOrderRequest`, both responses, the POS UI and every report change shape.
- **Cost**: the scale change from `12,2` to `19,4` reaches the DTOs and the Angular forms, and existing
  tests asserting two decimals need to assert the rounding boundary instead.
- **Risk**: this is the highest-risk ADR of the set for whatever data exists, because a mis-stated
  total is a financial number, not a display. That is an argument for landing it before production,
  not for deferring it.
