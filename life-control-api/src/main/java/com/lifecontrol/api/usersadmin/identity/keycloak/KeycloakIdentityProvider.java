package com.lifecontrol.api.usersadmin.identity.keycloak;

import com.lifecontrol.api.usersadmin.dto.PageResponse;
import com.lifecontrol.api.usersadmin.dto.RoleRequest;
import com.lifecontrol.api.usersadmin.identity.IdentityProvider;
import com.lifecontrol.api.usersadmin.identity.IdentityProviderConflictException;
import com.lifecontrol.api.usersadmin.identity.IdentityProviderConnectionException;
import com.lifecontrol.api.usersadmin.identity.IdentityProviderNotFoundException;
import com.lifecontrol.api.usersadmin.identity.RoleDto;
import com.lifecontrol.api.usersadmin.identity.RoleScope;
import com.lifecontrol.api.usersadmin.identity.UserSearchDto;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.Response;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Keycloak-specific adapter implementing {@link IdentityProvider}.
 * Delegates all operations to the Keycloak Admin Client, mapping
 * representations and exceptions to the domain model.
 */
@Component
public class KeycloakIdentityProvider implements IdentityProvider {

    private final Keycloak keycloak;
    private final KeycloakAdminProperties properties;

    public KeycloakIdentityProvider(Keycloak keycloak, KeycloakAdminProperties properties) {
        this.keycloak = keycloak;
        this.properties = properties;
    }

    private String realm() {
        return properties.realm();
    }

    // ---------------------------------------------------------------
    // Realm Role CRUD
    // ---------------------------------------------------------------

    @Override
    public List<RoleDto> listRealmRoles() {
        try {
            return keycloak.realm(realm()).roles().list()
                    .stream()
                    .map(r -> toRoleDto(r, RoleScope.REALM, null))
                    .toList();
        } catch (ProcessingException e) {
            throw new IdentityProviderConnectionException("Failed to list realm roles", e);
        }
    }

    @Override
    public RoleDto createRealmRole(RoleRequest request) {
        try {
            var rep = toRepresentation(request);
            keycloak.realm(realm()).roles().create(rep);
            return getRealmRole(request.name());
        } catch (ClientErrorException e) {
            if (e.getResponse().getStatus() == 409) {
                throw new IdentityProviderConflictException(
                        "Realm role already exists: " + request.name(), e);
            }
            throw new IdentityProviderConnectionException(
                    "Failed to create realm role: " + request.name(), e);
        } catch (ProcessingException e) {
            throw new IdentityProviderConnectionException(
                    "Failed to create realm role: " + request.name(), e);
        }
    }

    @Override
    public RoleDto getRealmRole(String name) {
        try {
            var rep = keycloak.realm(realm()).roles().get(name).toRepresentation();
            return toRoleDto(rep, RoleScope.REALM, null);
        } catch (NotFoundException e) {
            throw new IdentityProviderNotFoundException("Realm role not found: " + name, e);
        } catch (ProcessingException e) {
            throw new IdentityProviderConnectionException(
                    "Failed to get realm role: " + name, e);
        }
    }

    @Override
    public RoleDto updateRealmRole(String name, RoleRequest request) {
        try {
            var roleResource = keycloak.realm(realm()).roles().get(name);
            var rep = roleResource.toRepresentation();
            rep.setDescription(request.description());
            roleResource.update(rep);
            return toRoleDto(roleResource.toRepresentation(), RoleScope.REALM, null);
        } catch (NotFoundException e) {
            throw new IdentityProviderNotFoundException("Realm role not found: " + name, e);
        } catch (ProcessingException e) {
            throw new IdentityProviderConnectionException(
                    "Failed to update realm role: " + name, e);
        }
    }

    @Override
    public void deleteRealmRole(String name) {
        try {
            keycloak.realm(realm()).roles().deleteRole(name);
        } catch (NotFoundException e) {
            throw new IdentityProviderNotFoundException("Realm role not found: " + name, e);
        } catch (ClientErrorException e) {
            if (e.getResponse().getStatus() == 409) {
                throw new IdentityProviderConflictException(
                        "Cannot delete realm role: " + name + " (has user assignments)", e);
            }
            throw new IdentityProviderConnectionException(
                    "Failed to delete realm role: " + name, e);
        } catch (ProcessingException e) {
            throw new IdentityProviderConnectionException(
                    "Failed to delete realm role: " + name, e);
        }
    }

    // ---------------------------------------------------------------
    // Client Role CRUD
    // ---------------------------------------------------------------

    @Override
    public List<RoleDto> listClientRoles(String clientId) {
        try {
            var clientUuid = resolveClientUuid(clientId);
            return keycloak.realm(realm()).clients().get(clientUuid).roles().list()
                    .stream()
                    .map(r -> toRoleDto(r, RoleScope.CLIENT, clientId))
                    .toList();
        } catch (NotFoundException e) {
            throw new IdentityProviderNotFoundException("Client not found: " + clientId, e);
        } catch (ProcessingException e) {
            throw new IdentityProviderConnectionException(
                    "Failed to list client roles for: " + clientId, e);
        }
    }

    @Override
    public RoleDto createClientRole(String clientId, RoleRequest request) {
        try {
            var clientUuid = resolveClientUuid(clientId);
            var rep = toRepresentation(request);
            keycloak.realm(realm()).clients().get(clientUuid).roles().create(rep);
            return getClientRoleByName(clientUuid, request.name(), clientId);
        } catch (NotFoundException e) {
            throw new IdentityProviderNotFoundException("Client not found: " + clientId, e);
        } catch (ClientErrorException e) {
            if (e.getResponse().getStatus() == 409) {
                throw new IdentityProviderConflictException(
                        "Client role already exists: " + request.name() + " for client " + clientId, e);
            }
            throw new IdentityProviderConnectionException(
                    "Failed to create client role: " + request.name(), e);
        } catch (ProcessingException e) {
            throw new IdentityProviderConnectionException(
                    "Failed to create client role: " + request.name(), e);
        }
    }

    @Override
    public void deleteClientRole(String clientId, String roleName) {
        try {
            var clientUuid = resolveClientUuid(clientId);
            var roleResource = keycloak.realm(realm()).clients().get(clientUuid).roles().get(roleName);
            roleResource.toRepresentation(); // verify exists
            roleResource.remove();
        } catch (NotFoundException e) {
            throw new IdentityProviderNotFoundException(
                    "Client role not found: " + roleName + " for client " + clientId, e);
        } catch (ClientErrorException e) {
            if (e.getResponse().getStatus() == 409) {
                throw new IdentityProviderConflictException(
                        "Cannot delete client role: " + roleName + " (has assignments or is composite)", e);
            }
            throw new IdentityProviderConnectionException(
                    "Failed to delete client role: " + roleName, e);
        } catch (ProcessingException e) {
            throw new IdentityProviderConnectionException(
                    "Failed to delete client role: " + roleName, e);
        }
    }

    // ---------------------------------------------------------------
    // Composite Roles
    // ---------------------------------------------------------------

    @Override
    public void addChildRole(String parentRole, String childRole, RoleScope scope, String clientId) {
        try {
            var parentResource = keycloak.realm(realm()).roles().get(parentRole);
            parentResource.toRepresentation(); // verify exists
            var childRep = findChildRoleRepresentation(childRole, scope, clientId);
            parentResource.addComposites(List.of(childRep));
        } catch (NotFoundException e) {
            throw new IdentityProviderNotFoundException(
                    "Parent or child role not found: parent=" + parentRole + ", child=" + childRole, e);
        } catch (ProcessingException e) {
            throw new IdentityProviderConnectionException(
                    "Failed to add child role: " + childRole + " to " + parentRole, e);
        }
    }

    @Override
    public void removeChildRole(String parentRole, String childRole, RoleScope scope, String clientId) {
        try {
            var parentResource = keycloak.realm(realm()).roles().get(parentRole);
            parentResource.toRepresentation(); // verify exists
            var childRep = findChildRoleRepresentation(childRole, scope, clientId);
            parentResource.deleteComposites(List.of(childRep));
        } catch (NotFoundException e) {
            throw new IdentityProviderNotFoundException(
                    "Parent or child role not found: parent=" + parentRole + ", child=" + childRole, e);
        } catch (ProcessingException e) {
            throw new IdentityProviderConnectionException(
                    "Failed to remove child role: " + childRole + " from " + parentRole, e);
        }
    }

    // ---------------------------------------------------------------
    // User Role Assignment
    // ---------------------------------------------------------------

    @Override
    public void assignRoleToUser(String userId, String roleName, RoleScope scope, String clientId) {
        try {
            var user = keycloak.realm(realm()).users().get(userId);
            user.toRepresentation(); // verify exists
            var roleRep = findRoleRepresentation(roleName, scope, clientId);

            if (scope == RoleScope.REALM) {
                user.roles().realmLevel().add(List.of(roleRep));
            } else {
                var clientUuid = resolveClientUuid(clientId);
                user.roles().clientLevel(clientUuid).add(List.of(roleRep));
            }
        } catch (NotFoundException e) {
            throw new IdentityProviderNotFoundException(
                    "User or role not found: user=" + userId + ", role=" + roleName, e);
        } catch (ProcessingException e) {
            throw new IdentityProviderConnectionException(
                    "Failed to assign role " + roleName + " to user " + userId, e);
        }
    }

    @Override
    public void removeRoleFromUser(String userId, String roleName, RoleScope scope, String clientId) {
        try {
            var user = keycloak.realm(realm()).users().get(userId);
            var roleRep = findRoleRepresentation(roleName, scope, clientId);

            if (scope == RoleScope.REALM) {
                user.roles().realmLevel().remove(List.of(roleRep));
            } else {
                var clientUuid = resolveClientUuid(clientId);
                user.roles().clientLevel(clientUuid).remove(List.of(roleRep));
            }
        } catch (NotFoundException e) {
            throw new IdentityProviderNotFoundException(
                    "User or role not found: user=" + userId + ", role=" + roleName, e);
        } catch (ProcessingException e) {
            throw new IdentityProviderConnectionException(
                    "Failed to remove role " + roleName + " from user " + userId, e);
        }
    }

    @Override
    public List<RoleDto> getUserRoles(String userId) {
        try {
            var user = keycloak.realm(realm()).users().get(userId);
            user.toRepresentation(); // verify exists

            var realmRoles = user.roles().realmLevel().listAll();
            var result = new java.util.ArrayList<>(realmRoles.stream()
                    .map(r -> toRoleDto(r, RoleScope.REALM, null))
                    .toList());

            return Collections.unmodifiableList(result);
        } catch (NotFoundException e) {
            throw new IdentityProviderNotFoundException("User not found: " + userId, e);
        } catch (ProcessingException e) {
            throw new IdentityProviderConnectionException(
                    "Failed to get roles for user: " + userId, e);
        }
    }

    @Override
    public List<RoleDto> getUserRoles(String userId, String clientId) {
        try {
            var user = keycloak.realm(realm()).users().get(userId);
            user.toRepresentation(); // verify exists
            var clientUuid = resolveClientUuid(clientId);

            var clientRoles = user.roles().clientLevel(clientUuid).listAll();
            return clientRoles.stream()
                    .map(r -> toRoleDto(r, RoleScope.CLIENT, clientId))
                    .toList();
        } catch (NotFoundException e) {
            throw new IdentityProviderNotFoundException(
                    "User or client not found: user=" + userId + ", client=" + clientId, e);
        } catch (ProcessingException e) {
            throw new IdentityProviderConnectionException(
                    "Failed to get client roles for user: " + userId, e);
        }
    }

    // ---------------------------------------------------------------
    // User Attributes
    // ---------------------------------------------------------------

    @Override
    public Map<String, List<String>> getUserAttributes(String userId) {
        try {
            var userRep = keycloak.realm(realm()).users().get(userId).toRepresentation();
            var attrs = userRep.getAttributes();
            return attrs != null ? Collections.unmodifiableMap(attrs) : Map.of();
        } catch (NotFoundException e) {
            throw new IdentityProviderNotFoundException("User not found: " + userId, e);
        } catch (ProcessingException e) {
            throw new IdentityProviderConnectionException(
                    "Failed to get attributes for user: " + userId, e);
        }
    }

    @Override
    public void updateUserAttribute(String userId, String key, List<String> values) {
        try {
            var user = keycloak.realm(realm()).users().get(userId);
            var userRep = user.toRepresentation();
            var attrs = userRep.getAttributes();
            if (attrs == null) {
                attrs = new HashMap<>();
                userRep.setAttributes(attrs);
            }
            attrs.put(key, values);
            user.update(userRep);
        } catch (NotFoundException e) {
            throw new IdentityProviderNotFoundException("User not found: " + userId, e);
        } catch (ProcessingException e) {
            throw new IdentityProviderConnectionException(
                    "Failed to update attribute " + key + " for user: " + userId, e);
        }
    }

    @Override
    public void deleteUserAttribute(String userId, String key) {
        try {
            var user = keycloak.realm(realm()).users().get(userId);
            var userRep = user.toRepresentation();
            var attrs = userRep.getAttributes();
            if (attrs != null) {
                attrs.remove(key);
                user.update(userRep);
            }
        } catch (NotFoundException e) {
            throw new IdentityProviderNotFoundException("User not found: " + userId, e);
        } catch (ProcessingException e) {
            throw new IdentityProviderConnectionException(
                    "Failed to delete attribute " + key + " for user: " + userId, e);
        }
    }

    // ---------------------------------------------------------------
    // User Search
    // ---------------------------------------------------------------

    @Override
    public PageResponse<UserSearchDto> searchUsers(String query, int page, int size) {
        try {
            var allUsers = keycloak.realm(realm()).users().search(query, 0, 5000);
            var total = allUsers.size();

            var start = page * size;
            if (start >= total) {
                return new PageResponse<>(List.of(), page, size, total);
            }

            var end = Math.min(start + size, total);
            var content = allUsers.subList(start, end).stream()
                    .map(this::toUserSearchDto)
                    .toList();

            return new PageResponse<>(content, page, size, total);
        } catch (ProcessingException e) {
            throw new IdentityProviderConnectionException(
                    "Failed to search users with query: " + query, e);
        }
    }

    // ---------------------------------------------------------------
    // Groups
    // ---------------------------------------------------------------

    @Override
    public void createGroupWithRole(String groupName, Map<String, List<String>> attributes,
                                    String roleName, String clientId) {
        try {
            var groupRep = new GroupRepresentation();
            groupRep.setName(groupName);
            groupRep.setAttributes(new HashMap<>(attributes));

            var response = keycloak.realm(realm()).groups().add(groupRep);
            var groupId = resolveGroupIdFromResponse(response, groupName);

            var clientUuid = resolveClientUuid(clientId);
            var roleRep = keycloak.realm(realm()).clients().get(clientUuid)
                    .roles().get(roleName).toRepresentation();
            keycloak.realm(realm()).groups().group(groupId)
                    .roles().clientLevel(clientUuid).add(List.of(roleRep));
        } catch (NotFoundException e) {
            throw new IdentityProviderNotFoundException(
                    "Role '%s' not found for client '%s'".formatted(roleName, clientId), e);
        } catch (ClientErrorException e) {
            if (e.getResponse().getStatus() == 409) {
                throw new IdentityProviderConflictException(
                        "Group already exists: " + groupName, e);
            }
            throw new IdentityProviderConnectionException(
                    "Failed to create group: " + groupName, e);
        } catch (ProcessingException e) {
            throw new IdentityProviderConnectionException(
                    "Failed to create group: " + groupName, e);
        }
    }

    @Override
    public boolean companyGroupExists(String groupName) {
        try {
            return keycloak.realm(realm()).groups().groups()
                    .stream()
                    .anyMatch(g -> groupName.equals(g.getName()));
        } catch (ProcessingException e) {
            throw new IdentityProviderConnectionException(
                    "Failed to check if group exists: " + groupName, e);
        }
    }

    @Override
    public Optional<String> findGroupIdByName(String name) {
        try {
            return keycloak.realm(realm()).groups().groups(name, 0, Integer.MAX_VALUE)
                    .stream()
                    .filter(g -> name.equals(g.getName()))
                    .map(GroupRepresentation::getId)
                    .findFirst();
        } catch (ProcessingException e) {
            throw new IdentityProviderConnectionException(
                    "Failed to search group: " + name, e);
        }
    }

    @Override
    public void createGroupWithRole(String groupName, Map<String, List<String>> attributes,
                                    String roleName, String clientId, String parentGroupId) {
        try {
            var groupRep = new GroupRepresentation();
            groupRep.setName(groupName);
            groupRep.setAttributes(new HashMap<>(attributes));

            Response response;
            if (parentGroupId != null) {
                // Keycloak 26+: use subGroup() for child groups — groups().add() ignores parentId
                response = keycloak.realm(realm()).groups().group(parentGroupId).subGroup(groupRep);
            } else {
                response = keycloak.realm(realm()).groups().add(groupRep);
            }
            var groupId = resolveGroupIdFromResponse(response, groupName);

            var clientUuid = resolveClientUuid(clientId);
            var roleRep = keycloak.realm(realm()).clients().get(clientUuid)
                    .roles().get(roleName).toRepresentation();
            keycloak.realm(realm()).groups().group(groupId)
                    .roles().clientLevel(clientUuid).add(List.of(roleRep));
        } catch (NotFoundException e) {
            throw new IdentityProviderNotFoundException(
                    "Role '%s' not found for client '%s'".formatted(roleName, clientId), e);
        } catch (ClientErrorException e) {
            if (e.getResponse().getStatus() == 409) {
                throw new IdentityProviderConflictException(
                        "Group already exists: " + groupName, e);
            }
            throw new IdentityProviderConnectionException(
                    "Failed to create group: " + groupName, e);
        } catch (ProcessingException e) {
            throw new IdentityProviderConnectionException(
                    "Failed to create group: " + groupName, e);
        }
    }

    private String resolveGroupIdFromResponse(Response response, String groupName) {
        try (response) {
            var location = response.getLocation();
            if (location != null) {
                return location.getPath().substring(location.getPath().lastIndexOf('/') + 1);
            }
            // Keycloak 26+ may omit Location header for child groups
            return findGroupIdByName(groupName)
                    .orElseThrow(() -> new IdentityProviderConnectionException(
                            "Failed to create group '" + groupName
                                    + "': no Location header and group not found by name"));
        }
    }

    // ---------------------------------------------------------------
    // Mapping helpers
    // ---------------------------------------------------------------

    private RoleDto toRoleDto(RoleRepresentation rep, RoleScope scope, String clientId) {
        return new RoleDto(
                rep.getName(),
                rep.getDescription(),
                rep.isComposite(),
                scope,
                clientId
        );
    }

    private RoleRepresentation toRepresentation(RoleRequest request) {
        var rep = new RoleRepresentation();
        rep.setName(request.name());
        rep.setDescription(request.description());
        rep.setComposite(request.composite() != null ? request.composite() : false);
        return rep;
    }

    private UserSearchDto toUserSearchDto(UserRepresentation rep) {
        return new UserSearchDto(
                rep.getId(),
                rep.getUsername(),
                rep.getEmail(),
                rep.isEnabled()
        );
    }

    // ---------------------------------------------------------------
    // Keycloak-specific helpers
    // ---------------------------------------------------------------

    private String resolveClientUuid(String clientId) {
        var clients = keycloak.realm(realm()).clients().findByClientId(clientId);
        if (clients.isEmpty()) {
            throw new IdentityProviderNotFoundException("Client not found: " + clientId);
        }
        return clients.getFirst().getId();
    }

    private RoleRepresentation findRoleRepresentation(String roleName, RoleScope scope, String clientId) {
        if (scope == RoleScope.REALM) {
            return keycloak.realm(realm()).roles().get(roleName).toRepresentation();
        }
        var clientUuid = resolveClientUuid(clientId);
        return keycloak.realm(realm()).clients().get(clientUuid).roles().get(roleName).toRepresentation();
    }

    private RoleRepresentation findChildRoleRepresentation(String childRole, RoleScope scope, String clientId) {
        return findRoleRepresentation(childRole, scope, clientId);
    }

    private RoleDto getClientRoleByName(String clientUuid, String roleName, String clientId) {
        var rep = keycloak.realm(realm()).clients().get(clientUuid).roles().get(roleName).toRepresentation();
        return toRoleDto(rep, RoleScope.CLIENT, clientId);
    }
}
