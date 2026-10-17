package com.lifecontrol.api.config.logbook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Pins the logger that decides whether Logbook emits anything at all.
 * <p>
 * The suite's active configuration is {@code logback-test.xml}, which keeps this logger
 * muted on purpose, so nothing else in the suite would notice if
 * {@code logback-spring.xml} stopped enabling it.
 */
@DisplayName("logback-spring.xml Logbook logger pinning")
class LogbookLogbookLoggerPinningTest {

    @Test
    @DisplayName("enables exactly org.zalando.logbook.Logbook at TRACE and does not re-mute the package")
    void pinsTheLogbookLogger() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/logback-spring.xml")) {
            assertThat(in)
                    .as("logback-spring.xml must be on the runtime classpath")
                    .isNotNull();
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            NodeList loggers = factory.newDocumentBuilder().parse(in).getElementsByTagName("logger");

            String logbookClassLevel = null;
            boolean packageLevelLogger = false;
            for (int i = 0; i < loggers.getLength(); i++) {
                var element = (Element) loggers.item(i);
                if ("org.zalando.logbook.Logbook".equals(element.getAttribute("name"))) {
                    logbookClassLevel = element.getAttribute("level");
                }
                if ("org.zalando.logbook".equals(element.getAttribute("name"))) {
                    packageLevelLogger = true;
                }
            }

            assertThat(logbookClassLevel)
                    .as("Logbook's DefaultHttpLogWriter writes through this exact logger, so any other "
                            + "name or level leaves Logbook silent")
                    .isEqualTo("TRACE");
            assertThat(packageLevelLogger)
                    .as("a logger for the whole org.zalando.logbook package would also enable "
                            + "Spring's condition-evaluation lines for the Logbook auto-configuration classes")
                    .isFalse();
        }
    }
}
