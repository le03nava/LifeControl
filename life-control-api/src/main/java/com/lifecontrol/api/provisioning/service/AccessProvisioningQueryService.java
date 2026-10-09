package com.lifecontrol.api.provisioning.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.config.provisioning.ProvisioningWorkerProperties;
import com.lifecontrol.api.config.security.ApplicationClientProperties;
import com.lifecontrol.api.hr.exception.EmployeeNotFoundException;
import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.hr.service.EmployeeStoreScope;
import com.lifecontrol.api.provisioning.dto.AccessProvisioningClaims;
import com.lifecontrol.api.provisioning.dto.AccessProvisioningOverview;
import com.lifecontrol.api.provisioning.dto.AccessProvisioningRoleDiff;
import com.lifecontrol.api.provisioning.dto.AccessProvisioningTaskView;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTask;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus;
import com.lifecontrol.api.provisioning.repository.AccessProvisioningTaskRepository;
import com.lifecontrol.api.usersadmin.identity.IdentityProvider;
import com.lifecontrol.api.usersadmin.identity.RoleDto;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The <b>read data path</b> of the employee-access flow (unit W5a): what this unit's Access read
 * surface needs, composed from the write path's own derivations and the durable task rows, with
 * <b>no write of its own</b> — no migration, no producer, no state change.
 *
 * <p>The rules below are decisions, not conveniences:</p>
 *
 * <ul>
 *   <li><b>The company check is the first statement</b>, before any load, and the employee is then
 *       loaded through the company-scoped {@code findByIdAndCompanyId} — the convention every sibling
 *       service follows ({@code AccessProvisioningTaskService.create}, {@code EmployeeService}), so a
 *       foreign or unknown employee is a 404 before any other read.</li>
 *   <li><b>The required set is the write path's own derivation.</b> It comes from
 *       {@link AccessProvisioningRoleService#requiredRoles(Employee)} — the exact method
 *       {@code convergeRoles} uses — so the two can never drift. It already respects the allowlist
 *       ({@code lc-admin} is never required) and the current-contract rule (records T12/T28/T29), and
 *       it is derived even when the account is not linked, because the required set is a fact of the
 *       employment.</li>
 *   <li><b>The current roles are read live from the identity provider</b> —
 *       {@code getUserRoles(userId, clientId)} with the client id from the existing
 *       {@code keycloak.app.client-id} binding ({@link ApplicationClientProperties}) — and never
 *       mirrored locally: there is no local mirror to read (record T7). The diff is the shared
 *       {@link RoleDiffRule} rule — {@code added = required − current} and
 *       {@code removed = (current ∩ GrantablePositionRoles.names()) − required} — the exact rule
 *       {@link AccessProvisioningRoleService#convergeRoles(Employee)} applies, so a held role
 *       outside the grantable universe (including {@code lc-admin}) is never proposed for removal
 *       (records T12/T34).</li>
 *   <li><b>No linked account is said explicitly and reads no current roles</b>: when
 *       {@code employees.keycloak_user_id} is absent or blank the identity provider is never asked
 *       with a {@code null} user id, the current set is empty and so are the claim values.</li>
 *   <li><b>{@code attempts} is rendered against the configured maximum</b>
 *       ({@link ProvisioningWorkerProperties#maxAttempts()}), because a task that failed once and a
 *       task out of attempts are otherwise indistinguishable — both {@code FAILED} (record T43). A
 *       failed task's reason travels with it in {@code lastError} (record T10).</li>
 *   <li><b>The open task is the newest task in an open status</b>, using the same
 *       {@link AccessProvisioningTaskService#OPEN_STATUSES} set the creation path uses, so there is
 *       one definition of "open" rather than a second literal outside the partial index's predicate.
 *       The history is the employee's tasks newest first.</li>
 *   <li><b>The claim values are the membership projection's derivation</b> —
 *       {@link AccessProvisioningMembershipService#deriveMembership(Employee)} — the same read-only
 *       accessor shape as the role set: one derivation, no duplication, no write (records
 *       T1/T39).</li>
 * </ul>
 *
 * <p>The whole read runs in a single read-only transaction so the lazy {@code employee.company}
 * association and the contract's position chain resolve inside it, exactly as the write paths read
 * them.</p>
 */
@Service
public class AccessProvisioningQueryService {

    /**
     * The two non-null access states this read can return (record T10). The wire
     * vocabulary is deliberately <b>coarser</b> than {@link AccessProvisioningTaskStatus}: six task
     * statuses collapse into these two strings plus {@code null}, so this is a mapping decision and not
     * a name pass-through, and the query-service tests pin both strings.
     */
    private static final String ACCESS_STATE_PENDING = "PENDING";

    private static final String ACCESS_STATE_FAILED = "FAILED";

    private final CurrentUserContext currentUserContext;
    private final EmployeeRepository employeeRepository;
    private final AccessProvisioningRoleService roleService;
    private final AccessProvisioningMembershipService membershipService;
    private final IdentityProvider identityProvider;
    private final ApplicationClientProperties applicationClientProperties;
    private final AccessProvisioningTaskRepository taskRepository;
    private final ProvisioningWorkerProperties provisioningWorkerProperties;

    public AccessProvisioningQueryService(
            CurrentUserContext currentUserContext,
            EmployeeRepository employeeRepository,
            AccessProvisioningRoleService roleService,
            AccessProvisioningMembershipService membershipService,
            IdentityProvider identityProvider,
            ApplicationClientProperties applicationClientProperties,
            AccessProvisioningTaskRepository taskRepository,
            ProvisioningWorkerProperties provisioningWorkerProperties) {
        this.currentUserContext = currentUserContext;
        this.employeeRepository = employeeRepository;
        this.roleService = roleService;
        this.membershipService = membershipService;
        this.identityProvider = identityProvider;
        this.applicationClientProperties = applicationClientProperties;
        this.taskRepository = taskRepository;
        this.provisioningWorkerProperties = provisioningWorkerProperties;
    }

    /**
     * Composes one employee's access overview.
     *
     * @param companyId the company the caller claims and the employee must belong to
     * @param employeeId the employee whose access is read
     * @return the account linkage, the required/current roles and their diff, the open task and the
     *     history, and the five derived claim values
     * @throws org.springframework.security.access.AccessDeniedException when the current user cannot
     *     access the company
     * @throws EmployeeNotFoundException when the employee does not belong to the company
     */
    @Transactional(readOnly = true)
    public AccessProvisioningOverview getAccessOverview(UUID companyId, UUID employeeId) {
        currentUserContext.verifyCompanyAccess(companyId);
        var employee = employeeRepository
                .findByIdAndCompanyId(employeeId, companyId)
                .orElseThrow(() -> new EmployeeNotFoundException(employeeId));

        var required = roleService.requiredRoles(employee);

        var keycloakUserId = employee.getKeycloakUserId();
        var linked = keycloakUserId != null && !keycloakUserId.isBlank();

        var current = linked ? currentRoles(keycloakUserId) : Set.<String>of();

        var added = RoleDiffRule.additions(required, current);
        var removed = RoleDiffRule.removals(current, required);

        var claims = linked ? claimsOf(membershipService.deriveMembership(employee)) : AccessProvisioningClaims.empty();

        var maxAttempts = configuredMaxAttempts();
        var tasks = taskRepository.findByEmployeeIdOrderByRequestedAtDesc(employeeId);
        var history = tasks.stream().map(task -> toView(task, maxAttempts)).toList();
        var openTask = tasks.stream()
                .filter(task -> AccessProvisioningTaskService.OPEN_STATUSES.contains(task.getStatus()))
                .findFirst()
                .map(task -> toView(task, maxAttempts))
                .orElse(null);

        return new AccessProvisioningOverview(
                keycloakUserId,
                linked,
                required,
                current,
                new AccessProvisioningRoleDiff(added, removed),
                openTask,
                history,
                claims);
    }

    /**
     * The access <b>state</b> of one employee's most recent provisioning task, for the employee
     * detail {@code GET}: {@code "PENDING"} when the newest task is open and waiting (any status in
     * {@link AccessProvisioningTaskService#OPEN_STATUSES} other than {@code FAILED}), {@code "FAILED"}
     * when the newest task failed (record T10), and {@code null} when there is no task yet or the
     * newest one is terminal ({@code APPLIED} or {@code REJECTED}).
     *
     * <p>This is a <b>narrow read</b>, deliberately separate from
     * {@link #getAccessOverview(UUID, UUID)}: the employee detail {@code GET} is gated by
     * {@code isAuthenticated()} alone, an audience deliberately wider than the Access read surface
     * this unit owns, so the state it returns carries no role name and no count. The caller has
     * already verified the company, so this method performs <b>no</b> scope check and reads <b>no</b>
     * identity provider — it only resolves the newest task's state. It exists so the {@code hr}
     * detail path can enrich its payload without reaching into this domain's repository (record
     * G3).</p>
     *
     * @param employeeId the employee whose most recent task decides the state
     * @return {@code "PENDING"}, {@code "FAILED"} or {@code null}
     */
    @Transactional(readOnly = true)
    public String accessState(UUID employeeId) {
        return taskRepository.findByEmployeeIdOrderByRequestedAtDesc(employeeId).stream()
                .findFirst()
                .map(AccessProvisioningQueryService::accessStateOf)
                .orElse(null);
    }

    /**
     * The state of one task as this read reports it: {@code FAILED} stays visible as
     * its own state (record T10), every other open status collapses to {@code PENDING}, and a
     * terminal status is no state at all. The open set is reused from
     * {@link AccessProvisioningTaskService#OPEN_STATUSES} so there is no second literal to drift.
     */
    private static String accessStateOf(AccessProvisioningTask task) {
        var status = task.getStatus();
        if (status == AccessProvisioningTaskStatus.FAILED) {
            return ACCESS_STATE_FAILED;
        }
        return AccessProvisioningTaskService.OPEN_STATUSES.contains(status) ? ACCESS_STATE_PENDING : null;
    }

    /**
     * The account's live client roles, read through the port and normalized to a sorted set with
     * {@code null} entries dropped — the identical read {@code AccessProvisioningRoleService} performs,
     * so the diff the read renders is the diff the write would apply.
     */
    private Set<String> currentRoles(String userId) {
        var clientId = applicationClientProperties.clientId();
        return identityProvider.getUserRoles(userId, clientId).stream()
                .filter(Objects::nonNull)
                .map(RoleDto::name)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * The five claim values of a derived membership scope, in the scope's own insertion order: the
     * order the derivation published is the order the guard's claim parser reads, so it is preserved
     * rather than sorted.
     */
    private static AccessProvisioningClaims claimsOf(EmployeeStoreScope scope) {
        return new AccessProvisioningClaims(
                strings(scope.companyIds()),
                strings(scope.companyCountryIds()),
                strings(scope.companyRegionIds()),
                strings(scope.companyZoneIds()),
                strings(scope.companyStoreIds()));
    }

    private static List<String> strings(Set<UUID> ids) {
        return ids.stream().map(UUID::toString).toList();
    }

    /**
     * One task as the Access section reads it, with {@code attempts} rendered against the configured
     * ceiling (record T43) and the failure reason carried verbatim (record T10).
     */
    private static AccessProvisioningTaskView toView(AccessProvisioningTask task, int maxAttempts) {
        return new AccessProvisioningTaskView(
                task.getId(),
                task.getKind(),
                task.getStatus(),
                task.getAttempts() == null ? 0 : task.getAttempts(),
                maxAttempts,
                task.getLastError(),
                task.getNextAttemptAt(),
                task.getRequestedBy(),
                task.getRequestedAt(),
                task.getDecidedBy(),
                task.getDecidedAt(),
                Boolean.TRUE.equals(task.getEnabled()));
    }

    /**
     * The configured retry ceiling, with an unset binding read as {@code 0} rather than a
     * {@link NullPointerException}: the read surface must never fail because a worker property nobody
     * set is absent.
     */
    private int configuredMaxAttempts() {
        var configured = provisioningWorkerProperties.maxAttempts();
        return configured == null ? 0 : configured;
    }
}
