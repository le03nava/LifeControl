package com.lifecontrol.api.tax.service;

import com.lifecontrol.api.tax.repository.TaxRateRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The server side of ADR-0002 decision 6: «The rate is resolved server-side from the latest {@code tax_rate}
 * row with {@code effective_from ≤ the document's date} for the company and tax code, and is copied onto the
 * line at write time, so a rate change never rewrites history.»
 *
 * <p><b>Why the date is a parameter and never {@code LocalDate.now()}.</b> The sentence says the
 * <i>document's</i> date. Reading the clock here would make a document entered today carry today's rate even
 * when the document is dated last month, and it would make every test depend on when it runs. The caller
 * passes the date the document states, which is also the date printed on the ticket.</p>
 *
 * <p><b>Why an empty answer is not zero tax.</b> {@link Optional#empty()} means "this company has stated no
 * enabled rate for that code on or before that date", which is a fact about the configuration and not a
 * statement that the line is untaxed. A caller that wants 0% has to read a code that says so
 * ({@code TASA_0}, {@code EXENTO}) and a rate row of {@code 0}. Turning a missing rate into a silent zero is
 * the failure mode the catalog exists to prevent.</p>
 */
@Service
public class TaxRateService {

    /** The scale of every money column on a sales document, and of {@code ck_item_tax}'s right-hand side. */
    private static final int MONEY_SCALE = 4;

    private final TaxRateRepository taxRateRepository;

    public TaxRateService(TaxRateRepository taxRateRepository) {
        this.taxRateRepository = taxRateRepository;
    }

    /**
     * The rate that applies to a document of this company, code and date.
     *
     * @param companyId the company that files the rate
     * @param taxCode the catalog code the line names, e.g. {@code IVA}
     * @param documentDate the date the document itself states — never "today"
     * @return the applying rate, or empty when the company has stated none for that code on or before that
     *     date
     */
    @Transactional(readOnly = true)
    public Optional<TaxRateResolution> resolve(UUID companyId, String taxCode, LocalDate documentDate) {
        Objects.requireNonNull(companyId, "companyId is required to resolve a tax rate");
        Objects.requireNonNull(taxCode, "taxCode is required to resolve a tax rate");
        Objects.requireNonNull(documentDate, "documentDate is required: a rate is resolved for a date");

        return taxRateRepository
                .findFirstByCompanyIdAndTaxCodeAndEnabledTrueAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                        companyId, taxCode, documentDate)
                .map(rate -> new TaxRateResolution(
                        rate.getId(), rate.getTaxCode(), rate.getRate(), rate.getEffectiveFrom()));
    }

    /**
     * The line's tax amount, computed the way the schema computes it: {@code V81:170-171}'s
     * {@code ck_item_tax} states
     * {@code tax_amount = round((subtotal - discount_applied) * tax_rate, 4)}, and this method is that
     * expression reachable from Java.
     *
     * <p><b>Why the base is {@code subtotal - discount_applied} and not {@code final_price}.</b>
     * {@code final_price} is the <i>unit</i> net price on a legacy line (the measurement D-f records), so
     * taxing it would multiply the tax by the quantity twice or not at all. The base is the line's money after
     * its discount, which is exactly what the constraint subtracts.</p>
     *
     * <p><b>Why {@link RoundingMode#HALF_UP}.</b> PostgreSQL's {@code round(numeric, int)} rounds half away
     * from zero, which is {@code HALF_UP} for the positives a line's money can carry; {@code HALF_EVEN} would
     * disagree with the schema on exactly the half cases and the insert would be refused by the check that
     * this method is mirroring.</p>
     *
     * @param subtotal the line's money before its discount, as {@code sales_order_items.subtotal} holds it
     * @param discountApplied the line's discount, never {@code null} since {@code V81}
     * @param rate the resolved rate
     * @return the tax amount at the scale every money column uses
     */
    public static BigDecimal lineTaxAmount(BigDecimal subtotal, BigDecimal discountApplied, BigDecimal rate) {
        Objects.requireNonNull(subtotal, "subtotal is required");
        Objects.requireNonNull(discountApplied, "discountApplied is required");
        Objects.requireNonNull(rate, "rate is required");

        return subtotal.subtract(discountApplied).multiply(rate).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
