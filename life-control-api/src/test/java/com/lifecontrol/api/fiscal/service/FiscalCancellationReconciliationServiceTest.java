package com.lifecontrol.api.fiscal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.common.tenant.TenantScope;
import com.lifecontrol.api.exception.ResourceNotFoundException;
import com.lifecontrol.api.fiscal.cancellation.CfdiCancellationSealer;
import com.lifecontrol.api.fiscal.cancellation.FiscalCancellationReconciliationPort;
import com.lifecontrol.api.fiscal.cancellation.FiscalCancellationReconciliationPort.Outcome;
import com.lifecontrol.api.fiscal.cancellation.FiscalCancellationReconciliationPort.Registration;
import com.lifecontrol.api.fiscal.cancellation.FiscalCancellationRequest;
import com.lifecontrol.api.fiscal.cancellation.FiscalCancellationResult;
import com.lifecontrol.api.fiscal.dto.FiscalCancellationReconciliation;
import com.lifecontrol.api.fiscal.exception.FiscalCancellationNotConfiguredException;
import com.lifecontrol.api.fiscal.exception.FiscalCancellationNotFoundException;
import com.lifecontrol.api.fiscal.exception.FiscalDocumentNotStampedException;
import com.lifecontrol.api.fiscal.model.FiscalCancellationMotive;
import com.lifecontrol.api.fiscal.model.FiscalDocument;
import com.lifecontrol.api.fiscal.repository.FiscalDocumentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * The reconciliation service's decisions, counted ({@code T3} of
 * {@code odd/tasks/fiscal-cancellation-reconciliation.md}, {@code S5}, {@code S6} and {@code S8}).
 *
 * <p><b>Why the port is a recording double and the repository a mock.</b> {@code S8} asks for provider calls and row
 * writes to be <b>counted</b> rather than argued: the double counts {@code status} and {@code register} separately and
 * answers in the sequence a case needs, so "the second pass asked nothing" is a number. The repository is a mock
 * because what this class must not do is decide anything the database decides — the guard the settlement passes
 * through is pinned against real PostgreSQL in {@code FiscalCancellationReconciliationIntegrationTest}, and here
 * {@code saveAndFlush} invocations are the row count.</p>
 *
 * <p><b>Why the rows are real entities and not mocks.</b> A reconciliation mutates the row it read, and the answer it
 * gives quotes that row: a mocked entity would let the service's placement of a code go unobserved. The rows are built
 * by the same factories the submission uses ({@link FiscalDocument#pendingCancellationOf} and
 * {@link FiscalDocument#cancellationOf}), so a case cannot pass on a row shape production never writes.</p>
 *
 * <p><b>The clock is fixed, and one read is the rule.</b> The settle stamp and the rebuilt request's
 * {@code Fecha} both come from this clock; a fixed instant makes the written value exact and makes a second read
 * harmless, which is why the assertions read the instant rather than merely "not null".</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Fiscal cancellation reconciliation service (S5, S6, S8)")
class FiscalCancellationReconciliationServiceTest {

    private static final UUID COMPANY = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SALE = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID STAMPED = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID PENDING = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID FISCAL_UUID = UUID.fromString("6DB5A9A0-8A41-4481-AEA4-FBFFADBC96DA");

    private static final String IN_PROCESS = "211";
    private static final String IN_PROCESS_MESSAGE = "La cancelación está en proceso";
    private static final String ACCEPTED_CODE = "200";
    private static final String ACCEPTED_MESSAGE = "La cancelación fue aceptada por el SAT";
    private static final String ACUSE = "Kw3RSgj6Us8w5yqgxHtM15oVsyw0l7mnheJ1J0YyppG";
    private static final String REJECTED_CODE = "213";
    private static final String REJECTED_MESSAGE = "El receptor rechazó la cancelación";
    private static final String REFUSED_CODE = "620";
    private static final String REFUSED_MESSAGE = "No fue posible procesar la solicitud";

    private static final Instant NOW = Instant.parse("2026-11-19T18:00:00Z");
    private static final OffsetDateTime SETTLED_AT = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);

    private static final String SIGNATURE = "<Signature>Zml4ZWQ=</Signature>";

    @Mock
    private FiscalDocumentRepository repository;

    @Mock
    private FiscalDocumentStoreScope storeScope;

    private RecordingPort port;
    private RecordingSealer sealer;
    private FiscalCancellationReconciliationService service;
    private FiscalDocument stamped;
    private FiscalDocument pending;

    @BeforeEach
    void seedAPendingCancellation() {
        port = new RecordingPort();
        sealer = new RecordingSealer();
        service = newService(
                providerOf(FiscalCancellationReconciliationPort.class, port),
                providerOf(CfdiCancellationSealer.class, sealer));

        stamped = stampedDocument(STAMPED);
        pending = FiscalDocument.pendingCancellationOf(
                stamped,
                FiscalCancellationMotive.ERRORS_WITHOUT_RELATION,
                null,
                "suite:fiscal",
                IN_PROCESS,
                "in process");
        identify(pending, PENDING);

        TenantScope.set(COMPANY, null);
        lenient()
                .when(repository.saveAndFlush(any(FiscalDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient()
                .when(repository.findByCompanyIdAndCancelsFiscalDocumentId(COMPANY, STAMPED))
                .thenReturn(Optional.of(pending));
        lenient().when(repository.findByCompanyIdAndId(COMPANY, STAMPED)).thenReturn(Optional.of(stamped));
    }

    @AfterEach
    void clearTheScope() {
        TenantScope.clear();
    }

    // ─── accepted ───────────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("An acceptance")
    class AnAcceptance {

        @Test
        @DisplayName("S5: moves the pending row to CANCELLED with the code, the sentence and the settle stamp, and "
                + "keeps the pointer, the motive and the substituting row")
        void settlesTheRowAsCancelled() {
            port.answeringStatus(new Outcome.Accepted(ACCEPTED_CODE, ACUSE, ACCEPTED_MESSAGE));

            var answer = service.reconcile(STAMPED);

            assertThat(port.statusInvocations())
                    .as("S8: one question to the authority and not one per row")
                    .isEqualTo(1);
            assertThat(port.registerInvocations())
                    .as("S8: an acceptance is not a registration")
                    .isZero();
            verify(repository, times(1)).saveAndFlush(pending);

            assertThat(pending.getStatus()).isEqualTo(FiscalDocument.STATUS_CANCELLED);
            assertThat(pending.getCancellationStatusUuid()).isEqualTo(ACCEPTED_CODE);
            assertThat(pending.getCancellationMessage()).isEqualTo(ACCEPTED_MESSAGE);
            assertThat(pending.getCancellationSettledAt())
                    .as("the pass that read the answer stamps the moment it read it")
                    .isEqualTo(SETTLED_AT);
            assertThat(pending.getCancelsFiscalDocumentId())
                    .as("L2(b): the row keeps cancelling the same document")
                    .isEqualTo(STAMPED);
            assertThat(pending.getCancellationMotive()).isEqualTo("02");
            assertThat(pending.getUuidFiscal())
                    .as("L2(c): the accepted row still carries no UUID of its own")
                    .isNull();
            assertThat(pending.getStampedAt()).isNull();

            assertThat(answer)
                    .as("the answer quotes the row the pass wrote")
                    .isEqualTo(new FiscalCancellationReconciliation(
                            STAMPED, FiscalDocument.STATUS_CANCELLED, ACCEPTED_CODE, ACCEPTED_MESSAGE, true));
        }

        @Test
        @DisplayName("S6: a settled acceptance answers from the row and asks the authority nothing")
        void asksNothingOnceSettled() {
            port.answeringStatus(new Outcome.Accepted(ACCEPTED_CODE, ACUSE, ACCEPTED_MESSAGE));
            var first = service.reconcile(STAMPED);

            var second = service.reconcile(STAMPED);

            assertThat(port.statusInvocations())
                    .as("a second run changes nothing it already settled (S1), and asks nothing")
                    .isEqualTo(1);
            verify(repository, times(1)).saveAndFlush(any());
            assertThat(second).isEqualTo(first);
        }
    }

    // ─── rejected ───────────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("A rejection")
    class ARejection {

        @Test
        @DisplayName("S5: keeps the row pending, keeps the authority's 213 and settles it by the stamp")
        void settlesTheRowWhereItStands() {
            port.answeringStatus(new Outcome.Rejected(REJECTED_CODE, REJECTED_MESSAGE));

            var answer = service.reconcile(STAMPED);

            verify(repository, times(1)).saveAndFlush(pending);
            assertThat(pending.getStatus())
                    .as("a refusal is not a cancellation: promoting the row would state what the authority denied")
                    .isEqualTo(FiscalDocument.STATUS_CANCELLATION_PENDING);
            assertThat(pending.getCancellationStatusUuid())
                    .as("the authority's code is preserved, untranslated")
                    .isEqualTo(REJECTED_CODE);
            assertThat(pending.getCancellationMessage()).isEqualTo(REJECTED_MESSAGE);
            assertThat(pending.getCancellationSettledAt()).isEqualTo(SETTLED_AT);
            assertThat(port.statusInvocations()).isEqualTo(1);
            assertThat(answer)
                    .isEqualTo(new FiscalCancellationReconciliation(
                            STAMPED,
                            FiscalDocument.STATUS_CANCELLATION_PENDING,
                            REJECTED_CODE,
                            REJECTED_MESSAGE,
                            true));
        }

        @Test
        @DisplayName("S6/S8: a second run writes nothing and makes zero further status calls")
        void aSecondRunWritesNothing() {
            port.answeringStatus(new Outcome.Rejected(REJECTED_CODE, REJECTED_MESSAGE));
            var first = service.reconcile(STAMPED);

            var second = service.reconcile(STAMPED);

            assertThat(port.statusInvocations())
                    .as("the settle stamp is what lets the second run skip the authority")
                    .isEqualTo(1);
            verify(repository, times(1)).saveAndFlush(any());
            assertThat(second).isEqualTo(first);
        }
    }

    // ─── still pending, and the not-registered transaction ──────────────────────────────────────────

    @Nested
    @DisplayName("A still-pending answer")
    class AStillPendingAnswer {

        @Test
        @DisplayName("S5: writes nothing at all, and answers what this pass read")
        void writesNothing() {
            port.answeringStatus(new Outcome.StillPending(IN_PROCESS, IN_PROCESS_MESSAGE));

            var answer = service.reconcile(STAMPED);

            verify(repository, never()).saveAndFlush(any());
            assertThat(pending.getCancellationSettledAt()).isNull();
            assertThat(pending.getStatus()).isEqualTo(FiscalDocument.STATUS_CANCELLATION_PENDING);
            assertThat(pending.getCancellationStatusUuid())
                    .as("the submission's own code stays: nothing new was written")
                    .isEqualTo(IN_PROCESS);
            assertThat(port.statusInvocations()).isEqualTo(1);
            assertThat(answer)
                    .as("an unknown or still-pending answer moves nothing")
                    .isEqualTo(new FiscalCancellationReconciliation(
                            STAMPED,
                            FiscalDocument.STATUS_CANCELLATION_PENDING,
                            IN_PROCESS,
                            IN_PROCESS_MESSAGE,
                            false));
        }
    }

    @Nested
    @DisplayName("A transaction the authority does not hold")
    class ANoTransaction {

        @BeforeEach
        void theAuthorityHoldsNoTransaction() {
            port.answeringStatus(new Outcome.NoTransaction());
        }

        @Test
        @DisplayName("S4: registers the sealed request once, writes nothing and answers still pending")
        void registersTheRequestAndWritesNothing() {
            port.answeringRegistration(new Registration.Registered(FISCAL_UUID.toString(), "Solicitud registrada"));

            var answer = service.reconcile(STAMPED);

            assertThat(port.statusInvocations()).isEqualTo(1);
            assertThat(port.registerInvocations()).isEqualTo(1);
            verify(repository, never()).saveAndFlush(any());
            assertThat(pending.getCancellationSettledAt()).isNull();
            assertThat(answer.settled()).isFalse();
            assertThat(answer.documentStatus()).isEqualTo(FiscalDocument.STATUS_CANCELLATION_PENDING);
            assertThat(answer.authorityCode())
                    .as("nothing was written, so the answer quotes the code the row already carries")
                    .isEqualTo(IN_PROCESS);

            assertThat(sealer.invocations())
                    .as("the request is sealed once, exactly as the submission seals it")
                    .isEqualTo(1);
            assertThat(sealer.lastUnsigned())
                    .as("the seal covers the seal-less rendering, so a signature can be computed from it")
                    .contains("<Cancelacion")
                    .doesNotContain(ACUSE);
            assertThat(port.lastRegistered()).isNotNull();
            assertThat(port.lastRegistered().uuid())
                    .as("the document the authority is asked about keeps its own UUID through the pointer")
                    .isEqualTo(FISCAL_UUID);
            assertThat(port.lastRegistered().requestXml())
                    .as("what travels is the sealed rendering")
                    .contains(SIGNATURE)
                    .contains("RfcEmisor=\"SFE0807172W8\"")
                    .contains("Motivo=\"02\"");
        }

        @Test
        @DisplayName("S6: after a registration the next pass polls the status again and still writes nothing")
        void theNextPassPollsAgain() {
            port.answeringRegistration(new Registration.Registered(FISCAL_UUID.toString(), "Solicitud registrada"));
            service.reconcile(STAMPED);

            port.answeringStatus(new Outcome.StillPending(IN_PROCESS, IN_PROCESS_MESSAGE));
            var second = service.reconcile(STAMPED);

            assertThat(port.statusInvocations())
                    .as("a registration writes no stamp, so the row is still open and the next pass asks again")
                    .isEqualTo(2);
            assertThat(port.registerInvocations())
                    .as("and it does not register a second time: the first pass did")
                    .isEqualTo(1);
            verify(repository, never()).saveAndFlush(any());
            assertThat(second.settled()).isFalse();
        }

        @Test
        @DisplayName("S5/S8: a refused registration writes nothing, names the code and moves nothing")
        void answersTheRefusalWithoutMovingTheRow() {
            port.answeringRegistration(new Registration.Refused(
                    List.of(new FiscalCancellationResult.Issue(REFUSED_CODE, REFUSED_MESSAGE))));

            var answer = service.reconcile(STAMPED);

            assertThat(port.registerInvocations()).isEqualTo(1);
            verify(repository, never()).saveAndFlush(any());
            assertThat(pending.getStatus())
                    .as("nothing moves — not the state, not the answer columns")
                    .isEqualTo(FiscalDocument.STATUS_CANCELLATION_PENDING);
            assertThat(pending.getCancellationStatusUuid()).isEqualTo(IN_PROCESS);
            assertThat(pending.getCancellationSettledAt()).isNull();
            assertThat(answer.authorityCode())
                    .as("the operator sees why, and the code is the authority's own")
                    .isEqualTo(REFUSED_CODE);
            assertThat(answer.message()).isEqualTo(REFUSED_MESSAGE);
            assertThat(answer.settled()).isFalse();
            assertThat(answer.documentStatus())
                    .as("do not invent a status: the row still says it is waiting")
                    .isEqualTo(FiscalDocument.STATUS_CANCELLATION_PENDING);
        }
    }

    // ─── the rows that are already finished ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("A row that is already finished")
    class AnAlreadyFinishedRow {

        @Test
        @DisplayName("S6: a CANCELLED row is answered from the row and the authority is never called")
        void answeredWithoutTheProvider() {
            var cancelled = FiscalDocument.cancellationOf(
                    stampedDocument(STAMPED),
                    FiscalCancellationMotive.ERRORS_WITHOUT_RELATION,
                    null,
                    "suite:fiscal",
                    "201",
                    "El folio se ha cancelado con éxito");
            identify(cancelled, PENDING);
            when(repository.findByCompanyIdAndCancelsFiscalDocumentId(COMPANY, STAMPED))
                    .thenReturn(Optional.of(cancelled));

            var answer = service.reconcile(STAMPED);

            assertThat(port.statusInvocations()).isZero();
            assertThat(port.registerInvocations()).isZero();
            verify(repository, never()).saveAndFlush(any());
            assertThat(answer)
                    .isEqualTo(new FiscalCancellationReconciliation(
                            STAMPED,
                            FiscalDocument.STATUS_CANCELLED,
                            "201",
                            "El folio se ha cancelado con éxito",
                            true));
        }
    }

    // ─── the refusals ───────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("What refuses the pass")
    class WhatRefusesIt {

        @Test
        @DisplayName("S5: a document with no submitted cancellation is a 404 and asks nothing")
        void noCancellationRowIsA404() {
            when(repository.findByCompanyIdAndCancelsFiscalDocumentId(COMPANY, STAMPED))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.reconcile(STAMPED))
                    .isInstanceOf(FiscalCancellationNotFoundException.class)
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(STAMPED.toString())
                    .hasMessageContaining("No cancellation was submitted");

            assertThat(port.statusInvocations()).isZero();
            assertThat(port.registerInvocations()).isZero();
        }

        @Test
        @DisplayName("the tenant is the scope's, and an absent one is the cancellation service's own 404")
        void anAbsentScopeIsTheSame404() {
            TenantScope.clear();

            assertThatThrownBy(() -> service.reconcile(STAMPED)).isInstanceOf(FiscalDocumentNotStampedException.class);

            assertThat(port.statusInvocations()).isZero();
        }

        @Test
        @DisplayName("a deployment with no reconciler answers the named 503 before anything is read as pending")
        void aDeploymentWithoutThePortAnswersThe503() {
            var withoutPort = newService(
                    providerOf(FiscalCancellationReconciliationPort.class, null),
                    providerOf(CfdiCancellationSealer.class, sealer));

            assertThatThrownBy(() -> withoutPort.reconcile(STAMPED))
                    .isInstanceOf(FiscalCancellationNotConfiguredException.class)
                    .hasMessageContaining("FiscalCancellationReconciliationPort");

            verify(repository, never()).saveAndFlush(any());
        }
    }

    // ─── the fixtures ───────────────────────────────────────────────────────────────────────────────

    private FiscalCancellationReconciliationService newService(
            ObjectProvider<FiscalCancellationReconciliationPort> portProvider,
            ObjectProvider<CfdiCancellationSealer> sealerProvider) {
        return new FiscalCancellationReconciliationService(
                repository, storeScope, portProvider, sealerProvider, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** A provider over one bean — or over none, which is the absence the service refuses by name. */
    private static <T> ObjectProvider<T> providerOf(Class<T> type, T bean) {
        var beans = new StaticListableBeanFactory(bean == null ? Map.of() : Map.of("bean", bean));
        return beans.getBeanProvider(type);
    }

    /**
     * One stamped document with the fields a cancellation copies.
     *
     * <p>The id is set by reflection because {@code @GeneratedValue} leaves no setter, and a row without an id cannot
     * be the {@code cancels_fiscal_document_id} of the row under test.</p>
     */
    private static FiscalDocument stampedDocument(UUID id) {
        var document = new FiscalDocument();
        document.setCompanyId(COMPANY);
        document.setSourceType("SALES_ORDER");
        document.setSourceId(SALE);
        document.setStatus(FiscalDocument.STATUS_STAMPED);
        document.setUuidFiscal(FISCAL_UUID);
        document.setStampedAt(OffsetDateTime.parse("2026-11-19T10:00:00-06:00"));
        document.setEmisorRfc("SFE0807172W8");
        document.setEmisorName("Fiscal Stamp Issuer SA de CV");
        document.setEmisorRegimen("601");
        document.setReceptorRfc("RECE010101AB1");
        document.setReceptorName("Fiscal Stamp Receiver SA de CV");
        document.setReceptorRegimen("612");
        document.setReceptorZipCode("44100");
        document.setReceptorUsoCfdi("G03");
        document.setLugarExpedicion("06700");
        document.setCurrency("MXN");
        document.setSubtotal(new java.math.BigDecimal("100.0000"));
        document.setDiscountTotal(new java.math.BigDecimal("0.0000"));
        document.setTaxTransferred(new java.math.BigDecimal("16.0000"));
        document.setTaxWithheld(new java.math.BigDecimal("0.0000"));
        document.setTotalAmount(new java.math.BigDecimal("116.0000"));
        identify(document, id);
        return document;
    }

    private static void identify(FiscalDocument document, UUID id) {
        try {
            var field = FiscalDocument.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(document, id);
        } catch (ReflectiveOperationException cannotIdentify) {
            throw new IllegalStateException("a fiscal document fixture needs its id", cannotIdentify);
        }
    }

    /**
     * A reconciliation port that counts both calls and answers in the order a case states.
     *
     * <p>The queue is what makes "the second pass polls again" observable: a single field would answer the same thing
     * to every call and the case could not tell a second question from none.</p>
     */
    private static final class RecordingPort implements FiscalCancellationReconciliationPort {

        private final Deque<Outcome> outcomes = new ArrayDeque<>();
        private final Deque<Registration> registrations = new ArrayDeque<>();
        private final List<UUID> statuses = new ArrayList<>();
        private final List<FiscalCancellationRequest> registered = new ArrayList<>();

        @Override
        public Registration register(FiscalCancellationRequest request) {
            registered.add(request);
            return registrations.isEmpty()
                    ? new Registration.Registered(request.uuid().toString(), "Solicitud registrada")
                    : registrations.poll();
        }

        @Override
        public Outcome status(UUID transactionId) {
            statuses.add(transactionId);
            if (outcomes.isEmpty()) {
                throw new IllegalStateException("the case stated no outcome for this status call");
            }
            return outcomes.poll();
        }

        void answeringStatus(Outcome outcome) {
            outcomes.add(outcome);
        }

        void answeringRegistration(Registration registration) {
            registrations.add(registration);
        }

        int statusInvocations() {
            return statuses.size();
        }

        int registerInvocations() {
            return registered.size();
        }

        FiscalCancellationRequest lastRegistered() {
            return registered.isEmpty() ? null : registered.get(registered.size() - 1);
        }
    }

    /** The sealer's double, recording what it was asked to sign — the submission's sealer, one pass over. */
    private static final class RecordingSealer implements CfdiCancellationSealer {

        private final List<String> unsigned = new ArrayList<>();

        @Override
        public String seal(String unsignedRequestXml) {
            unsigned.add(unsignedRequestXml);
            return SIGNATURE;
        }

        int invocations() {
            return unsigned.size();
        }

        String lastUnsigned() {
            return unsigned.isEmpty() ? null : unsigned.get(unsigned.size() - 1);
        }
    }
}
