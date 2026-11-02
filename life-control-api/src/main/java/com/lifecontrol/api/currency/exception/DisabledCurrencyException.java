package com.lifecontrol.api.currency.exception;

import com.lifecontrol.api.currency.model.Currency;

/**
 * Thrown when a company states a currency that exists but is disabled.
 *
 * <p>Thin subclass of {@link IllegalArgumentException}: the {@code GlobalExceptionHandler} already maps
 * that class to 400 and needs no method of its own for this type — the shape
 * {@code DisabledMeasureUnitException} copies. It is a client error rather than a server one because the
 * catalog is read on every write that names a currency, so a disabled code can only arrive from a caller
 * holding a stale selection. The row is kept, not deleted: history that references it keeps resolving.</p>
 */
public class DisabledCurrencyException extends IllegalArgumentException {

    public DisabledCurrencyException(Currency currency) {
        super("Currency is disabled and cannot be assigned to a company: " + currency.getCode());
    }
}
