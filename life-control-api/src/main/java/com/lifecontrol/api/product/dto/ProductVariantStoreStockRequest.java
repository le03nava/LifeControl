package com.lifecontrol.api.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import java.math.BigDecimal;

/**
 * Request payload that upserts the PER-STORE row of a product variant
 * ({@code product_variant_store_stock}).
 *
 * <p>Every field is optional. A {@code null} field leaves the stored value untouched, so a client
 * can update prices without touching stock and vice versa; on first creation the untouched columns
 * take their database defaults ({@code stock = 0}, prices {@code null}).</p>
 *
 * @param listPrice sellable price in this store (D1)
 * @param costPrice acquisition cost in this store (D1)
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
