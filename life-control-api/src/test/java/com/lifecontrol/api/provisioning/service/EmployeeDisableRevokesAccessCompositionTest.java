package com.lifecontrol.api.provisioning.service;

import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind.DEACTIVATE;
import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind.RECONCILE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.common.security.ScopeLevel;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.config.invitation.InvitationProperties;
import com.lifecontrol.api.config.security.ApplicationClientProperties;
import com.lifecontrol.api.hr.model.Contract;
import com.lifecontrol.api.hr.model.Department;
import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.hr.model.Position;
import com.lifecontrol.api.hr.model.PositionRole;
import com.lifecontrol.api.hr.repository.ContractRepository;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.hr.repository.EmployeeScopeAssignmentRepository;
import com.lifecontrol.api.hr.repository.PositionRoleRepository;
import com.lifecontrol.api.hr.service.EmployeeStatuses;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.usersadmin.identity.InMemoryIdentityProvider;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Independent probes for odd/tasks/employee-disable-revokes-access (S1, S3, S6, S9, S8, S1 boundary). */
@ExtendWith(MockitoExtension.class)
class EmployeeDisableRevokesAccessCompositionTest {

    private static final String CLIENT_ID = "life-control-client";
    private static final String EMAIL = "probe.doe@acme.com";
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
    private EmployeeScopeAssignmentRepository scopeAssignmentRepository;

    private InMemoryIdentityProvider identityProvider;
    private AccessProvisioningAccountService accountService;
    private AccessProvisioningRoleService roleService;
    private AccessProvisioningWorkerHandler handler;
    private UUID employeeId;
    private Company company;

    @BeforeEach
    void setUp() {
        identityProvider = new InMemoryIdentityProvider();
        var appClient = new ApplicationClientProperties(CLIENT_ID);
        var invitation = new InvitationProperties("https://app.example/activate", 43200);
        accountService =
                new AccessProvisioningAccountService(identityProvider, employeeRepository, appClient, invitation);
        roleService = new AccessProvisioningRoleService(
                identityProvider, appClient, contractRepository, positionRoleRepository);
        var membershipService = new AccessProvisioningMembershipService(identityProvider, scopeAssignmentRepository);
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

    private Status active() {
        return Status.builder().statusName("Active").enabled(true).build();
    }

    private Employee employee(boolean enabled, String keycloakUserId) {
        return Employee.builder()
                .id(employeeId)
                .company(company)
                .email(EMAIL)
                .keycloakUserId(keycloakUserId)
                .status(active())
                .enabled(enabled)
                .build();
    }

    /** A covering contract whose position template still requires lc-sales: the exact G41 shape. */
    private void stubCoveringContractWithSales() {
        var department = Department.builder()
                .id(UUID.randomUUID())
                .company(company)
                .departmentName("Operations")
                .build();
        var position = Position.builder()
                .id(UUID.randomUUID())
                .department(department)
                .positionName("Cashier")
                .build();
        when(contractRepository.findEnabledContractCoveringDate(eq(employeeId), any(LocalDate.class)))
                .thenReturn(List.of(Contract.builder()
                        .position(position)
                        .startDate(LocalDate.now().minusMonths(1))
                        .build()));
        lenient()
                .when(positionRoleRepository.findByPositionIdOrderByRoleNameAsc(eq(position.getId())))
                .thenReturn(List.of(PositionRole.builder()
                        .position(position)
                        .roleName("lc-sales")
                        .enabled(true)
                        .build()));
    }

    private String provisionedAccount() {
        var userId = identityProvider.createAccount(EMAIL, EMAIL);
        identityProvider.seedClientRole(userId, CLIENT_ID, "lc-sales");
        identityProvider.seedClientRole(userId, CLIENT_ID, "lc-company");
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
        return userId;
    }

    // P1 (S1+S9+S6): disabled (not Terminated) + covering contract: the DEACTIVATE removes the
    // template role even though the contract still covers today, and deletes all five claims.
    @Test
    @DisplayName("P1: DEACTIVATE of a soft-deleted employee removes roles and claims despite a covering contract")
    void p1() {
        var userId = provisionedAccount();
        stubCoveringContractWithSales();
        var disabled = employee(false, userId);

        var touched = handler.apply(disabled, DEACTIVATE);

        assertThat(identityProvider.isEnabled(userId)).isFalse();
        assertThat(identityProvider.roleNames(userId, CLIENT_ID)).isEmpty();
        assertThat(identityProvider.attributes(userId))
                .doesNotContainKeys(COMPANY_CLAIM, COUNTRY_CLAIM, REGION_CLAIM, ZONE_CLAIM, STORE_CLAIM);
        assertThat(touched).containsExactlyInAnyOrder("lc-sales", "lc-company");
    }

    // P1 control: the same fixture with enabled=true keeps the template role, so P1 is sensitive.
    @Test
    @DisplayName("P1-control: an enabled employee keeps the template role the contract requires")
    void p1Control() {
        var userId = provisionedAccount();
        stubCoveringContractWithSales();
        var activeEmployee = employee(true, userId);

        handler.apply(activeEmployee, DEACTIVATE);

        assertThat(identityProvider.roleNames(userId, CLIENT_ID)).contains("lc-sales");
    }

    // P2 (S6): a RECONCILE reaching a revoked employee must not re-enable the account.
    @Test
    @DisplayName("P2: RECONCILE never re-enables the account of a soft-deleted employee")
    void p2() {
        var userId = provisionedAccount();
        identityProvider.disableUser(userId);
        stubCoveringContractWithSales();
        var disabled = employee(false, userId);

        handler.apply(disabled, RECONCILE);

        assertThat(identityProvider.isEnabled(userId)).isFalse();
    }

    // P3 (S3): a re-enabled (enabled=true) employee's RECONCILE restores the account and roles.
    @Test
    @DisplayName("P3: RECONCILE for a re-enabled employee re-enables the account and restores the roles")
    void p3() {
        var userId = provisionedAccount();
        identityProvider.disableUser(userId);
        identityProvider.removeRoleFromUser(
                userId, "lc-company", com.lifecontrol.api.usersadmin.identity.RoleScope.CLIENT, CLIENT_ID);
        stubCoveringContractWithSales();
        var restored = employee(true, userId);

        handler.apply(restored, RECONCILE);

        assertThat(identityProvider.isEnabled(userId)).isTrue();
        assertThat(identityProvider.roleNames(userId, CLIENT_ID)).contains("lc-sales");
    }

    // P4 (S1 boundary): the rule itself.
    @Test
    @DisplayName("P4: isRevoked boundaries — null employee, null enabled, disabled, terminated")
    void p4() {
        assertThat(EmployeeStatuses.isRevoked(null)).isFalse();
        assertThat(EmployeeStatuses.isRevoked(
                        Employee.builder().id(employeeId).company(company).build()))
                .isFalse();
        assertThat(EmployeeStatuses.isRevoked(employee(false, null))).isTrue();
        assertThat(EmployeeStatuses.isRevoked(Employee.builder()
                        .id(employeeId)
                        .company(company)
                        .status(Status.builder()
                                .statusName("Terminated")
                                .enabled(true)
                                .build())
                        .enabled(true)
                        .build()))
                .isTrue();
    }

    // P5 (S1 read path): requiredRoles is empty for a revoked employee with a covering contract.
    @Test
    @DisplayName("P5: requiredRoles is empty for a soft-deleted employee whose contract covers today")
    void p5() {
        stubCoveringContractWithSales();

        assertThat(roleService.requiredRoles(employee(false, null))).isEmpty();
        assertThat(roleService.requiredRoles(employee(true, null))).containsExactly("lc-sales");
    }
}
