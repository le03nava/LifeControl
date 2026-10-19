package com.lifecontrol.api.usersadmin.identity.keycloak;

import com.lifecontrol.api.usersadmin.dto.PageResponse;
import com.lifecontrol.api.usersadmin.dto.RoleRequest;
import com.lifecontrol.api.usersadmin.identity.IdentityProvider;
import com.lifecontrol.api.usersadmin.identity.IdentityProviderConflictException;
import com.lifecontrol.api.usersadmin.identity.IdentityProviderConnectionException;
import com.lifecontrol.api.usersadmin.identity.IdentityProviderException;
import com.lifecontrol.api.usersadmin.identity.IdentityProviderNotFoundException;
import com.lifecontrol.api.usersadmin.identity.RoleDto;
import com.lifecontrol.api.usersadmin.identity.RoleScope;
import com.lifecontrol.api.usersadmin.identity.UserSearchDto;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Component;

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
    // User Lifecycle
    // ---------------------------------------------------------------

    @Override
    public String createUser(UserRepresentation user) {
        try (var response = keycloak.realm(realm()).users().create(user)) {
            if (response.getStatus() == 201) {
                var location = response.getLocation();
                if (location == null) {
                    throw new IdentityProviderConnectionException(
                            "Missing Location header creating user: " + user.getUsername());
                }
                var path = location.getPath();
                return path.substring(path.lastIndexOf('/') + 1);
            }
            // The response-returning overload does not throw on a client error, so a 409 arrives here
            // as a status and never reaches the catch below: without this branch the address-taken
            // conflict would be reported as retryable and the linker's catch would never see it.
            if (response.getStatus() == 409) {
                throw new IdentityProviderConflictException("User already exists: " + user.getUsername());
            }
            throw new IdentityProviderConnectionException("Unexpected response creating user: " + response.getStatus());
        } catch (WebApplicationException | ProcessingException e) {
            var message = "Failed to create user: " + user.getUsername();
            throw toDomainFailure(e, message, "User already exists: " + user.getUsername());
        }
    }

    @Override
    public void deleteUser(String userId) {
        try {
            var response = keycloak.realm(realm()).users().delete(userId);
            response.close();
        } catch (WebApplicationException | ProcessingException e) {
            throw toDomainFailure(e, "Failed to delete user: " + userId);
        }
    }

    @Override
    public void updateUser(String userId, UserRepresentation user) {
        try {
            keycloak.realm(realm()).users().get(userId).update(user);
        } catch (WebApplicationException | ProcessingException e) {
            throw toDomainFailure(e, "Failed to update user: " + userId);
        }
    }

    @Override
    public void disableUser(String userId) {
        try {
            var user = keycloak.realm(realm()).users().get(userId);
            var userRep = user.toRepresentation();
            userRep.setEnabled(false);
            user.update(userRep);
        } catch (WebApplicationException | ProcessingException e) {
            throw toDomainFailure(e, "Failed to disable user: " + userId);
        }
    }

    @Override
    public void enableUser(String userId) {
        try {
            var user = keycloak.realm(realm()).users().get(userId);
            var userRep = user.toRepresentation();
            userRep.setEnabled(true);
            user.update(userRep);
        } catch (WebApplicationException | ProcessingException e) {
            throw toDomainFailure(e, "Failed to enable user: " + userId);
        }
    }

    // ---------------------------------------------------------------
    // Realm Role CRUD
    // ---------------------------------------------------------------

    @Override
    public List<RoleDto> listRealmRoles() {
        try {
            return keycloak.realm(realm()).roles().list().stream()
                    .map(r -> toRoleDto(r, RoleScope.REALM, null))
                    .toList();
        } catch (WebApplicationException | ProcessingException e) {
            throw toDomainFailure(e, "Failed to list realm roles");
        }
    }

    @Override
    public RoleDto createRealmRole(RoleRequest request) {
        try {
            var rep = toRepresentation(request);
            keycloak.realm(realm()).roles().create(rep);
            return getRealmRole(request.name());
        } catch (WebApplicationException | ProcessingException e) {
            var message = "Failed to create realm role: " + request.name();
            throw toDomainFailure(e, message, "Realm role already exists: " + request.name());
        }
    }

    @Override
    public RoleDto getRealmRole(String name) {
        try {
            var rep = keycloak.realm(realm()).roles().get(name).toRepresentation();
            return toRoleDto(rep, RoleScope.REALM, null);
        } catch (WebApplicationException | ProcessingException e) {
            throw toDomainFailure(e, "Failed to get realm role: " + name);
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
        } catch (WebApplicationException | ProcessingException e) {
            throw toDomainFailure(e, "Failed to update realm role: " + name);
        }
    }

    @Override
    public void deleteRealmRole(String name) {
        try {
            keycloak.realm(realm()).roles().deleteRole(name);
        } catch (WebApplicationException | ProcessingException e) {
            var message = "Failed to delete realm role: " + name;
            throw toDomainFailure(e, message, "Cannot delete realm role: " + name + " (has user assignments)");
        }
    }

    // ---------------------------------------------------------------
    // Client Role CRUD
    // ---------------------------------------------------------------

    @Override
    public List<RoleDto> listClientRoles(String clientId) {
        try {
            var clientUuid = resolveClientUuid(clientId);
            return keycloak.realm(realm()).clients().get(clientUuid).roles().list().stream()
                    .map(r -> toRoleDto(r, RoleScope.CLIENT, clientId))
                    .toList();
        } catch (WebApplicationException | ProcessingException e) {
            throw toDomainFailure(e, "Failed to list client roles for: " + clientId);
        }
    }

    @Override
    public RoleDto createClientRole(String clientId, RoleRequest request) {
        try {
            var clientUuid = resolveClientUuid(clientId);
            var rep = toRepresentation(request);
            keycloak.realm(realm()).clients().get(clientUuid).roles().create(rep);
            return getClientRoleByName(clientUuid, request.name(), clientId);
        } catch (WebApplicationException | ProcessingException e) {
            var message = "Failed to create client role: " + request.name();
            var conflictMessage = "Client role already exists: " + request.name() + " for client " + clientId;
            throw toDomainFailure(e, message, conflictMessage);
        }
    }

    @Override
    public void deleteClientRole(String clientId, String roleName) {
        try {
            var clientUuid = resolveClientUuid(clientId);
            var roleResource =
                    keycloak.realm(realm()).clients().get(clientUuid).roles().get(roleName);
            roleResource.toRepresentation(); // verify exists
            roleResource.remove();
        } catch (WebApplicationException | ProcessingException e) {
            var message = "Failed to delete client role: " + roleName;
            var conflictMessage = "Cannot delete client role: " + roleName + " (has assignments or is composite)";
            throw toDomainFailure(e, message, conflictMessage);
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
        } catch (WebApplicationException | ProcessingException e) {
            throw toDomainFailure(e, "Failed to add child role: " + childRole + " to " + parentRole);
        }
    }

    @Override
    public void removeChildRole(String parentRole, String childRole, RoleScope scope, String clientId) {
        try {
            var parentResource = keycloak.realm(realm()).roles().get(parentRole);
            parentResource.toRepresentation(); // verify exists
            var childRep = findChildRoleRepresentation(childRole, scope, clientId);
            parentResource.deleteComposites(List.of(childRep));
        } catch (WebApplicationException | ProcessingException e) {
            throw toDomainFailure(e, "Failed to remove child role: " + childRole + " from " + parentRole);
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
        } catch (WebApplicationException | ProcessingException e) {
            throw toDomainFailure(e, "Failed to assign role " + roleName + " to user " + userId);
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
        } catch (WebApplicationException | ProcessingException e) {
            throw toDomainFailure(e, "Failed to remove role " + roleName + " from user " + userId);
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
        } catch (WebApplicationException | ProcessingException e) {
            throw toDomainFailure(e, "Failed to get roles for user: " + userId);
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
        } catch (WebApplicationException | ProcessingException e) {
            throw toDomainFailure(e, "Failed to get client roles for user: " + userId);
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
        } catch (WebApplicationException | ProcessingException e) {
            throw toDomainFailure(e, "Failed to get attributes for user: " + userId);
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
        } catch (WebApplicationException | ProcessingException e) {
            throw toDomainFailure(e, "Failed to update attribute " + key + " for user: " + userId);
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
        } catch (WebApplicationException | ProcessingException e) {
            throw toDomainFailure(e, "Failed to delete attribute " + key + " for user: " + userId);
        }
    }

    // ---------------------------------------------------------------
    // Actions Email
    // ---------------------------------------------------------------

    @Override
    public void sendActionsEmail(
            String userId, String clientId, String redirectUri, Integer lifespan, List<String> actions) {
        try {
            var user = keycloak.realm(realm()).users().get(userId);
            user.toRepresentation(); // verify exists
            user.executeActionsEmail(clientId, redirectUri, lifespan, actions);
        } catch (WebApplicationException | ProcessingException e) {
            throw toDomainFailure(e, "Failed to send actions email to user: " + userId);
        }
    }

    // ---------------------------------------------------------------
    // User Search
    // ---------------------------------------------------------------

    @Override
    public PageResponse<UserSearchDto> searchUsers(String query, int page, int size) {
        try {
            // Keycloak performs the pagination server-side (firstResult / maxResults),
            // so only the requested page is loaded and counted locally.
            var users = keycloak.realm(realm()).users().search(query, page * size, size);
            var total = keycloak.realm(realm()).users().count(query);

            var content = users.stream().map(this::toUserSearchDto).toList();

            return new PageResponse<>(content, page, size, total != null ? total : 0);
        } catch (WebApplicationException | ProcessingException e) {
            throw toDomainFailure(e, "Failed to search users with query: " + query);
        }
    }

    @Override
    public List<UserSearchDto> findUsersByEmail(String email) {
        try {
            return keycloak.realm(realm()).users().searchByEmail(email, Boolean.TRUE).stream()
                    .map(this::toUserSearchDto)
                    .toList();
        } catch (WebApplicationException | ProcessingException e) {
            throw toDomainFailure(e, "Failed to find users by email: " + email);
        }
    }

    @Override
    public Optional<String> findUserDisplayName(String userId) {
        try {
            return Optional.ofNullable(
                    displayNameOf(keycloak.realm(realm()).users().get(userId).toRepresentation()));
        } catch (NotFoundException e) {
            return Optional.empty();
        } catch (WebApplicationException | ProcessingException e) {
            throw toDomainFailure(e, "Failed to find user by id: " + userId);
        }
    }

    /**
     * The display name one representation carries: the full name when either half is present, and the
     * username otherwise. A user with neither answers {@code null}, which the caller reads as "no name
     * to show" rather than as an empty string — the read surface renders the actor id instead (record
     * T93, gap G42).
     */
    private static String displayNameOf(UserRepresentation user) {
        var first = user.getFirstName();
        var last = user.getLastName();
        var full = Stream.of(first, last)
                .filter(part -> part != null && !part.isBlank())
                .map(String::trim)
                .collect(Collectors.joining(" "));
        if (!full.isBlank()) {
            return full;
        }
        var username = user.getUsername();
        return username == null || username.isBlank() ? null : username;
    }

    // ---------------------------------------------------------------
    // Groups
    // ---------------------------------------------------------------

    @Override
    public void createGroup(String name, Map<String, List<String>> attributes, Optional<String> parentId) {
        try {
            var groupRep = new GroupRepresentation();
            groupRep.setName(name);
            groupRep.setAttributes(new HashMap<>(attributes));

            if (parentId.isPresent()) {
                keycloak.realm(realm()).groups().group(parentId.get()).subGroup(groupRep);
            } else {
                keycloak.realm(realm()).groups().add(groupRep);
            }
        } catch (WebApplicationException | ProcessingException e) {
            var message = "Failed to create group: " + name;
            throw toDomainFailure(e, message, "Group already exists: " + name);
        }
    }

    @Override
    public boolean companyGroupExists(String groupName) {
        try {
            return keycloak.realm(realm()).groups().groups().stream().anyMatch(g -> groupName.equals(g.getName()));
        } catch (WebApplicationException | ProcessingException e) {
            throw toDomainFailure(e, "Failed to check if group exists: " + groupName);
        }
    }

    @Override
    public Optional<String> findGroupIdByName(String name) {
        try {
            // Search returns top-level groups whose name or path matches
            var candidates = keycloak.realm(realm()).groups().groups(name, 0, Integer.MAX_VALUE);
            for (var group : candidates) {
                var found = findInTree(group, name);
                if (found.isPresent()) return found;
            }
            return Optional.empty();
        } catch (WebApplicationException | ProcessingException e) {
            throw toDomainFailure(e, "Failed to search group: " + name);
        }
    }

    private Optional<String> findInTree(GroupRepresentation group, String targetName) {
        if (targetName.equals(group.getName())) {
            return Optional.of(group.getId());
        }
        // Search sub-groups — Keycloak 26+ search only returns top-level matches
        var subGroups = keycloak.realm(realm()).groups().group(group.getId()).getSubGroups(0, Integer.MAX_VALUE, false);
        for (var sub : subGroups) {
            var found = findInTree(sub, targetName);
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------
    // Error translation
    // ---------------------------------------------------------------

    /**
     * Translates the identity provider's answer into the port's sealed vocabulary. A missing resource
     * is a domain 404, a 409 is a conflict, and every other provider answer — any other client error,
     * the 5xx family, a redirect, or a transport failure — is retryable and becomes a domain 503. The
     * message is the operation's own failure description; the outcome is carried by the type, never by
     * prose.
     */
    private IdentityProviderException toDomainFailure(RuntimeException failure, String message) {
        return toDomainFailure(failure, message, message);
    }

    private IdentityProviderException toDomainFailure(
            RuntimeException failure, String message, String conflictMessage) {
        if (failure instanceof NotFoundException) {
            return new IdentityProviderNotFoundException(message, failure);
        }
        if (failure instanceof ClientErrorException clientError
                && clientError.getResponse().getStatus() == 409) {
            return new IdentityProviderConflictException(conflictMessage, failure);
        }
        return new IdentityProviderConnectionException(message, failure);
    }

    // ---------------------------------------------------------------
    // Mapping helpers
    // ---------------------------------------------------------------

    private RoleDto toRoleDto(RoleRepresentation rep, RoleScope scope, String clientId) {
        return new RoleDto(rep.getName(), rep.getDescription(), rep.isComposite(), scope, clientId);
    }

    private RoleRepresentation toRepresentation(RoleRequest request) {
        var rep = new RoleRepresentation();
        rep.setName(request.name());
        rep.setDescription(request.description());
        rep.setComposite(request.composite() != null ? request.composite() : false);
        return rep;
    }

    private UserSearchDto toUserSearchDto(UserRepresentation rep) {
        return new UserSearchDto(rep.getId(), rep.getUsername(), rep.getEmail(), rep.isEnabled());
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
        return keycloak.realm(realm())
                .clients()
                .get(clientUuid)
                .roles()
                .get(roleName)
                .toRepresentation();
    }

    private RoleRepresentation findChildRoleRepresentation(String childRole, RoleScope scope, String clientId) {
        return findRoleRepresentation(childRole, scope, clientId);
    }

    private RoleDto getClientRoleByName(String clientUuid, String roleName, String clientId) {
        var rep = keycloak.realm(realm())
                .clients()
                .get(clientUuid)
                .roles()
                .get(roleName)
                .toRepresentation();
        return toRoleDto(rep, RoleScope.CLIENT, clientId);
    }
}
