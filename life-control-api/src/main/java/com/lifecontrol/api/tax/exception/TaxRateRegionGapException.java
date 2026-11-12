package com.lifecontrol.api.tax.exception;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 400 category for a document whose store sits in a region that states rates for the line's code but none that
 * applies on the document's date.
 *
 * <p><b>Why this is a refusal and not a fallback.</b> The precedence {@code D2} of
 * {@code odd/tasks/tax-rate-region-axis.md} settles it: a region that holds <b>no row at all</b> for a code
 * resolves from the company-wide rows, so nothing that works today changes; a region that <b>holds rows</b> is
 * authoritative for that code, and if none applies on the document's date the line is refused. The two are one
 * rule — <i>a region that has stated rates is authoritative for them</i> — and the alternative is not a gap
 * but a wrong number: a company on the northern frontier charges 8% IVA there and 16% everywhere else for the
 * same code, so a fallback would charge the 16% in the place that must not pay it, silently, on a document
 * whose rate the schema accepts. Refusing unconditionally was rejected for the mirror reason: with no region
 * rows anywhere it would refuse every line in the tree.</p>
 *
 * <p>Thin subclass of {@link IllegalArgumentException}, the shape {@link InvalidTaxRateSupersessionException}
 * and the document writers' own refusals use: {@code GlobalExceptionHandler} already maps that class to
 * {@code 400} with the message as the body, so no handler method and no new status is invented here. The
 * sentence names the code, the region and the document's date — the three facts whoever states the rate needs
 * to fix it — and the caller resolves the line before its first write, so a refused line stores nothing.</p>
 */
public class TaxRateRegionGapException extends IllegalArgumentException {

    public TaxRateRegionGapException(
            String taxCode, UUID companyRegionId, String companyRegionName, LocalDate documentDate) {
        super("Cannot write the line: tax code " + taxCode + " has rates stated for "
                + label(companyRegionId, companyRegionName) + " but none of them applies on " + documentDate
                + ", the document's date, so the line is refused instead of being charged the company-wide rate of a"
                + " place the document was not sold in (ADR-0002 decision 6). State the rate that applies from that"
                + " date onward for that region, or retire the region's rows for " + taxCode + ".");
    }

    /** The region as a person can act on: its name beside its id, which is what a caller can look up. */
    private static String label(UUID companyRegionId, String companyRegionName) {
        return companyRegionName == null || companyRegionName.isBlank()
                ? "region " + companyRegionId
                : "region '" + companyRegionName + "' (" + companyRegionId + ")";
    }
}
