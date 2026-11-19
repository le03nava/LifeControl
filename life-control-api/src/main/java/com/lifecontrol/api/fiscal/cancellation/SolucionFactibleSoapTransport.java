package com.lifecontrol.api.fiscal.cancellation;

import com.lifecontrol.api.config.fiscal.SolucionFactibleProperties;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * One Solución Factible endpoint, one account and one way of reading what the vendor answers — shared by the two
 * adapters that talk to it ({@code T3} of {@code odd/tasks/fiscal-cancellation-reconciliation.md}).
 *
 * <p><b>Why one hand-written envelope and one hand-read answer, for the third time.</b> The trade
 * {@code L18}(g) made for {@code timbrarBase64} and {@code L20}(h) kept for {@code enviarSolicitudCancelacion}: a
 * fixed envelope over {@link HttpClient}, parsed with a hardened {@link DocumentBuilderFactory}, adds no dependency and
 * keeps each suite a test of the vendor's contract rather than of a marshaller. What this class is, then, is not a
 * client library: it is the <b>one</b> copy of the three facts all these operations share — the endpoint and its
 * validated timeout, the credentials escaped once, and an answer read by local name whatever the vendor qualifies.</p>
 *
 * <p><b>Why it was extracted rather than copied.</b> {@code SolucionFactibleFiscalCancellationReconciler} sends two
 * more operations to the same host with the same account, and the half that is easy to get wrong — the escaped
 * credential children, the parser that refuses a DOCTYPE, the local-name search that accepts an answer bare or inside
 * a {@code soapenv:Body} — would otherwise be two copies free to drift. The adapter's behaviour did not change when its
 * helpers moved here; its own suites are the check.</p>
 *
 * <p><b>What it deliberately does not do.</b> It knows no operation name, no mapping and no verdict: the
 * {@code SOAPAction}, the envelope and the reading of the answer belong to each adapter, and this class only carries
 * them. It does not retry, it does not cache a token (this vendor issues none), and it never logs the credentials it
 * renders.</p>
 */
final class SolucionFactibleSoapTransport {

    static final String SOAP_ENVELOPE = "http://schemas.xmlsoap.org/soap/envelope/";

    static final String CANCELACION = "http://timbrado.ws.cfdi.solucionfactible.com";

    /** {@code xsi:nil="true"} is the vendor's own way of saying a field is absent. */
    private static final String XSI = "http://www.w3.org/2001/XMLSchema-instance";

    private static final String ENDPOINT_PROPERTY = "app.fiscal.provider.solucion-factible.endpoint";
    private static final String TIMEOUT_PROPERTY = "app.fiscal.provider.solucion-factible.timeout-seconds";

    /** The code element every one of these operations answers first, and the one the call level is read from. */
    static final String STATUS = "status";

    private final URI endpoint;
    private final String usuario;
    private final String password;
    private final Duration timeout;
    private final HttpClient httpClient;

    /**
     * Binds one endpoint and one account, validating both before the first call.
     *
     * <p>The endpoint is parsed here rather than at the first call on purpose: a value that is not an absolute
     * {@code http(s)} URL is a configuration mistake, and the boot is where an operator sees it. Every adapter
     * reuses {@code app.fiscal.provider.solucion-factible.*} whole — the same vendor, the same service URL and the same
     * account stamp and cancel — so no second properties record and no second secret exist to drift from this one.</p>
     *
     * @param properties the bound {@code app.fiscal.provider.solucion-factible} values
     * @throws IllegalStateException when the endpoint is absent or is not an absolute {@code http(s)} URL
     */
    SolucionFactibleSoapTransport(SolucionFactibleProperties properties) {
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

    /** The validated endpoint, so an adapter can report which host it talked to without re-reading the properties. */
    URI endpoint() {
        return endpoint;
    }

    /**
     * The two credential children every operation of this service carries, in the WSDL's order and escaped.
     *
     * <p>One copy, because a password is data and not markup: the vendor's own test account is a valid username with
     * characters that are markup in XML, and an adapter that forgot one of the five replacements would send a
     * different password than the one configured.</p>
     *
     * @param prefix the namespace prefix the vendor's operation declares — {@code timb} for all of them today
     * @return {@code <prefix:usuario>…</prefix:usuario><prefix:password>…</prefix:password>}
     */
    String credentialsAs(String prefix) {
        return "<" + prefix + ":usuario>" + escape(usuario) + "</" + prefix + ":usuario>" + "<" + prefix + ":password>"
                + escape(password) + "</" + prefix + ":password>";
    }

    /**
     * Posts one envelope and answers its body, or raises the transport failure that says the call broke.
     *
     * <p>A non-2xx response is a transport failure and never a verdict: it says the call did not get a reading, which is
     * the opposite of the authority having judged a document. The two failures carry the same three facts — endpoint,
     * timeout and elapsed time — so a log line is enough to tell a hung vendor from a refused connection.</p>
     *
     * @param soapAction the quoted {@code Action} header of the operation being called
     * @param envelope the complete SOAP 1.1 envelope
     * @param startedAt the {@link System#nanoTime()} the call started at, for the failure's elapsed millis
     * @return the response body, already decoded as UTF-8
     */
    String send(String soapAction, String envelope, long startedAt) {
        var httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "text/xml; charset=utf-8")
                .header("SOAPAction", soapAction)
                .POST(HttpRequest.BodyPublishers.ofString(envelope, StandardCharsets.UTF_8))
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
            // The interrupt is the caller's, so it is restored before the failure is reported: a swallowed one
            // would leave the transaction unable to be cancelled and the pool unable to notice a shutdown.
            Thread.currentThread().interrupt();
            throw FiscalStampProviderTransportException.unreachable(
                    endpoint.toString(), timeout, elapsedMillis(startedAt), interrupted);
        }
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    // ─── reading the answer ──────────────────────────────────────────────────────────────────────────

    /**
     * The operation's answer element, found by local name, bare or inside a SOAP body.
     *
     * <p>By local name and not by namespace because the vendor's own published answers carry no namespace at all, and
     * this tree has never been told the qualification is part of the contract. The bare form is not tolerated by
     * accident: it is what the vendor publishes, and a parser that demanded an envelope would refuse the very examples
     * these adapters are pinned against.</p>
     *
     * @param document the parsed body
     * @param response the answer element's local name, which is the one thing that differs per operation
     */
    static Element answerOf(Document document, String response) {
        var faults = document.getElementsByTagNameNS("*", "Fault");
        if (faults.getLength() > 0) {
            var fault = (Element) faults.item(0);
            throw FiscalStampProviderDefectException.unreadable("the answer is a SOAP Fault: "
                    + firstText(fault, "faultstring") + " / " + firstText(fault, "faultcode"));
        }
        var scope = document.getDocumentElement();
        var bodies = document.getElementsByTagNameNS("*", "Body");
        if (bodies.getLength() > 0) {
            scope = (Element) bodies.item(0);
        }
        if (response.equalsIgnoreCase(scope.getLocalName() == null ? scope.getNodeName() : scope.getLocalName())) {
            return scope;
        }
        for (var descendant : descendants(scope)) {
            var name = descendant.getLocalName() == null ? descendant.getNodeName() : descendant.getLocalName();
            if (response.equalsIgnoreCase(name)) {
                return descendant;
            }
        }
        throw FiscalStampProviderDefectException.unreadable("the answer carries no " + response + " element, so "
                + "there is nothing in it that can be read as the operation's answer");
    }

    static List<Element> descendants(Element scope) {
        var elements = scope.getElementsByTagName("*");
        List<Element> found = new ArrayList<>(elements.getLength());
        for (var index = 0; index < elements.getLength(); index++) {
            found.add((Element) elements.item(index));
        }
        return found;
    }

    static String firstText(Element scope, String localName) {
        var elements = scope.getElementsByTagNameNS("*", localName);
        return elements.getLength() == 0 ? "none" : elements.item(0).getTextContent();
    }

    /**
     * The answer's {@code status} as a number.
     *
     * <p>Numeric and not a string, because every operation of this service states it as {@code xs:int} and the mapping
     * is a decision over the number; a code that is not a number is a defect, not a verdict.</p>
     */
    static int statusOf(Element scope, String what) {
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

    static String text(Element scope, String name) {
        for (var element : children(scope, name)) {
            // An `xsi:nil="true"` child is the vendor's own way of saying the field is absent, and an empty string
            // is not the same fact as a missing one.
            if ("true".equals(element.getAttributeNS(XSI, "nil"))) {
                continue;
            }
            var value = element.getTextContent();
            return value == null ? null : value.trim();
        }
        return null;
    }

    /** Direct children by local name, whatever namespace the vendor qualifies them with. */
    static List<Element> children(Element parent, String localName) {
        List<Element> found = new ArrayList<>();
        for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element) {
                var name = element.getLocalName() == null ? element.getNodeName() : element.getLocalName();
                if (localName.equals(name)) {
                    found.add(element);
                }
            }
        }
        return found;
    }

    /**
     * Parses XML this process did not produce, with a parser that cannot be talked into fetching anything.
     *
     * <p>The input is a third party's answer, so the factory refuses a DOCTYPE outright and disables every route from
     * it — external general and parameter entities, and external DTD loading — which is what closes an XXE in a client
     * that has no business resolving any external reference.</p>
     */
    static Document parse(String xml, String what) {
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

    static String quoted(String message) {
        return message == null || message.isBlank() ? "no message" : "\"" + message + "\"";
    }

    static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
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
}
