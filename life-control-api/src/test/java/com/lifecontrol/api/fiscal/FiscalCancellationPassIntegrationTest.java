package com.lifecontrol.api.fiscal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.accounting.catalog.StarterAccountingCatalogApplier;
import com.lifecontrol.api.common.tenant.TenantScope;
import com.lifecontrol.api.fiscal.FiscalCancellationProviderTestConfiguration.InMemoryFiscalCancellationProvider;
import com.lifecontrol.api.fiscal.FiscalCancellationProviderTestConfiguration.InMemoryFiscalCancellationReconciliationPort;
import com.lifecontrol.api.fiscal.cancellation.FiscalCancellationReconciliationPort.Outcome;
import com.lifecontrol.api.fiscal.repository.FiscalCancellationTenantResolver;
import com.lifecontrol.api.fiscal.service.FiscalCancellationPass;
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
 * The scheduled pass that settles submitted cancellations — {@code S1}–{@code S5} of issue {@code #994}, {@code T6} of
 * {@code odd/tasks/fiscal-cancellation-cadence.md}.
 *
 * <p><b>Why the distinction this suite is built around is "no HTTP request from a human".</b> The settlement itself is
 * not new: {@code POST /{id}/cancellation/reconciliation} already resolves a pending row and its own suites assert
 * every answer. What did not exist is a caller that is not a human on a screen — the endpoint fires one document per
 * click, and the receiver's window is 72 hours, so a remembered submission whose operator forgot it stayed pending
 * forever. Every case here therefore drives {@link FiscalCancellationPass#runPass()} directly, through no request and
 * no token, and the fixture is still produced the way a real one is: a submission over the wire, whose {@code 202}
 * leaves the {@code CANCELLATION_PENDING} row the pass is supposed to find.</p>
 *
 * <p><b>Why the counters matter as much as the row.</b> {@code S4} and {@code S5} are not "the row looks right after
 * the pass" — they are statements about what the pass <b>did not do</b>: it must not ask the authority a second time
 * about a row it already settled, and it must not retry a rejection. A row-only assertion cannot see the difference
 * between a pass that skipped a document and one that asked and got the same answer again, so each case counts the
 * provider's status calls and asserts the exact number.</p>
 *
 * <p><b>Why the fixtures state their own tenant.</b> {@code fiscal_document} is armed with {@code FORCE} since
 * {@code V108}, so every read and write here runs as {@code lifecontrol_app} under the policy, exactly as delivery
 * does. The pass states one tenant at a time itself; this suite is one company, and the {@code S3} case is the one that
 * shows the difference between the two kinds of read.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import({FiscalStampProviderTestConfiguration.class, FiscalCancellationProviderTestConfiguration.class})
class FiscalCancellationPassIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String ATTEMPT = "cancellation-pass-2026-11-20-01";

    private static final String CANCELLATION_URL = "/api/fiscal-documents/%s/cancellation";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FiscalDocumentStamper stamper;

    @Autowired
    private StarterAccountingCatalogApplier accountingCatalog;

    @Autowired
    private FiscalCancellationPass pass;

    @Autowired
    private FiscalCancellationTenantResolver tenantResolver;

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

        // The submission is the fixture, not the subject: the authority answers 211 and the real endpoint writes the
        // pending row. The pass is what these cases observe, so the counters it reads start at zero.
        FiscalCancellationProviderTestConfiguration.PROVIDER.answerWith(
                InMemoryFiscalCancellationProvider.Answer.IN_PROCESS);
        mockMvc.perform(cancellation(stamped).with(token(seed.company())).content("{\"motive\":\"02\"}"))
                .andExpect(status().isAccepted());

        FiscalCancellationProviderTestConfiguration.PROVIDER.reset();
        FiscalCancellationProviderTestConfiguration.SEALER.reset();
        FiscalCancellationProviderTestConfiguration.RECONCILER.reset();
    }

    @AfterEach
    void clearTheIdentity() {
        TenantScope.clear();
        SecurityContextHolder.clearContext();
    }

    // ─── S2: settled without a human ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("S2: an accepted cancellation is settled by the pass itself, with its code and its stamp, and no "
            + "request from a human")
    void anAcceptanceIsSettledWithoutAnHttpRequest() {
        FiscalCancellationProviderTestConfiguration.RECONCILER.answeringStatus(new Outcome.Accepted(
                InMemoryFiscalCancellationReconciliationPort.ACCEPTED_STATUS_UUID,
                InMemoryFiscalCancellationReconciliationPort.ACCEPTED_ACUSE,
                InMemoryFiscalCancellationReconciliationPort.ACCEPTED_MESSAGE));

        var report = pass.runPass();

        assertThat(report.skipped()).isFalse();
        assertThat(report.companies()).isPositive();
        assertThat(report.documents()).isEqualTo(1);
        assertThat(report.settled()).isEqualTo(1);
        assertThat(report.waiting()).isZero();
        assertThat(report.failed()).isZero();
        assertThat(report.abandonedTenants()).isZero();

        Map<String, Object> row = pendingRow();
        assertThat(row.get("status")).isEqualTo("CANCELLED");
        assertThat(row.get("cancellation_status_uuid"))
                .isEqualTo(InMemoryFiscalCancellationReconciliationPort.ACCEPTED_STATUS_UUID);
        assertThat(row.get("cancellation_settled_at")).isNotNull();
        assertThat(FiscalCancellationProviderTestConfiguration.RECONCILER.statusInvocations())
                .isEqualTo(1);
    }

    // ─── S4: running it twice ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("S4: a second pass changes nothing the first settled and asks the authority nothing about it")
    void aSecondPassAsksNothingAboutASettledRow() {
        FiscalCancellationProviderTestConfiguration.RECONCILER.answeringStatus(new Outcome.Accepted(
                InMemoryFiscalCancellationReconciliationPort.ACCEPTED_STATUS_UUID,
                InMemoryFiscalCancellationReconciliationPort.ACCEPTED_ACUSE,
                InMemoryFiscalCancellationReconciliationPort.ACCEPTED_MESSAGE));
        pass.runPass();

        Map<String, Object> settled = pendingRow();
        FiscalCancellationProviderTestConfiguration.RECONCILER.reset();

        var second = pass.runPass();

        assertThat(second.documents())
                .as("a settled row is not a candidate, so the second pass reads none")
                .isZero();
        assertThat(second.settled()).isZero();
        assertThat(FiscalCancellationProviderTestConfiguration.RECONCILER.statusInvocations())
                .as("a settled row is never asked about again")
                .isZero();
        assertThat(pendingRow()).isEqualTo(settled);
    }

    // ─── S5: the two answers that do not finish it ────────────────────────────────────────────────────

    @Test
    @DisplayName("S5: a still-pending answer moves nothing and the row stays a candidate for the next pass")
    void aStillPendingAnswerMovesNothingAndStaysACandidate() {
        FiscalCancellationProviderTestConfiguration.RECONCILER.answeringStatus(new Outcome.StillPending(
                InMemoryFiscalCancellationReconciliationPort.IN_PROCESS_STATUS_UUID,
                InMemoryFiscalCancellationReconciliationPort.IN_PROCESS_MESSAGE));

        var first = pass.runPass();

        assertThat(first.documents()).isEqualTo(1);
        assertThat(first.waiting()).isEqualTo(1);
        assertThat(first.settled()).isZero();

        Map<String, Object> row = pendingRow();
        assertThat(row.get("status")).isEqualTo("CANCELLATION_PENDING");
        assertThat(row.get("cancellation_settled_at")).isNull();

        FiscalCancellationProviderTestConfiguration.RECONCILER.answeringStatus(new Outcome.StillPending(
                InMemoryFiscalCancellationReconciliationPort.IN_PROCESS_STATUS_UUID,
                InMemoryFiscalCancellationReconciliationPort.IN_PROCESS_MESSAGE));
        var second = pass.runPass();

        assertThat(second.documents())
                .as("an unanswered row is still work for the next pass")
                .isEqualTo(1);
        assertThat(FiscalCancellationProviderTestConfiguration.RECONCILER.statusInvocations())
                .as("asked once per pass while nobody has answered: two passes, two calls, one row")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("S5: a rejected cancellation is settled by its stamp and leaves the candidate set, so a second pass "
            + "never retries it")
    void aRejectionLeavesTheCandidateSet() {
        FiscalCancellationProviderTestConfiguration.RECONCILER.answeringStatus(new Outcome.Rejected(
                InMemoryFiscalCancellationReconciliationPort.REJECTED_STATUS_UUID,
                InMemoryFiscalCancellationReconciliationPort.REJECTED_MESSAGE));

        var first = pass.runPass();

        assertThat(first.documents()).isEqualTo(1);
        assertThat(first.settled()).isEqualTo(1);

        Map<String, Object> row = pendingRow();
        assertThat(row.get("status"))
                .as("a rejection is not a cancellation: the state stays, the stamp finishes it")
                .isEqualTo("CANCELLATION_PENDING");
        assertThat(row.get("cancellation_status_uuid"))
                .isEqualTo(InMemoryFiscalCancellationReconciliationPort.REJECTED_STATUS_UUID);
        assertThat(row.get("cancellation_settled_at")).isNotNull();

        FiscalCancellationProviderTestConfiguration.RECONCILER.reset();
        var second = pass.runPass();

        assertThat(second.documents()).isZero();
        assertThat(FiscalCancellationProviderTestConfiguration.RECONCILER.statusInvocations())
                .as("retrying a rejection is a decision nobody has made")
                .isZero();
    }

    // ─── S3: the tenant enumeration is the only door ──────────────────────────────────────────────────

    @Test
    @DisplayName("S3: the pass finds its tenant through the resolver while an unscoped connection still reads no "
            + "fiscal document at all")
    void theResolverIsTheOnlyDoorToAnotherTenantsWork() {
        TenantScope.clear();
        jdbc.execute("select set_config('app.company_id', '', true)");

        assertThat(tenantResolver.findCompaniesWithPendingCancellations())
                .as("a SECURITY DEFINER resolver answers with no scope, and names only the company")
                .contains(seed.company());
        assertThat(pendingCancellationCount())
                .as("the resolver is the only door: the same read without a scope sees nothing")
                .isZero();

        jdbc.execute("select set_config('app.company_id', '" + seed.company() + "', true)");

        assertThat(pendingCancellationCount())
                .as("the zero above is the policy and not an empty table")
                .isEqualTo(1);
    }

    // ─── fixtures ────────────────────────────────────────────────────────────────────────────────────

    private long pendingCancellationCount() {
        Long count = jdbc.queryForObject(
                "select count(*) from fiscal_document where status = 'CANCELLATION_PENDING'", Long.class);
        return count == null ? 0L : count;
    }

    private Map<String, Object> pendingRow() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select status, cancellation_status_uuid, cancellation_settled_at
                  from fiscal_document
                 where cancels_fiscal_document_id = ?
                """, stamped);
        assertThat(rows).as("the submission wrote exactly one cancellation row").hasSize(1);
        return rows.getFirst();
    }

    private MockHttpServletRequestBuilder cancellation(UUID documentId) {
        return post(CANCELLATION_URL.formatted(documentId))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON);
    }

    private RequestPostProcessor token(UUID company) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_lc-admin"))
                .jwt(builder -> builder.subject("suite:fiscal-cancellation-pass")
                        .claim("company_id", company.toString())
                        .claim("company_country_id", seed.companyCountry().toString())
                        .claim("company_region_id", seed.region().toString())
                        .claim("company_zone_id", seed.zone().toString())
                        .claim("company_store_id", seed.store().toString()));
    }
}
