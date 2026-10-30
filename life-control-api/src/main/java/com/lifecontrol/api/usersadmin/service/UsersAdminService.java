package com.lifecontrol.api.usersadmin.service;

import com.lifecontrol.api.common.tenant.TenantScope;
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
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public UsersAdminService(IdentityProvider identityProvider, UserPreferencesRepository userPreferencesRepository) {
        this.identityProvider = identityProvider;
        this.userPreferencesRepository = userPreferencesRepository;
    }

    // ---------------------------------------------------------------
    // User Creation
    // ---------------------------------------------------------------

    /**
     * Creates the Keycloak user and the {@code user_preferences} row that marks it as a known subject.
     *
     * <p><b>Why this method is not {@code @Transactional}.</b> The subject is created <b>inside</b> the
     * call, so it does not exist when the transaction boundary is entered: a {@code @Transactional} method
     * would open its transaction first, and the boundary hook would read a scope holder that cannot yet
     * name the user being created. The row's policy compares {@code keycloak_user_id} with
     * {@code app.user_id} (ADR-0005's {@code subject} kind), so that insert would be refused or, worse,
     * silently read back as somebody else's. There is exactly <b>one</b> piece of database work here — a
     * single {@code save} — and the repository opens its own transaction for it, so the annotation bought
     * nothing but the ordering problem it caused. The method therefore creates the Keycloak user first and
     * states the id it received around that one insert; everything before it is an identity-provider call,
     * and the compensation below is unchanged.</p>
     *
     * <p>{@link TenantScope#withSubject(String, java.util.function.Supplier)} is what states it, and it
     * restores whatever the thread carried before: a request's own scope survives, so the caller's
     * remaining transactions are not affected by this one write.</p>
     */
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
            var createdUserId = identityProvider.createUser(userRep);
            keycloakUserId = createdUserId;

            var prefs = UserPreferences.builder().keycloakUserId(createdUserId).build();
            // The one write, under the subject it writes: the row is the person's own, not a tenant's.
            TenantScope.withSubject(createdUserId, () -> userPreferencesRepository.save(prefs));

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
