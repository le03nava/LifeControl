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
}

/**
 * Write body for the GLOBAL definition of a product variant
 * (`POST`/`PUT` under `/api/products/{productId}/variants`).
 *
 * It carries only what is global: the barcode and the name/size. Stock and prices
 * are per store and are written through {@link ProductVariantStoreStockRequest};
 * the owning product travels in the path, never in the body.
 *
 * There is no `enabled` field on the wire: the backend removed it, and re-enabling
 * a soft-deleted variant is a separate endpoint.
 */
export interface ProductVariantRequest {
  /** The variant identifier: globally unique barcode. */
  barCode: string;
  /** The name of the variant, which is where the size lives (for example `Talla 38`). */
  variantName: string;
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
