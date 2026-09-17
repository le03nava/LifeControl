package com.lifecontrol.gateway.routes;

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
  public RouterFunction<ServerResponse> productServiceRoute() {
    return GatewayRouterFunctions.route("product_service")
        .route(RequestPredicates.path("/api/products/**"), HandlerFunctions.http(props.lifeControlApiUri()))
        .filter(CircuitBreakerFilterFunctions.circuitBreaker("productServiceCircuitBreaker",
            URI.create("forward:/fallbackRoute")))
        .build();
  }

  @Bean
  public RouterFunction<ServerResponse> companyServiceRoute() {
    return GatewayRouterFunctions.route("company_service")
        .route(RequestPredicates.path("/api/companies/**"), HandlerFunctions.http(props.lifeControlApiUri()))
        .filter(CircuitBreakerFilterFunctions.circuitBreaker("companyServiceCircuitBreaker",
            URI.create("forward:/fallbackRoute")))
        .build();
  }

  @Bean
  public RouterFunction<ServerResponse> companyServiceSwaggerRoute() {
    return GatewayRouterFunctions.route("company_service_swagger")
        .route(RequestPredicates.GET("/aggregate/company-service/v3/api-docs"),
            HandlerFunctions.http(props.lifeControlApiUri()))
        .filter(CircuitBreakerFilterFunctions.circuitBreaker("companyServiceSwaggerCircuitBreaker",
            URI.create("forward:/fallbackRoute")))
        .filter(FilterFunctions.setPath("/api-docs"))
        .build();
  }

  @Bean
  public RouterFunction<ServerResponse> lifeControlApiRoute() {
    return GatewayRouterFunctions.route("lifecontrol_api")
        .route(RequestPredicates.path("/api/user/**"), HandlerFunctions.http(props.lifeControlApiUri()))
        .route(RequestPredicates.path("/api/countries/**"), HandlerFunctions.http(props.lifeControlApiUri()))
        .route(RequestPredicates.path("/api/users-admin/**"), HandlerFunctions.http(props.lifeControlApiUri()))
        .route(RequestPredicates.path("/api/suppliers/**"), HandlerFunctions.http(props.lifeControlApiUri()))
        .route(RequestPredicates.path("/api/purchase-orders/**"), HandlerFunctions.http(props.lifeControlApiUri()))
        .route(RequestPredicates.path("/api/payment-methods/**"), HandlerFunctions.http(props.lifeControlApiUri()))
        .route(RequestPredicates.path("/api/status-types/**"), HandlerFunctions.http(props.lifeControlApiUri()))
        .route(RequestPredicates.path("/api/statuses/**"), HandlerFunctions.http(props.lifeControlApiUri()))
        .route(RequestPredicates.path("/api/measure-units/**"), HandlerFunctions.http(props.lifeControlApiUri()))
        .route(RequestPredicates.path("/api/customers/**"), HandlerFunctions.http(props.lifeControlApiUri()))
        .route(RequestPredicates.path("/api/promotions/**"), HandlerFunctions.http(props.lifeControlApiUri()))
        .route(RequestPredicates.path("/api/shifts/**"), HandlerFunctions.http(props.lifeControlApiUri()))
        .route(RequestPredicates.path("/api/sales-orders/**"), HandlerFunctions.http(props.lifeControlApiUri()))
        .route(RequestPredicates.path("/api/product-variants/**"), HandlerFunctions.http(props.lifeControlApiUri()))
        .route(RequestPredicates.path("/api/profile/**"), HandlerFunctions.http(props.lifeControlApiUri()))
        .route(RequestPredicates.path("/api/store-areas/**"), HandlerFunctions.http(props.lifeControlApiUri()))
        .filter(CircuitBreakerFilterFunctions.circuitBreaker("lifeControlApiCircuitBreaker",
            URI.create("forward:/fallbackRoute")))
        .build();
  }

  @Bean
  public RouterFunction<ServerResponse> lifeControlApiSwaggerRoute() {
    return GatewayRouterFunctions.route("lifecontrol_api_swagger")
        .route(RequestPredicates.GET("/aggregate/lifecontrol-api/v3/api-docs"),
            HandlerFunctions.http(props.lifeControlApiUri()))
        .filter(CircuitBreakerFilterFunctions.circuitBreaker("lifeControlApiSwaggerCircuitBreaker",
            URI.create("forward:/fallbackRoute")))
        .filter(FilterFunctions.setPath("/api-docs"))
        .build();
  }

  @Bean
  public RouterFunction<ServerResponse> fallbackRoute() {
    return route("fallbackRoute")
        .GET("/fallbackRoute", request -> ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body("Service Unavailable, please try again later"))
        .build();
  }
}
