package com.lifecontrol.api.fiscal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.accounting.catalog.StarterAccountingCatalogApplier;
import com.lifecontrol.api.common.tenant.TenantScope;
import com.lifecontrol.api.fiscal.FiscalCancellationProviderTestConfiguration.InMemoryFiscalCancellationProvider;
import com.lifecontrol.api.fiscal.cancellation.CfdiCancellationSealer;
import com.lifecontrol.api.fiscal.cancellation.FiscalCancellationProvider;
import com.lifecontrol.api.fiscal.service.FiscalDocumentStamper;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * A deployment that can stamp and cancel but holds no reconciling port: the reconciliation endpoint answers
 * <b>503</b> and no row moves ({@code T8} of {@code odd/tasks/fiscal-cancellation-reconciliation.md}, decision
 * {@code L7}(b) of the same record).
 *
 * <p><b>Why this needs a context of its own, and why that is the point.</b> {@code L7}(b) says the adapter has to be
 * registered beside the other two, because a bean reachable only through an {@code ObjectProvider} is found missing by
 * nobody — the missing case has a good answer. This suite is the other half of that claim: it states what the good
 * answer <b>is</b> when the switch really is off, so the 503 stays a statement about a capability the deployment does
 * not have instead of a description of a bean nobody registered. The context therefore registers exactly the two
 * halves a submission needs — the cancelling provider and its sealer — and not the reconciling port, which is the
 * absence under test.</p>
 *
 * <p><b>Why the pending row is written through the wire.</b> The 404 branch answers a document with no cancellation at
 * all, so this case has to start from a real one: the submission runs through the endpoint {@code T2} already shipped,
 * and the refusal is then the missing port and not a missing row.</p>
 *
 * <p><b>Why the assertion is the status and the row set.</b> A 503 that wrote something would be a pass that answered
 * nothing and still moved a row; the counters are the pending row's whole contents, compared before and after.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(FiscalStampProviderTestConfiguration.class)
@DisplayName("A reconciliation in a deployment with no reconciling port (T8, L7b)")
class FiscalDocumentReconciliationNotConfiguredIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String ATTEMPT = "reconcile-absent-2026-11-19-01";

    private static final String CANCELLATION_URL = "/api/fiscal-documents/%s/cancellation";

    private static final String RECONCILIATION_URL = "/api/fiscal-documents/%s/cancellation/reconciliation";

    /**
     * The cancelling half of the account and nothing else.
     *
     * <p>It reuses the doubles {@link FiscalCancellationProviderTestConfiguration} already owns — the provider that
     * counts its calls and the sealer that records the request — and deliberately omits the third bean that class
     * registers, because its absence is what this suite measures.</p>
     */
    @TestConfiguration
    static class OnlyTheCancellingHalf {

        @Bean
        FiscalCancellationProvider cancellationProvider() {
            return FiscalCancellationProviderTestConfiguration.PROVIDER;
        }

        @Bean
        CfdiCancellationSealer cancellationSealer() {
            return FiscalCancellationProviderTestConfiguration.SEALER;
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FiscalDocumentStamper stamper;

    @Autowired
    private StarterAccountingCatalogApplier accountingCatalog;

    private FiscalDocumentSeed seed;

    private UUID stamped;

    @BeforeEach
    void submitAPendingCancellationThroughTheWire() throws Exception {
        FiscalStampProviderTestConfiguration.PROVIDER.reset();
        FiscalStampProviderTestConfiguration.SEALER.reset();
        FiscalCancellationProviderTestConfiguration.PROVIDER.reset();
        FiscalCancellationProviderTestConfiguration.SEALER.reset();
        FiscalCancellationProviderTestConfiguration.PROVIDER.answerWith(
                InMemoryFiscalCancellationProvider.Answer.IN_PROCESS);

        seed = new FiscalDocumentSeed(jdbc, accountingCatalog).aCompletedSaleWithAFiscalProfile();
        stamped = stamper.stamp(seed.sale(), ATTEMPT).documentId();

        mockMvc.perform(post(CANCELLATION_URL.formatted(stamped))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"motive\":\"02\"}")
                        .with(token()))
                .andExpect(status().isAccepted());

        // The counters the case reads belong to the reconciliation, not to the submission that built its fixture.
        FiscalCancellationProviderTestConfiguration.PROVIDER.reset();
        FiscalCancellationProviderTestConfiguration.SEALER.reset();
    }

    @AfterEach
    void clearTheIdentity() {
        TenantScope.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("T8/L7(b): with no reconciling port the endpoint answers 503 naming the interface, leaves the "
            + "pending row untouched and writes nothing")
    void withNoReconcilingPortTheEndpointAnswers503() throws Exception {
        var pending = pendingRow();
        var documentsBefore = documentCount();

        var message = mockMvc.perform(post(RECONCILIATION_URL.formatted(stamped))
                        .accept(MediaType.APPLICATION_JSON)
                        .with(token()))
                .andExpect(status().is(HttpStatus.SERVICE_UNAVAILABLE.value()))
                .andExpect(jsonPath("$.status").value(HttpStatus.SERVICE_UNAVAILABLE.value()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(message)
                .as("the refusal names the interface an operator has to register")
                .contains("FiscalCancellationReconciliationPort");
        assertThat(row((UUID) pending.get("id")))
                .as("a capability the deployment does not have writes nothing")
                .isEqualTo(pending);
        assertThat(documentCount()).isEqualTo(documentsBefore);
        assertThat(FiscalCancellationProviderTestConfiguration.PROVIDER.invocations())
                .as("and nothing was cancelled a second time on the way")
                .isZero();
    }

    // ──────────────────────────────────────
    // the fixtures
    // ──────────────────────────────────────

    private Map<String, Object> row(UUID documentId) {
        return jdbc.queryForMap("SELECT * FROM fiscal_document WHERE id = ?", documentId);
    }

    private Map<String, Object> pendingRow() {
        return jdbc.queryForMap(
                "SELECT * FROM fiscal_document WHERE company_id = ? AND status = 'CANCELLATION_PENDING'",
                seed.company());
    }

    private long documentCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM fiscal_document WHERE company_id = ?", Long.class, seed.company());
    }

    /** The admin token, with the store chain the document's sale belongs to, exactly as the other suites state it. */
    private RequestPostProcessor token() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_lc-admin"))
                .jwt(builder -> builder.subject("suite:fiscal-reconciliation-absent")
                        .claim("company_id", seed.company().toString())
                        .claim("company_country_id", seed.companyCountry().toString())
                        .claim("company_region_id", seed.region().toString())
                        .claim("company_zone_id", seed.zone().toString())
                        .claim("company_store_id", seed.store().toString()));
    }
}
