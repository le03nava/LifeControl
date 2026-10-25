package com.lifecontrol.api.product.category.exception;

import com.lifecontrol.api.exception.ConflictException;

/**
 * 409 category for a create or a re-enable whose parent category — or any ancestor of it — is
 * soft-deleted ({@code enabled = false}).
 *
 * <p>The request is well formed and the parent exists; it conflicts with the current state of the
 * tree. This is the same rule the store tree applies to its own nodes
 * ({@code com.lifecontrol.api.store.exception.DisabledParentException}), restated here rather than
 * imported so the product module does not depend on the store domain for its own vocabulary: the
 * classes are named the same on purpose, so the two trees answer with the same 409 and the same
 * reasoning.</p>
 */
public class DisabledParentCategoryException extends ConflictException {

    public DisabledParentCategoryException(String message) {
        super(message);
    }
}
