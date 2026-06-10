package com.lifecontrol.api.usersadmin.identity;

import com.lifecontrol.api.usersadmin.dto.PageResponse;
import com.lifecontrol.api.usersadmin.dto.RoleRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.UserRepresentation;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("IdentityProvider interface contract")
class IdentityProviderTest {

    private static final Set<String> EXPECTED_METHODS = Set.of(
            "createUser",
            "deleteUser",
            "listRealmRoles",
            "createRealmRole",
            "getRealmRole",
            "updateRealmRole",
            "deleteRealmRole",
            "listClientRoles",
            "createClientRole",
            "addChildRole",
            "removeChildRole",
            "assignRoleToUser",
            "removeRoleFromUser",
            "getUserRoles",
            "getUserAttributes",
            "updateUserAttribute",
            "deleteUserAttribute",
            "createGroup",
            "searchUsers",
            "companyGroupExists",
            "findGroupIdByName",
            "deleteClientRole"
    );

    @Nested
    @DisplayName("interface existence and structure")
    class InterfaceStructureTests {

        @Test
        @DisplayName("should be an interface")
        void shouldBeAnInterface() {
            assertThat(IdentityProvider.class.isInterface()).isTrue();
        }

        @Test
        @DisplayName("should declare all required methods from design contract")
        void shouldDeclareAllRequiredMethods() {
            var declaredMethods = Arrays.stream(IdentityProvider.class.getDeclaredMethods())
                    .map(Method::getName)
                    .collect(Collectors.toSet());

            assertThat(declaredMethods).containsAll(EXPECTED_METHODS);
        }

        @Test
        @DisplayName("listRealmRoles should return List of RoleDto")
        void listRealmRolesSignature() throws Exception {
            var method = IdentityProvider.class.getMethod("listRealmRoles");
            assertThat(method.getReturnType()).isEqualTo(List.class);
        }

        @Test
        @DisplayName("createRealmRole should accept RoleRequest and return RoleDto")
        void createRealmRoleSignature() throws Exception {
            var method = IdentityProvider.class.getMethod("createRealmRole", RoleRequest.class);
            assertThat(method.getReturnType()).isEqualTo(RoleDto.class);
        }

        @Test
        @DisplayName("searchUsers should accept query/page/size and return PageResponse")
        void searchUsersSignature() throws Exception {
            var method = IdentityProvider.class.getMethod("searchUsers", String.class, int.class, int.class);
            assertThat(method.getReturnType()).isEqualTo(PageResponse.class);
        }

        @Test
        @DisplayName("getUserAttributes should return Map")
        void getUserAttributesSignature() throws Exception {
            var method = IdentityProvider.class.getMethod("getUserAttributes", String.class);
            assertThat(method.getReturnType()).isEqualTo(Map.class);
        }

        @Test
        @DisplayName("companyGroupExists should accept String groupName and return boolean")
        void companyGroupExistsSignature() throws Exception {
            var method = IdentityProvider.class.getMethod("companyGroupExists", String.class);
            assertThat(method.getReturnType()).isEqualTo(boolean.class);
        }

        @Test
        @DisplayName("createUser should accept UserRepresentation and return String")
        void createUserSignature() throws Exception {
            var method = IdentityProvider.class.getMethod("createUser", UserRepresentation.class);
            assertThat(method.getReturnType()).isEqualTo(String.class);
        }

        @Test
        @DisplayName("deleteUser should accept String userId and return void")
        void deleteUserSignature() throws Exception {
            var method = IdentityProvider.class.getMethod("deleteUser", String.class);
            assertThat(method.getReturnType()).isEqualTo(void.class);
        }
    }
}
