package com.lifecontrol.api.hr.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.config.ratelimit.RateLimitProperties;
import com.lifecontrol.api.hr.dto.CloseContractRequest;
import com.lifecontrol.api.hr.dto.ContractRequest;
import com.lifecontrol.api.hr.dto.ContractResponse;
import com.lifecontrol.api.hr.model.ContractType;
import com.lifecontrol.api.hr.service.ContractService;
import java.math.BigDecimal;
import java.time.LocalDate;
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

@WebMvcTest(ContractController.class)
@DisplayName("Contract Controller Security — @PreAuthorize method-level authorization")
class ContractControllerSecurityTest {

    /**
     * Minimal security configuration that enables method-level security without requiring JWT/OAuth2
     * infrastructure. {@code @WithMockUser} sets up the SecurityContext directly, bypassing
     * authentication filters.
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
    private ContractService contractService;

    @MockitoBean
    private RateLimitProperties rateLimitProperties;

    private final UUID companyId = UUID.randomUUID();
    private final UUID employeeId = UUID.randomUUID();
    private final UUID contractId = UUID.randomUUID();

    private ContractRequest buildRequest() {
        return new ContractRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "PERMANENT",
                new BigDecimal("1500.00"),
                LocalDate.of(2026, 1, 1),
                null);
    }

    private ContractResponse buildResponse() {
        return new ContractResponse(
                contractId,
                employeeId,
                UUID.randomUUID(),
                "Operator",
                UUID.randomUUID(),
                "Junior",
                ContractType.PERMANENT,
                new BigDecimal("1500.00"),
                LocalDate.of(2026, 1, 1),
                null,
                true);
    }

    // ─── Reads: any authenticated user ────────────────────────────

    @Nested
    @DisplayName("GET endpoints")
    class ReadSecurity {

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 200 OK for any authenticated user on the list")
        void anyAuthenticatedUserCanList() throws Exception {
            when(contractService.getContracts(companyId, employeeId)).thenReturn(List.of(buildResponse()));

            mockMvc.perform(get("/api/companies/{companyId}/employees/{employeeId}/contracts", companyId, employeeId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for an unauthenticated list")
        void unauthenticatedListReturns401() throws Exception {
            mockMvc.perform(get("/api/companies/{companyId}/employees/{employeeId}/contracts", companyId, employeeId))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ─── POST ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/companies/{companyId}/employees/{employeeId}/contracts")
    class CreateContractSecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 201 Created for lc-admin")
        void adminCanCreate() throws Exception {
            when(contractService.createContract(eq(companyId), eq(employeeId), any(ContractRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(post("/api/companies/{companyId}/employees/{employeeId}/contracts", companyId, employeeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = {"lc-employee"})
        @DisplayName("returns 201 Created for lc-employee")
        void employeeRoleCanCreate() throws Exception {
            when(contractService.createContract(eq(companyId), eq(employeeId), any(ContractRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(post("/api/companies/{companyId}/employees/{employeeId}/contracts", companyId, employeeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = {"lc-department"})
        @DisplayName("returns 403 Forbidden for an unrelated catalog-write role")
        void unrelatedCatalogRoleGetsForbidden() throws Exception {
            mockMvc.perform(post("/api/companies/{companyId}/employees/{employeeId}/contracts", companyId, employeeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for a user with the wrong role")
        void wrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(post("/api/companies/{companyId}/employees/{employeeId}/contracts", companyId, employeeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for an unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(post("/api/companies/{companyId}/employees/{employeeId}/contracts", companyId, employeeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ─── PATCH close ──────────────────────────────────────────────

    @Nested
    @DisplayName("PATCH /api/companies/{companyId}/employees/{employeeId}/contracts/{id}/close")
    class CloseContractSecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for lc-admin")
        void adminCanClose() throws Exception {
            when(contractService.closeContract(
                            eq(companyId), eq(employeeId), eq(contractId), any(CloseContractRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(patch(
                                    "/api/companies/{companyId}/employees/{employeeId}/contracts/{id}/close",
                                    companyId,
                                    employeeId,
                                    contractId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-employee"})
        @DisplayName("returns 200 OK for lc-employee")
        void employeeRoleCanClose() throws Exception {
            when(contractService.closeContract(
                            eq(companyId), eq(employeeId), eq(contractId), any(CloseContractRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(patch(
                                    "/api/companies/{companyId}/employees/{employeeId}/contracts/{id}/close",
                                    companyId,
                                    employeeId,
                                    contractId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-department"})
        @DisplayName("returns 403 Forbidden for an unrelated catalog-write role")
        void unrelatedCatalogRoleGetsForbidden() throws Exception {
            mockMvc.perform(patch(
                                    "/api/companies/{companyId}/employees/{employeeId}/contracts/{id}/close",
                                    companyId,
                                    employeeId,
                                    contractId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for a user with the wrong role")
        void wrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(patch(
                                    "/api/companies/{companyId}/employees/{employeeId}/contracts/{id}/close",
                                    companyId,
                                    employeeId,
                                    contractId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for an unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(patch(
                                    "/api/companies/{companyId}/employees/{employeeId}/contracts/{id}/close",
                                    companyId,
                                    employeeId,
                                    contractId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isUnauthorized());
        }
    }
}
