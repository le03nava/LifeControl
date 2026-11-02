package com.lifecontrol.api.product.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
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
 *
 * <p>{@code attributes} is the variant-level attribute set (V37): the codes of the attribute profile
 * that applies to the product's category, with the values it validated. It is <b>appended last</b> for
 * the same reason {@code measureUnitId} was — the field set is additive, so a client that predates the
 * attribute catalog keeps reading what it knew and simply ignores the new field.</p>
 *
 * <p><b>{@code listed} and why the name is that one (ADR-0006 decision 6).</b> Since
 * {@code variant_listing} exists, a store-scoped row is in one of four states and a client cannot tell
 * them apart from the values alone:
 * {@code stock = null} means "no position" for one store and "the read is not narrowed to a store" for
 * another, and {@code listPrice = null} means "unpriced" for one row and "not sold here" for another.
 * The flag answers the question decision 6 asks — <em>is this variant sellable in this store</em> — and it
 * is named after the fact it reports (<b>listed</b>: this store carries it and states a price) rather than
 * after the table it is read from, so a client reading {@code listed: false, stock: 4} renders "stock
 * without price" instead of the "listed, nothing to sell" or "not carried here" the other rows mean. {@code true} with {@code stock = null} is
 * "listed, nothing to sell", and the only sellable row has both.</p>
 *
 * <p>{@code listed} is <b>appended last</b> like every field before it, and it is {@code null} exactly when
 * the read is not narrowed to a store — the same rule as {@code companyStoreId}, {@code listPrice} and
 * {@code stock}. A client that only reads {@code listPrice} keeps working: it now carries the listing's
 * price, which is the same number the backfill copied.</p>
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
        UUID measureUnitId,
        Map<String, Object> attributes,
        Boolean listed) {

    /**
     * The pre-listing shape: a caller that does not know the four states states {@code listed = null},
     * which is exactly what an unscoped read means.
     */
    public ProductVariantResponse(
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
            UUID measureUnitId,
            Map<String, Object> attributes) {
        this(
                id,
                productId,
                companyStoreId,
                barCode,
                sku,
                variantName,
                listPrice,
                costPrice,
                stock,
                enabled,
                createdAt,
                updatedAt,
                measureUnitId,
                attributes,
                null);
    }

    /**
     * The pre-attributes shape, kept so a caller that predates the variant attribute catalog keeps
     * compiling. It states {@code attributes = null}, which is exactly what those callers meant.
     */
    public ProductVariantResponse(
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
            UUID measureUnitId) {
        this(
                id,
                productId,
                companyStoreId,
                barCode,
                sku,
                variantName,
                listPrice,
                costPrice,
                stock,
                enabled,
                createdAt,
                updatedAt,
                measureUnitId,
                null,
                null);
    }
}
