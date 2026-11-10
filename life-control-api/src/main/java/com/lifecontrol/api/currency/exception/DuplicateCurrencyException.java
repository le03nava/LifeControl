package com.lifecontrol.api.currency.exception;

import com.lifecontrol.api.exception.DuplicateResourceException;

/**
 * Thrown when a caller tries to add a currency code the catalog already carries.
 *
 * <p>Thin subclass of {@link DuplicateResourceException}: the {@code GlobalExceptionHandler} already maps
 * that class to 409 and needs no method of its own for this type — the shape
 * {@code DuplicatePaymentMethodException} has. The name is the code and not a generated id because the
 * code <b>is</b> the key: a second row for {@code MXN} is the same currency under a different
 * {@code minorUnits}, which is how a document rounded against {@code 2} would come back rounded against
 * {@code 0}.</p>
 *
 * <p>It is a refused <b>create</b> only. A {@code PUT} names its row in the path, so it cannot produce a
 * duplicate; it answers 404 for a code the catalog does not carry.</p>
 */
public class DuplicateCurrencyException extends DuplicateResourceException {

    public DuplicateCurrencyException(String code) {
        super("Currency with code '" + code + "' already exists");
    }
}
