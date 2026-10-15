package com.lifecontrol.api.provisioning.controller;

import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind.ACTIVATE;
import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus.APPROVAL_PENDING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.provisioning.dto.AccessProvisioningQueueEntry;
import com.lifecontrol.api.provisioning.service.AccessProvisioningQueryService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
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
 * Pins the <b>wire shape</b> of the company decision queue read: the path it answers on, the company id
 * it forwards unchanged, and the fields one row exposes (unit W5d, slice B, record T94).
 *
 * <p>The controller is asserted with a standalone {@link MockMvc} and a mocked service, the shape the
 * sibling employee-access controller test already uses: what a controller can be wrong about is its
 * path, its mapping and the payload it hands back, and none of those need a Spring context.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccessProvisioningQueueController Tests")
class AccessProvisioningQueueControllerTest {

    private static final String BASE_URL = "/api/companies/{companyId}/access/requests";

    private MockMvc mockMvc;

    @Mock
    private AccessProvisioningQueryService accessProvisioningQueryService;

    @InjectMocks
    private AccessProvisioningQueueController controller;

    private final UUID companyId = UUID.randomUUID();
    private final UUID employeeId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private AccessProvisioningQueueEntry entry() {
        return new AccessProvisioningQueueEntry(
                taskId,
                ACTIVATE,
                APPROVAL_PENDING,
                "kc-sub-requester",
                "Ada Lovelace",
                LocalDateTime.of(2026, 10, 10, 9, 0),
                employeeId,
                "EMP-001",
                "Ada",
                "Lovelace",
                "Byron");
    }

    @Test
    @DisplayName("should return 200 with the queue the service returns, one row per waiting task")
    void returnsTheServiceQueue() throws Exception {
        when(accessProvisioningQueryService.getDecisionQueue(companyId)).thenReturn(List.of(entry()));

        mockMvc.perform(get(BASE_URL, companyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].taskId").value(taskId.toString()))
                .andExpect(jsonPath("$[0].kind").value("ACTIVATE"))
                .andExpect(jsonPath("$[0].status").value("APPROVAL_PENDING"))
                .andExpect(jsonPath("$[0].requestedBy").value("kc-sub-requester"))
                .andExpect(jsonPath("$[0].requestedByName").value("Ada Lovelace"))
                .andExpect(jsonPath("$[0].employeeId").value(employeeId.toString()))
                .andExpect(jsonPath("$[0].employeeNumber").value("EMP-001"))
                .andExpect(jsonPath("$[0].employeeFirstName").value("Ada"))
                .andExpect(jsonPath("$[0].employeePaternalLastName").value("Lovelace"))
                .andExpect(jsonPath("$[0].employeeMaternalLastName").value("Byron"));
    }

    @Test
    @DisplayName("should answer an empty array when nothing waits for a human")
    void answersAnEmptyArrayWhenTheQueueIsEmpty() throws Exception {
        when(accessProvisioningQueryService.getDecisionQueue(companyId)).thenReturn(List.of());

        mockMvc.perform(get(BASE_URL, companyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("a queue row exposes exactly its own fields: no frozen set and no role names")
    void queueRowExposesExactlyItsOwnFields() throws Exception {
        when(accessProvisioningQueryService.getDecisionQueue(companyId)).thenReturn(List.of(entry()));

        var body = mockMvc.perform(get(BASE_URL, companyId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        var fields = new TreeSet<String>();
        new ObjectMapper().readTree(body).get(0).fieldNames().forEachRemaining(fields::add);

        assertThat(fields)
                .as("the queue row is its own, narrower view (records T94/T95): the frozen set and both"
                        + " role sets stay in the per-employee overview, and a field added here has to be a"
                        + " decision rather than a drift")
                .containsExactlyInAnyOrderElementsOf(Set.of(
                        "taskId",
                        "kind",
                        "status",
                        "requestedBy",
                        "requestedByName",
                        "requestedAt",
                        "employeeId",
                        "employeeNumber",
                        "employeeFirstName",
                        "employeePaternalLastName",
                        "employeeMaternalLastName"));
    }

    @Test
    @DisplayName("should pass the company id to the service unchanged")
    void passesTheCompanyIdUnchanged() throws Exception {
        when(accessProvisioningQueryService.getDecisionQueue(any())).thenReturn(List.of());

        mockMvc.perform(get(BASE_URL, companyId)).andExpect(status().isOk());

        var companyCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(accessProvisioningQueryService).getDecisionQueue(companyCaptor.capture());
        assertThat(companyCaptor.getValue()).isEqualTo(companyId);
    }
}
