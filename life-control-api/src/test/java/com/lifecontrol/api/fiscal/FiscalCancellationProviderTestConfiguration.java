package com.lifecontrol.api.fiscal;

import com.lifecontrol.api.fiscal.cancellation.CfdiCancellationSealer;
import com.lifecontrol.api.fiscal.cancellation.FiscalCancellationProvider;
import com.lifecontrol.api.fiscal.cancellation.FiscalCancellationReconciliationPort;
import com.lifecontrol.api.fiscal.cancellation.FiscalCancellationRequest;
import com.lifecontrol.api.fiscal.cancellation.FiscalCancellationResult;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The cancelling port's double, the sealer's and the reconciling port's, and the bean definitions that put them
 * behind their interfaces — {@code T8d}'s counterpart of {@link FiscalStampProviderTestConfiguration}, written the same
 * way and for the same reason: a hand-written implementation that keeps real state and counts real calls, so a case
 * asserts on what the service did rather than on Mockito argument captures.
 *
 * <p><b>Why the provider answers all three outcomes.</b> {@code L20}(c)–(d) split the authority's answer into
 * cancelled, in process and refused, and the whole point of the slice is which of the three writes a row: a double
 * that could only cancel would leave "in process writes nothing" unprovable, and "refused writes nothing" argued.
 * The outcome is stated by the case ({@link InMemoryFiscalCancellationProvider#answerWith}) and every call is counted,
 * which is how "the provider was never called" is an assertion and not a hope.</p>
 *
 * <p><b>Why the sealer is a double of this suite's own.</b> The real one reads a CSD, and this tree has no
 * certificate: what the service can be held to is that it asked for the seal of the <b>seal-less</b> rendering and
 * sent what the sealer answered. {@link InMemoryCfdiCancellationSealer} records both, and the signature it returns is
 * fixed markup — the XMLDSig itself is proven in {@code CsdCfdiCancellationSealerTest} and against the vendor's own
 * example in {@code VendorCancellationKnownAnswerTest}, where it can be.</p>
 *
 * <p><b>Why the instances are JVM-wide constants.</b> Spring caches one context per configuration for the whole run, so
 * a fresh instance per test method would be a bean the context cannot provide; {@link #reset()} on each case's
 * {@code @BeforeEach} is what makes the counters belong to the case that reads them.</p>
 */
@TestConfiguration
public class FiscalCancellationProviderTestConfiguration {

    /** The one double every context of this suite shares; reset by each case before it reads a counter. */
    public static final InMemoryFiscalCancellationProvider PROVIDER = new InMemoryFiscalCancellationProvider();

    /** The sealing double beside it, reset by each case like the provider. */
    public static final InMemoryCfdiCancellationSealer SEALER = new InMemoryCfdiCancellationSealer();

    /**
     * The reconciling port's double, reset by each case like the other two.
     *
     * <p>{@code T3} of {@code odd/tasks/fiscal-cancellation-reconciliation.md} asks the authority two different
     * questions — register, and what is the status — and {@code S8} of the same record asks for the two calls to be
     * <b>counted separately</b>: a double that counted them together could not tell "the pass registered nothing"
     * from "the pass asked nothing". It answers in the order a case states, which is what makes the second-pass case
     * ({@code S6}) observable at all.</p>
     */
    public static final InMemoryFiscalCancellationReconciliationPort RECONCILER =
            new InMemoryFiscalCancellationReconciliationPort();

    @Bean
    public FiscalCancellationProvider fakeFiscalCancellationProvider() {
        return PROVIDER;
    }

    @Bean
    public CfdiCancellationSealer fakeCfdiCancellationSealer() {
        return SEALER;
    }

    @Bean
    public FiscalCancellationReconciliationPort fakeFiscalCancellationReconciliationPort() {
        return RECONCILER;
    }

    /**
     * A reconciling port that answers in the order a case states and counts each call separately.
     *
     * <p>Three facts it has to make assertable: that a settled row is reconciled without asking anything
     * ({@code S6}), that a pass which only reads writes nothing ({@code S5}), and that a {@code NoTransaction} answer
     * registers the request exactly once and then lets the next pass ask for the status again — a registration
     * writes no settle stamp, so the row stays open by design.</p>
     */
    public static final class InMemoryFiscalCancellationReconciliationPort
            implements FiscalCancellationReconciliationPort {

        /** The code the authority answers when it accepted the cancellation: its own statement of the fact. */
        public static final String ACCEPTED_STATUS_UUID = "200";

        /** The receipt the authority returns on an acceptance. */
        public static final String ACCEPTED_ACUSE =
                "Kw3RSgj6Us8w5yqgxHtM15oVsyw0l7mnheJ1J0YyppGrv4z2bukZz43V4Dng8G3eyANJ+XZVIug1xeO5VBCA3Q==";

        /** The sentence the authority pairs with an acceptance. */
        public static final String ACCEPTED_MESSAGE = "La solicitud de cancelación fue aceptada por el SAT";

        /** The code the receiver's refusal arrives under. */
        public static final String REJECTED_STATUS_UUID = "213";

        /** The sentence the authority pairs with that refusal. */
        public static final String REJECTED_MESSAGE = "El receptor rechazó la cancelación del comprobante";

        /** The code that says the request is still being processed. */
        public static final String IN_PROCESS_STATUS_UUID = "211";

        /** The sentence the authority pairs with {@link #IN_PROCESS_STATUS_UUID}. */
        public static final String IN_PROCESS_MESSAGE = "La cancelación está en proceso";

        /** The code an account failure arrives under on the registration call. */
        public static final String REFUSED_CODE = "620";

        /** The sentence the authority pairs with {@link #REFUSED_CODE}. */
        public static final String REFUSED_MESSAGE = "No fue posible procesar la solicitud";

        private final AtomicInteger statusInvocations = new AtomicInteger();
        private final AtomicInteger registerInvocations = new AtomicInteger();
        private final Deque<Outcome> outcomes = new ArrayDeque<>();
        private final Deque<Registration> registrations = new ArrayDeque<>();
        private final List<UUID> askedAbout = new ArrayList<>();
        private final List<FiscalCancellationRequest> registered = new ArrayList<>();

        @Override
        public Registration register(FiscalCancellationRequest request) {
            registerInvocations.incrementAndGet();
            registered.add(request);
            return registrations.isEmpty()
                    ? new Registration.Registered(request.uuid().toString(), "Solicitud registrada")
                    : registrations.poll();
        }

        @Override
        public Outcome status(UUID transactionId) {
            statusInvocations.incrementAndGet();
            askedAbout.add(transactionId);
            if (outcomes.isEmpty()) {
                throw new IllegalStateException(
                        "the case stated no outcome for status call " + statusInvocations.get());
            }
            return outcomes.poll();
        }

        /** States what the next status call answers, in order. */
        public void answeringStatus(Outcome outcome) {
            outcomes.add(outcome);
        }

        /** States what the next registration answers. */
        public void answeringRegistration(Registration registration) {
            registrations.add(registration);
        }

        /** Clears the counters and the queue, so one case's evidence is not the previous case's. */
        public void reset() {
            statusInvocations.set(0);
            registerInvocations.set(0);
            outcomes.clear();
            registrations.clear();
            askedAbout.clear();
            registered.clear();
        }

        /** How many times the pass asked the authority about a transaction. */
        public int statusInvocations() {
            return statusInvocations.get();
        }

        /** How many times the pass registered a request. */
        public int registerInvocations() {
            return registerInvocations.get();
        }

        /** The UUIDs the pass asked about, in order. */
        public List<UUID> askedAbout() {
            return List.copyOf(askedAbout);
        }

        /** The last request the pass registered, or {@code null} when it registered none. */
        public FiscalCancellationRequest lastRegistered() {
            return registered.isEmpty() ? null : registered.get(registered.size() - 1);
        }
    }

    /**
     * A cancellation sealer that answers fixed markup and records the bytes it was asked to sign.
     *
     * <p>Not part of the interface's contract: {@link #invocations()} and {@link #lastUnsignedXml()} are the evidence
     * a case reads.</p>
     */
    public static final class InMemoryCfdiCancellationSealer implements CfdiCancellationSealer {

        /** The {@code Signature} element the double answers, in the namespace the builder writes verbatim. */
        public static final String SIGNATURE = "<Signature xmlns=\"http://www.w3.org/2000/09/xmldsig#\">"
                + "<SignedInfo><Reference URI=\"\"><DigestValue>Zml4ZWQ=</DigestValue></Reference></SignedInfo>"
                + "<SignatureValue>Zml4ZWQtY2FuY2VsYWNpb24=</SignatureValue></Signature>";

        private final List<String> unsignedDocuments = new ArrayList<>();

        @Override
        public String seal(String unsignedRequestXml) {
            unsignedDocuments.add(unsignedRequestXml);
            return SIGNATURE;
        }

        /** Clears what the last case recorded, so one case's evidence is not the previous case's. */
        public void reset() {
            unsignedDocuments.clear();
        }

        /** How many times a cancellation asked for a seal. */
        public int invocations() {
            return unsignedDocuments.size();
        }

        /** The seal-less request the service asked to have signed, or {@code null} when it never asked. */
        public String lastUnsignedXml() {
            return unsignedDocuments.isEmpty() ? null : unsignedDocuments.get(unsignedDocuments.size() - 1);
        }
    }

    /**
     * A cancelling provider that answers what it is told to answer and records every call.
     *
     * <p>It answers about the UUID the request names, because that is the authority's contract: an answer about
     * another document is a provider defect the real adapter refuses.</p>
     *
     * <p><b>Why a grant's code is settable.</b> {@code 201} and {@code 202} are one outcome with two codes —
     * <i>se ha cancelado con éxito</i> and <i>ya se había cancelado previamente</i> — and {@code V113} makes the code a
     * column of the row, so "the row keeps the authority's own answer" is only observable if the double can state the
     * second one. {@link #grantingUnder} is the same shape {@code FiscalStampProviderTestConfiguration}'s
     * {@code stampingUnder} uses, and the default stays {@code 201} so no earlier case reads a different code.</p>
     */
    public static final class InMemoryFiscalCancellationProvider implements FiscalCancellationProvider {

        /** The three outcomes {@code L20}(c)–(d) give a cancellation. */
        public enum Answer {
            CANCELLED,
            IN_PROCESS,
            REJECTED
        }

        /** The code the authority states when it cancelled: the one that means "cancelled". */
        public static final String CANCELLED_STATUS_UUID = "201";

        /** The sentence the authority pairs with {@link #CANCELLED_STATUS_UUID}. */
        public static final String CANCELLED_MESSAGE = "El folio se ha cancelado con éxito";

        /**
         * The code it states when the document was already cancelled: the same fact, said a second time.
         *
         * <p>{@code SolucionFactibleFiscalCancellationProvider} maps it to the same {@code Cancelled} outcome, which is
         * why the row has to keep the code and not the outcome.</p>
         */
        public static final String ALREADY_CANCELLED_STATUS_UUID = "202";

        /** The sentence the authority pairs with {@link #ALREADY_CANCELLED_STATUS_UUID}. */
        public static final String ALREADY_CANCELLED_MESSAGE = "El folio ya se había cancelado previamente";

        /** The code it states when the request is registered and not yet decided. */
        public static final String IN_PROCESS_STATUS_UUID = "211";

        /** The code the vendor's published example reaches for a document the SAT has not processed yet. */
        public static final String REJECTED_STATUS_UUID = "205";

        private final AtomicInteger invocations = new AtomicInteger();
        private final List<FiscalCancellationRequest> requests = new ArrayList<>();
        private Answer answer = Answer.CANCELLED;
        private String grantedStatusUuid = CANCELLED_STATUS_UUID;
        private String grantedMessage = CANCELLED_MESSAGE;

        @Override
        public FiscalCancellationResult cancel(FiscalCancellationRequest request) {
            invocations.incrementAndGet();
            requests.add(request);
            return switch (answer) {
                case CANCELLED ->
                    new FiscalCancellationResult.Cancelled(request.uuid(), grantedStatusUuid, grantedMessage);
                case IN_PROCESS ->
                    new FiscalCancellationResult.InProcess(
                            request.uuid(), IN_PROCESS_STATUS_UUID, "La cancelación está en proceso");
                case REJECTED ->
                    new FiscalCancellationResult.Rejected(
                            List.of(
                                    new FiscalCancellationResult.Issue(
                                            REJECTED_STATUS_UUID,
                                            "El SAT no ha procesado la recepción de este comprobante, por favor espere al menos 24 horas")));
            };
        }

        /** Makes the next answers the outcome the case is about. */
        public void answerWith(Answer next) {
            this.answer = next;
        }

        /**
         * Makes the next granted answers state this code and sentence instead of the {@code 201} pair.
         *
         * <p>The authority has two ways to say "cancelled" and {@code V113} stores which one it said, so a case that
         * only ever heard {@code 201} could not tell a stored code from a constant this tree wrote.</p>
         */
        public void grantingUnder(String statusUuid, String message) {
            this.grantedStatusUuid = statusUuid;
            this.grantedMessage = message;
        }

        /** Clears the counters and the outcome, so one case's evidence is not the previous case's. */
        public void reset() {
            invocations.set(0);
            requests.clear();
            answer = Answer.CANCELLED;
            grantedStatusUuid = CANCELLED_STATUS_UUID;
            grantedMessage = CANCELLED_MESSAGE;
        }

        /** How many times the service called the authority. */
        public int invocations() {
            return invocations.get();
        }

        /** The last request the service sent, or {@code null} when it never called. */
        public FiscalCancellationRequest lastRequest() {
            return requests.isEmpty() ? null : requests.get(requests.size() - 1);
        }
    }
}
