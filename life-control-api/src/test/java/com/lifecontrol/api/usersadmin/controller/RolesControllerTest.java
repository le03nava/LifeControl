package com.lifecontrol.api.usersadmin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.usersadmin.dto.ChildRoleRequest;
import com.lifecontrol.api.usersadmin.dto.RoleRequest;
import com.lifecontrol.api.usersadmin.identity.IdentityProviderConflictException;
import com.lifecontrol.api.usersadmin.identity.IdentityProviderNotFoundException;
import com.lifecontrol.api.usersadmin.identity.RoleDto;
import com.lifecontrol.api.usersadmin.identity.RoleScope;
import com.lifecontrol.api.usersadmin.service.UsersAdminService;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("RolesController Tests")
class RolesControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private UsersAdminService service;

    @InjectMocks
    private RolesController controller;

    private RoleDto testRole;
    private RoleRequest testRoleRequest;
    private ChildRoleRequest testChildRequest;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();

        testRole = new RoleDto("test-role", "Test role description", false, RoleScope.REALM, null);
        testRoleRequest = new RoleRequest("test-role", "Test role description", false, null);
        testChildRequest = new ChildRoleRequest("child-role", RoleScope.REALM, null);
    }

    // ─── Realm Role Happy Path ──────────────────────────────

    @Nested
    @DisplayName("Realm Role endpoints — happy path")
    class RealmRoleHappyPath {

        @Test
        @DisplayName("GET /api/users-admin/roles/realm returns 200 with role list")
        void listRealmRoles_returns200() throws Exception {
            var roles = List.of(testRole);
            when(service.listRealmRoles()).thenReturn(roles);

            mockMvc.perform(get("/api/users-admin/roles/realm"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].name").value("test-role"))
                    .andExpect(jsonPath("$[0].description").value("Test role description"))
                    .andExpect(jsonPath("$[0].composite").value(false))
                    .andExpect(jsonPath("$[0].scope").value("REALM"));
        }

        @Test
        @DisplayName("POST /api/users-admin/roles/realm returns 201 with created role")
        void createRealmRole_returns201() throws Exception {
            when(service.createRealmRole(any(RoleRequest.class))).thenReturn(testRole);

            mockMvc.perform(post("/api/users-admin/roles/realm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testRoleRequest)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("test-role"))
                    .andExpect(jsonPath("$.description").value("Test role description"));
        }

        @Test
        @DisplayName("GET /api/users-admin/roles/realm/{name} returns 200 with role")
        void getRealmRole_returns200() throws Exception {
            when(service.getRealmRole("test-role")).thenReturn(testRole);

            mockMvc.perform(get("/api/users-admin/roles/realm/test-role"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("test-role"));
        }

        @Test
        @DisplayName("PUT /api/users-admin/roles/realm/{name} returns 200 with updated role")
        void updateRealmRole_returns200() throws Exception {
            var updated = new RoleDto("test-role", "Updated description", true, RoleScope.REALM, null);
            when(service.updateRealmRole(eq("test-role"), any(RoleRequest.class))).thenReturn(updated);

            mockMvc.perform(put("/api/users-admin/roles/realm/test-role")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testRoleRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.description").value("Updated description"))
                    .andExpect(jsonPath("$.composite").value(true));
        }

        @Test
        @DisplayName("DELETE /api/users-admin/roles/realm/{name} returns 204")
        void deleteRealmRole_returns204() throws Exception {
            mockMvc.perform(delete("/api/users-admin/roles/realm/test-role"))
                    .andExpect(status().isNoContent());

            verify(service).deleteRealmRole("test-role");
        }
    }

    // ─── Client Role Happy Path ─────────────────────────────

    @Nested
    @DisplayName("Client Role endpoints — happy path")
    class ClientRoleHappyPath {

        @Test
        @DisplayName("GET /api/users-admin/roles/client/{clientId} returns 200")
        void listClientRoles_returns200() throws Exception {
            var clientRoles = List.of(
                new RoleDto("client-role", "Client desc", false, RoleScope.CLIENT, "my-client")
            );
            when(service.listClientRoles("my-client")).thenReturn(clientRoles);

            mockMvc.perform(get("/api/users-admin/roles/client/my-client"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].name").value("client-role"))
                    .andExpect(jsonPath("$[0].scope").value("CLIENT"))
                    .andExpect(jsonPath("$[0].clientId").value("my-client"));
        }

        @Test
        @DisplayName("POST /api/users-admin/roles/client/{clientId} returns 201")
        void createClientRole_returns201() throws Exception {
            var clientRole = new RoleDto("client-role", "Desc", false, RoleScope.CLIENT, "my-client");
            when(service.createClientRole(eq("my-client"), any(RoleRequest.class))).thenReturn(clientRole);

            mockMvc.perform(post("/api/users-admin/roles/client/my-client")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testRoleRequest)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.scope").value("CLIENT"))
                    .andExpect(jsonPath("$.clientId").value("my-client"));
        }
    }

    // ─── Composite Children Happy Path ──────────────────────

    @Nested
    @DisplayName("Composite Child Role endpoints")
    class CompositeChildTests {

        @Test
        @DisplayName("POST /api/users-admin/roles/realm/{name}/children returns 201")
        void addChildRole_returns201() throws Exception {
            mockMvc.perform(post("/api/users-admin/roles/realm/parent-role/children")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testChildRequest)))
                    .andExpect(status().isCreated());

            verify(service).addChildRole("parent-role", "child-role", RoleScope.REALM, null);
        }
    }

    // ─── Error Cases ────────────────────────────────────────

    @Nested
    @DisplayName("Error cases")
    class ErrorCases {

        @Test
        @DisplayName("GET unknown realm role returns 404")
        void getRealmRole_notFound_returns404() throws Exception {
            when(service.getRealmRole("nonexistent"))
                    .thenThrow(new IdentityProviderNotFoundException("Role not found: nonexistent"));

            mockMvc.perform(get("/api/users-admin/roles/realm/nonexistent"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }

        @Test
        @DisplayName("POST duplicate realm role returns 409")
        void createRealmRole_duplicate_returns409() throws Exception {
            when(service.createRealmRole(any(RoleRequest.class)))
                    .thenThrow(new IdentityProviderConflictException("Role already exists: test-role"));

            mockMvc.perform(post("/api/users-admin/roles/realm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testRoleRequest)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
        }

        @Test
        @DisplayName("POST realm role with blank name returns 400")
        void createRealmRole_blankName_returns400() throws Exception {
            var invalidRequest = new RoleRequest("", "Some desc", false, null);

            mockMvc.perform(post("/api/users-admin/roles/realm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest());
        }
    }
}
