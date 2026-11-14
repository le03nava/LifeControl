package com.lifecontrol.api.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.accounting.controller.AccountingReportController;
import com.lifecontrol.api.accounting.dto.GrossMarginDocumentsPage;
import com.lifecontrol.api.accounting.dto.GrossMarginStoresResponse;
import com.lifecontrol.api.accounting.dto.TrialBalanceResponse;
import com.lifecontrol.api.accounting.service.AccountingReportService;
import com.lifecontrol.api.config.ratelimit.RateLimitProperties;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Pins the {@code @PreAuthorize} gate of the four accounting reports, and the binding of their {@code from} and
 * {@code to} parameters — the HTTP-level case the delivery's own risks section named as missing.
 *
 * <p><b>Why the gate needs a case of its own.</b> The accounting suite verifies
 * {@code CurrentUserContext.verifyCompanyAccess}, which is the <em>service's</em> refusal; it says nothing about
 * whether the route is reachable by a caller with an unrelated role, and a {@code @PreAuthorize} that was deleted
 * or misspelled would leave every service-level assertion green. {@code L15(b)} chose {@code lc-admin} and
 * {@code lc-sales} over inventing an {@code lc-accounting} role, and that choice is only real if a role outside
 * the pair is refused by each of the four paths.</p>
 *
 * <p><b>Why the binding needs one too.</b> A {@code LocalDate} parameter that does not parse and is silently
 * ignored is the failure this asserts against: {@code ?to=31/10/2026} must be a 400 — never a 200 that quietly
 * reports the whole book — and a well-formed bound must be the value the service actually received. The currency
 * is asserted through the JSON the client reads, because {@code unknownCostDocuments} rides on the paged
 * response's body and would otherwise be invisible to the contract this endpoint publishes.</p>
 *
 * <p>The service is mocked and no fact is written: this class is about the layer in front of the book, so it runs
 * as a web slice and takes no database.</p>
 */
@WebMvcTest(AccountingReportController.class)
@Import({GlobalExceptionHandler.class, AccountingReportControllerSecurityTest.TestSecurityConfig.class})
@DisplayName("AccountingReportController — @PreAuthorize gate and query-parameter binding")
class AccountingReportControllerSecurityTest {

    @TestConfiguration
    @EnableWebSecurity
    @EnableMethodSecurity
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .httpBasic(basic -> {})
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .build();
        }
    }

    /**
     * The company in the path, as a constant <b>string</b> so the four report paths stay compile-time constants
     * ({@code @ValueSource} needs literal values): the gate refuses before the id is used, so any id serves.
     */
    private static final String COMPANY_ID = "0a0a0a0a-1111-2222-3333-444444444444";

    private static final UUID COMPANY = UUID.fromString(COMPANY_ID);

    private static final String BASE_URL = "/api/companies/" + COMPANY_ID + "/accounting";

    private static final String JOURNAL = BASE_URL + "/sales-journal";
    private static final String TRIAL_BALANCE = BASE_URL + "/trial-balance";
    private static final String MARGIN_DOCUMENTS = BASE_URL + "/gross-margin/documents";
    private static final String MARGIN_STORES = BASE_URL + "/gross-margin/stores";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountingReportService accountingReportService;

    @MockitoBean
    private RateLimitProperties rateLimitProperties;

    @BeforeEach
    void stubEveryReport() {
        var pageable = PageRequest.of(0, 20);
        when(accountingReportService.salesJournal(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));
        when(accountingReportService.trialBalance(any(), any(), any()))
                .thenReturn(new TrialBalanceResponse(List.of(), List.of()));
        when(accountingReportService.grossMarginByDocument(any(), any(), any(), any()))
                .thenReturn(new GrossMarginDocumentsPage(List.of(), pageable, 0, 0));
        when(accountingReportService.grossMarginByStore(any(), any(), any()))
                .thenReturn(new GrossMarginStoresResponse(List.of()));
    }

    // ──────────────────────────────────────
    // the gate
    // ──────────────────────────────────────

    @ParameterizedTest(name = "returns 403 for an unrelated role: {0}")
    @ValueSource(strings = {JOURNAL, TRIAL_BALANCE, MARGIN_DOCUMENTS, MARGIN_STORES})
    @WithMockUser(roles = {"other-role"})
    @DisplayName("returns 403 on every report for a role that is neither lc-admin nor lc-sales")
    void unrelatedRoleGetsForbiddenOnEveryReport(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser
    @DisplayName("returns 403 for an authenticated caller with no roles at all")
    void authenticatedCallerWithNoRolesGetsForbidden() throws Exception {
        mockMvc.perform(get(JOURNAL)).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"lc-company-store-read"})
    @DisplayName("returns 403 for the read-only store role: reading the book is not reading a store")
    void readOnlyStoreRoleGetsForbidden() throws Exception {
        mockMvc.perform(get(JOURNAL)).andExpect(status().isForbidden());
    }

    @ParameterizedTest(name = "returns 200 for lc-sales: {0}")
    @ValueSource(strings = {JOURNAL, TRIAL_BALANCE, MARGIN_DOCUMENTS, MARGIN_STORES})
    @WithMockUser(roles = {"lc-sales"})
    @DisplayName("returns 200 on every report for lc-sales")
    void salesRoleReadsEveryReport(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = {"lc-admin"})
    @DisplayName("returns 200 for lc-admin")
    void adminRoleReadsTheReport() throws Exception {
        mockMvc.perform(get(JOURNAL)).andExpect(status().isOk());
    }

    // ──────────────────────────────────────
    // the binding
    // ──────────────────────────────────────

    @Test
    @WithMockUser(roles = {"lc-sales"})
    @DisplayName("both bounds reach the service as the ISO dates the request stated")
    void bothBoundsAreBoundAsIsoDates() throws Exception {
        mockMvc.perform(get(JOURNAL).param("from", "2026-10-01").param("to", "2026-10-31"))
                .andExpect(status().isOk());

        var from = ArgumentCaptor.forClass(LocalDate.class);
        var to = ArgumentCaptor.forClass(LocalDate.class);
        verify(accountingReportService).salesJournal(eq(COMPANY), from.capture(), to.capture(), any());
        assertThat(from.getValue())
                .as("`from` is the date the request stated and not the day it ran")
                .isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(to.getValue())
                .as("`to` is inclusive, and it is the value the service saw")
                .isEqualTo(LocalDate.of(2026, 10, 31));
    }

    @Test
    @WithMockUser(roles = {"lc-sales"})
    @DisplayName("an absent bound reaches the service as null, which is the open end")
    void anAbsentBoundReachesTheServiceAsNull() throws Exception {
        mockMvc.perform(get(TRIAL_BALANCE)).andExpect(status().isOk());

        var from = ArgumentCaptor.forClass(LocalDate.class);
        var to = ArgumentCaptor.forClass(LocalDate.class);
        verify(accountingReportService).trialBalance(eq(COMPANY), from.capture(), to.capture());
        assertThat(from.getValue()).isNull();
        assertThat(to.getValue()).isNull();
    }

    @Test
    @WithMockUser(roles = {"lc-sales"})
    @DisplayName("a bound that does not parse is a 400 and never a 200 over the whole book")
    void anUnparseableBoundIsRefused() throws Exception {
        mockMvc.perform(get(JOURNAL).param("from", "2026-10-01").param("to", "31/10/2026"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for parameter 'to'"));

        verify(accountingReportService, never()).salesJournal(any(), any(), any(), any());
    }

    // ──────────────────────────────────────
    // the value that rides on the page
    // ──────────────────────────────────────

    @Test
    @WithMockUser(roles = {"lc-sales"})
    @DisplayName("the paged margin document response states its range-scoped unknown-cost count in the body")
    void theUnknownCostCountIsOnTheResponseBody() throws Exception {
        when(accountingReportService.grossMarginByDocument(any(), any(), any(), any()))
                .thenReturn(new GrossMarginDocumentsPage(List.of(), PageRequest.of(0, 20), 0, 7));

        mockMvc.perform(get(MARGIN_DOCUMENTS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(0))
                // A page answers how many documents of the range have an unknown cost, and a client reads it from
                // the body rather than from a header.
                .andExpect(jsonPath("$.unknownCostDocuments").value(7));
    }
}
