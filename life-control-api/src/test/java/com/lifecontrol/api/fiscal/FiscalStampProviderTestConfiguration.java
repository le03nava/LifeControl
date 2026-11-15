package com.lifecontrol.api.fiscal;

import com.lifecontrol.api.fiscal.model.IssuerCertificate;
import com.lifecontrol.api.fiscal.seal.CfdiSealer;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The fiscal stamping port's double, and the bean definition that puts it behind it — the
 * {@code InMemoryIdentityProvider} shape: a hand-written implementation of the port that keeps real state and
 * counts real calls, so a case asserts on what the attempt did rather than on Mockito argument captures.
 *
 * <p><b>Why the port needs a double at all.</b> {@code T7} is the adapter with credentials, a sandbox and a
 * vendor's catalogue; this slice has no provider by design, so the only honest way to prove "stamps once",
 * "calls zero times on a replay" and "keeps the bytes the authority returned" is a provider this suite controls.
 * The answer it gives is deliberately <b>additive</b> — the request's XML with a {@code TimbreFiscalDigital}
 * appended — because the point of {@code S10} is that the artefact is the provider's rendering and not this
 * tree's, and a double that returned the request unchanged could not tell the two apart.</p>
 *
 * <p><b>Why it is a {@code @TestConfiguration} bean and not a static field read directly.</b> The attempt asks
 * the context for the port when it stamps ({@code ObjectProvider}), exactly as it will in delivery, so the
 * double has to be a bean. The instance is a JVM-wide constant because Spring caches one context for the run and
 * a fresh instance per test method would be a bean the context cannot provide; {@link #reset()} at each test's
 * {@code @BeforeEach} is what makes the counters belong to the case that reads them.</p>
 *
 * <p><b>Why the sealer is a double of this suite's own ({@code T7b}).</b> The stamp asks for the seal of the
 * bytes it just built, so the port is no longer the only collaborator a stamp needs. The real sealer is a
 * certificate and a private key; this suite has neither, and the two facts it does need to observe are that the
 * sealer was asked to sign the <b>seal-less</b> rendering and that the seal it answered is what travels in
 * {@code Sello}. {@link InMemoryCfdiSealer} records both, and it answers a fixed seal rather than a real
 * signature — the signature is proven where it can be, in {@code FiscalCsdSealerTest}, against a generated
 * key pair.</p>
 */
@TestConfiguration
public class FiscalStampProviderTestConfiguration {

    /** The one double every context of this slice shares; reset by each test before it reads a counter. */
    public static final InMemoryFiscalStampProvider PROVIDER = new InMemoryFiscalStampProvider();

    /** The sealing double beside it, reset by each test's {@code @BeforeEach} like the provider. */
    public static final InMemoryCfdiSealer SEALER = new InMemoryCfdiSealer();

    @Bean
    public FiscalStampProvider fakeFiscalStampProvider() {
        return PROVIDER;
    }

    @Bean
    public CfdiSealer fakeCfdiSealer() {
        return SEALER;
    }

    /**
     * The seal a stamp states, as the double produces it: fixed, so a case can compare the document with a
     * literal instead of with a second signature.
     */
    public static final class InMemoryCfdiSealer implements CfdiSealer {

        /** The certificate facts every stamp of this slice writes; nothing depends on their contents. */
        public static final IssuerCertificate CERTIFICATE =
                new IssuerCertificate("30001000000400002434", "MIIBc2VhbA==");

        /** The seal the double answers, which is what {@code Sello} must carry. */
        public static final String SELLO = "c2VsbG8tY2Zk";

        private final List<String> unsignedDocuments = new ArrayList<>();

        @Override
        public IssuerCertificate certificate() {
            return CERTIFICATE;
        }

        @Override
        public String seal(String unsignedXml) {
            unsignedDocuments.add(unsignedXml);
            return SELLO;
        }

        /** Clears what the last case recorded, so one case's evidence is not the previous case's. */
        public void reset() {
            unsignedDocuments.clear();
        }

        /** How many times the stamp asked for a seal. */
        public int invocations() {
            return unsignedDocuments.size();
        }

        /** The seal-less rendering the stamp asked to have signed, or {@code null} when it never asked. */
        public String lastUnsignedXml() {
            return unsignedDocuments.isEmpty() ? null : unsignedDocuments.get(unsignedDocuments.size() - 1);
        }
    }

    /**
     * A stamping provider that answers what it is told to answer and records every call.
     *
     * <p>Not part of the port's contract: {@link #invocations()} and {@link #lastRequest()} are the evidence a
     * case reads, exactly as {@code InMemoryIdentityProvider}'s helpers are.</p>
     */
    public static final class InMemoryFiscalStampProvider implements FiscalStampProvider {

        /** The authority's UUID every stamped answer states by default: fixed, so a case compares a row with a literal. */
        public static final UUID STAMPED_UUID = UUID.fromString("11111111-2222-3333-4444-555555555555");

        /** The instant every stamped answer states: fixed, so a case compares the stored value with a literal. */
        public static final OffsetDateTime STAMPED_AT = OffsetDateTime.parse("2026-11-15T10:15:30-06:00");

        private final AtomicInteger invocations = new AtomicInteger();
        private final List<FiscalStampRequest> requests = new ArrayList<>();
        private final List<String> answers = new ArrayList<>();
        private List<FiscalStampResult.Issue> rejection = List.of();
        private UUID stampedUuid = STAMPED_UUID;

        @Override
        public FiscalStampResult stamp(FiscalStampRequest request) {
            invocations.incrementAndGet();
            requests.add(request);
            if (!rejection.isEmpty()) {
                return new FiscalStampResult.Rejected(rejection);
            }
            var xml = answerTo(request);
            answers.add(xml);
            return new FiscalStampResult.Stamped(
                    stampedUuid,
                    STAMPED_AT,
                    "AAA010101AAA",
                    "30001000000400002434",
                    "c2VsbG8tY2Zk",
                    "c2VsbG8tc2F0",
                    xml);
        }

        /**
         * Makes the next answers state this fiscal UUID instead of {@link #STAMPED_UUID}.
         *
         * <p>The authority issues a UUID per document and {@code uq_fiscal_document_uuid_fiscal} is unique across the
         * table, so a suite that stamps <b>two</b> documents in one transaction — the substitution, which needs a
         * second stamped document of the same company — has to give them two UUIDs. A suite that stamps one never
         * calls this, which is why the fixed one stays the default and every earlier assertion still reads a
         * literal.</p>
         */
        public void stampingUnder(UUID uuid) {
            this.stampedUuid = uuid;
        }

        /** Makes the next answers a rejection with exactly these issues. */
        public void reject(FiscalStampResult.Issue... issues) {
            rejection = List.of(issues);
        }

        /** Clears the counters and the mode, so one case's evidence is not the previous case's. */
        public void reset() {
            invocations.set(0);
            requests.clear();
            answers.clear();
            rejection = List.of();
            stampedUuid = STAMPED_UUID;
        }

        /** How many times the attempt called the authority. */
        public int invocations() {
            return invocations.get();
        }

        /** The last request the attempt sent, or {@code null} when it never called. */
        public FiscalStampRequest lastRequest() {
            return requests.isEmpty() ? null : requests.get(requests.size() - 1);
        }

        /** The XML the last stamped answer carried, which is what the artefact must hold byte for byte. */
        public String lastAnswerXml() {
            return answers.isEmpty() ? null : answers.get(answers.size() - 1);
        }

        /** The timbre the double adds, so the answer's bytes are provably not the request's. */
        private String answerTo(FiscalStampRequest request) {
            return request.xml()
                    .replace(
                            "</cfdi:Comprobante>",
                            "<cfdi:Complemento><tfd:TimbreFiscalDigital"
                                    + " xmlns:tfd=\"http://www.sat.gob.mx/TimbreFiscalDigital\" Version=\"1.1\""
                                    + " UUID=\"" + stampedUuid + "\""
                                    + " FechaTimbrado=\"2026-11-15T10:15:30\" RfcProvCertif=\"AAA010101AAA\""
                                    + " NoCertificadoSAT=\"30001000000400002434\" SelloCFD=\"c2VsbG8tY2Zk\""
                                    + " SelloSAT=\"c2VsbG8tc2F0\"/></cfdi:Complemento></cfdi:Comprobante>");
        }
    }
}
