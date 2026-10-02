package com.lifecontrol.api.hr.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.hr.dto.PositionSalaryBandRequest;
import com.lifecontrol.api.hr.dto.PositionSalaryBandResponse;
import com.lifecontrol.api.hr.dto.PositionSalaryBandsRequest;
import com.lifecontrol.api.hr.exception.PositionNotFoundException;
import com.lifecontrol.api.hr.exception.SeniorityLevelNotFoundException;
import com.lifecontrol.api.hr.service.PositionSalaryBandService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
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
@DisplayName("PositionSalaryBandController Tests")
class PositionSalaryBandControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private PositionSalaryBandService salaryBandService;

    @InjectMocks
    private PositionSalaryBandController controller;

    private UUID companyId;
    private UUID positionId;
    private UUID juniorId;
    private PositionSalaryBandResponse testResponse;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        companyId = UUID.randomUUID();
        positionId = UUID.randomUUID();
        juniorId = UUID.randomUUID();
        testResponse = response(juniorId, "100.00", "200.00", true);
    }

    private PositionSalaryBandResponse response(UUID levelId, String min, String max, boolean enabled) {
        return new PositionSalaryBandResponse(
                UUID.randomUUID(),
                positionId,
                levelId,
                new BigDecimal(min),
                new BigDecimal(max),
                enabled,
                LocalDateTime.now(),
                LocalDateTime.now());
    }

    private PositionSalaryBandsRequest buildRequest(String min, String max) {
        return new PositionSalaryBandsRequest(
                List.of(new PositionSalaryBandRequest(juniorId, new BigDecimal(min), new BigDecimal(max))));
    }

    private String path() {
        return "/api/companies/{companyId}/positions/{positionId}/salary-bands";
    }

    @Nested
    @DisplayName("GET /api/companies/{companyId}/positions/{positionId}/salary-bands")
    class GetSalaryBandsTests {

        @Test
        @DisplayName("should return 200 with every stored band including disabled ones")
        void getSalaryBands_Returns200() throws Exception {
            when(salaryBandService.getSalaryBands(companyId, positionId))
                    .thenReturn(List.of(testResponse, response(UUID.randomUUID(), "300.00", "400.00", false)));

            mockMvc.perform(get(path(), companyId, positionId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].positionId").value(positionId.toString()))
                    .andExpect(jsonPath("$[0].seniorityLevelId").value(juniorId.toString()))
                    .andExpect(jsonPath("$[0].minimumSalary").value(100.00))
                    .andExpect(jsonPath("$[0].maximumSalary").value(200.00))
                    .andExpect(jsonPath("$[0].enabled").value(true))
                    .andExpect(jsonPath("$[1].enabled").value(false));
            verify(salaryBandService).getSalaryBands(companyId, positionId);
        }

        @Test
        @DisplayName("should return 404 when the position belongs to another company")
        void getSalaryBands_NotFound_Returns404() throws Exception {
            when(salaryBandService.getSalaryBands(companyId, positionId))
                    .thenThrow(new PositionNotFoundException(positionId));

            mockMvc.perform(get(path(), companyId, positionId)).andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("PUT /api/companies/{companyId}/positions/{positionId}/salary-bands")
    class ReplaceSalaryBandsTests {

        @Test
        @DisplayName("should return 200 with the resulting set when the save is valid")
        void replaceSalaryBands_Valid_Returns200() throws Exception {
            when(salaryBandService.replaceSalaryBands(
                            eq(companyId), eq(positionId), any(PositionSalaryBandsRequest.class)))
                    .thenReturn(List.of(testResponse));

            mockMvc.perform(put(path(), companyId, positionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest("100.00", "200.00"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].seniorityLevelId").value(juniorId.toString()))
                    .andExpect(jsonPath("$[0].enabled").value(true));
            verify(salaryBandService)
                    .replaceSalaryBands(eq(companyId), eq(positionId), any(PositionSalaryBandsRequest.class));
        }

        @Test
        @DisplayName("should return 200 with an empty list when the request clears every band")
        void replaceSalaryBands_EmptyList_Returns200() throws Exception {
            when(salaryBandService.replaceSalaryBands(
                            eq(companyId), eq(positionId), any(PositionSalaryBandsRequest.class)))
                    .thenReturn(List.of());

            mockMvc.perform(put(path(), companyId, positionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"bands\":[]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isEmpty());
        }

        @Test
        @DisplayName("should return 400 when the bands list is missing")
        void replaceSalaryBands_MissingList_Returns400() throws Exception {
            mockMvc.perform(put(path(), companyId, positionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(salaryBandService);
        }

        @Test
        @DisplayName("should return 400 when an amount is missing")
        void replaceSalaryBands_MissingAmount_Returns400() throws Exception {
            mockMvc.perform(put(path(), companyId, positionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"bands\":[{\"seniorityLevelId\":\"" + juniorId
                                    + "\",\"minimumSalary\":100.00}]}"))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(salaryBandService);
        }

        @Test
        @DisplayName("should return 400 when the maximum is below the minimum")
        void replaceSalaryBands_MaxBelowMin_Returns400() throws Exception {
            when(salaryBandService.replaceSalaryBands(
                            eq(companyId), eq(positionId), any(PositionSalaryBandsRequest.class)))
                    .thenThrow(new IllegalArgumentException(
                            "maximumSalary must be greater than or equal to minimumSalary"));

            mockMvc.perform(put(path(), companyId, positionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest("200.00", "100.00"))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 400 when the minimum is negative")
        void replaceSalaryBands_NegativeMinimum_Returns400() throws Exception {
            when(salaryBandService.replaceSalaryBands(
                            eq(companyId), eq(positionId), any(PositionSalaryBandsRequest.class)))
                    .thenThrow(new IllegalArgumentException("minimumSalary must be greater than or equal to 0"));

            mockMvc.perform(put(path(), companyId, positionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest("-1.00", "100.00"))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 400 when a seniority level is repeated")
        void replaceSalaryBands_DuplicateLevel_Returns400() throws Exception {
            when(salaryBandService.replaceSalaryBands(
                            eq(companyId), eq(positionId), any(PositionSalaryBandsRequest.class)))
                    .thenThrow(new IllegalArgumentException("Duplicate seniorityLevelId in salary bands: " + juniorId));

            var body = new PositionSalaryBandsRequest(List.of(
                    new PositionSalaryBandRequest(juniorId, new BigDecimal("100.00"), new BigDecimal("200.00")),
                    new PositionSalaryBandRequest(juniorId, new BigDecimal("300.00"), new BigDecimal("400.00"))));

            mockMvc.perform(put(path(), companyId, positionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 404 when a seniority level does not exist")
        void replaceSalaryBands_UnknownLevel_Returns404() throws Exception {
            when(salaryBandService.replaceSalaryBands(
                            eq(companyId), eq(positionId), any(PositionSalaryBandsRequest.class)))
                    .thenThrow(new SeniorityLevelNotFoundException(juniorId));

            mockMvc.perform(put(path(), companyId, positionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest("100.00", "200.00"))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 404 when the position belongs to another company")
        void replaceSalaryBands_PositionNotFound_Returns404() throws Exception {
            when(salaryBandService.replaceSalaryBands(
                            eq(companyId), eq(positionId), any(PositionSalaryBandsRequest.class)))
                    .thenThrow(new PositionNotFoundException(positionId));

            mockMvc.perform(put(path(), companyId, positionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest("100.00", "200.00"))))
                    .andExpect(status().isNotFound());
        }
    }
}
