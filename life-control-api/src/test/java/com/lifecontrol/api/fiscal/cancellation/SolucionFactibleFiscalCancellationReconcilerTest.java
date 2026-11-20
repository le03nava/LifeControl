package com.lifecontrol.api.fiscal.cancellation;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.config.fiscal.SolucionFactibleProperties;
import com.lifecontrol.api.fiscal.cancellation.FiscalCancellationReconciliationPort.Outcome;
import com.lifecontrol.api.fiscal.cancellation.FiscalCancellationReconciliationPort.Registration;
import com.lifecontrol.api.fiscal.cancellation.FiscalCancellationResult.Issue;
import com.lifecontrol.api.fiscal.exception.FiscalStampProviderDefectException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

/**
 * The Solución Factible asynchronous reconciliation adapter, pinned against a stub authority over a real socket
 * ({@code T3} of {@code odd/tasks/fiscal-cancellation-reconciliation.md}, operations {@code L3} and reading
 * {@code L4}).
 *
 * <p><b>Why the verdicts are the subject and not the parsing.</b> Both operations answer a <i>flat</i> document —
 * {@code CancelacionAsincronoResponse{status, mensaje}} and {@code StatusCancelacionResponse{status, mensaje,
 * acuseSAT}} — so there is no three-level mapping to pin, as there is for the synchronous cancellation. What has to
 * be proven is the reading {@code L4} states: {@code acuseSAT} is the only acceptance, {@code 213} and {@code 204}
 * are settled rejections, {@code 702} is "no transaction", {@code 211} and a bare {@code 200} are still pending, and
 * a code the catalogue does not settle is a <b>provider defect</b> rather than a silent "still waiting".</p>
 *
 * <p><b>Why these answer bodies are built here and not committed as fixtures.</b> The tree's precedent
 * ({@code src/test/resources/fiscal/solucionfactible/}) is the vendor's own published examples, committed verbatim,
 * and this unit did not fetch one for these two operations: inventing a file and calling it a vendor example would be
 * worse than saying so. What the bodies below reproduce is the shape {@code L3} quotes — the operation's answer
 * element with its three children — and the codes are the vendor's own.</p>
 *
 * <p><b>Why a loopback {@link HttpServer}.</b> Two facts are about what left the process: which {@code SOAPAction}
 * addressed the call and that the registration sends the base64 of the sealed request where {@code solicitud}
 * expects it. A mocked client would prove what the code intended, not what it serialized.</p>
 */
@DisplayName("Solución Factible's asynchronous reconciliation adapter (T3)")
class SolucionFactibleFiscalCancellationReconcilerTest {

    private static final String OPERATION_PATH = "/ws/services/Timbrado";

    private static final String SOAP_ENVELOPE = "http://schemas.xmlsoap.org/soap/envelope/";
    private static final String CANCELACION = "http://timbrado.ws.cfdi.solucionfactible.com";

    private static final String USUARIO = "usuario.de.pruebas@example.test";
    private static final String PASSWORD = "s3cr<to>&\"'";
    private static final int TIMEOUT_SECONDS = 30;

    /** The document the transaction is about: the vendor's catalogue says the transaction id is usually its UUID. */
    private static final UUID UUID_FISCAL = UUID.fromString("6DB5A9A0-8A41-4481-AEA4-FBFFADBC96DA");

    private static final String REQUEST_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Cancelacion xmlns="http://cancelacfd.sat.gob.mx" Fecha="2026-11-19T10:00:00" RfcEmisor="SFE0807172W8">\
            <Folios><Folio UUID="6DB5A9A0-8A41-4481-AEA4-FBFFADBC96DA" Motivo="02"/></Folios>\
            <Signature xmlns="http://www.w3.org/2000/09/xmldsig#"><SignedInfo/></Signature></Cancelacion>
            """;

    private StubAuthority authority;
    private SolucionFactibleFiscalCancellationReconciler reconciler;

    @BeforeEach
    void startStubAuthority() {
        authority = StubAuthority.start();
        reconciler = new SolucionFactibleFiscalCancellationReconciler(
                properties("http://127.0.0.1:" + authority.port() + OPERATION_PATH));
    }

    @AfterEach
    void stopStubAuthority() {
        authority.stop();
    }

    private static FiscalCancellationRequest request() {
        return new FiscalCancellationRequest(REQUEST_XML, UUID_FISCAL);
    }

    // ─── the status operation ───────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getStatusCancelacionAsincrona")
    class TheStatusOperation {

        @Test
        @DisplayName(
                "addresses the call with its own SOAPAction, its three children and the transaction id it asks about")
        void addressesTheStatusOperation() {
            authority.answer(soapAnswer(statusAnswer(200, ACCEPTED_MESSAGE, ACUSE)));

            reconciler.status(UUID_FISCAL);

            var sent = authority.lastRequest();
            assertThat(sent.method()).isEqualTo("POST");
            assertThat(sent.soapAction())
                    .as("L3: the vendor addresses this operation by urn:getStatusCancelacionAsincrona")
                    .isEqualTo("\"urn:getStatusCancelacionAsincrona\"");

            var operation = operation(sent.body());
            assertThat(operation.getLocalName()).isEqualTo("getStatusCancelacionAsincrona");
            assertThat(operation.getNamespaceURI()).isEqualTo(CANCELACION);
            assertThat(childNames(operation))
                    .as("L3: usuario, password and transactionId, and the optional properties is not sent")
                    .containsExactly("usuario", "password", "transactionId");
            assertThat(text(operation, "usuario")).isEqualTo(USUARIO);
            assertThat(text(operation, "password"))
                    .as("a password carrying &, < and a quote reaches the vendor intact")
                    .isEqualTo(PASSWORD);
            assertThat(text(operation, "transactionId"))
                    .as("the transaction the pass asks about is the document's fiscal UUID")
                    .isEqualTo(UUID_FISCAL.toString().toUpperCase(java.util.Locale.ROOT));
        }

        @Test
        @DisplayName("L4: acuseSAT present is an acceptance, whatever code sits beside it")
        void anAcuseIsAnAcceptance() {
            authority.answer(soapAnswer(statusAnswer(200, ACCEPTED_MESSAGE, ACUSE)));

            var outcome = reconciler.status(UUID_FISCAL);

            assertThat(outcome).isInstanceOfSatisfying(Outcome.Accepted.class, accepted -> {
                assertThat(accepted.acuse())
                        .as("the catalogue's only statement of «cancelada correctamente»")
                        .isEqualTo(ACUSE);
                assertThat(accepted.statusUuid()).isEqualTo("200");
                assertThat(accepted.message()).isEqualTo(ACCEPTED_MESSAGE);
            });
        }

        @Test
        @DisplayName("L4: 213 is a settled rejection and keeps the receiver's code")
        void rejectsUnder213() {
            authority.answer(soapAnswer(statusAnswer(213, REJECTED_BY_RECEIVER, "")));

            var outcome = reconciler.status(UUID_FISCAL);

            assertThat(outcome).isInstanceOfSatisfying(Outcome.Rejected.class, rejected -> {
                assertThat(rejected.statusUuid()).isEqualTo("213");
                assertThat(rejected.message()).isEqualTo(REJECTED_BY_RECEIVER);
            });
        }

        @Test
        @DisplayName("L4: 204 is a settled rejection too, because the document cannot be cancelled")
        void rejectsUnder204() {
            authority.answer(soapAnswer(statusAnswer(204, CANNOT_BE_CANCELLED, "")));

            var outcome = reconciler.status(UUID_FISCAL);

            assertThat(outcome).isInstanceOfSatisfying(Outcome.Rejected.class, rejected -> {
                assertThat(rejected.statusUuid()).isEqualTo("204");
                assertThat(rejected.message()).isEqualTo(CANNOT_BE_CANCELLED);
            });
        }

        @Test
        @DisplayName("L4: 702 says the authority holds no transaction, which is a different fact from waiting")
        void noTransactionUnder702() {
            authority.answer(soapAnswer(statusAnswer(702, NO_TRANSACTION, "")));

            assertThat(reconciler.status(UUID_FISCAL)).isInstanceOf(Outcome.NoTransaction.class);
        }

        @Test
        @DisplayName("L4: a bare 200 with no acuseSAT is still pending, never an acceptance")
        void aBare200IsStillPending() {
            authority.answer(soapAnswer(statusAnswer(200, IN_PROCESS_MESSAGE, null)));

            var outcome = reconciler.status(UUID_FISCAL);

            assertThat(outcome).isInstanceOfSatisfying(Outcome.StillPending.class, pending -> {
                assertThat(pending.statusUuid()).isEqualTo("200");
                assertThat(pending.message()).isEqualTo(IN_PROCESS_MESSAGE);
            });
        }

        @Test
        @DisplayName("L4: 211 is still pending")
        void accepts211AsStillPending() {
            authority.answer(soapAnswer(statusAnswer(211, IN_PROCESS_MESSAGE, "")));

            var outcome = reconciler.status(UUID_FISCAL);

            assertThat(outcome).isInstanceOfSatisfying(Outcome.StillPending.class, pending -> {
                assertThat(pending.statusUuid()).isEqualTo("211");
                assertThat(pending.message()).isEqualTo(IN_PROCESS_MESSAGE);
            });
        }

        @Test
        @DisplayName("a code the catalogue does not settle is a defect, never a silent «still waiting»")
        void anUnsettledCodeIsADefect() {
            authority.answer(soapAnswer(statusAnswer(601, "La cuenta no es válida", "")));

            assertThatThrownBy(() -> reconciler.status(UUID_FISCAL))
                    .as("a call that states nothing must not read as pending: the operator would wait forever")
                    .isInstanceOf(FiscalStampProviderDefectException.class)
                    .hasMessageContaining("601");
        }

        @Test
        @DisplayName("a blank acuseSAT is an absence, not a receipt: 211 stays pending beside it")
        void aBlankAcuseIsNotAReceipt() {
            authority.answer(soapAnswer(statusAnswer(211, IN_PROCESS_MESSAGE, "   ")));

            assertThat(reconciler.status(UUID_FISCAL)).isInstanceOf(Outcome.StillPending.class);
        }

        @Test
        @DisplayName("a SOAP Fault is a defect and never a verdict")
        void aFaultIsADefect() {
            authority.answer(soapAnswer("""
                    <soapenv:Fault xmlns:soapenv="%s"><faultcode>soapenv:Server</faultcode>\
                    <faultstring>Internal error</faultstring></soapenv:Fault>""".formatted(SOAP_ENVELOPE)));

            assertThatThrownBy(() -> reconciler.status(UUID_FISCAL))
                    .isInstanceOf(FiscalStampProviderDefectException.class)
                    .hasMessageContaining("Fault");
        }
    }

    // ─── the registration operation ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("enviarSolicitudCancelacionAsincrono")
    class TheRegistrationOperation {

        @Test
        @DisplayName(
                "addresses the call with its own SOAPAction and sends the base64 of the sealed request as solicitud")
        void addressesTheRegistrationOperation() {
            authority.answer(soapAnswer(registrationAnswer(200, REGISTERED_MESSAGE)));

            reconciler.register(request());

            var sent = authority.lastRequest();
            assertThat(sent.soapAction())
                    .as("L3: the vendor addresses this operation by urn:enviarSolicitudCancelacionAsincrono")
                    .isEqualTo("\"urn:enviarSolicitudCancelacionAsincrono\"");

            var operation = operation(sent.body());
            assertThat(operation.getLocalName()).isEqualTo("enviarSolicitudCancelacionAsincrono");
            assertThat(operation.getNamespaceURI()).isEqualTo(CANCELACION);
            assertThat(childNames(operation))
                    .as("L3: usuario, password and solicitud — emailNotifica and properties are not sent")
                    .containsExactly("usuario", "password", "solicitud");
            var decoded = new String(Base64.getDecoder().decode(text(operation, "solicitud")), UTF_8);
            assertThat(decoded)
                    .as("solicitud is xs:base64Binary of the document the caller sealed, not the XML inline")
                    .isEqualTo(REQUEST_XML);
        }

        @Test
        @DisplayName("L4: 200 is registered, and the message carries the transaction id the vendor answers in it")
        void registersUnder200() {
            authority.answer(soapAnswer(registrationAnswer(200, REGISTERED_MESSAGE)));

            var registration = reconciler.register(request());

            assertThat(registration).isInstanceOfSatisfying(Registration.Registered.class, registered -> {
                assertThat(registered.transactionId()).isEqualTo(REGISTERED_MESSAGE);
                assertThat(registered.message()).isEqualTo(REGISTERED_MESSAGE);
            });
        }

        @Test
        @DisplayName("L4: 701 is registered as well, and the message says an earlier pass already did it")
        void registersUnder701() {
            authority.answer(soapAnswer(registrationAnswer(701, ALREADY_REGISTERED_MESSAGE)));

            var registration = reconciler.register(request());

            assertThat(registration).isInstanceOfSatisfying(Registration.Registered.class, registered -> {
                assertThat(registered.transactionId()).isEqualTo(ALREADY_REGISTERED_MESSAGE);
                assertThat(registered.message())
                        .as("the operator still has to be able to read that nothing was registered twice")
                        .contains("701")
                        .contains(ALREADY_REGISTERED_MESSAGE);
            });
        }

        @Test
        @DisplayName("L4: an account failure is a refusal carrying the authority's code")
        void refusesUnderAnAccountFailure() {
            authority.answer(soapAnswer(registrationAnswer(620, REFUSED_MESSAGE)));

            var registration = reconciler.register(request());

            assertThat(registration).isInstanceOfSatisfying(Registration.Refused.class, refused -> {
                assertThat(refused.issues()).singleElement().satisfies(issue -> {
                    assertThat(issue.code()).isEqualTo("620");
                    assertThat(issue.message()).isEqualTo(REFUSED_MESSAGE);
                });
            });
        }

        @Test
        @DisplayName("L3: 1801–1803 are refusals too, so an unsigned or invalid request is reported and not mapped")
        void refusesUnderAnInvalidRequest() {
            authority.answer(soapAnswer(registrationAnswer(1802, INVALID_SIGNATURE)));

            var registration = reconciler.register(request());

            assertThat(registration)
                    .isInstanceOfSatisfying(
                            Registration.Refused.class,
                            refused -> assertThat(refused.issues())
                                    .extracting(Issue::code)
                                    .containsExactly("1802"));
        }
    }

    // ─── the answer bodies this suite serves ────────────────────────────────────────────────────────

    private static final String OK = "[Servidor de pruebas] OK - Autenticación correcta";

    private static final String ACCEPTED_MESSAGE = "La solicitud de cancelación fue aceptada por el SAT.";
    private static final String ACUSE =
            "Kw3RSgj6Us8w5yqgxHtM15oVsyw0l7mnheJ1J0YyppGrv4z2bukZz43V4Dng8G3eyANJ+XZVIug1xeO5VBCA3Q==";
    private static final String IN_PROCESS_MESSAGE = "La cancelación está en proceso.";
    private static final String REJECTED_BY_RECEIVER = "El receptor rechazó la cancelación del comprobante.";
    private static final String CANNOT_BE_CANCELLED = "El comprobante no puede ser cancelado.";
    private static final String NO_TRANSACTION = "No existe una transacción con el UUID especificado.";

    private static final String REGISTERED_MESSAGE = "6DB5A9A0-8A41-4481-AEA4-FBFFADBC96DA";
    private static final String ALREADY_REGISTERED_MESSAGE = "Ya existe una transacción asíncrona para el UUID.";
    private static final String REFUSED_MESSAGE = "No fue posible procesar la solicitud.";
    private static final String INVALID_SIGNATURE = "La firma de la solicitud no ha pasado la validación.";

    /**
     * The registration's answer, as {@code L3} quotes it: {@code CancelacionAsincronoResponse{status, mensaje}}.
     *
     * <p>{@code mensaje} is where the vendor says the transaction id arrives, which is why the fixture's message is a
     * UUID: the adapter has nothing else to answer with.</p>
     */
    private static String registrationAnswer(int status, String mensaje) {
        return """
                <CancelacionAsincronoResponse>
                  <status>%d</status>
                  <mensaje>%s</mensaje>
                </CancelacionAsincronoResponse>""".formatted(status, mensaje);
    }

    /**
     * The status answer, as {@code L3} quotes it:
     * {@code StatusCancelacionResponse{status, mensaje, acuseSAT}} — and a {@code null} acuse omits the element,
     * which is a different fact from an empty one.
     */
    private static String statusAnswer(int status, String mensaje, String acuse) {
        return """
                <StatusCancelacionResponse>
                  <status>%d</status>
                  <mensaje>%s</mensaje>
                  %s
                </StatusCancelacionResponse>""".formatted(status, mensaje, acuse == null ? "" : "<acuseSAT>" + acuse + "</acuseSAT>");
    }

    private static String soapAnswer(String answerElement) {
        return """
                <?xml version="1.0" encoding="utf-8"?>
                <soapenv:Envelope xmlns:soapenv="%s">\
                <soapenv:Body>\
                %s\
                </soapenv:Body>\
                </soapenv:Envelope>""".formatted(SOAP_ENVELOPE, answerElement);
    }

    // ─── reading what was sent ──────────────────────────────────────────────────────────────────────

    private static SolucionFactibleProperties properties(String endpoint) {
        return new SolucionFactibleProperties(endpoint, USUARIO, PASSWORD, TIMEOUT_SECONDS);
    }

    private static org.w3c.dom.Document parse(String xml) {
        try {
            var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)));
        } catch (Exception cannotParse) {
            throw new IllegalStateException("the envelope the adapter sent has to be well formed XML", cannotParse);
        }
    }

    private static Element operation(String envelope) {
        var body = children(parse(envelope).getDocumentElement(), SOAP_ENVELOPE, "Body")
                .get(0);
        for (var child = body.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && CANCELACION.equals(element.getNamespaceURI())) {
                return element;
            }
        }
        throw new IllegalStateException("the envelope carries no cancellation operation element");
    }

    private static List<Element> children(Element parent, String namespace, String localName) {
        var found = new ArrayList<Element>();
        for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element
                    && localName.equals(element.getLocalName())
                    && namespace.equals(element.getNamespaceURI())) {
                found.add(element);
            }
        }
        return found;
    }

    private static String text(Element parent, String localName) {
        for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && localName.equals(element.getLocalName())) {
                return element.getTextContent();
            }
        }
        return null;
    }

    private static List<String> childNames(Element parent) {
        var names = new ArrayList<String>();
        for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element) {
                names.add(element.getLocalName());
            }
        }
        return names;
    }

    /** A stub of the vendor's endpoint, bound to loopback on port {@code 0}, recording what arrived. */
    private static final class StubAuthority {

        private final HttpServer server;
        private volatile Recorded lastRequest;
        private volatile int status = 200;
        private volatile String body = "";

        private StubAuthority(HttpServer server) {
            this.server = server;
        }

        static StubAuthority start() {
            try {
                var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
                var authority = new StubAuthority(server);
                server.createContext(OPERATION_PATH, exchange -> {
                    var sent = new String(exchange.getRequestBody().readAllBytes(), UTF_8);
                    authority.lastRequest = new Recorded(
                            exchange.getRequestMethod(),
                            exchange.getRequestHeaders().getFirst("SOAPAction"),
                            sent);
                    var payload = authority.body.getBytes(UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "text/xml; charset=utf-8");
                    exchange.sendResponseHeaders(authority.status, payload.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(payload);
                    }
                });
                server.start();
                return authority;
            } catch (IOException cannotBind) {
                throw new UncheckedIOException("the stub authority has to bind an ephemeral loopback port", cannotBind);
            }
        }

        int port() {
            return server.getAddress().getPort();
        }

        void answer(String soapBody) {
            this.status = 200;
            this.body = soapBody;
        }

        Recorded lastRequest() {
            return lastRequest;
        }

        void stop() {
            server.stop(0);
        }
    }

    private record Recorded(String method, String soapAction, String body) {}
}
