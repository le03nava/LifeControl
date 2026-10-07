package com.lifecontrol.api.provisioning.service;

import com.lifecontrol.api.config.security.ApplicationClientProperties;
import com.lifecontrol.api.hr.model.Contract;
import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.hr.model.Position;
import com.lifecontrol.api.hr.model.PositionRole;
import com.lifecontrol.api.hr.repository.ContractRepository;
import com.lifecontrol.api.hr.repository.PositionRoleRepository;
import com.lifecontrol.api.hr.service.EmployeeStatuses;
import com.lifecontrol.api.hr.service.GrantablePositionRoles;
import com.lifecontrol.api.provisioning.dto.RoleConvergenceResult;
import com.lifecontrol.api.provisioning.exception.AccountNotLinkedException;
import com.lifecontrol.api.provisioning.exception.AmbiguousCurrentContractException;
import com.lifecontrol.api.provisioning.exception.PositionOutsideEmployeeCompanyException;
import com.lifecontrol.api.usersadmin.identity.IdentityProvider;
import com.lifecontrol.api.usersadmin.identity.RoleDto;
import com.lifecontrol.api.usersadmin.identity.RoleScope;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The <b>role convergence</b> of the access-provisioning projection (unit W2b): derive the roles of an
 * employee's position from the current contract, read the account's live client roles, and converge
 * Keycloak to the required set.
 *
 * <p>It is a capability with <b>no endpoint, no scheduler, no migration and no task write</b>: its
 * caller is the access-provisioning worker (W4). It <b>persists nothing</b> — not a task row and not
 * an {@code access_provisioning_applied_roles} row; writing the applied-roles snapshot is W4's job
 * (record T32), because that snapshot is attached to the task while this unit's input is an employee.
 * The returned {@link RoleConvergenceResult} carries the three sets separately, and the snapshot W4
 * persists is {@code granted ∪ removed}. It deliberately does <b>not</b> call
 * {@code verifyCompanyAccess}: the worker has no current user, and the same reason made W2a skip the
 * same guard — the position template is read through
 * {@link PositionRoleRepository#findByPositionIdOrderByRoleNameAsc} directly, never through
 * {@code PositionRoleService.getRoles} (record T38).</p>
 *
 * <p>It <b>refuses by name rather than guessing</b> in the three situations where guessing would
 * grant the wrong person a role (record T36): an employee with no {@code keycloak_user_id}
 * ({@link AccountNotLinkedException}), a current contract that resolves to more than one row
 * ({@link AmbiguousCurrentContractException}), and a position whose company is not the employee's
 * ({@link PositionOutsideEmployeeCompanyException}). A failure applying a single role propagates
 * unchanged: nothing is swallowed, nothing is retried here and no partial success is reported (record
 * T37), because the convergence is idempotent by construction and a retry re-derives everything.</p>
 */
@Service
public class AccessProvisioningRoleService {

    private final IdentityProvider identityProvider;
    private final ApplicationClientProperties applicationClientProperties;
    private final ContractRepository contractRepository;
    private final PositionRoleRepository positionRoleRepository;

    public AccessProvisioningRoleService(
            IdentityProvider identityProvider,
            ApplicationClientProperties applicationClientProperties,
            ContractRepository contractRepository,
            PositionRoleRepository positionRoleRepository) {
        this.identityProvider = identityProvider;
        this.applicationClientProperties = applicationClientProperties;
        this.contractRepository = contractRepository;
        this.positionRoleRepository = positionRoleRepository;
    }

    /**
     * Converges the account's client roles to the required set of the employee's current contract.
     *
     * <ol>
     *   <li><b>Linked account or refuse.</b> A {@code null} {@code keycloakUserId} throws
     *       {@link AccountNotLinkedException} and nothing else happens — no repository call and no
     *       identity-provider call.</li>
     *   <li><b>Current contract.</b> {@code findEnabledContractCoveringDate} today; more than one row
     *       throws {@link AmbiguousCurrentContractException} and never picks one; zero rows means the
     *       required set is empty and no position is read; otherwise the single contract is used.</li>
     *   <li><b>Position and its company.</b> {@code position → department → company} must resolve to
     *       the employee's company, or {@link PositionOutsideEmployeeCompanyException} is thrown — a
     *       {@code null} anywhere in that chain is the same refusal and never a
     *       {@link NullPointerException}.</li>
     *   <li><b>Required set.</b> A {@code Terminated} employee requires nothing (the template read is
     *       short-circuited); otherwise the position's enabled template rows are kept, mapped to their
     *       role names and filtered through {@link GrantablePositionRoles#includes}. A disabled row is
     *       not required — the repository deliberately has no enabled filter, so this method applies
     *       it.</li>
     *   <li><b>Live current roles.</b> {@code getUserRoles(userId, clientId)} through the two-arg
     *       overload, which the adapter already scopes to the application client; the service does not
     *       re-implement scope filtering.</li>
     *   <li><b>Diff.</b> {@code granted = required − current} and
     *       {@code removed = (current ∩ GrantablePositionRoles.names()) − required}: a held role
     *       outside the grantable universe (including {@code lc-admin}) is never touched (record
     *       T34). {@code lc-admin} is unreachable because it is absent from
     *       {@link GrantablePositionRoles}.</li>
     *   <li><b>Apply.</b> Removals first, then grants, each set in ascending name order.</li>
     * </ol>
     *
     * @param employee the employee whose account roles converge; its company is the authorization
     *     scope of the position
     * @return the required, granted and removed sets, each normalized to an unmodifiable sorted set
     * @throws AccountNotLinkedException when the employee has no linked Keycloak account
     * @throws AmbiguousCurrentContractException when more than one enabled contract covers today
     * @throws PositionOutsideEmployeeCompanyException when the position does not resolve to the
     *     employee's company
     */
    @Transactional
    public RoleConvergenceResult convergeRoles(Employee employee) {
        var userId = employee.getKeycloakUserId();
        if (userId == null) {
            throw new AccountNotLinkedException("Account not linked: employee " + employee.getId()
                    + " has no keycloakUserId, so its roles have nowhere to go");
        }

        var required = requiredRoles(employee);

        var clientId = applicationClientProperties.clientId();
        var current = currentRoles(userId, clientId);

        var granted = new TreeSet<>(required);
        granted.removeAll(current);

        var removed = new TreeSet<>(current);
        removed.retainAll(GrantablePositionRoles.names());
        removed.removeAll(required);

        // Removals first, then grants: a transient loss of privilege is fail-closed, a transient
        // excess is not.
        removed.forEach(roleName -> identityProvider.removeRoleFromUser(userId, roleName, RoleScope.CLIENT, clientId));
        granted.forEach(roleName -> identityProvider.assignRoleToUser(userId, roleName, RoleScope.CLIENT, clientId));

        return new RoleConvergenceResult(required, granted, removed);
    }

    private Set<String> requiredRoles(Employee employee) {
        var contracts = contractRepository.findEnabledContractCoveringDate(employee.getId(), LocalDate.now());
        if (contracts.size() > 1) {
            throw new AmbiguousCurrentContractException("Ambiguous current contract for employee " + employee.getId()
                    + ": " + contracts.size() + " enabled contracts cover today; refusing to pick one");
        }
        if (contracts.isEmpty()) {
            return new TreeSet<>();
        }
        return requiredRolesFor(employee, contracts.getFirst());
    }

    private Set<String> requiredRolesFor(Employee employee, Contract contract) {
        if (EmployeeStatuses.isTerminated(employee)) {
            return new TreeSet<>();
        }
        var position = requireOwnedPosition(employee, contract);
        return positionRoleRepository.findByPositionIdOrderByRoleNameAsc(position.getId()).stream()
                .filter(role -> Boolean.TRUE.equals(role.getEnabled()))
                .map(PositionRole::getRoleName)
                .filter(GrantablePositionRoles::includes)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private Position requireOwnedPosition(Employee employee, Contract contract) {
        var position = contract.getPosition();
        var positionCompanyId = companyIdOf(position);
        var employeeCompanyId =
                employee.getCompany() != null ? employee.getCompany().getId() : null;
        if (position == null
                || positionCompanyId == null
                || employeeCompanyId == null
                || !positionCompanyId.equals(employeeCompanyId)) {
            throw new PositionOutsideEmployeeCompanyException("Position of the current contract is outside the company"
                    + " of employee " + employee.getId() + ": refusing to grant its roles");
        }
        return position;
    }

    private UUID companyIdOf(Position position) {
        if (position == null
                || position.getDepartment() == null
                || position.getDepartment().getCompany() == null) {
            return null;
        }
        return position.getDepartment().getCompany().getId();
    }

    private Set<String> currentRoles(String userId, String clientId) {
        return identityProvider.getUserRoles(userId, clientId).stream()
                .filter(Objects::nonNull)
                .map(RoleDto::name)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
