package com.lifecontrol.api.provisioning.service;

import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind.ACTIVATE;
import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind.DEACTIVATE;
import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus.FAILED;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.hr.exception.EmployeeNotFoundException;
import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.provisioning.exception.AccountNotLinkedException;
import com.lifecontrol.api.provisioning.exception.InvalidTaskStatusTransitionException;
import com.lifecontrol.api.provisioning.exception.NoOpenAccessProvisioningTaskException;
import com.lifecontrol.api.provisioning.exception.RevokedEmployeeRefusedException;
import com.lifecontrol.api.provisioning.exception.RevokedEmployeeRequiredException;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTask;
import com.lifecontrol.api.provisioning.repository.AccessProvisioningTaskRepository;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.status.model.StatusType;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    @Mock
    private AccessProvisioningTaskService taskService;

    @Mock
    private AccessProvisioningTaskRepository taskRepository;

    @InjectMocks
    private AccessProvisioningRequestService service;

    private UUID companyId;
    private UUID employeeId;
    private UUID taskId;
    private Employee active;
    private Employee terminated;
    private Employee softDeleted;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        employeeId = UUID.randomUUID();
        taskId = UUID.randomUUID();

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
        // The soft delete's own fact: an employee who is still `Active` by status and out by
        // `enabled = false` (gap G41). The two facts are one verdict, and these tests are what pins that
        // the explicit surface reads it.
        softDeleted = Employee.builder()
                .id(employeeId)
                .company(company)
                .email("juan.perez@acme.com")
                .status(status("Active"))
                .enabled(false)
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

    /**
     * An open task of the fixture employee. The status is set explicitly rather than through the state
     * machine: these tests are about which row the surface resolves and what it delegates, not about
     * the transitions, which are {@link AccessProvisioningTaskServiceTest}'s subject.
     */
    private AccessProvisioningTask openTask(UUID id) {
        return task(id);
    }

    private AccessProvisioningTask task(UUID id) {
        return AccessProvisioningTask.builder()
                .id(id)
                .employee(active)
                .kind(ACTIVATE)
                .status(FAILED)
                .attempts(1)
                .requestedBy(ACTOR_SUB)
                .requestedAt(LocalDateTime.now())
                .build();
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
                    .isInstanceOf(RevokedEmployeeRefusedException.class)
                    .hasMessageContaining("Terminated");

            verifyNoInteractions(gateService);
        }

        @Test
        @DisplayName("refuses a soft-deleted employee by the same rule, before any task exists")
        void softDeletedEmployeeIsRefusedByTheSameRule() {
            employeeInCompany(softDeleted);

            assertThatThrownBy(() -> service.apply(companyId, employeeId))
                    .isInstanceOf(RevokedEmployeeRefusedException.class)
                    .hasMessageContaining("revoked")
                    .hasMessageContaining(employeeId.toString());

            verifyNoInteractions(gateService, accountService);
        }

        @Test
        @DisplayName("does not even ask whether the address is deliverable for a revoked employee")
        void theRevocationRuleRunsBeforeTheDomainRule() {
            employeeInCompany(terminated);

            assertThatThrownBy(() -> service.apply(companyId, employeeId))
                    .isInstanceOf(RevokedEmployeeRefusedException.class);

            verifyNoInteractions(accountService);
        }

        @Test
        @DisplayName("requests the ACTIVATE task for a company with no declared email domain")
        void domainlessCompanyStillRequestsTheTask() {
            var domainless = Employee.builder()
                    .id(employeeId)
                    .company(Company.builder()
                            .id(companyId)
                            .companyKey("acme")
                            .companyName("Acme")
                            .rfc("ACM010101ABC")
                            .emailDomain(null)
                            .build())
                    .email("juan.perez@acme.com")
                    .status(status("Active"))
                    .enabled(true)
                    .build();
            employeeInCompany(domainless);

            service.apply(companyId, employeeId);

            verify(gateService).request(companyId, employeeId, ACTIVATE, ACTOR_SUB);
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
            employeeInCompany(terminated);

            service.deactivate(companyId, employeeId);

            verify(gateService).request(companyId, employeeId, DEACTIVATE, ACTOR_SUB);
            verify(currentUserContext, never()).getUsername();
        }

        @Test
        @DisplayName("refuses an employee who still has access with a named conflict, before any task exists")
        void refusesAnEmployeeWhoStillHasAccess() {
            employeeInCompany(active);

            assertThatThrownBy(() -> service.deactivate(companyId, employeeId))
                    .isInstanceOf(RevokedEmployeeRequiredException.class)
                    .hasMessageContaining(employeeId.toString());

            verifyNoInteractions(gateService, taskService, accountService);
        }

        @Test
        @DisplayName("requests the task for a soft-deleted employee: that is who a revocation is for")
        void softDeletedEmployeeIsAdmitted() {
            employeeInCompany(softDeleted);

            service.deactivate(companyId, employeeId);

            verify(gateService).request(companyId, employeeId, DEACTIVATE, ACTOR_SUB);
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
            employeeInCompany(terminated);

            service.deactivate(companyId, employeeId);

            verifyNoInteractions(accountService);
        }
    }

    @Test
    @DisplayName("both intents scope the employee to the claimed company, never load it unscoped")
    void bothIntentsLoadTheEmployeeThroughTheCompany() {
        // Two consecutive loads, one per intent: apply is refused for a revoked employee and the
        // revocation requires one, so each intent needs its own side of the same rule.
        when(employeeRepository.findByIdAndCompanyId(employeeId, companyId))
                .thenReturn(Optional.of(active), Optional.of(terminated));

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

    @Nested
    @DisplayName("retry")
    class Retry {

        @Test
        @DisplayName("checks the company access first, before any load")
        void companyCheckIsTheFirstStatement() {
            doThrow(new AccessDeniedException("denied"))
                    .when(currentUserContext)
                    .verifyCompanyAccess(companyId);

            assertThatThrownBy(() -> service.retry(companyId, employeeId)).isInstanceOf(AccessDeniedException.class);

            verify(currentUserContext).verifyCompanyAccess(companyId);
            verifyNoInteractions(employeeRepository, taskRepository, taskService);
        }

        @Test
        @DisplayName("refuses an employee outside the claimed company before looking for a task")
        void foreignEmployeeIsRefused() {
            when(employeeRepository.findByIdAndCompanyId(employeeId, companyId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.retry(companyId, employeeId))
                    .isInstanceOf(EmployeeNotFoundException.class);

            verifyNoInteractions(taskRepository, taskService);
        }

        @Test
        @DisplayName("refuses with a named 404 when there is no open task to retry")
        void noOpenTaskIsANotFound() {
            employeeInCompany(active);

            assertThatThrownBy(() -> service.retry(companyId, employeeId))
                    .isInstanceOf(NoOpenAccessProvisioningTaskException.class)
                    .hasMessageContaining(employeeId.toString());

            verifyNoInteractions(taskService);
        }

        @Test
        @DisplayName("resolves the row through the landed open set, so 'open' has one definition")
        void theOpenSetIsTheLandedOne() {
            employeeInCompany(active);
            when(taskRepository.findFirstByEmployeeIdAndStatusInOrderByRequestedAtDesc(
                            employeeId, AccessProvisioningTaskService.OPEN_STATUSES))
                    .thenReturn(Optional.of(openTask(taskId)));

            service.retry(companyId, employeeId);

            verify(taskRepository)
                    .findFirstByEmployeeIdAndStatusInOrderByRequestedAtDesc(
                            employeeId, AccessProvisioningTaskService.OPEN_STATUSES);
        }

        @Test
        @DisplayName("delegates the move to the state machine, which owns whether it is legal")
        void delegatesToTheMachine() {
            employeeInCompany(active);
            when(taskRepository.findFirstByEmployeeIdAndStatusInOrderByRequestedAtDesc(
                            employeeId, AccessProvisioningTaskService.OPEN_STATUSES))
                    .thenReturn(Optional.of(openTask(taskId)));

            service.retry(companyId, employeeId);

            verify(taskService).retry(employeeId, taskId);
        }

        @Test
        @DisplayName("propagates the machine's refusal instead of re-stating the rule")
        void theMachinesRefusalPropagates() {
            employeeInCompany(active);
            when(taskRepository.findFirstByEmployeeIdAndStatusInOrderByRequestedAtDesc(
                            employeeId, AccessProvisioningTaskService.OPEN_STATUSES))
                    .thenReturn(Optional.of(openTask(taskId)));
            doThrow(new InvalidTaskStatusTransitionException("PENDING", "PENDING"))
                    .when(taskService)
                    .retry(employeeId, taskId);

            assertThatThrownBy(() -> service.retry(companyId, employeeId))
                    .isInstanceOf(InvalidTaskStatusTransitionException.class);
        }
    }

    @Nested
    @DisplayName("resend")
    class Resend {

        @Test
        @DisplayName("checks the company access first, before any load")
        void companyCheckIsTheFirstStatement() {
            doThrow(new AccessDeniedException("denied"))
                    .when(currentUserContext)
                    .verifyCompanyAccess(companyId);

            assertThatThrownBy(() -> service.resend(companyId, employeeId)).isInstanceOf(AccessDeniedException.class);

            verify(currentUserContext).verifyCompanyAccess(companyId);
            verifyNoInteractions(employeeRepository, taskRepository, taskService, accountService);
        }

        @Test
        @DisplayName("refuses an employee outside the claimed company")
        void foreignEmployeeIsRefused() {
            when(employeeRepository.findByIdAndCompanyId(employeeId, companyId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.resend(companyId, employeeId))
                    .isInstanceOf(EmployeeNotFoundException.class);

            verifyNoInteractions(accountService);
        }

        @Test
        @DisplayName("refuses a Terminated employee before delegating the send")
        void terminatedEmployeeIsRefusedByName() {
            employeeInCompany(terminated);

            assertThatThrownBy(() -> service.resend(companyId, employeeId))
                    .isInstanceOf(RevokedEmployeeRefusedException.class)
                    .hasMessageContaining("Terminated");

            verifyNoInteractions(accountService);
        }

        @Test
        @DisplayName("refuses a soft-deleted employee before delegating the send")
        void softDeletedEmployeeIsRefusedByName() {
            employeeInCompany(softDeleted);

            assertThatThrownBy(() -> service.resend(companyId, employeeId))
                    .isInstanceOf(RevokedEmployeeRefusedException.class)
                    .hasMessageContaining("revoked");

            verifyNoInteractions(accountService);
        }

        @Test
        @DisplayName("delegates the send to the account service, with the employee it loaded")
        void delegatesToTheAccountService() {
            employeeInCompany(active);

            service.resend(companyId, employeeId);

            verify(accountService).resendInvitation(active);
        }

        @Test
        @DisplayName("propagates the not-linked refusal by name")
        void theNotLinkedRefusalPropagates() {
            employeeInCompany(active);
            doThrow(new AccountNotLinkedException("Account not linked: employee " + employeeId))
                    .when(accountService)
                    .resendInvitation(active);

            assertThatThrownBy(() -> service.resend(companyId, employeeId))
                    .isInstanceOf(AccountNotLinkedException.class)
                    .hasMessageContaining("Account not linked");
        }

        @Test
        @DisplayName("creates no task and touches no queue: a resent link grants nothing")
        void noTaskIsCreated() {
            employeeInCompany(active);

            service.resend(companyId, employeeId);

            verifyNoInteractions(gateService, taskService, taskRepository);
        }
    }
}
