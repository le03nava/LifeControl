package com.lifecontrol.api.paymentmethod.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.config.ratelimit.RateLimitProperties;
import com.lifecontrol.api.paymentmethod.dto.PaymentMethodRequest;
import com.lifecontrol.api.paymentmethod.dto.PaymentMethodResponse;
import com.lifecontrol.api.paymentmethod.service.PaymentMethodService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
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

@WebMvcTest(PaymentMethodController.class)
@DisplayName("PaymentMethod Controller Security — @PreAuthorize method-level authorization")
class PaymentMethodControllerSecurityTest {

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
    private PaymentMethodService paymentMethodService;

    @MockitoBean
    private RateLimitProperties rateLimitProperties;

    private final UUID paymentMethodId = UUID.randomUUID();

    private PaymentMethodRequest buildRequest() {
        return new PaymentMethodRequest("Efectivo", "EFE", true);
    }

    private PaymentMethodResponse buildResponse() {
        return new PaymentMethodResponse(
                paymentMethodId, "Efectivo", "EFE", true, LocalDateTime.now(), LocalDateTime.now());
    }

    // ─── GET /api/payment-methods ─────────────────────────────────

    @Nested
    @DisplayName("GET /api/payment-methods")
    class GetPaymentMethodsSecurity {

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 200 OK for any authenticated user (reads are isAuthenticated)")
        void anyAuthenticatedUserCanRead() throws Exception {
            when(paymentMethodService.getAllPaymentMethods()).thenReturn(List.of(buildResponse()));

            mockMvc.perform(get("/api/payment-methods")).andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(get("/api/payment-methods")).andExpect(status().isUnauthorized());
        }
    }

    // ─── POST /api/payment-methods ────────────────────────────────

    @Nested
    @DisplayName("POST /api/payment-methods")
    class CreatePaymentMethodSecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 201 Created for user with lc-admin role")
        void adminCanCreate() throws Exception {
            when(paymentMethodService.createPaymentMethod(any(PaymentMethodRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(post("/api/payment-methods")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = {"lc-payment-method"})
        @DisplayName("returns 201 Created for user with lc-payment-method role")
        void domainRoleCanCreate() throws Exception {
            when(paymentMethodService.createPaymentMethod(any(PaymentMethodRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(post("/api/payment-methods")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(post("/api/payment-methods")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 Forbidden for authenticated user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(post("/api/payment-methods")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(post("/api/payment-methods")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── PUT /api/payment-methods/{id} ────────────────────────────

    @Nested
    @DisplayName("PUT /api/payment-methods/{id}")
    class UpdatePaymentMethodSecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for user with lc-admin role")
        void adminCanUpdate() throws Exception {
            when(paymentMethodService.updatePaymentMethod(eq(paymentMethodId), any(PaymentMethodRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(put("/api/payment-methods/{id}", paymentMethodId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-payment-method"})
        @DisplayName("returns 200 OK for user with lc-payment-method role")
        void domainRoleCanUpdate() throws Exception {
            when(paymentMethodService.updatePaymentMethod(eq(paymentMethodId), any(PaymentMethodRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(put("/api/payment-methods/{id}", paymentMethodId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(put("/api/payment-methods/{id}", paymentMethodId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(put("/api/payment-methods/{id}", paymentMethodId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── DELETE /api/payment-methods/{id} ─────────────────────────

    @Nested
    @DisplayName("DELETE /api/payment-methods/{id}")
    class DeletePaymentMethodSecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 204 No Content for user with lc-admin role")
        void adminCanDelete() throws Exception {
            mockMvc.perform(delete("/api/payment-methods/{id}", paymentMethodId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(roles = {"lc-payment-method"})
        @DisplayName("returns 204 No Content for user with lc-payment-method role")
        void domainRoleCanDelete() throws Exception {
            mockMvc.perform(delete("/api/payment-methods/{id}", paymentMethodId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 Forbidden for authenticated user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(delete("/api/payment-methods/{id}", paymentMethodId))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── PATCH /api/payment-methods/{id}/enable ───────────────────

    @Nested
    @DisplayName("PATCH /api/payment-methods/{id}/enable")
    class EnablePaymentMethodSecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for user with lc-admin role")
        void adminCanEnable() throws Exception {
            when(paymentMethodService.setPaymentMethodEnabled(paymentMethodId, true))
                    .thenReturn(buildResponse());

            mockMvc.perform(patch("/api/payment-methods/{id}/enable", paymentMethodId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"enabled\": true}"))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-payment-method"})
        @DisplayName("returns 200 OK for user with lc-payment-method role")
        void domainRoleCanEnable() throws Exception {
            when(paymentMethodService.setPaymentMethodEnabled(paymentMethodId, true))
                    .thenReturn(buildResponse());

            mockMvc.perform(patch("/api/payment-methods/{id}/enable", paymentMethodId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"enabled\": true}"))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(patch("/api/payment-methods/{id}/enable", paymentMethodId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"enabled\": true}"))
                    .andExpect(status().isForbidden());
        }
    }
}
