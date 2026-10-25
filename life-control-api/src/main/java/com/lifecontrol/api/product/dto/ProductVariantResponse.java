package com.lifecontrol.api.product.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Read projection of a product variant.
 *
 * <p>The field set is frozen for the Angular client: it keeps the exact names it had before the
 * variant-identity split, so the frontend compiles unchanged while slice S3 adapts it. What changed
 * is where the values come from:</p>
 *
 * <ul>
 *   <li>{@code id}, {@code productId}, {@code barCode}, {@code variantName}, {@code enabled},
 *       {@code createdAt}, {@code updatedAt} come from the global definition
 *       ({@code product_variants}).</li>
 *   <li>{@code companyStoreId}, {@code listPrice}, {@code costPrice}, {@code stock} come from the
 *       per-store row ({@code product_variant_store_stock}). They are <strong>{@code null} whenever
 *       the read is not narrowed to a store</strong> (no {@code storeId} query parameter), because
 *       there is no single store whose values could be reported.</li>
 *   <li>{@code sku} now carries the <strong>PRODUCT</strong> sku ({@code products.sku}). The variant
 *       sku was deleted by decision D2, so this field is not a leftover: it is the product sku by
 *       contract.</li>
 *   <li>{@code measureUnitId} is the unit the quantities of this variant are expressed in, or
 *       {@code null} when the variant declares none (D1, S1). It is <strong>appended last</strong>
 *       on purpose: the field set is additive so every existing client keeps compiling and keeps
 *       reading the fields it already knew.</li>
 * </ul>
 *
 * <p>The response carries the measure unit <strong>id only</strong> and never its label: decision
 * D2 resolves {@code measureUnitShortName} in the Angular client from the catalog it already loads
 * for the picker. Denormalising the short name here would cost a join on the store-scoped JPQL
 * constructor and an N+1 on the definition list, to save the client a lookup it never skips.</p>
 */
public record ProductVariantResponse(
        UUID id,
        UUID productId,
        UUID companyStoreId,
        String barCode,
        String sku,
        String variantName,
        BigDecimal listPrice,
        BigDecimal costPrice,
        BigDecimal stock,
        Boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        UUID measureUnitId) {}
