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
import com.lifecontrol.api.hr.dto.PositionRoleRequest;
import com.lifecontrol.api.hr.dto.PositionRoleResponse;
import com.lifecontrol.api.hr.dto.PositionRolesRequest;
import com.lifecontrol.api.hr.exception.PositionNotFoundException;
import com.lifecontrol.api.hr.service.PositionRoleService;
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
@DisplayName("PositionRoleController Tests")
class PositionRoleControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private PositionRoleService positionRoleService;

    @InjectMocks
    private PositionRoleController controller;

    private UUID companyId;
    private UUID positionId;
    private PositionRoleResponse testResponse;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        companyId = UUID.randomUUID();
        positionId = UUID.randomUUID();
        testResponse = response("lc-position", true);
    }

    private PositionRoleResponse response(String roleName, boolean enabled) {
        return new PositionRoleResponse(
                UUID.randomUUID(), positionId, roleName, enabled, LocalDateTime.now(), LocalDateTime.now());
    }

    private PositionRolesRequest buildRequest(String roleName) {
        return new PositionRolesRequest(List.of(new PositionRoleRequest(roleName)));
    }

    private String path() {
        return "/api/companies/{companyId}/positions/{positionId}/roles";
    }

    @Nested
    @DisplayName("GET /api/companies/{companyId}/positions/{positionId}/roles")
    class GetRolesTests {

        @Test
        @DisplayName("should return 200 with every stored role including disabled ones")
        void getRoles_Returns200() throws Exception {
            when(positionRoleService.getRoles(companyId, positionId))
                    .thenReturn(List.of(testResponse, response("lc-department", false)));

            mockMvc.perform(get(path(), companyId, positionId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].positionId").value(positionId.toString()))
                    .andExpect(jsonPath("$[0].roleName").value("lc-position"))
                    .andExpect(jsonPath("$[0].enabled").value(true))
                    .andExpect(jsonPath("$[1].roleName").value("lc-department"))
                    .andExpect(jsonPath("$[1].enabled").value(false));
            verify(positionRoleService).getRoles(companyId, positionId);
        }

        @Test
        @DisplayName("should return 404 when the position belongs to another company")
        void getRoles_NotFound_Returns404() throws Exception {
            when(positionRoleService.getRoles(companyId, positionId))
                    .thenThrow(new PositionNotFoundException(positionId));

            mockMvc.perform(get(path(), companyId, positionId)).andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("PUT /api/companies/{companyId}/positions/{positionId}/roles")
    class ReplaceRolesTests {

        @Test
        @DisplayName("should return 200 with the resulting set when the save is valid")
        void replaceRoles_Valid_Returns200() throws Exception {
            when(positionRoleService.replaceRoles(eq(companyId), eq(positionId), any(PositionRolesRequest.class)))
                    .thenReturn(List.of(testResponse));

            mockMvc.perform(put(path(), companyId, positionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest("lc-position"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].roleName").value("lc-position"))
                    .andExpect(jsonPath("$[0].enabled").value(true));
            verify(positionRoleService).replaceRoles(eq(companyId), eq(positionId), any(PositionRolesRequest.class));
        }

        @Test
        @DisplayName("should return 200 with an empty list when the request clears every role")
        void replaceRoles_EmptyList_Returns200() throws Exception {
            when(positionRoleService.replaceRoles(eq(companyId), eq(positionId), any(PositionRolesRequest.class)))
                    .thenReturn(List.of());

            mockMvc.perform(put(path(), companyId, positionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"roles\":[]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isEmpty());
        }

        @Test
        @DisplayName("should return 400 when the roles list is missing")
        void replaceRoles_MissingList_Returns400() throws Exception {
            mockMvc.perform(put(path(), companyId, positionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(positionRoleService);
        }

        @Test
        @DisplayName("should return 400 when a role name is missing")
        void replaceRoles_MissingRoleName_Returns400() throws Exception {
            mockMvc.perform(put(path(), companyId, positionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"roles\":[{}]}"))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(positionRoleService);
        }

        @Test
        @DisplayName("should return 400 when a role is outside the frozen allowlist")
        void replaceRoles_RoleOutsideAllowlist_Returns400() throws Exception {
            when(positionRoleService.replaceRoles(eq(companyId), eq(positionId), any(PositionRolesRequest.class)))
                    .thenThrow(new IllegalArgumentException("roleName is not a grantable position role: lc-admin"));

            mockMvc.perform(put(path(), companyId, positionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest("lc-admin"))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 400 when an allowlisted role is not a client role of the application client")
        void replaceRoles_UnknownClientRole_Returns400() throws Exception {
            when(positionRoleService.replaceRoles(eq(companyId), eq(positionId), any(PositionRolesRequest.class)))
                    .thenThrow(new IllegalArgumentException(
                            "roleName is not a client role of the application client: lc-position"));

            mockMvc.perform(put(path(), companyId, positionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest("lc-position"))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 400 when a role name is repeated")
        void replaceRoles_DuplicateRoleName_Returns400() throws Exception {
            when(positionRoleService.replaceRoles(eq(companyId), eq(positionId), any(PositionRolesRequest.class)))
                    .thenThrow(new IllegalArgumentException("Duplicate roleName in position roles: lc-position"));

            var body = new PositionRolesRequest(
                    List.of(new PositionRoleRequest("lc-position"), new PositionRoleRequest("lc-position")));

            mockMvc.perform(put(path(), companyId, positionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 404 when the position belongs to another company")
        void replaceRoles_PositionNotFound_Returns404() throws Exception {
            when(positionRoleService.replaceRoles(eq(companyId), eq(positionId), any(PositionRolesRequest.class)))
                    .thenThrow(new PositionNotFoundException(positionId));

            mockMvc.perform(put(path(), companyId, positionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest("lc-position"))))
                    .andExpect(status().isNotFound());
        }
    }
}
