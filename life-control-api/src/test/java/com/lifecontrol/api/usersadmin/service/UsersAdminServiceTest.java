package com.lifecontrol.api.usersadmin.service;

import com.lifecontrol.api.usersadmin.dto.PageResponse;
import com.lifecontrol.api.usersadmin.dto.RoleRequest;
import com.lifecontrol.api.usersadmin.identity.IdentityProvider;
import com.lifecontrol.api.usersadmin.identity.RoleDto;
import com.lifecontrol.api.usersadmin.identity.RoleScope;
import com.lifecontrol.api.usersadmin.identity.UserSearchDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UsersAdminService Tests")
class UsersAdminServiceTest {

    @Mock
    private IdentityProvider identityProvider;

    @InjectMocks
    private UsersAdminService service;

    private RoleDto testRole;
    private RoleRequest testRoleRequest;
    private UserSearchDto testUser;
    private PageResponse<UserSearchDto> testPage;

    @BeforeEach
    void setUp() {
        testRole = new RoleDto("test-role", "Test role description", false, RoleScope.REALM, null);
        testRoleRequest = new RoleRequest("test-role", "Test role description", false, null);
        testUser = new UserSearchDto("user-1", "testuser", "test@example.com", true);
        testPage = new PageResponse<>(List.of(testUser), 0, 20, 1);
    }

    // ─── Realm Role CRUD ─────────────────────────────────────

    @Nested
    @DisplayName("Realm Role operations")
    class RealmRoleTests {

        @Test
        @DisplayName("listRealmRoles delegates to IdentityProvider and returns roles")
        void listRealmRoles_shouldDelegate() {
            var roles = List.of(testRole);
            when(identityProvider.listRealmRoles()).thenReturn(roles);

            var result = service.listRealmRoles();

            assertThat(result).isEqualTo(roles);
            assertThat(result).hasSize(1);
            assertThat(result.get(0).name()).isEqualTo("test-role");
            verify(identityProvider).listRealmRoles();
        }

        @Test
        @DisplayName("createRealmRole delegates to IdentityProvider and returns created role")
        void createRealmRole_shouldDelegate() {
            when(identityProvider.createRealmRole(testRoleRequest)).thenReturn(testRole);

            var result = service.createRealmRole(testRoleRequest);

            assertThat(result).isEqualTo(testRole);
            verify(identityProvider).createRealmRole(testRoleRequest);
        }

        @Test
        @DisplayName("getRealmRole delegates to IdentityProvider")
        void getRealmRole_shouldDelegate() {
            when(identityProvider.getRealmRole("test-role")).thenReturn(testRole);

            var result = service.getRealmRole("test-role");

            assertThat(result).isEqualTo(testRole);
            verify(identityProvider).getRealmRole("test-role");
        }

        @Test
        @DisplayName("updateRealmRole delegates to IdentityProvider with name and request")
        void updateRealmRole_shouldDelegate() {
            var updated = new RoleDto("test-role", "Updated desc", true, RoleScope.REALM, null);
            when(identityProvider.updateRealmRole("test-role", testRoleRequest)).thenReturn(updated);

            var result = service.updateRealmRole("test-role", testRoleRequest);

            assertThat(result.description()).isEqualTo("Updated desc");
            assertThat(result.composite()).isTrue();
            verify(identityProvider).updateRealmRole("test-role", testRoleRequest);
        }

        @Test
        @DisplayName("deleteRealmRole delegates to IdentityProvider")
        void deleteRealmRole_shouldDelegate() {
            service.deleteRealmRole("test-role");

            verify(identityProvider).deleteRealmRole("test-role");
        }
    }

    // ─── Client Role CRUD ───────────────────────────────────

    @Nested
    @DisplayName("Client Role operations")
    class ClientRoleTests {

        @Test
        @DisplayName("listClientRoles delegates to IdentityProvider with clientId")
        void listClientRoles_shouldDelegate() {
            var clientRoles = List.of(
                new RoleDto("client-role", "Client role desc", false, RoleScope.CLIENT, "my-client")
            );
            when(identityProvider.listClientRoles("my-client")).thenReturn(clientRoles);

            var result = service.listClientRoles("my-client");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).scope()).isEqualTo(RoleScope.CLIENT);
            assertThat(result.get(0).clientId()).isEqualTo("my-client");
            verify(identityProvider).listClientRoles("my-client");
        }

        @Test
        @DisplayName("createClientRole delegates to IdentityProvider with clientId and request")
        void createClientRole_shouldDelegate() {
            var clientRole = new RoleDto("client-role", "Desc", false, RoleScope.CLIENT, "my-client");
            when(identityProvider.createClientRole("my-client", testRoleRequest)).thenReturn(clientRole);

            var result = service.createClientRole("my-client", testRoleRequest);

            assertThat(result).isEqualTo(clientRole);
            verify(identityProvider).createClientRole("my-client", testRoleRequest);
        }
    }

    // ─── Composite Roles ────────────────────────────────────

    @Nested
    @DisplayName("Composite Role operations")
    class CompositeRoleTests {

        @Test
        @DisplayName("addChildRole delegates all parameters to IdentityProvider")
        void addChildRole_shouldDelegate() {
            service.addChildRole("parent-role", "child-role", RoleScope.REALM, null);

            verify(identityProvider).addChildRole("parent-role", "child-role", RoleScope.REALM, null);
        }

        @Test
        @DisplayName("removeChildRole delegates all parameters to IdentityProvider")
        void removeChildRole_shouldDelegate() {
            service.removeChildRole("parent-role", "child-role", RoleScope.CLIENT, "my-client");

            verify(identityProvider).removeChildRole("parent-role", "child-role", RoleScope.CLIENT, "my-client");
        }
    }

    // ─── User Role Assignment ────────────────────────────────

    @Nested
    @DisplayName("User Role Assignment operations")
    class UserRoleAssignmentTests {

        @Test
        @DisplayName("assignRoleToUser delegates to IdentityProvider with all params")
        void assignRoleToUser_shouldDelegate() {
            service.assignRoleToUser("user-1", "admin-role", RoleScope.REALM, null);

            verify(identityProvider).assignRoleToUser("user-1", "admin-role", RoleScope.REALM, null);
        }

        @Test
        @DisplayName("removeRoleFromUser delegates to IdentityProvider with all params")
        void removeRoleFromUser_shouldDelegate() {
            service.removeRoleFromUser("user-1", "admin-role", RoleScope.CLIENT, "my-client");

            verify(identityProvider).removeRoleFromUser("user-1", "admin-role", RoleScope.CLIENT, "my-client");
        }

        @Test
        @DisplayName("getUserRoles without clientId delegates to IdentityProvider")
        void getUserRoles_shouldDelegate() {
            var roles = List.of(testRole);
            when(identityProvider.getUserRoles("user-1")).thenReturn(roles);

            var result = service.getUserRoles("user-1");

            assertThat(result).isEqualTo(roles);
            verify(identityProvider).getUserRoles("user-1");
        }

        @Test
        @DisplayName("getUserRoles with clientId delegates filtered call")
        void getUserRoles_withClientId_shouldDelegate() {
            var clientRoles = List.of(
                new RoleDto("client-role", "Desc", false, RoleScope.CLIENT, "my-client")
            );
            when(identityProvider.getUserRoles("user-1", "my-client")).thenReturn(clientRoles);

            var result = service.getUserRoles("user-1", "my-client");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).clientId()).isEqualTo("my-client");
            verify(identityProvider).getUserRoles("user-1", "my-client");
        }
    }

    // ─── User Attributes ─────────────────────────────────────

    @Nested
    @DisplayName("User Attribute operations")
    class UserAttributeTests {

        @Test
        @DisplayName("getUserAttributes delegates and returns attributes map")
        void getUserAttributes_shouldDelegate() {
            var attrs = Map.of("department", List.of("engineering"));
            when(identityProvider.getUserAttributes("user-1")).thenReturn(attrs);

            var result = service.getUserAttributes("user-1");

            assertThat(result).containsKey("department");
            assertThat(result.get("department")).contains("engineering");
            verify(identityProvider).getUserAttributes("user-1");
        }

        @Test
        @DisplayName("updateUserAttribute delegates with key and values")
        void updateUserAttribute_shouldDelegate() {
            var values = List.of("value1", "value2");

            service.updateUserAttribute("user-1", "custom-key", values);

            verify(identityProvider).updateUserAttribute("user-1", "custom-key", values);
        }

        @Test
        @DisplayName("deleteUserAttribute delegates with userId and key")
        void deleteUserAttribute_shouldDelegate() {
            service.deleteUserAttribute("user-1", "custom-key");

            verify(identityProvider).deleteUserAttribute("user-1", "custom-key");
        }
    }

    // ─── User Search ─────────────────────────────────────────

    @Nested
    @DisplayName("User Search operations")
    class UserSearchTests {

        @Test
        @DisplayName("searchUsers delegates and returns paginated result")
        void searchUsers_shouldDelegate() {
            when(identityProvider.searchUsers("test", 0, 20)).thenReturn(testPage);

            var result = service.searchUsers("test", 0, 20);

            assertThat(result.content()).hasSize(1);
            assertThat(result.content().get(0).username()).isEqualTo("testuser");
            assertThat(result.total()).isEqualTo(1);
            verify(identityProvider).searchUsers("test", 0, 20);
        }

        @Test
        @DisplayName("searchUsers with empty query delegates correctly")
        void searchUsers_emptyQuery_shouldDelegate() {
            PageResponse<UserSearchDto> emptyPage = new PageResponse<>(List.of(), 0, 20, 0);
            when(identityProvider.searchUsers("", 0, 20)).thenReturn(emptyPage);

            var result = service.searchUsers("", 0, 20);

            assertThat(result.content()).isEmpty();
            assertThat(result.total()).isEqualTo(0);
            verify(identityProvider).searchUsers("", 0, 20);
        }
    }
}
