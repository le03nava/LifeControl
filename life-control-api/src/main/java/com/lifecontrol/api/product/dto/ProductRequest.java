package com.lifecontrol.api.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;

/**
 * Write payload for a product.
 *
 * <p>{@code categoryId} is the <b>deepest selected node</b> of the company's category tree, or
 * {@code null} to leave the product unclassified. It is the only key of the classification that
 * travels: the three selects are derived from it, and the company is the category's own — which is why
 * no company is part of this request. An id that does not exist is answered with the existing 404
 * category contract instead of an FK violation (see {@code ProductService}).</p>
 *
 * <p>{@code variantMode} is ADR-0006 decision 1: whether the article is one sellable thing
 * ({@link com.lifecontrol.api.product.model.Product#VARIANT_MODE_SIMPLE}) or a family of them
 * ({@code MULTI}). It is a property of the article, stated at creation and not at update, and its
 * absence means {@code SIMPLE} — the article every caller that predates the mode creates. The closed
 * vocabulary is the schema's ({@code ck_products_variant_mode}, V54) and this annotation is the request's
 * half of it, so a client gets a 400 that names the field instead of a constraint violation.</p>
 *
 * <p>{@code variantMode} is <b>appended last</b> so the seven-argument shape every existing caller uses
 * keeps compiling through the convenience constructor below.</p>
 */
public record ProductRequest(
        @NotBlank(message = "sku is required") @Size(max = 50, message = "sku must not exceed 50 characters")
        String sku,

        @NotBlank(message = "name is required") @Size(max = 255, message = "name must not exceed 255 characters")
        String name,

        @Size(max = 100, message = "shortName must not exceed 100 characters")
        String shortName,

        @Size(max = 20, message = "satCode must not exceed 20 characters")
        String satCode,

        @Size(max = 50, message = "productType must not exceed 50 characters")
        String productType,

        Map<String, Object> attributes,

        UUID categoryId,

        @Pattern(regexp = "SIMPLE|MULTI", message = "variantMode must be SIMPLE or MULTI")
        String variantMode) {

    /**
     * The pre-mode shape, kept so every caller that predates {@code variant_mode} keeps compiling and
     * keeps meaning "the single-variant article".
     */
    public ProductRequest(
            String sku,
            String name,
            String shortName,
            String satCode,
            String productType,
            Map<String, Object> attributes,
            UUID categoryId) {
        this(sku, name, shortName, satCode, productType, attributes, categoryId, null);
    }
}
