package com.lifecontrol.api.provisioning.service;

import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.provisioning.dto.RoleConvergenceResult;
import com.lifecontrol.api.provisioning.exception.AccountDeactivationNotSupportedException;
import com.lifecontrol.api.provisioning.exception.AccountLinkRefusedException;
import com.lifecontrol.api.provisioning.exception.AccountNotLinkedException;
import com.lifecontrol.api.provisioning.exception.AmbiguousCurrentContractException;
import com.lifecontrol.api.provisioning.exception.CompanyScopeInvariantException;
import com.lifecontrol.api.provisioning.exception.InvitationNotDeliverableException;
import com.lifecontrol.api.provisioning.exception.PositionOutsideEmployeeCompanyException;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

/**
 * The <b>domain half</b> of the access-provisioning worker (unit W4b2a, record T44): the only class
 * that knows an {@link AccessProvisioningTaskKind} and the only one that calls the three capabilities
 * the projection already has — the account lifecycle (W2a), the role convergence (W2b) and the
 * membership projection (W3).
 *
 * <p><b>What this class is not.</b> It is not the pass and it owns no transaction. The mechanics —
 * the bounded tick, the backoff and the ceiling — live in {@code common/worker/}, and the guarded
 * claim plus the two edges live in {@link AccessProvisioningTaskService}; this handler only turns one
 * kind into the calls that kind names. It is deliberately <b>not</b> {@code @Transactional}: each
 * capability it calls is already {@code @Transactional} on its own, and one outer transaction would
 * silently merge three bounded writes into one long one without removing any window the record argues
 * about (record T44's split of mechanics from domain, record T57: every write happens inside the
 * service method that already guards it).</p>
 *
 * <p><b>Why the order is what it is and not a preference.</b> {@code ACTIVATE} runs the account
 * lifecycle <b>first</b>, because roles and membership attributes have nowhere to go until the
 * account exists: {@code convergeRoles} and {@code convergeMembership} both refuse with
 * {@link AccountNotLinkedException} for an employee with no {@code keycloakUserId} (records
 * T35/T44/T59). {@code RECONCILE} converges the two projections a drift would move and deliberately
 * does <b>not</b> touch the account lifecycle: reactivating on a reconcile would re-send an invitation
 * nobody asked for, and the kind's own contract is to re-derive the desired state, not to re-onboard.
 * Nothing is invented beyond those two measured orders.</p>
 *
 * <p><b>{@code DEACTIVATE} fails closed.</b> The capability to disable an account does not exist in
 * {@code IdentityProvider} (record G19), and the alternative the tree allows — removing the roles and
 * the {@code company_*} claims while the account stays enabled — is a silent half-revocation (record
 * O4). This handler therefore throws {@link AccountDeactivationNotSupportedException} <b>before</b>
 * calling anything, so no write happens on the way to the refusal and the task ends {@code FAILED}
 * with a reason the operator can read and retry once the capability lands (records T10/T59).</p>
 *
 * <p><b>Nothing is swallowed.</b> A refusal or failure from any capability propagates unchanged and
 * the steps after it do not run; the caller turns that into the task's {@code last_error} and the
 * {@code FAILED} edge. This class catches nothing, and it does not re-implement the T33 refusal of the
 * account path: {@code activate} already refuses before its first Keycloak write when the company has
 * no usable email domain, so those refusals are declared here rather than duplicated.</p>
 */
@Service
public class AccessProvisioningWorkerHandler {

    private final AccessProvisioningAccountService accountService;
    private final AccessProvisioningRoleService roleService;
    private final AccessProvisioningMembershipService membershipService;

    public AccessProvisioningWorkerHandler(
            AccessProvisioningAccountService accountService,
            AccessProvisioningRoleService roleService,
            AccessProvisioningMembershipService membershipService) {
        this.accountService = accountService;
        this.roleService = roleService;
        this.membershipService = membershipService;
    }

    /**
     * Applies one task of one kind and returns the role names the run <b>touched</b>.
     *
     * <p>The returned set is exactly {@code granted ∪ removed} of the role convergence — what the run
     * changed — and never a "final state" (record T35). That distinction is load-bearing for the most
     * destructive convergence there is: a {@code Terminated} employee requires nothing, so a
     * final-state reading would return an <b>empty</b> set for a run that removed everything, and the
     * applied snapshot would be empty exactly where the history matters. {@code RECONCILE} returns the
     * same union, and a run that touched nothing legitimately returns the empty set: a converged
     * person is an {@code APPLIED} task with zero snapshot rows, not an error.</p>
     *
     * <p>The switch has <b>no {@code default} arm</b> on purpose: it is exhaustive over the enum, so a
     * future kind is a compile error instead of a silent fall-through into the wrong behaviour. That is
     * the convention this module already uses ({@code AccessProvisioningMembershipService.idsAt}).</p>
     *
     * <p>The task remains the caller's: it claims the row, calls this method and hands the returned set
     * to {@link AccessProvisioningTaskService#markApplied(java.util.UUID, java.util.UUID, java.util.Set)},
     * which writes the snapshot in the same transaction as the {@code RUNNING → APPLIED} edge (record
     * T60). The set is an unmodifiable {@link TreeSet}, so the touched names iterate in ascending order
     * and are stable to log; the write side does not depend on that order, because it normalizes
     * again.</p>
     *
     * @param employee the employee the task belongs to
     * @param kind the task's immutable kind (record T3)
     * @return the roles the run touched ({@code granted ∪ removed}); empty when nothing changed
     * @throws AccountDeactivationNotSupportedException for {@code DEACTIVATE}, always: the capability
     *     to disable an account does not exist (record G19) and a partial revocation is refused
     * @throws InvitationNotDeliverableException for {@code ACTIVATE} when the company has no usable
     *     email domain (record T33); declared here because it surfaces from
     *     {@link AccessProvisioningAccountService#activate(Employee)} before its first write
     * @throws AccountLinkRefusedException for {@code ACTIVATE} when an existing account cannot be
     *     linked under the exactly-one-exact-match rule (records T5/T27)
     * @throws AccountNotLinkedException for {@code ACTIVATE} and {@code RECONCILE} when the employee
     *     has no linked Keycloak account
     * @throws AmbiguousCurrentContractException when more than one enabled contract covers today
     * @throws PositionOutsideEmployeeCompanyException when the position does not resolve to the
     *     employee's company
     * @throws CompanyScopeInvariantException when the membership derivation yields anything other than
     *     exactly one {@code company_id}
     */
    public Set<String> apply(Employee employee, AccessProvisioningTaskKind kind) {
        return switch (kind) {
            case ACTIVATE -> {
                // The account lifecycle leads: an employee with no account has nowhere to receive
                // roles or claims, and both later capabilities refuse with AccountNotLinkedException.
                accountService.activate(employee);
                yield converge(employee);
            }
            case RECONCILE -> converge(employee);
            // Not an approximation of a revocation: the account-disable capability does not exist
            // (record G19) and the half that does is refused (record T59). Thrown before any call so
            // no write happens on the way to the failure.
            case DEACTIVATE -> throw new AccountDeactivationNotSupportedException();
        };
    }

    /**
     * Converges the two projections a drift moves — roles first, then membership — and returns the
     * roles the run touched. It is shared by {@code ACTIVATE} and {@code RECONCILE} because both
     * converge the same two projections; only the account lifecycle differs between them.
     */
    private Set<String> converge(Employee employee) {
        var roles = roleService.convergeRoles(employee);
        membershipService.convergeMembership(employee);
        return touched(roles);
    }

    /**
     * The union the snapshot means: what the convergence granted plus what it removed, normalized to an
     * unmodifiable ascending set (record T35).
     */
    private static Set<String> touched(RoleConvergenceResult roles) {
        var touched = new TreeSet<>(roles.granted());
        touched.addAll(roles.removed());
        return Collections.unmodifiableSet(touched);
    }
}
