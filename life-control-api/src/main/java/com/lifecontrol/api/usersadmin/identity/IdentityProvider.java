package com.lifecontrol.api.usersadmin.identity;

import com.lifecontrol.api.usersadmin.dto.PageResponse;
import com.lifecontrol.api.usersadmin.dto.RoleRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.keycloak.representations.idm.UserRepresentation;

/**
 * Abstraction for identity provider operations (role CRUD, user assignment,
 * attributes, search). Decouples the domain from any specific IdP implementation.
 */
public interface IdentityProvider {

    // --- User Lifecycle ---

    /**
     * Creates a user in the identity provider.
     *
     * @param user the user representation to create
     * @return the newly created user's ID
     */
    String createUser(UserRepresentation user);

    /**
     * Deletes a user from the identity provider.
     *
     * @param userId the identity provider user ID
     */
    void deleteUser(String userId);

    /**
     * Updates a user's representation in the identity provider.
     *
     * @param userId the identity provider user ID
     * @param user   the updated user representation
     */
    void updateUser(String userId, UserRepresentation user);

    /**
     * Disables a user's account so it can no longer authenticate.
     *
     * <p>This is the capability revocation ({@code DEACTIVATE}, decision O4 in
     * {@code odd/tasks/employee-access-provisioning.md}) needs to turn an account off, and it is
     * gap G19 of that record: before it, the port could only create, delete or replace the whole
     * representation. It is deliberately <b>not</b> {@link #updateUser(String, UserRepresentation)}:
     * that method forwards the caller's entire representation (record T77), so any field the
     * caller does not carry is silently lost. This method performs a read-modify-write instead,
     * flipping only {@code enabled} and leaving every other field of the account intact.</p>
     *
     * <p>The write is unconditional and idempotent by value: an account that is already disabled is
     * still updated with {@code enabled = false}, and no extra state is kept to decide whether to
     * skip it.</p>
     *
     * @param userId the identity provider user ID
     */
    void disableUser(String userId);

    // --- Realm Role CRUD ---

    List<RoleDto> listRealmRoles();

    RoleDto createRealmRole(RoleRequest request);

    RoleDto getRealmRole(String name);

    RoleDto updateRealmRole(String name, RoleRequest request);

    void deleteRealmRole(String name);

    // --- Client Role CRUD ---

    List<RoleDto> listClientRoles(String clientId);

    RoleDto createClientRole(String clientId, RoleRequest request);

    void deleteClientRole(String clientId, String roleName);

    // --- Composite Roles ---

    void addChildRole(String parentRole, String childRole, RoleScope scope, String clientId);

    void removeChildRole(String parentRole, String childRole, RoleScope scope, String clientId);

    // --- User Role Assignment ---

    void assignRoleToUser(String userId, String roleName, RoleScope scope, String clientId);

    void removeRoleFromUser(String userId, String roleName, RoleScope scope, String clientId);

    List<RoleDto> getUserRoles(String userId);

    List<RoleDto> getUserRoles(String userId, String clientId);

    // --- User Attributes ---

    Map<String, List<String>> getUserAttributes(String userId);

    void updateUserAttribute(String userId, String key, List<String> values);

    void deleteUserAttribute(String userId, String key);

    // --- Actions Email ---

    /**
     * Sends the identity provider's own execute-actions email to a user.
     *
     * @param userId      the identity provider user ID
     * @param clientId    the client ID the actions link is scoped to
     * @param redirectUri the URI the flow redirects back to once completed
     * @param lifespan    how long the email link stays valid in seconds;
     *                    a null value means Keycloak's own default (12 hours)
     * @param actions     the required actions the user must complete
     */
    void sendActionsEmail(String userId, String clientId, String redirectUri, Integer lifespan, List<String> actions);

    // --- User Search ---

    PageResponse<UserSearchDto> searchUsers(String query, int page, int size);

    /**
     * Finds users whose email <b>exactly</b> matches the given address.
     *
     * <p>This is a narrow, exact finder, deliberately different from
     * {@link #searchUsers(String, int, int)}, which is Keycloak's substring search. The
     * access-provisioning link decision (record T5/T27) compares the frozen
     * {@code employees.email} against the accounts an address already owns, and a substring match
     * would make that comparison meaningless.</p>
     *
     * @param email the exact email address to look up
     * @return the matching accounts as {@link UserSearchDto}s; empty when none match. The list the
     *     identity provider returned, with no link decision applied here
     */
    List<UserSearchDto> findUsersByEmail(String email);

    /**
     * Resolves one user's <b>display name</b>, for a payload that has to say <b>who</b> acted and not
     * only which id acted (record T93).
     *
     * <p>It is deliberately narrow. The access-provisioning rows store the acting user's JWT
     * {@code sub} — Keycloak's own user id (record T73) — and the Access section renders the requester
     * of a task a human has to approve or refuse. The generic search carries <b>no</b> name at all
     * ({@link UserSearchDto} is id/username/email/enabled), so widening it would touch its other
     * consumers for a value only this read needs.</p>
     *
     * @param userId the identity provider user id
     * @return the display name when the provider knows the user — their full name, falling back to the
     *     username — or {@link Optional#empty()} when the user does not exist or carries no name at
     *     all. A connection failure is <b>not</b> an empty result: it propagates, the same way the
     *     sibling reads propagate it
     */
    Optional<String> findUserDisplayName(String userId);

    // --- Groups ---

    boolean companyGroupExists(String groupName);

    // --- Generic Group ---

    /**
     * Creates a Keycloak group with the given name and attributes.
     * No client or realm role is assigned to the group.
     *
     * @param name       the group name
     * @param attributes the group attributes (e.g. company_id, company_country_id)
     * @param parentId   if present the group is created as a child of that parent;
     *                   otherwise it is created at the top level
     */
    void createGroup(String name, Map<String, List<String>> attributes, Optional<String> parentId);

    /**
     * Finds a group ID by its exact name in the identity provider.
     *
     * @param name the exact group name to search for
     * @return the group ID if found, or {@link Optional#empty()} otherwise
     */
    Optional<String> findGroupIdByName(String name);
}
