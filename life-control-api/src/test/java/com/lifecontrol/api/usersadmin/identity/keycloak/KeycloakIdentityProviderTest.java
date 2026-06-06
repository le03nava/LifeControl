package com.lifecontrol.api.usersadmin.identity.keycloak;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.admin.client.resource.ClientsResource;
import org.keycloak.admin.client.resource.GroupResource;
import org.keycloak.admin.client.resource.GroupsResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.RoleMappingResource;
import org.keycloak.admin.client.resource.RoleResource;
import org.keycloak.admin.client.resource.RoleScopeResource;
import org.keycloak.admin.client.resource.RolesResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
    @Mock
    private Response response;
    @Mock
    private ClientsResource clientsResource;
    @Mock
    private ClientResource clientResource;
    @Mock
    private RolesResource rolesResource;
    @Mock
    private RoleResource roleResource;
    @Mock
    private RoleMappingResource roleMappingResource;
    @Mock
    private RoleScopeResource roleScopeResource;

    private KeycloakAdminProperties properties;
    private KeycloakIdentityProvider provider;

    private static final String REALM = "life-control-realm";
    private static final String CLIENT_ID = "life-control-client";
    private static final String CLIENT_UUID = "client-uuid-123";
    private static final String COMPANY_ROLE_NAME = "lc-company";
    private static final String GROUP_NAME = "test-group";
    private static final String GROUP_ID = "group-id-456";

    @BeforeEach
    void setUp() {
        properties = new KeycloakAdminProperties("http://localhost:8080", REALM, CLIENT_ID, "secret");
        provider = new KeycloakIdentityProvider(keycloak, properties);

        lenient().when(keycloak.realm(REALM)).thenReturn(realmResource);
    }

    private void stubClientResolution() {
        var clientRep = new ClientRepresentation();
        clientRep.setId(CLIENT_UUID);
        lenient().when(realmResource.clients()).thenReturn(clientsResource);
        lenient().when(clientsResource.findByClientId(CLIENT_ID)).thenReturn(List.of(clientRep));
        lenient().when(clientsResource.get(CLIENT_UUID)).thenReturn(clientResource);
        lenient().when(clientResource.roles()).thenReturn(rolesResource);
        lenient().when(rolesResource.get(COMPANY_ROLE_NAME)).thenReturn(roleResource);
        lenient().when(roleResource.toRepresentation()).thenReturn(new RoleRepresentation());
    }

    private void stubGroupCreation() throws Exception {
        lenient().when(realmResource.groups()).thenReturn(groupsResource);
        lenient().when(groupsResource.add(any(GroupRepresentation.class))).thenReturn(response);
        lenient().when(response.getLocation()).thenReturn(new URI("/groups/" + GROUP_ID));
        lenient().when(realmResource.groups().group(GROUP_ID)).thenReturn(groupResource);
        lenient().when(groupResource.roles()).thenReturn(roleMappingResource);
        lenient().when(roleMappingResource.clientLevel(CLIENT_UUID)).thenReturn(roleScopeResource);
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
    @DisplayName("createGroupWithRole")
    class CreateGroupWithRoleTests {

        @Test
        @DisplayName("should create group with attributes and assign role")
        void shouldCreateGroupWithAttributesAndAssignRole() throws Exception {
            stubClientResolution();
            stubGroupCreation();

            var attrs = Map.of("company_country_id", List.of("uuid-123"));
            provider.createGroupWithRole(GROUP_NAME, attrs, COMPANY_ROLE_NAME, CLIENT_ID);

            var groupCaptor = ArgumentCaptor.forClass(GroupRepresentation.class);
            verify(groupsResource).add(groupCaptor.capture());
            var captured = groupCaptor.getValue();
            assertThat(captured.getName()).isEqualTo(GROUP_NAME);
            assertThat(captured.getAttributes())
                    .containsEntry("company_country_id", List.of("uuid-123"));

            verify(response).close();
            verify(roleScopeResource).add(anyList());
        }

        @Test
        @DisplayName("should propagate 409 as IdentityProviderConflictException")
        void shouldPropagate409AsConflictException() throws Exception {
            stubClientResolution();
            when(realmResource.groups()).thenReturn(groupsResource);
            var e409 = new jakarta.ws.rs.ClientErrorException(
                    "Conflict", jakarta.ws.rs.core.Response.Status.CONFLICT.getStatusCode());
            when(groupsResource.add(any(GroupRepresentation.class))).thenThrow(e409);

            var attrs = Map.of("attr", List.of("val"));
            org.junit.jupiter.api.Assertions.assertThrows(
                    com.lifecontrol.api.usersadmin.identity.IdentityProviderConflictException.class,
                    () -> provider.createGroupWithRole(GROUP_NAME, attrs, COMPANY_ROLE_NAME, CLIENT_ID));
        }

        @Test
        @DisplayName("should use subGroup() when parentGroupId is provided")
        void shouldSetParentIdWhenProvided() throws Exception {
            stubClientResolution();
            lenient().when(realmResource.groups()).thenReturn(groupsResource);
            lenient().when(groupsResource.group("parent-group-789")).thenReturn(groupResource);
            lenient().when(groupResource.subGroup(any(GroupRepresentation.class))).thenReturn(response);
            lenient().when(response.getLocation()).thenReturn(new URI("/groups/" + GROUP_ID));
            lenient().when(realmResource.groups().group(GROUP_ID)).thenReturn(groupResource);
            lenient().when(groupResource.roles()).thenReturn(roleMappingResource);
            lenient().when(roleMappingResource.clientLevel(CLIENT_UUID)).thenReturn(roleScopeResource);

            var attrs = Map.of("company_country_id", List.of("uuid-123"));
            var parentId = "parent-group-789";
            provider.createGroupWithRole(GROUP_NAME, attrs, COMPANY_ROLE_NAME, CLIENT_ID, parentId);

            var groupCaptor = ArgumentCaptor.forClass(GroupRepresentation.class);
            verify(groupResource).subGroup(groupCaptor.capture());
            var captured = groupCaptor.getValue();
            assertThat(captured.getName()).isEqualTo(GROUP_NAME);
            assertThat(captured.getAttributes())
                    .containsEntry("company_country_id", List.of("uuid-123"));
        }

        @Test
        @DisplayName("should use add() when parentGroupId is null")
        void shouldNotSetParentIdWhenNull() throws Exception {
            stubClientResolution();
            stubGroupCreation();

            var attrs = Map.of("company_country_id", List.of("uuid-123"));
            provider.createGroupWithRole(GROUP_NAME, attrs, COMPANY_ROLE_NAME, CLIENT_ID, null);

            var groupCaptor = ArgumentCaptor.forClass(GroupRepresentation.class);
            verify(groupsResource).add(groupCaptor.capture());
            var captured = groupCaptor.getValue();
            assertThat(captured.getName()).isEqualTo(GROUP_NAME);
            assertThat(captured.getParentId()).isNull();
        }
    }

}
