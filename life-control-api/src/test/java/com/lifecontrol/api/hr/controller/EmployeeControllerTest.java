package com.lifecontrol.api.hr.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.hr.dto.EmployeeEmailSuggestionResponse;
import com.lifecontrol.api.hr.dto.EmployeeRequest;
import com.lifecontrol.api.hr.dto.EmployeeResponse;
import com.lifecontrol.api.hr.exception.DuplicateEmployeeException;
import com.lifecontrol.api.hr.exception.EmployeeEmailFrozenException;
import com.lifecontrol.api.hr.exception.EmployeeNotFoundException;
import com.lifecontrol.api.hr.service.EmployeeService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmployeeController Tests")
class EmployeeControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private EmployeeService employeeService;

    @InjectMocks
    private EmployeeController employeeController;

    private UUID companyId;
    private UUID employeeId;
    private UUID statusId;
    private EmployeeResponse testResponse;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(employeeController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        companyId = UUID.randomUUID();
        employeeId = UUID.randomUUID();
        statusId = UUID.randomUUID();
        testResponse = buildResponse();
    }

    private EmployeeResponse buildResponse() {
        return new EmployeeResponse(
                employeeId,
                companyId,
                "EMP-001",
                "Juan",
                "Pérez",
                "López",
                "juan.perez@example.com",
                "+52 55 0000 0000",
                LocalDate.of(1990, 1, 1),
                LocalDate.of(2020, 1, 1),
                null,
                null,
                statusId,
                "Active",
                null,
                true,
                0L,
                LocalDateTime.now(),
                LocalDateTime.now(),
                null);
    }

    private EmployeeRequest buildRequest() {
        return new EmployeeRequest(
                "EMP-001",
                "Juan",
                "Pérez",
                "López",
                "juan.perez@example.com",
                "+52 55 0000 0000",
                LocalDate.of(1990, 1, 1),
                LocalDate.of(2020, 1, 1),
                null,
                null,
                null);
    }

    /**
     * The detail payload with the one provisioning field it carries: the access state. Built from
     * the default response so only that field varies.
     */
    private EmployeeResponse withAccessState(String accessState) {
        return new EmployeeResponse(
                testResponse.id(),
                testResponse.companyId(),
                testResponse.employeeNumber(),
                testResponse.firstName(),
                testResponse.paternalLastName(),
                testResponse.maternalLastName(),
                testResponse.email(),
                testResponse.phoneNumber(),
                testResponse.birthDate(),
                testResponse.hireDate(),
                testResponse.terminationDate(),
                testResponse.addressId(),
                testResponse.statusId(),
                testResponse.statusName(),
                testResponse.keycloakUserId(),
                testResponse.enabled(),
                testResponse.version(),
                testResponse.createdAt(),
                testResponse.updatedAt(),
                accessState);
    }

    @Nested
    @DisplayName("GET /api/companies/{companyId}/employees")
    class GetAllEmployeesTests {

        @Test
        @DisplayName("should return 200 with the company's employees and default filters")
        void getAllEmployees_Returns200() throws Exception {
            when(employeeService.getAllEmployees(companyId, null, null, false)).thenReturn(List.of(testResponse));

            mockMvc.perform(get("/api/companies/{companyId}/employees", companyId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].companyId").value(companyId.toString()))
                    .andExpect(jsonPath("$[0].employeeNumber").value("EMP-001"))
                    .andExpect(jsonPath("$[0].email").value("juan.perez@example.com"))
                    .andExpect(jsonPath("$[0].statusName").value("Active"))
                    .andExpect(jsonPath("$[0].accessState").value(nullValue()))
                    .andExpect(jsonPath("$[0].enabled").value(true));
            verify(employeeService).getAllEmployees(companyId, null, null, false);
        }

        @Test
        @DisplayName("should pass search, statusId and includeDisabled through")
        void getAllEmployees_PassesFilters() throws Exception {
            when(employeeService.getAllEmployees(companyId, "juan", statusId, true))
                    .thenReturn(List.of(testResponse));

            mockMvc.perform(get("/api/companies/{companyId}/employees", companyId)
                            .param("search", "juan")
                            .param("statusId", statusId.toString())
                            .param("includeDisabled", "true"))
                    .andExpect(status().isOk());
            verify(employeeService).getAllEmployees(companyId, "juan", statusId, true);
        }
    }

    @Nested
    @DisplayName("GET /api/companies/{companyId}/employees/{id}")
    class GetEmployeeByIdTests {

        @Test
        @DisplayName("should return 200 when the employee exists in the company")
        void getEmployeeById_Returns200() throws Exception {
            when(employeeService.getEmployeeById(companyId, employeeId)).thenReturn(testResponse);

            mockMvc.perform(get("/api/companies/{companyId}/employees/{id}", companyId, employeeId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.employeeNumber").value("EMP-001"));
        }

        @Test
        @DisplayName("should carry the access state field on the detail payload")
        void getEmployeeById_CarriesAccessState() throws Exception {
            when(employeeService.getEmployeeById(companyId, employeeId)).thenReturn(withAccessState("FAILED"));

            mockMvc.perform(get("/api/companies/{companyId}/employees/{id}", companyId, employeeId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.employeeNumber").value("EMP-001"))
                    .andExpect(jsonPath("$.accessState").value("FAILED"));
        }

        @Test
        @DisplayName("should return 404 when the employee does not exist in the company")
        void getEmployeeById_NotFound_Returns404() throws Exception {
            when(employeeService.getEmployeeById(companyId, employeeId))
                    .thenThrow(new EmployeeNotFoundException(employeeId));

            mockMvc.perform(get("/api/companies/{companyId}/employees/{id}", companyId, employeeId))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("GET /api/companies/{companyId}/employees/suggest-email")
    class SuggestEmailTests {

        @Test
        @DisplayName("should return 200 with the suggested email")
        void suggestEmail_Returns200() throws Exception {
            when(employeeService.suggestEmail(companyId, "Juan", "Pérez"))
                    .thenReturn(new EmployeeEmailSuggestionResponse("juan.perez@example.com", null));

            mockMvc.perform(get("/api/companies/{companyId}/employees/suggest-email", companyId)
                            .param("firstName", "Juan")
                            .param("paternalLastName", "Pérez"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value("juan.perez@example.com"))
                    .andExpect(jsonPath("$.reason").value(nullValue()));
        }

        @Test
        @DisplayName("should return 200 with a null email and a reason when there is no domain")
        void suggestEmail_NoDomain_ReturnsReason() throws Exception {
            when(employeeService.suggestEmail(companyId, "Juan", "Pérez"))
                    .thenReturn(new EmployeeEmailSuggestionResponse(null, "NO_EMAIL_DOMAIN"));

            mockMvc.perform(get("/api/companies/{companyId}/employees/suggest-email", companyId)
                            .param("firstName", "Juan")
                            .param("paternalLastName", "Pérez"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value(nullValue()))
                    .andExpect(jsonPath("$.reason").value("NO_EMAIL_DOMAIN"));
        }

        @Test
        @DisplayName("should return 400 when firstName is missing")
        void suggestEmail_MissingFirstName_Returns400() throws Exception {
            mockMvc.perform(get("/api/companies/{companyId}/employees/suggest-email", companyId)
                            .param("paternalLastName", "Pérez"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("POST /api/companies/{companyId}/employees")
    class CreateEmployeeTests {

        @Test
        @DisplayName("should return 201 with the created employee")
        void createEmployee_Returns201() throws Exception {
            when(employeeService.createEmployee(eq(companyId), any(EmployeeRequest.class)))
                    .thenReturn(testResponse);

            mockMvc.perform(post("/api/companies/{companyId}/employees", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.employeeNumber").value("EMP-001"));
        }

        @Test
        @DisplayName("should return 400 when the employee number is blank")
        void createEmployee_BlankNumber_Returns400() throws Exception {
            var request = new EmployeeRequest(
                    "",
                    "Juan",
                    "Pérez",
                    null,
                    null,
                    null,
                    LocalDate.of(1990, 1, 1),
                    LocalDate.of(2020, 1, 1),
                    null,
                    null,
                    null);

            mockMvc.perform(post("/api/companies/{companyId}/employees", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 400 when the birth date is missing")
        void createEmployee_MissingBirthDate_Returns400() throws Exception {
            var request = new EmployeeRequest(
                    "EMP-001", "Juan", "Pérez", null, null, null, null, LocalDate.of(2020, 1, 1), null, null, null);

            mockMvc.perform(post("/api/companies/{companyId}/employees", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should accept a blank email because generation is the service's job")
        void createEmployee_BlankEmail_Returns201() throws Exception {
            when(employeeService.createEmployee(eq(companyId), any(EmployeeRequest.class)))
                    .thenReturn(testResponse);
            var request = new EmployeeRequest(
                    "EMP-001",
                    "Juan",
                    "Pérez",
                    null,
                    "",
                    null,
                    LocalDate.of(1990, 1, 1),
                    LocalDate.of(2020, 1, 1),
                    null,
                    null,
                    null);

            mockMvc.perform(post("/api/companies/{companyId}/employees", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("should return 400 when the email has no local part")
        void createEmployee_MalformedEmailWithoutLocalPart_Returns400() throws Exception {
            var request = new EmployeeRequest(
                    "EMP-001",
                    "Juan",
                    "Pérez",
                    null,
                    "@c.com",
                    null,
                    LocalDate.of(1990, 1, 1),
                    LocalDate.of(2020, 1, 1),
                    null,
                    null,
                    null);

            mockMvc.perform(post("/api/companies/{companyId}/employees", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 400 when the email carries several at-signs")
        void createEmployee_MalformedEmailWithSeveralAtSigns_Returns400() throws Exception {
            var request = new EmployeeRequest(
                    "EMP-001",
                    "Juan",
                    "Pérez",
                    null,
                    "a@b@c.com",
                    null,
                    LocalDate.of(1990, 1, 1),
                    LocalDate.of(2020, 1, 1),
                    null,
                    null,
                    null);

            mockMvc.perform(post("/api/companies/{companyId}/employees", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 409 when the employee number or email collides")
        void createEmployee_Duplicate_Returns409() throws Exception {
            when(employeeService.createEmployee(eq(companyId), any(EmployeeRequest.class)))
                    .thenThrow(new DuplicateEmployeeException("employeeNumber", "EMP-001"));

            mockMvc.perform(post("/api/companies/{companyId}/employees", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isConflict());
        }
    }

    @Nested
    @DisplayName("PUT /api/companies/{companyId}/employees/{id}")
    class UpdateEmployeeTests {

        @Test
        @DisplayName("should return 200 when updated successfully")
        void updateEmployee_Returns200() throws Exception {
            when(employeeService.updateEmployee(eq(companyId), eq(employeeId), any(EmployeeRequest.class)))
                    .thenReturn(testResponse);

            mockMvc.perform(put("/api/companies/{companyId}/employees/{id}", companyId, employeeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.employeeNumber").value("EMP-001"));
        }

        @Test
        @DisplayName("should return 404 when the employee does not exist in the company")
        void updateEmployee_NotFound_Returns404() throws Exception {
            when(employeeService.updateEmployee(eq(companyId), eq(employeeId), any(EmployeeRequest.class)))
                    .thenThrow(new EmployeeNotFoundException(employeeId));

            mockMvc.perform(put("/api/companies/{companyId}/employees/{id}", companyId, employeeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 409 when the email is frozen")
        void updateEmployee_FrozenEmail_Returns409() throws Exception {
            when(employeeService.updateEmployee(eq(companyId), eq(employeeId), any(EmployeeRequest.class)))
                    .thenThrow(new EmployeeEmailFrozenException(employeeId));

            mockMvc.perform(put("/api/companies/{companyId}/employees/{id}", companyId, employeeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isConflict());
        }
    }

    @Nested
    @DisplayName("DELETE /api/companies/{companyId}/employees/{id}")
    class DeleteEmployeeTests {

        @Test
        @DisplayName("should return 204 when soft-deleted successfully")
        void deleteEmployee_Returns204() throws Exception {
            mockMvc.perform(delete("/api/companies/{companyId}/employees/{id}", companyId, employeeId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("should return 404 when the employee does not exist in the company")
        void deleteEmployee_NotFound_Returns404() throws Exception {
            doThrow(new EmployeeNotFoundException(employeeId))
                    .when(employeeService)
                    .deleteEmployee(companyId, employeeId);

            mockMvc.perform(delete("/api/companies/{companyId}/employees/{id}", companyId, employeeId))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("PATCH /api/companies/{companyId}/employees/{id}/enable")
    class SetEmployeeEnabledTests {

        @Test
        @DisplayName("should default the body value to true when the body is empty")
        void setEmployeeEnabled_DefaultsToTrue() throws Exception {
            when(employeeService.setEmployeeEnabled(companyId, employeeId, true))
                    .thenReturn(testResponse);

            mockMvc.perform(patch("/api/companies/{companyId}/employees/{id}/enable", companyId, employeeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(true));
        }

        @Test
        @DisplayName("should set exactly the value the body carries")
        void setEmployeeEnabled_SetsExactValue() throws Exception {
            var disabled = buildResponse();
            when(employeeService.setEmployeeEnabled(companyId, employeeId, false))
                    .thenReturn(new EmployeeResponse(
                            disabled.id(),
                            disabled.companyId(),
                            disabled.employeeNumber(),
                            disabled.firstName(),
                            disabled.paternalLastName(),
                            disabled.maternalLastName(),
                            disabled.email(),
                            disabled.phoneNumber(),
                            disabled.birthDate(),
                            disabled.hireDate(),
                            disabled.terminationDate(),
                            disabled.addressId(),
                            disabled.statusId(),
                            disabled.statusName(),
                            disabled.keycloakUserId(),
                            false,
                            disabled.version(),
                            disabled.createdAt(),
                            disabled.updatedAt(),
                            disabled.accessState()));

            mockMvc.perform(patch("/api/companies/{companyId}/employees/{id}/enable", companyId, employeeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("enabled", false))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(false));
        }
    }
}
