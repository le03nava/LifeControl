package com.lifecontrol.api.product.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
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
 * <p>{@code variants} is ADR-0006 decision 2: a product is a family with at least one variant, so the
 * variant list is <b>part of the creation</b> and not a second request. {@code SIMPLE} means exactly one,
 * {@code MULTI} means one or more, and a request that names none is refused — the schema forces it, because
 * {@code product_variants.variant_name} is {@code NOT NULL} and nothing may invent one. There is therefore
 * no "variant step": the caller always states what it creates, and the backend writes the product and its
 * variants in <b>one transaction</b> ({@code ProductService#createProduct}).</p>
 *
 * <p>It is <b>creation-only</b>: {@code updateProduct} ignores it, because a variant is managed through
 * {@code /products/{id}/variants} and the mode is a creation fact (decision 1). A caller that predates the
 * field still compiles through the convenience constructors below, which carry no variant — that is a
 * request the creation path refuses with a 400 that names this field, never a product written with none.</p>
 *
 * <p>{@code variants} is <b>appended last</b>, after {@code variantMode}, so the seven- and eight-argument
 * shapes every existing caller uses keep compiling.</p>
 *
 * <p>It is <b>{@code @Valid}-cascaded</b> so a violation inside a variant is refused at the boundary, with the
 * key bean validation gives a list element — {@code variants[<index>].<field>} — instead of travelling to the
 * service, which could only answer about the list as a whole. That key is the one a client needs to mark the
 * offending row's control, and it cannot collide with a first-level key such as {@code sku}: the response map
 * holds both, because their paths differ.</p>
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
        String variantMode,

        @Valid List<ProductVariantRequest> variants) {

    /**
     * The pre-mode shape, kept so every caller that predates {@code variant_mode} keeps compiling and
     * keeps meaning "the single-variant article".
     *
     * <p>It carries no variant, so it is a valid <b>update</b> payload and an invalid creation one — which is
     * exactly what ADR-0006 decision 2 says a product is not: a row with no variant.</p>
     */
    public ProductRequest(
            String sku,
            String name,
            String shortName,
            String satCode,
            String productType,
            Map<String, Object> attributes,
            UUID categoryId) {
        this(sku, name, shortName, satCode, productType, attributes, categoryId, null, null);
    }

    /**
     * The pre-variants shape: a mode and no variant list.
     *
     * <p>Kept for the update path and for the callers that state a mode without a family. Like the shape
     * above it declares no variant, so the creation path refuses it with a 400 naming {@code variants}.</p>
     */
    public ProductRequest(
            String sku,
            String name,
            String shortName,
            String satCode,
            String productType,
            Map<String, Object> attributes,
            UUID categoryId,
            String variantMode) {
        this(sku, name, shortName, satCode, productType, attributes, categoryId, variantMode, null);
    }
}
