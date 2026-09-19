/**
 * Product variant as returned by the nested
 * `GET /api/products/{productId}/variants` endpoint.
 *
 * Mirrors the backend `ProductVariantResponse`: `costPrice` is what a purchase
 * order line pre-fills its unit price with, and `companyStoreId` is the store
 * the variant belongs to (the endpoint can be narrowed with `?storeId=`).
 */
export interface ProductVariant {
  id: string;
  productId: string;
  companyStoreId: string;
  barCode: string | null;
  sku: string;
  variantName: string;
  listPrice: number;
  costPrice: number;
  stock: number;
  enabled: boolean;
}
