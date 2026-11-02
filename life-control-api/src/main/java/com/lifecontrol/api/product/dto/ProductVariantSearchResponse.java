package com.lifecontrol.api.product.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Search projection of a product variant, enriched with the product name and sku.
 *
 * <p>Field names are frozen for the Angular client, exactly like {@link ProductVariantResponse}.
 * Search always runs inside a store, so the store-scoped values are always present here:</p>
 *
 * <ul>
 *   <li>{@code id}, {@code productId}, {@code barCode}, {@code variantName}, {@code enabled},
 *       {@code createdAt}, {@code updatedAt} come from the global definition.</li>
 *   <li>{@code companyStoreId}, {@code listPrice}, {@code costPrice}, {@code stock} come from the
 *       per-store row for the searched store.</li>
 *   <li>{@code sku} carries the <strong>PRODUCT</strong> sku ({@code products.sku}); the variant sku
 *       no longer exists (D2). {@code productSku} keeps carrying the same product sku, so both
 *       fields report it.</li>
 *   <li>{@code listed} says whether this store sells the variant at all (ADR-0006 decision 6):
 *       {@code true} when the store has a listing for it, {@code false} when it holds stock without a
 *       price. It is the field that lets a client tell "stock without price" from "not carried here" and
 *       from "listed, nothing to sell", and it is named after that fact and not after
 *       {@code variant_listing} — see {@link ProductVariantResponse#listed()} for the four states.
 *       Appended last, like every field before it.</li>
 * </ul>
 */
public record ProductVariantSearchResponse(
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
        String productName,
        String productSku,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Boolean listed) {

    /** The pre-listing shape: a caller that does not read the four states states {@code listed = null}. */
    public ProductVariantSearchResponse(
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
            String productName,
            String productSku,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
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
                productName,
                productSku,
                createdAt,
                updatedAt,
                null);
    }
}
