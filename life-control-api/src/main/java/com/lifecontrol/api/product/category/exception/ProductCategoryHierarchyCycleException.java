package com.lifecontrol.api.product.category.exception;

import com.lifecontrol.api.exception.ConflictException;

/**
 * 409 category for a re-parent that would make a category its own ancestor: the requested parent is
 * either the category itself or a node inside its own subtree.
 *
 * <p>The database closes only the one-node case ({@code ck_product_categories_not_self_parent}); the
 * deeper cycle has to be closed here, because a self-referencing foreign key cannot express
 * "no ancestor of mine". A cycle that reached the table would make every depth-first read loop
 * forever, so this is a hard stop rather than a warning.</p>
 */
public class ProductCategoryHierarchyCycleException extends ConflictException {

    public ProductCategoryHierarchyCycleException(String message) {
        super(message);
    }

    /** The requested parent is the category being moved. */
    public static ProductCategoryHierarchyCycleException selfParent() {
        return new ProductCategoryHierarchyCycleException("A product category cannot be its own parent");
    }

    /** The requested parent sits inside the subtree of the category being moved. */
    public static ProductCategoryHierarchyCycleException descendantParent() {
        return new ProductCategoryHierarchyCycleException(
                "A product category cannot be moved under one of its own descendants");
    }
}
