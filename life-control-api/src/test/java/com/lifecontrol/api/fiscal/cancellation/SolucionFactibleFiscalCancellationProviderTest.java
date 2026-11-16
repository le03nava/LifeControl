package com.lifecontrol.api.fiscal.cancellation;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.config.fiscal.SolucionFactibleProperties;
import com.lifecontrol.api.fiscal.exception.FiscalStampProviderDefectException;
import com.lifecontrol.api.fiscal.exception.FiscalStampProviderTransportException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * The Solución Factible cancellation adapter, pinned against a stub authority that speaks over a real socket
 * ({@code T8c}, decisions {@code L20}(c), {@code L20}(d) and {@code L20}(h) of
 * {@code odd/tasks/cfdi-fiscal-document.md}).
 *
 * <p><b>Why a loopback {@link HttpServer} and not a mock of the client.</b> Three of the facts this unit has to
 * prove are facts about <b>what left the process</b> — the operation element and its namespace, the
 * {@code SOAPAction} header, and that {@code solicitudCancelacion} is the base64 of the sealed document — and a
 * mocked client proves what the code intended to send, not what it serialized. A real server on port {@code 0}
 * also gives the two transport cases for free: an {@code HTTP 500} is a canned answer and a connection refused
 * is the same server, stopped.</p>
 *
 * <p><b>Why the vendor's two published answers are served verbatim.</b> They are the authority for the wire shape
 * ({@code L20}(h)): no {@code return} wrapper, the per-document results <b>directly</b> under the answer element,
 * {@code UUID} in capitals, and an error answer whose process status is {@code 1802} with a
 * {@code CFDIResultadoCancelacion} whose fields are empty. The published bodies carry <b>no SOAP envelope</b> —
 * they are the operation's answer as the vendor serializes it — so the adapter is pinned against that shape too,
 * and every hand-built body below mimics it rather than the WSDL's sketch.</p>
 *
 * <p><b>Why the third level is the whole suite.</b> The vendor's own <i>correct</i> answer is process {@code 200},
 * result {@code 200} and {@code statusUUID} {@code 205} — «el SAT no ha procesado la recepción de este
 * comprobante» — so a mapper that stopped at the result's {@code 200} would record a cancellation the authority
 * explicitly did not make ({@code L20}(c)). Cases {@code 201}, {@code 202}, {@code 205}, {@code 211} and
 * {@code 213} are one child apart from each other, which is what makes that distinction readable.</p>
 */
@DisplayName("Solución Factible's cancellation adapter (T8c)")
class SolucionFactibleFiscalCancellationProviderTest {

    /** The path the vendor publishes the operation on, so the stub answers where the properties point. */
    private static final String OPERATION_PATH = "/ws/services/Timbrado";

    private static final String SOAP_ENVELOPE = "http://schemas.xmlsoap.org/soap/envelope/";
    private static final String CANCELACION = "http://timbrado.ws.cfdi.solucionfactible.com";

    private static final String USUARIO = "usuario.de.pruebas@example.test";
    private static final String PASSWORD = "s3cr<to>&\"'";
    private static final int TIMEOUT_SECONDS = 30;

    /** The document being cancelled: the UUID the vendor's published correct answer names, in its own capitals. */
    private static final UUID UUID_FISCAL = UUID.fromString("6DB5A9A0-8A41-4481-AEA4-FBFFADBC96DA");

    /**
     * The document this adapter sends: already built and sealed by {@code T8b}, and never touched here.
     *
     * <p>It carries a {@code Signature} on purpose — the adapter's contract is "sends, does not build or seal", so
     * a document without one would prove nothing about which half does what.</p>
     */
    private static final String REQUEST_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Cancelacion xmlns="http://cancelacfd.sat.gob.mx" Fecha="2026-11-15T10:00:00" RfcEmisor="SFE0807172W8">\
            <Folios><Folio UUID="6DB5A9A0-8A41-4481-AEA4-FBFFADBC96DA" Motivo="02"/></Folios>\
            <Signature xmlns="http://www.w3.org/2000/09/xmldsig#"><SignedInfo/></Signature></Cancelacion>
            """;

    private StubAuthority authority;
    private SolucionFactibleFiscalCancellationProvider provider;

    @BeforeEach
    void startStubAuthority() {
        authority = StubAuthority.start();
        provider = new SolucionFactibleFiscalCancellationProvider(
                properties("http://127.0.0.1:" + authority.port() + OPERATION_PATH));
    }

    @AfterEach
    void stopStubAuthority() {
        authority.stop();
    }

    private FiscalCancellationRequest request() {
        return new FiscalCancellationRequest(REQUEST_XML, UUID_FISCAL);
    }

    // ─── what is sent ────────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("What leaves the process")
    class WhatIsSent {

        @Test
        @DisplayName("sends one SOAP 1.1 POST with the enviarSolicitudCancelacion action header and its namespace")
        void sendsOneSoapPostWithItsAction() {
            authority.answer(soapAnswer(answer(200, OK, result("200", CANCELLED_MESSAGE, "201", UUID_FISCAL))));

            provider.cancel(request());

            var sent = authority.lastRequest();
            assertThat(sent.method()).as("a SOAP 1.1 call is a POST").isEqualTo("POST");
            assertThat(sent.contentType())
                    .as("the vendor parses the envelope as SOAP 1.1 XML")
                    .startsWith("text/xml")
                    .contains("utf-8");
            assertThat(sent.soapAction())
                    .as("the operation is addressed in the Action header, quoted as SOAP 1.1 requires")
                    .isEqualTo("\"urn:enviarSolicitudCancelacion\"");

            var envelope = parse(sent.body());
            assertThat(envelope.getDocumentElement().getNamespaceURI())
                    .as("the envelope is the SOAP 1.1 one")
                    .isEqualTo(SOAP_ENVELOPE);
            var operation = operation(sent.body());
            assertThat(operation.getLocalName()).isEqualTo("enviarSolicitudCancelacion");
            assertThat(operation.getNamespaceURI())
                    .as("the operation element is in the WSDL's namespace")
                    .isEqualTo(CANCELACION);
            assertThat(childNames(operation))
                    .as("the three children travel in the WSDL's order, and only those three")
                    .containsExactly("usuario", "password", "solicitudCancelacion");
        }

        @Test
        @DisplayName("sends usuario and password verbatim, escaped so a & or a < reaches the vendor intact")
        void sendsTheCredentialsEscaped() {
            authority.answer(soapAnswer(answer(200, OK, result("200", CANCELLED_MESSAGE, "201", UUID_FISCAL))));

            provider.cancel(request());

            var operation = operation(authority.lastRequest().body());
            assertThat(text(operation, "usuario")).isEqualTo(USUARIO);
            assertThat(text(operation, "password"))
                    .as("a password carrying &, < and a quote has to survive the envelope")
                    .isEqualTo(PASSWORD);
        }

        @Test
        @DisplayName("sends the base64 of the sealed request it was given, byte for byte, and builds nothing")
        void sendsTheSealedRequestAsBase64() {
            authority.answer(soapAnswer(answer(200, OK, result("200", CANCELLED_MESSAGE, "201", UUID_FISCAL))));

            provider.cancel(request());

            var operation = operation(authority.lastRequest().body());
            var decoded = new String(Base64.getDecoder().decode(text(operation, "solicitudCancelacion")), UTF_8);
            assertThat(decoded)
                    .as("solicitudCancelacion is xs:base64Binary of the document T8b sealed, not the XML inline")
                    .isEqualTo(REQUEST_XML);
            assertThat(decoded)
                    .as("the adapter does not seal: the Signature element travels as it arrived")
                    .contains("<Signature")
                    .contains("Motivo=\"02\"");
        }
    }

    // ─── what is mapped ──────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("What the third level maps to")
    class WhatIsMapped {

        @Test
        @DisplayName("maps statusUUID 201 to Cancelled, which is the only code that says the document was cancelled")
        void maps201ToCancelled() {
            authority.answer(soapAnswer(answer(200, OK, result("200", CANCELLED_MESSAGE, "201", UUID_FISCAL))));

            var answer = provider.cancel(request());

            assertThat(answer).isInstanceOfSatisfying(FiscalCancellationResult.Cancelled.class, cancelled -> {
                assertThat(cancelled.uuid()).isEqualTo(UUID_FISCAL);
                assertThat(cancelled.statusUuid()).isEqualTo("201");
                assertThat(cancelled.message()).isEqualTo(CANCELLED_MESSAGE);
            });
        }

        @Test
        @DisplayName("maps statusUUID 202 to Cancelled as well, because a repeated cancellation is a cancellation")
        void maps202ToCancelled() {
            authority.answer(soapAnswer(answer(200, OK, result("200", PREVIOUSLY_CANCELLED, "202", UUID_FISCAL))));

            var answer = provider.cancel(request());

            assertThat(answer).isInstanceOfSatisfying(FiscalCancellationResult.Cancelled.class, cancelled -> {
                assertThat(cancelled.statusUuid()).isEqualTo("202");
                assertThat(cancelled.message()).isEqualTo(PREVIOUSLY_CANCELLED);
            });
        }

        @Test
        @DisplayName("maps statusUUID 211 to InProcess, a submitted request that is neither cancelled nor refused")
        void maps211ToInProcess() {
            authority.answer(soapAnswer(answer(200, OK, result("200", IN_PROCESS_MESSAGE, "211", UUID_FISCAL))));

            var answer = provider.cancel(request());

            assertThat(answer)
                    .as("211 writes nothing anywhere: it is the value the caller answers 202 with (L20(d))")
                    .isInstanceOfSatisfying(FiscalCancellationResult.InProcess.class, inProcess -> {
                        assertThat(inProcess.uuid()).isEqualTo(UUID_FISCAL);
                        assertThat(inProcess.statusUuid()).isEqualTo("211");
                    });
        }

        @Test
        @DisplayName("maps statusUUID 205 to Rejected, because the authority did not cancel and did not refuse")
        void maps205ToRejected() {
            authority.answer(soapAnswer(answer(200, OK, result("200", NOT_PROCESSED, "205", UUID_FISCAL))));

            var answer = provider.cancel(request());

            assertThat(answer).isInstanceOfSatisfying(FiscalCancellationResult.Rejected.class, rejected -> {
                assertThat(rejected.issues()).singleElement().satisfies(issue -> {
                    assertThat(issue.code())
                            .as("the third level's code is the answer, untranslated")
                            .isEqualTo("205");
                    assertThat(issue.message()).isEqualTo(NOT_PROCESSED);
                });
            });
        }

        @Test
        @DisplayName("maps any other statusUUID to Rejected carrying it, so a new code arrives as a code")
        void mapsAnotherStatusUuidToRejected() {
            authority.answer(soapAnswer(answer(200, OK, result("200", ANOTHER_MESSAGE, "213", UUID_FISCAL))));

            var answer = provider.cancel(request());

            assertThat(answer)
                    .isInstanceOfSatisfying(
                            FiscalCancellationResult.Rejected.class,
                            rejected -> assertThat(rejected.issues())
                                    .singleElement()
                                    .satisfies(issue -> assertThat(issue.code()).isEqualTo("213")));
        }

        @Test
        @DisplayName("reads the vendor's published correct answer as Rejected 205, not as a cancellation")
        void readsTheVendorsPublishedCorrectAnswer() {
            authority.answer(fixture("response_Timbrado.enviarSolicitudCancelacion.xml"));

            var answer = provider.cancel(request());

            assertThat(answer)
                    .as("its process and result statuses are both 200 and its statusUUID is 205: the mapping that "
                            + "stopped at the result's 200 would have recorded a cancellation the SAT did not make")
                    .isInstanceOfSatisfying(
                            FiscalCancellationResult.Rejected.class,
                            rejected -> assertThat(rejected.issues())
                                    .singleElement()
                                    .satisfies(issue -> {
                                        assertThat(issue.code()).isEqualTo("205");
                                        assertThat(issue.message()).contains("El SAT no ha procesado la recepción");
                                    }));
        }

        @Test
        @DisplayName("reads the vendor's published error answer as Rejected 1802, taken from the process level")
        void readsTheVendorsPublishedErrorAnswer() {
            authority.answer(fixture("response_error_Timbrado.enviarSolicitudCancelacion.xml"));

            var answer = provider.cancel(request());

            assertThat(answer)
                    .as("its CFDIResultadoCancelacion states an empty status and an empty mensaje, so the only "
                            + "code the answer carries is the process one")
                    .isInstanceOfSatisfying(
                            FiscalCancellationResult.Rejected.class,
                            rejected -> assertThat(rejected.issues())
                                    .singleElement()
                                    .satisfies(issue -> {
                                        assertThat(issue.code()).isEqualTo("1802");
                                        assertThat(issue.message()).contains("firma de la solicitud de cancelación");
                                    }));
        }

        @Test
        @DisplayName("names both levels when the call failed and a result still states a statusUUID")
        void namesBothCodesWhenTheCallFailed() {
            authority.answer(soapAnswer(answer(
                    620, "No fue posible procesar la solicitud.", result("200", NOT_PROCESSED, "205", UUID_FISCAL))));

            var answer = provider.cancel(request());

            assertThat(answer)
                    .isInstanceOfSatisfying(
                            FiscalCancellationResult.Rejected.class,
                            rejected -> assertThat(rejected.issues())
                                    .extracting(FiscalCancellationResult.Issue::code)
                                    .as(
                                            "the call's own code comes first, the document's second: an operator needs both")
                                    .containsExactly("620", "205"));
        }
    }

    // ─── what is refused ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("What a defect and what a transport failure look like")
    class WhatIsRefused {

        @Test
        @DisplayName("refuses a process 200 whose result states no statusUUID, because no verdict was stated")
        void refusesAProcess200WithNoStatusUuid() {
            authority.answer(
                    soapAnswer(answer(200, OK, result("200", "CFDI correctamente validado.", null, UUID_FISCAL))));

            assertThatThrownBy(() -> provider.cancel(request()))
                    .as("a 200 with no statusUUID is neither a cancellation nor a rejection: the authority stated "
                            + "no verdict on the document, and inventing one costs a revocation")
                    .isInstanceOf(FiscalStampProviderDefectException.class)
                    .hasMessageContaining("statusUUID");
        }

        @Test
        @DisplayName("refuses a process 200 that carries no CFDIResultadoCancelacion at all")
        void refusesAProcess200WithNoResult() {
            authority.answer(soapAnswer(answer(200, OK)));

            assertThatThrownBy(() -> provider.cancel(request()))
                    .isInstanceOf(FiscalStampProviderDefectException.class)
                    .hasMessageContaining("CFDIResultadoCancelacion");
        }

        @Test
        @DisplayName("refuses an answer whose UUID disagrees with the one asked to be cancelled")
        void refusesAnAnswerThatNamesAnotherUuid() {
            var anotherUuid = "11111111-2222-3333-4444-555555555555";
            authority.answer(
                    soapAnswer(answer(200, OK, result("200", CANCELLED_MESSAGE, "201", UUID.fromString(anotherUuid)))));

            assertThatThrownBy(() -> provider.cancel(request()))
                    .as("an answer about another document cannot become a cancellation of this one")
                    .isInstanceOf(FiscalStampProviderDefectException.class)
                    .hasMessageContaining(anotherUuid)
                    .hasMessageContaining(UUID_FISCAL.toString());
        }

        @Test
        @DisplayName("refuses a SOAP Fault as a defect, never as the authority's verdict on the document")
        void refusesASoapFault() {
            authority.answer(soapAnswer("""
                    <soapenv:Fault xmlns:soapenv="%s"><faultcode>soapenv:Server</faultcode>\
                    <faultstring>Internal error</faultstring></soapenv:Fault>""".formatted(SOAP_ENVELOPE)));

            assertThatThrownBy(() -> provider.cancel(request()))
                    .isInstanceOf(FiscalStampProviderDefectException.class)
                    .hasMessageContaining("Fault")
                    .hasMessageContaining("Internal error");
        }

        @Test
        @DisplayName("raises a transport failure, not a rejection, when the authority answers HTTP 500")
        void raisesATransportFailureOnHttp500() {
            authority.answer(500, "the vendor is having a bad day");

            assertThatThrownBy(() -> provider.cancel(request()))
                    .isInstanceOf(FiscalStampProviderTransportException.class)
                    .hasMessageContaining("HTTP 500")
                    .hasMessageContaining(OPERATION_PATH)
                    .hasMessageContaining(TIMEOUT_SECONDS + " s");
        }

        @Test
        @DisplayName("raises a transport failure, not a rejection, when the endpoint refuses the connection")
        void raisesATransportFailureWhenNothingListens() {
            var closedPort = authority.port();
            authority.stop();

            assertThatThrownBy(() -> provider.cancel(request()))
                    .isInstanceOf(FiscalStampProviderTransportException.class)
                    .hasMessageContaining(":" + closedPort + OPERATION_PATH)
                    .hasMessageContaining(TIMEOUT_SECONDS + " s");
        }
    }

    // ─── the answer bodies this suite serves ─────────────────────────────────────────────────────────

    private static final String OK =
            "[Servidor de pruebas] OK - Autenticación correcta, verifique los resultados por comprobante";

    private static final String CANCELLED_MESSAGE = "El folio se ha cancelado con éxito.";
    private static final String PREVIOUSLY_CANCELLED = "El CFDI ya se había cancelado previamente.";
    private static final String IN_PROCESS_MESSAGE = "La cancelación está en proceso.";
    private static final String NOT_PROCESSED = "El SAT no ha procesado la recepción de este comprobante.";
    private static final String ANOTHER_MESSAGE = "El UUID no corresponde al RFC del emisor.";

    /**
     * One {@code CFDIResultadoCancelacion}, written in the vendor's own order.
     *
     * <p>A {@code null} {@code statusUUID} is <b>omitted</b>, which is a different fact from an empty one: the
     * published correct answer always states it, and its absence is exactly the defect one case below refuses.</p>
     */
    private static String result(String status, String mensaje, String statusUuid, UUID uuid) {
        return """
                <CFDIResultadoCancelacion>\
                <status>%s</status>\
                <mensaje>%s</mensaje>\
                %s\
                %s\
                </CFDIResultadoCancelacion>""".formatted(
                        status == null ? "" : status,
                        mensaje == null ? "" : mensaje,
                        statusUuid == null ? "" : "<statusUUID>" + statusUuid + "</statusUUID>",
                        uuid == null ? "" : "<UUID>" + uuid.toString().toUpperCase() + "</UUID>");
    }

    /**
     * The {@code EnviarSolicitudCancelacionResponse} the operation answers with: no {@code return} wrapper and the
     * results directly under it, which is the vendor's published wire form ({@code L20}(h)).
     */
    private static String answer(int status, String mensaje, String... results) {
        return """
                <EnviarSolicitudCancelacionResponse>
                  <status>%d</status>
                  <mensaje>%s</mensaje>
                %s
                </EnviarSolicitudCancelacionResponse>""".formatted(status, mensaje, String.join("\n", results));
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

    // ─── reading what was sent, and the fixtures ─────────────────────────────────────────────────────

    private static String fixture(String name) {
        try {
            return Files.readString(
                    Path.of("src/test/resources/fiscal/solucionfactible", name), StandardCharsets.UTF_8);
        } catch (IOException cannotRead) {
            throw new UncheckedIOException(
                    "the vendor's committed example has to be on the test classpath", cannotRead);
        }
    }

    private SolucionFactibleProperties properties(String endpoint) {
        return new SolucionFactibleProperties(endpoint, USUARIO, PASSWORD, TIMEOUT_SECONDS);
    }

    private static Element operation(String envelope) {
        var body = children(root(parse(envelope)), SOAP_ENVELOPE, "Body").get(0);
        return children(body, CANCELACION, "enviarSolicitudCancelacion").get(0);
    }

    private static Document parse(String xml) {
        try {
            var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)));
        } catch (Exception cannotParse) {
            throw new IllegalStateException("the envelope the adapter sent has to be well formed XML", cannotParse);
        }
    }

    private static Element root(Document document) {
        return document.getDocumentElement();
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

    /** The operation's children are read by local name: the vendor qualifies them, this suite does not care. */
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

    /**
     * A stub of the vendor's endpoint: it records what arrived and answers whatever the case told it to.
     *
     * <p>Bound to {@code 127.0.0.1} on port {@code 0}, so two runs — or two cases in one run — never collide, and
     * nothing is reachable from outside the loopback interface.</p>
     */
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
                            exchange.getRequestHeaders().getFirst("Content-Type"),
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

        /** Answers {@code 200} with this body, which is what every mapping case needs. */
        void answer(String soapBody) {
            answer(200, soapBody);
        }

        void answer(int status, String soapBody) {
            this.status = status;
            this.body = soapBody;
        }

        Recorded lastRequest() {
            return lastRequest;
        }

        void stop() {
            server.stop(0);
        }
    }

    /** What the stub received: the facts about the request that a mocked client could not prove. */
    private record Recorded(String method, String contentType, String soapAction, String body) {}
}
