package com.lifecontrol.api.product.attribute.exception;

/**
 * 400 for a value that does not belong to the attribute catalog: an unknown code, a value outside its
 * definition's allowed set, a measurement without its unit, or a missing required attribute.
 *
 * <p>It extends {@link IllegalArgumentException} so the existing handler
 * ({@code GlobalExceptionHandler}, which maps that type to 400 with its message) answers without a new
 * mapping. The message names the offending code, because the operator's next action depends on which
 * one it is.</p>
 *
 * <p>The check lives in the service and not only in the form on purpose: the form is not an integrity
 * boundary, and any other client writes through the same endpoint.</p>
 */
public class ProductAttributeValidationException extends IllegalArgumentException {

    public ProductAttributeValidationException(String message) {
        super(message);
    }
}
