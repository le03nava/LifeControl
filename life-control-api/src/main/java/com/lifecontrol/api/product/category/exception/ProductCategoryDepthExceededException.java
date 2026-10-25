package com.lifecontrol.api.product.category.exception;

import com.lifecontrol.api.exception.ConflictException;

/**
 * 409 category for a create or a re-parent that would push the tree past its three-level ceiling
 * (decision D2: {@code categoria} &rarr; {@code subcategoria} &rarr; {@code sub-subcategoria}).
 *
 * <p>The ceiling is not a database constraint — a depth {@code CHECK} cannot be expressed without a
 * trigger — so this exception is the rule's only enforcement point, and its message states the
 * resulting level so the caller learns what the request asked for, not just that it was refused.</p>
 */
public class ProductCategoryDepthExceededException extends ConflictException {

    public ProductCategoryDepthExceededException(int attemptedDepth, int maximumDepth) {
        super("Product category hierarchy is limited to " + maximumDepth + " levels; this operation would"
                + " create level " + attemptedDepth);
    }
}
