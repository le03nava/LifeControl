package com.lifecontrol.api.fiscal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.accounting.catalog.StarterAccountingCatalogApplier;
import com.lifecontrol.api.common.tenant.TenantScope;
import com.lifecontrol.api.fiscal.FiscalCancellationProviderTestConfiguration.InMemoryFiscalCancellationProvider;
import com.lifecontrol.api.fiscal.FiscalCancellationProviderTestConfiguration.InMemoryFiscalCancellationReconciliationPort;
import com.lifecontrol.api.fiscal.cancellation.FiscalCancellationReconciliationPort.Outcome;
import com.lifecontrol.api.fiscal.cancellation.FiscalCancellationReconciliationPort.Registration;
import com.lifecontrol.api.fiscal.cancellation.FiscalCancellationResult;
import com.lifecontrol.api.fiscal.exception.FiscalCancellationNotFoundException;
import com.lifecontrol.api.fiscal.model.FiscalCancellationMotive;
import com.lifecontrol.api.fiscal.service.FiscalCancellationReconciliationService;
import com.lifecontrol.api.fiscal.service.FiscalDocumentCancellationService;
import com.lifecontrol.api.fiscal.service.FiscalDocumentStamper;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reconciliation service against real PostgreSQL, where the fourth state's guard lives ({@code T3} of
 * {@code odd/tasks/fiscal-cancellation-reconciliation.md}; {@code S4}, {@code S5}, {@code S6} and {@code S8}).
 *
 * <p><b>Why the accepted settlement has to be pinned here and not in the unit suite.</b> {@code V113} replaced
 * {@code fiscal_document_guard} so that a {@code CANCELLATION_PENDING} row may only stay pending (recording an answer)
 * or become {@code CANCELLED} — every other target is refused. The service's acceptance is the <b>only</b> writer that
 * uses that arm, and a suite that mocked the repository would pass even if the transition it performs were one the
 * trigger refuses. So the row is read back with {@code JdbcTemplate} through the same pooled role and the same tenant
 * scope the application uses, and the case asserts that the row the pass wrote is the one the guard admitted.</p>
 *
 * <p><b>Why rows and provider calls are both counted ({@code S8}).</b> Every path below is a statement about what
 * <b>did not</b> happen: a still-pending answer moves nothing, a settled rejection is skipped on the second pass, a
 * registration writes no row. An exception alone cannot carry any of those, so each case reads the row set, the row's
 * three answer columns and the two call counters of the reconciling double.</p>
 *
 * <p><b>Why the port is a double here and the vendor's adapter has its own suite.</b> What this class owns is the
 * row's outcome; {@code SolucionFactibleFiscalCancellationReconcilerTest} owns the reading of the authority's answers.
 * The double answers in the sequence a case states, which is how "the second run asked nothing" becomes a number.</p>
 */
@SpringBootTest
@Transactional
@Import({FiscalStampProviderTestConfiguration.class, FiscalCancellationProviderTestConfiguration.class})
@DisplayName("Fiscal cancellation reconciliation (S4, S5, S6, S8)")
class FiscalCancellationReconciliationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String ATTEMPT = "reconcile-2026-11-19-01";

    @Autowired
    private FiscalDocumentStamper stamper;

    @Autowired
    private FiscalDocumentCancellationService cancellations;

    @Autowired
    private FiscalCancellationReconciliationService reconciliations;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StarterAccountingCatalogApplier accountingCatalog;

    private FiscalDocumentSeed seed;
    private UUID stamped;

    @BeforeEach
    void submitAPendingCancellation() {
        FiscalStampProviderTestConfiguration.PROVIDER.reset();
        FiscalStampProviderTestConfiguration.SEALER.reset();
        FiscalCancellationProviderTestConfiguration.PROVIDER.reset();
        FiscalCancellationProviderTestConfiguration.SEALER.reset();
        FiscalCancellationProviderTestConfiguration.RECONCILER.reset();

        seed = new FiscalDocumentSeed(jdbc, accountingCatalog).aCompletedSaleWithAFiscalProfile();
        stamped = stamper.stamp(seed.sale(), ATTEMPT).documentId();
        asCompany(seed.company());

        // The submission is the fixture: every case starts from the row T2 writes, so the counters this suite reads
        // belong to the reconciling double and its sealer alone.
        FiscalCancellationProviderTestConfiguration.PROVIDER.answerWith(
                InMemoryFiscalCancellationProvider.Answer.IN_PROCESS);
        cancellations.cancel(stamped, FiscalCancellationMotive.ERRORS_WITHOUT_RELATION, null);
        FiscalCancellationProviderTestConfiguration.SEALER.reset();
        FiscalCancellationProviderTestConfiguration.RECONCILER.reset();

        asCompany(seed.company());
    }

    @AfterEach
    void clearTheIdentity() {
        TenantScope.clear();
        SecurityContextHolder.clearContext();
    }

    // ─── accepted ───────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("S5/L2(e): an acceptance moves the pending row to CANCELLED — the one update the guard admits — "
            + "keeping the pointer and the motive, writing the code, the sentence and the settle stamp")
    void anAcceptanceSettlesThePendingRow() {
        var pending = pendingCancellation();
        var pendingId = (UUID) pending.get("id");
        var documentsBefore = documentCount();
        FiscalCancellationProviderTestConfiguration.RECONCILER.answeringStatus(new Outcome.Accepted(
                InMemoryFiscalCancellationReconciliationPort.ACCEPTED_STATUS_UUID,
                InMemoryFiscalCancellationReconciliationPort.ACCEPTED_ACUSE,
                InMemoryFiscalCancellationReconciliationPort.ACCEPTED_MESSAGE));

        var answer = reconciliations.reconcile(stamped);

        assertThat(FiscalCancellationProviderTestConfiguration.RECONCILER.statusInvocations())
                .as("S8: one question to the authority")
                .isEqualTo(1);
        assertThat(FiscalCancellationProviderTestConfiguration.RECONCILER.registerInvocations())
                .as("S8: an acceptance registers nothing")
                .isZero();
        assertThat(FiscalCancellationProviderTestConfiguration.RECONCILER.askedAbout())
                .as("L2(c): the UUID asked about is the pointed document's, reached through the pointer")
                .containsExactly(UUID.fromString(row(stamped).get("uuid_fiscal").toString()));

        var settled = row(pendingId);
        assertThat(settled.get("status"))
                .as("L2(e): the trigger admits exactly this transition out of CANCELLATION_PENDING")
                .isEqualTo("CANCELLED");
        assertThat(settled.get("cancellation_status_uuid"))
                .as("the authority's own code is what the row keeps")
                .isEqualTo(InMemoryFiscalCancellationReconciliationPort.ACCEPTED_STATUS_UUID);
        assertThat(settled.get("cancellation_message"))
                .isEqualTo(InMemoryFiscalCancellationReconciliationPort.ACCEPTED_MESSAGE);
        assertThat(settled.get("cancellation_settled_at"))
                .as("the pass that read the answer stamps the moment it read it, which is what a second pass skips")
                .isNotNull();
        assertThat(settled.get("cancels_fiscal_document_id")).isEqualTo(stamped);
        assertThat(settled.get("cancellation_motive")).isEqualTo(pending.get("cancellation_motive"));
        assertThat(settled.get("replaces_fiscal_document_id")).isEqualTo(pending.get("replaces_fiscal_document_id"));
        assertThat(settled.get("uuid_fiscal"))
                .as("L2(c): a cancellation row never claims the authority witnessed it")
                .isNull();
        assertThat(settled.get("stamped_at")).isNull();
        assertThat(documentCount()).as("the row moved; no row was added").isEqualTo(documentsBefore);
        assertThat(countByStatus("CANCELLED")).isEqualTo(1);
        assertThat(countByStatus("CANCELLATION_PENDING")).isZero();
        assertThat(row(stamped).get("status"))
                .as("the stamped document is untouched: a cancellation is remembered by its own row (V108)")
                .isEqualTo("STAMPED");

        assertThat(answer.settled()).isTrue();
        assertThat(answer.documentStatus()).isEqualTo("CANCELLED");
        assertThat(answer.authorityCode()).isEqualTo(InMemoryFiscalCancellationReconciliationPort.ACCEPTED_STATUS_UUID);
        assertThat(answer.cancelledDocumentId()).isEqualTo(stamped);
    }

    @Test
    @DisplayName("S6: a second pass over a settled acceptance writes nothing and asks the authority nothing")
    void aSecondPassOverAnAcceptanceAsksNothing() {
        FiscalCancellationProviderTestConfiguration.RECONCILER.answeringStatus(new Outcome.Accepted(
                InMemoryFiscalCancellationReconciliationPort.ACCEPTED_STATUS_UUID,
                InMemoryFiscalCancellationReconciliationPort.ACCEPTED_ACUSE,
                InMemoryFiscalCancellationReconciliationPort.ACCEPTED_MESSAGE));
        var first = reconciliations.reconcile(stamped);
        var settled = settledCancellationId();
        var beforeSecondPass = row(settled);

        var second = reconciliations.reconcile(stamped);

        assertThat(FiscalCancellationProviderTestConfiguration.RECONCILER.statusInvocations())
                .as("S6/S8: a second run changes nothing the first settled, and does not ask again")
                .isEqualTo(1);
        assertThat(second).isEqualTo(first);
        assertThat(row(settled))
                .as("and the row is byte-identical to the one the first pass left")
                .isEqualTo(beforeSecondPass);
    }

    // ─── rejected ───────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("S5: a 213 keeps the row pending, preserves the code and settles it by the stamp; a second pass "
            + "writes nothing and makes zero further status calls")
    void aRejectionIsSettledWhereItStands() {
        var pending = pendingCancellation();
        var pendingId = (UUID) pending.get("id");
        FiscalCancellationProviderTestConfiguration.RECONCILER.answeringStatus(new Outcome.Rejected(
                InMemoryFiscalCancellationReconciliationPort.REJECTED_STATUS_UUID,
                InMemoryFiscalCancellationReconciliationPort.REJECTED_MESSAGE));

        var answer = reconciliations.reconcile(stamped);

        var settled = row(pendingId);
        assertThat(settled.get("status"))
                .as("a refusal is not a cancellation: the document is still stamped and this row is not one")
                .isEqualTo("CANCELLATION_PENDING");
        assertThat(settled.get("cancellation_status_uuid"))
                .as("the authority's code is preserved, untranslated")
                .isEqualTo(InMemoryFiscalCancellationReconciliationPort.REJECTED_STATUS_UUID);
        assertThat(settled.get("cancellation_message"))
                .isEqualTo(InMemoryFiscalCancellationReconciliationPort.REJECTED_MESSAGE);
        assertThat(settled.get("cancellation_settled_at")).isNotNull();
        assertThat(countByStatus("CANCELLED")).isZero();
        assertThat(answer.settled()).isTrue();
        assertThat(answer.documentStatus()).isEqualTo("CANCELLATION_PENDING");

        var second = reconciliations.reconcile(stamped);

        assertThat(FiscalCancellationProviderTestConfiguration.RECONCILER.statusInvocations())
                .as("S6/S8: settled is the timestamp, and it is what lets the second run skip the authority")
                .isEqualTo(1);
        assertThat(second).isEqualTo(answer);
        assertThat(row(pendingId)).isEqualTo(settled);
    }

    // ─── still pending, and the not-registered transaction ──────────────────────────────────────────

    @Test
    @DisplayName("S5: a still-pending answer writes nothing at all — not the code, not the stamp — and the row stays "
            + "open for the next pass")
    void aStillPendingAnswerMovesNothing() {
        var pending = pendingCancellation();

        FiscalCancellationProviderTestConfiguration.RECONCILER.answeringStatus(new Outcome.StillPending(
                InMemoryFiscalCancellationReconciliationPort.IN_PROCESS_STATUS_UUID,
                InMemoryFiscalCancellationReconciliationPort.IN_PROCESS_MESSAGE));

        var answer = reconciliations.reconcile(stamped);

        assertThat(row((UUID) pending.get("id")))
                .as("an unknown or still-pending answer moves nothing")
                .isEqualTo(pending);
        assertThat(answer.settled()).isFalse();
        assertThat(answer.documentStatus()).isEqualTo("CANCELLATION_PENDING");
        assertThat(answer.authorityCode())
                .isEqualTo(InMemoryFiscalCancellationReconciliationPort.IN_PROCESS_STATUS_UUID);
    }

    @Test
    @DisplayName("S4: a 702 registers the request rebuilt from the row, writes nothing, and the next pass polls again")
    void aNotRegisteredTransactionIsRegisteredAgain() {
        var pending = pendingCancellation();
        FiscalCancellationProviderTestConfiguration.RECONCILER.answeringStatus(new Outcome.NoTransaction());

        var answer = reconciliations.reconcile(stamped);

        assertThat(FiscalCancellationProviderTestConfiguration.RECONCILER.registerInvocations())
                .as("S4: a 702 is the fact that makes the pass register the request")
                .isEqualTo(1);
        assertThat(FiscalCancellationProviderTestConfiguration.RECONCILER.statusInvocations())
                .isEqualTo(1);
        assertThat(row((UUID) pending.get("id")))
                .as("a registration is not an answer: nothing is written")
                .isEqualTo(pending);
        assertThat(answer.settled()).isFalse();

        var registered = FiscalCancellationProviderTestConfiguration.RECONCILER.lastRegistered();
        assertThat(registered.uuid())
                .isEqualTo(UUID.fromString(row(stamped).get("uuid_fiscal").toString()));
        assertThat(registered.requestXml())
                .as("the rebuilt request states the row's motive and the document's issuer, and it travels sealed")
                .contains("Motivo=\"" + pending.get("cancellation_motive") + "\"")
                .contains("RfcEmisor=\"" + row(stamped).get("emisor_rfc") + "\"")
                .contains(FiscalCancellationProviderTestConfiguration.InMemoryCfdiCancellationSealer.SIGNATURE);
        assertThat(FiscalCancellationProviderTestConfiguration.SEALER.invocations())
                .as("the same sealer the submission uses, asked once")
                .isEqualTo(1);
        assertThat(FiscalCancellationProviderTestConfiguration.SEALER.lastUnsignedXml())
                .doesNotContain(FiscalCancellationProviderTestConfiguration.InMemoryCfdiCancellationSealer.SIGNATURE);

        FiscalCancellationProviderTestConfiguration.RECONCILER.answeringStatus(new Outcome.StillPending(
                InMemoryFiscalCancellationReconciliationPort.IN_PROCESS_STATUS_UUID,
                InMemoryFiscalCancellationReconciliationPort.IN_PROCESS_MESSAGE));
        reconciliations.reconcile(stamped);

        assertThat(FiscalCancellationProviderTestConfiguration.RECONCILER.statusInvocations())
                .as("a registration writes no stamp, so the next pass asks the authority again")
                .isEqualTo(2);
        assertThat(FiscalCancellationProviderTestConfiguration.RECONCILER.registerInvocations())
                .as("and it does not register a second time")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("S5: a refused registration writes nothing and names the authority's code, and the row still says "
            + "it is waiting")
    void aRefusedRegistrationMovesNothing() {
        var pending = pendingCancellation();
        FiscalCancellationProviderTestConfiguration.RECONCILER.answeringStatus(new Outcome.NoTransaction());
        FiscalCancellationProviderTestConfiguration.RECONCILER.answeringRegistration(
                new Registration.Refused(List.of(new FiscalCancellationResult.Issue(
                        InMemoryFiscalCancellationReconciliationPort.REFUSED_CODE,
                        InMemoryFiscalCancellationReconciliationPort.REFUSED_MESSAGE))));

        var answer = reconciliations.reconcile(stamped);

        assertThat(row((UUID) pending.get("id"))).isEqualTo(pending);
        assertThat(answer.authorityCode()).isEqualTo(InMemoryFiscalCancellationReconciliationPort.REFUSED_CODE);
        assertThat(answer.message()).isEqualTo(InMemoryFiscalCancellationReconciliationPort.REFUSED_MESSAGE);
        assertThat(answer.settled()).isFalse();
        assertThat(answer.documentStatus())
                .as("do not invent a status: the row still says it is waiting")
                .isEqualTo("CANCELLATION_PENDING");
    }

    // ─── the refusal ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a document with no submitted cancellation is a 404 and asks the authority nothing")
    void aDocumentWithNoCancellationIs404() {
        var otherSale = seed.anotherCompletedSale();
        FiscalStampProviderTestConfiguration.PROVIDER.stampingUnder(UUID.randomUUID());
        var neverCancelled = stamper.stamp(otherSale, "reconcile-2026-11-19-02").documentId();

        assertThatThrownBy(() -> reconciliations.reconcile(neverCancelled))
                .isInstanceOf(FiscalCancellationNotFoundException.class)
                .hasMessageContaining(neverCancelled.toString());

        assertThat(FiscalCancellationProviderTestConfiguration.RECONCILER.statusInvocations())
                .isZero();
        assertThat(FiscalCancellationProviderTestConfiguration.RECONCILER.registerInvocations())
                .isZero();
    }

    // ─── the fixtures ───────────────────────────────────────────────────────────────────────────────

    /**
     * States one tenant for the three halves of the contract — the caller's identity, the scope the service reads and
     * the setting the row policy reads — exactly as the cancellation suite does it, and for the same reason.
     */
    private void asCompany(UUID companyId) {
        seed.authenticateAs(seed.store());
        TenantScope.set(companyId, null);
        TenantFixtures.stateTenantWithinTheOpenTransaction(companyId, jdbc);
    }

    private Map<String, Object> row(UUID documentId) {
        return jdbc.queryForMap("SELECT * FROM fiscal_document WHERE id = ?", documentId);
    }

    /** The one pending cancellation row of this company, read whole: every assertion below is about its columns. */
    private Map<String, Object> pendingCancellation() {
        return jdbc.queryForMap(
                "SELECT * FROM fiscal_document WHERE company_id = ? AND status = 'CANCELLATION_PENDING'",
                seed.company());
    }

    /** The one settled cancellation row of this company, whatever state the pass left it in. */
    private UUID settledCancellationId() {
        return jdbc.queryForObject(
                "SELECT id FROM fiscal_document WHERE company_id = ? AND cancellation_settled_at IS NOT NULL",
                (resultSet, rowNumber) -> resultSet.getObject(1, UUID.class),
                seed.company());
    }

    private long documentCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM fiscal_document WHERE company_id = ?", Long.class, seed.company());
    }

    private long countByStatus(String status) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM fiscal_document WHERE company_id = ? AND status = ?",
                Long.class,
                seed.company(),
                status);
    }
}
