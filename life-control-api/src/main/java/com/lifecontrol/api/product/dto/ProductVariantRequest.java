package com.lifecontrol.api.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
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
 * <p>{@code measureUnitId} is <b>required</b> since ADR-0006 decision 3: a quantity without a unit is a
 * meaningless number, and the catalog it needs ({@code measure_units}) already ships. The service
 * resolves it against that catalog, so the request carries the id only — the label is resolved by the
 * Angular client from the catalog it already loads (D2). Omission is refused with a 400 that names this
 * field ({@code MeasureUnitRequiredException}), never by the {@code NOT NULL} column: the same rule is
 * stated twice on purpose, once so a client gets a message and once so no other writer can skip it.</p>
 *
 * <p>The sizes mirror the columns: {@code bar_code} is 100 and {@code variant_name} is 255.</p>
 *
 * <p>{@code attributes} carries the VARIANT-level entries of the category's attribute profile (V37) —
 * a shoe's {@code color} and {@code acabado} — and is <b>appended last</b> so the three-argument shape
 * every existing caller uses keeps compiling through the convenience constructor below. Its values are
 * validated against the product's category by {@code ProductAttributeCatalogService}; {@code null}
 * leaves the stored set untouched, and {@code {}} clears it.</p>
 */
public record ProductVariantRequest(
        @NotBlank(message = "barCode is required") @Size(max = 100, message = "barCode must not exceed 100 characters")
        String barCode,

        @NotBlank(message = "variantName is required")
        @Size(max = 255, message = "variantName must not exceed 255 characters")
        String variantName,

        @NotNull(message = "measureUnitId is required") UUID measureUnitId,

        Map<String, Object> attributes) {

    /**
     * The pre-attributes shape, kept so every caller that predates the variant attribute catalog keeps
     * compiling and keeps meaning "write no variant attributes".
     */
    public ProductVariantRequest(String barCode, String variantName, UUID measureUnitId) {
        this(barCode, variantName, measureUnitId, null);
    }
}
