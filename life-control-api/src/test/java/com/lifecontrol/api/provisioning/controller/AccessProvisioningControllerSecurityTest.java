package com.lifecontrol.api.provisioning.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.config.ratelimit.RateLimitProperties;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.provisioning.dto.AccessProvisioningClaims;
import com.lifecontrol.api.provisioning.dto.AccessProvisioningOverview;
import com.lifecontrol.api.provisioning.dto.AccessProvisioningRoleDiff;
import com.lifecontrol.api.provisioning.service.AccessProvisioningGateService;
import com.lifecontrol.api.provisioning.service.AccessProvisioningQueryService;
import com.lifecontrol.api.provisioning.service.AccessProvisioningRequestService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Pins the {@code @PreAuthorize} role set of the three employee-access routes: it is the only
 * evidence that the gate exists at all. A user with no relevant role is refused with 403, and each of
 * {@code lc-admin} and {@code lc-employee-access} alone is enough — there is no read-only pair
 * (record {@code employee-access-provisioning} O5). The two decision routes get the same coverage as
 * the read route because they grant and refuse access.
 */
@WebMvcTest(AccessProvisioningController.class)
@Import({GlobalExceptionHandler.class, AccessProvisioningControllerSecurityTest.TestSecurityConfig.class})
@DisplayName("AccessProvisioningController Security — @PreAuthorize method-level authorization")
class AccessProvisioningControllerSecurityTest {

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

    private static final String BASE_URL = "/api/companies/{companyId}/employees/{employeeId}/access";
    private static final String APPLY_URL = BASE_URL + "/apply";
    private static final String DEACTIVATE_URL = BASE_URL + "/deactivate";
    private static final String RETRY_URL = BASE_URL + "/retry";
    private static final String RESEND_URL = BASE_URL + "/resend";
    private static final String APPROVE_URL = BASE_URL + "/requests/{taskId}/approve";
    private static final String REJECT_URL = BASE_URL + "/requests/{taskId}/reject";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccessProvisioningQueryService accessProvisioningQueryService;

    /**
     * The controller's second collaborator. The decision-route cases below assert it is actually
     * invoked, so a 200 is evidence the request reached the controller and not just that a role
     * matched.
     */
    @MockitoBean
    private AccessProvisioningGateService accessProvisioningGateService;

    /**
     * The controller's third collaborator, added with the two intent routes (unit W5c, slice S1). The
     * cases below assert it is actually invoked, so a 200 is evidence the request reached the
     * controller and not just that a role matched.
     */
    @MockitoBean
    private AccessProvisioningRequestService accessProvisioningRequestService;

    @MockitoBean
    private RateLimitProperties rateLimitProperties;

    private final UUID companyId = UUID.randomUUID();
    private final UUID employeeId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(accessProvisioningQueryService.getAccessOverview(any(), any())).thenReturn(emptyOverview());
    }

    private AccessProvisioningOverview emptyOverview() {
        return new AccessProvisioningOverview(
                null,
                false,
                Set.of(),
                Set.of(),
                new AccessProvisioningRoleDiff(Set.of(), Set.of()),
                null,
                null,
                List.of(),
                AccessProvisioningClaims.empty());
    }

    @Nested
    @DisplayName("GET " + BASE_URL)
    class GetAccessOverview {

        @Test
        @DisplayName("returns 401 for an unauthenticated request")
        void unauthenticatedGetsUnauthorized() throws Exception {
            mockMvc.perform(get(BASE_URL, companyId, employeeId)).andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 for a user with no roles")
        void noRolesGetsForbidden() throws Exception {
            mockMvc.perform(get(BASE_URL, companyId, employeeId)).andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for an unrelated role")
        void unrelatedRoleGetsForbidden() throws Exception {
            mockMvc.perform(get(BASE_URL, companyId, employeeId)).andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-employee"})
        @DisplayName("returns 403 for the HR employee role alone, which is a different power")
        void employeeRoleAloneIsNotEnough() throws Exception {
            mockMvc.perform(get(BASE_URL, companyId, employeeId)).andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 for lc-admin alone")
        void adminCanRead() throws Exception {
            mockMvc.perform(get(BASE_URL, companyId, employeeId)).andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-employee-access"})
        @DisplayName("returns 200 for lc-employee-access alone")
        void employeeAccessRoleCanRead() throws Exception {
            mockMvc.perform(get(BASE_URL, companyId, employeeId)).andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("POST " + APPROVE_URL)
    class ApproveRequest {

        @Test
        @DisplayName("returns 401 for an unauthenticated request")
        void unauthenticatedGetsUnauthorized() throws Exception {
            mockMvc.perform(post(APPROVE_URL, companyId, employeeId, taskId)).andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 for a user with no roles")
        void noRolesGetsForbidden() throws Exception {
            mockMvc.perform(post(APPROVE_URL, companyId, employeeId, taskId)).andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for an unrelated role")
        void unrelatedRoleGetsForbidden() throws Exception {
            mockMvc.perform(post(APPROVE_URL, companyId, employeeId, taskId)).andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 for lc-admin alone and reaches the controller")
        void adminCanApprove() throws Exception {
            mockMvc.perform(post(APPROVE_URL, companyId, employeeId, taskId)).andExpect(status().isOk());

            verify(accessProvisioningGateService).approve(companyId, employeeId, taskId);
        }

        @Test
        @WithMockUser(roles = {"lc-employee-access"})
        @DisplayName("returns 200 for lc-employee-access alone and reaches the controller")
        void employeeAccessRoleCanApprove() throws Exception {
            mockMvc.perform(post(APPROVE_URL, companyId, employeeId, taskId)).andExpect(status().isOk());

            verify(accessProvisioningGateService).approve(companyId, employeeId, taskId);
        }
    }

    @Nested
    @DisplayName("POST " + APPLY_URL)
    class ApplyRequest {

        @Test
        @DisplayName("returns 401 for an unauthenticated request")
        void unauthenticatedGetsUnauthorized() throws Exception {
            mockMvc.perform(post(APPLY_URL, companyId, employeeId)).andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 for a user with no roles")
        void noRolesGetsForbidden() throws Exception {
            mockMvc.perform(post(APPLY_URL, companyId, employeeId)).andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for an unrelated role")
        void unrelatedRoleGetsForbidden() throws Exception {
            mockMvc.perform(post(APPLY_URL, companyId, employeeId)).andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-employee"})
        @DisplayName("returns 403 for the HR employee role alone, which is a different power")
        void employeeRoleAloneIsNotEnough() throws Exception {
            mockMvc.perform(post(APPLY_URL, companyId, employeeId)).andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 for lc-admin alone and reaches the controller")
        void adminCanApply() throws Exception {
            mockMvc.perform(post(APPLY_URL, companyId, employeeId)).andExpect(status().isOk());

            verify(accessProvisioningRequestService).apply(companyId, employeeId);
        }

        @Test
        @WithMockUser(roles = {"lc-employee-access"})
        @DisplayName("returns 200 for lc-employee-access alone and reaches the controller")
        void employeeAccessRoleCanApply() throws Exception {
            mockMvc.perform(post(APPLY_URL, companyId, employeeId)).andExpect(status().isOk());

            verify(accessProvisioningRequestService).apply(companyId, employeeId);
        }
    }

    @Nested
    @DisplayName("POST " + DEACTIVATE_URL)
    class DeactivateRequest {

        @Test
        @DisplayName("returns 401 for an unauthenticated request")
        void unauthenticatedGetsUnauthorized() throws Exception {
            mockMvc.perform(post(DEACTIVATE_URL, companyId, employeeId)).andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 for a user with no roles")
        void noRolesGetsForbidden() throws Exception {
            mockMvc.perform(post(DEACTIVATE_URL, companyId, employeeId)).andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for an unrelated role")
        void unrelatedRoleGetsForbidden() throws Exception {
            mockMvc.perform(post(DEACTIVATE_URL, companyId, employeeId)).andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 for lc-admin alone and reaches the controller")
        void adminCanDeactivate() throws Exception {
            mockMvc.perform(post(DEACTIVATE_URL, companyId, employeeId)).andExpect(status().isOk());

            verify(accessProvisioningRequestService).deactivate(companyId, employeeId);
        }

        @Test
        @WithMockUser(roles = {"lc-employee-access"})
        @DisplayName("returns 200 for lc-employee-access alone and reaches the controller")
        void employeeAccessRoleCanDeactivate() throws Exception {
            mockMvc.perform(post(DEACTIVATE_URL, companyId, employeeId)).andExpect(status().isOk());

            verify(accessProvisioningRequestService).deactivate(companyId, employeeId);
        }
    }

    @Nested
    @DisplayName("POST " + RETRY_URL)
    class RetryRequest {

        @Test
        @DisplayName("returns 401 for an unauthenticated request")
        void unauthenticatedGetsUnauthorized() throws Exception {
            mockMvc.perform(post(RETRY_URL, companyId, employeeId)).andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 for a user with no roles")
        void noRolesGetsForbidden() throws Exception {
            mockMvc.perform(post(RETRY_URL, companyId, employeeId)).andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for an unrelated role")
        void unrelatedRoleGetsForbidden() throws Exception {
            mockMvc.perform(post(RETRY_URL, companyId, employeeId)).andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 for lc-admin alone and reaches the controller")
        void adminCanRetry() throws Exception {
            mockMvc.perform(post(RETRY_URL, companyId, employeeId)).andExpect(status().isOk());

            verify(accessProvisioningRequestService).retry(companyId, employeeId);
        }

        @Test
        @WithMockUser(roles = {"lc-employee-access"})
        @DisplayName("returns 200 for lc-employee-access alone and reaches the controller")
        void employeeAccessRoleCanRetry() throws Exception {
            mockMvc.perform(post(RETRY_URL, companyId, employeeId)).andExpect(status().isOk());

            verify(accessProvisioningRequestService).retry(companyId, employeeId);
        }
    }

    @Nested
    @DisplayName("POST " + RESEND_URL)
    class ResendRequest {

        @Test
        @DisplayName("returns 401 for an unauthenticated request")
        void unauthenticatedGetsUnauthorized() throws Exception {
            mockMvc.perform(post(RESEND_URL, companyId, employeeId)).andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 for a user with no roles")
        void noRolesGetsForbidden() throws Exception {
            mockMvc.perform(post(RESEND_URL, companyId, employeeId)).andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for an unrelated role")
        void unrelatedRoleGetsForbidden() throws Exception {
            mockMvc.perform(post(RESEND_URL, companyId, employeeId)).andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 for lc-admin alone and reaches the controller")
        void adminCanResend() throws Exception {
            mockMvc.perform(post(RESEND_URL, companyId, employeeId)).andExpect(status().isOk());

            verify(accessProvisioningRequestService).resend(companyId, employeeId);
        }

        @Test
        @WithMockUser(roles = {"lc-employee-access"})
        @DisplayName("returns 200 for lc-employee-access alone and reaches the controller")
        void employeeAccessRoleCanResend() throws Exception {
            mockMvc.perform(post(RESEND_URL, companyId, employeeId)).andExpect(status().isOk());

            verify(accessProvisioningRequestService).resend(companyId, employeeId);
        }
    }

    @Nested
    @DisplayName("POST " + REJECT_URL)
    class RejectRequest {

        @Test
        @DisplayName("returns 401 for an unauthenticated request")
        void unauthenticatedGetsUnauthorized() throws Exception {
            mockMvc.perform(post(REJECT_URL, companyId, employeeId, taskId)).andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 for a user with no roles")
        void noRolesGetsForbidden() throws Exception {
            mockMvc.perform(post(REJECT_URL, companyId, employeeId, taskId)).andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for an unrelated role")
        void unrelatedRoleGetsForbidden() throws Exception {
            mockMvc.perform(post(REJECT_URL, companyId, employeeId, taskId)).andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 for lc-admin alone and reaches the controller")
        void adminCanReject() throws Exception {
            mockMvc.perform(post(REJECT_URL, companyId, employeeId, taskId)).andExpect(status().isOk());

            verify(accessProvisioningGateService).reject(companyId, employeeId, taskId, null);
        }

        @Test
        @WithMockUser(roles = {"lc-employee-access"})
        @DisplayName("returns 200 for lc-employee-access alone and reaches the controller")
        void employeeAccessRoleCanReject() throws Exception {
            mockMvc.perform(post(REJECT_URL, companyId, employeeId, taskId)).andExpect(status().isOk());

            verify(accessProvisioningGateService).reject(companyId, employeeId, taskId, null);
        }
    }
}
