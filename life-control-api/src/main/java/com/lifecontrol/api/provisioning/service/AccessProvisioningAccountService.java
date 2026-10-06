package com.lifecontrol.api.provisioning.service;

import com.lifecontrol.api.config.invitation.InvitationProperties;
import com.lifecontrol.api.config.security.ApplicationClientProperties;
import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.provisioning.dto.AccountProvisioningResult;
import com.lifecontrol.api.provisioning.exception.AccountLinkRefusedException;
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
 * records T5/T27 — and send the invitation.
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

    private void requireDeliverableCompanyEmailDomain(Employee employee) {
        var company = employee.getCompany();
        var domain = company != null ? company.getEmailDomain() : null;
        if (domain == null || domain.isBlank()) {
            throw new InvitationNotDeliverableException("Invitation not deliverable: no email domain configured"
                    + " for the company of employee " + employee.getId());
        }
    }
}
