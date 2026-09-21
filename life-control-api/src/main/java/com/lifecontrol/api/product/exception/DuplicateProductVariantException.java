package com.lifecontrol.api.product.exception;

import com.lifecontrol.api.exception.DuplicateResourceException;

/**
 * Thrown when a variant definition would collide with an existing one: either the same global
 * {@code bar_code} (D2) or the same {@code (product_id, variant_name)} pair (D3).
 *
 * <p>Thin subclass of {@link DuplicateResourceException}, exactly like
 * {@code DuplicateProductException}: the {@code GlobalExceptionHandler} maps the parent to 409 and
 * needs no method of its own for this type.</p>
 */
public class DuplicateProductVariantException extends DuplicateResourceException {

    public DuplicateProductVariantException(String message) {
        super(message);
    }
}
