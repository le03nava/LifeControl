package com.lifecontrol.api.product.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.config.ratelimit.RateLimitProperties;
import com.lifecontrol.api.product.dto.ProductRequest;
import com.lifecontrol.api.product.dto.ProductResponse;
import com.lifecontrol.api.product.service.ProductService;
import com.lifecontrol.api.product.service.ProductVariantService;
import com.lifecontrol.api.product.supplier.service.ProductSupplierService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProductController.class)
@DisplayName("Product ABM Security — @PreAuthorize method-level authorization on /api/products writes")
class ProductControllerSecurityTest {

    /**
     * Minimal security configuration that enables method-level security
     * without requiring JWT/OAuth2 infrastructure. @WithMockUser sets up
     * the SecurityContext directly, bypassing authentication filters.
     */
    @TestConfiguration
    @EnableWebSecurity
    @EnableMethodSecurity
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .httpBasic(basic -> {})
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .build();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ProductService productService;

    @MockitoBean
    private ProductSupplierService productSupplierService;

    @MockitoBean
    private ProductVariantService productVariantService;

    @MockitoBean
    private RateLimitProperties rateLimitProperties;

    private final UUID productId = UUID.randomUUID();

    private ProductRequest buildRequest() {
        return new ProductRequest("SKU-001", "Test Product", "Test", "SAT-001", "SERVICE", Map.of("color", "red"));
    }

    private ProductResponse buildResponse() {
        return new ProductResponse(
                productId,
                "SKU-001",
                "Test Product",
                "Test",
                "SAT-001",
                "SERVICE",
                Map.of("color", "red"),
                true,
                LocalDateTime.now(),
                LocalDateTime.now());
    }

    // ─── POST /api/products ────────────────────────────────────

    @Nested
    @DisplayName("POST /api/products")
    class CreateProductSecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 201 Created for user with lc-admin role")
        void adminCanCreate() throws Exception {
            when(productService.createProduct(any(ProductRequest.class))).thenReturn(buildResponse());

            mockMvc.perform(post("/api/products")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = {"lc-sales"})
        @DisplayName("returns 403 Forbidden for lc-sales and never reaches the service")
        void nonAdminGetsForbidden() throws Exception {
            mockMvc.perform(post("/api/products")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isForbidden());

            verify(productService, never()).createProduct(any(ProductRequest.class));
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 Forbidden for authenticated user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(post("/api/products")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isForbidden());

            verify(productService, never()).createProduct(any(ProductRequest.class));
        }

        @Test
        @DisplayName("returns 401 Unauthorized for unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(post("/api/products")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isUnauthorized());

            verify(productService, never()).createProduct(any(ProductRequest.class));
        }
    }

    // ─── PUT /api/products/{id} ────────────────────────────────

    @Nested
    @DisplayName("PUT /api/products/{id}")
    class UpdateProductSecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for user with lc-admin role")
        void adminCanUpdate() throws Exception {
            when(productService.updateProduct(eq(productId), any(ProductRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(put("/api/products/{id}", productId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-sales"})
        @DisplayName("returns 403 Forbidden for lc-sales and never reaches the service")
        void nonAdminGetsForbidden() throws Exception {
            mockMvc.perform(put("/api/products/{id}", productId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isForbidden());

            verify(productService, never()).updateProduct(eq(productId), any(ProductRequest.class));
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 Forbidden for authenticated user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(put("/api/products/{id}", productId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isForbidden());

            verify(productService, never()).updateProduct(eq(productId), any(ProductRequest.class));
        }

        @Test
        @DisplayName("returns 401 Unauthorized for unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(put("/api/products/{id}", productId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isUnauthorized());

            verify(productService, never()).updateProduct(eq(productId), any(ProductRequest.class));
        }
    }

    // ─── DELETE /api/products/{id} ─────────────────────────────

    @Nested
    @DisplayName("DELETE /api/products/{id}")
    class DeleteProductSecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 204 No Content for user with lc-admin role")
        void adminCanDelete() throws Exception {
            mockMvc.perform(delete("/api/products/{id}", productId)).andExpect(status().isNoContent());

            verify(productService).deleteProduct(productId);
        }

        @Test
        @WithMockUser(roles = {"lc-sales"})
        @DisplayName("returns 403 Forbidden for lc-sales and never reaches the service")
        void nonAdminGetsForbidden() throws Exception {
            mockMvc.perform(delete("/api/products/{id}", productId)).andExpect(status().isForbidden());

            verify(productService, never()).deleteProduct(productId);
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 Forbidden for authenticated user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(delete("/api/products/{id}", productId)).andExpect(status().isForbidden());

            verify(productService, never()).deleteProduct(productId);
        }

        @Test
        @DisplayName("returns 401 Unauthorized for unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(delete("/api/products/{id}", productId)).andExpect(status().isUnauthorized());

            verify(productService, never()).deleteProduct(productId);
        }
    }

    // ─── Read endpoints: deliberate scope boundary regression pin ──
    // The write hardening above must NOT tighten the reads: the variant
    // screens are reachable by lc-sales and consume GET /api/products/{id}.

    @Nested
    @DisplayName("GET /api/products — reads stay reachable by non-admin")
    class ListProductsReadSecurity {

        @Test
        @WithMockUser(roles = {"lc-sales"})
        @DisplayName("returns 200 OK for lc-sales (read authorization unchanged)")
        void nonAdminCanList() throws Exception {
            var pageable = PageRequest.of(0, 12);
            when(productService.listProducts(any(Pageable.class), eq(null), eq(false)))
                    .thenReturn(new PageImpl<>(List.of(buildResponse()), pageable, 1));

            mockMvc.perform(get("/api/products")).andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(get("/api/products")).andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("GET /api/products/{id} — reads stay reachable by non-admin")
    class GetProductByIdReadSecurity {

        @Test
        @WithMockUser(roles = {"lc-sales"})
        @DisplayName("returns 200 OK for lc-sales (read authorization unchanged)")
        void nonAdminCanGetById() throws Exception {
            when(productService.findProduct(productId)).thenReturn(buildResponse());

            mockMvc.perform(get("/api/products/{id}", productId)).andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(get("/api/products/{id}", productId)).andExpect(status().isUnauthorized());
        }
    }
}
