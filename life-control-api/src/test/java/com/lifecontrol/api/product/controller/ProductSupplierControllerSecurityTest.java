package com.lifecontrol.api.product.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.config.ratelimit.RateLimitProperties;
import com.lifecontrol.api.product.service.ProductService;
import com.lifecontrol.api.product.supplier.dto.ProductSupplierRequest;
import com.lifecontrol.api.product.supplier.dto.ProductSupplierResponse;
import com.lifecontrol.api.product.supplier.service.ProductSupplierService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductController.class)
@DisplayName("ProductSupplier Security — @PreAuthorize method-level authorization on nested /suppliers endpoints")
class ProductSupplierControllerSecurityTest {

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
            return http
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
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
    private RateLimitProperties rateLimitProperties;

    private final UUID productId = UUID.randomUUID();
    private final UUID relationId = UUID.randomUUID();
    private final UUID supplierId = UUID.randomUUID();

    private ProductSupplierRequest buildRequest() {
        return new ProductSupplierRequest(
                supplierId,
                new BigDecimal("150.00"),
                true,
                true
        );
    }

    private ProductSupplierResponse buildResponse() {
        return new ProductSupplierResponse(
                relationId, productId, supplierId, "Test Supplier Co",
                new BigDecimal("150.00"), true, true,
                LocalDateTime.now(), LocalDateTime.now()
        );
    }

    // ─── GET /api/products/{productId}/suppliers ───────────────

    @Nested
    @DisplayName("GET /api/products/{productId}/suppliers")
    class GetProductSuppliersSecurity {

        @Test
        @WithMockUser(roles = {"life-control-admin"})
        @DisplayName("returns 200 OK for user with life-control-admin role")
        void adminCanGetSuppliers() throws Exception {
            when(productSupplierService.listSuppliersByProductId(productId))
                    .thenReturn(List.of(buildResponse()));

            mockMvc.perform(get("/api/products/{productId}/suppliers", productId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"life-control-country"})
        @DisplayName("returns 200 OK for user with life-control-country role")
        void countryRoleCanGetSuppliers() throws Exception {
            when(productSupplierService.listSuppliersByProductId(productId))
                    .thenReturn(List.of(buildResponse()));

            mockMvc.perform(get("/api/products/{productId}/suppliers", productId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(get("/api/products/{productId}/suppliers", productId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 Forbidden for authenticated user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(get("/api/products/{productId}/suppliers", productId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(get("/api/products/{productId}/suppliers", productId))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── POST /api/products/{productId}/suppliers ──────────────

    @Nested
    @DisplayName("POST /api/products/{productId}/suppliers")
    class PostProductSupplierSecurity {

        @Test
        @WithMockUser(roles = {"life-control-admin"})
        @DisplayName("returns 201 Created for user with life-control-admin role")
        void adminCanCreateRelation() throws Exception {
            when(productSupplierService.addSupplierToProduct(eq(productId), any(ProductSupplierRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(post("/api/products/{productId}/suppliers", productId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = {"life-control-country"})
        @DisplayName("returns 201 Created for user with life-control-country role")
        void countryRoleCanCreateRelation() throws Exception {
            when(productSupplierService.addSupplierToProduct(eq(productId), any(ProductSupplierRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(post("/api/products/{productId}/suppliers", productId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(post("/api/products/{productId}/suppliers", productId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 Forbidden for authenticated user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(post("/api/products/{productId}/suppliers", productId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(post("/api/products/{productId}/suppliers", productId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── PUT /api/products/{productId}/suppliers/{id} ──────────

    @Nested
    @DisplayName("PUT /api/products/{productId}/suppliers/{id}")
    class PutProductSupplierSecurity {

        @Test
        @WithMockUser(roles = {"life-control-admin"})
        @DisplayName("returns 200 OK for user with life-control-admin role")
        void adminCanUpdateRelation() throws Exception {
            when(productSupplierService.updateSupplier(eq(productId), eq(relationId), any(ProductSupplierRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(put("/api/products/{productId}/suppliers/{id}", productId, relationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"life-control-country"})
        @DisplayName("returns 200 OK for user with life-control-country role")
        void countryRoleCanUpdateRelation() throws Exception {
            when(productSupplierService.updateSupplier(eq(productId), eq(relationId), any(ProductSupplierRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(put("/api/products/{productId}/suppliers/{id}", productId, relationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(put("/api/products/{productId}/suppliers/{id}", productId, relationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 Forbidden for authenticated user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(put("/api/products/{productId}/suppliers/{id}", productId, relationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(put("/api/products/{productId}/suppliers/{id}", productId, relationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── DELETE /api/products/{productId}/suppliers/{id} ───────

    @Nested
    @DisplayName("DELETE /api/products/{productId}/suppliers/{id}")
    class DeleteProductSupplierSecurity {

        @Test
        @WithMockUser(roles = {"life-control-admin"})
        @DisplayName("returns 204 No Content for user with life-control-admin role")
        void adminCanDeleteRelation() throws Exception {
            mockMvc.perform(delete("/api/products/{productId}/suppliers/{id}", productId, relationId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(roles = {"life-control-country"})
        @DisplayName("returns 204 No Content for user with life-control-country role")
        void countryRoleCanDeleteRelation() throws Exception {
            mockMvc.perform(delete("/api/products/{productId}/suppliers/{id}", productId, relationId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(delete("/api/products/{productId}/suppliers/{id}", productId, relationId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 Forbidden for authenticated user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(delete("/api/products/{productId}/suppliers/{id}", productId, relationId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(delete("/api/products/{productId}/suppliers/{id}", productId, relationId))
                    .andExpect(status().isForbidden());
        }
    }
}
