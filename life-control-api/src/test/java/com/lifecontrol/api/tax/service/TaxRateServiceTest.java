package com.lifecontrol.api.tax.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.repository.CompanyRegionRepository;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.tax.exception.TaxRateRegionGapException;
import com.lifecontrol.api.tax.model.TaxRate;
import com.lifecontrol.api.tax.repository.TaxCodeRepository;
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
 * {@link TaxRateService}: the contract a line's tax is computed from (ADR-0002 decision 6), and the precedence
 * the region axis adds to it (issue #855, {@code D1} and {@code D2}).
 *
 * <p>What the database owns — the policy, the FK, the two partial unique keys, the bounds of {@code rate} — is
 * {@code TaxRateMigrationIntegrationTest}'s; what is pinned here is the part the schema cannot state: that the
 * date handed to the query is the <b>document's</b> and never the clock, that "no rate" stays empty instead of
 * becoming a silent zero, and that a region that holds <b>enabled</b> rows for a code is authoritative for it —
 * the frontier's 8% must not be answered with the country's 16%, and a gap in a region that states the code is a
 * refusal that names it rather than a fallback. Authority follows the enabled rows and not the existence of any
 * row (issue #1030): retiring a region's rows gives the place back, so the refusal is a state a region enters and
 * leaves, never an open-only flag.</p>
 */
@DisplayName("TaxRateService: the document's date decides, the region comes first, and no rate is not zero tax")
class TaxRateServiceTest {

    private static final UUID COMPANY = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RATE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID REGION = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final LocalDate DOCUMENT_DATE = LocalDate.of(2026, 3, 15);

    private TaxRateRepository taxRateRepository;
    private CompanyRegionRepository companyRegionRepository;
    private TaxRateService taxRateService;

    @BeforeEach
    void setUp() {
        taxRateRepository = mock(TaxRateRepository.class);
        companyRegionRepository = mock(CompanyRegionRepository.class);
        // The remaining collaborators are irrelevant to the reads pinned here: `resolve` never reaches them, and
        // mocking them keeps this suite about the resolution alone.
        taxRateService = new TaxRateService(
                taxRateRepository,
                mock(TaxCodeRepository.class),
                mock(CompanyRepository.class),
                companyRegionRepository,
                mock(CurrentUserContext.class));
    }

    @Test
    @DisplayName("the resolution carries the row's four facts, so a line can snapshot all of them")
    void mapsTheRowToTheResolution() {
        // The fixture is built before the stubbing, never inside `thenReturn(...)`: a nested `when(...)`
        // is an UnfinishedStubbing error in Mockito, and the failure names the helper, not the mistake.
        var row = rate("IVA", "0.160000", LocalDate.of(2026, 1, 1));
        when(taxRateRepository
                        .findFirstByCompanyIdAndTaxCodeAndCompanyRegionIsNullAndEnabledTrueAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                                COMPANY, "IVA", DOCUMENT_DATE))
                .thenReturn(Optional.of(row));

        var resolution = taxRateService.resolve(COMPANY, "IVA", null, DOCUMENT_DATE);

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
                        .findFirstByCompanyIdAndTaxCodeAndCompanyRegionIsNullAndEnabledTrueAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                                any(), any(), any()))
                .thenReturn(Optional.empty());

        taxRateService.resolve(COMPANY, "IVA", null, DOCUMENT_DATE);

        var companyId = ArgumentCaptor.forClass(UUID.class);
        var taxCode = ArgumentCaptor.forClass(String.class);
        var date = ArgumentCaptor.forClass(LocalDate.class);
        verify(taxRateRepository)
                .findFirstByCompanyIdAndTaxCodeAndCompanyRegionIsNullAndEnabledTrueAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
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
                        .findFirstByCompanyIdAndTaxCodeAndCompanyRegionIsNullAndEnabledTrueAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                                eq(COMPANY), eq("IVA"), eq(DOCUMENT_DATE)))
                .thenReturn(Optional.empty());

        assertThat(taxRateService.resolve(COMPANY, "IVA", null, DOCUMENT_DATE))
                .as("answering 0 here would tax every IVA line at nothing and no constraint would notice: a "
                        + "line at 0% satisfies ck_item_tax")
                .isEmpty();
    }

    @Test
    @DisplayName("a rate cannot be resolved without a company, a code and a date; the region is the only optional one")
    void refusesAnIncompleteQuestion() {
        assertThatThrownBy(() -> taxRateService.resolve(null, "IVA", null, DOCUMENT_DATE))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("companyId");
        assertThatThrownBy(() -> taxRateService.resolve(COMPANY, null, null, DOCUMENT_DATE))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("taxCode");
        assertThatThrownBy(() -> taxRateService.resolve(COMPANY, "IVA", null, null))
                .as("a null region is not an error: a company-wide rate needs no place")
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("documentDate");
    }

    // ---- the region axis: the four cases of the precedence, D1 and D2 of odd/tasks/tax-rate-region-axis.md ----

    @Test
    @DisplayName("a region's rate answers for the same code and date, and the company-wide rate is never consulted")
    void regionRateWinsOverTheCompanyWideOne() {
        // Both rates are 8% apart in everything but the eight percent: the company-wide row is the 16% the
        // frontier must not be charged, and it exists on the same day, which is the whole point of V95.
        // Both fixtures are built before the stubbing, never inside `thenReturn(...)`: a nested `when(...)` is an
        // UnfinishedStubbing error in Mockito (the note the cases above carry), and the failure would name the
        // helper instead of the rule.
        var regional = rate(REGION, "IVA", "0.080000", LocalDate.of(2026, 1, 1));
        var companyWide = rate("IVA", "0.160000", LocalDate.of(2026, 1, 1));
        when(taxRateRepository
                        .findFirstByCompanyIdAndTaxCodeAndCompanyRegionIdAndEnabledTrueAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                                COMPANY, "IVA", REGION, DOCUMENT_DATE))
                .thenReturn(Optional.of(regional));
        // Lenient: a correct answer never consults the company-wide rate here, and never consulting it is what
        // the case measures. A strict stub would turn a wrong answer into an "unnecessary stubbing" report
        // instead of the 16% nobody asked for.
        lenient()
                .when(taxRateRepository
                        .findFirstByCompanyIdAndTaxCodeAndCompanyRegionIsNullAndEnabledTrueAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                                COMPANY, "IVA", DOCUMENT_DATE))
                .thenReturn(Optional.of(companyWide));

        var resolution = taxRateService.resolve(COMPANY, "IVA", REGION, DOCUMENT_DATE);

        assertThat(resolution)
                .as("the region's row is the answer, even though a company-wide row also applies that day")
                .get()
                .extracting(TaxRateResolution::rate)
                .isEqualTo(new BigDecimal("0.080000"));
        verify(taxRateRepository, never())
                .findFirstByCompanyIdAndTaxCodeAndCompanyRegionIsNullAndEnabledTrueAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                        any(), any(), any());
    }

    @Test
    @DisplayName("a region that states rates but has none on the document's date refuses, naming code, region and date")
    void regionGapRefusesNamingTheCodeTheRegionAndTheDate() {
        var region = new CompanyRegion();
        region.setId(REGION);
        region.setRegionName("Frontera Norte");
        when(companyRegionRepository.findById(REGION)).thenReturn(Optional.of(region));
        // The region holds one *enabled* row for IVA and it starts after this document, so the region states the
        // code and has no answer for the day: the enabled read finds nothing and the authority check says yes (S1).
        when(taxRateRepository
                        .findFirstByCompanyIdAndTaxCodeAndCompanyRegionIdAndEnabledTrueAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                                COMPANY, "IVA", REGION, DOCUMENT_DATE))
                .thenReturn(Optional.empty());
        when(taxRateRepository.existsByCompanyIdAndTaxCodeAndCompanyRegionIdAndEnabledTrue(COMPANY, "IVA", REGION))
                .thenReturn(true);
        // The company-wide 16% that must NOT be charged in the frontier is there for the taking. Lenient for the
        // same reason as above: never consulting it is the property under test.
        var companyWide = rate("IVA", "0.160000", LocalDate.of(2026, 1, 1));
        lenient()
                .when(taxRateRepository
                        .findFirstByCompanyIdAndTaxCodeAndCompanyRegionIsNullAndEnabledTrueAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                                COMPANY, "IVA", DOCUMENT_DATE))
                .thenReturn(Optional.of(companyWide));

        assertThatThrownBy(() -> taxRateService.resolve(COMPANY, "IVA", REGION, DOCUMENT_DATE))
                .as("falling through to the company-wide rate here would charge the frontier's sale at 16% and "
                        + "no constraint would notice: the line would satisfy ck_item_tax with a rate from another "
                        + "place (D2)")
                .isInstanceOf(TaxRateRegionGapException.class)
                // The refusal names the three facts whoever states the rate needs to fix it.
                .hasMessageContaining("IVA")
                .hasMessageContaining("Frontera Norte")
                .hasMessageContaining(REGION.toString())
                .hasMessageContaining(DOCUMENT_DATE.toString());
        verify(taxRateRepository, never())
                .findFirstByCompanyIdAndTaxCodeAndCompanyRegionIsNullAndEnabledTrueAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                        any(), any(), any());
    }

    @Test
    @DisplayName("a region with no rows at all for the code resolves from the company-wide rate, exactly as before")
    void regionWithNoRowsFallsBackToTheCompanyWideRate() {
        // Lenient on the region's side: this case is about the fallback's outcome, and an implementation that
        // never asks the region at all still has to answer 0.16 — which is the number asserted below.
        lenient()
                .when(taxRateRepository
                        .findFirstByCompanyIdAndTaxCodeAndCompanyRegionIdAndEnabledTrueAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                                COMPANY, "IVA", REGION, DOCUMENT_DATE))
                .thenReturn(Optional.empty());
        lenient()
                .when(taxRateRepository.existsByCompanyIdAndTaxCodeAndCompanyRegionIdAndEnabledTrue(
                        COMPANY, "IVA", REGION))
                .thenReturn(false);
        var companyWide = rate("IVA", "0.160000", LocalDate.of(2026, 1, 1));
        when(taxRateRepository
                        .findFirstByCompanyIdAndTaxCodeAndCompanyRegionIsNullAndEnabledTrueAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                                COMPANY, "IVA", DOCUMENT_DATE))
                .thenReturn(Optional.of(companyWide));

        assertThat(taxRateService.resolve(COMPANY, "IVA", REGION, DOCUMENT_DATE))
                .as("no region has rows yet in the tree as it stands, so refusing here would refuse every line "
                        + "(D2's rejected alternative)")
                .get()
                .extracting(TaxRateResolution::rate)
                .isEqualTo(new BigDecimal("0.160000"));
    }

    @Test
    @DisplayName("a region whose only rows are retired gives the place back: the company-wide rate answers (S2)")
    void regionWithOnlyRetiredRowsFallsBackToTheCompanyWideRate() {
        // What the two queries answer when the region's only row for IVA is `enabled = false`: the enabled read
        // finds nothing and — since issue #1030 — the authority check finds nothing either, because authority
        // follows the enabled rows. The region withdrew its statement, so the company-wide rate is the answer it
        // used to be denied: the old check counted the retired row as a statement and refused every document of
        // that region and code for good, with nothing left for anyone to state.
        when(taxRateRepository
                        .findFirstByCompanyIdAndTaxCodeAndCompanyRegionIdAndEnabledTrueAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                                COMPANY, "IVA", REGION, DOCUMENT_DATE))
                .thenReturn(Optional.empty());
        when(taxRateRepository.existsByCompanyIdAndTaxCodeAndCompanyRegionIdAndEnabledTrue(COMPANY, "IVA", REGION))
                .thenReturn(false);
        var companyWide = rate("IVA", "0.160000", LocalDate.of(2026, 1, 1));
        when(taxRateRepository
                        .findFirstByCompanyIdAndTaxCodeAndCompanyRegionIsNullAndEnabledTrueAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                                COMPANY, "IVA", DOCUMENT_DATE))
                .thenReturn(Optional.of(companyWide));

        assertThat(taxRateService.resolve(COMPANY, "IVA", REGION, DOCUMENT_DATE))
                .as("a retired row is a statement the region withdrew: answering the company-wide rate is the "
                        + "fallback D2 allows for a region that says nothing, and refusing here would leave the "
                        + "region with no way back")
                .get()
                .extracting(TaxRateResolution::rate)
                .isEqualTo(new BigDecimal("0.160000"));
    }

    @Test
    @DisplayName("a day the retired row used to cover answers 8% while it is enabled and 16% after it is retired (S3)")
    void aRetiredRowStopsCoveringTheDayItUsedToAnswer() {
        var covered = LocalDate.of(2026, 2, 1);
        var regional = rate(REGION, "IVA", "0.080000", LocalDate.of(2026, 1, 1));
        when(taxRateRepository
                        .findFirstByCompanyIdAndTaxCodeAndCompanyRegionIdAndEnabledTrueAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                                COMPANY, "IVA", REGION, covered))
                .thenReturn(Optional.of(regional));

        assertThat(taxRateService.resolve(COMPANY, "IVA", REGION, covered))
                .as("while the row is enabled the region answers the day it covers")
                .get()
                .extracting(TaxRateResolution::rate)
                .isEqualTo(new BigDecimal("0.080000"));

        // The same row, now retired: step 1 steps over it and step 2 no longer counts it, so the scope is a region
        // that states nothing and the company-wide row answers the very day the 8% used to.
        when(taxRateRepository
                        .findFirstByCompanyIdAndTaxCodeAndCompanyRegionIdAndEnabledTrueAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                                COMPANY, "IVA", REGION, covered))
                .thenReturn(Optional.empty());
        when(taxRateRepository.existsByCompanyIdAndTaxCodeAndCompanyRegionIdAndEnabledTrue(COMPANY, "IVA", REGION))
                .thenReturn(false);
        var companyWide = rate("IVA", "0.160000", LocalDate.of(2026, 1, 1));
        when(taxRateRepository
                        .findFirstByCompanyIdAndTaxCodeAndCompanyRegionIsNullAndEnabledTrueAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                                COMPANY, "IVA", covered))
                .thenReturn(Optional.of(companyWide));

        assertThat(taxRateService.resolve(COMPANY, "IVA", REGION, covered))
                .as("retiring the region's row withdraws the answer it was giving, so the day it used to cover is "
                        + "the company's again instead of a gap")
                .get()
                .extracting(TaxRateResolution::rate)
                .isEqualTo(new BigDecimal("0.160000"));
    }

    @Test
    @DisplayName("a region that states only another code is not authoritative for this one (S4)")
    void authorityIsPerRegionAndCode() {
        // The region holds an enabled row for another code. The rule is per (region, code), so that row says
        // nothing about IVA — and this change must not widen the rule into "a region with any rate at all".
        lenient()
                .when(taxRateRepository.existsByCompanyIdAndTaxCodeAndCompanyRegionIdAndEnabledTrue(
                        COMPANY, "IEPS", REGION))
                .thenReturn(true);
        lenient()
                .when(taxRateRepository
                        .findFirstByCompanyIdAndTaxCodeAndCompanyRegionIdAndEnabledTrueAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                                COMPANY, "IVA", REGION, DOCUMENT_DATE))
                .thenReturn(Optional.empty());
        when(taxRateRepository.existsByCompanyIdAndTaxCodeAndCompanyRegionIdAndEnabledTrue(COMPANY, "IVA", REGION))
                .thenReturn(false);
        var companyWide = rate("IVA", "0.160000", LocalDate.of(2026, 1, 1));
        when(taxRateRepository
                        .findFirstByCompanyIdAndTaxCodeAndCompanyRegionIsNullAndEnabledTrueAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                                COMPANY, "IVA", DOCUMENT_DATE))
                .thenReturn(Optional.of(companyWide));

        assertThat(taxRateService.resolve(COMPANY, "IVA", REGION, DOCUMENT_DATE))
                .as("an IEPS row in the region is not a statement about IVA, so the company-wide IVA rate answers")
                .get()
                .extracting(TaxRateResolution::rate)
                .isEqualTo(new BigDecimal("0.160000"));

        // The question is the line's own pair: a check that dropped the code would find the IEPS row and refuse
        // an IVA line, which is the widening this change must not introduce.
        verify(taxRateRepository).existsByCompanyIdAndTaxCodeAndCompanyRegionIdAndEnabledTrue(COMPANY, "IVA", REGION);
        verify(taxRateRepository, never())
                .existsByCompanyIdAndTaxCodeAndCompanyRegionIdAndEnabledTrue(COMPANY, "IEPS", REGION);
    }

    @Test
    @DisplayName("authority returns as soon as the region speaks again, so the refusal is a state (S6)")
    void authorityReturnsWhenTheRegionSpeaksAgain() {
        var companyWide = rate("IVA", "0.160000", LocalDate.of(2026, 1, 1));
        when(taxRateRepository
                        .findFirstByCompanyIdAndTaxCodeAndCompanyRegionIdAndEnabledTrueAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                                COMPANY, "IVA", REGION, DOCUMENT_DATE))
                .thenReturn(Optional.empty());
        when(taxRateRepository
                        .findFirstByCompanyIdAndTaxCodeAndCompanyRegionIsNullAndEnabledTrueAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                                COMPANY, "IVA", DOCUMENT_DATE))
                .thenReturn(Optional.of(companyWide));
        // The retired state first, then the reinstated one: the region leaves authority and enters it again.
        when(taxRateRepository.existsByCompanyIdAndTaxCodeAndCompanyRegionIdAndEnabledTrue(COMPANY, "IVA", REGION))
                .thenReturn(false, true);

        assertThat(taxRateService.resolve(COMPANY, "IVA", REGION, DOCUMENT_DATE))
                .as("first half: nothing enabled in the region, so the company-wide 16% answers")
                .get()
                .extracting(TaxRateResolution::rate)
                .isEqualTo(new BigDecimal("0.160000"));

        var region = new CompanyRegion();
        region.setId(REGION);
        region.setRegionName("Frontera Norte");
        when(companyRegionRepository.findById(REGION)).thenReturn(Optional.of(region));

        assertThatThrownBy(() -> taxRateService.resolve(COMPANY, "IVA", REGION, DOCUMENT_DATE))
                .as("second half: the region states the code again, and the refusal for a day it does not cover is "
                        + "back — which is what makes authority a state the region enters and leaves rather than a "
                        + "one-way flag")
                .isInstanceOf(TaxRateRegionGapException.class)
                .hasMessageContaining("Frontera Norte");
    }

    @Test
    @DisplayName(
            "an empty answer is not zero tax: with no company-wide row either, the retired region resolves empty (S8)")
    void aRetiredRegionWithNoCompanyWideRowResolvesEmpty() {
        when(taxRateRepository
                        .findFirstByCompanyIdAndTaxCodeAndCompanyRegionIdAndEnabledTrueAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                                COMPANY, "IVA", REGION, DOCUMENT_DATE))
                .thenReturn(Optional.empty());
        when(taxRateRepository.existsByCompanyIdAndTaxCodeAndCompanyRegionIdAndEnabledTrue(COMPANY, "IVA", REGION))
                .thenReturn(false);
        when(taxRateRepository
                        .findFirstByCompanyIdAndTaxCodeAndCompanyRegionIsNullAndEnabledTrueAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                                COMPANY, "IVA", DOCUMENT_DATE))
                .thenReturn(Optional.empty());

        assertThat(taxRateService.resolve(COMPANY, "IVA", REGION, DOCUMENT_DATE))
                .as("no scope states an enabled rate, so the answer is empty and each document writer turns it into "
                        + "its own 400: a zero here would tax the line at nothing and ck_item_tax would agree with "
                        + "it (S8)")
                .isEmpty();
    }

    /** A read-only entity has no public constructor by design, so the company-wide fixture is a mock. */
    private TaxRate rate(String taxCode, String rate, LocalDate effectiveFrom) {
        var row = mock(TaxRate.class);
        when(row.getId()).thenReturn(RATE_ID);
        when(row.getTaxCode()).thenReturn(taxCode);
        when(row.getRate()).thenReturn(new BigDecimal(rate));
        when(row.getEffectiveFrom()).thenReturn(effectiveFrom);
        when(row.getEnabled()).thenReturn(true);
        return row;
    }

    /** The same fixture for a region-scoped row, which states the region it belongs to. */
    private TaxRate rate(UUID regionId, String taxCode, String rate, LocalDate effectiveFrom) {
        var row = rate(taxCode, rate, effectiveFrom);
        var region = new CompanyRegion();
        region.setId(regionId);
        when(row.getCompanyRegion()).thenReturn(region);
        return row;
    }
}
