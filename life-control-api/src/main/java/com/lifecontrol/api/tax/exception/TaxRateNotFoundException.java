package com.lifecontrol.api.tax.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;
import java.util.UUID;

/**
 * 404 category for a rate that does not exist or does not belong to the requested company.
 *
 * <p>The service always looks a rate up through a company-scoped query, so a row owned by another company
 * resolves to this same not-found instead of a 200 — the issue's S4, and the answer the policy would give
 * anyway: {@code tax_rate} is armed with {@code FORCE} row-level security, so another tenant's row is not
 * merely hidden from the caller, it is invisible to the connection.
 */
public class TaxRateNotFoundException extends ResourceNotFoundException {

    public TaxRateNotFoundException(UUID id) {
        super("Tax rate not found with id: " + id);
    }
}
