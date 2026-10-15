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
import com.lifecontrol.api.provisioning.dto.AccessProvisioningQueueEntry;
import com.lifecontrol.api.provisioning.dto.AccessProvisioningRoleDiff;
import com.lifecontrol.api.provisioning.dto.AccessProvisioningTaskView;
import com.lifecontrol.api.provisioning.model.AccessProvisioningReviewedRole;
import com.lifecontrol.api.provisioning.model.AccessProvisioningRoleDirection;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTask;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus;
import com.lifecontrol.api.provisioning.repository.AccessProvisioningReviewedRoleRepository;
import com.lifecontrol.api.provisioning.repository.AccessProvisioningTaskRepository;
import com.lifecontrol.api.usersadmin.identity.IdentityProvider;
import com.lifecontrol.api.usersadmin.identity.RoleDto;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 *   <li><b>The frozen set is the open task's own, and it is not the live diff</b> (record T92, closing
 *       G33): {@code frozenRoleDiff} is read from the rows the task froze when it went to the gate, so
 *       a human decides against the set they were shown and not against a diff that moved after the
 *       request. No open task — or an open task that froze nothing — answers {@code null} instead of an
 *       empty set, because "there is nothing to review" and "nothing is frozen" are different facts.</li>
 *   <li><b>The company's decision queue is a company-scoped read with no employee in its path</b>
 *       (record T94): it verifies the company first, asks for {@code APPROVAL_PENDING} only, and resolves
 *       the requester's name through the same per-actor memo the overview uses, so a queue of rows that
 *       share one actor costs one identity-provider read. The scope itself lives in the repository query,
 *       because the tasks table has no {@code company_id} to filter on.</li>
 *   <li><b>The requester's name is resolved through the port, once per distinct actor</b> (record T93):
 *       the rows carry the actor's id and the section has to say who asked, so the name is looked up
 *       for the rendered tasks and memoized, never once per row. An actor the provider no longer knows
 *       keeps their id and carries no name (gap G42), and a provider failure propagates rather than
 *       being swallowed into a nameless row.</li>
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
    private final AccessProvisioningReviewedRoleRepository reviewedRoleRepository;
    private final ProvisioningWorkerProperties provisioningWorkerProperties;

    public AccessProvisioningQueryService(
            CurrentUserContext currentUserContext,
            EmployeeRepository employeeRepository,
            AccessProvisioningRoleService roleService,
            AccessProvisioningMembershipService membershipService,
            IdentityProvider identityProvider,
            ApplicationClientProperties applicationClientProperties,
            AccessProvisioningTaskRepository taskRepository,
            AccessProvisioningReviewedRoleRepository reviewedRoleRepository,
            ProvisioningWorkerProperties provisioningWorkerProperties) {
        this.currentUserContext = currentUserContext;
        this.employeeRepository = employeeRepository;
        this.roleService = roleService;
        this.membershipService = membershipService;
        this.identityProvider = identityProvider;
        this.applicationClientProperties = applicationClientProperties;
        this.taskRepository = taskRepository;
        this.reviewedRoleRepository = reviewedRoleRepository;
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
        var openTaskRow = tasks.stream()
                .filter(task -> AccessProvisioningTaskService.OPEN_STATUSES.contains(task.getStatus()))
                .findFirst()
                .orElse(null);

        // One display-name lookup per distinct actor, shared by the history and the open task: the
        // rows record the actor by id, and asking once per rendered task would turn one render into
        // as many identity-provider reads as the history is long.
        var resolvedNames = new HashMap<String, String>();
        var history = tasks.stream()
                .map(task -> toView(task, maxAttempts, resolvedNames))
                .toList();
        var openTask = openTaskRow == null ? null : toView(openTaskRow, maxAttempts, resolvedNames);

        return new AccessProvisioningOverview(
                keycloakUserId,
                linked,
                required,
                current,
                new AccessProvisioningRoleDiff(added, removed),
                frozenDiffOf(openTaskRow),
                openTask,
                history,
                claims);
    }

    /**
     * The company's <b>decision queue</b> (unit W5d, slice B, record T94): the tasks of this company's
     * employees waiting for a human, oldest request first, each with the employee it belongs to.
     *
     * <p>The composition is deliberately thin and the rules live where they belong. The <b>company check
     * is the first statement</b>, before any load, exactly as the overview's is — which is what makes this
     * a company-scoped read rather than a cross-company one. The <b>set</b> is
     * {@code APPROVAL_PENDING} and nothing else: a queue is the set a human has to decide, while
     * {@code PENDING}, {@code RUNNING} and {@code FAILED} tasks are the worker's and the operator's own
     * business, which the Access section already serves per employee with {@code retry} and
     * {@code resend} (gap G42). The <b>scope enters through the employee</b>, because
     * {@code access_provisioning_tasks} carries no {@code company_id} — that is the repository query's
     * own job, and this method cannot express it any other way.</p>
     *
     * <p>The requester's name is resolved through the same memo the overview uses, so a queue of rows
     * that share one actor costs one identity-provider read rather than one per row (record T93).</p>
     *
     * @param companyId the company whose employees' waiting tasks are listed
     * @return the queue, oldest request first
     * @throws org.springframework.security.access.AccessDeniedException when the current user cannot
     *     access the company
     */
    @Transactional(readOnly = true)
    public List<AccessProvisioningQueueEntry> getDecisionQueue(UUID companyId) {
        currentUserContext.verifyCompanyAccess(companyId);

        var resolvedNames = new HashMap<String, String>();
        return taskRepository
                .findByEmployeeCompanyIdAndStatus(companyId, AccessProvisioningTaskStatus.APPROVAL_PENDING)
                .stream()
                .map(task -> toQueueEntry(task, resolvedNames))
                .toList();
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
     * ceiling (record T43), the failure reason carried verbatim (record T10), and the requester's
     * display name resolved through the shared memo (record T93).
     */
    private AccessProvisioningTaskView toView(
            AccessProvisioningTask task, int maxAttempts, Map<String, String> resolvedNames) {
        return new AccessProvisioningTaskView(
                task.getId(),
                task.getKind(),
                task.getStatus(),
                task.getAttempts() == null ? 0 : task.getAttempts(),
                maxAttempts,
                task.getLastError(),
                task.getNextAttemptAt(),
                task.getRequestedBy(),
                requestedByNameOf(task.getRequestedBy(), resolvedNames),
                task.getRequestedAt(),
                task.getDecidedBy(),
                task.getDecidedAt(),
                Boolean.TRUE.equals(task.getEnabled()));
    }

    /**
     * The <b>frozen reviewed set</b> of the task a human is asked to decide (record T92, closing
     * G33), as the two sets a renderer shows: a {@code GRANT} row is a role the reviewer is approving
     * the addition of, a {@code REVOKE} row one they are approving the removal of.
     *
     * <p>It is <b>null</b> when no task is open — a terminal history is not asked for a frozen set,
     * because the decision it belonged to is over — and also when the open task froze nothing, which
     * is what an auto-applied task looks like (the set exists only where a human had to decide). The
     * UI has to tell those apart from an empty set rather than render the live diff in their place.</p>
     *
     * <p>A row whose direction or role name is unreadable is skipped rather than defaulted: the column
     * is {@code NOT NULL} and the only writer is the gate service, so a null here is a construction
     * defect, and defaulting it would either invent a grant or invent a removal.</p>
     */
    private AccessProvisioningRoleDiff frozenDiffOf(AccessProvisioningTask openTask) {
        if (openTask == null) {
            return null;
        }
        var reviewed = reviewedRoleRepository.findByTaskIdOrderByRoleNameAsc(openTask.getId());
        if (reviewed.isEmpty()) {
            return null;
        }
        var granted = new TreeSet<String>();
        var revoked = new TreeSet<String>();
        for (AccessProvisioningReviewedRole row : reviewed) {
            var roleName = row.getRoleName();
            var direction = row.getDirection();
            if (roleName == null || direction == null) {
                continue;
            }
            if (direction == AccessProvisioningRoleDirection.GRANT) {
                granted.add(roleName);
            } else if (direction == AccessProvisioningRoleDirection.REVOKE) {
                revoked.add(roleName);
            }
        }
        return new AccessProvisioningRoleDiff(granted, revoked);
    }

    /**
     * One queue row: the waiting task plus the employee it belongs to (record T94).
     *
     * <p>The employee is read without a null branch, and that is a statement rather than an oversight:
     * {@code access_provisioning_tasks.employee_id} is {@code NOT NULL} with a foreign key and the
     * repository's query joins it, so a task without an employee cannot be selected. Inventing a branch
     * that emitted a row with a null employee id would be the silent half-row this record keeps
     * refusing; a broken invariant is meant to be loud.</p>
     */
    private AccessProvisioningQueueEntry toQueueEntry(AccessProvisioningTask task, Map<String, String> resolvedNames) {
        var employee = task.getEmployee();
        return new AccessProvisioningQueueEntry(
                task.getId(),
                task.getKind(),
                task.getStatus(),
                task.getRequestedBy(),
                requestedByNameOf(task.getRequestedBy(), resolvedNames),
                task.getRequestedAt(),
                employee.getId(),
                employee.getEmployeeNumber(),
                employee.getFirstName(),
                employee.getPaternalLastName(),
                employee.getMaternalLastName());
    }

    /**
     * The requester's display name resolved through the port, memoized per distinct actor: a blank or
     * absent actor is never asked for, and an actor the provider no longer knows resolves once and
     * stays {@code null} rather than being retried for every row that mentions them (record T93, gap
     * G42). A connection failure is not swallowed: it propagates, exactly as the sibling reads do.
     */
    private String requestedByNameOf(String requestedBy, Map<String, String> resolvedNames) {
        if (requestedBy == null || requestedBy.isBlank()) {
            return null;
        }
        if (resolvedNames.containsKey(requestedBy)) {
            return resolvedNames.get(requestedBy);
        }
        var resolved = identityProvider.findUserDisplayName(requestedBy).orElse(null);
        resolvedNames.put(requestedBy, resolved);
        return resolved;
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
