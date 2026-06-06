package com.lifecontrol.api.usersadmin.identity;

import com.lifecontrol.api.usersadmin.dto.PageResponse;
import com.lifecontrol.api.usersadmin.dto.RoleRequest;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Abstraction for identity provider operations (role CRUD, user assignment,
 * attributes, search). Decouples the domain from any specific IdP implementation.
 */
public interface IdentityProvider {

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

    // --- User Search ---

    PageResponse<UserSearchDto> searchUsers(String query, int page, int size);

    // --- Groups ---

    boolean companyGroupExists(String groupName);

    // --- Generic Group with Role ---

    void createGroupWithRole(String groupName, Map<String, List<String>> attributes,
                             String roleName, String clientId);

    /**
     * Overload with optional parent group ID. When {@code parentGroupId} is
     * non-{@code null} the group is created as a child of that parent; otherwise
     * it is created at the top level (equivalent to the 4-arg form).
     */
    void createGroupWithRole(String groupName, Map<String, List<String>> attributes,
                             String roleName, String clientId, String parentGroupId);

    /**
     * Finds a group ID by its exact name in the identity provider.
     *
     * @param name the exact group name to search for
     * @return the group ID if found, or {@link Optional#empty()} otherwise
     */
    Optional<String> findGroupIdByName(String name);
}
