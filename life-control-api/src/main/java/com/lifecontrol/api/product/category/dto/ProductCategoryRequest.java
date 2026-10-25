package com.lifecontrol.api.product.category.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Write payload for a product category, shared by create and update.
 *
 * <p>One record covers both operations because this catalog has no {@code version} column and
 * therefore no 412 precondition to carry — the split the company domain needed for
 * {@code CompanyStore} has no purpose here, the same reasoning {@code DepartmentRequest} records.</p>
 *
 * <p>{@code parentCategoryId} is {@code null} for a root and must name a category of the same company
 * otherwise; a different company's id is a 404, not a bad request, because the service never looks it
 * up outside the company. {@code enabled} defaults to {@code true} and {@code displayOrder} to
 * {@code 0} — the column default — so a create without them is legal and both stay explicit when the
 * caller cares. {@code displayOrder} allows {@code 0} on purpose: the column defaults to it, so
 * {@code @Positive} would reject the very value a fresh category gets.</p>
 */
public record ProductCategoryRequest(
        UUID parentCategoryId,

        @NotBlank(message = "categoryName is required")
        @Size(max = 100, message = "categoryName must not exceed 100 characters")
        String categoryName,

        @Size(max = 255, message = "description must not exceed 255 characters")
        String description,

        @PositiveOrZero(message = "displayOrder must not be negative")
        Integer displayOrder,

        Boolean enabled) {

    public ProductCategoryRequest {
        if (enabled == null) {
            enabled = true;
        }
        if (displayOrder == null) {
            displayOrder = 0;
        }
    }
}
