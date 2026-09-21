package com.lifecontrol.api.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload for creating or updating the GLOBAL definition of a product variant.
 *
 * <p>It carries only what is global: the barcode ({@link #barCode()}, D2) and the name/size
 * ({@link #variantName()}, D3). Stock and prices are per store and are written through
 * {@link ProductVariantStoreStockRequest} on the store-scoped endpoint; the owning product is the
 * path variable, never the body.</p>
 *
 * <p>The sizes mirror the columns: {@code bar_code} is 100 and {@code variant_name} is 255.</p>
 */
public record ProductVariantRequest(
        @NotBlank(message = "barCode is required") @Size(max = 100, message = "barCode must not exceed 100 characters")
        String barCode,

        @NotBlank(message = "variantName is required")
        @Size(max = 255, message = "variantName must not exceed 255 characters")
        String variantName) {}
