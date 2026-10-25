package com.lifecontrol.api.product.category.exception;

import com.lifecontrol.api.exception.DuplicateResourceException;

/**
 * 409 category for a category whose name collides with a sibling of the same company.
 *
 * <p>"Sibling" is the whole rule: the same name under two different parents is legal, and two roots
 * with the same name are not, because roots are siblings of each other. The message therefore names
 * the parent the collision happened under — {@code null} for a root — instead of claiming the name is
 * taken across the company, which would be false.</p>
 *
 * <p>The database enforces the same rule through the expression index
 * {@code uq_product_categories_sibling_name}, so a racing insert that slips past this check still
 * fails closed: {@code DataIntegrityViolationException} maps to 409 as well.</p>
 */
public class DuplicateProductCategoryException extends DuplicateResourceException {

    public DuplicateProductCategoryException(String categoryName, Object parentCategoryId) {
        super("Product category with name '" + categoryName + "' already exists under parent "
                + (parentCategoryId == null ? "<root>" : parentCategoryId) + " for this company");
    }
}
