package com.lifecontrol.api.product.dto;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Read model of a product.
 *
 * <p>{@code categoryId} is the deepest node of the company's category tree the product is classified
 * under, and {@code categoryCompanyId} is the company that owns that node — both {@code null} for an
 * unclassified product. The second one exists because the form needs it: {@code /api/products} carries
 * no company segment, so a caller cannot otherwise know which company's tree to load to render the
 * saved path, and a product classified under company A must still resolve while the screen's company
 * context points at B.</p>
 *
 * <p>The category's own {@code satCode} is deliberately <b>not</b> here: the product's {@code satCode}
 * is a suggestion the operator accepted or edited, so the value that belongs in a response is the one
 * actually stored on the product.</p>
 */
public record ProductResponse(
        UUID id,
        String sku,
        String name,
        String shortName,
        String satCode,
        String productType,
        Map<String, Object> attributes,
        Boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        UUID categoryId,
        UUID categoryCompanyId) {}
