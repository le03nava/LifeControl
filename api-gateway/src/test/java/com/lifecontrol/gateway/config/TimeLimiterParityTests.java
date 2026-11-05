package com.lifecontrol.gateway.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.DurationStyle;

/**
 * Holds the time limiter dev actually runs with to the same headroom as the environment where the same
 * write must work, and keeps the two dev declarations from drifting apart.
 *
 * <p><b>Which file decides.</b> Not {@code application-dev.properties}. The compose file passes
 * {@code TIMEOUT_DURATION} as a <em>process environment variable</em> ({@code docker-compose.yml},
 * {@code TIMEOUT_DURATION=${TIMEOUT_DURATION:-3s}}), materialised from {@code docker/.env.<env>}, and a
 * Spring environment variable outranks a profile properties file. Measured on 2026-11-04: the container
 * reported {@code TIMEOUT_DURATION=2s} while {@code application-dev.properties} declared {@code 5s}, so a
 * first version of this test asserted on the properties file and went green while dev kept cancelling
 * writes at 2 s. That is the failure this test now cannot reproduce: it reads the tracked value that
 * reaches the process ({@code docker/.env.template}, which every materialised {@code .env.<env>} is created
 * from) and fails when the properties file disagrees with it.
 *
 * <p><b>Why it matters.</b> A company creation measured 2963 ms on a warm path (trace
 * {@code 5cbabcef9316bbc2e9154c23e5fb54dc}; the security filter chain accounted for 124 ms). With a 2 s
 * limiter the gateway cancelled the call at 2004 ms while the API committed and answered 201, so the
 * caller was told {@code 404} for a write that exists. Dev is where a developer decides whether a write
 * works, so it must not be the strictest environment.
 *
 * <p><b>What it does not close.</b> Any write slower than the limiter is still cancelled at the edge while
 * it commits behind it. This pins the configuration in which ordinary writes trip the breaker at all; the
 * remaining race is a separate design question.
 */
class TimeLimiterParityTests {

  /** The tracked value compose feeds the process from a materialised {@code .env.<env>}. */
  private static final Pattern ENV_TEMPLATE = Pattern.compile("^TIMEOUT_DURATION=(.+)$", Pattern.MULTILINE);

  /** The literal prod override, which ignores the env var and states its own value. */
  private static final Pattern PROD_COMPOSE =
      Pattern.compile("TIMEOUT_DURATION=([0-9]+[a-zA-Z]+)", Pattern.MULTILINE);

  /** The profile properties file, which applies only when the process runs without compose. */
  private static final Pattern DEV_PROPERTIES = Pattern.compile("^TIMEOUT_DURATION=(.+)$", Pattern.MULTILINE);

  private static final Duration MEASURED_COMPANY_CREATION = Duration.ofMillis(2963);

  @Test
  void devIsNotStricterThanProd() throws IOException {
    Path repository = repositoryRoot();
    Duration dev = parse(repository.resolve("docker/.env.template"), ENV_TEMPLATE, "docker/.env.template");
    Duration prod =
        parse(
            repository.resolve("docker/docker-compose.prod.yml"),
            PROD_COMPOSE,
            "docker/docker-compose.prod.yml");

    assertTrue(
        dev.compareTo(prod) >= 0,
        "docker/.env.template declares TIMEOUT_DURATION="
            + dev.toMillis()
            + "ms, shorter than prod's "
            + prod.toMillis()
            + "ms. A warm company creation measured "
            + MEASURED_COMPANY_CREATION.toMillis()
            + "ms, so every such write in dev is cancelled at the edge while the API still commits it:"
            + " raise dev's limiter, or lower prod's with the measurement that justifies it.");
  }

  @Test
  void theTwoDevDeclarationsAgree() throws IOException {
    Path repository = repositoryRoot();
    Duration fromEnvironment =
        parse(repository.resolve("docker/.env.template"), ENV_TEMPLATE, "docker/.env.template");
    Duration fromProfile =
        parse(
            repository.resolve("api-gateway/src/main/resources/application-dev.properties"),
            DEV_PROPERTIES,
            "application-dev.properties");

    assertEquals(
        fromEnvironment,
        fromProfile,
        "docker/.env.template declares TIMEOUT_DURATION="
            + fromEnvironment.toMillis()
            + "ms and application-dev.properties declares "
            + fromProfile.toMillis()
            + "ms. The environment variable wins in a container, so editing the properties file alone looks"
            + " like a fix and changes nothing: keep the two equal, or drop the redundant declaration.");
  }

  private static Duration parse(Path file, Pattern pattern, String label) throws IOException {
    String content = Files.readString(file);
    Matcher matcher = pattern.matcher(content);
    assertTrue(matcher.find(), "No TIMEOUT_DURATION declared in " + label + " (" + file + ")");
    Duration parsed = DurationStyle.detectAndParse(matcher.group(1).trim());
    assertNotNull(parsed, "TIMEOUT_DURATION in " + label + " did not parse: " + matcher.group(1));
    return parsed;
  }

  /**
   * Walks up from the working directory to the checkout root, identified by holding both modules, so
   * the test behaves the same whether Gradle runs it from {@code api-gateway/} or from the root.
   */
  private static Path repositoryRoot() {
    Path start = Path.of("").toAbsolutePath().normalize();
    for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
      if (Files.isDirectory(candidate.resolve("api-gateway/src/main/java"))
          && Files.isDirectory(candidate.resolve("life-control-api/src/main/java"))) {
        return candidate;
      }
    }
    throw new IllegalStateException(
        "Could not locate the repository root (a directory holding both api-gateway/ and"
            + " life-control-api/) by walking up from "
            + start);
  }
}
