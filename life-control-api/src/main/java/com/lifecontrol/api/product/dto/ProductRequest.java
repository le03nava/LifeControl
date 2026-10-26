package com.lifecontrol.api.product.dto;

import jakarta.validation.constraints.NotBlank;
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

        UUID categoryId) {}
