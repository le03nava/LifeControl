package com.lifecontrol.gateway.routes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Pins the answer the circuit breaker's fallback gives, for <b>every</b> method the gateway forwards.
 *
 * <p>The routes in {@link Routes} forward to {@code forward:/fallbackRoute} when their circuit breaker
 * trips. The forward keeps the original method, so a fallback declared as {@code .GET} only matches a
 * tripped {@code GET}: every other method misses the fallback, falls through to the static resource
 * handler and answers {@code 404 NOT_FOUND} instead of the documented {@code 503}.
 *
 * <p>That is not cosmetic. It was measured on 2026-11-04 against dev: {@code POST /api/companies} was
 * cancelled by the {@code companyServiceCircuitBreaker} TimeLimiter at 2004 ms, the API committed and
 * answered {@code 201 CREATED} at 2963 ms, and the client received {@code 404} — the one status that
 * tells a caller its write does not exist. A misleading status is worst on writes, which is exactly
 * where this route was silent.
 *
 * <p>The test builds the {@link ServerRequest} the way the framework does (from the servlet request the
 * forward produces) and only reads the status the fallback declares: no Spring context, no network, no
 * timing.
 */
class FallbackRouteTests {

  /** The fallback handler reads no property, so the routes can be built without configuration. */
  private final Routes routes = new Routes(null);

  @ParameterizedTest
  @ValueSource(strings = {"GET", "POST", "PUT", "PATCH", "DELETE"})
  void theFallbackAnswersServiceUnavailableToEveryMethod(String method) throws Exception {
    ServerRequest request =
        ServerRequest.create(new MockHttpServletRequest(method, "/fallbackRoute"), List.of());

    Optional<HandlerFunction<ServerResponse>> handler = routes.fallbackRoute().route(request);

    assertTrue(
        handler.isPresent(),
        method
            + " /fallbackRoute is not matched by the fallback route, so a tripped "
            + method
            + " circuit breaker answers 404 from the static resource handler instead of 503");
    assertEquals(
        HttpStatus.SERVICE_UNAVAILABLE,
        handler.get().handle(request).statusCode(),
        method + " /fallbackRoute answered a status other than 503");
  }
}
