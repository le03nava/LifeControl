package com.lifecontrol.api.usersadmin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.usersadmin.dto.AttributeValueRequest;
import com.lifecontrol.api.usersadmin.dto.PageResponse;
import com.lifecontrol.api.usersadmin.dto.UserAssignmentRequest;
import com.lifecontrol.api.usersadmin.identity.IdentityProviderNotFoundException;
import com.lifecontrol.api.usersadmin.identity.RoleDto;
import com.lifecontrol.api.usersadmin.identity.RoleScope;
import com.lifecontrol.api.usersadmin.identity.UserSearchDto;
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
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
@DisplayName("UsersAdminController Tests")
class UsersAdminControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private UsersAdminService service;

    @InjectMocks
    private UsersAdminController controller;

    private RoleDto testRole;
    private UserSearchDto testUser;
    private PageResponse<UserSearchDto> testPage;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();

        testRole = new RoleDto("admin-role", "Administrator", false, RoleScope.REALM, null);
        testUser = new UserSearchDto("user-1", "testuser", "test@example.com", true);
        testPage = new PageResponse<>(List.of(testUser), 0, 20, 1);
    }

    // ─── User Search ─────────────────────────────────────────

    @Nested
    @DisplayName("User Search endpoints")
    class UserSearchTests {

        @Test
        @DisplayName("GET /api/users-admin/users?search=test returns 200 with paginated result")
        void searchUsers_returns200() throws Exception {
            when(service.searchUsers("test", 0, 20)).thenReturn(testPage);

            mockMvc.perform(get("/api/users-admin/users")
                            .param("search", "test")
                            .param("page", "0")
                            .param("size", "20"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].username").value("testuser"))
                    .andExpect(jsonPath("$.content[0].email").value("test@example.com"))
                    .andExpect(jsonPath("$.total").value(1));
        }

        @Test
        @DisplayName("GET /api/users-admin/users with default params returns 200")
        void searchUsers_defaultParams_returns200() throws Exception {
            PageResponse<UserSearchDto> emptyPage = new PageResponse<>(List.of(), 0, 20, 0);
            when(service.searchUsers("", 0, 20)).thenReturn(emptyPage);

            mockMvc.perform(get("/api/users-admin/users"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isEmpty())
                    .andExpect(jsonPath("$.total").value(0));
        }
    }

    // ─── User Role Listing ───────────────────────────────────

    @Nested
    @DisplayName("User Role listing endpoints")
    class UserRoleListingTests {

        @Test
        @DisplayName("GET /api/users-admin/users/{id}/roles returns 200 with roles")
        void getUserRoles_returns200() throws Exception {
            var roles = List.of(testRole);
            when(service.getUserRoles("user-1")).thenReturn(roles);

            mockMvc.perform(get("/api/users-admin/users/user-1/roles"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].name").value("admin-role"))
                    .andExpect(jsonPath("$[0].scope").value("REALM"));
        }

        @Test
        @DisplayName("GET /api/users-admin/users/{id}/roles?clientId=X returns filtered roles")
        void getUserRoles_withClientId_returns200() throws Exception {
            var clientRoles = List.of(
                new RoleDto("client-role", "Client role", false, RoleScope.CLIENT, "my-client")
            );
            when(service.getUserRoles("user-1", "my-client")).thenReturn(clientRoles);

            mockMvc.perform(get("/api/users-admin/users/user-1/roles")
                            .param("clientId", "my-client"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].scope").value("CLIENT"))
                    .andExpect(jsonPath("$[0].clientId").value("my-client"));
        }

        @Test
        @DisplayName("GET /api/users-admin/users/{id}/roles for unknown user returns 404")
        void getUserRoles_unknownUser_returns404() throws Exception {
            when(service.getUserRoles("unknown"))
                    .thenThrow(new IdentityProviderNotFoundException("User not found: unknown"));

            mockMvc.perform(get("/api/users-admin/users/unknown/roles"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }
    }

    // ─── Role Assignment ─────────────────────────────────────

    @Nested
    @DisplayName("Role Assignment endpoints")
    class RoleAssignmentTests {

        @Test
        @DisplayName("POST /api/users-admin/users/{id}/roles/realm returns 201")
        void assignRealmRole_returns201() throws Exception {
            var request = new UserAssignmentRequest("admin-role");

            mockMvc.perform(post("/api/users-admin/users/user-1/roles/realm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());

            verify(service).assignRoleToUser("user-1", "admin-role", RoleScope.REALM, null);
        }

        @Test
        @DisplayName("POST /api/users-admin/users/{id}/roles/client/{clientId} returns 201")
        void assignClientRole_returns201() throws Exception {
            var request = new UserAssignmentRequest("client-role");

            mockMvc.perform(post("/api/users-admin/users/user-1/roles/client/my-client")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());

            verify(service).assignRoleToUser("user-1", "client-role", RoleScope.CLIENT, "my-client");
        }

        @Test
        @DisplayName("DELETE /api/users-admin/users/{id}/roles/realm/{roleName} returns 204")
        void removeRealmRole_returns204() throws Exception {
            mockMvc.perform(delete("/api/users-admin/users/user-1/roles/realm/admin-role"))
                    .andExpect(status().isNoContent());

            verify(service).removeRoleFromUser("user-1", "admin-role", RoleScope.REALM, null);
        }

        @Test
        @DisplayName("DELETE /api/users-admin/users/{id}/roles/client/{clientId}/{roleName} returns 204")
        void removeClientRole_returns204() throws Exception {
            mockMvc.perform(delete("/api/users-admin/users/user-1/roles/client/my-client/client-role"))
                    .andExpect(status().isNoContent());

            verify(service).removeRoleFromUser("user-1", "client-role", RoleScope.CLIENT, "my-client");
        }

        @Test
        @DisplayName("POST assign role to unknown user returns 404")
        void assignRole_unknownUser_returns404() throws Exception {
            var request = new UserAssignmentRequest("admin-role");
            doThrow(new IdentityProviderNotFoundException("User not found: unknown"))
                    .when(service).assignRoleToUser(eq("unknown"), anyString(), any(RoleScope.class), any());

            mockMvc.perform(post("/api/users-admin/users/unknown/roles/realm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound());
        }
    }

    // ─── User Attributes ─────────────────────────────────────

    @Nested
    @DisplayName("User Attribute endpoints")
    class UserAttributeTests {

        @Test
        @DisplayName("GET /api/users-admin/users/{id}/attributes returns 200 with attributes map")
        void getUserAttributes_returns200() throws Exception {
            var attrs = Map.of("department", List.of("engineering"));
            when(service.getUserAttributes("user-1")).thenReturn(attrs);

            mockMvc.perform(get("/api/users-admin/users/user-1/attributes"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.department[0]").value("engineering"));
        }

        @Test
        @DisplayName("PUT /api/users-admin/users/{id}/attributes/{key} returns 200")
        void updateUserAttribute_returns200() throws Exception {
            var request = new AttributeValueRequest(List.of("value1", "value2"));

            mockMvc.perform(put("/api/users-admin/users/user-1/attributes/custom-key")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());

            verify(service).updateUserAttribute("user-1", "custom-key", List.of("value1", "value2"));
        }

        @Test
        @DisplayName("DELETE /api/users-admin/users/{id}/attributes/{key} returns 204")
        void deleteUserAttribute_returns204() throws Exception {
            mockMvc.perform(delete("/api/users-admin/users/user-1/attributes/custom-key"))
                    .andExpect(status().isNoContent());

            verify(service).deleteUserAttribute("user-1", "custom-key");
        }
    }
}
