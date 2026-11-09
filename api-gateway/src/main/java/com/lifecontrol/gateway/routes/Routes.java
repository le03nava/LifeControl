package com.lifecontrol.gateway.routes;

import org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions;
import org.springframework.cloud.gateway.server.mvc.filter.CircuitBreakerFilterFunctions;
import org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions;
import org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions;
import org.springframework.cloud.gateway.server.mvc.filter.FilterFunctions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.function.*;

import com.lifecontrol.gateway.config.GatewayProperties;

import java.net.URI;

import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;

@Configuration
public class Routes {

  private final GatewayProperties props;

  public Routes(GatewayProperties props) {
    this.props = props;
  }

  @Bean
  public RouterFunction<ServerResponse> companyServiceRoute() {
    return GatewayRouterFunctions.route("company_service")
        .route(RequestPredicates.path("/api/companies/**"), HandlerFunctions.http())
        .before(BeforeFilterFunctions.uri(props.lifeControlApiUri()))
        .filter(CircuitBreakerFilterFunctions.circuitBreaker("companyServiceCircuitBreaker",
            URI.create("forward:/fallbackRoute")))
        .build();
  }

  @Bean
  public RouterFunction<ServerResponse> companyServiceSwaggerRoute() {
    return GatewayRouterFunctions.route("company_service_swagger")
        .route(RequestPredicates.GET("/aggregate/company-service/v3/api-docs"),
            HandlerFunctions.http())
        .before(BeforeFilterFunctions.uri(props.lifeControlApiUri()))
        .filter(CircuitBreakerFilterFunctions.circuitBreaker("companyServiceSwaggerCircuitBreaker",
            URI.create("forward:/fallbackRoute")))
        .filter(FilterFunctions.setPath("/api-docs"))
        .build();
  }

  @Bean
  public RouterFunction<ServerResponse> lifeControlApiRoute() {
    return GatewayRouterFunctions.route("lifecontrol_api")
        .route(RequestPredicates.path("/api/activity-logs/**"), HandlerFunctions.http())
        .route(RequestPredicates.path("/api/user/**"), HandlerFunctions.http())
        .route(RequestPredicates.path("/api/countries/**"), HandlerFunctions.http())
        .route(RequestPredicates.path("/api/currencies/**"), HandlerFunctions.http())
        .route(RequestPredicates.path("/api/tax-codes/**"), HandlerFunctions.http())
        .route(RequestPredicates.path("/api/users-admin/**"), HandlerFunctions.http())
        .route(RequestPredicates.path("/api/suppliers/**"), HandlerFunctions.http())
        .route(RequestPredicates.path("/api/purchase-orders/**"), HandlerFunctions.http())
        .route(RequestPredicates.path("/api/goods-receipts/**"), HandlerFunctions.http())
        .route(RequestPredicates.path("/api/payment-methods/**"), HandlerFunctions.http())
        .route(RequestPredicates.path("/api/status-types/**"), HandlerFunctions.http())
        .route(RequestPredicates.path("/api/statuses/**"), HandlerFunctions.http())
        .route(RequestPredicates.path("/api/measure-units/**"), HandlerFunctions.http())
        .route(RequestPredicates.path("/api/customers/**"), HandlerFunctions.http())
        .route(RequestPredicates.path("/api/promotions/**"), HandlerFunctions.http())
        .route(RequestPredicates.path("/api/shifts/**"), HandlerFunctions.http())
        .route(RequestPredicates.path("/api/sales-orders/**"), HandlerFunctions.http())
        .route(RequestPredicates.path("/api/product-variants/**"), HandlerFunctions.http())
        .route(RequestPredicates.path("/api/variants/**"), HandlerFunctions.http())
        .route(RequestPredicates.path("/api/profile/**"), HandlerFunctions.http())
        .route(RequestPredicates.path("/api/store-areas/**"), HandlerFunctions.http())
        .route(RequestPredicates.path("/api/store-zones/**"), HandlerFunctions.http())
        .route(RequestPredicates.path("/api/store-locations/**"), HandlerFunctions.http())
        .route(RequestPredicates.path("/api/stores/**"), HandlerFunctions.http())
        .route(RequestPredicates.path("/api/scheduling/**"), HandlerFunctions.http())
        .route(RequestPredicates.path("/api/seniority-levels/**"), HandlerFunctions.http())
        .before(BeforeFilterFunctions.uri(props.lifeControlApiUri()))
        .filter(CircuitBreakerFilterFunctions.circuitBreaker("lifeControlApiCircuitBreaker",
            URI.create("forward:/fallbackRoute")))
        .build();
  }

  @Bean
  public RouterFunction<ServerResponse> lifeControlApiSwaggerRoute() {
    return GatewayRouterFunctions.route("lifecontrol_api_swagger")
        .route(RequestPredicates.GET("/aggregate/lifecontrol-api/v3/api-docs"),
            HandlerFunctions.http())
        .before(BeforeFilterFunctions.uri(props.lifeControlApiUri()))
        .filter(CircuitBreakerFilterFunctions.circuitBreaker("lifeControlApiSwaggerCircuitBreaker",
            URI.create("forward:/fallbackRoute")))
        .filter(FilterFunctions.setPath("/api-docs"))
        .build();
  }

  /**
   * The answer a tripped circuit breaker gives.
   *
   * <p>Matched by <b>path only</b>, never by method: the four routes above forward to
   * {@code forward:/fallbackRoute}, and a forward keeps the original method. Declared as {@code .GET} it
   * matched only a tripped read, so every tripped write (POST/PUT/PATCH/DELETE) missed the fallback,
   * fell through to the static resource handler and answered 404 — measured on 2026-11-04, where a
   * {@code POST /api/companies} the API committed with 201 was reported to the caller as 404. The
   * status a write receives is the one place a wrong status is most expensive, so the predicate is
   * method-agnostic by design.
   */
  @Bean
  public RouterFunction<ServerResponse> fallbackRoute() {
    return route("fallbackRoute")
        .route(RequestPredicates.path("/fallbackRoute"), request ->
            ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body("Service Unavailable, please try again later"))
        .build();
  }
}
