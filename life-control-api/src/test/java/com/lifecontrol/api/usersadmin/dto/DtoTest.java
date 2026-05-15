package com.lifecontrol.api.usersadmin.dto;

import com.lifecontrol.api.usersadmin.identity.RoleDto;
import com.lifecontrol.api.usersadmin.identity.RoleScope;
import com.lifecontrol.api.usersadmin.identity.UserSearchDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DTOs and shared types")
class DtoTest {

    @Nested
    @DisplayName("RoleRequest")
    class RoleRequestTests {

        @Test
        @DisplayName("should construct with all fields and return correct values")
        void shouldConstructWithAllFields() {
            var childRoles = List.of("child-role-1", "child-role-2");
            var request = new RoleRequest("admin", "Administrator role", true, childRoles);

            assertThat(request.name()).isEqualTo("admin");
            assertThat(request.description()).isEqualTo("Administrator role");
            assertThat(request.composite()).isTrue();
            assertThat(request.childRoles()).containsExactly("child-role-1", "child-role-2");
        }

        @Test
        @DisplayName("should construct with minimal fields (non-composite)")
        void shouldConstructWithMinimalFields() {
            var request = new RoleRequest("viewer", "Read-only role", false, null);

            assertThat(request.name()).isEqualTo("viewer");
            assertThat(request.description()).isEqualTo("Read-only role");
            assertThat(request.composite()).isFalse();
            assertThat(request.childRoles()).isNull();
        }

        @Test
        @DisplayName("should be equal when all fields match")
        void shouldBeEqualWhenFieldsMatch() {
            var request1 = new RoleRequest("admin", "desc", true, List.of("child"));
            var request2 = new RoleRequest("admin", "desc", true, List.of("child"));

            assertThat(request1).isEqualTo(request2);
            assertThat(request1.hashCode()).isEqualTo(request2.hashCode());
        }

        @Test
        @DisplayName("should not be equal when fields differ")
        void shouldNotBeEqualWhenFieldsDiffer() {
            var request1 = new RoleRequest("admin", "desc", true, null);
            var request2 = new RoleRequest("user", "desc", true, null);

            assertThat(request1).isNotEqualTo(request2);
        }
    }

    @Nested
    @DisplayName("RoleResponse")
    class RoleResponseTests {

        @Test
        @DisplayName("should construct with all fields and return correct values")
        void shouldConstructWithAllFields() {
            var response = new RoleResponse("admin", "Administrator role", true, RoleScope.REALM, null);

            assertThat(response.name()).isEqualTo("admin");
            assertThat(response.description()).isEqualTo("Administrator role");
            assertThat(response.composite()).isTrue();
            assertThat(response.scope()).isEqualTo(RoleScope.REALM);
            assertThat(response.clientId()).isNull();
        }

        @Test
        @DisplayName("should include clientId for client-scoped roles")
        void shouldIncludeClientIdForClientScope() {
            var response = new RoleResponse("client-role", "A client role", false, RoleScope.CLIENT, "my-client");

            assertThat(response.scope()).isEqualTo(RoleScope.CLIENT);
            assertThat(response.clientId()).isEqualTo("my-client");
        }
    }

    @Nested
    @DisplayName("UserAssignmentRequest")
    class UserAssignmentRequestTests {

        @Test
        @DisplayName("should construct with roleName and return correct value")
        void shouldConstructWithRoleName() {
            var request = new UserAssignmentRequest("admin");

            assertThat(request.roleName()).isEqualTo("admin");
        }
    }

    @Nested
    @DisplayName("UserSearchResponse")
    class UserSearchResponseTests {

        @Test
        @DisplayName("should construct with all fields")
        void shouldConstructWithAllFields() {
            var response = new UserSearchResponse("user-123", "john.doe", "john@example.com", true);

            assertThat(response.id()).isEqualTo("user-123");
            assertThat(response.username()).isEqualTo("john.doe");
            assertThat(response.email()).isEqualTo("john@example.com");
            assertThat(response.enabled()).isTrue();
        }

        @Test
        @DisplayName("should handle null email")
        void shouldHandleNullEmail() {
            var response = new UserSearchResponse("user-456", "jane.doe", null, false);

            assertThat(response.email()).isNull();
            assertThat(response.enabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("PageResponse")
    class PageResponseTests {

        @Test
        @DisplayName("should construct with content and pagination metadata")
        void shouldConstructWithContentAndMetadata() {
            var content = List.of("item1", "item2");
            var page = new PageResponse<>(content, 0, 10, 25L);

            assertThat(page.content()).containsExactly("item1", "item2");
            assertThat(page.page()).isEqualTo(0);
            assertThat(page.size()).isEqualTo(10);
            assertThat(page.total()).isEqualTo(25L);
        }

        @Test
        @DisplayName("should handle empty content")
        void shouldHandleEmptyContent() {
            var page = new PageResponse<>(List.of(), 0, 10, 0L);

            assertThat(page.content()).isEmpty();
            assertThat(page.total()).isZero();
        }

        @Test
        @DisplayName("should work with different content types")
        void shouldWorkWithDifferentContentTypes() {
            var user1 = new UserSearchResponse("id1", "user1", "u1@test.com", true);
            var user2 = new UserSearchResponse("id2", "user2", "u2@test.com", false);
            var page = new PageResponse<>(List.of(user1, user2), 0, 2, 2L);

            assertThat(page.content()).hasSize(2);
            assertThat(page.content().get(0).username()).isEqualTo("user1");
            assertThat(page.content().get(1).username()).isEqualTo("user2");
        }
    }

    @Nested
    @DisplayName("RoleScope enum")
    class RoleScopeTests {

        @Test
        @DisplayName("should have REALM and CLIENT values")
        void shouldHaveRealmAndClientValues() {
            assertThat(RoleScope.values()).containsExactly(RoleScope.REALM, RoleScope.CLIENT);
        }

        @Test
        @DisplayName("should resolve from name")
        void shouldResolveFromName() {
            assertThat(RoleScope.valueOf("REALM")).isEqualTo(RoleScope.REALM);
            assertThat(RoleScope.valueOf("CLIENT")).isEqualTo(RoleScope.CLIENT);
        }
    }

    @Nested
    @DisplayName("RoleDto")
    class RoleDtoTests {

        @Test
        @DisplayName("should construct with all fields")
        void shouldConstructWithAllFields() {
            var dto = new RoleDto("admin", "Administrator", true, RoleScope.REALM, null);

            assertThat(dto.name()).isEqualTo("admin");
            assertThat(dto.description()).isEqualTo("Administrator");
            assertThat(dto.composite()).isTrue();
            assertThat(dto.scope()).isEqualTo(RoleScope.REALM);
            assertThat(dto.clientId()).isNull();
        }

        @Test
        @DisplayName("should be equal when fields match")
        void shouldBeEqualWhenFieldsMatch() {
            var dto1 = new RoleDto("admin", "desc", true, RoleScope.REALM, null);
            var dto2 = new RoleDto("admin", "desc", true, RoleScope.REALM, null);

            assertThat(dto1).isEqualTo(dto2);
        }
    }

    @Nested
    @DisplayName("UserSearchDto")
    class UserSearchDtoTests {

        @Test
        @DisplayName("should construct with all fields")
        void shouldConstructWithAllFields() {
            var dto = new UserSearchDto("user-abc", "alice", "alice@example.com", true);

            assertThat(dto.id()).isEqualTo("user-abc");
            assertThat(dto.username()).isEqualTo("alice");
            assertThat(dto.email()).isEqualTo("alice@example.com");
            assertThat(dto.enabled()).isTrue();
        }
    }
}
