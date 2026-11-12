package com.lifecontrol.api.tax.exception;

import com.lifecontrol.api.exception.DuplicateResourceException;
import java.time.LocalDate;

/**
 * 409 category for a rate whose {@code (company, tax_code, effective_from)} already exists.
 *
 * <p>The uniqueness rule is the schema's, not this class's: {@code uq_tax_rate_company_code_from} states it, so
 * a second row for the same day would be refused by the database anyway. Raising it here first is what turns
 * a generic "violates a data constraint" into a sentence that names the three facts the caller has to change
 * one of. The race between the check and the insert is still covered by the constraint, which
 * {@code GlobalExceptionHandler} answers with the same 409.</p>
 */
public class DuplicateTaxRateException extends DuplicateResourceException {

    public DuplicateTaxRateException(String taxCode, LocalDate effectiveFrom) {
        super("Tax rate for code '" + taxCode + "' effective from " + effectiveFrom
                + " already exists for this company");
    }
}
