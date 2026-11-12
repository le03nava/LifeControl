package com.lifecontrol.api.product.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Response of the per-store state of a product variant: the listing's price, the declared cost, the ledger's
 * cost and quantity.
 *
 * <p>The values do not come from one row (ADR-0006 decision 6, ADR-0001 decision 2): {@code listPrice}
 * is the listing's, {@code costPrice} is {@code product_variant_store_stock}'s, and since T5b of
 * {@code odd/tasks/inventory-ledger.md} {@code stock} is read back from {@code stock_position} — the same
 * number the store-scoped list and the POS search answer with, so the editor cannot report a column no writer
 * maintains once the legacy write stops (T5c). Field names are unchanged on purpose — a client that only reads
 * {@code listPrice} keeps working, and it now reads the price that is actually stored.</p>
 *
 * <p><b>{@code averageCost} is additive and derived, never a replacement for {@code costPrice}</b>
 * ({@code odd/tasks/purchase-cost-into-inventory.md}, S6 as the tree can actually satisfy it). The declared cost
 * is what the operator maintains and what the purchase screen pre-fills; the average is what the inventory
 * actually paid, read from the ledger's {@code stock_valuation.avg_unit_cost} — the weighted moving average
 * {@code V61} derives from the inbound movements (now booked at the purchase price, {@code V96}) and the
 * allocations that consumed them. It is {@code null} when the ledger holds no movement for this variant in this
 * store: no cost has been observed here, which is not a zero. Appended last, so a client that predates it keeps
 * reading every field it knew.</p>
 *
 * @param companyStoreId the store this state belongs to
 * @param listPrice sellable price in that store, read back from {@code variant_listing} after the write
 * @param costPrice acquisition cost declared for that store ({@code product_variant_store_stock})
 * @param stock the ledger's position in that store, read back after the write — <b>{@code null} when the ledger
 *     holds no movement for this variant in this store</b>, which is "nothing observed here" and not a zero
 * @param averageCost the ledger's derived average cost in that store ({@code stock_valuation.avg_unit_cost}), or
 *     {@code null} when the store holds no valuation for the variant
 */
public record ProductVariantStoreStockResponse(
        UUID companyStoreId, BigDecimal listPrice, BigDecimal costPrice, BigDecimal stock, BigDecimal averageCost) {

    /** The pre-average shape: a caller that states no derived cost states {@code averageCost = null}. */
    public ProductVariantStoreStockResponse(
            UUID companyStoreId, BigDecimal listPrice, BigDecimal costPrice, BigDecimal stock) {
        this(companyStoreId, listPrice, costPrice, stock, null);
    }
}
