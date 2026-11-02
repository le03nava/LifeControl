package com.lifecontrol.api.product.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Response of the per-store state of a product variant: the listing's price and the ledger's cost and
 * quantity.
 *
 * <p>The three values do not come from one row (ADR-0006 decision 6, ADR-0001 decision 2): {@code listPrice}
 * is the listing's, {@code costPrice} is {@code product_variant_store_stock}'s, and since T5b of
 * {@code odd/tasks/inventory-ledger.md} {@code stock} is read back from {@code stock_position} — the same
 * number the store-scoped list and the POS search answer with, so the editor cannot report a column no writer
 * maintains once the legacy write stops (T5c). Field names are unchanged on purpose — a client that only reads
 * {@code listPrice} keeps working, and it now reads the price that is actually stored.</p>
 *
 * @param companyStoreId the store this state belongs to
 * @param listPrice sellable price in that store, read back from {@code variant_listing} after the write
 * @param costPrice acquisition cost in that store
 * @param stock the ledger's position in that store, read back after the write — <b>{@code null} when the ledger
 *     holds no movement for this variant in this store</b>, which is "nothing observed here" and not a zero
 */
public record ProductVariantStoreStockResponse(
        UUID companyStoreId, BigDecimal listPrice, BigDecimal costPrice, BigDecimal stock) {}
