# ADR 0007 — The fiscal invoice is a document of its own

- **Status**: accepted
- **Date**: 2026-11-12
- **Accepted**: 2026-11-12, by the maintainer, with the corrections recorded under *Corrections applied before acceptance*
- **Decision id**: ADR-0007, written from the sales/fiscal/accounting review of 2026-11-12
- **Depends on**: ADR-0002 (the money the document states), ADR-0003 (the per-tenant identity it must not
  reuse), ADR-0004 (the typed state machine its own lifecycle copies), ADR-0005 (the tenancy every table it
  adds is armed in)
- **Blocks**: nothing. ADR-0008 consumes the sale, not this document, and says why
- **Replaces**: nothing. There is no fiscal document in this tree to replace

## Context

A sale in this system is a document that states money. A fiscal invoice is a document that states the same
money to a tax authority, and the difference is not cosmetic: a stamped invoice carries an identifier the
issuer does not control and cannot revoke — only cancel, and even that leaves a record at the authority. Eight
facts decide the shape, and the first one is that the sale already holds almost every input.

| # | Evidence | What it means |
| --- | --- | --- |
| 1 | `V80__sales_orders_money_breakdown.sql` gives the document `subtotal`, `discount_total`, `tax_total`, `rounding_adjustment`, `total_amount` and `currency`; `V84` constrains the four parts to add up | The money an invoice states already exists, verified by constraint, in the document's own currency |
| 2 | `V81` and `V90__sales_order_items_tax_derivation.sql` give every line `tax_code`, `tax_rate` and a derived `tax_amount`, alongside the product and variant name snapshots | A line already knows what it costs and what tax it carries, frozen at the values in force when it was written |
| 3 | `V89__products_tax_code.sql` takes the code from the product, so *what* is taxed is a property of the article rather than of the operator | The fiscal classification of a sale is inherited, not typed on the ticket |
| 4 | `V34` gives `product_categories` an eight-digit `sat_code` with a format check, `V1__baseline_schema.sql` gives `measure_units` a `sat_code` that is required and unique, and `products.sat_code` exists beside them as twenty characters of free text with no check and no catalog | Three of the keys the invoice needs already have a column, and none of them is copied onto the sale line. The weakest of the three is the one the concept needs most |
| 5 | `V1__baseline_schema.sql` gives `companies` an `rfc`, a `razon_social` and an address, and gives `customers` a nullable `rfc` with no format check and nothing else | The issuer is nearly complete; the receiver is a name and a free-text identifier. Neither carries a fiscal regime |
| 6 | `V83__sale_payment.sql` makes a payment append-only with a trigger **and** a privilege revocation, because a taken payment is a fact | The tree already knows how to hold a fact that may not be edited. A stamped invoice is that, with an external witness |
| 7 | `V64__document_identity_per_tenant.sql` numbers documents per tenant and period, and ADR-0003 states that the identity is the parts, never the string | The tree already assigns a folio. It is the **internal** one, and it is not the one a tax authority recognises |
| 8 | A sale cannot be charged unless the payments cover the total exactly, and a zero total is refused | The tree can only express a sale that is paid when it happens. Anything else is a capability, not a column |

The consequence that orders every other one: **the sale is mutable and the invoice cannot be.** An order is
editable until it reaches a terminal state, its lines are replaced wholesale by the `PUT`, and a deleted order
can be re-enabled. A fiscal document whose contents could follow those edits would be a document that changes
after the authority witnessed it. So the fiscal document cannot be a set of columns on `sales_orders` — it has
to be a separate row set that is written once, from a snapshot, and never re-derived.

One thing this ADR does not derive from the tree is the shape of what the authority requires. Which elements
are mandatory, which are conditional, and which ones the fourth revision added were verified against the
schema annex of 2022-01-13 and its filling guide, not recalled from experience. That is why the issuer's and
the receiver's fiscal regimes, the receiver's fiscal postal code, the use, the export declaration and the tax
object per concept are treated as mandatory, while series, folio, payment form and payment method are treated
as optional and conditional. Common belief runs the other way on two of those, and the authority's document
wins over the belief.

## Decision

**A fiscal invoice is its own document: a row set with its own identity, its own lifecycle and its own frozen
snapshot of everything it states. It derives from a sale; it is never a state of one.**

1. **A separate document, referenced by the sale.** `fiscal_document` carries `(company_id, source_type,
   source_id)` pointing at the sale, plus its own state. A sale may end up with more than one fiscal document
   — a cancelled one and its replacement — so the link is not a column on the order and not a one-to-one.

2. **The fiscal identity belongs to the authority, not to this tree.** The document carries `uuid_fiscal` and
   keeps `serie` and `folio` as an optional local convention. ADR-0003's per-tenant sequence numbers the sale
   and is **not** reused, because the authority's schema makes both of those attributes optional: the UUID is
   the identity that survives everything else, and a unique index on it is the only global uniqueness this ADR
   adds. Series and folio are written because humans read them, never because a stamp requires them.

3. **Stamped facts are append-only, and the lifecycle is a history.** The stamped row refuses `UPDATE` and
   `DELETE` with the same trigger-and-grant pair `V83__sale_payment.sql` uses on `sale_payment`. A
   cancellation is a new row that names the cancelled document, the motive and, when the motive is a
   substitution, the folio that replaces it. The original stamped XML is kept byte for byte, because a
   re-download three years later has to return the same document.

4. **Every fact the invoice states is frozen on the invoice.** Clave, unit, description, quantity, unit value,
   discount, tax object, base, tax, factor and rate are copied onto `fiscal_document_line` at stamp time, and
   so is the receiver's fiscal identity. Nothing is read from the live catalogs when a document is re-issued,
   reprinted or cancelled, for the reason `V81` froze the rate on the line: a catalog is today's answer to a
   question asked last year.

5. **The fiscal identity of the two parties becomes a first-class, validated profile.** The issuer gains a
   fiscal regime. The receiver gains the full profile a stamp requires — a validated `rfc`, a legal name, a
   regime, a fiscal address with a postal code, and a default use. The receiver's profile is validated when a
   sale is issued, not when it is rung up, because a cash sale does not need one and an invoice does.

6. **The internal tax vocabulary gains a SAT mapping; it does not get a parallel catalog.**
   `tax_code` already discriminates rate, zero rate, exempt and not-applicable. Two mapped columns make that
   distinction speak the authority's language, and `EXENTO` and `TASA_0` become distinguishable for the first
   time — today they differ only by the string in a code. A second catalog table beside `tax_code` would be a
   second place to keep the same fact aligned.

7. **Taxes are stated per line and summarised per document, in two directions.** Document totals split into
   transferred and withheld from the start, with withheld allowed to be zero. Withholding **per line** is out
   of scope for the first slice, and the document-level total is the one the authority reconciles.

8. **Three facts that are not about money are columns, and two of them are mandatory.** The type of document
   (income, which is the only one this tree issues until a note exists), the postal code of the place of issue,
   and whether the operation exports. The place of issue and the export declaration are required on every
   document in the fourth revision of the schema, so they are columns with a closed vocabulary rather than
   optional metadata an operator types.

9. **Only a sale paid at the moment it happens can be invoiced.** The tree cannot charge a partially paid
   sale, so the first slice expresses a single-payment invoice and refuses the deferred one as a validation,
   not as a bug. The payment form and the payment method are both conditional in the authority's schema — the
   deferred method is exactly what forces a placeholder payment form and a payment complement — so naming that
   method out of scope is what keeps the two attributes honest. A deferred invoice needs a receivable, which
   ADR-0008 states as a balance, and a complement document of its own.

10. **The fiscal document does not create revenue.** Revenue accrues at the sale and this document is its
    evidence — ADR-0008 states that consequence in full. A stamp in a later month, a cancellation, or a sale
    never invoiced all leave the books describing the sale, which is what happened commercially.

11. **Catalog values are not pinned by this ADR.** It fixes columns, types and the set of facts each row must
    carry. The codes themselves come from the catalog version in force at stamp time, and a stamp request is
    validated against the catalog the stamping provider accepts. Writing the codes into this document would
    make the ADR wrong the first time a catalog is reissued.

## Schema

```sql
-- The issuer's fiscal regime, and the receiver's fiscal profile.
ALTER TABLE companies ADD COLUMN regimen_fiscal VARCHAR(3);

ALTER TABLE customers
    ADD COLUMN legal_name            VARCHAR(255),
    ADD COLUMN regimen_fiscal        VARCHAR(3),
    ADD COLUMN fiscal_street         VARCHAR(255),
    ADD COLUMN fiscal_zip_code       VARCHAR(10),
    ADD COLUMN fiscal_state          VARCHAR(120),
    ADD COLUMN fiscal_country_id     UUID REFERENCES countries (id),
    ADD COLUMN default_uso_cfdi      VARCHAR(5);

-- The internal tax vocabulary learns the authority's two coordinates.
ALTER TABLE tax_code
    ADD COLUMN sat_impuesto    VARCHAR(3),
    ADD COLUMN sat_tipo_factor VARCHAR(10);

CREATE TABLE fiscal_document (
    id                  UUID PRIMARY KEY,
    company_id          UUID NOT NULL REFERENCES companies (id),
    source_type         VARCHAR(40) NOT NULL,          -- 'SALES_ORDER'
    source_id           UUID NOT NULL,
    status              VARCHAR(20) NOT NULL,          -- 'DRAFT' | 'STAMPED' | 'CANCELLED'
    serie               VARCHAR(25),
    folio               VARCHAR(40),
    uuid_fiscal         UUID,
    stamped_at          TIMESTAMPTZ,
    cancellation_motive VARCHAR(2),
    replaces_fiscal_document_id UUID REFERENCES fiscal_document (id),
    receptor_rfc        VARCHAR(13) NOT NULL,
    receptor_name       VARCHAR(255) NOT NULL,
    receptor_regimen    VARCHAR(3) NOT NULL,
    receptor_zip_code   VARCHAR(10) NOT NULL,
    receptor_uso_cfdi   VARCHAR(5) NOT NULL,
    tipo_comprobante    VARCHAR(1) NOT NULL DEFAULT 'I' CHECK (tipo_comprobante = 'I'),
    forma_pago          VARCHAR(2),
    metodo_pago         VARCHAR(3) NOT NULL DEFAULT 'PUE' CHECK (metodo_pago IN ('PUE', 'PPD')),
    exportacion         VARCHAR(2) NOT NULL DEFAULT '01',
    lugar_expedicion    VARCHAR(10) NOT NULL,
    currency            CHAR(3) NOT NULL REFERENCES currency (code),
    subtotal            NUMERIC(19,4) NOT NULL,
    discount_total      NUMERIC(19,4) NOT NULL DEFAULT 0,
    tax_transferred     NUMERIC(19,4) NOT NULL DEFAULT 0,
    tax_withheld        NUMERIC(19,4) NOT NULL DEFAULT 0,
    total_amount        NUMERIC(19,4) NOT NULL,
    issued_by           VARCHAR(255) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE fiscal_document_line (
    id              UUID PRIMARY KEY,
    company_id      UUID NOT NULL REFERENCES companies (id),
    fiscal_document_id UUID NOT NULL REFERENCES fiscal_document (id),
    sales_order_item_id UUID,
    clave_prod_serv VARCHAR(20) NOT NULL,
    clave_unidad    VARCHAR(5)  NOT NULL,
    description     VARCHAR(1000) NOT NULL,
    quantity        NUMERIC(19,6) NOT NULL,
    unit_value      NUMERIC(19,4) NOT NULL,
    discount        NUMERIC(19,4) NOT NULL DEFAULT 0,
    tax_object      VARCHAR(2)  NOT NULL,
    base            NUMERIC(19,4) NOT NULL,
    tax_code        VARCHAR(3)  NOT NULL,
    tipo_factor     VARCHAR(10) NOT NULL,          -- the authority's factor, frozen
    tasa_o_cuota    NUMERIC(19,6),
    tax_amount      NUMERIC(19,4) NOT NULL DEFAULT 0
);

CREATE TABLE fiscal_document_artifact (
    fiscal_document_id UUID PRIMARY KEY REFERENCES fiscal_document (id),
    company_id         UUID NOT NULL REFERENCES companies (id),
    xml                TEXT NOT NULL,
    pdf                BYTEA,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Every tenant table is armed with `apply_tenant_isolation` in the file that creates it, and the two artefact
tables are append-only on the same pattern the payment tables already use.

## Invariants (each one testable)

| # | Invariant | Enforced by |
| --- | --- | --- |
| F1 | A sale has at most one live stamped invoice | Partial unique index on `(company_id, source_type, source_id)` where the status is the stamped one |
| F2 | A stamped invoice's facts cannot be updated or deleted | Trigger plus privilege revocation, the pair `V83` already enforces |
| F3 | `uuid_fiscal` is unique, and never null on a stamped row | Unique index plus a `CHECK` on the status/nulls pair |
| F4 | A cancelled document names a motive, and a substitution names a document that exists | `CHECK` plus a composite foreign key |
| F5 | An invoice's line amounts equal the sale line's, within one minor unit | Constraint trigger at stamp time; the only place two documents are compared |
| F6 | An invoice whose receiver profile is incomplete is refused | Validation at issue, and a `NOT NULL` set on the receiver's frozen columns |
| F7 | Every `tax_code` an invoice line states has its SAT mapping, or the stamp is refused | Foreign key plus a validation that names the code |
| F8 | The artefact's XML is the one that was stamped | Append-only pair on the artefact table; a reprint reads it, never regenerates it |
| F9 | The invoice cannot be stamped twice for the same attempt | Idempotency key on the attempt, unique per sale |

## Migration from the current tree

Four slices, each one shippable on its own, in the order that makes the next one cheaper:

| Step | Slice | What it makes true |
| --- | --- | --- |
| 1 | Issuer regime and place of issue, receiver profile, the `tax_code` mapping, and a format check on the product's key | F6 and F7 become expressible. No document yet; a sale still works exactly as today |
| 2 | `fiscal_document` and its lines, with the state machine, the append-only pair and the frozen snapshot | F1–F5. A document can be created and refused, and nothing is stamped |
| 3 | The stamping port: an XML builder and a provider adapter behind one interface | F8 and F9. The domain speaks its own types; the provider is a dependency at the edge |
| 4 | Cancellation, substitution and the reprint surface | The lifecycle closes. A cancelled invoice keeps its XML and names what replaced it |

Two things are worth stating before the first migration lands:

- **The frozen snapshot is not optional, and it is not the sale line.** Step 2 copies the fiscal coordinates
  onto the invoice, so a later change to `products.sat_code` or to a category's `sat_code` cannot rewrite an
  already-stamped document. Without that copy, step 3 would be stamping today's catalog against last year's
  sale.
- **The provider must not be reachable from a domain service.** A stamp is an external call with a timeout, a
  retry and a partial-failure shape; the tree already treats runtime admission and external dependencies as
  edge concerns. Keeping the port at the boundary is what lets step 3 be tested without a network.
- **The concept's key is the weakest fiscal column in the tree.** The category's counterpart is constrained to
  eight digits, while the product's is free text with no check and no catalog — and the concept is the one
  element a stamp cannot omit. Step 1 constrains it instead of discovering it at the first rejection.

## Rejected alternatives

| Alternative | Why it is refused |
| --- | --- |
| Invoice columns on `sales_orders` | The order is editable until terminal and re-enablable after deletion. A stamped identifier that lives on a mutable row is a document that can be invalidated by an edit |
| Reusing ADR-0003's `doc_number` as the fiscal folio | The tree's number is internal, per tenant and per period; the fiscal one is declared to the authority and must survive independently. Two identities, two columns |
| Building the XML at stamp time from the live catalogs | It makes a document's contents depend on when it is read, not when it was written. The line already froze its tax rate for the same reason |
| A parallel SAT catalog for tax, beside `tax_code` | A second table holding the same fact is a second thing to keep aligned. Two mapped columns on the existing vocabulary are enough, and `EXENTO` and `TASA_0` stop being the same string |
| Modelling deferred payment now, with its payment complement | The tree cannot charge a partially paid sale, so the model would have no data to describe. It needs a receivable and a complement document; naming it out of scope is cheaper than an empty schema |
| Letting the fiscal document create revenue | A cancellation would move revenue, which is a fiscal event unhooking a commercial fact. ADR-0008 posts from the sale |
| A single `tax_total` reused for the invoice's transferred tax | The document's total is one number; an invoice states transferred and withheld separately, and a withheld amount that is always zero is still a column someone has to see |
| Storing only the PDF | The authority holds the XML, and a reprint that regenerates rather than reads cannot be proven to be the original |

## Out of scope, named

- **Deferred payment, in any form** — the payment complement, the receivable it implies, and the partial
  collection. Named, not half-built.
- **Credit and debit notes as fiscal document types.** A refund exists in the schema as a payment direction
  with no writer, and a fiscal note is a document type of its own. Both belong to the slice that gives money
  a way back.
- **Foreign currency and exchange rate.** ADR-0002 left them out and this ADR does not reopen it: one
  document, one currency, the company's.
- **Per-line withholding.** The document states a withheld total; splitting it per line is a later decision
  about which lines a retention applies to, and no rule in this tree says.
- **Filing, declarations and the electronic accounting export.** Issuing a document is not reporting it.
- **Choosing a stamping provider.** This ADR fixes the port, not the vendor. A vendor choice is a dependency
  with a contract, a cost and a failure mode of its own.
- **Multiple issuers per company.** One company, one fiscal regime, one certificate. A tenant that needs two
  issuers is a different tenant or a different decision.
- **Retention and legal archiving periods.** The artefact is kept forever because keeping it is cheaper than
  the rules; a retention policy is a decision nobody has asked for.

## Consequences

**What becomes true**

- The catalog work that a fiscal invoice needs is mostly a mapping plus two profiles, because the money, the
  currency and the tax snapshot already exist.
- A sale can be invoiced late, corrected, or never invoiced without the commercial record changing, and a
  stamp can be cancelled without the accounting entry moving.
- The fiscal folio and the internal folio stop competing for one column, which is the confusion this tree
  would otherwise discover at the first audit.

**What becomes expensive**

- Every fiscal document is a snapshot, so the invoice grows its own copy of what it states. That duplication
  is the price of immutability, and the invariant that compares the two at stamp time is what keeps it honest.
- The receiver profile becomes a required flow for invoicing, which means an operator-facing screen has to
  collect data a cash sale never needed before. That is a product surface this ADR does not design.
- A stamping provider brings a dependency, a sandbox, a timeout and a partial-failure state to a system that
  currently has no external dependency on the sale path.

**What breaks if this is skipped**

- Invoicing gets built as columns on the order because it is the shortest path, and the first cancelled
  invoice leaves a document that can be edited.
- The SAT codes get read from the live catalog at print time, and the first product recategorization silently
  changes the face of every previously issued invoice.

## Corrections applied before acceptance

**2026-11-12 — three corrections from checking the authority's schema, before acceptance.** The draft was
written from what this tree knows and then verified against the schema annex of 2022-01-13 and its filling
guide. Three things moved: series and folio were demoted from identity to an optional local convention,
because the schema makes both optional; the export declaration was added as a required column, because the
fourth revision requires it on every document; and the concept's key was scheduled for a format check in
step 1, because the product's column is free text while its category counterpart is constrained. The episode
is recorded because it is the argument for decision 11 — the shape of a fiscal document is a fact to look
up, not one to recall.

Accepted with those corrections in place.
