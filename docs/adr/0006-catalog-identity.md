# ADR 0006 — Catalog identity: one variant minimum, a unit, and price apart from stock

- **Status**: proposed
- **Date**: 2026-10-29
- **Decision id**: D6 of `odd/tasks/architecture-redesign-adrs.md`
- **Depends on**: ADR-0005 (policies on `products`, `product_variants`, `variant_listing`)
- **Blocks**: ADR-0001 (the ledger's key is the variant; the listing is the price half of a sale)
- **Replaces**: `V13`'s per-store row, and the barcode and unit rules of `V14`, `V29` and `V40`

## Context

**A barcode for everything.** `V14` made the variant the global sellable definition and in the same
breath made `bar_code` required (`V14__product_variant_definition.sql:72`), `variant_name` required
(`:73`) and `(product_id, variant_name)` unique (`:75`). A loaf of bread is a single article with no
barcode: today it cannot be created without inventing one. `V40` re-scoped that key to the tenant —
`uq_product_variants_company_bar_code` on `(company_id, bar_code)`
(`V40__product_catalog_company_contract.sql:124-126`) — and pinned a variant to its product's company
(`fk_product_variants_product_same_company`, `:131-135`), inheriting the requirement.

**A unit that is optional.** `V29` added `measure_unit_id` as nullable
(`V29__product_variant_measure_unit.sql:23-24`) on the honest argument that existing rows had never
declared a unit (`V29:14-18`). The request still mirrors it: `@NotBlank barCode`
(`ProductVariantRequest.java:32`) and `@NotBlank variantName` (`:35`) are required for every variant,
while `measureUnitId` (`:39`) is not. A quantity of 3 of nothing is not a quantity.

**Price and stock in one row.** `product_variant_store_stock` (`V13:23-33`) holds `stock`, `list_price`
and `cost_price`, unique per `(product_variant_id, company_store_id)` (`V13:32`), and `upsertStoreStock`
(`ProductVariantService.java:383`) writes price and stock together: `:406-412` sets both prices on the
row, then `:416-419` delegates the stock to the inventory engine. Two facts with different authorities
share one lifecycle, which is why the price screen is also a stock editor. Both store-scoped reads
`INNER JOIN` that table (`ProductVariantRepository.java:157-159` for the product's variants, `:187-189`
for the search), so **a variant with no row in a store does not exist in that store's POS**: receiving
stock into a store that never priced it hides the variant instead of showing it as unpriced.

## Decision

**A product is a family with at least one variant; a variant declares a unit; price is a listing.**

1. **`product.variant_mode` is `SIMPLE` or `MULTI`**, set at creation, immutable once its variant has
   stock movements. The mode is a property of the article, not of the request that created it.
2. **A product always has at least one variant, and creating one is one transaction.** `SIMPLE` means
   exactly one, written by the backend with the product and never shown as a step; `MULTI` means the
   variants are declared in the creation request and at least one is required — none is refused.
3. **`variant.measure_unit_id` becomes `NOT NULL`**: a quantity without a unit is a meaningless number,
   and the catalog it needs already ships (`measure_units`, `V1:284`).
4. **`variant.bar_code` is required for `MULTI` and optional for `SIMPLE`**, unique per tenant when
   present. A single-variant article has no barcode to give.
5. **`variant_listing` replaces the price half of `product_variant_store_stock`**: `(variant, store)`
   with `list_price`, `currency`, `tax_rate_id`, `enabled`, and **no stock and no cost** — stock is
   ADR-0001's ledger, cost is the inbound movement's and the valuation projection's.
6. **"Sellable in this store" is `variant_listing`; "there is stock here" is the ledger.** A sale needs
   both. A receipt may create stock in a store with no listing (S1) — a legitimate state the POS reports
   as **stock without price**, not one it hides.
7. **The order is fixed**: company → product → variant → listing per store → movements. The listing
   depends on the variant AND on the store being of the same tenant; the movement depends on variant +
   store + location; nothing depends on the listing to create stock.

The four states of (listing × stock), and what each one means at the till:

| Listing | Stock | POS behaviour | What it means |
| --- | --- | --- | --- |
| no | no | invisible | the store does not carry this variant at all |
| yes | no | visible, refuses the sale | listed but nothing to sell — a price with no ledger |
| no | yes | **reported as "stock without price"** | stock arrived without a listing (S1); sellable once priced |
| yes | yes | sellable | the only complete state |

## Schema

```sql
ALTER TABLE products
    ADD COLUMN variant_mode VARCHAR(6) NOT NULL DEFAULT 'SIMPLE',
    ADD CONSTRAINT ck_products_variant_mode CHECK (variant_mode IN ('SIMPLE', 'MULTI'));

-- ─── variant: a unit is mandatory, a barcode is not, and the mode decides the rest
ALTER TABLE product_variants
    ALTER COLUMN measure_unit_id SET NOT NULL,
    ALTER COLUMN bar_code DROP NOT NULL;

ALTER TABLE product_variants DROP CONSTRAINT uq_product_variants_company_bar_code;

CREATE UNIQUE INDEX uq_variant_bar_code
    ON product_variants (company_id, bar_code)
    WHERE bar_code IS NOT NULL;

CREATE FUNCTION assert_variant_matches_mode() RETURNS trigger LANGUAGE plpgsql AS $$
    -- SIMPLE allows exactly one variant; MULTI requires a barcode on each
DECLARE mode varchar(6) := (SELECT variant_mode FROM products WHERE id = NEW.product_id);
BEGIN
    IF mode = 'SIMPLE' AND EXISTS (SELECT 1 FROM product_variants
                                   WHERE product_id = NEW.product_id AND id <> NEW.id) THEN
        RAISE EXCEPTION 'product % is SIMPLE and already has a variant', NEW.product_id;
    END IF;
    IF mode = 'MULTI' AND NEW.bar_code IS NULL THEN
        RAISE EXCEPTION 'bar_code is required for a variant of MULTI product %', NEW.product_id;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_variant_matches_mode BEFORE INSERT OR UPDATE ON product_variants
    FOR EACH ROW EXECUTE FUNCTION assert_variant_matches_mode();

-- ─── listing: price and availability per store, never stock, never cost ──────
CREATE TABLE variant_listing (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id         UUID NOT NULL,
    product_variant_id UUID NOT NULL,
    company_store_id   UUID NOT NULL,
    list_price         NUMERIC(19,4) NOT NULL CHECK (list_price >= 0),
    currency           CHAR(3) NOT NULL REFERENCES currency (code),
    tax_rate_id        UUID REFERENCES tax_rate (id),
    enabled            BOOLEAN NOT NULL DEFAULT true,
    CONSTRAINT fk_listing_variant
        FOREIGN KEY (company_id, product_variant_id) REFERENCES product_variants (company_id, id),
    CONSTRAINT fk_listing_store
        FOREIGN KEY (company_id, company_store_id) REFERENCES company_stores (company_id, id),
    CONSTRAINT uq_listing_variant_store UNIQUE (product_variant_id, company_store_id)
);
```

`currency` and `tax_rate` are ADR-0002's tables; the quantity a listing omits is ADR-0001's.

## Invariants (each one testable)

| # | Invariant | Enforced by |
| --- | --- | --- |
| C1 | A product has at least one variant | the single creation transaction; a delete that would leave zero is refused by the write path and counted by a query |
| C2 | A `SIMPLE` product has exactly one variant | `trg_variant_matches_mode` refuses the second |
| C3 | A `MULTI` variant has a barcode | `trg_variant_matches_mode` |
| C4 | `measure_unit_id` is never null | `NOT NULL`, after the backfill below |
| C5 | `(company_id, bar_code)` is unique where a barcode exists | `uq_variant_bar_code`, partial |
| C6 | A listing's variant and store are one tenant, one listing per `(variant, store)` | `fk_listing_variant` + `fk_listing_store` + `uq_listing_variant_store` |
| C7 | `variant_listing` has no stock and no cost column | a query over `information_schema.columns` fails the build if one appears |

## Migration from the current tree

Steps 1 to 3 run **before** the trigger of the Schema block is created: while `variant_mode` still
holds its default on a product that is about to become `MULTI`, the trigger would refuse the update
that classifies it.

1. **Backfill `variant_mode`**: `MULTI` for every product with more than one variant, `SIMPLE`
   otherwise. `SIMPLE` forbids nothing that exists, because products with one variant satisfy it.
2. **Reject, then fix, every null measure unit.** The migration runs a report first, not a write:
   `SELECT p.sku, pv.variant_name FROM product_variants pv JOIN products p ON p.id = pv.product_id
   WHERE pv.measure_unit_id IS NULL`. It then **aborts**, listing those rows, and guesses nothing. The
   operator assigns each one the unit its quantities were always expressed in — through the variant
   endpoint, which already allows the first declaration even when stock exists
   (`ProductVariantService.java:567-571`) — and the migration is re-run. Then `SET NOT NULL` applies.
3. **`bar_code` stops being required**: drop `NOT NULL` and replace
   `uq_product_variants_company_bar_code` with the partial `uq_variant_bar_code`. No row needs fixing:
   none was ever allowed to violate the stronger rule.
4. **Split `product_variant_store_stock`.** `variant_listing` takes `list_price`, with currency and tax,
   for each row that has one; every row with a non-zero `stock` produces **one `ADJUSTMENT_IN`
   movement** for its `(variant, store)` through ADR-0001 Path A, keeping the ledger sum equal to
   today's balance by construction; `cost_price` becomes that movement's `unit_cost`. The table then
   drops, once its readers (`:157-159`, `:187-189`) read `variant_listing` ⨝ the position projection.
5. **The POS and the search stop `INNER JOIN`ing a store row.** They join the listing (price) and the
   position projection (quantity) independently, so all four states are representable and a variant with
   stock and no listing is returned, flagged, instead of missing.
6. **Mode immutability lands with ADR-0001.** Today the only "movement" is a per-store stock row, so the
   guard is "refuse a mode change while any variant holds a listing or non-zero stock"; once
   `stock_movement` exists, the guard reads the ledger.

## Rejected alternatives

| Alternative | Why rejected |
| --- | --- |
| Keep the per-store row and add a flag for "sells here" | The status quo with a new column: price, availability, stock and cost stay in one lifecycle, so the store screen keeps editing stock while pricing and a receipt keeps needing a price row to record quantity |
| An `is_simple` boolean with no variant row | Every downstream path forks on it — sale line, receipt line, ledger key, listing, search projection — and each fork is a place where a simple article is silently unsupported. One variant row for everything is one code path |
| Leave the measure unit optional and default it at read time | A default on read invents a unit for quantities already stored, which reinterprets history. The information does not exist; the fix is to ask for it once, at migration time |
| Allow products with zero variants | That is the state this ADR removes. A product with no variant cannot be sold, received, priced or counted, so it is an incomplete creation, not a catalog entry |

## Consequences

- **Good**: a single-variant article becomes creatable — the majority of a real catalog — and the
  operator never sees a variant step with no decision in it. The POS tells four states apart instead of
  two, so "stock without price" becomes a report the system produces instead of a state it hides.
- **Cost**: the creation request changes shape — `variantMode`, and the variant list for `MULTI` — and
  the Angular product form splits into "the article" and "its variants" with a branch per mode.
- **Cost**: `product_variant_store_stock` disappears, so the store-scoped variant list, the search page,
  the POS selector and the price endpoint all change their read and their write: the queries at
  `ProductVariantRepository.java:157-159` and `:187-189` are the visible half, the response record that
  carries `listPrice`, `costPrice` and `stock` together is the other.
- **Risk**: the measure-unit backfill cannot be automated: each row it reports is a decision for an
  operator, so the migration stops rather than defaulting and needs a dry run on a copy of the data.
