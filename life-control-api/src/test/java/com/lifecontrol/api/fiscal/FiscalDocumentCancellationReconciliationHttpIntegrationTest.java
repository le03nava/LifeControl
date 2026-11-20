package com.lifecontrol.api.fiscal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.accounting.catalog.StarterAccountingCatalogApplier;
import com.lifecontrol.api.common.tenant.TenantScope;
import com.lifecontrol.api.fiscal.FiscalCancellationProviderTestConfiguration.InMemoryFiscalCancellationProvider;
import com.lifecontrol.api.fiscal.FiscalCancellationProviderTestConfiguration.InMemoryFiscalCancellationReconciliationPort;
import com.lifecontrol.api.fiscal.cancellation.FiscalCancellationReconciliationPort.Outcome;
import com.lifecontrol.api.fiscal.cancellation.FiscalCancellationReconciliationPort.Registration;
import com.lifecontrol.api.fiscal.cancellation.FiscalCancellationResult;
import com.lifecontrol.api.fiscal.service.FiscalDocumentStamper;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code POST /api/fiscal-documents/{id}/cancellation/reconciliation}: the pass {@code S1} of
 * {@code odd/tasks/fiscal-cancellation-reconciliation.md} makes reachable, and the four answers it can give
 * ({@code T8} of the same record, reading {@code S1}, {@code S5} and {@code S6}).
 *
 * <p><b>Why this suite exists at all, stated as the defect it closes.</b> {@code L5} decided the reconciler runs
 * inside a request's tenant scope, because {@code fiscal_document} is armed and forced and no platform thread may
 * enumerate the tenants that hold a pending cancellation. Until this endpoint existed the reconciling service had
 * no caller in {@code src/main} at all: a capability whose adapter is registered, whose service is green and whose
 * production meaning is "nobody has to query the PAC by hand" was unreachable — the same lesson {@code L17} of
 * {@code odd/tasks/cfdi-fiscal-document.md} recorded for the stamping endpoint, one layer out.</p>
 *
 * <p><b>Why {@code 200} is the answer for all four outcomes.</b> An acceptance, a settled rejection, a
 * still-pending answer and a refused registration are four <b>answers</b>, and the record says which one this pass
 * read: {@code documentStatus} states where the row stands after the pass and {@code settled} whether there is
 * anything left to ask. A status that encoded the outcome would make a caller parse a nullable body to learn a fact
 * the body already states, and a rejection — which is finished ({@code L2}(d)) — would have to borrow the status of
 * a failure it is not.</p>
 *
 * <p><b>Why the submission goes through the wire and the assertion is the row set ({@code S8}).</b> The fixture is
 * the {@code 202} {@code T2} writes, produced by the real endpoint and the real provider double, so the case starts
 * from the state a caller can actually reach: one {@code CANCELLATION_PENDING} row with the submission's code and no
 * settle stamp. Every case then counts provider calls and cancellation rows, because the three interesting outcomes
 * are statements about what <b>did not</b> happen — a rejected cancellation writes no {@code CANCELLED} row, a
 * still-pending answer writes nothing at all, and a second pass after either settlement asks nobody.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import({FiscalStampProviderTestConfiguration.class, FiscalCancellationProviderTestConfiguration.class})
@DisplayName("Fiscal cancellation reconciliation endpoint (S1, S5, S6, T8)")
class FiscalDocumentCancellationReconciliationHttpIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String ATTEMPT = "reconcile-http-2026-11-19-01";

    private static final String CANCELLATION_URL = "/api/fiscal-documents/%s/cancellation";

    private static final String RECONCILIATION_URL = "/api/fiscal-documents/%s/cancellation/reconciliation";

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
        FiscalCancellationProviderTestConfiguration.RECONCILER.reset();

        seed = new FiscalDocumentSeed(jdbc, accountingCatalog).aCompletedSaleWithAFiscalProfile();
        stamped = stamper.stamp(seed.sale(), ATTEMPT).documentId();

        // The submission is the fixture, not the subject: the authority answers 211, T2 writes the pending row, and
        // the caller receives the 202 the record describes (L2(g)).
        FiscalCancellationProviderTestConfiguration.PROVIDER.answerWith(
                InMemoryFiscalCancellationProvider.Answer.IN_PROCESS);
        mockMvc.perform(cancellation(stamped).with(token(seed.company())).content("{\"motive\":\"02\"}"))
                .andExpect(status().isAccepted());

        // The counters the cases read belong to the reconciling pass alone.
        FiscalCancellationProviderTestConfiguration.PROVIDER.reset();
        FiscalCancellationProviderTestConfiguration.SEALER.reset();
        FiscalCancellationProviderTestConfiguration.RECONCILER.reset();
    }

    @AfterEach
    void clearTheIdentity() {
        TenantScope.clear();
        SecurityContextHolder.clearContext();
    }

    // ─── accepted ───────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("S1/S5: an accepted cancellation is a 200, moves the pending row to CANCELLED with the stamp, and "
            + "the pass reports it settled")
    void anAcceptanceIs200AndMovesTheRowToCancelled() throws Exception {
        FiscalCancellationProviderTestConfiguration.RECONCILER.answeringStatus(new Outcome.Accepted(
                InMemoryFiscalCancellationReconciliationPort.ACCEPTED_STATUS_UUID,
                InMemoryFiscalCancellationReconciliationPort.ACCEPTED_ACUSE,
                InMemoryFiscalCancellationReconciliationPort.ACCEPTED_MESSAGE));

        mockMvc.perform(reconciliation(stamped).with(token(seed.company())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelledDocumentId").value(stamped.toString()))
                .andExpect(jsonPath("$.documentStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.authorityCode")
                        .value(InMemoryFiscalCancellationReconciliationPort.ACCEPTED_STATUS_UUID))
                .andExpect(jsonPath("$.message").value(InMemoryFiscalCancellationReconciliationPort.ACCEPTED_MESSAGE))
                .andExpect(jsonPath("$.settled").value(true));

        assertThat(FiscalCancellationProviderTestConfiguration.RECONCILER.statusInvocations())
                .as("S8: one question to the authority")
                .isEqualTo(1);
        assertThat(FiscalCancellationProviderTestConfiguration.RECONCILER.registerInvocations())
                .isZero();
        assertThat(statusCount("CANCELLED"))
                .as("the pending row moved, and no row was added")
                .isEqualTo(1);
        assertThat(statusCount("CANCELLATION_PENDING")).isZero();

        var settled = settledRow();
        assertThat(settled.get("cancels_fiscal_document_id")).isEqualTo(stamped);
        assertThat(settled.get("cancellation_status_uuid"))
                .as("the authority's own code is what the row keeps")
                .isEqualTo(InMemoryFiscalCancellationReconciliationPort.ACCEPTED_STATUS_UUID);
        assertThat(settled.get("cancellation_message"))
                .isEqualTo(InMemoryFiscalCancellationReconciliationPort.ACCEPTED_MESSAGE);
        assertThat(settled.get("cancellation_settled_at")).isNotNull();
        assertThat(row(stamped).get("status"))
                .as("the stamped document is untouched: a cancellation is remembered by its own row")
                .isEqualTo("STAMPED");
    }

    @Test
    @DisplayName("S6: a second call after an acceptance returns the same answer, asks the authority nothing and "
            + "changes no row")
    void aSecondCallAfterAnAcceptanceAsksNothing() throws Exception {
        FiscalCancellationProviderTestConfiguration.RECONCILER.answeringStatus(new Outcome.Accepted(
                InMemoryFiscalCancellationReconciliationPort.ACCEPTED_STATUS_UUID,
                InMemoryFiscalCancellationReconciliationPort.ACCEPTED_ACUSE,
                InMemoryFiscalCancellationReconciliationPort.ACCEPTED_MESSAGE));

        mockMvc.perform(reconciliation(stamped).with(token(seed.company()))).andExpect(status().isOk());
        var settled = settledRow();
        var settledId = (UUID) settled.get("id");
        var beforeSecondCall = row(settledId);

        // No outcome is queued for a second question: the double would throw if the pass asked again, which is the
        // assertion and not a side effect.
        mockMvc.perform(reconciliation(stamped).with(token(seed.company())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.settled").value(true));

        assertThat(FiscalCancellationProviderTestConfiguration.RECONCILER.statusInvocations())
                .as("S1/S6: running it twice changes nothing the first run settled, and asks nobody")
                .isEqualTo(1);
        assertThat(row(settledId))
                .as("and the row is byte-identical to the one the first pass left")
                .isEqualTo(beforeSecondCall);
    }

    // ─── rejected ───────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("S5: a 213 is a 200 with settled=true that keeps the row pending and preserves the code")
    void aRejectionIs200SettledAndLeavesTheRowPending() throws Exception {
        FiscalCancellationProviderTestConfiguration.RECONCILER.answeringStatus(new Outcome.Rejected(
                InMemoryFiscalCancellationReconciliationPort.REJECTED_STATUS_UUID,
                InMemoryFiscalCancellationReconciliationPort.REJECTED_MESSAGE));

        mockMvc.perform(reconciliation(stamped).with(token(seed.company())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentStatus").value("CANCELLATION_PENDING"))
                .andExpect(jsonPath("$.authorityCode")
                        .value(InMemoryFiscalCancellationReconciliationPort.REJECTED_STATUS_UUID))
                .andExpect(jsonPath("$.message").value(InMemoryFiscalCancellationReconciliationPort.REJECTED_MESSAGE))
                .andExpect(jsonPath("$.settled").value(true));

        assertThat(statusCount("CANCELLED"))
                .as("a refusal is not a cancellation: no row may say CANCELLED")
                .isZero();
        assertThat(statusCount("CANCELLATION_PENDING")).isEqualTo(1);

        var pending = settledRow();
        assertThat(pending.get("status")).isEqualTo("CANCELLATION_PENDING");
        assertThat(pending.get("cancellation_status_uuid"))
                .as("the authority's code is preserved, untranslated")
                .isEqualTo(InMemoryFiscalCancellationReconciliationPort.REJECTED_STATUS_UUID);
        assertThat(pending.get("cancellation_settled_at"))
                .as("L2(d): a rejection is finished by the stamp, which is what the settled flag reports")
                .isNotNull();
    }

    @Test
    @DisplayName("S6: a second call after a settled rejection asks the authority nothing and changes no row")
    void aSecondCallAfterARejectionAsksNothing() throws Exception {
        FiscalCancellationProviderTestConfiguration.RECONCILER.answeringStatus(new Outcome.Rejected(
                InMemoryFiscalCancellationReconciliationPort.REJECTED_STATUS_UUID,
                InMemoryFiscalCancellationReconciliationPort.REJECTED_MESSAGE));

        mockMvc.perform(reconciliation(stamped).with(token(seed.company()))).andExpect(status().isOk());
        var rejectedId = (UUID) settledRow().get("id");
        var beforeSecondCall = row(rejectedId);

        mockMvc.perform(reconciliation(stamped).with(token(seed.company())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentStatus").value("CANCELLATION_PENDING"))
                .andExpect(jsonPath("$.settled").value(true));

        assertThat(FiscalCancellationProviderTestConfiguration.RECONCILER.statusInvocations())
                .as("S6: settled is the timestamp, and it is what lets the second call skip the authority")
                .isEqualTo(1);
        assertThat(row(rejectedId)).isEqualTo(beforeSecondCall);
    }

    // ─── the answers that move nothing ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("S5: a still-pending answer is a 200 with settled=false and leaves the row byte-identical")
    void aStillPendingAnswerIs200AndWritesNothing() throws Exception {
        var pending = pendingRow();
        FiscalCancellationProviderTestConfiguration.RECONCILER.answeringStatus(new Outcome.StillPending(
                InMemoryFiscalCancellationReconciliationPort.IN_PROCESS_STATUS_UUID,
                InMemoryFiscalCancellationReconciliationPort.IN_PROCESS_MESSAGE));

        mockMvc.perform(reconciliation(stamped).with(token(seed.company())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentStatus").value("CANCELLATION_PENDING"))
                .andExpect(jsonPath("$.authorityCode")
                        .value(InMemoryFiscalCancellationReconciliationPort.IN_PROCESS_STATUS_UUID))
                .andExpect(jsonPath("$.settled").value(false));

        assertThat(row((UUID) pending.get("id")))
                .as("an unknown or still-pending answer moves nothing: not the code, not the stamp")
                .isEqualTo(pending);
    }

    @Test
    @DisplayName("S5: a refused registration is a 200 too — it is an answer — and it writes nothing")
    void aRefusedRegistrationIs200AndWritesNothing() throws Exception {
        var pending = pendingRow();
        FiscalCancellationProviderTestConfiguration.RECONCILER.answeringStatus(new Outcome.NoTransaction());
        FiscalCancellationProviderTestConfiguration.RECONCILER.answeringRegistration(
                new Registration.Refused(List.of(new FiscalCancellationResult.Issue(
                        InMemoryFiscalCancellationReconciliationPort.REFUSED_CODE,
                        InMemoryFiscalCancellationReconciliationPort.REFUSED_MESSAGE))));

        mockMvc.perform(reconciliation(stamped).with(token(seed.company())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentStatus").value("CANCELLATION_PENDING"))
                .andExpect(jsonPath("$.authorityCode").value(InMemoryFiscalCancellationReconciliationPort.REFUSED_CODE))
                .andExpect(jsonPath("$.settled").value(false));

        assertThat(FiscalCancellationProviderTestConfiguration.RECONCILER.statusInvocations())
                .isEqualTo(1);
        assertThat(FiscalCancellationProviderTestConfiguration.RECONCILER.registerInvocations())
                .as("a 702 is the fact that makes the pass register the request")
                .isEqualTo(1);
        assertThat(row((UUID) pending.get("id")))
                .as("a registration is not an answer: no row may claim one")
                .isEqualTo(pending);
    }

    // ─── the refusals ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("S1: the endpoint is admin-only, so a caller with lc-sales and not lc-admin is refused and the "
            + "authority is never asked")
    void aCallerWithoutTheAdminRoleIsForbidden() throws Exception {
        mockMvc.perform(post(RECONCILIATION_URL.formatted(stamped))
                        .accept(MediaType.APPLICATION_JSON)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_lc-sales"))
                                .jwt(builder -> builder.subject("suite:fiscal-reconciliation")
                                        .claim("company_id", seed.company().toString()))))
                .andExpect(status().isForbidden());

        assertThat(FiscalCancellationProviderTestConfiguration.RECONCILER.statusInvocations())
                .as("a caller without the role never reaches the authority")
                .isZero();
    }

    @Test
    @DisplayName("S5: a document with no submitted cancellation is a 404 that says so, and the authority is "
            + "never asked")
    void aDocumentThatWasNeverCancelledIs404() throws Exception {
        var otherSale = seed.anotherCompletedSale();
        FiscalStampProviderTestConfiguration.PROVIDER.stampingUnder(UUID.randomUUID());
        var neverCancelled =
                stamper.stamp(otherSale, "reconcile-http-2026-11-19-02").documentId();

        var message = mockMvc.perform(reconciliation(neverCancelled).with(token(seed.company())))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(message)
                .as("the absence the service names, not an unmapped path: the two share a status and not a meaning")
                .contains("No cancellation was submitted");
        assertThat(FiscalCancellationProviderTestConfiguration.RECONCILER.statusInvocations())
                .isZero();
        assertThat(FiscalCancellationProviderTestConfiguration.RECONCILER.registerInvocations())
                .isZero();
    }

    // ──────────────────────────────────────
    // the fixtures
    // ──────────────────────────────────────

    private MockHttpServletRequestBuilder cancellation(UUID documentId) {
        return post(CANCELLATION_URL.formatted(documentId))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON);
    }

    private MockHttpServletRequestBuilder reconciliation(UUID documentId) {
        return post(RECONCILIATION_URL.formatted(documentId)).accept(MediaType.APPLICATION_JSON);
    }

    private Map<String, Object> row(UUID documentId) {
        return jdbc.queryForMap("SELECT * FROM fiscal_document WHERE id = ?", documentId);
    }

    /** The one pending cancellation row of this company, read whole: every assertion below is about its columns. */
    private Map<String, Object> pendingRow() {
        return jdbc.queryForMap(
                "SELECT * FROM fiscal_document WHERE company_id = ? AND status = 'CANCELLATION_PENDING'",
                seed.company());
    }

    /** The one cancellation row this company holds once a pass has settled it, whatever state that left it in. */
    private Map<String, Object> settledRow() {
        return jdbc.queryForMap(
                "SELECT * FROM fiscal_document WHERE company_id = ? AND cancellation_settled_at IS NOT NULL",
                seed.company());
    }

    private long statusCount(String status) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM fiscal_document WHERE company_id = ? AND status = ?",
                Long.class,
                seed.company(),
                status);
    }

    /**
     * The caller's token, with the whole store chain the document's sale belongs to.
     *
     * <p><b>Why the chain and not only the company.</b> Reconciling authorizes the caller for the store the
     * document came from before the authority is asked, exactly as cancelling does, so a token that states only
     * the company would be refused with the same 403 in every case. {@code lc-admin} is the role the endpoint
     * requires and the one that is exempt from the store walk ({@code S7} of
     * {@code odd/tasks/fiscal-document-cross-store-authz.md}), and the narrower case above states what happens
     * without it.</p>
     */
    private RequestPostProcessor token(UUID company) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_lc-admin"))
                .jwt(builder -> builder.subject("suite:fiscal-reconciliation")
                        .claim("company_id", company.toString())
                        .claim("company_country_id", seed.companyCountry().toString())
                        .claim("company_region_id", seed.region().toString())
                        .claim("company_zone_id", seed.zone().toString())
                        .claim("company_store_id", seed.store().toString()));
    }
}
