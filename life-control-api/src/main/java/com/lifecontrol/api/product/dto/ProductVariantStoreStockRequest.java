package com.lifecontrol.api.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import java.math.BigDecimal;

/**
 * Request payload that upserts the PER-STORE state of a product variant.
 *
 * <p>Every field is optional, and {@code null} means "leave the stored value as it is" — including
 * {@code listPrice}, which never clears a price. The three fields no longer share one row: since
 * ADR-0006 decision 6 the price is a listing ({@code variant_listing}) and {@code costPrice} and
 * {@code stock} stay on {@code product_variant_store_stock}, which is the position until ADR-0001's
 * ledger retires it.</p>
 *
 * @param listPrice sellable price in this store (D1) — written to the listing, and the row is created on
 *     the first price with the company's own currency ({@code V57})
 * @param costPrice acquisition cost in this store (D1) — the position's column, not the listing's: cost is
 *     the inbound movement's and the valuation projection's (ADR-0006 decision 5)
 * @param stock sellable units; normally moved by receipts and sales, settable here only to seed a
 *     store row explicitly
 */
public record ProductVariantStoreStockRequest(
        @DecimalMin(value = "0.00", message = "listPrice must be greater than or equal to 0")
        @Digits(integer = 10, fraction = 2, message = "listPrice must have at most 10 integer digits and 2 decimals")
        BigDecimal listPrice,

        @DecimalMin(value = "0.00", message = "costPrice must be greater than or equal to 0")
        @Digits(integer = 10, fraction = 2, message = "costPrice must have at most 10 integer digits and 2 decimals")
        BigDecimal costPrice,

        @DecimalMin(value = "0.00", message = "stock must be greater than or equal to 0")
        @Digits(integer = 10, fraction = 2, message = "stock must have at most 10 integer digits and 2 decimals")
        BigDecimal stock) {}
