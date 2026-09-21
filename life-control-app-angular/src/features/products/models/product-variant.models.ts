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
