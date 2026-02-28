package com.lifecontrol.gateway.routes;

import org.springframework.cloud.gateway.server.mvc.filter.CircuitBreakerFilterFunctions;
import org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions;
import org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions;
import org.springframework.cloud.gateway.server.mvc.filter.FilterFunctions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.function.*;

import java.net.URI;

import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;

@Configuration
public class Routes {

  @Bean
  public RouterFunction<ServerResponse> productServiceRoute() {
    return GatewayRouterFunctions.route("product_service")
        .route(RequestPredicates.path("/api/product/**"), HandlerFunctions.http("http://localhost:8080"))
        .filter(CircuitBreakerFilterFunctions.circuitBreaker("productServiceCircuitBreaker",
            URI.create("forward:/fallbackRoute")))
        .build();
  }

  @Bean
  public RouterFunction<ServerResponse> productServiceSwaggerRoute() {
    return GatewayRouterFunctions.route("product_service_swagger")
        .route(RequestPredicates.GET("/aggregate/product-service/v3/api-docs"),
            HandlerFunctions.http("http://localhost:8080"))
        .filter(CircuitBreakerFilterFunctions.circuitBreaker("productServiceSwaggerCircuitBreaker",
            URI.create("forward:/fallbackRoute")))
        .filter(FilterFunctions.setPath("/api-docs"))
        .build();
  }

  @Bean
  public RouterFunction<ServerResponse> orderServiceRoute() {
    return GatewayRouterFunctions.route("order_service")
        .route(RequestPredicates.GET("/api/order"), HandlerFunctions.http("http://localhost:8080"))
        .filter(CircuitBreakerFilterFunctions.circuitBreaker("orderServiceCircuitBreaker",
            URI.create("forward:/fallbackRoute")))
        .build();
  }

  @Bean
  public RouterFunction<ServerResponse> orderServiceSwaggerRoute() {
    return GatewayRouterFunctions.route("order_service_swagger")
        .route(RequestPredicates.GET("/aggregate/order-service/v3/api-docs"),
            HandlerFunctions.http("http://localhost:8081"))
        .filter(CircuitBreakerFilterFunctions.circuitBreaker("orderServiceSwaggerCircuitBreaker",
            URI.create("forward:/fallbackRoute")))
        .filter(FilterFunctions.setPath("/api-docs"))
        .build();
  }

  @Bean
  public RouterFunction<ServerResponse> inventoryServiceRoute() {
    return GatewayRouterFunctions.route("inventory_service")
        .filter(CircuitBreakerFilterFunctions.circuitBreaker("inventoryServiceCircuitBreaker",
            URI.create("forward:/fallbackRoute")))
        .route(RequestPredicates.GET("/api/inventory"), HandlerFunctions.http("http://localhost:8080"))
        .build();
  }

  @Bean
  public RouterFunction<ServerResponse> inventoryServiceSwaggerRoute() {
    return GatewayRouterFunctions.route("inventory_service_swagger")
        .route(RequestPredicates.GET("/aggregate/inventory-service/v3/api-docs"),
            HandlerFunctions.http("http://localhost:8082"))
        .filter(CircuitBreakerFilterFunctions.circuitBreaker("inventoryServiceSwaggerCircuitBreaker",
            URI.create("forward:/fallbackRoute")))
        .filter(FilterFunctions.setPath("/api-docs"))
        .build();
  }

  @Bean
  public RouterFunction<ServerResponse> lifeControlApiRoute() {
    return GatewayRouterFunctions.route("lifecontrol_api")
        .route(RequestPredicates.path("/api/user/**"), HandlerFunctions.http("http://lifecontrol-api:8082"))
        .filter(CircuitBreakerFilterFunctions.circuitBreaker("lifeControlApiCircuitBreaker",
            URI.create("forward:/fallbackRoute")))
        .build();
  }

  @Bean
  public RouterFunction<ServerResponse> lifeControlApiSwaggerRoute() {
    return GatewayRouterFunctions.route("lifecontrol_api_swagger")
        .route(RequestPredicates.GET("/aggregate/lifecontrol-api/v3/api-docs"),
            HandlerFunctions.http("http://lifecontrol-api:8082"))
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
