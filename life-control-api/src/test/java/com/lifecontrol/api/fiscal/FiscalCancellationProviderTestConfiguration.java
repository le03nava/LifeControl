package com.lifecontrol.api.fiscal;

import com.lifecontrol.api.fiscal.cancellation.CfdiCancellationSealer;
import com.lifecontrol.api.fiscal.cancellation.FiscalCancellationProvider;
import com.lifecontrol.api.fiscal.cancellation.FiscalCancellationRequest;
import com.lifecontrol.api.fiscal.cancellation.FiscalCancellationResult;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The cancelling port's double and the sealer's, and the bean definitions that put them behind their interfaces —
 * {@code T8d}'s counterpart of {@link FiscalStampProviderTestConfiguration}, written the same way and for the same
 * reason: a hand-written implementation that keeps real state and counts real calls, so a case asserts on what the
 * service did rather than on Mockito argument captures.
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

    @Bean
    public FiscalCancellationProvider fakeFiscalCancellationProvider() {
        return PROVIDER;
    }

    @Bean
    public CfdiCancellationSealer fakeCfdiCancellationSealer() {
        return SEALER;
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

        /** The code it states when the request is registered and not yet decided. */
        public static final String IN_PROCESS_STATUS_UUID = "211";

        /** The code the vendor's published example reaches for a document the SAT has not processed yet. */
        public static final String REJECTED_STATUS_UUID = "205";

        private final AtomicInteger invocations = new AtomicInteger();
        private final List<FiscalCancellationRequest> requests = new ArrayList<>();
        private Answer answer = Answer.CANCELLED;

        @Override
        public FiscalCancellationResult cancel(FiscalCancellationRequest request) {
            invocations.incrementAndGet();
            requests.add(request);
            return switch (answer) {
                case CANCELLED ->
                    new FiscalCancellationResult.Cancelled(
                            request.uuid(), CANCELLED_STATUS_UUID, "El folio se ha cancelado con éxito");
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

        /** Clears the counters and the outcome, so one case's evidence is not the previous case's. */
        public void reset() {
            invocations.set(0);
            requests.clear();
            answer = Answer.CANCELLED;
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
