package com.lifecontrol.api.tax.exception;

import com.lifecontrol.api.exception.DuplicateResourceException;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 409 category for a rate whose {@code (company, code, day)} already exists <b>in the same scope</b>.
 *
 * <p>The uniqueness rule is the schema's, not this class's: {@code V95} states it as two partial unique
 * indexes — {@code uq_tax_rate_scope_from} for the company-wide rows and {@code uq_tax_rate_region_scope_from}
 * for the region ones — so two rates for one day are legal exactly when their scopes differ, which is what
 * makes the frontier's 8% and the country's 16% representable for the same code (issue #855). Raising this
 * before the insert is what turns a generic "violates a data constraint" into a sentence that names the facts
 * the caller has to change one of, and it names the <b>scope</b> because that is the fact that makes the day
 * available or not: «a region already holds that day» and «the company-wide rate already holds that day» are
 * two different rows the caller would add. The race between the check and the insert is still covered by the
 * indexes, which {@code GlobalExceptionHandler} answers with the same 409.</p>
 */
public class DuplicateTaxRateException extends DuplicateResourceException {

    public DuplicateTaxRateException(
            String taxCode, UUID companyRegionId, String companyRegionName, LocalDate effectiveFrom) {
        super("Tax rate for code '" + taxCode + "' effective from " + effectiveFrom
                + " already exists for this company " + scope(companyRegionId, companyRegionName));
    }

    /**
     * The scope that holds the day, as the caller stated it.
     *
     * <p>The region is named when it is the one the request asked for, and "at company-wide scope" otherwise —
     * which is the half of the sentence that tells an operator whether to pick another day or another region.
     * </p>
     */
    private static String scope(UUID companyRegionId, String companyRegionName) {
        if (companyRegionId == null) {
            return "at company-wide scope";
        }
        return companyRegionName == null || companyRegionName.isBlank()
                ? "at region scope: region " + companyRegionId
                : "at region scope: region '" + companyRegionName + "' (" + companyRegionId + ")";
    }
}
