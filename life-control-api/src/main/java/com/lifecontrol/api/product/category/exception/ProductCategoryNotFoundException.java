package com.lifecontrol.api.product.category.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;
import java.util.UUID;

/**
 * 404 category for a category that does not exist or does not belong to the requested company.
 *
 * <p>The service always looks a category up through a company-scoped query
 * ({@code findByCompanyIdAndId}), so a row owned by another company resolves to this same not-found
 * instead of a 200 — the tree of one company is never reachable from another company's path.</p>
 */
public class ProductCategoryNotFoundException extends ResourceNotFoundException {

    public ProductCategoryNotFoundException(UUID id) {
        super("Product category not found with id: " + id);
    }
}
