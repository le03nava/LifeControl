package com.lifecontrol.api.provisioning.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.config.security.ApplicationClientProperties;
import com.lifecontrol.api.hr.exception.EmployeeNotFoundException;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.provisioning.model.AccessProvisioningReviewedRole;
import com.lifecontrol.api.provisioning.model.AccessProvisioningRoleDirection;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTask;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus;
import com.lifecontrol.api.provisioning.repository.AccessProvisioningReviewedRoleRepository;
import com.lifecontrol.api.usersadmin.identity.IdentityProvider;
import com.lifecontrol.api.usersadmin.identity.RoleDto;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The <b>request path</b> of the employee-access gate (unit W5b): derive the employee's role diff,
 * decide the task's initial status with {@link AccessProvisioningApprovalPolicy}, create the task,
 * and — only when the gate applies — freeze the reviewed diff in the same transaction.
 *
 * <p><b>The reviewed set is the diff, with direction, and not the two raw role sets</b> (decision
 * T71): one {@code access_provisioning_reviewed_roles} row per touched role, {@link
 * AccessProvisioningRoleDirection#GRANT} for an addition and
 * {@link AccessProvisioningRoleDirection#REVOKE} for a removal, so the frozen rows read the same
 * grant/revoke distinction the read surface already renders.</p>
 *
 * <p><b>The diff is the same composition the write path and the read path use</b> (decision T74):
 * the required set comes from {@link AccessProvisioningRoleService#requiredRoles(com.lifecontrol.api.hr.model.Employee)},
 * the current set is read live from {@link IdentityProvider} under the read path's {@code linked}
 * guard — an unlinked employee never calls the identity provider — and the arithmetic is the shared
 * {@link RoleDiffRule}. The company check is the first statement, mirroring
 * {@link AccessProvisioningQueryService#getAccessOverview(UUID, UUID)}.</p>
 *
 * <p><b>The task is created through {@link AccessProvisioningTaskService#create}</b> with the status
 * the policy selects, so that landed signature and the guarded entry into the state machine stay
 * untouched. The reviewed rows are written in the same transaction as the task, so the frozen set
 * and the task row can never disagree about what the diff was.</p>
 *
 * <p><b>The request path has no production caller yet.</b> The producer that finally calls
 * {@link #request(UUID, UUID, AccessProvisioningTaskKind, String)} — a contract activation, a
 * store-assignment change, a termination — lands in a later unit; until then that method is reached
 * by its tests. The decision methods {@link #approve(UUID, UUID, UUID)} and
 * {@link #reject(UUID, UUID, UUID, String)} <b>do</b> have a caller: the controller's approve and
 * reject endpoints.</p>
 */
@Service
public class AccessProvisioningGateService {

    private final CurrentUserContext currentUserContext;
    private final EmployeeRepository employeeRepository;
    private final AccessProvisioningRoleService roleService;
    private final IdentityProvider identityProvider;
    private final ApplicationClientProperties applicationClientProperties;
    private final AccessProvisioningApprovalPolicy approvalPolicy;
    private final AccessProvisioningTaskService taskService;
    private final AccessProvisioningReviewedRoleRepository reviewedRoleRepository;

    public AccessProvisioningGateService(
            CurrentUserContext currentUserContext,
            EmployeeRepository employeeRepository,
            AccessProvisioningRoleService roleService,
            IdentityProvider identityProvider,
            ApplicationClientProperties applicationClientProperties,
            AccessProvisioningApprovalPolicy approvalPolicy,
            AccessProvisioningTaskService taskService,
            AccessProvisioningReviewedRoleRepository reviewedRoleRepository) {
        this.currentUserContext = currentUserContext;
        this.employeeRepository = employeeRepository;
        this.roleService = roleService;
        this.identityProvider = identityProvider;
        this.applicationClientProperties = applicationClientProperties;
        this.approvalPolicy = approvalPolicy;
        this.taskService = taskService;
        this.reviewedRoleRepository = reviewedRoleRepository;
    }

    /**
     * Derives an employee's role diff and creates the task the diff's risk selects.
     *
     * <ol>
     *   <li>The company access check is the <b>first</b> statement, before any load.</li>
     *   <li>The employee is loaded through the company-scoped {@code findByIdAndCompanyId}, so a
     *       foreign or unknown employee is an {@link EmployeeNotFoundException} and nothing is
     *       written.</li>
     *   <li>The diff is composed exactly as the read path composes it: the required set from
     *       {@link AccessProvisioningRoleService#requiredRoles(com.lifecontrol.api.hr.model.Employee)},
     *       the current set read live from the identity provider only when the account is linked,
     *       and {@link RoleDiffRule} for the arithmetic.</li>
     *   <li>The initial status is {@link AccessProvisioningApprovalPolicy#initialStatusFor(Set)} over
     *       the touched roles — additions and removals together.</li>
     *   <li>{@link AccessProvisioningTaskService#create} writes the task with that status and the
     *       {@code requestedBy} passed through verbatim.</li>
     *   <li>Only when the status is {@link AccessProvisioningTaskStatus#APPROVAL_PENDING} is the
     *       diff frozen, one row per role with its direction, in the same transaction.</li>
     * </ol>
     *
     * @param companyId the company the caller claims and the employee must belong to
     * @param employeeId the employee the task is requested for
     * @param kind the task's immutable reference
     * @param requestedBy the actor requesting the task, recorded verbatim
     * @return the task {@code create} saved
     * @throws org.springframework.security.access.AccessDeniedException when the current user cannot
     *     access the company
     * @throws EmployeeNotFoundException when the employee does not belong to the company
     */
    @Transactional
    public AccessProvisioningTask request(
            UUID companyId, UUID employeeId, AccessProvisioningTaskKind kind, String requestedBy) {
        currentUserContext.verifyCompanyAccess(companyId);
        var employee = employeeRepository
                .findByIdAndCompanyId(employeeId, companyId)
                .orElseThrow(() -> new EmployeeNotFoundException(employeeId));

        var required = roleService.requiredRoles(employee);

        var keycloakUserId = employee.getKeycloakUserId();
        var linked = keycloakUserId != null && !keycloakUserId.isBlank();

        var current = linked ? currentRoles(keycloakUserId) : Set.<String>of();

        var additions = RoleDiffRule.additions(required, current);
        var removals = RoleDiffRule.removals(current, required);

        var touched = new TreeSet<>(additions);
        touched.addAll(removals);

        var status = approvalPolicy.initialStatusFor(touched);
        var task = taskService.create(companyId, employeeId, kind, requestedBy, status);

        if (status == AccessProvisioningTaskStatus.APPROVAL_PENDING) {
            freezeReviewedRoles(task, additions, removals);
        }
        return task;
    }

    /**
     * Approves a gated task for the current user: the company access check is the first statement,
     * then the decision is delegated to
     * {@link AccessProvisioningTaskService#approve(UUID, UUID, String)}.
     *
     * <p>The actor is {@link CurrentUserContext#getUserId()} — the JWT {@code sub} claim — and
     * <b>never</b> {@link CurrentUserContext#getUsername()}: the persisted
     * {@code requested_by}/{@code decided_by} columns are {@code VARCHAR(36)} and rule O3 compares
     * those two persisted actors, so both sides must use the same basis.</p>
     *
     * <p>The company check lives here and is deliberately <b>not</b> pushed into the task service:
     * the worker calls the same decision methods and has no current user, so the scope check belongs
     * to the request-path component that owns a {@link CurrentUserContext}. Proving the employee
     * belongs to that company is part of the same scope check and happens here too, before the
     * decision is delegated.</p>
     *
     * @throws org.springframework.security.access.AccessDeniedException when the current user cannot
     *     access the company
     * @throws EmployeeNotFoundException when the employee does not belong to the company
     */
    @Transactional
    public void approve(UUID companyId, UUID employeeId, UUID taskId) {
        currentUserContext.verifyCompanyAccess(companyId);
        requireEmployeeInCompany(companyId, employeeId);
        taskService.approve(employeeId, taskId, currentUserContext.getUserId());
    }

    /**
     * Rejects a gated task for the current user: the company access check is the first statement, then
     * the decision is delegated to
     * {@link AccessProvisioningTaskService#reject(UUID, UUID, String, String)} with the reason.
     *
     * <p>The actor is {@link CurrentUserContext#getUserId()} — the JWT {@code sub} claim — and
     * <b>never</b> {@link CurrentUserContext#getUsername()}: the persisted
     * {@code requested_by}/{@code decided_by} columns are {@code VARCHAR(36)} and rule O3 compares
     * those two persisted actors, so both sides must use the same basis.</p>
     *
     * <p>The company check lives here and is deliberately <b>not</b> pushed into the task service:
     * the worker calls the same decision methods and has no current user. Proving the employee
     * belongs to that company is part of the same scope check and happens here too, before the
     * decision is delegated.</p>
     *
     * @param reason the rejection reason, persisted in {@code last_error}; the task service refuses a
     *     {@code null} or blank value with {@link IllegalArgumentException}
     * @throws org.springframework.security.access.AccessDeniedException when the current user cannot
     *     access the company
     * @throws EmployeeNotFoundException when the employee does not belong to the company
     */
    @Transactional
    public void reject(UUID companyId, UUID employeeId, UUID taskId, String reason) {
        currentUserContext.verifyCompanyAccess(companyId);
        requireEmployeeInCompany(companyId, employeeId);
        taskService.reject(employeeId, taskId, currentUserContext.getUserId(), reason);
    }

    /**
     * Proves the employee belongs to the company the caller claims, <b>before</b> the decision is
     * delegated to the task service.
     *
     * <p>The decision path scopes only the <b>task</b> to the employee
     * ({@code findByIdAndEmployeeIdForUpdate(taskId, employeeId)}) and never receives the company,
     * so without this check the caller's company would be compared against the path variable and
     * nothing else: an actor with access to any company could approve or reject a foreign employee's
     * task by sending their own {@code companyId} together with the victim's
     * {@code employeeId}/{@code taskId}, and the controller would only reload the mismatched pair
     * <b>after</b> the decision had already committed. Loading the employee through the
     * company-scoped {@code findByIdAndCompanyId} — the same scope the read path
     * ({@link AccessProvisioningQueryService#getAccessOverview(UUID, UUID)}) and
     * {@link #request(UUID, UUID, AccessProvisioningTaskKind, String)} apply — turns that mismatched
     * pair into a 404 before any decision is attempted.</p>
     *
     * @throws EmployeeNotFoundException when the employee is unknown or does not belong to the company
     */
    private void requireEmployeeInCompany(UUID companyId, UUID employeeId) {
        employeeRepository
                .findByIdAndCompanyId(employeeId, companyId)
                .orElseThrow(() -> new EmployeeNotFoundException(employeeId));
    }

    /**
     * Freezes one row per touched role, each carrying its direction, in the task's own transaction.
     */
    private void freezeReviewedRoles(AccessProvisioningTask task, Set<String> additions, Set<String> removals) {
        var rows = new ArrayList<AccessProvisioningReviewedRole>(additions.size() + removals.size());
        additions.forEach(roleName -> rows.add(reviewedRole(task, roleName, AccessProvisioningRoleDirection.GRANT)));
        removals.forEach(roleName -> rows.add(reviewedRole(task, roleName, AccessProvisioningRoleDirection.REVOKE)));
        if (!rows.isEmpty()) {
            reviewedRoleRepository.saveAll(rows);
        }
    }

    private static AccessProvisioningReviewedRole reviewedRole(
            AccessProvisioningTask task, String roleName, AccessProvisioningRoleDirection direction) {
        return AccessProvisioningReviewedRole.builder()
                .task(task)
                .roleName(roleName)
                .direction(direction)
                .build();
    }

    /**
     * The account's live client roles, normalized to a sorted set with {@code null} entries dropped —
     * the identical read the write path and the read path perform, so the diff the gate freezes is
     * the diff they would apply.
     */
    private Set<String> currentRoles(String userId) {
        var clientId = applicationClientProperties.clientId();
        return identityProvider.getUserRoles(userId, clientId).stream()
                .filter(Objects::nonNull)
                .map(RoleDto::name)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
