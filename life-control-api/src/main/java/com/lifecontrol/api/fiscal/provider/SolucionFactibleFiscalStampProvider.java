package com.lifecontrol.api.fiscal.provider;

import com.lifecontrol.api.config.fiscal.SolucionFactibleProperties;
import com.lifecontrol.api.fiscal.FiscalStampProvider;
import com.lifecontrol.api.fiscal.FiscalStampRequest;
import com.lifecontrol.api.fiscal.FiscalStampResult;
import com.lifecontrol.api.fiscal.exception.FiscalStampProviderDefectException;
import com.lifecontrol.api.fiscal.exception.FiscalStampProviderTransportException;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Solución Factible's {@code timbrarBase64} operation, behind {@link FiscalStampProvider}
 * ({@code T7a}, decisions {@code L18}(a) to {@code L18}(g) of {@code odd/tasks/cfdi-fiscal-document.md}).
 *
 * <p><b>One hand-written envelope and one hand-read answer, because a SOAP stack would be the subject of the
 * review instead of the mapping.</b> The call is fixed: a {@code <timbrarBase64>} with four children against one
 * namespace, and an answer whose shape the WSDL has not changed in fifteen years ({@code L18}(b)). CXF, JAX-WS
 * or {@code WebServiceTemplate} would bring a marshaller, a binding generator or a build plugin to this tree's
 * first SOAP call, and every rule below — the two-level status, the timbre read, the transport/rejection split —
 * would then be pinned against the marshaller's output rather than against the vendor's own published example.
 * So: {@link HttpClient} for the one request, {@link DocumentBuilderFactory} for the one answer, and the vendor's
 * example response committed as a fixture. The limit is stated and accepted: this class understands <b>one</b>
 * operation and <b>one</b> answer; {@code T8}'s cancellation will be a second envelope with its own test, not a
 * general SOAP client.</p>
 *
 * <p><b>Why the answer is mapped by the first result and not by the process status.</b> Solución Factible answers
 * on two levels, and a successful call can carry a refused document: process {@code 200} with
 * {@code resultados[0].status = 302} is a document the authority would not seal ({@code L18}(e)). So {@code 200}
 * is the <b>call</b>, never the document. A process {@code 200} with no result is a defect — a call that stamped
 * nothing is not a stamp — and a process status other than {@code 200} that carries no result is a defect too,
 * because there is no per-document code to report and inventing one would put the vendor's outage in the
 * operator's catalogue of things to fix. Both are {@link FiscalStampProviderDefectException}, never a
 * {@link FiscalStampResult.Rejected}, which is reserved for an answer that judged a document.</p>
 *
 * <p><b>Why two of the six facts are read from the document and not from the answer.</b> The operation returns
 * {@code uuid}, {@code fechaTimbrado}, {@code certificadoSAT} and {@code selloSAT}, and no
 * {@code RfcProvCertif} or {@code SelloCFD}: those two are attributes of the {@code TimbreFiscalDigital} inside
 * the {@code cfdiTimbrado} the provider returns. Reading them from the document it is handing over is not a
 * workaround but the correct order — the timbre is the authority's statement, and a field beside it would be a
 * second copy free to disagree ({@code L18}(c)). A returned document with no timbre, or whose timbre names
 * another UUID than the result, is refused instead of stored.</p>
 *
 * <p><b>What this adapter deliberately does not do.</b> It does not idempotency ({@code L12}(h): schema and
 * attempt), it does not seal ({@code T7b}), it does not retry, and it sends nothing derived from
 * {@link FiscalStampRequest#emisorRfc()} — the operation has no field for it, the issuer is inside the XML, and
 * the vendor addresses the call by the account's credentials. It also emits no {@code ZIP}: the operation accepts
 * a base64 zip of several documents as well as one document, and this tree stamps one document per call, so the
 * flag is the constant {@code false} and never a caller's choice.</p>
 */
public final class SolucionFactibleFiscalStampProvider implements FiscalStampProvider {

    private static final Logger logger = LoggerFactory.getLogger(SolucionFactibleFiscalStampProvider.class);

    private static final String SOAP_ENVELOPE = "http://schemas.xmlsoap.org/soap/envelope/";
    private static final String TIMBRADO = "http://timbrado.ws.cfdi.solucionfactible.com";
    private static final String TIMBRADO_XSD = "http://timbrado.ws.cfdi.solucionfactible.com/xsd";
    private static final String TIMBRE = "http://www.sat.gob.mx/TimbreFiscalDigital";
    private static final String XSI = "http://www.w3.org/2001/XMLSchema-instance";

    /** SOAP 1.1 carries the operation in the {@code Action} header, quoted as the HTTP binding requires. */
    private static final String SOAP_ACTION = "\"urn:timbrarBase64\"";

    private static final String ENDPOINT_PROPERTY = "app.fiscal.provider.solucion-factible.endpoint";
    private static final String TIMEOUT_PROPERTY = "app.fiscal.provider.solucion-factible.timeout-seconds";

    /**
     * {@code 200} on <b>both</b> levels, and the two are not the same fact: it means "the call succeeded" at the
     * process level and "the authority stamped this document" at the result level. Sharing the constant is the
     * catalogue's own convention, not an accident — the vendor numbers the two levels in one scheme, which is
     * why {@code L18}(e) insists the mapping read both.
     */
    private static final int STATUS_OK = 200;

    private static final String STATUS = "status";
    private static final String MENSAJE = "mensaje";
    private static final String RESULTADOS = "resultados";
    private static final String UUID_ATTRIBUTE = "UUID";
    private static final String RFC_PROV_CERTIF = "RfcProvCertif";
    private static final String SELLO_CFD = "SelloCFD";

    private final URI endpoint;
    private final String usuario;
    private final String password;
    private final Duration timeout;
    private final HttpClient httpClient;

    /**
     * Binds the adapter to one endpoint and one account, and validates both before the first call.
     *
     * <p>The endpoint is parsed here rather than at the first stamp on purpose: a value that is not an absolute
     * {@code http(s)} URL is a configuration mistake, and the boot is where an operator sees it. The two reasons
     * a value can be unusable are named — absent and not-a-URL — because they have different fixes.</p>
     *
     * @param properties the bound {@code app.fiscal.provider.solucion-factible} values
     * @throws IllegalStateException when the endpoint is absent or is not an absolute {@code http(s)} URL
     */
    public SolucionFactibleFiscalStampProvider(SolucionFactibleProperties properties) {
        Objects.requireNonNull(properties, "the Solución Factible adapter is configured, never defaulted");
        this.endpoint = endpointOf(properties.endpoint());
        this.usuario = Objects.requireNonNull(
                properties.usuario(), "app.fiscal.provider.solucion-factible.usuario is required");
        this.password = Objects.requireNonNull(
                properties.password(), "app.fiscal.provider.solucion-factible.password is required");
        this.timeout = Duration.ofSeconds(
                Objects.requireNonNull(properties.timeoutSeconds(), TIMEOUT_PROPERTY + " is required")
                        .longValue());
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public FiscalStampResult stamp(FiscalStampRequest request) {
        Objects.requireNonNull(request, "a stamp carries the document to send");
        var startedAt = System.nanoTime();
        var body = send(request, startedAt);
        var answer = map(body);
        logger.info(
                "Solución Factible stamp call: endpoint={}, elapsedMillis={}, answer={}",
                endpoint,
                elapsedMillis(startedAt),
                answer.getClass().getSimpleName());
        return answer;
    }

    // ─── the call ────────────────────────────────────────────────────────────────────────────────────

    private String send(FiscalStampRequest request, long startedAt) {
        var httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "text/xml; charset=utf-8")
                .header("SOAPAction", SOAP_ACTION)
                .POST(HttpRequest.BodyPublishers.ofString(envelopeFor(request), StandardCharsets.UTF_8))
                .build();
        try {
            var response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw FiscalStampProviderTransportException.answeredWithStatus(
                        endpoint.toString(), timeout, elapsedMillis(startedAt), response.statusCode());
            }
            return response.body();
        } catch (IOException transportFailed) {
            throw FiscalStampProviderTransportException.unreachable(
                    endpoint.toString(), timeout, elapsedMillis(startedAt), transportFailed);
        } catch (InterruptedException interrupted) {
            // The interrupt is the caller's, so it is restored before it is reported: a swallowed one would leave
            // the transaction unable to be cancelled and the pool unable to notice a shutdown.
            Thread.currentThread().interrupt();
            throw FiscalStampProviderTransportException.unreachable(
                    endpoint.toString(), timeout, elapsedMillis(startedAt), interrupted);
        }
    }

    /**
     * The one fixed envelope: SOAP 1.1, document style, four children in the WSDL's order.
     *
     * <p>Every child is written in the operation's namespace, because the operation's element is and a SOAP body
     * is read as a document: an unqualified child would arrive in no namespace, which a document-style server
     * matches against nothing. The document travels as the base64 of the bytes the builder produced — the vendor
     * seals what it receives, so this string is the only copy of what was sent — and the credentials are escaped
     * because a password is data and not markup.</p>
     */
    private String envelopeFor(FiscalStampRequest request) {
        var document = Base64.getEncoder().encodeToString(request.xml().getBytes(StandardCharsets.UTF_8));
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<soapenv:Envelope xmlns:soapenv=\"" + SOAP_ENVELOPE + "\" xmlns:timb=\"" + TIMBRADO + "\">"
                + "<soapenv:Header/>"
                + "<soapenv:Body>"
                + "<timb:timbrarBase64>"
                + "<timb:usuario>" + escape(usuario) + "</timb:usuario>"
                + "<timb:password>" + escape(password) + "</timb:password>"
                + "<timb:cfdiBase64>" + document + "</timb:cfdiBase64>"
                + "<timb:ZIP>false</timb:ZIP>"
                + "</timb:timbrarBase64>"
                + "</soapenv:Body>"
                + "</soapenv:Envelope>";
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    // ─── the answer ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Maps the answer by the first result, and refuses anything that is neither a stamp nor a rejection.
     *
     * <p>The order of the branches is the rule: the process status decides whether the <b>call</b> worked, the
     * first result decides what happened to the <b>document</b>, and an answer that skips either level is a
     * defect instead of a default ({@code L18}(e)). When the call itself failed and a result still explains the
     * document, both codes travel — the operator needs the call's sentence to fix the account and the document's
     * to fix the request.</p>
     */
    private FiscalStampResult map(String body) {
        var answer = returnOf(parse(body, "the SOAP answer"));
        var processStatus = statusOf(answer, "the answer");
        var processMessage = text(answer, MENSAJE);
        var results = children(answer, TIMBRADO_XSD, RESULTADOS);

        if (processStatus != STATUS_OK) {
            if (results.isEmpty()) {
                throw FiscalStampProviderDefectException.failedCallWithoutResult(processStatus, processMessage);
            }
            List<FiscalStampResult.Issue> issues = new ArrayList<>();
            issues.add(new FiscalStampResult.Issue(String.valueOf(processStatus), processMessage));
            issues.add(issueOf(results.get(0)));
            return new FiscalStampResult.Rejected(issues);
        }
        if (results.isEmpty()) {
            throw FiscalStampProviderDefectException.successfulCallWithoutResult(processStatus, processMessage);
        }
        return stampedOrRejected(results.get(0));
    }

    private FiscalStampResult stampedOrRejected(Element result) {
        var status = statusOf(result, "the first result");
        var message = text(result, MENSAJE);
        if (status != STATUS_OK) {
            return new FiscalStampResult.Rejected(
                    List.of(new FiscalStampResult.Issue(String.valueOf(status), message)));
        }

        var uuid = uuidOf(text(result, "uuid"), "the first result states no UUID");
        var stampedAt = instantOf(text(result, "fechaTimbrado"));
        var xml = decoded(text(result, "cfdiTimbrado"));
        var timbre = timbreOf(xml);
        requireSameUuid(timbre, uuid);

        return new FiscalStampResult.Stamped(
                uuid,
                stampedAt,
                attribute(timbre, RFC_PROV_CERTIF),
                text(result, "certificadoSAT"),
                attribute(timbre, SELLO_CFD),
                text(result, "selloSAT"),
                xml);
    }

    private static FiscalStampResult.Issue issueOf(Element result) {
        return new FiscalStampResult.Issue(String.valueOf(statusOf(result, "the first result")), text(result, MENSAJE));
    }

    /**
     * The document the authority signed, decoded from {@code cfdiTimbrado}, byte for byte.
     *
     * <p>Decoded as <b>UTF-8</b> because that is the encoding the tree's own builder emits and the vendor echoes
     * the bytes it received; the string is then the artefact exactly as it arrived ({@code S10}). The MIME decoder
     * is used because base64 in a SOAP body may be line-wrapped, and a wrapped payload is not a defect.</p>
     */
    private static String decoded(String cfdiTimbrado) {
        if (cfdiTimbrado == null || cfdiTimbrado.isBlank()) {
            throw FiscalStampProviderDefectException.unreadable("the first result states no cfdiTimbrado");
        }
        try {
            return new String(Base64.getMimeDecoder().decode(cfdiTimbrado), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notBase64) {
            throw FiscalStampProviderDefectException.unreadable("cfdiTimbrado is not base64", notBase64);
        }
    }

    private static Element timbreOf(String xml) {
        var document = parse(xml, "the cfdiTimbrado it returned");
        var timbres = document.getElementsByTagNameNS(TIMBRE, "TimbreFiscalDigital");
        if (timbres.getLength() == 0) {
            throw FiscalStampProviderDefectException.noTimbre();
        }
        return (Element) timbres.item(0);
    }

    private static void requireSameUuid(Element timbre, UUID uuid) {
        var stated = attribute(timbre, UUID_ATTRIBUTE);
        var statedUuid = uuidOf(stated, "its timbre states no UUID");
        if (!statedUuid.equals(uuid)) {
            throw FiscalStampProviderDefectException.timbreDisagrees(stated, uuid);
        }
    }

    // ─── reading the answer ──────────────────────────────────────────────────────────────────────────

    /**
     * The operation's answer element, found by local name inside the SOAP body.
     *
     * <p>By local name and not by namespace because the answer's prefix is the vendor's serialization of the
     * operation's namespace — its own published example uses {@code ns:return} whose children are in the
     * {@code /xsd} namespace — and this tree has never been told the body's qualification is part of the contract.
     * The fields inside it <b>are</b> read by namespace, because those the WSDL does state.</p>
     */
    private static Element returnOf(Document document) {
        var faults = document.getElementsByTagNameNS(SOAP_ENVELOPE, "Fault");
        if (faults.getLength() > 0) {
            var fault = (Element) faults.item(0);
            throw FiscalStampProviderDefectException.unreadable("the answer is a SOAP Fault: "
                    + firstText(fault, "faultstring") + " / " + firstText(fault, "faultcode"));
        }
        var bodies = document.getElementsByTagNameNS(SOAP_ENVELOPE, "Body");
        if (bodies.getLength() == 0) {
            throw FiscalStampProviderDefectException.unreadable("the answer has no soapenv:Body");
        }
        var body = (Element) bodies.item(0);
        var descendants = body.getElementsByTagName("*");
        for (var index = 0; index < descendants.getLength(); index++) {
            var element = (Element) descendants.item(index);
            if ("return".equals(element.getLocalName()) || "return".equals(element.getNodeName())) {
                return element;
            }
        }
        throw FiscalStampProviderDefectException.unreadable("the answer carries no return element");
    }

    private static String firstText(Element scope, String localName) {
        var elements = scope.getElementsByTagNameNS("*", localName);
        return elements.getLength() == 0 ? "none" : elements.item(0).getTextContent();
    }

    private static int statusOf(Element scope, String what) {
        var value = text(scope, STATUS);
        if (value == null) {
            throw FiscalStampProviderDefectException.unreadable(what + " states no status");
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException notANumber) {
            throw FiscalStampProviderDefectException.unreadable(what + " states status '" + value + "'", notANumber);
        }
    }

    private static String text(Element scope, String name) {
        for (var element : children(scope, TIMBRADO_XSD, name)) {
            // An `xsi:nil="true"` child is the vendor's own way of saying the field is absent — its published
            // error example nils nine of them — and an empty string is not the same fact as a missing one.
            if ("true".equals(element.getAttributeNS(XSI, "nil"))) {
                continue;
            }
            var value = element.getTextContent();
            return value == null ? null : value.trim();
        }
        return null;
    }

    private static String attribute(Element element, String name) {
        var value = element.getAttribute(name);
        return value == null || value.isEmpty() ? null : value;
    }

    private static List<Element> children(Element parent, String namespace, String localName) {
        List<Element> found = new ArrayList<>();
        for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element
                    && localName.equals(element.getLocalName())
                    && namespace.equals(element.getNamespaceURI())) {
                found.add(element);
            }
        }
        return found;
    }

    private static UUID uuidOf(String value, String whenMissing) {
        if (value == null || value.isBlank()) {
            throw FiscalStampProviderDefectException.unreadable(whenMissing);
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException notAUuid) {
            throw FiscalStampProviderDefectException.unreadable("'" + value + "' is not a UUID", notAUuid);
        }
    }

    /**
     * The instant the authority stamped, parsed with its offset.
     *
     * <p>The port states an {@link OffsetDateTime} and the vendor's own published answer sends
     * {@code 2012-05-18T15:26:24.718Z}, so an offset is what actually arrives. An answer without one is refused
     * rather than read in the deployment's fiscal zone: the instant would be off by hours, and the timbre's own
     * local time is already in the document for a reader who needs it.</p>
     */
    private static OffsetDateTime instantOf(String value) {
        if (value == null || value.isBlank()) {
            throw FiscalStampProviderDefectException.unreadable("the first result states no fechaTimbrado");
        }
        try {
            return OffsetDateTime.parse(value.trim());
        } catch (DateTimeParseException notAnInstant) {
            throw FiscalStampProviderDefectException.unreadable(
                    "fechaTimbrado '" + value + "' is not an ISO instant with an offset", notAnInstant);
        }
    }

    /**
     * Parses XML this process did not produce, with a parser that cannot be talked into fetching anything.
     *
     * <p>Both documents read here come from the network: the SOAP answer, and the {@code cfdiTimbrado} inside it.
     * So the factory refuses a DOCTYPE outright and disables every route from it — external general and parameter
     * entities, and external DTD loading — which is what closes an XXE in a client that has no business resolving
     * any external reference. It is the hardening the tree applies to its own XML elsewhere, applied to the one
     * place where the input is a third party's.</p>
     */
    private static Document parse(String xml, String what) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException | SAXException | IOException cannotParse) {
            throw FiscalStampProviderDefectException.unreadable(what + " is not well-formed XML", cannotParse);
        }
    }

    // ─── construction ───────────────────────────────────────────────────────────────────────────────

    private static URI endpointOf(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(ENDPOINT_PROPERTY + " is required: the adapter has no default endpoint, "
                    + "because a default would send a tenant's document to whichever host the default names (L18).");
        }
        try {
            var uri = new URI(value.trim());
            var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (!uri.isAbsolute() || uri.getHost() == null || !(scheme.equals("http") || scheme.equals("https"))) {
                throw new IllegalStateException(ENDPOINT_PROPERTY + " = '" + value
                        + "' is not an absolute http(s) URL of the vendor's Timbrado service, so there is no "
                        + "authority to call.");
            }
            return uri;
        } catch (URISyntaxException notAUri) {
            throw new IllegalStateException(
                    ENDPOINT_PROPERTY + " = '" + value + "' is not a URI at all: " + notAUri, notAUri);
        }
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}
