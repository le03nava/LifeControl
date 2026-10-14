package com.lifecontrol.api.provisioning.service;

import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind.ACTIVATE;
import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind.DEACTIVATE;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.hr.exception.EmployeeNotFoundException;
import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.provisioning.exception.InvitationNotDeliverableException;
import com.lifecontrol.api.provisioning.exception.TerminatedEmployeeRefusedException;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.status.model.StatusType;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

/**
 * Focused unit tests for {@link AccessProvisioningRequestService}: the two explicit intents the
 * operator's surface owns, and the order of the refusals that precede them.
 *
 * <p>What is pinned here is <b>not</b> the task the gate creates — that is
 * {@link AccessProvisioningGateServiceTest}'s subject — but the three things this class is
 * responsible for: the company check as the first statement, the two refusals that have to happen
 * <b>before</b> any task is requested (the {@code Terminated} rule of record T84 and the
 * deliverable-domain rule of record T68, both read from the same predicate the account path uses),
 * and the actor the request is recorded with — the JWT {@code sub} claim and never the username
 * (record T83), because the gate compares that value against the approving actor (rule O3).</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AccessProvisioningRequestService Tests")
class AccessProvisioningRequestServiceTest {

    private static final String ACTOR_SUB = "d4a1f0c2-0000-0000-0000-000000000001";

    @Mock
    private CurrentUserContext currentUserContext;

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private AccessProvisioningAccountService accountService;

    @Mock
    private AccessProvisioningGateService gateService;

    @InjectMocks
    private AccessProvisioningRequestService service;

    private UUID companyId;
    private UUID employeeId;
    private Employee active;
    private Employee terminated;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        employeeId = UUID.randomUUID();

        var company = Company.builder()
                .id(companyId)
                .companyKey("acme")
                .companyName("Acme")
                .rfc("ACM010101ABC")
                .emailDomain("acme.com")
                .build();

        active = Employee.builder()
                .id(employeeId)
                .company(company)
                .email("juan.perez@acme.com")
                .status(status("Active"))
                .enabled(true)
                .build();
        terminated = Employee.builder()
                .id(employeeId)
                .company(company)
                .email("juan.perez@acme.com")
                .status(status("Terminated"))
                .enabled(true)
                .build();

        when(currentUserContext.getUserId()).thenReturn(ACTOR_SUB);
    }

    private Status status(String name) {
        return Status.builder()
                .id(UUID.randomUUID())
                .statusName(name)
                .statusType(StatusType.builder()
                        .id(UUID.randomUUID())
                        .statusTypeName("EMPLOYEE_STATUS")
                        .enabled(true)
                        .build())
                .enabled(true)
                .build();
    }

    private void employeeInCompany(Employee employee) {
        when(employeeRepository.findByIdAndCompanyId(employeeId, companyId)).thenReturn(Optional.of(employee));
    }

    @Nested
    @DisplayName("apply")
    class Apply {

        @Test
        @DisplayName("checks the company access first, before any load")
        void companyCheckIsTheFirstStatement() {
            doThrow(new AccessDeniedException("denied"))
                    .when(currentUserContext)
                    .verifyCompanyAccess(companyId);

            assertThatThrownBy(() -> service.apply(companyId, employeeId)).isInstanceOf(AccessDeniedException.class);

            verify(currentUserContext).verifyCompanyAccess(companyId);
            verifyNoInteractions(employeeRepository, accountService, gateService);
        }

        @Test
        @DisplayName("refuses an employee outside the claimed company before requesting anything")
        void foreignEmployeeIsRefused() {
            when(employeeRepository.findByIdAndCompanyId(employeeId, companyId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.apply(companyId, employeeId))
                    .isInstanceOf(EmployeeNotFoundException.class);

            verifyNoInteractions(accountService, gateService);
        }

        @Test
        @DisplayName("refuses a Terminated employee with a named conflict, before any task exists")
        void terminatedEmployeeIsRefusedByName() {
            employeeInCompany(terminated);

            assertThatThrownBy(() -> service.apply(companyId, employeeId))
                    .isInstanceOf(TerminatedEmployeeRefusedException.class)
                    .hasMessageContaining("Terminated");

            verifyNoInteractions(gateService);
        }

        @Test
        @DisplayName("does not even ask whether the address is deliverable for a Terminated employee")
        void theTerminatedRuleRunsBeforeTheDomainRule() {
            employeeInCompany(terminated);

            assertThatThrownBy(() -> service.apply(companyId, employeeId))
                    .isInstanceOf(TerminatedEmployeeRefusedException.class);

            verifyNoInteractions(accountService);
        }

        @Test
        @DisplayName("refuses when the company has no deliverable email domain, before any task exists")
        void undeliverableDomainIsRefusedBeforeTheRequest() {
            employeeInCompany(active);
            doThrow(new InvitationNotDeliverableException(
                            "Invitation not deliverable: no email domain configured for the company of employee "
                                    + employeeId))
                    .when(accountService)
                    .requireDeliverableCompanyEmailDomain(active);

            assertThatThrownBy(() -> service.apply(companyId, employeeId))
                    .isInstanceOf(InvitationNotDeliverableException.class)
                    .hasMessageContaining("not deliverable");

            verifyNoInteractions(gateService);
        }

        @Test
        @DisplayName("checks the domain before requesting the task, not after")
        void theDomainRulePrecedesTheRequest() {
            employeeInCompany(active);

            service.apply(companyId, employeeId);

            InOrder order = inOrder(accountService, gateService);
            order.verify(accountService).requireDeliverableCompanyEmailDomain(active);
            order.verify(gateService).request(companyId, employeeId, ACTIVATE, ACTOR_SUB);
        }

        @Test
        @DisplayName("requests an ACTIVATE task recorded with the JWT sub claim")
        void requestsActivateWithTheSubjectClaim() {
            employeeInCompany(active);

            service.apply(companyId, employeeId);

            verify(gateService).request(companyId, employeeId, ACTIVATE, ACTOR_SUB);
            verify(currentUserContext, never()).getUsername();
        }
    }

    @Nested
    @DisplayName("deactivate")
    class Deactivate {

        @Test
        @DisplayName("checks the company access first, before any load")
        void companyCheckIsTheFirstStatement() {
            doThrow(new AccessDeniedException("denied"))
                    .when(currentUserContext)
                    .verifyCompanyAccess(companyId);

            assertThatThrownBy(() -> service.deactivate(companyId, employeeId))
                    .isInstanceOf(AccessDeniedException.class);

            verify(currentUserContext).verifyCompanyAccess(companyId);
            verifyNoInteractions(employeeRepository, accountService, gateService);
        }

        @Test
        @DisplayName("refuses an employee outside the claimed company before requesting anything")
        void foreignEmployeeIsRefused() {
            when(employeeRepository.findByIdAndCompanyId(employeeId, companyId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deactivate(companyId, employeeId))
                    .isInstanceOf(EmployeeNotFoundException.class);

            verifyNoInteractions(accountService, gateService);
        }

        @Test
        @DisplayName("requests a DEACTIVATE task recorded with the JWT sub claim")
        void requestsDeactivateWithTheSubjectClaim() {
            employeeInCompany(active);

            service.deactivate(companyId, employeeId);

            verify(gateService).request(companyId, employeeId, DEACTIVATE, ACTOR_SUB);
            verify(currentUserContext, never()).getUsername();
        }

        @Test
        @DisplayName("does not refuse a Terminated employee: revoking a revoked person is what it is for")
        void terminatedEmployeeIsNotRefused() {
            employeeInCompany(terminated);

            service.deactivate(companyId, employeeId);

            verify(gateService).request(companyId, employeeId, DEACTIVATE, ACTOR_SUB);
        }

        @Test
        @DisplayName("never asks whether the address is deliverable: a revocation sends no invitation")
        void theDomainRuleIsNotConsulted() {
            employeeInCompany(active);

            service.deactivate(companyId, employeeId);

            verifyNoInteractions(accountService);
        }
    }

    @Test
    @DisplayName("both intents scope the employee to the claimed company, never load it unscoped")
    void bothIntentsLoadTheEmployeeThroughTheCompany() {
        employeeInCompany(active);

        service.apply(companyId, employeeId);
        service.deactivate(companyId, employeeId);

        verify(employeeRepository, org.mockito.Mockito.times(2)).findByIdAndCompanyId(employeeId, companyId);
        verify(employeeRepository, never()).findById(any());
    }

    @Test
    @DisplayName("both intents refuse with 404 when the repository finds no employee")
    void bothIntentsRefuseAnUnknownEmployee() {
        when(employeeRepository.findByIdAndCompanyId(eq(employeeId), eq(companyId)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.apply(companyId, employeeId)).isInstanceOf(EmployeeNotFoundException.class);
        assertThatThrownBy(() -> service.deactivate(companyId, employeeId))
                .isInstanceOf(EmployeeNotFoundException.class);
    }
}
