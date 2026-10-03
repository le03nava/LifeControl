package com.lifecontrol.api.hr.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.exception.ConflictException;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.hr.dto.CloseContractRequest;
import com.lifecontrol.api.hr.dto.ContractRequest;
import com.lifecontrol.api.hr.dto.ContractResponse;
import com.lifecontrol.api.hr.exception.ContractNotFoundException;
import com.lifecontrol.api.hr.model.ContractType;
import com.lifecontrol.api.hr.service.ContractService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
@DisplayName("ContractController Tests")
class ContractControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private ContractService contractService;

    @InjectMocks
    private ContractController contractController;

    private UUID companyId;
    private UUID employeeId;
    private UUID contractId;
    private UUID positionId;
    private UUID seniorityLevelId;
    private ContractResponse testResponse;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(contractController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        companyId = UUID.randomUUID();
        employeeId = UUID.randomUUID();
        contractId = UUID.randomUUID();
        positionId = UUID.randomUUID();
        seniorityLevelId = UUID.randomUUID();
        testResponse = buildResponse();
    }

    private ContractResponse buildResponse() {
        return new ContractResponse(
                contractId,
                employeeId,
                positionId,
                "Operator",
                seniorityLevelId,
                "Junior",
                ContractType.PERMANENT,
                new BigDecimal("1500.00"),
                LocalDate.of(2026, 1, 1),
                null,
                true);
    }

    private ContractRequest buildRequest() {
        return new ContractRequest(
                positionId, seniorityLevelId, "PERMANENT", new BigDecimal("1500.00"), LocalDate.of(2026, 1, 1), null);
    }

    @Nested
    @DisplayName("GET /api/companies/{companyId}/employees/{employeeId}/contracts")
    class GetContractsTests {

        @Test
        @DisplayName("should return 200 with the contract history")
        void getContracts_Returns200() throws Exception {
            when(contractService.getContracts(companyId, employeeId)).thenReturn(List.of(testResponse));

            mockMvc.perform(get("/api/companies/{companyId}/employees/{employeeId}/contracts", companyId, employeeId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").value(contractId.toString()))
                    .andExpect(jsonPath("$[0].employeeId").value(employeeId.toString()))
                    .andExpect(jsonPath("$[0].positionId").value(positionId.toString()))
                    .andExpect(jsonPath("$[0].positionName").value("Operator"))
                    .andExpect(jsonPath("$[0].seniorityLevelId").value(seniorityLevelId.toString()))
                    .andExpect(jsonPath("$[0].seniorityLevelName").value("Junior"))
                    .andExpect(jsonPath("$[0].contractType").value("PERMANENT"))
                    .andExpect(jsonPath("$[0].monthlySalary").value(1500.00))
                    .andExpect(jsonPath("$[0].endDate").value(org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$[0].enabled").value(true));
            verify(contractService).getContracts(companyId, employeeId);
        }

        @Test
        @DisplayName("should return 404 when the employee is not in the company")
        void getContracts_ForeignEmployee_Returns404() throws Exception {
            when(contractService.getContracts(companyId, employeeId))
                    .thenThrow(new com.lifecontrol.api.hr.exception.EmployeeNotFoundException(employeeId));

            mockMvc.perform(get("/api/companies/{companyId}/employees/{employeeId}/contracts", companyId, employeeId))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("POST /api/companies/{companyId}/employees/{employeeId}/contracts")
    class CreateContractTests {

        @Test
        @DisplayName("should return 201 with the created contract")
        void createContract_Returns201() throws Exception {
            when(contractService.createContract(eq(companyId), eq(employeeId), any(ContractRequest.class)))
                    .thenReturn(testResponse);

            mockMvc.perform(post("/api/companies/{companyId}/employees/{employeeId}/contracts", companyId, employeeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(contractId.toString()))
                    .andExpect(jsonPath("$.positionName").value("Operator"));
        }

        @Test
        @DisplayName("should return 400 when the position is missing")
        void createContract_MissingPosition_Returns400() throws Exception {
            var request = new ContractRequest(
                    null, seniorityLevelId, "PERMANENT", new BigDecimal("1500.00"), LocalDate.of(2026, 1, 1), null);

            mockMvc.perform(post("/api/companies/{companyId}/employees/{employeeId}/contracts", companyId, employeeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 400 when the contract type is blank")
        void createContract_BlankType_Returns400() throws Exception {
            var request = new ContractRequest(
                    positionId, seniorityLevelId, "", new BigDecimal("1500.00"), LocalDate.of(2026, 1, 1), null);

            mockMvc.perform(post("/api/companies/{companyId}/employees/{employeeId}/contracts", companyId, employeeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 400 when the monthly salary is negative")
        void createContract_NegativeSalary_Returns400() throws Exception {
            var request = new ContractRequest(
                    positionId, seniorityLevelId, "PERMANENT", new BigDecimal("-1.00"), LocalDate.of(2026, 1, 1), null);

            mockMvc.perform(post("/api/companies/{companyId}/employees/{employeeId}/contracts", companyId, employeeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 404 when the position or level does not resolve")
        void createContract_PositionNotFound_Returns404() throws Exception {
            when(contractService.createContract(eq(companyId), eq(employeeId), any(ContractRequest.class)))
                    .thenThrow(new com.lifecontrol.api.hr.exception.PositionNotFoundException(positionId));

            mockMvc.perform(post("/api/companies/{companyId}/employees/{employeeId}/contracts", companyId, employeeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 409 when the employee is Terminated")
        void createContract_TerminatedEmployee_Returns409() throws Exception {
            when(contractService.createContract(eq(companyId), eq(employeeId), any(ContractRequest.class)))
                    .thenThrow(new ConflictException("A terminated employee cannot open a contract"));

            mockMvc.perform(post("/api/companies/{companyId}/employees/{employeeId}/contracts", companyId, employeeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isConflict());
        }
    }

    @Nested
    @DisplayName("PATCH /api/companies/{companyId}/employees/{employeeId}/contracts/{id}/close")
    class CloseContractTests {

        @Test
        @DisplayName("should return 200 with the closed contract when a body carries the end date")
        void closeContract_WithBody_Returns200() throws Exception {
            var closed = new ContractResponse(
                    contractId,
                    employeeId,
                    positionId,
                    "Operator",
                    seniorityLevelId,
                    "Junior",
                    ContractType.PERMANENT,
                    new BigDecimal("1500.00"),
                    LocalDate.of(2026, 1, 1),
                    LocalDate.of(2026, 6, 30),
                    true);
            when(contractService.closeContract(
                            eq(companyId), eq(employeeId), eq(contractId), any(CloseContractRequest.class)))
                    .thenReturn(closed);

            mockMvc.perform(patch(
                                    "/api/companies/{companyId}/employees/{employeeId}/contracts/{id}/close",
                                    companyId,
                                    employeeId,
                                    contractId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new CloseContractRequest(LocalDate.of(2026, 6, 30)))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(contractId.toString()));
        }

        @Test
        @DisplayName("should return 200 when the body is omitted (the end date defaults to today)")
        void closeContract_WithoutBody_Returns200() throws Exception {
            when(contractService.closeContract(companyId, employeeId, contractId, null))
                    .thenReturn(testResponse);

            mockMvc.perform(patch(
                            "/api/companies/{companyId}/employees/{employeeId}/contracts/{id}/close",
                            companyId,
                            employeeId,
                            contractId))
                    .andExpect(status().isOk());
            verify(contractService).closeContract(companyId, employeeId, contractId, null);
        }

        @Test
        @DisplayName("should return 409 when the contract is already closed")
        void closeContract_AlreadyClosed_Returns409() throws Exception {
            when(contractService.closeContract(eq(companyId), eq(employeeId), eq(contractId), any()))
                    .thenThrow(new ConflictException("Contract already closed"));

            mockMvc.perform(patch(
                                    "/api/companies/{companyId}/employees/{employeeId}/contracts/{id}/close",
                                    companyId,
                                    employeeId,
                                    contractId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("should return 404 when the contract is missing")
        void closeContract_Missing_Returns404() throws Exception {
            when(contractService.closeContract(eq(companyId), eq(employeeId), eq(contractId), any()))
                    .thenThrow(new ContractNotFoundException(contractId));

            mockMvc.perform(patch(
                                    "/api/companies/{companyId}/employees/{employeeId}/contracts/{id}/close",
                                    companyId,
                                    employeeId,
                                    contractId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 400 when the end date is before the contract's start date")
        void closeContract_EndBeforeStart_Returns400() throws Exception {
            when(contractService.closeContract(eq(companyId), eq(employeeId), eq(contractId), any()))
                    .thenThrow(new IllegalArgumentException("endDate must be on or after startDate"));

            mockMvc.perform(patch(
                                    "/api/companies/{companyId}/employees/{employeeId}/contracts/{id}/close",
                                    companyId,
                                    employeeId,
                                    contractId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("D12 — no contract PUT and no contract DELETE")
    class NoPutNoDeleteTests {

        @Test
        @DisplayName("PUT on the contract collection is method-not-allowed")
        void putOnCollectionIsNotSupported() throws Exception {
            mockMvc.perform(put("/api/companies/{companyId}/employees/{employeeId}/contracts", companyId, employeeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isMethodNotAllowed());
        }

        @Test
        @DisplayName("DELETE on the contract collection is method-not-allowed")
        void deleteOnCollectionIsNotSupported() throws Exception {
            mockMvc.perform(delete(
                            "/api/companies/{companyId}/employees/{employeeId}/contracts", companyId, employeeId))
                    .andExpect(status().isMethodNotAllowed());
        }

        @Test
        @DisplayName("PUT on the close route is method-not-allowed")
        void putOnCloseRouteIsNotSupported() throws Exception {
            mockMvc.perform(put(
                                    "/api/companies/{companyId}/employees/{employeeId}/contracts/{id}/close",
                                    companyId,
                                    employeeId,
                                    contractId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isMethodNotAllowed());
        }

        @Test
        @DisplayName("DELETE on the close route is method-not-allowed")
        void deleteOnCloseRouteIsNotSupported() throws Exception {
            mockMvc.perform(delete(
                            "/api/companies/{companyId}/employees/{employeeId}/contracts/{id}/close",
                            companyId,
                            employeeId,
                            contractId))
                    .andExpect(status().isMethodNotAllowed());
        }
    }
}
