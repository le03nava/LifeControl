package com.lifecontrol.api.usersadmin.service;

import com.lifecontrol.api.usersadmin.dto.CreateUserRequest;
import com.lifecontrol.api.usersadmin.dto.CreateUserResponse;
import com.lifecontrol.api.usersadmin.dto.PageResponse;
import com.lifecontrol.api.usersadmin.dto.RoleRequest;
import com.lifecontrol.api.usersadmin.identity.IdentityProvider;
import com.lifecontrol.api.usersadmin.identity.IdentityProviderException;
import com.lifecontrol.api.usersadmin.identity.RoleDto;
import com.lifecontrol.api.usersadmin.identity.RoleScope;
import com.lifecontrol.api.usersadmin.identity.UserSearchDto;
import com.lifecontrol.api.usersadmin.model.UserPreferences;
import com.lifecontrol.api.usersadmin.repository.UserPreferencesRepository;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Application service for user administration operations.
 * Delegates all identity-provider operations to {@link IdentityProvider},
 * keeping the domain free of Keycloak-specific references.
 */
@Service
public class UsersAdminService {

    private static final Logger logger = LoggerFactory.getLogger(UsersAdminService.class);

    private final IdentityProvider identityProvider;
    private final UserPreferencesRepository userPreferencesRepository;

    public UsersAdminService(IdentityProvider identityProvider,
                             UserPreferencesRepository userPreferencesRepository) {
        this.identityProvider = identityProvider;
        this.userPreferencesRepository = userPreferencesRepository;
    }

    // ---------------------------------------------------------------
    // User Creation
    // ---------------------------------------------------------------

    @Transactional
    public CreateUserResponse createUser(CreateUserRequest request) {
        var userRep = new UserRepresentation();
        userRep.setUsername(request.username());
        userRep.setEmail(request.email());
        userRep.setFirstName(request.firstName());
        userRep.setLastName(request.lastName());
        userRep.setEnabled(request.enabled());

        var randomBytes = new byte[16];
        new SecureRandom().nextBytes(randomBytes);
        var password = Base64.getEncoder().encodeToString(randomBytes);

        var credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(password);
        credential.setTemporary(false);
        userRep.setCredentials(List.of(credential));

        String keycloakUserId = null;
        try {
            keycloakUserId = identityProvider.createUser(userRep);

            var prefs = UserPreferences.builder()
                    .keycloakUserId(keycloakUserId)
                    .build();
            userPreferencesRepository.save(prefs);

            return new CreateUserResponse(keycloakUserId);
        } catch (IdentityProviderException e) {
            throw e;
        } catch (Exception e) {
            if (keycloakUserId != null) {
                try {
                    identityProvider.deleteUser(keycloakUserId);
                } catch (Exception ex) {
                    logger.error("Compensation failed: Keycloak user {} not deleted", keycloakUserId, ex);
                }
            }
            throw e;
        }
    }

    // ---------------------------------------------------------------
    // Realm Role CRUD
    // ---------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<RoleDto> listRealmRoles() {
        return identityProvider.listRealmRoles();
    }

    @Transactional
    public RoleDto createRealmRole(RoleRequest request) {
        return identityProvider.createRealmRole(request);
    }

    @Transactional(readOnly = true)
    public RoleDto getRealmRole(String name) {
        return identityProvider.getRealmRole(name);
    }

    @Transactional
    public RoleDto updateRealmRole(String name, RoleRequest request) {
        return identityProvider.updateRealmRole(name, request);
    }

    @Transactional
    public void deleteRealmRole(String name) {
        identityProvider.deleteRealmRole(name);
    }

    // ---------------------------------------------------------------
    // Client Role CRUD
    // ---------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<RoleDto> listClientRoles(String clientId) {
        return identityProvider.listClientRoles(clientId);
    }

    @Transactional
    public RoleDto createClientRole(String clientId, RoleRequest request) {
        return identityProvider.createClientRole(clientId, request);
    }

    @Transactional
    public void deleteClientRole(String clientId, String roleName) {
        identityProvider.deleteClientRole(clientId, roleName);
    }

    // ---------------------------------------------------------------
    // Composite Roles
    // ---------------------------------------------------------------

    @Transactional
    public void addChildRole(String parentRole, String childRole, RoleScope scope, String clientId) {
        identityProvider.addChildRole(parentRole, childRole, scope, clientId);
    }

    @Transactional
    public void removeChildRole(String parentRole, String childRole, RoleScope scope, String clientId) {
        identityProvider.removeChildRole(parentRole, childRole, scope, clientId);
    }

    // ---------------------------------------------------------------
    // User Role Assignment
    // ---------------------------------------------------------------

    @Transactional
    public void assignRoleToUser(String userId, String roleName, RoleScope scope, String clientId) {
        identityProvider.assignRoleToUser(userId, roleName, scope, clientId);
    }

    @Transactional
    public void removeRoleFromUser(String userId, String roleName, RoleScope scope, String clientId) {
        identityProvider.removeRoleFromUser(userId, roleName, scope, clientId);
    }

    @Transactional(readOnly = true)
    public List<RoleDto> getUserRoles(String userId) {
        return identityProvider.getUserRoles(userId);
    }

    @Transactional(readOnly = true)
    public List<RoleDto> getUserRoles(String userId, String clientId) {
        return identityProvider.getUserRoles(userId, clientId);
    }

    // ---------------------------------------------------------------
    // User Attributes
    // ---------------------------------------------------------------

    @Transactional(readOnly = true)
    public Map<String, List<String>> getUserAttributes(String userId) {
        return identityProvider.getUserAttributes(userId);
    }

    @Transactional
    public void updateUserAttribute(String userId, String key, List<String> values) {
        identityProvider.updateUserAttribute(userId, key, values);
    }

    @Transactional
    public void deleteUserAttribute(String userId, String key) {
        identityProvider.deleteUserAttribute(userId, key);
    }

    // ---------------------------------------------------------------
    // User Search
    // ---------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<UserSearchDto> searchUsers(String query, int page, int size) {
        return identityProvider.searchUsers(query, page, size);
    }
}
