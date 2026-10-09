package com.lifecontrol.api.provisioning.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.hr.exception.EmployeeNotFoundException;
import com.lifecontrol.api.provisioning.dto.AccessProvisioningClaims;
import com.lifecontrol.api.provisioning.dto.AccessProvisioningOverview;
import com.lifecontrol.api.provisioning.dto.AccessProvisioningRoleDiff;
import com.lifecontrol.api.provisioning.dto.AccessProvisioningTaskView;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus;
import com.lifecontrol.api.provisioning.service.AccessProvisioningQueryService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Controller-level verification of {@code GET …/access}: the delegated contract (the service's own
 * overview is what is serialized), the two path variables reaching the service unchanged, and the
 * not-found case resolving to 404 through the existing {@link GlobalExceptionHandler} mapping rather
 * than a new exception type.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccessProvisioningController Tests")
class AccessProvisioningControllerTest {

    private static final String BASE_URL = "/api/companies/{companyId}/employees/{employeeId}/access";

    private MockMvc mockMvc;

    @Mock
    private AccessProvisioningQueryService accessProvisioningQueryService;

    @InjectMocks
    private AccessProvisioningController controller;

    private final UUID companyId = UUID.randomUUID();
    private final UUID employeeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private AccessProvisioningOverview overview() {
        var taskId = UUID.randomUUID();
        var openTask = new AccessProvisioningTaskView(
                taskId,
                AccessProvisioningTaskKind.ACTIVATE,
                AccessProvisioningTaskStatus.FAILED,
                1,
                5,
                "keycloak is unreachable",
                null,
                "operator",
                LocalDateTime.now(),
                null,
                null,
                true);

        var companyClaim = List.of(companyId.toString());
        var claims = new AccessProvisioningClaims(companyClaim, List.of(), List.of(), List.of(), List.of());

        return new AccessProvisioningOverview(
                "d4a1f0c2-0000-0000-0000-000000000001",
                true,
                Set.of("lc-company", "lc-sales"),
                Set.of("lc-sales"),
                new AccessProvisioningRoleDiff(Set.of("lc-company"), Set.of()),
                openTask,
                List.of(openTask),
                claims);
    }

    @Test
    @DisplayName("should return 200 with the overview the service returns")
    void returnsTheServiceOverview() throws Exception {
        when(accessProvisioningQueryService.getAccessOverview(companyId, employeeId))
                .thenReturn(overview());

        mockMvc.perform(get(BASE_URL, companyId, employeeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountLinked").value(true))
                .andExpect(jsonPath("$.keycloakUserId").value("d4a1f0c2-0000-0000-0000-000000000001"))
                .andExpect(jsonPath("$.requiredRoles[0]").value("lc-company"))
                .andExpect(jsonPath("$.requiredRoles[1]").value("lc-sales"))
                .andExpect(jsonPath("$.currentRoles[0]").value("lc-sales"))
                .andExpect(jsonPath("$.roleDiff.added[0]").value("lc-company"))
                .andExpect(jsonPath("$.roleDiff.removed").isEmpty())
                .andExpect(jsonPath("$.openTask.kind").value("ACTIVATE"))
                .andExpect(jsonPath("$.openTask.status").value("FAILED"))
                .andExpect(jsonPath("$.openTask.attempts").value(1))
                .andExpect(jsonPath("$.openTask.maxAttempts").value(5))
                .andExpect(jsonPath("$.openTask.lastError").value("keycloak is unreachable"))
                .andExpect(jsonPath("$.history[0].status").value("FAILED"))
                .andExpect(jsonPath("$.claims.companyId[0]").value(companyId.toString()));
    }

    @Test
    @DisplayName("should pass the company and employee ids to the service unchanged")
    void passesPathVariablesUnchanged() throws Exception {
        when(accessProvisioningQueryService.getAccessOverview(any(), any())).thenReturn(overview());

        mockMvc.perform(get(BASE_URL, companyId, employeeId)).andExpect(status().isOk());

        var companyCaptor = ArgumentCaptor.forClass(UUID.class);
        var employeeCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(accessProvisioningQueryService).getAccessOverview(companyCaptor.capture(), employeeCaptor.capture());
        assertThat(companyCaptor.getValue()).isEqualTo(companyId);
        assertThat(employeeCaptor.getValue()).isEqualTo(employeeId);
    }

    @Test
    @DisplayName("should return 404 through the global handler when the employee is outside the company")
    void returns404ForForeignEmployee() throws Exception {
        when(accessProvisioningQueryService.getAccessOverview(companyId, employeeId))
                .thenThrow(new EmployeeNotFoundException(employeeId));

        mockMvc.perform(get(BASE_URL, companyId, employeeId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Employee not found with id: " + employeeId));
    }
}
