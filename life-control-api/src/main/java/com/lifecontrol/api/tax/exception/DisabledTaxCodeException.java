package com.lifecontrol.api.tax.exception;

import com.lifecontrol.api.tax.model.TaxCode;

/**
 * Thrown when a product states a fiscal code the catalog still holds but has retired.
 *
 * <p>Thin subclass of {@link IllegalArgumentException}, the shape {@code DisabledCurrencyException} copies: the
 * {@code GlobalExceptionHandler} already maps that class to {@code 400} with the message as the body, so no
 * handler method and no new status is invented here. It is a client error rather than a server one because the
 * catalog is read on every write that names a code, so a retired one can only arrive from a caller holding a
 * stale selection — and the row is kept, never deleted, so history that names it keeps resolving
 * ({@link TaxCode#getEnabled()}).</p>
 */
public class DisabledTaxCodeException extends IllegalArgumentException {

    public DisabledTaxCodeException(TaxCode taxCode) {
        super("Tax code is disabled and cannot be assigned to a product: " + taxCode.getCode());
    }
}
