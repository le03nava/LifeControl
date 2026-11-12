package com.lifecontrol.api.tax.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.tax.dto.TaxRateRequest;
import com.lifecontrol.api.tax.dto.TaxRateResponse;
import com.lifecontrol.api.tax.dto.TaxRateSupersedeRequest;
import com.lifecontrol.api.tax.exception.DuplicateTaxRateException;
import com.lifecontrol.api.tax.exception.InvalidTaxRateSupersessionException;
import com.lifecontrol.api.tax.exception.TaxCodeNotFoundException;
import com.lifecontrol.api.tax.exception.TaxRateNotFoundException;
import com.lifecontrol.api.tax.model.TaxRate;
import com.lifecontrol.api.tax.repository.TaxCodeRepository;
import com.lifecontrol.api.tax.repository.TaxRateRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The server side of ADR-0002 decision 6: «The rate is resolved server-side from the latest {@code tax_rate}
 * row with {@code effective_from ≤ the document's date} for the company and tax code, and is copied onto the
 * line at write time, so a rate change never rewrites history.»
 *
 * <p><b>The two halves.</b> {@link #resolve} is the read the document writers make, and it has no counterpart
 * that writes: before issue #858 the table had <b>no writer in any layer</b>, so a freshly provisioned company
 * could not state a single VAT rate and every taxable sale it attempted was refused by a correct rule with
 * nothing to satisfy it. {@link #declareTaxRate} and {@link #supersedeTaxRate} are that counterpart, and they
 * can only <b>append</b>: {@code rate}, {@code tax_code} and {@code effective_from} are {@code updatable =
 * false} on the entity, so the ADR's "never rewrites history" is a property of the type rather than a
 * convention a caller is trusted with.</p>
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

    private static final Logger logger = LoggerFactory.getLogger(TaxRateService.class);

    private final TaxRateRepository taxRateRepository;
    private final TaxCodeRepository taxCodeRepository;
    private final CompanyRepository companyRepository;
    private final CurrentUserContext currentUserContext;

    public TaxRateService(
            TaxRateRepository taxRateRepository,
            TaxCodeRepository taxCodeRepository,
            CompanyRepository companyRepository,
            CurrentUserContext currentUserContext) {
        this.taxRateRepository = taxRateRepository;
        this.taxCodeRepository = taxCodeRepository;
        this.companyRepository = companyRepository;
        this.currentUserContext = currentUserContext;
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
     * A company's rates, newest first within each code.
     *
     * <p><b>Not cached, deliberately.</b> The two global catalogs that are cached ({@code taxCodes},
     * {@code currencies}) cannot be changed by the application; a company's rates can, and a
     * {@code @Cacheable} here would also bypass the access check below — the same reason
     * {@code DepartmentService} states for its own read.</p>
     *
     * @param includeDisabled {@code true} to serve retired rates too
     */
    @Transactional(readOnly = true)
    public List<TaxRateResponse> getTaxRates(UUID companyId, boolean includeDisabled) {
        resolveCompany(companyId);

        var rates = includeDisabled
                ? taxRateRepository.findByCompanyIdOrderByTaxCodeAscEffectiveFromDesc(companyId)
                : taxRateRepository.findByCompanyIdAndEnabledTrueOrderByTaxCodeAscEffectiveFromDesc(companyId);
        return rates.stream().map(TaxRateService::toResponse).toList();
    }

    /** One of the company's rates; a row of another company is this same not-found. */
    @Transactional(readOnly = true)
    public TaxRateResponse getTaxRate(UUID companyId, UUID id) {
        resolveCompany(companyId);
        return taxRateRepository
                .findByCompanyIdAndId(companyId, id)
                .map(TaxRateService::toResponse)
                .orElseThrow(() -> new TaxRateNotFoundException(id));
    }

    /**
     * Declares a rate, appending one row.
     *
     * @throws TaxCodeNotFoundException when the catalog does not declare the code
     * @throws DuplicateTaxRateException when the company already states a rate for that code from that day
     */
    @Transactional
    public TaxRateResponse declareTaxRate(UUID companyId, TaxRateRequest request) {
        resolveCompany(companyId);
        requireCatalogCode(request.taxCode());
        requireNoRateForTheSameDay(companyId, request.taxCode(), request.effectiveFrom());

        var declared = taxRateRepository.save(TaxRate.declare(
                companyId, request.taxCode(), request.rate(), request.effectiveFrom(), request.enabled()));

        logger.info(
                "Tax rate declared: id={}, companyId={}, taxCode={}, rate={}, effectiveFrom={}",
                declared.getId(),
                companyId,
                declared.getTaxCode(),
                declared.getRate(),
                declared.getEffectiveFrom());
        return toResponse(declared);
    }

    /**
     * Supersedes the rate named by {@code id} with a new row for the same tax code, effective from a later day.
     *
     * <p><b>The named row is not touched.</b> Nothing is written to it — not its rate, not its date, not even its
     * {@code enabled} flag: an earlier {@code effective_from} is shadowed for later documents by construction
     * (the resolution takes the latest one that is not after the date), and disabling it would erase the rate a
     * document dated last month must keep resolving. That is the whole of S2.</p>
     *
     * @throws TaxRateNotFoundException when the named row is not one of this company's
     * @throws InvalidTaxRateSupersessionException when the successor would not take effect after the row it
     *     supersedes
     * @throws DuplicateTaxRateException when the company already states a rate for that code from that day
     */
    @Transactional
    public TaxRateResponse supersedeTaxRate(UUID companyId, UUID id, TaxRateSupersedeRequest request) {
        resolveCompany(companyId);

        var superseded = taxRateRepository
                .findByCompanyIdAndId(companyId, id)
                .orElseThrow(() -> new TaxRateNotFoundException(id));

        if (!request.effectiveFrom().isAfter(superseded.getEffectiveFrom())) {
            throw new InvalidTaxRateSupersessionException(superseded.getEffectiveFrom(), request.effectiveFrom());
        }
        requireNoRateForTheSameDay(companyId, superseded.getTaxCode(), request.effectiveFrom());

        var successor = taxRateRepository.save(TaxRate.declare(
                companyId, superseded.getTaxCode(), request.rate(), request.effectiveFrom(), request.enabled()));

        logger.info(
                "Tax rate superseded: supersededId={}, successorId={}, companyId={}, taxCode={}, rate={}, "
                        + "effectiveFrom={}",
                superseded.getId(),
                successor.getId(),
                companyId,
                successor.getTaxCode(),
                successor.getRate(),
                successor.getEffectiveFrom());
        return toResponse(successor);
    }

    /**
     * Retires or reinstates a rate.
     *
     * <p>The one in-place write the table allows, and it exists because the resolver already depends on it: a
     * retired row keeps its place in history while the resolution steps over it, which without this endpoint
     * would be a column no caller could ever set — a control that behaves like a comment.</p>
     */
    @Transactional
    public TaxRateResponse setTaxRateEnabled(UUID companyId, UUID id, boolean enabled) {
        resolveCompany(companyId);

        var rate = taxRateRepository
                .findByCompanyIdAndId(companyId, id)
                .orElseThrow(() -> new TaxRateNotFoundException(id));
        rate.setEnabled(enabled);
        var saved = taxRateRepository.save(rate);

        logger.info(
                "Tax rate {}: id={}, companyId={}, taxCode={}, effectiveFrom={}",
                enabled ? "enabled" : "retired",
                saved.getId(),
                companyId,
                saved.getTaxCode(),
                saved.getEffectiveFrom());
        return toResponse(saved);
    }

    /**
     * Verifies company access and then loads the company, in that order — the order {@code DepartmentService}
     * states for the same check: a caller without access is denied before any row is loaded.
     */
    private void resolveCompany(UUID companyId) {
        currentUserContext.verifyCompanyAccess(companyId);
        companyRepository.findById(companyId).orElseThrow(() -> new CompanyNotFoundException(companyId));
    }

    /** Refuses a code the platform catalog does not declare, naming it. */
    private void requireCatalogCode(String taxCode) {
        if (!taxCodeRepository.existsById(taxCode)) {
            throw new TaxCodeNotFoundException(taxCode);
        }
    }

    /** Refuses a second row for the same {@code (company, code, day)}, which the schema also refuses. */
    private void requireNoRateForTheSameDay(UUID companyId, String taxCode, LocalDate effectiveFrom) {
        if (taxRateRepository.existsByCompanyIdAndTaxCodeAndEffectiveFrom(companyId, taxCode, effectiveFrom)) {
            throw new DuplicateTaxRateException(taxCode, effectiveFrom);
        }
    }

    private static TaxRateResponse toResponse(TaxRate rate) {
        return new TaxRateResponse(
                rate.getId(),
                rate.getCompanyId(),
                rate.getTaxCode(),
                rate.getRate(),
                rate.getEffectiveFrom(),
                rate.getEnabled());
    }
}
