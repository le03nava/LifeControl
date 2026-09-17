package com.lifecontrol.api.store.exception;

import com.lifecontrol.api.exception.ConflictException;

/**
 * Thrown when a store-tree node is created or re-enabled while its parent — or any ancestor up to
 * the store — is soft-deleted ({@code enabled = false}).
 *
 * <p>Maps to {@code 409 Conflict}: the request is well formed but conflicts with the current state
 * of the tree. State conflicts belong to the {@link ConflictException} category, the same one used
 * by {@link com.lifecontrol.api.purchaseorder.exception.InvalidStatusTransitionException}.</p>
 */
public class DisabledParentException extends ConflictException {

    public DisabledParentException(String message) {
        super(message);
    }
}
