package com.lifecontrol.api.tax.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.exception.CompanyRegionNotFoundException;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.repository.CompanyRegionRepository;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.tax.dto.TaxRateRequest;
import com.lifecontrol.api.tax.dto.TaxRateResponse;
import com.lifecontrol.api.tax.dto.TaxRateSupersedeRequest;
import com.lifecontrol.api.tax.exception.DuplicateTaxRateException;
import com.lifecontrol.api.tax.exception.InvalidTaxRateSupersessionException;
import com.lifecontrol.api.tax.exception.TaxCodeNotFoundException;
import com.lifecontrol.api.tax.exception.TaxRateNotFoundException;
import com.lifecontrol.api.tax.exception.TaxRateRegionGapException;
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
 * can only <b>append</b>: {@code rate}, {@code tax_code}, {@code company_region_id} and {@code effective_from}
 * are {@code updatable = false} on the entity, so the ADR's "never rewrites history" is a property of the type
 * rather than a convention a caller is trusted with.</p>
 *
 * <p><b>Why the date is a parameter and never {@code LocalDate.now()}.</b> The sentence says the
 * <i>document's</i> date. Reading the clock here would make a document entered today carry today's rate even
 * when the document is dated last month, and it would make every test depend on when it runs. The caller
 * passes the date the document states, which is also the date printed on the ticket.</p>
 *
 * <p><b>Why the region is a parameter too, and what a {@code null} one means</b> (issue #855, {@code D1} and
 * {@code D2}). The frontier's IVA is 8% and the rest of the country's is 16% <b>for the same code</b>, so the
 * code cannot select between the two rates and the place has to. The place is the document's store's region —
 * {@code company_stores.company_zone_id} &rarr; {@code company_zones.company_region_id}, a chain both
 * documents already carry — and a {@code null} region is a caller that knows none, which resolves exactly as
 * before. {@link #resolve} states the three steps and why the middle one refuses.</p>
 *
 * <p><b>Why an empty answer is not zero tax.</b> {@link Optional#empty()} means "no scope states an enabled
 * rate for that code on or before that date", which is a fact about the configuration and not a statement that
 * the line is untaxed. A caller that wants 0% has to read a code that says so ({@code TASA_0}, {@code EXENTO})
 * and a rate row of {@code 0}. Turning a missing rate into a silent zero is the failure mode the catalog
 * exists to prevent.</p>
 */
@Service
public class TaxRateService {

    private static final Logger logger = LoggerFactory.getLogger(TaxRateService.class);

    private final TaxRateRepository taxRateRepository;
    private final TaxCodeRepository taxCodeRepository;
    private final CompanyRepository companyRepository;
    private final CompanyRegionRepository companyRegionRepository;
    private final CurrentUserContext currentUserContext;

    public TaxRateService(
            TaxRateRepository taxRateRepository,
            TaxCodeRepository taxCodeRepository,
            CompanyRepository companyRepository,
            CompanyRegionRepository companyRegionRepository,
            CurrentUserContext currentUserContext) {
        this.taxRateRepository = taxRateRepository;
        this.taxCodeRepository = taxCodeRepository;
        this.companyRepository = companyRepository;
        this.companyRegionRepository = companyRegionRepository;
        this.currentUserContext = currentUserContext;
    }

    /**
     * The rate that applies to a document of this company, code, place and date.
     *
     * <p>The three steps, in this order, and they are one rule rather than three guesses:</p>
     *
     * <ol>
     *   <li><b>The region answers first.</b> When the caller knows the region, the latest enabled row for
     *       <b>that region</b> whose {@code effective_from} is not after the document's date is the answer. A
     *       company-wide row never competes with it — that is the silent 16% in the frontier.</li>
     *   <li><b>A region that states rates and has none for this day refuses the line.</b> If the region has
     *       <b>any</b> row for that code — enabled or not, of any date — it is authoritative for that code, so a
     *       missing applicable day is {@link TaxRateRegionGapException} naming the code, the region and the
     *       date. Falling through to step 3 here would answer the company-wide rate, which in the frontier case
     *       is precisely the amount that must not be charged; refusing without the «has rows» check would refuse
     *       every line in a tree where no region has rows yet ({@code D2}).</li>
     *   <li><b>Otherwise, today's behaviour.</b> No region rows for that code, or no region at all: the latest
     *       enabled <b>company-wide</b> row ({@code company_region_id IS NULL}) on or before the document's
     *       date, as {@link Optional} so both callers keep turning empty into their own 400 ({@code S3}).</li>
     * </ol>
     *
     * @param companyId the company that files the rate
     * @param taxCode the catalog code the line names, e.g. {@code IVA}
     * @param companyRegionId the region of the document's store, or {@code null} when the caller knows none —
     *     genuinely optional, unlike the other three, because a company-wide rate needs no place
     * @param documentDate the date the document itself states — never "today"
     * @return the applying rate, or empty when neither the region nor the company states one for that code on or
     *     before that date
     * @throws TaxRateRegionGapException (400) when the region states rates for the code and none applies on the
     *     document's date
     */
    @Transactional(readOnly = true)
    public Optional<TaxRateResolution> resolve(
            UUID companyId, String taxCode, UUID companyRegionId, LocalDate documentDate) {
        Objects.requireNonNull(companyId, "companyId is required to resolve a tax rate");
        Objects.requireNonNull(taxCode, "taxCode is required to resolve a tax rate");
        Objects.requireNonNull(documentDate, "documentDate is required: a rate is resolved for a date");

        if (companyRegionId != null) {
            var regional = taxRateRepository
                    .findFirstByCompanyIdAndTaxCodeAndCompanyRegionIdAndEnabledTrueAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                            companyId, taxCode, companyRegionId, documentDate);
            if (regional.isPresent()) {
                return regional.map(TaxRateService::toResolution);
            }
            // Step 2: the region's silence about this day is a refusal only when the region has spoken about
            // this code at all. The check ignores `enabled` and the date on purpose — a region whose only rows
            // are retired, or all of them dated after this document, has still stated that this code is its own.
            if (taxRateRepository.existsByCompanyIdAndTaxCodeAndCompanyRegionId(companyId, taxCode, companyRegionId)) {
                throw new TaxRateRegionGapException(
                        taxCode, companyRegionId, regionNameOf(companyRegionId), documentDate);
            }
        }

        return taxRateRepository
                .findFirstByCompanyIdAndTaxCodeAndCompanyRegionIsNullAndEnabledTrueAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                        companyId, taxCode, documentDate)
                .map(TaxRateService::toResolution);
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
     * Declares a rate, appending one row in the scope the request names.
     *
     * <p>{@code request.regionId()} chooses the scope: a region of this company, or nothing at all for the
     * company-wide rate. Two rates for one day are legal exactly when their scopes differ ({@code V95}'s two
     * partial unique indexes), so the day is checked <b>within</b> the scope the caller stated.</p>
     *
     * @throws TaxCodeNotFoundException when the catalog does not declare the code
     * @throws CompanyRegionNotFoundException when the region does not exist or belongs to another company
     * @throws DuplicateTaxRateException when that scope already states a rate for that code from that day
     */
    @Transactional
    public TaxRateResponse declareTaxRate(UUID companyId, TaxRateRequest request) {
        resolveCompany(companyId);
        requireCatalogCode(request.taxCode());
        var region = resolveRegionOrNull(companyId, request.regionId());
        requireNoRateForTheSameDay(companyId, request.taxCode(), region, request.effectiveFrom());

        var declared = taxRateRepository.save(TaxRate.declare(
                companyId, request.taxCode(), region, request.rate(), request.effectiveFrom(), request.enabled()));

        logger.info(
                "Tax rate declared: id={}, companyId={}, taxCode={}, companyRegionId={}, rate={}, effectiveFrom={}",
                declared.getId(),
                companyId,
                declared.getTaxCode(),
                region == null ? null : region.getId(),
                declared.getRate(),
                declared.getEffectiveFrom());
        return toResponse(declared);
    }

    /**
     * Supersedes the rate named by {@code id} with a new row for the same tax code and <b>the same scope</b>,
     * effective from a later day.
     *
     * <p><b>The named row is not touched.</b> Nothing is written to it — not its rate, not its date, not even its
     * {@code enabled} flag: an earlier {@code effective_from} is shadowed for later documents by construction
     * (the resolution takes the latest one that is not after the date), and disabling it would erase the rate a
     * document dated last month must keep resolving. That is the whole of S2.</p>
     *
     * <p><b>Why the scope is inherited and cannot be stated.</b> A successor is the next statement of the row it
     * supersedes, so it inherits that row's region: a company-wide supersession must not swallow a region's rows
     * (the successor would be shadowed again on the next day the region states), and a region's supersession must
     * not create a company-wide row (it would then apply where the superseded rate never did). The request
     * therefore carries no region — the same reason it carries no {@code taxCode} — and both the duplicate check
     * and the appended row use {@code superseded.getCompanyRegion()}.</p>
     *
     * @throws TaxRateNotFoundException when the named row is not one of this company's
     * @throws InvalidTaxRateSupersessionException when the successor would not take effect after the row it
     *     supersedes
     * @throws DuplicateTaxRateException when that scope already states a rate for that code from that day
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
        var region = superseded.getCompanyRegion();
        requireNoRateForTheSameDay(companyId, superseded.getTaxCode(), region, request.effectiveFrom());

        var successor = taxRateRepository.save(TaxRate.declare(
                companyId,
                superseded.getTaxCode(),
                region,
                request.rate(),
                request.effectiveFrom(),
                request.enabled()));

        logger.info(
                "Tax rate superseded: supersededId={}, successorId={}, companyId={}, taxCode={}, companyRegionId={}, "
                        + "rate={}, effectiveFrom={}",
                superseded.getId(),
                successor.getId(),
                companyId,
                successor.getTaxCode(),
                region == null ? null : region.getId(),
                successor.getRate(),
                successor.getEffectiveFrom());
        return toResponse(successor);
    }

    /**
     * Retires or reinstates a rate.
     *
     * <p>The one in-place write the table allows, and it exists because the resolver already depends on it: a
     * retired row keeps its place in history while the resolution steps over it, which without this endpoint
     * would be a column no caller could ever set — a control that behaves like a comment. It is also the switch
     * that opens and closes a region's authority: a region whose rows for a code are all retired still counts as
     * having stated that code, so the line refuses instead of falling back to the company-wide rate.</p>
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
     * Resolves the region a write names, or {@code null} for the company-wide rate.
     *
     * <p><b>Why the region has to be one of this company's</b> ({@code S7}). A role only says what a caller may
     * do; the row says which company's geography it may do it in. An id of another company's region is refused
     * as a not-found — the same answer the region's own resource gives, and the answer the policy would give
     * anyway, since {@code company_regions} is armed — because storing it would make the resolver filter by a
     * place this company does not have, and the rate would apply nowhere while looking applied.</p>
     *
     * <p><b>Why the owner is read through the country.</b> That is the chain
     * {@code EmployeeScopeAssignmentService} walks for the same question, and it is the chain the write is about:
     * a region's company is the company of the country that holds it, derived by {@code V50}'s trigger. Reading
     * {@code region.getCompany()} would be a second statement of the same boundary, and the two are only equal
     * by the trigger's grace.</p>
     */
    private CompanyRegion resolveRegionOrNull(UUID companyId, UUID regionId) {
        if (regionId == null) {
            return null;
        }
        var region = companyRegionRepository
                .findById(regionId)
                .orElseThrow(() -> new CompanyRegionNotFoundException("Company region not found with id: " + regionId));
        var companyCountry = region.getCompanyCountry();
        if (companyCountry == null
                || companyCountry.getCompany() == null
                || !companyCountry.getCompany().getId().equals(companyId)) {
            throw new CompanyRegionNotFoundException("Company region not found with id: " + regionId);
        }
        return region;
    }

    /**
     * The region's name, for a refusal sentence, or {@code null} when it cannot be read.
     *
     * <p>The one place the resolver costs a second query, and only on the path that refuses: naming the region
     * by its id alone would be a 400 an operator cannot act on without a second lookup of their own. A region
     * the scope cannot read answers {@code null} rather than failing — the refusal is already the answer, and
     * turning a readable-id refusal into a 500 would hide it.</p>
     */
    private String regionNameOf(UUID companyRegionId) {
        return companyRegionRepository
                .findById(companyRegionId)
                .map(CompanyRegion::getRegionName)
                .orElse(null);
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

    /**
     * Refuses a second row for the same {@code (company, code, day)} <b>in the same scope</b>, which the schema
     * also refuses.
     *
     * <p>The scope is the half of the key that makes two rates for one day legal ({@code V95}), so it is the
     * half the check has to name: asking the day alone would refuse the frontier's 8% because the country's 16%
     * holds that day, which is exactly the row the feature exists to allow. The refusal names the scope that
     * holds it, because that is what the caller has to change.</p>
     */
    private void requireNoRateForTheSameDay(
            UUID companyId, String taxCode, CompanyRegion region, LocalDate effectiveFrom) {
        var taken = region == null
                ? taxRateRepository.existsByCompanyIdAndTaxCodeAndCompanyRegionIsNullAndEffectiveFrom(
                        companyId, taxCode, effectiveFrom)
                : taxRateRepository.existsByCompanyIdAndTaxCodeAndCompanyRegionIdAndEffectiveFrom(
                        companyId, taxCode, region.getId(), effectiveFrom);
        if (taken) {
            throw new DuplicateTaxRateException(
                    taxCode,
                    region == null ? null : region.getId(),
                    region == null ? null : region.getRegionName(),
                    effectiveFrom);
        }
    }

    private static TaxRateResolution toResolution(TaxRate rate) {
        return new TaxRateResolution(rate.getId(), rate.getTaxCode(), rate.getRate(), rate.getEffectiveFrom());
    }

    private static TaxRateResponse toResponse(TaxRate rate) {
        var region = rate.getCompanyRegion();
        return new TaxRateResponse(
                rate.getId(),
                rate.getCompanyId(),
                rate.getTaxCode(),
                rate.getRate(),
                rate.getEffectiveFrom(),
                rate.getEnabled(),
                region == null ? null : region.getId(),
                region == null ? null : region.getRegionName());
    }
}
