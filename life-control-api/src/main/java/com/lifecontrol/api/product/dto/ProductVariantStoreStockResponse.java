package com.lifecontrol.api.product.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Response of the per-store row of a product variant, as stored in
 * {@code product_variant_store_stock}.
 *
 * @param companyStoreId the store this row belongs to
 * @param listPrice sellable price in that store
 * @param costPrice acquisition cost in that store
 * @param stock sellable units in that store
 */
public record ProductVariantStoreStockResponse(
        UUID companyStoreId, BigDecimal listPrice, BigDecimal costPrice, BigDecimal stock) {}
