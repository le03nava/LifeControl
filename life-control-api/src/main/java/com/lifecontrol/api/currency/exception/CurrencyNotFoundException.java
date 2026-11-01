package com.lifecontrol.api.currency.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;

/**
 * Thrown when a company states a currency code the platform catalog does not carry.
 *
 * <p>Thin subclass of {@link ResourceNotFoundException}: the {@code GlobalExceptionHandler} already maps
 * that class to 404 and needs no method of its own for this type — the same shape
 * {@code MeasureUnitNotFoundException} has, and the reason it is a 404 rather than a 400 is the same one:
 * the catalog is the reference data the caller picked from, so a code that is not in it is a name that
 * does not exist, not a malformed field. The message names the code, because the caller's only way to fix
 * it is to know which value was refused.</p>
 */
public class CurrencyNotFoundException extends ResourceNotFoundException {

    public CurrencyNotFoundException(String code) {
        super("Currency not found with code: " + code);
    }
}
