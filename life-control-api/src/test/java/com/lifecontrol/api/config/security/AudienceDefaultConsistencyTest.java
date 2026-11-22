package com.lifecontrol.api.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the audience literal in every place it is declared, against the one constant the code owns.
 *
 * <p>{@code KeycloakJwtValidator} rejects a token whose {@code aud} names none of the configured
 * audiences, and the realm only puts that claim in a token if {@code keycloak-setup.sh} provisions the
 * mapper. That makes the literal a contract between three files that no compiler checks: the shell
 * script that emits it, the API that requires it, and the gateway that requires it again. A drift in
 * any one of them is invisible to every other test — the property still binds, the script still runs,
 * and the only symptom is {@code 401 invalid_token} on a fully healthy stack.
 *
 * <p>This is the same shape as {@code KeycloakClaimMapperCoverageTest}, and for the same reason: the
 * provisioning lives in a shell script this build never executes.
 */
@DisplayName("Audience default consistency")
class AudienceDefaultConsistencyTest {

    private static final String SETUP_SCRIPT = "docker/scripts/keycloak-setup.sh";
    private static final String API_PROPERTIES = "life-control-api/src/main/resources/application.properties";
    private static final String GATEWAY_PROPERTIES = "api-gateway/src/main/resources/application.properties";

    /** The constant the two decoders default to; the pin below compares every copy against it. */
    private static final String EXPECTED = KeycloakJwtProperties.DEFAULT_AUDIENCE;

    /** The `${ENV_VAR:default}` shape both property files use. */
    private static final Pattern PLACEHOLDER_DEFAULT =
            Pattern.compile("^[a-z.-]*allowed-audiences=\\$\\{[A-Z_]+:([^}]*)}", Pattern.MULTILINE);

    /** The shape the setup script uses to read an overridable key out of the env file. */
    private static final Pattern SCRIPT_GET_ENV =
            Pattern.compile("^API_AUDIENCE=\"\\$\\(get_env KEYCLOAK_API_AUDIENCE ([^)\\s]+)\\)\"", Pattern.MULTILINE);

    @Test
    @DisplayName("the realm mints the audience the API requires by default")
    void theSetupScriptMintsTheDefaultAudience() throws IOException {
        assertThat(defaultDeclaredIn(repositoryRoot().resolve(SETUP_SCRIPT), SCRIPT_GET_ENV, SETUP_SCRIPT))
                .as("keycloak-setup.sh provisions the mapper with a custom audience that must be the one the"
                        + " decoders require; without this mapper Keycloak emits aud:[\"account\"] and every"
                        + " token is rejected")
                .isEqualTo(EXPECTED);
    }

    @Test
    @DisplayName("the API property defaults to the declared audience")
    void theApiPropertyDefaultsToTheDeclaredAudience() throws IOException {
        assertThat(defaultDeclaredIn(repositoryRoot().resolve(API_PROPERTIES), PLACEHOLDER_DEFAULT, API_PROPERTIES))
                .isEqualTo(EXPECTED);
    }

    @Test
    @DisplayName("the gateway property defaults to the same audience")
    void theGatewayPropertyDefaultsToTheSameAudience() throws IOException {
        assertThat(defaultDeclaredIn(
                        repositoryRoot().resolve(GATEWAY_PROPERTIES), PLACEHOLDER_DEFAULT, GATEWAY_PROPERTIES))
                .as("the gateway validates the same token the API validates, so a different literal here"
                        + " means it either rejects every token or accepts one the API refuses")
                .isEqualTo(EXPECTED);
    }

    @Test
    @DisplayName("no profile override narrows the audience")
    void noProfileOverrideNarrowsTheAudience() throws IOException {
        Path root = repositoryRoot();
        List<Path> overrides;
        try (Stream<Path> api = profileProperties(root.resolve("life-control-api/src/main/resources"));
                Stream<Path> gateway = profileProperties(root.resolve("api-gateway/src/main/resources"))) {
            overrides = Stream.concat(api, gateway).toList();
        }

        for (Path override : overrides) {
            String content = Files.readString(override);
            var matcher = PLACEHOLDER_DEFAULT.matcher(content);
            if (matcher.find()) {
                assertThat(matcher.group(1))
                        .as(
                                "%s declares its own allowed-audiences default; every profile validates the same"
                                        + " tokens, so a narrower value here would reject them for one environment only",
                                root.relativize(override))
                        .isEqualTo(EXPECTED);
            }
        }
    }

    private static Stream<Path> profileProperties(Path resources) throws IOException {
        if (!Files.isDirectory(resources)) {
            return Stream.empty();
        }
        try (Stream<Path> files = Files.list(resources)) {
            return files
                    .filter(path -> path.getFileName().toString().matches("application-.*\\.properties"))
                    .toList()
                    .stream();
        }
    }

    private static String defaultDeclaredIn(Path file, Pattern pattern, String display) throws IOException {
        assertThat(Files.isRegularFile(file))
                .as("%s not found at %s", display, file)
                .isTrue();
        var matcher = pattern.matcher(Files.readString(file));
        if (!matcher.find()) {
            fail("No allowed-audiences declaration found in " + display + "; the pin cannot verify a default"
                    + " that is no longer declared in the shape it expects.");
        }
        return matcher.group(1);
    }

    /**
     * Walks up from the working directory to the checkout root, identified by holding both the setup
     * script and the two module directories. Works whether Gradle runs this module's tests from
     * {@code life-control-api/} or the root.
     */
    private static Path repositoryRoot() {
        Path start = Path.of("").toAbsolutePath().normalize();
        for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve(SETUP_SCRIPT))
                    && Files.isDirectory(candidate.resolve("life-control-api"))
                    && Files.isDirectory(candidate.resolve("api-gateway"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not locate the checkout root from " + start);
    }
}
