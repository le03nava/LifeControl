package com.lifecontrol.api.product.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Response of the per-store state of a product variant: the listing's price and the position's cost and
 * quantity.
 *
 * <p>The three values no longer come from one row (ADR-0006 decision 6): {@code listPrice} is the
 * listing's, {@code costPrice} and {@code stock} are {@code product_variant_store_stock}'s. Field names
 * are unchanged on purpose — a client that only reads {@code listPrice} keeps working, and it now reads
 * the price that is actually stored.</p>
 *
 * @param companyStoreId the store this state belongs to
 * @param listPrice sellable price in that store, read back from {@code variant_listing} after the write
 * @param costPrice acquisition cost in that store
 * @param stock sellable units in that store
 */
public record ProductVariantStoreStockResponse(
        UUID companyStoreId, BigDecimal listPrice, BigDecimal costPrice, BigDecimal stock) {}
