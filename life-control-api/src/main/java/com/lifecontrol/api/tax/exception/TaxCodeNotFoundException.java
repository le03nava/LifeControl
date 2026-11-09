package com.lifecontrol.api.tax.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;

/**
 * Thrown when a product states a fiscal code the platform catalog does not carry.
 *
 * <p>Thin subclass of {@link ResourceNotFoundException}, the same shape
 * {@code CurrencyNotFoundException} has and for the same reason: the {@code GlobalExceptionHandler} already maps
 * that class to {@code 404}, and the catalog is reference data the caller picked from, so a code it does not hold
 * is a name that does not exist rather than a malformed field. The message names the code, because naming the
 * refused value is the caller's only way to fix it.</p>
 *
 * <p><b>Why this exists at all.</b> Until T6 of {@code odd/tasks/product-tax-code.md} an unknown code travelled
 * to {@code fk_products_tax_code} and came back as a {@code DataIntegrityViolation} — a {@code 500} that says a
 * constraint was violated and nothing about which value was wrong (S2's "an unknown code is a client error, not
 * a server one"). Asking the catalog first turns it into the answer the currency path already gives.</p>
 */
public class TaxCodeNotFoundException extends ResourceNotFoundException {

    public TaxCodeNotFoundException(String code) {
        super("Tax code not found with code: " + code);
    }
}
