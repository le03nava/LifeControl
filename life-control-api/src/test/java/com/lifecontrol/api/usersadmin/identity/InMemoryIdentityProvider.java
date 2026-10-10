package com.lifecontrol.api.usersadmin.identity;

import com.lifecontrol.api.usersadmin.dto.PageResponse;
import com.lifecontrol.api.usersadmin.dto.RoleRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.keycloak.representations.idm.UserRepresentation;

/**
 * Test-scoped, in-memory implementation of {@link IdentityProvider}, living beside the port it
 * implements (record T79): this module has no other implementation besides the Keycloak adapter, and a
 * double exists to exercise <b>calls into another system</b> without that system.
 *
 * <p>It keeps real state — each account has an enabled flag, an attribute map and a per-client role
 * set — so a consumer that runs the <b>real</b> provisioning services over it (the
 * {@code AccessProvisioningWorkerDeactivateCompositionTest}) can assert on the resulting state rather
 * than on Mockito argument captures (gap G23). The read-modify-write methods
 * ({@code disableUser}, {@code updateUserAttribute}, {@code deleteUserAttribute},
 * {@code assignRoleToUser}, {@code removeRoleFromUser}) mutate that state faithfully.</p>
 *
 * <p>The methods this double does not need <b>fail by name</b> with
 * {@link UnsupportedOperationException} — never returning an empty value that would read as success.
 * The handful of methods the composition path does exercise are implemented; the rest of the port is
 * refused so a future consumer gets a loud failure instead of a silent no-op.</p>
 */
public class InMemoryIdentityProvider implements IdentityProvider {

    private final Map<String, Account> accounts = new LinkedHashMap<>();
    private final AtomicInteger idSequence = new AtomicInteger();

    /** The mutable state one account holds: enabled flag, attributes and per-client role sets. */
    private static final class Account {
        private final String id;
        private String username;
        private String email;
        private boolean enabled;
        private final Map<String, List<String>> attributes = new LinkedHashMap<>();
        private final Map<String, Set<String>> clientRoles = new LinkedHashMap<>();
        private final List<String> sentActionsEmails = new ArrayList<>();

        private Account(String id, String username, String email, boolean enabled) {
            this.id = id;
            this.username = username;
            this.email = email;
            this.enabled = enabled;
        }
    }

    // --- test helpers (not part of the port) ---

    /** Creates an <b>enabled</b> account with a generated id and returns that id. */
    public String createAccount(String username, String email) {
        var id = "kc-" + idSequence.incrementAndGet();
        accounts.put(id, new Account(id, username, email, true));
        return id;
    }

    /** Whether the account exists and is enabled; {@code false} for an unknown account too. */
    public boolean isEnabled(String userId) {
        var account = accounts.get(userId);
        return account != null && account.enabled;
    }

    /** The role names the account holds in one client, as an unmodifiable copy. */
    public Set<String> roleNames(String userId, String clientId) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(rolesOf(requireAccount(userId), clientId)));
    }

    /** The attributes the account carries, as an unmodifiable copy. */
    public Map<String, List<String>> attributes(String userId) {
        var copy = new LinkedHashMap<String, List<String>>();
        requireAccount(userId).attributes.forEach((key, values) -> copy.put(key, List.copyOf(values)));
        return Collections.unmodifiableMap(copy);
    }

    /** A snapshot of the accounts that received an execute-actions email. */
    public List<String> actionsEmailRecipients() {
        return accounts.values().stream()
                .filter(account -> !account.sentActionsEmails.isEmpty())
                .map(account -> account.id)
                .toList();
    }

    /** Seeds a client role directly, without going through the assignment port method. */
    public void seedClientRole(String userId, String clientId, String roleName) {
        rolesOf(requireAccount(userId), clientId).add(roleName);
    }

    /** Seeds an attribute directly, without going through the update port method. */
    public void seedAttribute(String userId, String key, List<String> values) {
        requireAccount(userId).attributes.put(key, new ArrayList<>(values));
    }

    private Account requireAccount(String userId) {
        var account = accounts.get(userId);
        if (account == null) {
            throw new IllegalArgumentException("InMemoryIdentityProvider has no account " + userId);
        }
        return account;
    }

    private static Set<String> rolesOf(Account account, String clientId) {
        return account.clientRoles.computeIfAbsent(clientId, ignored -> new LinkedHashSet<>());
    }

    // --- User Lifecycle ---

    @Override
    public String createUser(UserRepresentation user) {
        var id = "kc-" + idSequence.incrementAndGet();
        var account = new Account(id, user.getUsername(), user.getEmail(), Boolean.TRUE.equals(user.isEnabled()));
        accounts.put(id, account);
        if (user.getAttributes() != null) {
            user.getAttributes().forEach((key, values) -> account.attributes.put(key, new ArrayList<>(values)));
        }
        return id;
    }

    @Override
    public void deleteUser(String userId) {
        throw unsupported("deleteUser");
    }

    @Override
    public void updateUser(String userId, UserRepresentation user) {
        throw unsupported("updateUser");
    }

    @Override
    public void disableUser(String userId) {
        requireAccount(userId).enabled = false;
    }

    // --- Realm Role CRUD ---

    @Override
    public List<RoleDto> listRealmRoles() {
        throw unsupported("listRealmRoles");
    }

    @Override
    public RoleDto createRealmRole(RoleRequest request) {
        throw unsupported("createRealmRole");
    }

    @Override
    public RoleDto getRealmRole(String name) {
        throw unsupported("getRealmRole");
    }

    @Override
    public RoleDto updateRealmRole(String name, RoleRequest request) {
        throw unsupported("updateRealmRole");
    }

    @Override
    public void deleteRealmRole(String name) {
        throw unsupported("deleteRealmRole");
    }

    // --- Client Role CRUD ---

    @Override
    public List<RoleDto> listClientRoles(String clientId) {
        throw unsupported("listClientRoles");
    }

    @Override
    public RoleDto createClientRole(String clientId, RoleRequest request) {
        throw unsupported("createClientRole");
    }

    @Override
    public void deleteClientRole(String clientId, String roleName) {
        throw unsupported("deleteClientRole");
    }

    // --- Composite Roles ---

    @Override
    public void addChildRole(String parentRole, String childRole, RoleScope scope, String clientId) {
        throw unsupported("addChildRole");
    }

    @Override
    public void removeChildRole(String parentRole, String childRole, RoleScope scope, String clientId) {
        throw unsupported("removeChildRole");
    }

    // --- User Role Assignment ---

    @Override
    public void assignRoleToUser(String userId, String roleName, RoleScope scope, String clientId) {
        rolesOf(requireAccount(userId), clientId).add(roleName);
    }

    @Override
    public void removeRoleFromUser(String userId, String roleName, RoleScope scope, String clientId) {
        rolesOf(requireAccount(userId), clientId).remove(roleName);
    }

    @Override
    public List<RoleDto> getUserRoles(String userId) {
        var account = requireAccount(userId);
        var names = new LinkedHashSet<String>();
        account.clientRoles.values().forEach(names::addAll);
        return names.stream().map(name -> dto(name, null)).toList();
    }

    @Override
    public List<RoleDto> getUserRoles(String userId, String clientId) {
        return rolesOf(requireAccount(userId), clientId).stream()
                .map(name -> dto(name, clientId))
                .toList();
    }

    private static RoleDto dto(String name, String clientId) {
        return new RoleDto(name, null, Boolean.FALSE, RoleScope.CLIENT, clientId);
    }

    // --- User Attributes ---

    @Override
    public Map<String, List<String>> getUserAttributes(String userId) {
        return attributes(userId);
    }

    @Override
    public void updateUserAttribute(String userId, String key, List<String> values) {
        requireAccount(userId).attributes.put(key, new ArrayList<>(values));
    }

    @Override
    public void deleteUserAttribute(String userId, String key) {
        requireAccount(userId).attributes.remove(key);
    }

    // --- Actions Email ---

    @Override
    public void sendActionsEmail(
            String userId, String clientId, String redirectUri, Integer lifespan, List<String> actions) {
        requireAccount(userId).sentActionsEmails.add(String.join(",", actions));
    }

    // --- User Search ---

    @Override
    public PageResponse<UserSearchDto> searchUsers(String query, int page, int size) {
        throw unsupported("searchUsers");
    }

    @Override
    public List<UserSearchDto> findUsersByEmail(String email) {
        return accounts.values().stream()
                .filter(account -> email != null && email.equalsIgnoreCase(account.email))
                .map(account -> new UserSearchDto(account.id, account.username, account.email, account.enabled))
                .toList();
    }

    // --- Groups ---

    @Override
    public boolean companyGroupExists(String groupName) {
        throw unsupported("companyGroupExists");
    }

    // --- Generic Group ---

    @Override
    public void createGroup(String name, Map<String, List<String>> attributes, Optional<String> parentId) {
        throw unsupported("createGroup");
    }

    @Override
    public Optional<String> findGroupIdByName(String name) {
        throw unsupported("findGroupIdByName");
    }

    private static UnsupportedOperationException unsupported(String methodName) {
        return new UnsupportedOperationException("InMemoryIdentityProvider does not implement " + methodName);
    }
}
