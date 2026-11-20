package com.lifecontrol.api.fiscal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.accounting.catalog.StarterAccountingCatalogApplier;
import com.lifecontrol.api.common.tenant.TenantScope;
import com.lifecontrol.api.fiscal.FiscalCancellationProviderTestConfiguration.InMemoryFiscalCancellationProvider;
import com.lifecontrol.api.fiscal.cancellation.FiscalCancellationProvider;
import com.lifecontrol.api.fiscal.cancellation.FiscalCancellationReconciliationPort;
import com.lifecontrol.api.fiscal.exception.FiscalStampProviderDefectException;
import com.lifecontrol.api.fiscal.exception.FiscalStampProviderException;
import com.lifecontrol.api.fiscal.exception.FiscalStampProviderTransportException;
import com.lifecontrol.api.fiscal.service.FiscalDocumentStamper;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * The three fiscal endpoints answer <b>{@code 502}</b> when the authority failed to answer, with the tree's own
 * envelope and a constant body — the nine cases of {@code S5} and {@code S8} of
 * {@code odd/tasks/fiscal-provider-failure-status.md}, and the two controls {@code S6} and {@code S7} place beside
 * them.
 *
 * <p><b>Why the nine cases and not one.</b> {@code S5} names three failure kinds across three endpoints, and the
 * three endpoints do not reach the authority through the same object: {@code POST /api/fiscal-documents} stamps
 * through {@code FiscalStampProvider}, the cancellation submits through {@code FiscalCancellationProvider} and the
 * reconciliation asks through {@code FiscalCancellationReconciliationPort}. A single case would only prove that the
 * handler exists on one of the three paths — and {@code D3} is a statement about exactly which paths share the
 * family, so it is measured path by path. The three kinds are the family's own shapes: a transport that never
 * answered ({@code unreachable}), an endpoint that answered a non-{@code 2xx} ({@code answeredWithStatus}) and an
 * answer this tree could not read ({@code unreadable}).</p>
 *
 * <p><b>Why the assertion is the status <i>and</i> the body.</b> {@code S8} refuses "an exception was raised" as
 * evidence: what a caller receives is the answer, so every case reads {@code status}, the {@code ErrorResponse}
 * envelope's own {@code status} field, the constant {@code message} of {@code D2}, the {@code path} the request
 * was made on and a correlation id. The constant message is what makes the answer actionable without leaking the
 * vendor's detail ({@code D2}): the detail is in the log, which no response can be asserted against.</p>
 *
 * <p><b>Why the ports are {@code @MockitoBean}s that delegate until a case says otherwise.</b> A provider that
 * fails is the scenario, and the failure has to be raised <b>from the port</b> — not from the controller and not
 * from a service — because the port is where the adapter raises the family. The doubles the other fiscal suites
 * share cannot raise it, so this suite replaces the three port beans with mocks whose default answer is still the
 * shared double: the fixtures (a stamped document, a submitted cancellation) are then the real paths, and each case
 * replaces exactly one port with a throwing stub. The shared
 * {@link FiscalStampProviderTestConfiguration} and {@link FiscalCancellationProviderTestConfiguration} are
 * <b>imported and untouched</b>: their sealers are the beans the stamp and the cancellation still need, and the
 * statics they hold stay the fixtures' own.</p>
 *
 * <p><b>What is deliberately not here.</b> {@code S6}'s {@code 503} is not duplicated: the not-configured trio is
 * pinned by four suites of its own ({@code FiscalDocumentIssueNotConfiguredIntegrationTest},
 * {@code FiscalSealNotConfiguredIntegrationTest}, {@code FiscalDocumentCancellationNotConfiguredIntegrationTest},
 * {@code FiscalDocumentReconciliationNotConfiguredIntegrationTest}), and this context has all three ports
 * configured on purpose, so a {@code 503} is unreachable here by construction. {@code S9}'s "no row behind" is
 * {@code FiscalProviderFailureLeavesNoRowIntegrationTest}, because it cannot be measured inside a
 * {@code @Transactional} suite.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import({FiscalStampProviderTestConfiguration.class, FiscalCancellationProviderTestConfiguration.class})
@DisplayName("Fiscal provider failure answers 502 (S5, S7, S8)")
class FiscalProviderFailureHttpIntegrationTest extends AbstractPostgresIntegrationTest {

    /** {@code D2}: the one sentence a caller receives, whatever the authority did. */
    private static final String MESSAGE = "The fiscal authority failed to answer this request";

    private static final String ISSUE_URL = "/api/fiscal-documents";

    private static final String CANCELLATION_URL = "/api/fiscal-documents/%s/cancellation";

    private static final String RECONCILIATION_URL = "/api/fiscal-documents/%s/cancellation/reconciliation";

    private static final String ATTEMPT = "provider-failure-http-2026-11-20-01";

    private static final String ENDPOINT = "https://pac.example.test/servicio/timbrado";

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FiscalDocumentStamper stamper;

    @Autowired
    private StarterAccountingCatalogApplier accountingCatalog;

    @MockitoBean
    private FiscalStampProvider stampProvider;

    @MockitoBean
    private FiscalCancellationProvider cancellationProvider;

    @MockitoBean
    private FiscalCancellationReconciliationPort reconciliationPort;

    private FiscalDocumentSeed seed;

    /**
     * One completed sale with a fiscal profile, and the three ports answering from the doubles the other fiscal
     * suites share until a case replaces exactly one of them.
     */
    @BeforeEach
    void seedASaleAndDefaultThePortsToTheSharedDoubles() {
        FiscalStampProviderTestConfiguration.PROVIDER.reset();
        FiscalStampProviderTestConfiguration.SEALER.reset();
        FiscalCancellationProviderTestConfiguration.PROVIDER.reset();
        FiscalCancellationProviderTestConfiguration.SEALER.reset();
        FiscalCancellationProviderTestConfiguration.RECONCILER.reset();

        seed = new FiscalDocumentSeed(jdbc, accountingCatalog).aCompletedSaleWithAFiscalProfile();

        doAnswer(call -> FiscalStampProviderTestConfiguration.PROVIDER.stamp(call.getArgument(0)))
                .when(stampProvider)
                .stamp(any());
        doAnswer(call -> FiscalCancellationProviderTestConfiguration.PROVIDER.cancel(call.getArgument(0)))
                .when(cancellationProvider)
                .cancel(any());
        doAnswer(call -> FiscalCancellationProviderTestConfiguration.RECONCILER.status(call.getArgument(0)))
                .when(reconciliationPort)
                .status(any());
        doAnswer(call -> FiscalCancellationProviderTestConfiguration.RECONCILER.register(call.getArgument(0)))
                .when(reconciliationPort)
                .register(any());
    }

    @AfterEach
    void clearTheIdentity() {
        TenantScope.clear();
        SecurityContextHolder.clearContext();
    }

    // ─── the stamp endpoint ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("S5/S8: an unreachable authority is a 502 on the stamp, with the constant message and a path")
    void unreachableAuthorityIs502OnTheStamp() throws Exception {
        doThrow(unreachable()).when(stampProvider).stamp(any());

        assertProviderFailure(issue(), ISSUE_URL);
    }

    @Test
    @DisplayName("S5/S8: a non-2xx answer is the same 502 on the stamp, and names no status of its own")
    void nonSuccessAnswerIs502OnTheStamp() throws Exception {
        doThrow(answeredWithStatus()).when(stampProvider).stamp(any());

        assertProviderFailure(issue(), ISSUE_URL);
    }

    @Test
    @DisplayName("S5/S8: an answer that cannot be read is the same 502 on the stamp")
    void unreadableAnswerIs502OnTheStamp() throws Exception {
        doThrow(unreadable()).when(stampProvider).stamp(any());

        assertProviderFailure(issue(), ISSUE_URL);
    }

    // ─── the cancellation endpoint ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("S5/S8: an unreachable authority is a 502 on the cancellation, with the constant message")
    void unreachableAuthorityIs502OnTheCancellation() throws Exception {
        var stamped = stampedDocument();
        doThrow(unreachable()).when(cancellationProvider).cancel(any());

        assertProviderFailure(cancellation(stamped), CANCELLATION_URL.formatted(stamped));
    }

    @Test
    @DisplayName("S5/S8: a non-2xx answer is the same 502 on the cancellation")
    void nonSuccessAnswerIs502OnTheCancellation() throws Exception {
        var stamped = stampedDocument();
        doThrow(answeredWithStatus()).when(cancellationProvider).cancel(any());

        assertProviderFailure(cancellation(stamped), CANCELLATION_URL.formatted(stamped));
    }

    @Test
    @DisplayName("S5/S8: an answer that cannot be read is the same 502 on the cancellation")
    void unreadableAnswerIs502OnTheCancellation() throws Exception {
        var stamped = stampedDocument();
        doThrow(unreadable()).when(cancellationProvider).cancel(any());

        assertProviderFailure(cancellation(stamped), CANCELLATION_URL.formatted(stamped));
    }

    // ─── the reconciliation endpoint ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("S5/S8: an unreachable authority is a 502 on the reconciliation, with the constant message")
    void unreachableAuthorityIs502OnTheReconciliation() throws Exception {
        var pending = submittedCancellation();
        doThrow(unreachable()).when(reconciliationPort).status(any());

        assertProviderFailure(reconciliation(pending), RECONCILIATION_URL.formatted(pending));
    }

    @Test
    @DisplayName("S5/S8: a non-2xx answer is the same 502 on the reconciliation")
    void nonSuccessAnswerIs502OnTheReconciliation() throws Exception {
        var pending = submittedCancellation();
        doThrow(answeredWithStatus()).when(reconciliationPort).status(any());

        assertProviderFailure(reconciliation(pending), RECONCILIATION_URL.formatted(pending));
    }

    @Test
    @DisplayName("S5/S8: an answer that cannot be read is the same 502 on the reconciliation")
    void unreadableAnswerIs502OnTheReconciliation() throws Exception {
        var pending = submittedCancellation();
        doThrow(unreadable()).when(reconciliationPort).status(any());

        assertProviderFailure(reconciliation(pending), RECONCILIATION_URL.formatted(pending));
    }

    // ─── the controls ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("S7: an exception outside the fiscal family still answers 500 through the shared advice")
    void anExceptionOutsideTheFiscalFamilyIsStillA500() throws Exception {
        var unexpected = new IllegalStateException("the snapshot writer left a line with no authority key");
        doThrow(unexpected).when(stampProvider).stamp(any());

        mockMvc.perform(issue())
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.path").value(ISSUE_URL));
    }

    // ──────────────────────────────────────
    // the fixtures
    // ──────────────────────────────────────

    /**
     * The answer a caller receives for a provider failure, asserted whole: the status, the envelope's own status
     * field, {@code D2}'s constant sentence, the path the response carries and a correlation id.
     *
     * <p><b>Why the correlation id is asserted.</b> {@code S1} names it as the operator's way to the detail the body
     * deliberately omits ({@code D2}), so a response without one would leave the constant sentence as the only fact
     * a caller has.</p>
     */
    private void assertProviderFailure(MockHttpServletRequestBuilder request, String expectedPath) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.message").value(MESSAGE))
                .andExpect(jsonPath("$.path").value(expectedPath))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    /** One stamp of the seeded sale through the shared double, which is the fixture the two writes need. */
    private UUID stampedDocument() throws Exception {
        var stamped = stamper.stamp(seed.sale(), ATTEMPT).documentId();

        // The stamp asked the authority and the sealer; the counters the cases read belong to the case alone.
        FiscalStampProviderTestConfiguration.PROVIDER.reset();
        FiscalStampProviderTestConfiguration.SEALER.reset();
        return stamped;
    }

    /**
     * The pending cancellation row the reconciliation resolves, written by the real submission endpoint over the
     * real provider double: the {@code 202} of {@code L2}(g) of
     * {@code odd/tasks/cfdi-fiscal-cancellation.md} is what a reconciliation starts from.
     */
    private UUID submittedCancellation() throws Exception {
        var stamped = stampedDocument();
        FiscalCancellationProviderTestConfiguration.PROVIDER.answerWith(
                InMemoryFiscalCancellationProvider.Answer.IN_PROCESS);

        mockMvc.perform(cancellation(stamped)).andExpect(status().isAccepted());

        FiscalCancellationProviderTestConfiguration.PROVIDER.reset();
        FiscalCancellationProviderTestConfiguration.SEALER.reset();
        return stamped;
    }

    private MockHttpServletRequestBuilder issue() {
        return post(ISSUE_URL)
                .with(token(seed.company()))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content("{\"salesOrderId\":\"" + seed.sale() + "\",\"attemptKey\":\"" + ATTEMPT + "\"}");
    }

    private MockHttpServletRequestBuilder cancellation(UUID documentId) {
        return post(CANCELLATION_URL.formatted(documentId))
                .with(token(seed.company()))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content("{\"motive\":\"02\"}");
    }

    private MockHttpServletRequestBuilder reconciliation(UUID documentId) {
        return post(RECONCILIATION_URL.formatted(documentId))
                .with(token(seed.company()))
                .accept(MediaType.APPLICATION_JSON);
    }

    /** The family's first shape: the connection was never answered. */
    private static FiscalStampProviderException unreachable() {
        return FiscalStampProviderTransportException.unreachable(
                ENDPOINT, TIMEOUT, 4_000, new SocketTimeoutException("connect timed out"));
    }

    /** The family's second shape: the endpoint answered, and not with a success. */
    private static FiscalStampProviderException answeredWithStatus() {
        return FiscalStampProviderTransportException.answeredWithStatus(ENDPOINT, TIMEOUT, 320, 503);
    }

    /** The family's third shape: the answer arrived and cannot be read as the contract states. */
    private static FiscalStampProviderException unreadable() {
        return FiscalStampProviderDefectException.unreadable("the SOAP body carries no return element");
    }

    /**
     * The caller's token, with the whole store chain the document's sale belongs to.
     *
     * <p>{@code lc-admin} is the role the reconciling endpoint requires and the one exempt from the store walk, and
     * the chain is stated anyway because the stamp and the cancellation resolve the sale's store for every caller
     * that is not exempt. One token for the three endpoints keeps the case about the answer and not about the
     * caller.</p>
     */
    private RequestPostProcessor token(UUID company) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_lc-admin"))
                .jwt(builder -> builder.subject("suite:fiscal-provider-failure")
                        .claim("company_id", company.toString())
                        .claim("company_country_id", seed.companyCountry().toString())
                        .claim("company_region_id", seed.region().toString())
                        .claim("company_zone_id", seed.zone().toString())
                        .claim("company_store_id", seed.store().toString()));
    }
}
