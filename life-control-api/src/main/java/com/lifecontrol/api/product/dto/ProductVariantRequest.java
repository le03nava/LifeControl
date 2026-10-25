package com.lifecontrol.api.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Request payload for creating or updating the GLOBAL definition of a product variant.
 *
 * <p>It carries only what is global: the barcode ({@link #barCode()}, D2), the name/size
 * ({@link #variantName()}, D3) and the measure unit the quantities of this variant are expressed in
 * ({@link #measureUnitId()}, D1/V29). Stock and prices are per store and are written through
 * {@link ProductVariantStoreStockRequest} on the store-scoped endpoint; the owning product is the
 * path variable, never the body.</p>
 *
 * <p>{@code measureUnitId} is optional and nullable: a variant whose unit is not declared keeps
 * {@code null}, which is exactly the behavior every variant had before the column existed (S1,
 * S7). When it is present the service resolves it against the {@code measure_units} catalog, so the
 * request carries the id only — the label is resolved by the Angular client from the catalog it
 * already loads (D2).</p>
 *
 * <p>The sizes mirror the columns: {@code bar_code} is 100 and {@code variant_name} is 255.</p>
 */
public record ProductVariantRequest(
        @NotBlank(message = "barCode is required") @Size(max = 100, message = "barCode must not exceed 100 characters")
        String barCode,

        @NotBlank(message = "variantName is required")
        @Size(max = 255, message = "variantName must not exceed 255 characters")
        String variantName,

        UUID measureUnitId) {}
