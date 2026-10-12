package com.lifecontrol.api.provisioning.service;

import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind.ACTIVATE;
import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind.DEACTIVATE;
import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind.RECONCILE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.common.security.ScopeLevel;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.config.invitation.InvitationProperties;
import com.lifecontrol.api.config.security.ApplicationClientProperties;
import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.hr.repository.ContractRepository;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.hr.repository.EmployeeStoreAssignmentRepository;
import com.lifecontrol.api.hr.repository.PositionRoleRepository;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.usersadmin.identity.InMemoryIdentityProvider;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Composition proof for the {@code DEACTIVATE} execution (record T78, narrowing gap G23): the
 * <b>real</b> {@link AccessProvisioningAccountService}, {@link AccessProvisioningRoleService},
 * {@link AccessProvisioningMembershipService} and {@link AccessProvisioningWorkerHandler} run over the
 * <b>real</b> {@link com.lifecontrol.api.usersadmin.identity.IdentityProvider} contract, with the
 * test-scoped {@link InMemoryIdentityProvider} standing in for Keycloak.
 *
 * <p><b>What is real and what is substituted, exactly.</b> Real: the three provisioning services, the
 * handler, and the port contract they call through. Substituted: the <b>persistence layer</b> — the
 * four repositories ({@code EmployeeRepository}, {@code ContractRepository},
 * {@code PositionRoleRepository}, {@code EmployeeStoreAssignmentRepository}) are Mockito mocks, and no
 * database or Spring context is involved. The two configuration records
 * ({@code ApplicationClientProperties}, {@code InvitationProperties}) are constructed with literals,
 * not mocked. So this test proves the <b>dispatch reaches the right capabilities and that their writes
 * land in the identity provider</b>; it does <b>not</b> prove anything about JPA, transactions or the
 * schema — those belong to the integration suites.</p>
 *
 * <p>Because the fake holds real state (enabled flag, attributes, per-client roles), every assertion
 * here reads the <b>state</b> the run produced rather than a Mockito argument capture: that is what
 * turns "the pass calls the right things" from argument evidence into state evidence (record T79).</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccessProvisioningWorkerHandler DEACTIVATE composition over the real services")
class AccessProvisioningWorkerDeactivateCompositionTest {

    private static final String CLIENT_ID = "life-control-client";
    private static final String REDIRECT_URI = "https://app.lifecontrol.example/activate";
    private static final int LIFESPAN_SECONDS = 43200;
    private static final String EMAIL = "jane.doe@acme.com";

    private static final String COMPANY_CLAIM = ScopeLevel.COMPANY.claim();
    private static final String COUNTRY_CLAIM = ScopeLevel.COUNTRY.claim();
    private static final String REGION_CLAIM = ScopeLevel.REGION.claim();
    private static final String ZONE_CLAIM = ScopeLevel.ZONE.claim();
    private static final String STORE_CLAIM = ScopeLevel.STORE.claim();

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private ContractRepository contractRepository;

    @Mock
    private PositionRoleRepository positionRoleRepository;

    @Mock
    private EmployeeStoreAssignmentRepository assignmentRepository;

    private InMemoryIdentityProvider identityProvider;
    private AccessProvisioningWorkerHandler handler;

    private UUID employeeId;
    private Company company;

    @BeforeEach
    void setUp() {
        identityProvider = new InMemoryIdentityProvider();
        var applicationClientProperties = new ApplicationClientProperties(CLIENT_ID);
        var invitationProperties = new InvitationProperties(REDIRECT_URI, LIFESPAN_SECONDS);

        var accountService = new AccessProvisioningAccountService(
                identityProvider, employeeRepository, applicationClientProperties, invitationProperties);
        var roleService = new AccessProvisioningRoleService(
                identityProvider, applicationClientProperties, contractRepository, positionRoleRepository);
        var membershipService = new AccessProvisioningMembershipService(identityProvider, assignmentRepository);
        handler = new AccessProvisioningWorkerHandler(accountService, roleService, membershipService);

        employeeId = UUID.randomUUID();
        company = Company.builder()
                .id(UUID.randomUUID())
                .companyKey("acme")
                .companyName("Acme")
                .rfc("ACM010101ABC")
                .emailDomain("acme.com")
                .build();
    }

    private Employee employee(Status status, String keycloakUserId) {
        var builder =
                Employee.builder().id(employeeId).company(company).email(EMAIL).keycloakUserId(keycloakUserId);
        if (status != null) {
            builder.status(status);
        }
        return builder.build();
    }

    private Status terminated() {
        return Status.builder().statusName("Terminated").enabled(true).build();
    }

    /** The repository returns no covering contract, so the required set derives to empty. */
    private void stubNoCurrentContract() {
        when(contractRepository.findEnabledContractCoveringDate(eq(employeeId), any(LocalDate.class)))
                .thenReturn(List.of());
    }

    /** The repository returns no covering assignment, so only the employee's own company derives. */
    private void stubNoAssignments() {
        when(assignmentRepository.findEnabledAssignmentsCoveringDate(eq(employeeId), any(LocalDate.class)))
                .thenReturn(List.of());
    }

    @Nested
    @DisplayName("DEACTIVATE")
    class Deactivate {

        @Test
        @DisplayName("disables the account, removes the template roles and deletes all five claims")
        void revokesTheAccountAndBothProjections() {
            // A fully provisioned person: linked account, template roles held, five claims written.
            var userId = identityProvider.createAccount(EMAIL, EMAIL);
            var employee = employee(terminated(), userId);
            identityProvider.seedClientRole(userId, CLIENT_ID, "lc-sales");
            identityProvider.seedClientRole(userId, CLIENT_ID, "lc-scheduling");
            // Outside the grantable universe, so the convergence must leave it untouched (record T34).
            identityProvider.seedClientRole(userId, CLIENT_ID, "lc-admin");
            identityProvider.seedAttribute(
                    userId, COMPANY_CLAIM, List.of(UUID.randomUUID().toString()));
            identityProvider.seedAttribute(
                    userId, COUNTRY_CLAIM, List.of(UUID.randomUUID().toString()));
            identityProvider.seedAttribute(
                    userId, REGION_CLAIM, List.of(UUID.randomUUID().toString()));
            identityProvider.seedAttribute(
                    userId, ZONE_CLAIM, List.of(UUID.randomUUID().toString()));
            identityProvider.seedAttribute(
                    userId, STORE_CLAIM, List.of(UUID.randomUUID().toString()));
            stubNoCurrentContract();

            var touched = handler.apply(employee, DEACTIVATE);

            assertThat(identityProvider.isEnabled(userId)).isFalse();
            assertThat(identityProvider.roleNames(userId, CLIENT_ID)).containsExactly("lc-admin");
            assertThat(identityProvider.attributes(userId))
                    .doesNotContainKeys(COMPANY_CLAIM, COUNTRY_CLAIM, REGION_CLAIM, ZONE_CLAIM, STORE_CLAIM);
            assertThat(touched).containsExactlyInAnyOrder("lc-sales", "lc-scheduling");
        }
    }

    @Nested
    @DisplayName("ACTIVATE")
    class Activate {

        @Test
        @DisplayName("reaches the account lifecycle, the roles and the membership for a linked employee")
        void dispatchesAllThreeCapabilities() {
            var userId = identityProvider.createAccount(EMAIL, EMAIL);
            var employee = employee(null, userId);
            stubNoCurrentContract();
            stubNoAssignments();

            var touched = handler.apply(employee, ACTIVATE);

            assertThat(identityProvider.actionsEmailRecipients()).containsExactly(userId);
            assertThat(identityProvider.roleNames(userId, CLIENT_ID)).isEmpty();
            assertThat(identityProvider.attributes(userId)).containsKey(COMPANY_CLAIM);
            assertThat(touched).isEmpty();
        }
    }

    @Nested
    @DisplayName("RECONCILE")
    class Reconcile {

        @Test
        @DisplayName("converges roles and membership but never touches the account lifecycle")
        void doesNotReinviteOnAReconcile() {
            var userId = identityProvider.createAccount(EMAIL, EMAIL);
            var employee = employee(null, userId);
            stubNoCurrentContract();
            stubNoAssignments();

            var touched = handler.apply(employee, RECONCILE);

            assertThat(identityProvider.actionsEmailRecipients()).isEmpty();
            assertThat(identityProvider.attributes(userId)).containsKey(COMPANY_CLAIM);
            assertThat(touched).isEmpty();
        }
    }
}
