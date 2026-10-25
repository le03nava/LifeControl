package com.lifecontrol.api.config.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Pins that the shipped configuration lets the deployment decide how much is sampled and how loud
 * the log is, instead of a value baked into the jar.
 *
 * <p>Why this needs its own test: two settings were measured to be inert on the running dev/prod
 * stack, and nothing in the suite noticed because both are invisible from inside the application.
 * {@code application-prod.properties} declared {@code management.tracing.sampling.probability=0.1}
 * as a literal, and a profile-specific file <b>wins</b> over {@code application.properties}, so
 * {@code TRACING_SAMPLING_PROBABILITY=0.01} in {@code docker/.env.prod} was silently outvoted:
 * production sampled ten times what the operator asked for. Separately, compose passes
 * {@code LOG_LEVEL} to this service, nothing read it, so raising the level needed a rebuild.
 *
 * <p>The tests read {@code application.properties}, {@code application-*.properties} and
 * {@code logback-spring.xml} — the artifacts Gradle puts on the classpath — rather than passing
 * values in, because the thing under test is <b>what this application ships</b>. The placeholder
 * chains are resolved with the same syntax the application uses, so a property that names the
 * environment variable but resolves to something else still fails.
 */
@DisplayName("The API honours the environment for sampling and log level")
class ObservabilityEnvironmentOverrideTest {

    /** The profile files that may override {@code application.properties}; the order is not relevant. */
    private static final List<String> PROFILE_FILES =
            List.of("/application-dev.properties", "/application-staging.properties", "/application-prod.properties");

    @Test
    @DisplayName("production sampling is the TRACING_SAMPLING_PROBABILITY the deployment sets")
    void productionSampling_ReadsTheEnvironmentVariable() throws IOException {
        var declared =
                properties("/application-prod.properties").getProperty("management.tracing.sampling.probability");

        assertThat(declared)
                .as("a profile file wins over application.properties, so a literal here silently replaces "
                        + "${TRACING_SAMPLING_PROBABILITY:0.1} and the operator's value never applies")
                .isNotNull()
                .contains("TRACING_SAMPLING_PROBABILITY");

        assertThat(resolved(declared, Map.of()))
                .as("the file's documented default must survive the change: 10% when the deployment says nothing")
                .isEqualTo("0.1");

        assertThat(resolved(declared, Map.of("TRACING_SAMPLING_PROBABILITY", "0.01")))
                .as("docker/.env.prod sets TRACING_SAMPLING_PROBABILITY=0.01; that is the rate the API must sample at")
                .isEqualTo("0.01");
    }

    @Test
    @DisplayName("logging.level.root is the LOG_LEVEL the deployment sets, and no profile file pins it")
    void rootLogLevel_ReadsTheLogLevelEnvironmentVariable() throws IOException {
        var declared = properties("/application.properties").getProperty("logging.level.root");

        assertThat(declared)
                .as("compose passes LOG_LEVEL to this service; without this line nothing reads it and the root "
                        + "logger keeps whatever logback-spring.xml declares")
                .isEqualTo("${LOG_LEVEL:INFO}");

        assertThat(resolved(declared, Map.of()))
                .as("the shipped default must stay INFO: unset LOG_LEVEL must not silence the service")
                .isEqualTo("INFO");

        assertThat(resolved(declared, Map.of("LOG_LEVEL", "DEBUG")))
                .as("LOG_LEVEL=DEBUG on the container must raise the root logger without a rebuild")
                .isEqualTo("DEBUG");

        for (String file : PROFILE_FILES) {
            assertThat(properties(file).getProperty("logging.level.root"))
                    .as(
                            "%s must not pin the root level: a profile file wins over application.properties, which "
                                    + "is exactly how TRACING_SAMPLING_PROBABILITY was ignored in production",
                            file)
                    .isNull();
        }
    }

    @Test
    @DisplayName("logback-spring.xml takes its root level from logging.level.root, not from a literal")
    void logbackRootLevel_UsesTheSameProperty() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/logback-spring.xml")) {
            assertThat(in)
                    .as("logback-spring.xml must be on the runtime classpath")
                    .isNotNull();

            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var document = factory.newDocumentBuilder().parse(in);

            String declaredRootLevelProperty = null;
            NodeList springProperties = document.getElementsByTagName("springProperty");
            for (int i = 0; i < springProperties.getLength(); i++) {
                var element = (Element) springProperties.item(i);
                if ("logging.level.root".equals(element.getAttribute("source"))) {
                    declaredRootLevelProperty = element.getAttribute("name");
                }
            }

            assertThat(declaredRootLevelProperty)
                    .as("the file must expose logging.level.root as a logback property, otherwise Spring Boot's "
                            + "level handling and this file can disagree about the root level")
                    .isNotNull();

            var root = (Element) document.getElementsByTagName("root").item(0);

            assertThat(root.getAttribute("level"))
                    .as("a literal level here wins or loses against logging.level.root depending on startup "
                            + "order; reading the same property makes the outcome the same either way")
                    .isEqualTo("${" + declaredRootLevelProperty + "}");
        }
    }

    /**
     * Resolves a declared value the way the application does: against the environment, with the
     * {@code ${NAME:default}} form honoured, and with the deployment's variables as the source.
     */
    private static String resolved(String declared, Map<String, Object> deployment) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("deployment", deployment));
        return environment.resolveRequiredPlaceholders(declared);
    }

    /** Reads a resource exactly as the application ships it: from the classpath, never from the worktree. */
    private static Properties properties(String resource) throws IOException {
        var properties = new Properties();
        try (InputStream in = ObservabilityEnvironmentOverrideTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(
                        resource + " is not on the classpath; this test reads the shipped file");
            }
            properties.load(in);
        }
        return properties;
    }
}
