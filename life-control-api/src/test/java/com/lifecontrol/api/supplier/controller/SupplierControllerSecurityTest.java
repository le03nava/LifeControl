package com.lifecontrol.api.supplier.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.common.address.dto.AddressRequest;
import com.lifecontrol.api.common.address.dto.AddressResponse;
import com.lifecontrol.api.config.ratelimit.RateLimitProperties;
import com.lifecontrol.api.supplier.dto.SupplierRequest;
import com.lifecontrol.api.supplier.dto.SupplierResponse;
import com.lifecontrol.api.supplier.service.SupplierService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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

@WebMvcTest(SupplierController.class)
@DisplayName("SupplierController Security — @PreAuthorize method-level authorization")
class SupplierControllerSecurityTest {

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
    private SupplierService supplierService;

    @MockitoBean
    private RateLimitProperties rateLimitProperties;

    private SupplierRequest buildSupplierRequest() {
        return new SupplierRequest(
                "Test Supplier", "Razon Social", "XAXX010101000",
                "test@supplier.com", "+1234567890",
                "INT-001",
                new AddressRequest("Calle", "123", null, "Centro", "12345", "Ciudad", "Estado", null),
                true
        );
    }

    // ─── GET /api/suppliers ───────────────────────────────────

    @Nested
    @DisplayName("GET /api/suppliers")
    class GetSuppliers {

        @Test
        @WithMockUser(roles = {"life-control-admin"})
        @DisplayName("returns 200 OK for admin user")
        void adminCanGetSuppliers() throws Exception {
            var response = new SupplierResponse(
                    UUID.randomUUID(), "Test", "RS", "XAXX010101000",
                    "e@e.com", "555", "INT-001",
                    new AddressResponse(null, "Calle", "123", null, "Centro", "12345", "CdMx", "CDMX", null), true,
                    LocalDateTime.now(), LocalDateTime.now()
            );
            var page = new PageImpl<>(List.of(response), PageRequest.of(0, 12), 1);
            when(supplierService.getAllSuppliers(any(), eq(null))).thenReturn(page);

            mockMvc.perform(get("/api/suppliers"))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 200 OK for authenticated user with no admin role")
        void authenticatedUserCanGetSuppliers() throws Exception {
            var response = new SupplierResponse(
                    UUID.randomUUID(), "Test", "RS", "XAXX010101000",
                    "e@e.com", "555", "INT-001",
                    new AddressResponse(null, "Calle", "123", null, "Centro", "12345", "CdMx", "CDMX", null), true,
                    LocalDateTime.now(), LocalDateTime.now()
            );
            var page = new PageImpl<>(List.of(response), PageRequest.of(0, 12), 1);
            when(supplierService.getAllSuppliers(any(), eq(null))).thenReturn(page);

            mockMvc.perform(get("/api/suppliers"))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 200 OK for authenticated user with any role")
        void userWithAnyRoleCanGetSuppliers() throws Exception {
            var response = new SupplierResponse(
                    UUID.randomUUID(), "Test", "RS", "XAXX010101000",
                    "e@e.com", "555", "INT-001",
                    new AddressResponse(null, "Calle", "123", null, "Centro", "12345", "CdMx", "CDMX", null), true,
                    LocalDateTime.now(), LocalDateTime.now()
            );
            var page = new PageImpl<>(List.of(response), PageRequest.of(0, 12), 1);
            when(supplierService.getAllSuppliers(any(), eq(null))).thenReturn(page);

            mockMvc.perform(get("/api/suppliers"))
                    .andExpect(status().isOk());
        }
    }

    // ─── POST /api/suppliers ─────────────────────────────────

    @Nested
    @DisplayName("POST /api/suppliers")
    class PostSuppliers {

        @Test
        @WithMockUser(roles = {"life-control-admin"})
        @DisplayName("returns 201 Created for admin user")
        void adminCanCreateSupplier() throws Exception {
            var response = new SupplierResponse(
                    UUID.randomUUID(), "Test", "RS", "XAXX010101000",
                    "e@e.com", "555", "INT-001",
                    new AddressResponse(null, "Calle", "123", null, "Centro", "12345", "CdMx", "CDMX", null), true,
                    LocalDateTime.now(), LocalDateTime.now()
            );
            when(supplierService.createSupplier(any())).thenReturn(response);

            mockMvc.perform(post("/api/suppliers")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildSupplierRequest())))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 Forbidden for authenticated user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(post("/api/suppliers")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildSupplierRequest())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for user without admin role")
        void userWithoutAdminRoleGetsForbidden() throws Exception {
            mockMvc.perform(post("/api/suppliers")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildSupplierRequest())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"life-control-country"})
        @DisplayName("returns 403 Forbidden for country-role (admin-only write)")
        void countryRoleCannotCreateSupplier() throws Exception {
            mockMvc.perform(post("/api/suppliers")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildSupplierRequest())))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── PUT /api/suppliers/{id} ──────────────────────────────

    @Nested
    @DisplayName("PUT /api/suppliers/{id}")
    class PutSuppliers {

        @Test
        @WithMockUser(roles = {"life-control-admin"})
        @DisplayName("returns 200 OK for admin user")
        void adminCanUpdateSupplier() throws Exception {
            var response = new SupplierResponse(
                    UUID.randomUUID(), "Test", "RS", "XAXX010101000",
                    "e@e.com", "555", "INT-001",
                    new AddressResponse(null, "Calle", "123", null, "Centro", "12345", "CdMx", "CDMX", null), true,
                    LocalDateTime.now(), LocalDateTime.now()
            );
            when(supplierService.updateSupplier(any(), any())).thenReturn(response);

            mockMvc.perform(put("/api/suppliers/{id}", UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildSupplierRequest())))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 Forbidden for authenticated user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(put("/api/suppliers/{id}", UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildSupplierRequest())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for user without admin role")
        void userWithoutAdminRoleGetsForbidden() throws Exception {
            mockMvc.perform(put("/api/suppliers/{id}", UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildSupplierRequest())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"life-control-country"})
        @DisplayName("returns 403 Forbidden for country-role (admin-only write)")
        void countryRoleCannotUpdateSupplier() throws Exception {
            mockMvc.perform(put("/api/suppliers/{id}", UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildSupplierRequest())))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── DELETE /api/suppliers/{id} ───────────────────────────

    @Nested
    @DisplayName("DELETE /api/suppliers/{id}")
    class DeleteSuppliers {

        @Test
        @WithMockUser(roles = {"life-control-admin"})
        @DisplayName("returns 204 No Content for admin user")
        void adminCanDeleteSupplier() throws Exception {
            var id = UUID.randomUUID();
            mockMvc.perform(delete("/api/suppliers/{id}", id))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 Forbidden for authenticated user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(delete("/api/suppliers/{id}", UUID.randomUUID()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for user without admin role")
        void userWithoutAdminRoleGetsForbidden() throws Exception {
            mockMvc.perform(delete("/api/suppliers/{id}", UUID.randomUUID()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"life-control-country"})
        @DisplayName("returns 403 Forbidden for country-role (admin-only write)")
        void countryRoleCannotDeleteSupplier() throws Exception {
            mockMvc.perform(delete("/api/suppliers/{id}", UUID.randomUUID()))
                    .andExpect(status().isForbidden());
        }
    }
}
