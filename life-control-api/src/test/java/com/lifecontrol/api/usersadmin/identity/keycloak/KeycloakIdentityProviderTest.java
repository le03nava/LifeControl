package com.lifecontrol.api.usersadmin.identity.keycloak;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.GroupResource;
import org.keycloak.admin.client.resource.GroupsResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.GroupRepresentation;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("KeycloakIdentityProvider Tests")
class KeycloakIdentityProviderTest {

    @Mock
    private Keycloak keycloak;
    @Mock
    private RealmResource realmResource;
    @Mock
    private GroupsResource groupsResource;
    @Mock
    private GroupResource groupResource;

    private KeycloakAdminProperties properties;
    private KeycloakIdentityProvider provider;

    private static final String REALM = "life-control-realm";
    private static final String GROUP_NAME = "test-group";
    private static final String GROUP_ID = "group-id-456";

    @BeforeEach
    void setUp() {
        properties = new KeycloakAdminProperties("http://localhost:8080", REALM, "life-control-client", "secret");
        provider = new KeycloakIdentityProvider(keycloak, properties);

        lenient().when(keycloak.realm(REALM)).thenReturn(realmResource);
    }

    private void stubGroupCreation() throws Exception {
        lenient().when(realmResource.groups()).thenReturn(groupsResource);
    }

    @Nested
    @DisplayName("findGroupIdByName")
    class FindGroupIdByNameTests {

        @Test
        @DisplayName("should return group ID when exact name match is found")
        void shouldReturnGroupIdWhenFound() {
            var groupRep = new GroupRepresentation();
            groupRep.setId(GROUP_ID);
            groupRep.setName(GROUP_NAME);
            when(realmResource.groups()).thenReturn(groupsResource);
            when(groupsResource.groups(GROUP_NAME, 0, Integer.MAX_VALUE))
                    .thenReturn(List.of(groupRep));

            var result = provider.findGroupIdByName(GROUP_NAME);

            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo(GROUP_ID);
        }

        @Test
        @DisplayName("should return empty when no group matches the exact name")
        void shouldReturnEmptyWhenNotFound() {
            var otherGroup = new GroupRepresentation();
            otherGroup.setId("other-id");
            otherGroup.setName("lc-company-other");
            when(realmResource.groups()).thenReturn(groupsResource);
            when(groupsResource.groups(GROUP_NAME, 0, Integer.MAX_VALUE))
                    .thenReturn(List.of(otherGroup));
            // Stub sub-group lookup — findInTree recurses into sub-groups
            when(groupsResource.group("other-id")).thenReturn(groupResource);
            when(groupResource.getSubGroups(0, Integer.MAX_VALUE, false))
                    .thenReturn(List.of());

            var result = provider.findGroupIdByName(GROUP_NAME);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty when search returns empty list")
        void shouldReturnEmptyWhenEmptyList() {
            when(realmResource.groups()).thenReturn(groupsResource);
            when(groupsResource.groups("nonexistent", 0, Integer.MAX_VALUE))
                    .thenReturn(List.of());

            var result = provider.findGroupIdByName("nonexistent");

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("createGroup")
    class CreateGroupTests {

        @Test
        @DisplayName("should create group with attributes and no role assigned")
        void shouldCreateGroupWithAttributesAndNoRole() throws Exception {
            stubGroupCreation();

            var attrs = Map.of("company_country_id", List.of("uuid-123"));
            provider.createGroup(GROUP_NAME, attrs, Optional.empty());

            var groupCaptor = ArgumentCaptor.forClass(GroupRepresentation.class);
            verify(groupsResource).add(groupCaptor.capture());
            var captured = groupCaptor.getValue();
            assertThat(captured.getName()).isEqualTo(GROUP_NAME);
            assertThat(captured.getAttributes())
                    .containsEntry("company_country_id", List.of("uuid-123"));
        }

        @Test
        @DisplayName("should propagate 409 as IdentityProviderConflictException")
        void shouldPropagate409AsConflictException() throws Exception {
            when(realmResource.groups()).thenReturn(groupsResource);
            var e409 = new jakarta.ws.rs.ClientErrorException(
                    "Conflict", jakarta.ws.rs.core.Response.Status.CONFLICT.getStatusCode());
            when(groupsResource.add(any(GroupRepresentation.class))).thenThrow(e409);

            var attrs = Map.of("attr", List.of("val"));
            org.junit.jupiter.api.Assertions.assertThrows(
                    com.lifecontrol.api.usersadmin.identity.IdentityProviderConflictException.class,
                    () -> provider.createGroup(GROUP_NAME, attrs, Optional.empty()));
        }

        @Test
        @DisplayName("should use subGroup() when parentId is provided")
        void shouldSetParentIdWhenProvided() throws Exception {
            lenient().when(realmResource.groups()).thenReturn(groupsResource);
            lenient().when(groupsResource.group("parent-group-789")).thenReturn(groupResource);

            var attrs = Map.of("company_country_id", List.of("uuid-123"));
            provider.createGroup(GROUP_NAME, attrs, Optional.of("parent-group-789"));

            var groupCaptor = ArgumentCaptor.forClass(GroupRepresentation.class);
            verify(groupResource).subGroup(groupCaptor.capture());
            var captured = groupCaptor.getValue();
            assertThat(captured.getName()).isEqualTo(GROUP_NAME);
            assertThat(captured.getAttributes())
                    .containsEntry("company_country_id", List.of("uuid-123"));
        }

        @Test
        @DisplayName("should use add() when parentId is not present")
        void shouldNotSetParentIdWhenEmpty() throws Exception {
            stubGroupCreation();

            var attrs = Map.of("company_country_id", List.of("uuid-123"));
            provider.createGroup(GROUP_NAME, attrs, Optional.empty());

            var groupCaptor = ArgumentCaptor.forClass(GroupRepresentation.class);
            verify(groupsResource).add(groupCaptor.capture());
            var captured = groupCaptor.getValue();
            assertThat(captured.getName()).isEqualTo(GROUP_NAME);
            assertThat(captured.getParentId()).isNull();
        }
    }

}
