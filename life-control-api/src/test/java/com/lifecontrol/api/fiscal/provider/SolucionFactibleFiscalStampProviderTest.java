package com.lifecontrol.api.fiscal.provider;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.config.fiscal.SolucionFactibleProperties;
import com.lifecontrol.api.fiscal.FiscalStampRequest;
import com.lifecontrol.api.fiscal.FiscalStampResult;
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
import java.time.OffsetDateTime;
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
 * The Solución Factible adapter, pinned against a stub authority that speaks over a real socket
 * ({@code T7a}, {@code L18} of {@code odd/tasks/cfdi-fiscal-document.md}).
 *
 * <p><b>Why a loopback {@link HttpServer} and not a mock of the client.</b> Three of the four facts this unit
 * has to prove are facts about <b>what left the process</b> — the envelope's namespaces, the {@code soapAction}
 * header, and that {@code cfdiBase64} is the base64 of the document and {@code ZIP} is {@code false} — and a
 * mocked client proves what the code intended to send, not what it serialized. A real server on port {@code 0}
 * also gives the two transport cases for free: an {@code HTTP 500} is a canned answer, and a connection refused
 * is the same server, stopped.</p>
 *
 * <p><b>Why the fixtures are files and the happy path is not.</b> The two committed files are the vendor's own
 * 2012 answers and are read verbatim (see {@code src/test/resources/fiscal/solucionfactible/README.md} for the
 * URLs, the date and what they cannot cover: a {@code TimbreFiscalDigital} of version 1.0 states no
 * {@code RfcProvCertif}). The class of document this tree sends is CFDI 4.0 with a timbre of version 1.1, so the
 * six-fact case, the missing timbre and the disagreed UUID are built here from the contract — each one is a
 * single attribute away from the others, which is what makes them readable side by side.</p>
 */
@DisplayName("Solución Factible's stamping adapter (T7a)")
class SolucionFactibleFiscalStampProviderTest {

    /** The path the vendor publishes the operation on, so the stub answers where the properties point. */
    private static final String OPERATION_PATH = "/ws/services/Timbrado";

    private static final String SOAP_ENVELOPE = "http://schemas.xmlsoap.org/soap/envelope/";
    private static final String TIMBRADO = "http://timbrado.ws.cfdi.solucionfactible.com";
    private static final String TIMBRADO_XSD = "http://timbrado.ws.cfdi.solucionfactible.com/xsd";
    private static final String TIMBRE = "http://www.sat.gob.mx/TimbreFiscalDigital";

    private static final String USUARIO = "usuario.de.pruebas@example.test";
    private static final String PASSWORD = "s3cr<to>&\"'";
    private static final int TIMEOUT_SECONDS = 30;

    /** The authority's UUID: the same one the timbre inside {@link #STAMPED_CFDI} states. */
    private static final UUID UUID_FISCAL = UUID.fromString("9f2c1a44-8b7d-4c3e-9a11-6d5e0f2b7c88");

    private static final OffsetDateTime STAMPED_AT = OffsetDateTime.parse("2026-11-15T10:15:30-06:00");

    private static final String RFC_PROV_CERTIF = "SFE0807172W8";
    private static final String NO_CERTIFICADO_SAT = "30001000000100000801";
    private static final String SELLO_CFD = "c2VsbG8tY2ZkLWRlbC1lbWlzb3I=";
    private static final String SELLO_SAT = "c2VsbG8tc2F0LWRlbGEtYXV0b3JpZGFk";

    /** The document this tree builds and sends: CFDI 4.0, and no timbre yet. */
    private static final String REQUEST_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <cfdi:Comprobante xmlns:cfdi="http://www.sat.gob.mx/cfd/4" Version="4.0" Serie="A" Folio="42"\
             Fecha="2026-11-15T10:00:00" FormaPago="01" MetodoPago="PUE" Moneda="MXN" SubTotal="100.0000"\
             Total="116.0000" TipoDeComprobante="I" Exportacion="01" LugarExpedicion="64000" NoCertificado="30001000000100000801"\
             Certificado="MIIB" Sello="c2VsbG8tY2ZkLWRlbC1lbWlzb3I=">
              <cfdi:Emisor Rfc="SFE0807172W8" Nombre="Solución Factible" RegimenFiscal="601"/>
              <cfdi:Receptor Rfc="AAA010101AAA" Nombre="Público en general" DomicilioFiscalReceptor="64000"\
             RegimenFiscalReceptor="616" UsoCFDI="G01"/>
              <cfdi:Conceptos>
                <cfdi:Concepto ClaveProdServ="01010101" Cantidad="1.000000" ClaveUnidad="H87"\
             Descripcion="Servicio" ValorUnitario="100.0000" Importe="100.0000" ObjetoImp="02"/>
              </cfdi:Conceptos>
            </cfdi:Comprobante>
            """;

    /** What the authority returns: the same document, plus the timbre it stamped it with. */
    private static final String STAMPED_CFDI = REQUEST_XML.replace("</cfdi:Comprobante>", """
            <cfdi:Complemento><tfd:TimbreFiscalDigital\
             xmlns:tfd="http://www.sat.gob.mx/TimbreFiscalDigital" Version="1.1"\
             UUID="9f2c1a44-8b7d-4c3e-9a11-6d5e0f2b7c88" FechaTimbrado="2026-11-15T10:15:30"\
             RfcProvCertif="SFE0807172W8" NoCertificadoSAT="30001000000100000801"\
             SelloCFD="c2VsbG8tY2ZkLWRlbC1lbWlzb3I=" SelloSAT="c2VsbG8tc2F0LWRlbGEtYXV0b3JpZGFk"/></cfdi:Complemento></cfdi:Comprobante>""");

    private StubAuthority authority;
    private SolucionFactibleFiscalStampProvider provider;

    @BeforeEach
    void startStubAuthority() {
        authority = StubAuthority.start();
        provider = new SolucionFactibleFiscalStampProvider(
                properties("http://127.0.0.1:" + authority.port() + OPERATION_PATH));
    }

    @AfterEach
    void stopStubAuthority() {
        authority.stop();
    }

    private FiscalStampRequest request() {
        return new FiscalStampRequest(REQUEST_XML, "SFE0807172W8");
    }

    // ─── what is sent ────────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("What leaves the process")
    class WhatIsSent {

        @Test
        @DisplayName(
                "sends one SOAP 1.1 POST to the configured endpoint, with the timbrarBase64 action header and its namespace")
        void sendsOneSoapPostWithItsAction() {
            authority.answer(soapAnswer(stampedReturn()));

            provider.stamp(request());

            var sent = authority.lastRequest();
            assertThat(sent.method()).as("a SOAP 1.1 call is a POST").isEqualTo("POST");
            assertThat(sent.contentType())
                    .as("the vendor parses the envelope as SOAP 1.1 XML")
                    .startsWith("text/xml")
                    .contains("utf-8");
            assertThat(sent.soapAction())
                    .as("SOAP 1.1 addresses the operation in the Action header, quoted as the specification asks")
                    .isEqualTo("\"urn:timbrarBase64\"");

            var body = parse(sent.body());
            assertThat(root(body).getNamespaceURI())
                    .as("the envelope is the SOAP 1.1 one")
                    .isEqualTo(SOAP_ENVELOPE);
            var operation = operation(sent.body());
            assertThat(operation.getNamespaceURI())
                    .as("the operation element is timbrarBase64 in the WSDL's namespace")
                    .isEqualTo(TIMBRADO);
            assertThat(operation.getLocalName()).isEqualTo("timbrarBase64");
            assertThat(childNames(operation))
                    .as("the four children travel in the WSDL's order, and only those four")
                    .containsExactly("usuario", "password", "cfdiBase64", "ZIP");
        }

        @Test
        @DisplayName("sends usuario and password verbatim, escaped so a & or a < reaches the vendor intact")
        void sendsTheCredentialsEscaped() {
            authority.answer(soapAnswer(stampedReturn()));

            provider.stamp(request());

            var operation = operation(authority.lastRequest().body());
            assertThat(text(operation, TIMBRADO, "usuario")).isEqualTo(USUARIO);
            assertThat(text(operation, TIMBRADO, "password"))
                    .as("a password carrying &, < and a quote has to survive the envelope")
                    .isEqualTo(PASSWORD);
        }

        @Test
        @DisplayName(
                "sends the base64 of the document and ZIP=false, because one document travels and never an archive")
        void sendsTheDocumentAsBase64AndZipFalse() {
            authority.answer(soapAnswer(stampedReturn()));

            provider.stamp(request());

            var operation = operation(authority.lastRequest().body());
            assertThat(new String(Base64.getDecoder().decode(text(operation, TIMBRADO, "cfdiBase64")), UTF_8))
                    .as("cfdiBase64 is xs:base64Binary of the bytes to be sealed, not the XML inline")
                    .isEqualTo(REQUEST_XML);
            assertThat(text(operation, TIMBRADO, "ZIP"))
                    .as("ZIP is xs:boolean and this adapter always sends one document")
                    .isEqualTo("false");
        }
    }

    // ─── what is mapped ──────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("What the answer maps to")
    class WhatIsMapped {

        @Test
        @DisplayName("maps a process 200 with result 200 to the six facts, reading two of them from the timbre")
        void mapsAStampedAnswer() {
            authority.answer(soapAnswer(stampedReturn()));

            var answer = provider.stamp(request());

            assertThat(answer).isInstanceOfSatisfying(FiscalStampResult.Stamped.class, stamped -> {
                assertThat(stamped.uuid()).isEqualTo(UUID_FISCAL);
                assertThat(stamped.stampedAt()).isEqualTo(STAMPED_AT);
                assertThat(stamped.noCertificadoSat()).isEqualTo(NO_CERTIFICADO_SAT);
                assertThat(stamped.selloSat()).isEqualTo(SELLO_SAT);
                assertThat(stamped.rfcProvCertif())
                        .as("RfcProvCertif is not a field of the answer: it is read from the timbre")
                        .isEqualTo(RFC_PROV_CERTIF);
                assertThat(stamped.selloCfd())
                        .as("SelloCFD is not a field of the answer: it is read from the timbre")
                        .isEqualTo(SELLO_CFD);
            });
        }

        @Test
        @DisplayName("returns the provider's own document decoded from cfdiTimbrado, byte for byte")
        void returnsTheProvidersDocumentByteForByte() {
            authority.answer(soapAnswer(stampedReturn()));

            var answer = (FiscalStampResult.Stamped) provider.stamp(request());

            assertThat(answer.xml())
                    .as("the artefact is the authority's rendering, and rereading it must not change a byte (S10)")
                    .isEqualTo(STAMPED_CFDI)
                    .isNotEqualTo(REQUEST_XML);
            assertThat(answer.xml().getBytes(UTF_8))
                    .as("a document with non-ASCII names survives the base64 round trip in UTF-8")
                    .isEqualTo(STAMPED_CFDI.getBytes(UTF_8));
        }

        @Test
        @DisplayName("maps a process 200 with result 302 to a rejection carrying the vendor's code and sentence")
        void mapsARefusedDocumentToARejection() {
            authority.answer(soapAnswer(process(
                    200,
                    "[Servidor de pruebas] OK - Autenticación correcta, verifique los resultados por comprobante",
                    List.of(result(302, "El sello del emisor no es válido.", null)))));

            var answer = provider.stamp(request());

            assertThat(answer).isInstanceOfSatisfying(FiscalStampResult.Rejected.class, rejected -> {
                assertThat(rejected.issues()).singleElement().satisfies(issue -> {
                    assertThat(issue.code())
                            .as("the code is the provider's catalogue number, untranslated")
                            .isEqualTo("302");
                    assertThat(issue.message()).isEqualTo("El sello del emisor no es válido.");
                });
            });
        }

        @Test
        @DisplayName("names both levels when the call itself failed and a result still states the document's reason")
        void namesBothLevelsWhenTheCallFailed() {
            authority.answer(soapAnswer(process(
                    620,
                    "No fue posible procesar la solicitud.",
                    List.of(result(302, "El sello del emisor no es válido.", null)))));

            var answer = provider.stamp(request());

            assertThat(answer).isInstanceOfSatisfying(FiscalStampResult.Rejected.class, rejected -> {
                assertThat(rejected.issues())
                        .extracting(FiscalStampResult.Issue::code)
                        .as("the call's own code comes first, the document's second: an operator needs both")
                        .containsExactly("620", "302");
            });
        }

        @Test
        @DisplayName("reads the vendor's published success example: process 200, result 200, and the document decoded")
        void readsTheVendorsPublishedSuccessExample() {
            authority.answer(fixture("response_correcto_Timbrado.timbrarBase64.xml"));

            var answer = provider.stamp(request());

            assertThat(answer).isInstanceOfSatisfying(FiscalStampResult.Stamped.class, stamped -> {
                assertThat(stamped.uuid()).isEqualTo(UUID.fromString("9EE2199A-D122-4C9D-AA17-AE0BE86F5829"));
                assertThat(stamped.stampedAt())
                        .as("the answer's own fechaTimbrado is UTC and is what travels, not the timbre's local time")
                        .isEqualTo(OffsetDateTime.parse("2012-05-18T15:26:24.718Z"));
                assertThat(stamped.noCertificadoSat()).isEqualTo("30001000000100000801");
                assertThat(stamped.selloSat()).startsWith("LT9ADYIEeNEEpk05Ql/AAc2pBJANxm1D22TxcmJE");
                assertThat(stamped.rfcProvCertif())
                        .as("the 2012 timbre is version 1.0 and states no RfcProvCertif, so none is invented")
                        .isNull();
                assertThat(stamped.selloCfd())
                        .as("a version 1.0 timbre spells it selloCFD, and the attribute name is case-sensitive")
                        .isNull();
                assertThat(stamped.xml())
                        .as("the artefact is the vendor's own document, decoded")
                        .contains("<cfdi:Comprobante")
                        .contains("<tfd:TimbreFiscalDigital")
                        .contains("UUID=\"9EE2199A-D122-4C9D-AA17-AE0BE86F5829\"");
            });
        }

        @Test
        @DisplayName("reads the vendor's published error example as a rejection, not as a stamped document")
        void readsTheVendorsPublishedErrorExample() {
            authority.answer(fixture("response_soap_Timbrado.timbrarBase64.xml"));

            var answer = provider.stamp(request());

            assertThat(answer).isInstanceOfSatisfying(FiscalStampResult.Rejected.class, rejected -> {
                assertThat(rejected.issues()).singleElement().satisfies(issue -> {
                    assertThat(issue.code())
                            .as("the call was 200 and the document was not: the result's code is the answer")
                            .isEqualTo("401");
                    assertThat(issue.message())
                            .startsWith("La fecha del comprobante está fuera del rango de timbrado permitido.");
                });
            });
        }
    }

    // ─── what is refused ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("What a defect and what a transport failure look like")
    class WhatIsRefused {

        @Test
        @DisplayName("refuses a process 200 that carries no result, because a call that stamped nothing is not a stamp")
        void refusesASuccessfulCallWithNoResult() {
            authority.answer(soapAnswer(process(200, "OK", List.of())));

            assertThatThrownBy(() -> provider.stamp(request()))
                    .isInstanceOf(FiscalStampProviderDefectException.class)
                    .hasMessageContaining("200")
                    .hasMessageContaining("no result");
        }

        @Test
        @DisplayName("refuses a process status other than 200 that carries no result, naming the code and the sentence")
        void refusesAFailedCallWithNoResult() {
            authority.answer(soapAnswer(process(601, "El usuario o la contraseña son incorrectos.", List.of())));

            assertThatThrownBy(() -> provider.stamp(request()))
                    .isInstanceOf(FiscalStampProviderDefectException.class)
                    .hasMessageContaining("601")
                    .hasMessageContaining("El usuario o la contraseña son incorrectos.");
        }

        @Test
        @DisplayName("refuses a returned document whose timbre states another UUID than the result")
        void refusesADisagreedUuid() {
            var otherUuid = "00000000-1111-2222-3333-444444444444";
            authority.answer(soapAnswer(stampedReturnFor(STAMPED_CFDI.replace(UUID_FISCAL.toString(), otherUuid))));

            assertThatThrownBy(() -> provider.stamp(request()))
                    .isInstanceOf(FiscalStampProviderDefectException.class)
                    .hasMessageContaining(otherUuid)
                    .hasMessageContaining(UUID_FISCAL.toString());
        }

        @Test
        @DisplayName("refuses a returned document with no TimbreFiscalDigital at all")
        void refusesADocumentWithNoTimbre() {
            authority.answer(soapAnswer(stampedReturnFor(REQUEST_XML)));

            assertThatThrownBy(() -> provider.stamp(request()))
                    .isInstanceOf(FiscalStampProviderDefectException.class)
                    .hasMessageContaining("TimbreFiscalDigital");
        }

        @Test
        @DisplayName("refuses a returned document whose timbre echoes another seal than the one we sent")
        void refusesATimbreThatEchoesAnotherSeal() {
            var anotherSeal = "YW5vdGhlci1kb2N1bWVudC1zLXNlYWw=";
            authority.answer(soapAnswer(stampedReturnFor(STAMPED_CFDI.replace(SELLO_CFD, anotherSeal))));

            assertThatThrownBy(() -> provider.stamp(request()))
                    .as("the UUID pair can be self-consistent while the document is somebody else's, so the seal "
                            + "the timbre echoes is the only fact that ties the artefact to the document we sent (S10)")
                    .isInstanceOf(FiscalStampProviderDefectException.class)
                    .hasMessageContaining(anotherSeal)
                    .hasMessageContaining(SELLO_CFD);
        }

        @Test
        @DisplayName("refuses to send a document that states no Sello, because a seal-less document is never stamped")
        void refusesARequestThatStatesNoSello() {
            authority.answer(soapAnswer(stampedReturn()));
            var unsigned = REQUEST_XML.replace(" Sello=\"" + SELLO_CFD + "\"", "");

            assertThatThrownBy(() -> provider.stamp(new FiscalStampRequest(unsigned, "SFE0807172W8")))
                    .as("a document with no seal is one the authority would refuse, and a stamp over it could not "
                            + "be compared with the timbre that came back")
                    .isInstanceOf(FiscalStampProviderDefectException.class)
                    .hasMessageContaining("Sello");
            assertThat(authority.lastRequest())
                    .as("the refusal happens before the call: nothing of the document left the process")
                    .isNull();
        }

        @Test
        @DisplayName("raises a transport failure, not a rejection, when the authority answers HTTP 500")
        void raisesATransportFailureOnHttp500() {
            authority.answer(500, "the vendor is having a bad day");

            assertThatThrownBy(() -> provider.stamp(request()))
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

            assertThatThrownBy(() -> provider.stamp(request()))
                    .isInstanceOf(FiscalStampProviderTransportException.class)
                    .hasMessageContaining(":" + closedPort + OPERATION_PATH)
                    .hasMessageContaining(TIMEOUT_SECONDS + " s");
        }
    }

    // ─── the answer bodies this suite serves ─────────────────────────────────────────────────────────

    /** The stamped answer every mapping case starts from: process 200 with one result that stamped. */
    private static String stampedReturn() {
        return stampedReturnFor(STAMPED_CFDI);
    }

    private static String stampedReturnFor(String cfdi) {
        return process(
                200,
                "[Servidor de pruebas] OK - Autenticación correcta, verifique los resultados por comprobante",
                List.of(result(200, "CFDI correctamente validado y timbrado.", cfdi)));
    }

    /** One {@code resultados} element, with the cfdiTimbrado nil when the case does not carry a document. */
    private static String result(int status, String mensaje, String cfdi) {
        var document = cfdi == null
                ? "<ax216:cfdiTimbrado xsi:nil=\"true\"/>"
                : "<ax216:cfdiTimbrado>" + Base64.getEncoder().encodeToString(cfdi.getBytes(UTF_8))
                        + "</ax216:cfdiTimbrado>";
        return """
                <ax216:resultados xsi:type="ax216:CFDIResultadoCertificacion">\
                <ax216:cadenaOriginal>||1.1|%s|2026-11-15T10:15:30|%s|%s||</ax216:cadenaOriginal>\
                <ax216:certificadoSAT>%s</ax216:certificadoSAT>\
                %s\
                <ax216:fechaTimbrado>2026-11-15T10:15:30-06:00</ax216:fechaTimbrado>\
                <ax216:mensaje>%s</ax216:mensaje>\
                <ax216:qrCode xsi:nil="true"/>\
                <ax216:selloSAT>%s</ax216:selloSAT>\
                <ax216:status>%d</ax216:status>\
                <ax216:uuid>%s</ax216:uuid>\
                <ax216:versionTFD>1.1</ax216:versionTFD>\
                </ax216:resultados>""".formatted(
                        UUID_FISCAL,
                        SELLO_CFD,
                        NO_CERTIFICADO_SAT,
                        NO_CERTIFICADO_SAT,
                        document,
                        mensaje,
                        SELLO_SAT,
                        status,
                        UUID_FISCAL);
    }

    /** The {@code CFDICertificacion} the operation answers with, in the vendor's own prefix shape. */
    private static String process(int status, String mensaje, List<String> resultados) {
        return """
                <ns:return xmlns:ax216="%s" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"\
                 xsi:type="ax216:CFDICertificacion">\
                <ax216:mensaje>%s</ax216:mensaje>\
                <ax216:status>%d</ax216:status>\
                %s\
                </ns:return>""".formatted(TIMBRADO_XSD, mensaje, status, String.join("", resultados));
    }

    private static String soapAnswer(String returnElement) {
        return """
                <?xml version="1.0" encoding="utf-8"?>
                <soapenv:Envelope xmlns:soapenv="%s">\
                <soapenv:Body>\
                <ns:timbrarBase64Response xmlns:ns="%s">\
                %s\
                </ns:timbrarBase64Response>\
                </soapenv:Body>\
                </soapenv:Envelope>""".formatted(SOAP_ENVELOPE, TIMBRADO, returnElement);
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
        return children(body, TIMBRADO, "timbrarBase64").get(0);
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

    private static String text(Element parent, String namespace, String localName) {
        var elements = children(parent, namespace, localName);
        return elements.isEmpty() ? null : elements.get(0).getTextContent();
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
