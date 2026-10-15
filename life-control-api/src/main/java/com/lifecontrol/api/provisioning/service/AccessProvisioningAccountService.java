package com.lifecontrol.api.provisioning.service;

import com.lifecontrol.api.config.invitation.InvitationProperties;
import com.lifecontrol.api.config.security.ApplicationClientProperties;
import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.hr.service.EmployeeStatuses;
import com.lifecontrol.api.provisioning.dto.AccountProvisioningResult;
import com.lifecontrol.api.provisioning.exception.AccountLinkRefusedException;
import com.lifecontrol.api.provisioning.exception.AccountNotLinkedException;
import com.lifecontrol.api.provisioning.exception.InvitationNotDeliverableException;
import com.lifecontrol.api.provisioning.model.AccountProvisioningOutcome;
import com.lifecontrol.api.usersadmin.identity.IdentityProvider;
import com.lifecontrol.api.usersadmin.identity.IdentityProviderConflictException;
import java.util.List;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The <b>account lifecycle</b> of the access-provisioning projection (unit W2a): ensure the Keycloak
 * account of an employee exists — create it, or link an existing one under the strict rule of
 * records T5/T27 — send the invitation, and disable the account on revocation. The last one is the
 * capability gap G19 owed (records T77/T78): a single read-modify-write of the {@code enabled} flag,
 * never {@code updateUser}'s whole-representation write and never {@code deleteUser} (record T6 keeps
 * the {@code sub} stable).
 *
 * <p>It is a capability with no endpoint, no scheduler, no migration and no task write: its caller is
 * the access-provisioning worker (W4). It deliberately does <b>not</b> call
 * {@code verifyCompanyAccess}: the worker has no current user, and W5's endpoints resolve the company
 * first (the precedent W1b set). It also performs no Keycloak existence check beyond resolving a
 * {@code 409} from {@link IdentityProvider#createUser(UserRepresentation)}.</p>
 *
 * <p>The account is built through the {@link IdentityProvider} port and never through
 * {@code UsersAdminService} (record T31): no credential is ever set, so no secret this application
 * could lose is created, and the invitation email is the only way in. The link decision stays
 * <b>here</b> and not in the adapter: {@link IdentityProvider#findUsersByEmail(String)} returns the
 * accounts the identity provider found, and this class links only when exactly one of them carries an
 * email equal to the employee's frozen address (case-insensitive). Zero or more than one is a
 * refusal, never a pick — the account that receives roles must be the account of <em>this</em>
 * person.</p>
 */
@Service
public class AccessProvisioningAccountService {

    /**
     * The actions the person must complete, in order: verify the address the invitation was sent to,
     * then set their own password (records O1/T14/T31). This is the only way into the account, so
     * there is deliberately no credential anywhere in this flow.
     */
    private static final List<String> REQUIRED_ACTIONS = List.of("VERIFY_EMAIL", "UPDATE_PASSWORD");

    private final IdentityProvider identityProvider;
    private final EmployeeRepository employeeRepository;
    private final ApplicationClientProperties applicationClientProperties;
    private final InvitationProperties invitationProperties;

    public AccessProvisioningAccountService(
            IdentityProvider identityProvider,
            EmployeeRepository employeeRepository,
            ApplicationClientProperties applicationClientProperties,
            InvitationProperties invitationProperties) {
        this.identityProvider = identityProvider;
        this.employeeRepository = employeeRepository;
        this.applicationClientProperties = applicationClientProperties;
        this.invitationProperties = invitationProperties;
    }

    /**
     * Ensures the employee has a Keycloak account and persists the resolved id.
     *
     * <ol>
     *   <li>An employee that already carries a {@code keycloakUserId} is returned as
     *       {@link AccountProvisioningOutcome#ALREADY_LINKED} — no identity-provider call and no
     *       write, so a retry of an applied task is a no-op (record T4).</li>
     *   <li>Otherwise an account is created with {@code username = email}, {@code email = email},
     *       {@code enabled = true}, {@code emailVerified = false}, the two required actions and
     *       <b>no credential at all</b> (record T31).</li>
     *   <li>A {@code 409} from Keycloak (the address already has an account) is resolved through
     *       {@link IdentityProvider#findUsersByEmail(String)}: the account is linked only when
     *       exactly one returned account carries the employee's frozen email
     *       (case-insensitive); zero or more than one throws
     *       {@link AccountLinkRefusedException} and picks nothing (records T5/T27).</li>
     *   <li>The resolved id is written to {@code employee.keycloakUserId} and saved.</li>
     * </ol>
     *
     * @throws AccountLinkRefusedException when the address is taken but the link rule is not
     *     satisfied
     */
    @Transactional
    public AccountProvisioningResult ensureAccount(Employee employee) {
        var existing = employee.getKeycloakUserId();
        if (existing != null) {
            return new AccountProvisioningResult(existing, AccountProvisioningOutcome.ALREADY_LINKED);
        }

        var result = createOrLink(employee.getEmail());
        employee.linkKeycloakUser(result.keycloakUserId());
        employeeRepository.save(employee);
        return result;
    }

    /**
     * Ensures the account and then sends the invitation through the identity provider's own
     * execute-actions flow.
     *
     * <p>The refusal of record T33 comes <b>first</b>: when the employee's company has no usable
     * {@code email_domain} (null or blank) this throws {@link InvitationNotDeliverableException}
     * <b>before</b> anything is created in Keycloak, so the failure cannot leave an account for a
     * person with no way in. The message names the reason and is usable as a task's
     * {@code last_error}.</p>
     *
     * <p>The identity provider call carries the flow's parameters explicitly (record T21):
     * {@code clientId} from {@link ApplicationClientProperties}, and the redirect and lifespan from
     * {@link InvitationProperties}. The actions are exactly {@link #REQUIRED_ACTIONS}, in that
     * order.</p>
     *
     * @throws InvitationNotDeliverableException when the company has no usable email domain
     * @throws AccountLinkRefusedException when the address is taken but the link rule is not
     *     satisfied
     */
    @Transactional
    public AccountProvisioningResult activate(Employee employee) {
        requireDeliverableCompanyEmailDomain(employee);

        var result = ensureAccount(employee);
        identityProvider.sendActionsEmail(
                result.keycloakUserId(),
                applicationClientProperties.clientId(),
                invitationProperties.redirectUri(),
                invitationProperties.lifespanSeconds(),
                REQUIRED_ACTIONS);
        return result;
    }

    /**
     * Disables the employee's Keycloak account, so it can no longer authenticate — the account half of
     * a {@code DEACTIVATE} revocation (records O4/T77/T78).
     *
     * <p>It calls exactly one port method, {@link IdentityProvider#disableUser(String)}, and nothing
     * else: not {@code deleteUser} (record T6 keeps the {@code sub} stable so the person's
     * {@code activity_logs} are not orphaned and a rehire re-links the same account), not
     * {@code updateUser} (its whole-representation write can clobber fields nobody meant to touch —
     * record T77/G19), and no re-enable. The roles and the five {@code company_*} claims are removed by
     * the caller's own convergence, which is why this method does not touch them: one instruction, one
     * port call.</p>
     *
     * <p>It deliberately does <b>not</b> call {@code verifyCompanyAccess}: the worker has no current
     * user, exactly as this class' other capabilities declare (the precedent W1b set).</p>
     *
     * <p>The return type is {@code void} on purpose: the account half of a revocation has no outcome to
     * report. It either disabled the account or the port threw, so a result enum would carry exactly
     * one reachable value.</p>
     *
     * @param employee the employee whose account is disabled; it must already be linked
     * @throws AccountNotLinkedException when the employee has no linked Keycloak account, the same
     *     refusal W2b and W3 make and for the same reason: the write has nowhere to go
     */
    @Transactional
    public void deactivate(Employee employee) {
        var userId = employee.getKeycloakUserId();
        if (userId == null || userId.isBlank()) {
            throw new AccountNotLinkedException("Account not linked: employee " + employee.getId()
                    + " has no keycloakUserId, so its account cannot be disabled");
        }
        identityProvider.disableUser(userId);
    }

    /**
     * Ensures the account of a <b>non-revoked</b> employee is enabled, and leaves a revoked employee's
     * account exactly as it is.
     *
     * <p>This is the convergence half of gap G36 (records T99/T100): a rehired person stays linked —
     * the revocation is {@code disableUser} and never a delete (record T6) — and this is what turns the
     * account back on, which no pass performed before. It is deliberately narrow in one direction only:
     * the <b>disable</b> direction belongs to {@code AccessProvisioningWorkerHandler}'s
     * {@code DEACTIVATE} arm, which runs it first, so a terminated employee is a no-op here instead of a
     * second write of the same fact.</p>
     *
     * <p>It deliberately does <b>not</b> call {@code verifyCompanyAccess}: the worker has no current
     * user, exactly as this class' other capabilities declare (the precedent W1b set).</p>
     *
     * @param employee the employee whose account state is converged; it must already be linked when the
     *     employee is not revoked
     * @throws AccountNotLinkedException when a non-revoked employee has no linked Keycloak account, the
     *     same refusal its siblings make and for the same reason: the write has nowhere to go
     */
    @Transactional
    public void ensureAccountEnabled(Employee employee) {
        if (EmployeeStatuses.isTerminated(employee)) {
            return;
        }
        var userId = employee.getKeycloakUserId();
        if (userId == null || userId.isBlank()) {
            throw new AccountNotLinkedException("Account not linked: employee " + employee.getId()
                    + " has no keycloakUserId, so its account cannot be enabled");
        }
        identityProvider.enableUser(userId);
    }

    /**
     * Re-sends the invitation for an employee whose account is already linked (record T69): the
     * consequence O1 accepted when it chose the emailed activation link — the link expires, so the
     * screen needs a way to send another one.
     *
     * <p><b>It re-uses the same frozen flow the activation path uses</b>: {@link #REQUIRED_ACTIONS} in
     * that order, the client from {@link ApplicationClientProperties} and the redirect and lifespan
     * from {@link InvitationProperties} (records T21/T30). A resend that invented its own actions or
     * its own lifespan would be a second invitation rule, which is the defect class this record keeps
     * recording.</p>
     *
     * <p><b>Two refusals, and the order is declared rather than accidental.</b> An employee with no
     * linked account is refused first with {@link AccountNotLinkedException}: "is there anything to
     * send to" is the more specific defect, and the message names it. Then the deliverable-domain
     * check of record T33, taken from this class' own
     * {@link #requireDeliverableCompanyEmailDomain(Employee)}, so an undeliverable address is refused
     * <b>before</b> the only Keycloak call the method makes. Both answer 409 on the wire (record T86).</p>
     *
     * <p>It writes <b>no</b> local row and creates nothing: it is a send, not a convergence. The
     * account, its roles and its claim attributes are left exactly as they were — a resent link grants
     * nothing that was not already granted.</p>
     *
     * @param employee the employee whose invitation is re-sent; its account must already be linked
     * @throws AccountNotLinkedException when the employee has no {@code keycloakUserId}
     * @throws InvitationNotDeliverableException when the company has no usable {@code email_domain}
     */
    @Transactional
    public void resendInvitation(Employee employee) {
        var userId = employee.getKeycloakUserId();
        if (userId == null || userId.isBlank()) {
            throw new AccountNotLinkedException("Account not linked: employee " + employee.getId()
                    + " has no keycloakUserId, so no invitation can be re-sent");
        }
        requireDeliverableCompanyEmailDomain(employee);

        identityProvider.sendActionsEmail(
                userId,
                applicationClientProperties.clientId(),
                invitationProperties.redirectUri(),
                invitationProperties.lifespanSeconds(),
                REQUIRED_ACTIONS);
    }

    private AccountProvisioningResult createOrLink(String email) {
        try {
            var created = identityProvider.createUser(accountFor(email));
            return new AccountProvisioningResult(created, AccountProvisioningOutcome.CREATED);
        } catch (IdentityProviderConflictException e) {
            return new AccountProvisioningResult(resolveExistingAccount(email), AccountProvisioningOutcome.LINKED);
        }
    }

    private UserRepresentation accountFor(String email) {
        var user = new UserRepresentation();
        user.setUsername(email);
        user.setEmail(email);
        user.setEnabled(true);
        user.setEmailVerified(false);
        user.setRequiredActions(REQUIRED_ACTIONS);
        // No credential is ever set: the account has no usable password, and the invitation is the
        // only way in (record T31). Reusing UsersAdminService.createUser would generate and discard a
        // secret, which is exactly the defect O1/T31 remove.
        return user;
    }

    private String resolveExistingAccount(String email) {
        var matches = identityProvider.findUsersByEmail(email).stream()
                .filter(candidate -> email != null && email.equalsIgnoreCase(candidate.email()))
                .toList();
        if (matches.size() != 1) {
            throw new AccountLinkRefusedException("Refusing to link the Keycloak account for " + email
                    + ": the address is already taken and exactly one account with an exact email match is"
                    + " required, but " + matches.size() + " matched");
        }
        return matches.getFirst().id();
    }

    /**
     * Refuses when the employee's company carries no usable {@code email_domain} (record T33), so no
     * account is created for a person with no way in.
     *
     * <p><b>Package-private rather than private on purpose</b> (record T85): this is the single
     * definition of "deliverable address" in the module, and the explicit request surface
     * ({@link AccessProvisioningRequestService#apply(java.util.UUID, java.util.UUID)}) has to refuse
     * with the same rule <b>before</b> it creates a task — the operator sees which path applies
     * instead of discovering it from a bounce (record T68). A second copy of the predicate is the
     * defect class G30 records: one rule, two implementations, one of them stale.</p>
     */
    void requireDeliverableCompanyEmailDomain(Employee employee) {
        var company = employee.getCompany();
        var domain = company != null ? company.getEmailDomain() : null;
        if (domain == null || domain.isBlank()) {
            throw new InvitationNotDeliverableException("Invitation not deliverable: no email domain configured"
                    + " for the company of employee " + employee.getId());
        }
    }
}
