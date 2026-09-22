package com.lifecontrol.gateway.routes;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guards the gateway's hand-maintained route allowlist against the API's real surface.
 *
 * <p>{@link Routes} declares every proxied prefix one by one, while {@code life-control-api} exposes
 * its own {@code @RequestMapping} prefixes independently. Nothing in either build connects the two,
 * and the backend tests bypass the gateway (they call the controllers directly through MockMvc), so
 * an API prefix with no gateway route is invisible to every existing test and only shows up as a 404
 * in a running topology. That is exactly how {@code /api/variants} shipped broken.
 *
 * <p>This test closes that gap deterministically and offline: it reads both source trees, compares
 * the discovered {@code /api/<segment>} prefixes, and fails listing the exact routes to add. It does
 * not start a Spring context, does not touch the network, and does not depend on timing.
 *
 * <p>Scope decisions behind this design (see {@code odd/tasks/gateway-route-coverage.md}): the
 * explicit route list is kept rather than collapsed to a single {@code /api/**} route, because the
 * per-area circuit breakers provide real isolation that a shared breaker would remove (D1); and a
 * live proxy probe was rejected in favour of this source-consistency check (D2).
 *
 * <p>Known limitation: this asserts a convention. Routes must stay declared as literal
 * {@code RequestPredicates.path("/api/<segment>/**")} predicates in {@link Routes}. If that ever
 * changes shape, the positive controls below fail loudly rather than passing vacuously.
 */
class GatewayRouteCoverageTests {

  /** A class-level {@code @RequestMapping("/api/...")} on an API controller. */
  private static final Pattern API_MAPPING = Pattern.compile("@RequestMapping\\(\"(/api/[^\"]*)\"\\)");

  /** A {@code RequestPredicates.path("/api/...")} predicate inside {@link Routes}. */
  private static final Pattern GATEWAY_ROUTE = Pattern.compile("RequestPredicates\\.path\\(\"(/api/[^\"]*)\"\\)");

  // Positive controls: if the regexes or the source layout drift, these fail instead of vacuously passing.
  private static final int MIN_EXPECTED_API_PREFIXES = 15;
  private static final int MIN_EXPECTED_GATEWAY_PREFIXES = 15;

  @Test
  void everyApiPrefixIsReachableThroughTheGateway() throws IOException {
    Path repository = repositoryRoot();
    Set<String> apiPrefixes = prefixesIn(apiControllerSources(repository), API_MAPPING);
    Set<String> proxiedPrefixes = prefixesIn(Set.of(routesSource(repository)), GATEWAY_ROUTE);

    assertTrue(
        apiPrefixes.size() >= MIN_EXPECTED_API_PREFIXES,
        "Expected at least "
            + MIN_EXPECTED_API_PREFIXES
            + " API /api prefixes but discovered "
            + apiPrefixes.size()
            + ": "
            + apiPrefixes
            + ". Are the controllers still under life-control-api/src/main/java?");

    assertTrue(
        proxiedPrefixes.size() >= MIN_EXPECTED_GATEWAY_PREFIXES,
        "Expected at least "
            + MIN_EXPECTED_GATEWAY_PREFIXES
            + " gateway /api routes but discovered "
            + proxiedPrefixes.size()
            + ": "
            + proxiedPrefixes
            + ". Did Routes.java stop declaring literal RequestPredicates.path(\"/api/<segment>/**\")?");

    Set<String> unreachable = new TreeSet<>(apiPrefixes);
    unreachable.removeAll(proxiedPrefixes);

    assertTrue(
        unreachable.isEmpty(),
        "These /api prefixes are exposed by life-control-api but not proxied by api-gateway, so every"
            + " request to them returns a gateway 404: "
            + unreachable
            + System.lineSeparator()
            + "Add one route per prefix to"
            + " api-gateway/src/main/java/com/lifecontrol/gateway/routes/Routes.java:"
            + System.lineSeparator()
            + unreachable.stream()
                .map(
                    prefix ->
                        "  .route(RequestPredicates.path(\"/api/"
                            + prefix
                            + "/**\"), HandlerFunctions.http(props.lifeControlApiUri()))")
                .collect(Collectors.joining(System.lineSeparator())));
  }

  private static Set<String> prefixesIn(Set<Path> sources, Pattern pattern) throws IOException {
    Set<String> prefixes = new TreeSet<>();
    for (Path source : sources) {
      Matcher matcher = pattern.matcher(Files.readString(source));
      while (matcher.find()) {
        prefixes.add(firstSegment(matcher.group(1)));
      }
    }
    return prefixes;
  }

  /** {@code /api/users-admin/roles} -> {@code users-admin}; {@code /api/countries} -> {@code countries}. */
  private static String firstSegment(String apiPath) {
    String remainder = apiPath.substring("/api/".length());
    int slash = remainder.indexOf('/');
    return slash < 0 ? remainder : remainder.substring(0, slash);
  }

  private static Set<Path> apiControllerSources(Path repository) throws IOException {
    Path controllers = repository.resolve("life-control-api/src/main/java");
    assertTrue(
        Files.isDirectory(controllers), "API controller source directory not found at " + controllers);
    try (Stream<Path> files = Files.walk(controllers)) {
      return files
          .filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith("Controller.java"))
          .collect(Collectors.toCollection(TreeSet::new));
    }
  }

  private static Path routesSource(Path repository) {
    Path routes =
        repository.resolve("api-gateway/src/main/java/com/lifecontrol/gateway/routes/Routes.java");
    assertTrue(Files.isRegularFile(routes), "Routes.java not found at " + routes);
    return routes;
  }

  /**
   * Walks up from the working directory to the checkout root, identified by holding both modules.
   *
   * <p>Works whether Gradle runs this module's tests from {@code api-gateway/} or from the root.
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
