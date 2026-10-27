package com.lifecontrol.api.product.attribute.dto;

import java.util.List;

/**
 * The attribute profile that resolves for one product category, partitioned by the entity each
 * attribute belongs to.
 *
 * <p>{@code productAttributes} feeds the product form and {@code variantAttributes} the variant dialog.
 * They are partitioned here rather than filtered by the client because the split is a fact of the
 * catalog, not a presentation choice: rendering a variant-level {@code color} on the product form would
 * suggest a product can have one colour, which is the mistake the level exists to prevent.</p>
 *
 * <p>{@code resolvedRootName} names the category root the attachment was found under, so a screen can
 * explain which branch supplied the attributes and an operator can tell a missing attachment from a
 * misnamed root. {@code profileKey} identifies the profile itself; {@code label} is the branch's
 * human-readable name.</p>
 */
public record ProductAttributeProfileResponse(
        String profileKey,
        String label,
        String resolvedRootName,
        List<ProductAttributeDefinitionResponse> productAttributes,
        List<ProductAttributeDefinitionResponse> variantAttributes) {}
