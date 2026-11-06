package com.lifecontrol.api.tax.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.tax.model.TaxRate;
import com.lifecontrol.api.tax.repository.TaxRateRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link TaxRateService}: the contract a line's tax is computed from (ADR-0002 decision 6).
 *
 * <p>What the database owns — the policy, the FK, the unique key, the bounds of {@code rate} — is
 * {@code TaxRateMigrationIntegrationTest}'s; what is pinned here is the part the schema cannot state: that the
 * date handed to the query is the <b>document's</b> and never the clock, that "no rate" stays empty instead
 * of becoming a silent zero, and that {@link TaxRateService#lineTaxAmount} rounds the way
 * {@code ck_item_tax} rounds, because a disagreement between the two is a refused insert.</p>
 */
@DisplayName("TaxRateService: the document's date decides, and no rate is not zero tax")
class TaxRateServiceTest {

    private static final UUID COMPANY = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RATE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final LocalDate DOCUMENT_DATE = LocalDate.of(2026, 3, 15);

    private TaxRateRepository taxRateRepository;
    private TaxRateService taxRateService;

    @BeforeEach
    void setUp() {
        taxRateRepository = mock(TaxRateRepository.class);
        taxRateService = new TaxRateService(taxRateRepository);
    }

    @Test
    @DisplayName("the resolution carries the row's four facts, so a line can snapshot all of them")
    void mapsTheRowToTheResolution() {
        // The fixture is built before the stubbing, never inside `thenReturn(...)`: a nested `when(...)`
        // is an UnfinishedStubbing error in Mockito, and the failure names the helper, not the mistake.
        var row = rate("IVA", "0.160000", LocalDate.of(2026, 1, 1));
        when(taxRateRepository
                        .findFirstByCompanyIdAndTaxCodeAndEnabledTrueAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                                COMPANY, "IVA", DOCUMENT_DATE))
                .thenReturn(Optional.of(row));

        var resolution = taxRateService.resolve(COMPANY, "IVA", DOCUMENT_DATE);

        assertThat(resolution).hasValueSatisfying(r -> {
            assertThat(r.taxRateId()).isEqualTo(RATE_ID);
            assertThat(r.taxCode()).isEqualTo("IVA");
            assertThat(r.rate()).isEqualByComparingTo("0.160000");
            assertThat(r.effectiveFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
        });
    }

    @Test
    @DisplayName("the query receives the document's date, never the day the request happens to run")
    void passesTheDocumentDateThrough() {
        when(taxRateRepository
                        .findFirstByCompanyIdAndTaxCodeAndEnabledTrueAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                                any(), any(), any()))
                .thenReturn(Optional.empty());

        taxRateService.resolve(COMPANY, "IVA", DOCUMENT_DATE);

        var companyId = ArgumentCaptor.forClass(UUID.class);
        var taxCode = ArgumentCaptor.forClass(String.class);
        var date = ArgumentCaptor.forClass(LocalDate.class);
        verify(taxRateRepository)
                .findFirstByCompanyIdAndTaxCodeAndEnabledTrueAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                        companyId.capture(), taxCode.capture(), date.capture());

        // The three arguments are the caller's, unchanged: a `LocalDate.now()` anywhere in this path would
        // show up here as a date that is not the document's, and would make a document entered today carry
        // today's rate even when it is dated last month.
        assertThat(companyId.getValue()).isEqualTo(COMPANY);
        assertThat(taxCode.getValue()).isEqualTo("IVA");
        assertThat(date.getValue()).isEqualTo(DOCUMENT_DATE);
    }

    @Test
    @DisplayName("no rate stays empty: a missing rate is a fact about the configuration, not a zero")
    void noRateIsNotZeroTax() {
        when(taxRateRepository
                        .findFirstByCompanyIdAndTaxCodeAndEnabledTrueAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                                eq(COMPANY), eq("IVA"), eq(DOCUMENT_DATE)))
                .thenReturn(Optional.empty());

        assertThat(taxRateService.resolve(COMPANY, "IVA", DOCUMENT_DATE))
                .as("answering 0 here would tax every IVA line at nothing and no constraint would notice: a "
                        + "line at 0% satisfies ck_item_tax")
                .isEmpty();
    }

    @Test
    @DisplayName("a rate cannot be resolved without a company, a code and a date")
    void refusesAnIncompleteQuestion() {
        assertThatThrownBy(() -> taxRateService.resolve(null, "IVA", DOCUMENT_DATE))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("companyId");
        assertThatThrownBy(() -> taxRateService.resolve(COMPANY, null, DOCUMENT_DATE))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("taxCode");
        assertThatThrownBy(() -> taxRateService.resolve(COMPANY, "IVA", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("documentDate");
    }

    // --- the arithmetic the schema states, computed from Java ---

    @Test
    @DisplayName("the tax amount is taken on the line's money after its discount, at four decimals")
    void computesTheTaxOnSubtotalMinusDiscount() {
        assertThat(TaxRateService.lineTaxAmount(
                        new BigDecimal("100.0000"), new BigDecimal("10.0000"), new BigDecimal("0.160000")))
                .as("(100 - 10) * 0.16, which is what ck_item_tax subtracts before multiplying")
                .isEqualByComparingTo("14.4000");
    }

    @Test
    @DisplayName("a half ten-thousandth rounds away from zero, like PostgreSQL's round(numeric, 4)")
    void roundsHalfAwayFromZero() {
        // 0.0001 * 0.5 = 0.00005 exactly: HALF_EVEN would answer 0.0000 and the insert would then be refused
        // by ck_item_tax, which asks PostgreSQL for round(..., 4) — half away from zero.
        assertThat(TaxRateService.lineTaxAmount(new BigDecimal("0.0001"), BigDecimal.ZERO, new BigDecimal("0.5")))
                .isEqualByComparingTo("0.0001");
        assertThat(TaxRateService.lineTaxAmount(new BigDecimal("0.0001"), BigDecimal.ZERO, new BigDecimal("0.499999")))
                .as("and just below the half stays at zero, so the boundary is a boundary and not a fence "
                        + "that rounds everything up")
                .isEqualByComparingTo("0.0000");
    }

    /** A read-only entity has no public constructor by design, so the fixture is a mock. */
    private TaxRate rate(String taxCode, String rate, LocalDate effectiveFrom) {
        var row = mock(TaxRate.class);
        when(row.getId()).thenReturn(RATE_ID);
        when(row.getTaxCode()).thenReturn(taxCode);
        when(row.getRate()).thenReturn(new BigDecimal(rate));
        when(row.getEffectiveFrom()).thenReturn(effectiveFrom);
        when(row.getEnabled()).thenReturn(true);
        return row;
    }
}
