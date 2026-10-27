/**
 * Product variant as returned by the nested
 * `GET /api/products/{productId}/variants` endpoint.
 *
 * Mirrors the backend `ProductVariantResponse`, which is a hybrid by design: the
 * global sellable definition (`id`, `productId`, `barCode`, `variantName`,
 * `enabled`) plus the per-store values (`companyStoreId`, `listPrice`,
 * `costPrice`, `stock`) that live in `product_variant_store_stock`.
 *
 * The store-scoped fields are `null` when the request omits `?storeId=`: the
 * endpoint then answers with the product's global definitions and there is no
 * store in play. Pass `storeId` whenever a store context exists.
 *
 * `sku` is the owning PRODUCT sku and is therefore identical for every variant of
 * a product. The variant no longer carries its own sku: `barCode` is the variant
 * identifier, globally unique, which is why the purchase-order picker labels each
 * option with it.
 */
export interface ProductVariant {
  id: string;
  productId: string;
  /** The store this row describes. `null` when the request omitted `?storeId=`. */
  companyStoreId: string | null;
  /** The variant identifier: globally unique barcode. */
  barCode: string;
  /** The owning product's sku, shared by all of its variants. */
  sku: string;
  /** The name of the variant, which is where the size lives (for example `Talla 38`). */
  variantName: string;
  /** `null` when the request omitted `?storeId=`. */
  listPrice: number | null;
  /** `null` when the request omitted `?storeId=`; a purchase order line pre-fills with it. */
  costPrice: number | null;
  /** `null` when the request omitted `?storeId=`. */
  stock: number | null;
  enabled: boolean;
  /**
   * The measure unit this variant is counted in, as an id of the catalog that
   * {@link MeasureUnit} mirrors.
   *
   * `null` means **no unit declared**, which is the behavior every variant had
   * before the column existed: the quantity keeps whatever meaning the operator
   * reads into it. The unit lives on the variant, not on the product, so the same
   * product can hold a `Pieza` variant and a `Kgm` variant (S1, S3).
   *
   * Only the id travels: the label is resolved in the client from the catalog it
   * loads for the picker (D2). The store-scoped list carries this field too.
   */
  measureUnitId: string | null;
  /**
   * The `VARIANT`-level entries of the product category's attribute profile, keyed by
   * attribute `code` (S5, S6): a shoe's `color` and `acabado`.
   *
   * `Talla` is deliberately absent — `variantName` already is the size, so a second key
   * would be a second source of truth for the same fact (record L7). Absent means the
   * variant holds no attributes, which is the shape every row had before the catalog.
   */
  attributes?: Record<string, unknown>;
}

/**
 * Write body for the GLOBAL definition of a product variant
 * (`POST`/`PUT` under `/api/products/{productId}/variants`).
 *
 * It carries only what is global: the barcode, the name/size and the measure unit.
 * Stock and prices are per store and are written through
 * {@link ProductVariantStoreStockRequest}; the owning product travels in the path,
 * never in the body.
 *
 * There is no `enabled` field on the wire: the backend removed it, and re-enabling
 * a soft-deleted variant is a separate endpoint.
 */
export interface ProductVariantRequest {
  /** The variant identifier: globally unique barcode. */
  barCode: string;
  /** The name of the variant, which is where the size lives (for example `Talla 38`). */
  variantName: string;
  /**
   * The measure unit of this variant, or `null` for **no unit declared**.
   *
   * Optional on the wire — the backend takes an absent value as `null`, which is what
   * keeps every client that only sends barcode and name byte-identical to before (S7).
   * The field itself is not `?`: the form always sends an explicit value, and a write
   * that omits it would silently keep the stored unit instead of clearing it.
   *
   * The backend answers 404 for an unknown id, 400 for a disabled unit or a
   * `unit_type` that collides with another variant of the product (S6), and 409 when
   * the variant already holds stock or ledger movements (S5).
   */
  measureUnitId: string | null;
  /**
   * The `VARIANT`-level attribute set, keyed by attribute `code` (S5, S6).
   *
   * Optional on the wire, and **not** the same as `{}`: an absent field leaves the stored
   * set untouched while `{}` clears it. The dialog therefore omits it when the product's
   * category declares no variant-level attribute at all — wiping a variant's saved colour
   * because a screen could not read the profile would be a silent loss.
   */
  attributes?: Record<string, unknown>;
}

/**
 * Write body that upserts the PER-STORE row of a product variant
 * (`PUT /api/variants/{variantId}/stores/{storeId}`).
 *
 * Every field is optional and nullable. An `undefined` or `null` field means "leave
 * the stored value unchanged"; it is NOT a reset to zero. That lets a caller update
 * prices without touching stock and vice versa. On a first insert the backend
 * defaults `stock` to 0 and leaves both prices `null`.
 */
export interface ProductVariantStoreStockRequest {
  /** Sellable price in this store. `undefined`/`null` keeps the stored value. */
  listPrice?: number | null;
  /** Acquisition cost in this store. `undefined`/`null` keeps the stored value. */
  costPrice?: number | null;
  /** Sellable units in this store. `undefined`/`null` keeps the stored value. */
  stock?: number | null;
}

/**
 * Per-store row of a product variant, as returned by
 * `PUT /api/variants/{variantId}/stores/{storeId}`.
 *
 * Unlike {@link ProductVariant}, this is the pure store projection: it carries the
 * store id and the three store-scoped values, with no global definition fields.
 * A `null` price or stock means the row has no stored value yet.
 */
export interface ProductVariantStoreStock {
  /** The store this row describes. */
  companyStoreId: string;
  /** Sellable price in that store, or `null` when unset. */
  listPrice: number | null;
  /** Acquisition cost in that store, or `null` when unset. */
  costPrice: number | null;
  /** Sellable units in that store, or `null` when unset. */
  stock: number | null;
}

/**
 * A variant as returned by the store-scoped search
 * (`GET /api/product-variants/search?q=&storeId=`).
 *
 * Only the subset this feature consumes is declared, mirroring the S3 decision that
 * unused client surface is dead code; the backend also sends `enabled` and the
 * timestamps, and the fields can be added here without a breaking change when a screen
 * needs them (`productName` was added that way for the store-scoped stock search).
 *
 * The store-scoped fields are `null`-able even though search always runs inside one
 * store: a store row can exist with its prices never set. `sku` carries the PRODUCT
 * sku — the variant sku no longer exists (D2).
 */
export interface ProductVariantSearchResult {
  id: string;
  productId: string;
  /** The store the search ran in. Never `null` here, unlike {@link ProductVariant}. */
  companyStoreId: string;
  barCode: string;
  variantName: string;
  /** The owning product's name, joined in by search. */
  productName: string;
  listPrice: number | null;
  costPrice: number | null;
  stock: number | null;
}

/**
 * A unit of the measure-unit catalog (`measure_units`), as returned by
 * `GET /api/measure-units`.
 *
 * Mirrors the backend `MeasureUnitResponse`. The list endpoint answers with a **plain
 * array**, not a page, and by default only with the enabled units — which is exactly
 * the set the variant picker offers, so the client never filters: a unit hidden from
 * the picker is a unit the operator cannot assign (S4).
 *
 * `unitType` is deliberately not narrowed to a union: the picker offers every unit it
 * receives, and the `PRODUCT`/`SERVICE` coherence rule is the backend's to enforce,
 * per product, across the units its own variants already use (D4, S6).
 */
export interface MeasureUnit {
  id: string;
  /** The full name, for example `Kilogramo`. */
  measureUnitName: string;
  /** The abbreviation the picker shows first, for example `Kg`. */
  measureUnitShortName: string;
  /** `PRODUCT` or `SERVICE`, as declared by the catalog. Never used to filter here. */
  unitType: string;
  /** The SAT `c_ClaveUnidad` code, for example `KGM`. */
  satCode: string;
  description: string | null;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}
