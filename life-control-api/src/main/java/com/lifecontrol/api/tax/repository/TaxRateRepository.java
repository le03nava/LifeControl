package com.lifecontrol.api.tax.repository;

import com.lifecontrol.api.tax.model.TaxRate;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Reads a company's tax rates and appends new ones. A rate is never rewritten: correcting one means appending
 * the next {@code effective_from}, which is what {@code POST} and {@code PUT} on
 * {@code /api/companies/{companyId}/tax-rates} do (issue #858).
 *
 * <p><b>Every read that answers a rate names a scope</b>, and the scope is one of two: a region
 * ({@code company_region_id = the store's region}) or the company-wide rows ({@code company_region_id IS
 * NULL}). Since {@code V95} the table holds both, so a query that named only the company and the code would
 * mix two places' answers — the defect issue #855 exists to remove. The pair of partial unique indexes that
 * migration installs keeps each scope's {@code (company_id, tax_code)} prefix indexed, so naming the scope is
 * also what keeps these reads off the other scope's rows.</p>
 */
@Repository
public interface TaxRateRepository extends JpaRepository<TaxRate, UUID> {

    /**
     * Every rate of a company, enabled or not, ordered by code and then newest first.
     *
     * <p>The order is part of the answer, not a presentation detail: a caller reading a code's history sees the
     * rate that applies today above the one it replaced. Both scopes are served together on purpose — this is
     * the ABM's read, which has to show the region rows beside the company-wide ones, and each row states its
     * region. No separate index is needed — {@code uq_tax_rate_scope_from} and
     * {@code uq_tax_rate_region_scope_from} both keep the {@code (company_id, tax_code)} prefix, as
     * {@code V82:74} and {@code V95} state.</p>
     */
    List<TaxRate> findByCompanyIdOrderByTaxCodeAscEffectiveFromDesc(UUID companyId);

    /** The enabled rates of a company, in the same order as {@link #findByCompanyIdOrderByTaxCodeAscEffectiveFromDesc}. */
    List<TaxRate> findByCompanyIdAndEnabledTrueOrderByTaxCodeAscEffectiveFromDesc(UUID companyId);

    /**
     * Whether this company already states a rate for that code and day <b>in that region</b>, enabled or not.
     *
     * <p>The same-day duplicate check's query, and the second of the pair below: two rows for one day are
     * legal only when their scopes differ, so the check names the scope instead of the day alone. {@code
     * enabled} is deliberately absent — a retired row still occupies its day, and the insert would collide
     * with it in {@code uq_tax_rate_region_scope_from} anyway; asking here is what turns that collision into
     * a sentence naming which scope holds the day.</p>
     */
    boolean existsByCompanyIdAndTaxCodeAndCompanyRegionIdAndEffectiveFrom(
            UUID companyId, String taxCode, UUID companyRegionId, LocalDate effectiveFrom);

    /** The same day in the <b>company-wide</b> scope, where the region column is absent from the identity. */
    boolean existsByCompanyIdAndTaxCodeAndCompanyRegionIsNullAndEffectiveFrom(
            UUID companyId, String taxCode, LocalDate effectiveFrom);

    /**
     * One rate, looked up through the company so a row of another tenant is not addressable.
     *
     * <p>The company predicate is redundant with the policy ({@code tax_rate} is armed with {@code FORCE} row-level
     * security) and is kept anyway: the two are independent statements of the same boundary, and the one written
     * here is the one a caller without a stated scope — a scheduled job, a unit test — still meets.</p>
     */
    Optional<TaxRate> findByCompanyIdAndId(UUID companyId, UUID id);

    /**
     * The rate that applies to a document dated {@code documentDate} in a <b>region</b>: the latest
     * {@code effective_from} that is not after that date, among the ones that are still enabled.
     *
     * <p>Step 1 of the resolution {@code TaxRateService#resolve} states. The region is part of the predicate
     * and not a filter applied afterwards: a company-wide row must never answer a region's question, which is
     * the silent 16% in the frontier that {@code D2} refuses.</p>
     *
     * <p>A derived query and not a {@code @Query}, for the reason stated on
     * {@link #findFirstByCompanyIdAndTaxCodeAndCompanyRegionIsNullAndEnabledTrueAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc}.</p>
     */
    Optional<TaxRate>
            findFirstByCompanyIdAndTaxCodeAndCompanyRegionIdAndEnabledTrueAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                    UUID companyId, String taxCode, UUID companyRegionId, LocalDate documentDate);

    /**
     * Whether this company has stated <b>any</b> rate for that code in that region — enabled or not, of any
     * date.
     *
     * <p>Step 2 of the resolution, and the one question that separates a gap from a fallback: a region that
     * has rows for a code is authoritative for it, so a <i>missing applicable day</i> there is a refusal
     * naming the region, while a region with <b>no rows at all</b> resolves from the company-wide ones
     * ({@code D2}). {@code enabled} and {@code effective_from} are deliberately absent: a region whose only
     * rows are retired has still stated its position on that code, and answering it with another
     * jurisdiction's rate is the failure this check exists to prevent.</p>
     */
    boolean existsByCompanyIdAndTaxCodeAndCompanyRegionId(UUID companyId, String taxCode, UUID companyRegionId);

    /**
     * The company-wide rate that applies to a document dated {@code documentDate}: the latest
     * {@code effective_from} that is not after that date, among the rows that are enabled <b>and belong to no
     * region</b>.
     *
     * <p><b>Why {@code companyRegionIsNull} is part of it.</b> Since {@code V95} a region row carries the same
     * company, the same code and its own {@code effective_from}, so a query without this clause would answer a
     * company-wide question with a region's rate — two stores taxed at whichever region published last. The
     * clause is what keeps "company-wide" meaning what it always meant.</p>
     *
     * <p><b>Why a derived query and not a {@code @Query}.</b> Every part of the predicate is a column of this
     * entity — company, code, the absence of a region, {@code enabled}, {@code effective_from} — so the method
     * name states the rule and Spring Data derives it; the same reason {@code CurrencyRepository
     * .findByEnabledTrue()} is derived. {@code findFirst…OrderBy…Desc} is what makes "latest wins" a property
     * of the query instead of a sort in a caller that could forget it.</p>
     *
     * <p><b>Why {@code enabled} is part of it.</b> ADR-0002 decision 6 names {@code effective_from} and says
     * nothing about {@code enabled}; retiring a rate is the one thing that column can mean, and it only means
     * it if the resolver steps over the retired row and answers with the previous enabled one. Without this
     * clause {@code enabled = false} would change nothing about what a new line is charged, which is worse
     * than not having the column: it would look like a control and behave like a comment.</p>
     *
     * <p>The caller is expected to have stated the tenant — the table is armed with {@code FORCE} row-level
     * security, so a call without a scope reads no row and answers {@link Optional#empty()} rather than
     * another company's rate.</p>
     *
     * @param companyId the company filing the rate
     * @param taxCode the catalog code the rate is for, e.g. {@code IVA}
     * @param documentDate the document's own date, never "today"
     */
    Optional<TaxRate>
            findFirstByCompanyIdAndTaxCodeAndCompanyRegionIsNullAndEnabledTrueAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                    UUID companyId, String taxCode, LocalDate documentDate);
}
