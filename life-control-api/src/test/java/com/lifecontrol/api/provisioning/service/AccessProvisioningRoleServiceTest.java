package com.lifecontrol.api.provisioning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.config.security.ApplicationClientProperties;
import com.lifecontrol.api.hr.model.Contract;
import com.lifecontrol.api.hr.model.Department;
import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.hr.model.Position;
import com.lifecontrol.api.hr.model.PositionRole;
import com.lifecontrol.api.hr.repository.ContractRepository;
import com.lifecontrol.api.hr.repository.PositionRoleRepository;
import com.lifecontrol.api.provisioning.exception.AccountNotLinkedException;
import com.lifecontrol.api.provisioning.exception.AmbiguousCurrentContractException;
import com.lifecontrol.api.provisioning.exception.PositionOutsideEmployeeCompanyException;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.usersadmin.identity.IdentityProvider;
import com.lifecontrol.api.usersadmin.identity.IdentityProviderConnectionException;
import com.lifecontrol.api.usersadmin.identity.RoleDto;
import com.lifecontrol.api.usersadmin.identity.RoleScope;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Focused unit tests for {@link AccessProvisioningRoleService}: the required set derived from the
 * current contract (T28/T29/T38), the live diff against Keycloak's own client roles, the apply order
 * and the removal scope (T34/T35/T36/T37).
 *
 * <p>Contract fixtures bracket {@link LocalDate#now()} and the mocked finder applies the same
 * half-open coverage rule the repository query documents, so a wrong date in the service would make a
 * covering contract disappear and fail the test rather than silently pass.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccessProvisioningRoleService Tests")
class AccessProvisioningRoleServiceTest {

    private static final String CLIENT_ID = "life-control-client";
    private static final String KC_USER_ID = "kc-user-1";

    @Mock
    private IdentityProvider identityProvider;

    @Mock
    private ApplicationClientProperties applicationClientProperties;

    @Mock
    private ContractRepository contractRepository;

    @Mock
    private PositionRoleRepository positionRoleRepository;

    @InjectMocks
    private AccessProvisioningRoleService service;

    private UUID employeeId;
    private UUID companyId;
    private UUID positionId;
    private Company company;
    private Department department;
    private Position position;
    private Employee employee;

    @BeforeEach
    void setUp() {
        employeeId = UUID.randomUUID();
        companyId = UUID.randomUUID();
        positionId = UUID.randomUUID();
        company = company(companyId);
        department = Department.builder()
                .id(UUID.randomUUID())
                .company(company)
                .departmentName("Operations")
                .build();
        position = Position.builder()
                .id(positionId)
                .department(department)
                .positionName("Cashier")
                .build();
        employee = linkedEmployee(company);
        lenient().when(applicationClientProperties.clientId()).thenReturn(CLIENT_ID);
    }

    // --- fixtures ---

    private Company company(UUID id) {
        return Company.builder()
                .id(id)
                .companyKey("acme")
                .companyName("Acme")
                .rfc("ACM010101ABC")
                .emailDomain("acme.com")
                .build();
    }

    private Employee linkedEmployee(Company ofCompany) {
        return Employee.builder()
                .id(employeeId)
                .company(ofCompany)
                .email("jane.doe@acme.com")
                .keycloakUserId(KC_USER_ID)
                .build();
    }

    private Employee terminatedEmployee() {
        return Employee.builder()
                .id(employeeId)
                .company(company)
                .email("jane.doe@acme.com")
                .keycloakUserId(KC_USER_ID)
                .status(Status.builder().statusName("Terminated").enabled(true).build())
                .build();
    }

    private Contract contract(Position ofPosition) {
        return Contract.builder()
                .position(ofPosition)
                .startDate(LocalDate.now().minusMonths(1))
                .endDate(null)
                .build();
    }

    private Contract pastContract(Position ofPosition) {
        return Contract.builder()
                .position(ofPosition)
                .startDate(LocalDate.now().minusMonths(3))
                .endDate(LocalDate.now().minusMonths(1))
                .build();
    }

    private PositionRole role(String roleName, boolean enabled) {
        return PositionRole.builder()
                .position(position)
                .roleName(roleName)
                .enabled(enabled)
                .build();
    }

    private RoleDto dto(String name) {
        return new RoleDto(name, null, Boolean.FALSE, RoleScope.CLIENT, CLIENT_ID);
    }

    // --- stubbing ---

    /**
     * Stubs the contract finder to apply the coverage rule the repository query documents
     * ({@code startDate <= date} and ({@code endDate == null || endDate > date})), so the fixture
     * dates are load-bearing instead of decorative.
     */
    private void stubContracts(Contract... contracts) {
        var fixtures = List.of(contracts);
        when(contractRepository.findEnabledContractCoveringDate(eq(employeeId), any(LocalDate.class)))
                .thenAnswer(invocation -> {
                    LocalDate date = invocation.getArgument(1);
                    return fixtures.stream()
                            .filter(c -> !c.getStartDate().isAfter(date))
                            .filter(c ->
                                    c.getEndDate() == null || c.getEndDate().isAfter(date))
                            .toList();
                });
    }

    private void stubTemplate(PositionRole... rows) {
        when(positionRoleRepository.findByPositionIdOrderByRoleNameAsc(positionId))
                .thenReturn(List.of(rows));
    }

    private void stubCurrentRoles(String... names) {
        when(identityProvider.getUserRoles(KC_USER_ID, CLIENT_ID))
                .thenReturn(Arrays.stream(names).map(this::dto).toList());
    }

    @Nested
    @DisplayName("convergeRoles")
    class ConvergeRolesTests {

        @Test
        @DisplayName("grants exactly the required roles that are missing, in ascending order")
        void grantsMissingRequiredRoles() {
            stubContracts(contract(position));
            stubTemplate(role("lc-sales", true), role("lc-company", true));
            stubCurrentRoles();

            var result = service.convergeRoles(employee);

            assertThat(result.required()).containsExactly("lc-company", "lc-sales");
            assertThat(result.granted()).containsExactly("lc-company", "lc-sales");
            assertThat(result.removed()).isEmpty();
            verify(identityProvider).assignRoleToUser(KC_USER_ID, "lc-company", RoleScope.CLIENT, CLIENT_ID);
            verify(identityProvider).assignRoleToUser(KC_USER_ID, "lc-sales", RoleScope.CLIENT, CLIENT_ID);
            verify(identityProvider, never()).removeRoleFromUser(any(), any(), any(), any());
        }

        @Test
        @DisplayName("does not re-grant a required role the account already holds (idempotence)")
        void doesNotRegrantHeldRole() {
            stubContracts(contract(position));
            stubTemplate(role("lc-company", true));
            stubCurrentRoles("lc-company");

            var result = service.convergeRoles(employee);

            assertThat(result.required()).containsExactly("lc-company");
            assertThat(result.granted()).isEmpty();
            assertThat(result.removed()).isEmpty();
            verify(identityProvider).getUserRoles(KC_USER_ID, CLIENT_ID);
            verify(identityProvider, never()).assignRoleToUser(any(), any(), any(), any());
            verify(identityProvider, never()).removeRoleFromUser(any(), any(), any(), any());
        }

        @Test
        @DisplayName("removes a held role inside the grantable universe that is no longer required")
        void removesHeldGrantableRoleNoLongerRequired() {
            stubContracts(contract(position));
            stubTemplate(role("lc-company", true));
            stubCurrentRoles("lc-company", "lc-sales");

            var result = service.convergeRoles(employee);

            assertThat(result.required()).containsExactly("lc-company");
            assertThat(result.granted()).isEmpty();
            assertThat(result.removed()).containsExactly("lc-sales");
            verify(identityProvider).removeRoleFromUser(KC_USER_ID, "lc-sales", RoleScope.CLIENT, CLIENT_ID);
            verify(identityProvider, never()).assignRoleToUser(any(), any(), any(), any());
        }

        @Test
        @DisplayName("never removes a held role outside the grantable universe (lc-admin and a hand-made name)")
        void neverRemovesRoleOutsideGrantableUniverse() {
            stubContracts(contract(position));
            stubTemplate();
            stubCurrentRoles("lc-admin", "hr-manager");

            var result = service.convergeRoles(employee);

            assertThat(result.required()).isEmpty();
            assertThat(result.granted()).isEmpty();
            assertThat(result.removed()).isEmpty();
            verify(identityProvider, never()).removeRoleFromUser(any(), any(), any(), any());
            verify(identityProvider, never()).assignRoleToUser(any(), any(), any(), any());
        }

        @Test
        @DisplayName("a Terminated employee requires nothing: every held grantable role is removed")
        void terminatedEmployeeConvergesToEmptyRequiredSet() {
            stubContracts(contract(position));
            stubCurrentRoles("lc-sales", "lc-company");

            var result = service.convergeRoles(terminatedEmployee());

            assertThat(result.required()).isEmpty();
            assertThat(result.granted()).isEmpty();
            assertThat(result.removed()).containsExactly("lc-company", "lc-sales");
            verify(identityProvider).removeRoleFromUser(KC_USER_ID, "lc-company", RoleScope.CLIENT, CLIENT_ID);
            verify(identityProvider).removeRoleFromUser(KC_USER_ID, "lc-sales", RoleScope.CLIENT, CLIENT_ID);
            verify(identityProvider, never()).assignRoleToUser(any(), any(), any(), any());
            verifyNoInteractions(positionRoleRepository);
        }

        @Test
        @DisplayName("a disabled position role is not required")
        void disabledPositionRoleIsNotRequired() {
            stubContracts(contract(position));
            stubTemplate(role("lc-sales", true), role("lc-company", false));
            stubCurrentRoles();

            var result = service.convergeRoles(employee);

            assertThat(result.required()).containsExactly("lc-sales");
            assertThat(result.granted()).containsExactly("lc-sales");
            verify(identityProvider).assignRoleToUser(KC_USER_ID, "lc-sales", RoleScope.CLIENT, CLIENT_ID);
            verify(identityProvider, never()).assignRoleToUser(KC_USER_ID, "lc-company", RoleScope.CLIENT, CLIENT_ID);
        }

        @Test
        @DisplayName("a position role outside GrantablePositionRoles is never granted")
        void nonGrantablePositionRoleIsNotGranted() {
            stubContracts(contract(position));
            stubTemplate(role("lc-superuser", true), role("lc-admin", true));
            stubCurrentRoles();

            var result = service.convergeRoles(employee);

            assertThat(result.required()).isEmpty();
            assertThat(result.granted()).isEmpty();
            verify(identityProvider, never()).assignRoleToUser(any(), any(), any(), any());
            verify(identityProvider, never()).removeRoleFromUser(any(), any(), any(), any());
        }

        @Test
        @DisplayName("no covering contract yields an empty required set and reads no template")
        void noCoveringContractYieldsEmptyRequiredSet() {
            stubContracts(pastContract(position));
            stubCurrentRoles();

            var result = service.convergeRoles(employee);

            assertThat(result.required()).isEmpty();
            assertThat(result.granted()).isEmpty();
            assertThat(result.removed()).isEmpty();
            verify(contractRepository).findEnabledContractCoveringDate(eq(employeeId), any(LocalDate.class));
            verifyNoInteractions(positionRoleRepository);
            verify(identityProvider, never()).assignRoleToUser(any(), any(), any(), any());
        }

        @Test
        @DisplayName("two covering contracts refuse with AmbiguousCurrentContractException")
        void ambiguousCurrentContractRefuses() {
            stubContracts(contract(position), contract(position));

            assertThatThrownBy(() -> service.convergeRoles(employee))
                    .isInstanceOf(AmbiguousCurrentContractException.class)
                    .hasMessageContaining(employeeId.toString());

            verifyNoInteractions(identityProvider);
            verifyNoInteractions(positionRoleRepository);
        }

        @Test
        @DisplayName("a position of another company refuses with PositionOutsideEmployeeCompanyException")
        void positionOfAnotherCompanyRefuses() {
            var otherCompany = company(UUID.randomUUID());
            var otherDepartment = Department.builder()
                    .id(UUID.randomUUID())
                    .company(otherCompany)
                    .departmentName("Other")
                    .build();
            var otherPosition = Position.builder()
                    .id(UUID.randomUUID())
                    .department(otherDepartment)
                    .positionName("Manager")
                    .build();
            stubContracts(contract(otherPosition));

            assertThatThrownBy(() -> service.convergeRoles(employee))
                    .isInstanceOf(PositionOutsideEmployeeCompanyException.class)
                    .hasMessageContaining(employeeId.toString());

            verifyNoInteractions(identityProvider);
            verifyNoInteractions(positionRoleRepository);
        }

        @Test
        @DisplayName("a broken position chain (no department) refuses rather than throwing NPE")
        void positionWithoutDepartmentRefuses() {
            var orphanPosition = Position.builder()
                    .id(UUID.randomUUID())
                    .positionName("Orphan")
                    .build();
            stubContracts(contract(orphanPosition));

            assertThatThrownBy(() -> service.convergeRoles(employee))
                    .isInstanceOf(PositionOutsideEmployeeCompanyException.class);

            verifyNoInteractions(identityProvider);
            verifyNoInteractions(positionRoleRepository);
        }

        @Test
        @DisplayName("a contract without a position refuses rather than throwing NPE")
        void contractWithoutPositionRefuses() {
            stubContracts(Contract.builder()
                    .position(null)
                    .startDate(LocalDate.now().minusMonths(1))
                    .endDate(null)
                    .build());

            assertThatThrownBy(() -> service.convergeRoles(employee))
                    .isInstanceOf(PositionOutsideEmployeeCompanyException.class);

            verifyNoInteractions(identityProvider);
            verifyNoInteractions(positionRoleRepository);
        }

        @Test
        @DisplayName("an employee without a keycloakUserId refuses and touches nothing")
        void accountNotLinkedRefusesBeforeAnyCall() {
            var unlinked = Employee.builder()
                    .id(employeeId)
                    .company(company)
                    .email("jane.doe@acme.com")
                    .build();

            assertThatThrownBy(() -> service.convergeRoles(unlinked))
                    .isInstanceOf(AccountNotLinkedException.class)
                    .hasMessageContaining(employeeId.toString());

            verifyNoInteractions(identityProvider);
            verifyNoInteractions(contractRepository);
            verifyNoInteractions(positionRoleRepository);
        }

        @Test
        @DisplayName("the result carries required, granted and removed separately and in order")
        void resultCarriesTheThreeSetsSeparately() {
            stubContracts(contract(position));
            stubTemplate(role("lc-receiving", true), role("lc-company", true));
            stubCurrentRoles("lc-sales", "lc-company", "lc-admin");

            var result = service.convergeRoles(employee);

            assertThat(result.required()).containsExactly("lc-company", "lc-receiving");
            assertThat(result.granted()).containsExactly("lc-receiving");
            assertThat(result.removed()).containsExactly("lc-sales");
            verify(identityProvider).assignRoleToUser(KC_USER_ID, "lc-receiving", RoleScope.CLIENT, CLIENT_ID);
            verify(identityProvider).removeRoleFromUser(KC_USER_ID, "lc-sales", RoleScope.CLIENT, CLIENT_ID);
        }

        @Test
        @DisplayName("applies removals before grants")
        void appliesRemovalsBeforeGrants() {
            stubContracts(contract(position));
            stubTemplate(role("lc-company", true), role("lc-receiving", true));
            stubCurrentRoles("lc-sales", "lc-scheduling", "lc-company");

            service.convergeRoles(employee);

            var inOrder = inOrder(identityProvider);
            inOrder.verify(identityProvider).removeRoleFromUser(KC_USER_ID, "lc-sales", RoleScope.CLIENT, CLIENT_ID);
            inOrder.verify(identityProvider)
                    .removeRoleFromUser(KC_USER_ID, "lc-scheduling", RoleScope.CLIENT, CLIENT_ID);
            inOrder.verify(identityProvider).assignRoleToUser(KC_USER_ID, "lc-receiving", RoleScope.CLIENT, CLIENT_ID);
        }

        @Test
        @DisplayName("an IdentityProviderConnectionException from a grant propagates unchanged")
        void identityProviderFailurePropagates() {
            stubContracts(contract(position));
            stubTemplate(role("lc-sales", true));
            stubCurrentRoles();
            var boom = new IdentityProviderConnectionException("keycloak is unreachable");
            doThrow(boom).when(identityProvider).assignRoleToUser(KC_USER_ID, "lc-sales", RoleScope.CLIENT, CLIENT_ID);

            assertThatThrownBy(() -> service.convergeRoles(employee)).isSameAs(boom);
        }

        @Test
        @DisplayName("null role names read from the identity provider are dropped")
        void nullCurrentRoleNamesAreDropped() {
            stubContracts(contract(position));
            stubTemplate(role("lc-sales", true));
            when(identityProvider.getUserRoles(KC_USER_ID, CLIENT_ID))
                    .thenReturn(Arrays.asList(dto(null), dto("lc-sales")));

            var result = service.convergeRoles(employee);

            assertThat(result.removed()).isEmpty();
            assertThat(result.granted()).isEmpty();
            verify(identityProvider, never()).removeRoleFromUser(any(), any(), any(), any());
        }
    }
}
