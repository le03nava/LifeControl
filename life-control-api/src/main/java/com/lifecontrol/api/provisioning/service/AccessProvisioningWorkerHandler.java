package com.lifecontrol.api.provisioning.service;

import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.provisioning.dto.RoleConvergenceResult;
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
 * T35/T44/T59). {@code RECONCILE} converges the projections a drift moves and deliberately does
 * <b>not</b> invite and does <b>not</b> disable: re-inviting on a reconcile would send an invitation
 * nobody asked for, and the kind's own contract is to re-derive the desired state, not to re-onboard.
 * What it <b>does</b> converge is the account's {@code enabled} flag (record T99): a rehired employee
 * stays linked, so their contract start is a {@code RECONCILE}, and without that direction the account
 * would stay disabled with roles and claims restored on it. Nothing is invented beyond those measured
 * orders.</p>
 *
 * <p><b>{@code DEACTIVATE} disables the account first, then converges.</b> The order is not a
 * preference: a disabled account cannot authenticate, so "disabled with roles still present" is inert,
 * while the reverse — removing the roles and the {@code company_*} claims while the account stays
 * enabled — is the silent half-revocation record O4 refuses. Every failure after the disable leaves
 * the task {@code FAILED} and retriable with the partial state on the safe side (records T78/G37).
 * The capability itself is {@link AccessProvisioningAccountService#deactivate(Employee)}; the removal
 * half is the existing convergence, because a {@code Terminated} employee requires no roles and
 * derives the empty scope.</p>
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
     * @throws InvitationNotDeliverableException for {@code ACTIVATE} when the company has no usable
     *     email domain (record T33); declared here because it surfaces from
     *     {@link AccessProvisioningAccountService#activate(Employee)} before its first write
     * @throws AccountLinkRefusedException for {@code ACTIVATE} when an existing account cannot be
     *     linked under the exactly-one-exact-match rule (records T5/T27)
     * @throws AccountNotLinkedException for {@code ACTIVATE}, {@code RECONCILE} and
     *     {@code DEACTIVATE} when the employee has no linked Keycloak account
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
            // Disable first, then converge: a disabled account cannot authenticate, so "disabled with
            // roles still present" is inert, while the reverse is the half-revocation record O4
            // refuses (record T78). A failure after the disable leaves the task FAILED and retriable
            // with the partial state on the safe side.
            case DEACTIVATE -> {
                accountService.deactivate(employee);
                yield converge(employee);
            }
        };
    }

    /**
     * Converges the three projections a drift moves — roles, then membership, then the account's
     * {@code enabled} state — and returns the roles the run touched. It is shared by all three kinds,
     * because the three converge the same projections; only the <b>disable</b> direction differs, and it
     * belongs to the {@code DEACTIVATE} arm.
     *
     * <p><b>Why the account step is last.</b> The step that opens the door runs after the steps that
     * decide what is behind it: when the role or the membership convergence fails, the account is left
     * disabled and the task {@code FAILED} and retriable, which is the fail-safe side of the pair. That
     * is the mirror of record T78's disable-first rule for the other direction, and the reason a revoked
     * employee is a no-op there instead of a second write of the same fact
     * ({@link AccessProvisioningAccountService#ensureAccountEnabled(Employee)}).</p>
     */
    private Set<String> converge(Employee employee) {
        var roles = roleService.convergeRoles(employee);
        membershipService.convergeMembership(employee);
        accountService.ensureAccountEnabled(employee);
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
