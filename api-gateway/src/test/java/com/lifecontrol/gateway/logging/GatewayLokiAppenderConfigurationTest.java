package com.lifecontrol.gateway.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Pins that the gateway ships a logback configuration that actually pushes to Loki.
 *
 * <p>Why this exists: the module depends on {@code loki-logback-appender} and declares
 * {@code loki.url}, {@code loki.batchSize} and {@code loki.timeout}, and all of it was dead — with no
 * {@code logback-spring.xml} on the classpath Logback never instantiates the appender, so
 * {@code GET :3100/loki/api/v1/label/application/values} answered {@code ["lifecontrol-api"]} while
 * the gateway, the service that authenticates and cuts every request first, pushed nothing at all.
 * The label set is asserted too, because a second application that arrives under a different label
 * shape is as invisible in a Loki query as one that never arrives, and the trace ids are asserted
 * because a line that cannot be joined to its trace turns a log query into a dead end.
 *
 * <p>This is a file test: it parses the shipped resource and never starts a Spring context or opens a
 * connection, so it fails when the configuration stops declaring the appender and stays green when
 * Loki happens to be down.
 */
@DisplayName("api-gateway logback-spring.xml Loki appender")
class GatewayLokiAppenderConfigurationTest {

    /** The appender class the module's {@code loki-logback-appender} dependency provides. */
    private static final String LOKI_APPENDER_CLASS = "com.github.loki4j.logback.Loki4jAppender";

    @Test
    @DisplayName("declares the Loki appender, wires it to the shipped properties and attaches it to the root logger")
    void declaresTheLokiAppenderFromTheShippedProperties() throws Exception {
        Document document = shippedConfiguration();

        var lokiAppenderNames = new ArrayList<String>();
        for (Element appender : elements(document.getDocumentElement(), "appender")) {
            if (LOKI_APPENDER_CLASS.equals(appender.getAttribute("class"))) {
                lokiAppenderNames.add(appender.getAttribute("name"));
            }
        }

        assertThat(lokiAppenderNames)
                .as("the gateway must declare a " + LOKI_APPENDER_CLASS)
                .hasSize(1);

        var lokiAppenderName = lokiAppenderNames.get(0);
        var lokiAppender = appenderByName(document, lokiAppenderName);

        assertThat(rootLoggerAppenders(document))
                .as("an appender that is declared but not referenced from the root logger never receives an "
                        + "event: declaring it is only half of shipping logs")
                .contains(lokiAppenderName);

        assertThat(springPropertySource(document, "lokiUrl"))
                .as("reading loki.url through a springProperty keeps the URL in application.properties, where "
                        + "every environment already overrides it")
                .isEqualTo("loki.url");

        assertThat(textOf(child(lokiAppender, "http"), "url"))
                .as("a hardcoded URL would push to the wrong host as soon as LOKI_URL changes")
                .isEqualTo("${lokiUrl}");

        assertThat(springPropertySource(document, "lokiBatchSize"))
                .as("application.properties declares loki.batchSize; the appender must read it instead of "
                        + "silently keeping the library default")
                .isEqualTo("loki.batchSize");

        assertThat(springPropertySource(document, "lokiBatchTimeoutMs"))
                .as("loki4j takes the batch timeout as a millisecond long, so the property that feeds it has to "
                        + "be one too: a Duration string here failed conversion and left the library default")
                .isEqualTo("loki.batchTimeoutMs");

        assertThat(textOf(lokiAppender, "batchTimeoutMs"))
                .as("a batch timeout declared in the properties and absent here is the same dead knob in another "
                        + "spelling")
                .isEqualTo("${lokiBatchTimeoutMs}");

        assertThat(textOf(lokiAppender, "batchMaxItems"))
                .as("a batch size that is declared in the properties but absent here is a knob that does nothing")
                .isEqualTo("${lokiBatchSize}");

        assertThat(textOf(child(child(lokiAppender, "format"), "label"), "pattern"))
                .as("the label set has to match the API's exactly, or a Loki query for one application cannot "
                        + "be compared with the other")
                .isEqualTo("application=${appName},host=${HOSTNAME},level=%level");

        assertThat(springPropertySource(document, "appName"))
                .as("the application label comes from spring.application.name, which is what makes it "
                        + "'api-gateway' instead of a hardcoded string")
                .isEqualTo("spring.application.name");

        assertThat(resolveLogbackVariables(document, textOf(child(child(lokiAppender, "format"), "message"), "pattern")))
                .as("a Loki line without the trace ids cannot be joined to its trace in Tempo")
                .contains("%X{traceId", "%X{spanId");
    }

    @Test
    @DisplayName("keeps the console line joinable to its trace")
    void consolePatternCarriesTheTraceIds() throws Exception {
        Document document = shippedConfiguration();
        var console = appenderByName(document, "CONSOLE");

        assertThat(rootLoggerAppenders(document))
                .as("the console line is what a developer reads while following a request across both services")
                .contains("CONSOLE");

        assertThat(resolveLogbackVariables(document, textOf(child(console, "encoder"), "pattern")))
                .as("without the ids the console line cannot be joined to the trace or to the API's line")
                .contains("%X{traceId", "%X{spanId");
    }

    /** Parses the configuration the module ships; the DOCTYPE refusal keeps entity expansion out of the test. */
    private static Document shippedConfiguration() throws Exception {
        try (InputStream in = GatewayLokiAppenderConfigurationTest.class.getResourceAsStream("/logback-spring.xml")) {
            assertThat(in)
                    .as("without a logback-spring.xml on the classpath the appender is never instantiated and the "
                            + "gateway ships no logs to Loki, which is what this test exists to prevent")
                    .isNotNull();
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(in);
        }
    }

    private static Element appenderByName(Document document, String name) {
        for (Element appender : elements(document.getDocumentElement(), "appender")) {
            if (name.equals(appender.getAttribute("name"))) {
                return appender;
            }
        }
        throw new AssertionError("no appender named " + name + " in logback-spring.xml");
    }

    private static List<String> rootLoggerAppenders(Document document) {
        var root = (Element) document.getElementsByTagName("root").item(0);
        var names = new ArrayList<String>();
        for (Element reference : elements(root, "appender-ref")) {
            names.add(reference.getAttribute("ref"));
        }
        return names;
    }

    /** The value of the {@code source} attribute of the {@code springProperty} bound to {@code name}. */
    private static String springPropertySource(Document document, String name) {
        for (Element element : elements(document.getDocumentElement(), "springProperty")) {
            if (name.equals(element.getAttribute("name"))) {
                return element.getAttribute("source");
            }
        }
        return null;
    }

    /** The direct child elements called {@code tagName}; nesting matters, so this never searches deeper. */
    private static List<Element> elements(Element parent, String tagName) {
        var children = parent.getChildNodes();
        var matches = new ArrayList<Element>();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && tagName.equals(node.getNodeName())) {
                matches.add((Element) node);
            }
        }
        return matches;
    }

    private static Element child(Element parent, String tagName) {
        var matches = elements(parent, tagName);
        if (matches.isEmpty()) {
            throw new AssertionError("no <" + tagName + "> inside <" + parent.getNodeName() + ">");
        }
        return matches.get(0);
    }

    private static String textOf(Element element, String tagName) {
        return child(element, tagName).getTextContent().trim();
    }

    /**
     * Follows a value that is nothing but a reference to a {@code <property>} declared in the file, so
     * the pattern can live in one place without hiding what it contains from this test.
     */
    private static String resolveLogbackVariables(Document document, String value) {
        var variables = new HashMap<String, String>();
        for (Element property : elements(document.getDocumentElement(), "property")) {
            variables.put(property.getAttribute("name"), property.getAttribute("value"));
        }
        var resolved = value;
        while (resolved.startsWith("${") && resolved.endsWith("}")) {
            var reference = variables.get(resolved.substring(2, resolved.length() - 1));
            if (reference == null) {
                break;
            }
            resolved = reference;
        }
        return resolved;
    }
}
