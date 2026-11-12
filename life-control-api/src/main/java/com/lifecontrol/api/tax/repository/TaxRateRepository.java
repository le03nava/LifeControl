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
 */
@Repository
public interface TaxRateRepository extends JpaRepository<TaxRate, UUID> {

    /**
     * Every rate of a company, enabled or not, ordered by code and then newest first.
     *
     * <p>The order is part of the answer, not a presentation detail: a caller reading a code's history sees the
     * rate that applies today above the one it replaced. No separate index is needed —
     * {@code uq_tax_rate_company_code_from} already indexes the {@code (company_id, tax_code)} prefix, as
     * {@code V82:74} states.</p>
     */
    List<TaxRate> findByCompanyIdOrderByTaxCodeAscEffectiveFromDesc(UUID companyId);

    /** The enabled rates of a company, in the same order as {@link #findByCompanyIdOrderByTaxCodeAscEffectiveFromDesc}. */
    List<TaxRate> findByCompanyIdAndEnabledTrueOrderByTaxCodeAscEffectiveFromDesc(UUID companyId);

    /**
     * One rate, looked up through the company so a row of another tenant is not addressable.
     *
     * <p>The company predicate is redundant with the policy ({@code tax_rate} is armed with {@code FORCE} row-level
     * security) and is kept anyway: the two are independent statements of the same boundary, and the one written
     * here is the one a caller without a stated scope — a scheduled job, a unit test — still meets.</p>
     */
    Optional<TaxRate> findByCompanyIdAndId(UUID companyId, UUID id);

    /** Whether this company already states a rate for that code effective from that day. */
    boolean existsByCompanyIdAndTaxCodeAndEffectiveFrom(UUID companyId, String taxCode, LocalDate effectiveFrom);

    /**
     * The rate that applies to a document dated {@code documentDate}: the latest {@code effective_from} that
     * is not after that date, among the ones that are still enabled.
     *
     * <p><b>Why a derived query and not a {@code @Query}.</b> Every part of the predicate is a column of this
     * entity — company, code, {@code enabled}, {@code effective_from} — so the method name states the rule
     * and Spring Data derives it; the same reason {@code CurrencyRepository.findByEnabledTrue()} is derived.
     * {@code findFirst…OrderBy…Desc} is what makes "latest wins" a property of the query instead of a sort in
     * a caller that could forget it.</p>
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
    Optional<TaxRate> findFirstByCompanyIdAndTaxCodeAndEnabledTrueAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
            UUID companyId, String taxCode, LocalDate documentDate);
}
