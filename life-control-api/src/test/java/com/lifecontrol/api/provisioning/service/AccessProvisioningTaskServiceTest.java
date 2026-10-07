package com.lifecontrol.api.provisioning.service;

import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind.ACTIVATE;
import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus.APPLIED;
import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus.APPROVAL_PENDING;
import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus.FAILED;
import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus.PENDING;
import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus.REJECTED;
import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus.RUNNING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.hr.exception.EmployeeNotFoundException;
import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.provisioning.exception.AccessProvisioningTaskAlreadyOpenException;
import com.lifecontrol.api.provisioning.exception.AccessProvisioningTaskNotFoundException;
import com.lifecontrol.api.provisioning.exception.InvalidTaskStatusTransitionException;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTask;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus;
import com.lifecontrol.api.provisioning.repository.AccessProvisioningTaskRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

/**
 * Focused unit tests for {@link AccessProvisioningTaskService}: the six legal transitions with their
 * exact field movements, and the refusal of every illegal pair of the 6 × 6 matrix.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccessProvisioningTaskService Tests")
class AccessProvisioningTaskServiceTest {

    private static final Set<AccessProvisioningTaskStatus> OPEN_SET =
            Set.of(PENDING, APPROVAL_PENDING, RUNNING, FAILED);

    @Mock
    private AccessProvisioningTaskRepository taskRepository;

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private CurrentUserContext currentUserContext;

    @InjectMocks
    private AccessProvisioningTaskService service;

    private UUID companyId;
    private UUID employeeId;
    private UUID taskId;
    private Employee employee;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        employeeId = UUID.randomUUID();
        taskId = UUID.randomUUID();
        employee = Employee.builder().id(employeeId).build();
    }

    private AccessProvisioningTask taskInStatus(AccessProvisioningTaskStatus status) {
        return AccessProvisioningTask.builder()
                .id(taskId)
                .employee(employee)
                .kind(ACTIVATE)
                .status(status)
                .attempts(0)
                .build();
    }

    private void stubLoadedForUpdate(AccessProvisioningTask task) {
        when(taskRepository.findByIdAndEmployeeIdForUpdate(taskId, employeeId)).thenReturn(Optional.of(task));
    }

    private void verifyLockedLoad() {
        verify(taskRepository).findByIdAndEmployeeIdForUpdate(taskId, employeeId);
        verify(taskRepository, never()).findByIdAndEmployeeId(any(), any());
    }

    private void stubSaved() {
        when(taskRepository.save(any(AccessProvisioningTask.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Nested
    @DisplayName("create")
    class CreateTests {

        @Test
        @DisplayName("should create a PENDING task scoped to the company and the employee")
        void create_Pending() {
            when(employeeRepository.findByIdAndCompanyId(employeeId, companyId)).thenReturn(Optional.of(employee));
            when(taskRepository.existsByEmployeeIdAndStatusIn(employeeId, OPEN_SET))
                    .thenReturn(false);
            stubSaved();

            var result = service.create(companyId, employeeId, ACTIVATE, "requester-1", PENDING);

            assertThat(result.getStatus()).isEqualTo(PENDING);
            assertThat(result.getEmployee()).isSameAs(employee);
            assertThat(result.getKind()).isEqualTo(ACTIVATE);
            assertThat(result.getRequestedBy()).isEqualTo("requester-1");
            assertThat(result.getRequestedAt()).isNotNull();
            assertThat(result.getAttempts()).isZero();
            assertThat(result.getLastError()).isNull();
            assertThat(result.getDecidedBy()).isNull();
            assertThat(result.getDecidedAt()).isNull();
            assertThat(result.getAppliedAt()).isNull();
            assertThat(result.getNextAttemptAt())
                    .as("a created PENDING task is due immediately")
                    .isNull();
            verify(currentUserContext).verifyCompanyAccess(companyId);
        }

        @Test
        @DisplayName("should create an APPROVAL_PENDING task when the gate applies")
        void create_ApprovalPending() {
            when(employeeRepository.findByIdAndCompanyId(employeeId, companyId)).thenReturn(Optional.of(employee));
            when(taskRepository.existsByEmployeeIdAndStatusIn(employeeId, OPEN_SET))
                    .thenReturn(false);
            stubSaved();

            var result = service.create(companyId, employeeId, ACTIVATE, "requester-1", APPROVAL_PENDING);

            assertThat(result.getStatus()).isEqualTo(APPROVAL_PENDING);
            assertThat(result.getAttempts()).isZero();
            assertThat(result.getNextAttemptAt())
                    .as("a gated task carries no deadline either")
                    .isNull();
        }

        @Test
        @DisplayName("should refuse an initial status outside PENDING and APPROVAL_PENDING")
        void create_RefusesIllegalInitialStatus() {
            when(employeeRepository.findByIdAndCompanyId(employeeId, companyId)).thenReturn(Optional.of(employee));
            when(taskRepository.existsByEmployeeIdAndStatusIn(eq(employeeId), any()))
                    .thenReturn(false);

            for (var illegal : Set.of(RUNNING, APPLIED, FAILED, REJECTED)) {
                assertThatThrownBy(() -> service.create(companyId, employeeId, ACTIVATE, "requester-1", illegal))
                        .as("initial status %s", illegal)
                        .isInstanceOf(InvalidTaskStatusTransitionException.class);
            }
            verify(taskRepository, never()).save(any());
        }

        @Test
        @DisplayName("should refuse a second open task and probe the exact open set")
        void create_RefusesSecondOpenTask() {
            when(employeeRepository.findByIdAndCompanyId(employeeId, companyId)).thenReturn(Optional.of(employee));
            when(taskRepository.existsByEmployeeIdAndStatusIn(employeeId, OPEN_SET))
                    .thenReturn(true);

            assertThatThrownBy(() -> service.create(companyId, employeeId, ACTIVATE, "requester-1", PENDING))
                    .isInstanceOf(AccessProvisioningTaskAlreadyOpenException.class)
                    .hasMessageContaining(employeeId.toString());

            verify(taskRepository).existsByEmployeeIdAndStatusIn(employeeId, OPEN_SET);
            verify(taskRepository, never()).save(any());
        }

        @Test
        @DisplayName("should refuse an employee that does not belong to the company")
        void create_RefusesForeignEmployee() {
            when(employeeRepository.findByIdAndCompanyId(employeeId, companyId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.create(companyId, employeeId, ACTIVATE, "requester-1", PENDING))
                    .isInstanceOf(EmployeeNotFoundException.class);
            verify(taskRepository, never()).save(any());
        }

        @Test
        @DisplayName("should propagate a refused company access before loading anything")
        void create_PropagatesRefusedCompanyAccess() {
            doThrow(new AccessDeniedException("no access"))
                    .when(currentUserContext)
                    .verifyCompanyAccess(companyId);

            assertThatThrownBy(() -> service.create(companyId, employeeId, ACTIVATE, "requester-1", PENDING))
                    .isInstanceOf(AccessDeniedException.class);
            verify(employeeRepository, never()).findByIdAndCompanyId(any(), any());
            verify(taskRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("claim")
    class ClaimTests {

        @Test
        @DisplayName("should move PENDING to RUNNING, increment attempts and read under the lock")
        void claim_FromPending() {
            var task = taskInStatus(PENDING);
            stubLoadedForUpdate(task);
            stubSaved();

            var result = service.claim(employeeId, taskId);

            assertThat(result.getStatus()).isEqualTo(RUNNING);
            assertThat(result.getAttempts()).isEqualTo(1);
            verifyLockedLoad();
        }

        @Test
        @DisplayName("should refuse a self-transition from RUNNING without touching attempts")
        void claim_FromRunning() {
            var task = taskInStatus(RUNNING);
            stubLoadedForUpdate(task);

            assertThatThrownBy(() -> service.claim(employeeId, taskId))
                    .isInstanceOf(InvalidTaskStatusTransitionException.class)
                    .hasMessageContaining("RUNNING");
            assertThat(task.getStatus()).isEqualTo(RUNNING);
            assertThat(task.getAttempts()).isZero();
            verifyLockedLoad();
            verify(taskRepository, never()).save(any());
        }

        @Test
        @DisplayName("should refuse an unknown or foreign task")
        void claim_RefusesUnknownTask() {
            when(taskRepository.findByIdAndEmployeeIdForUpdate(taskId, employeeId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.claim(employeeId, taskId))
                    .isInstanceOf(AccessProvisioningTaskNotFoundException.class);
            verifyLockedLoad();
            verify(taskRepository, never()).save(any());
        }

        @Test
        @DisplayName("should treat a null attempts count as zero when incrementing")
        void claim_TreatsNullAttemptsAsZero() {
            var task = taskInStatus(PENDING);
            task.setAttempts(null);
            stubLoadedForUpdate(task);
            stubSaved();

            assertThat(service.claim(employeeId, taskId).getAttempts()).isEqualTo(1);
        }

        @Test
        @DisplayName("should clear the retry deadline when claiming a waiting task")
        void claim_ClearsTheRetryDeadline() {
            var task = taskInStatus(PENDING);
            task.setNextAttemptAt(LocalDateTime.now().plusMinutes(5));
            stubLoadedForUpdate(task);
            stubSaved();

            var result = service.claim(employeeId, taskId);

            assertThat(result.getStatus()).isEqualTo(RUNNING);
            assertThat(result.getNextAttemptAt())
                    .as("a RUNNING task carries no deadline (record T42)")
                    .isNull();
            verifyLockedLoad();
        }
    }

    @Nested
    @DisplayName("markApplied")
    class MarkAppliedTests {

        @Test
        @DisplayName("should move RUNNING to APPLIED and stamp appliedAt")
        void markApplied_FromRunning() {
            var task = taskInStatus(RUNNING);
            stubLoadedForUpdate(task);
            stubSaved();

            var result = service.markApplied(employeeId, taskId);

            assertThat(result.getStatus()).isEqualTo(APPLIED);
            assertThat(result.getAppliedAt()).isNotNull();
            assertThat(result.getLastError()).isNull();
            verifyLockedLoad();
        }

        @Test
        @DisplayName("should refuse applying a task that is not RUNNING")
        void markApplied_RefusesFromPending() {
            var task = taskInStatus(PENDING);
            stubLoadedForUpdate(task);

            assertThatThrownBy(() -> service.markApplied(employeeId, taskId))
                    .isInstanceOf(InvalidTaskStatusTransitionException.class);
            assertThat(task.getStatus()).isEqualTo(PENDING);
            verifyLockedLoad();
            verify(taskRepository, never()).save(any());
        }

        @Test
        @DisplayName("should refuse an unknown or foreign task")
        void markApplied_RefusesUnknownTask() {
            when(taskRepository.findByIdAndEmployeeIdForUpdate(taskId, employeeId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.markApplied(employeeId, taskId))
                    .isInstanceOf(AccessProvisioningTaskNotFoundException.class);
            verifyLockedLoad();
            verify(taskRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("markFailed")
    class MarkFailedTests {

        @Test
        @DisplayName("should move RUNNING to FAILED and store the reason")
        void markFailed_FromRunning() {
            var task = taskInStatus(RUNNING);
            stubLoadedForUpdate(task);
            stubSaved();

            var result = service.markFailed(employeeId, taskId, "keycloak timeout", null);

            assertThat(result.getStatus()).isEqualTo(FAILED);
            assertThat(result.getLastError()).isEqualTo("keycloak timeout");
            assertThat(result.getAppliedAt()).isNull();
            assertThat(result.getNextAttemptAt()).isNull();
            verifyLockedLoad();
        }

        @Test
        @DisplayName("should reschedule atomically: FAILED -> PENDING with the deadline and the reason kept")
        void markFailed_ReschedulesWithTheDeadline() {
            var task = taskInStatus(RUNNING);
            stubLoadedForUpdate(task);
            stubSaved();
            var deadline = LocalDateTime.of(2026, 1, 1, 12, 5, 0);

            var result = service.markFailed(employeeId, taskId, "keycloak timeout", deadline);

            assertThat(result.getStatus())
                    .as("the FAILED -> PENDING edge ran in the same call")
                    .isEqualTo(PENDING);
            assertThat(result.getLastError())
                    .as("the reason stays visible while the task waits (record T10)")
                    .isEqualTo("keycloak timeout");
            assertThat(result.getNextAttemptAt()).isEqualTo(deadline);
            verifyLockedLoad();
        }

        @Test
        @DisplayName("should leave the task FAILED with no deadline when the ceiling is reached")
        void markFailed_AtTheCeilingStaysFailed() {
            var task = taskInStatus(RUNNING);
            stubLoadedForUpdate(task);
            stubSaved();

            var result = service.markFailed(employeeId, taskId, "ceiling reached", null);

            assertThat(result.getStatus()).isEqualTo(FAILED);
            assertThat(result.getLastError()).isEqualTo("ceiling reached");
            assertThat(result.getNextAttemptAt()).isNull();
            verifyLockedLoad();
        }

        @Test
        @DisplayName("should clear any deadline when the ceiling is reached")
        void markFailed_AtTheCeilingClearsAStaleDeadline() {
            var task = taskInStatus(RUNNING);
            task.setNextAttemptAt(LocalDateTime.now());
            stubLoadedForUpdate(task);
            stubSaved();

            var result = service.markFailed(employeeId, taskId, "ceiling reached", null);

            assertThat(result.getStatus()).isEqualTo(FAILED);
            assertThat(result.getNextAttemptAt())
                    .as("a terminal FAILED row never carries a pending deadline")
                    .isNull();
        }

        @Test
        @DisplayName("should refuse failing a task that is not RUNNING")
        void markFailed_RefusesFromApplied() {
            var task = taskInStatus(APPLIED);
            stubLoadedForUpdate(task);

            assertThatThrownBy(() -> service.markFailed(employeeId, taskId, "boom", null))
                    .isInstanceOf(InvalidTaskStatusTransitionException.class);
            assertThat(task.getStatus()).isEqualTo(APPLIED);
            verifyLockedLoad();
            verify(taskRepository, never()).save(any());
        }

        @Test
        @DisplayName("should refuse an unknown or foreign task")
        void markFailed_RefusesUnknownTask() {
            when(taskRepository.findByIdAndEmployeeIdForUpdate(taskId, employeeId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.markFailed(employeeId, taskId, "boom", null))
                    .isInstanceOf(AccessProvisioningTaskNotFoundException.class);
            verifyLockedLoad();
            verify(taskRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("retry")
    class RetryTests {

        @Test
        @DisplayName("should move FAILED to PENDING and clear lastError")
        void retry_FromFailed() {
            var task = taskInStatus(FAILED);
            task.setLastError("previous failure");
            stubLoadedForUpdate(task);
            stubSaved();

            var result = service.retry(employeeId, taskId);

            assertThat(result.getStatus()).isEqualTo(PENDING);
            assertThat(result.getLastError()).isNull();
            assertThat(result.getNextAttemptAt()).isNull();
            verifyLockedLoad();
        }

        @Test
        @DisplayName("should clear the deadline so the operator's retry is due immediately")
        void retry_ClearsTheRetryDeadline() {
            var task = taskInStatus(FAILED);
            task.setLastError("previous failure");
            task.setNextAttemptAt(LocalDateTime.now().plusMinutes(5));
            stubLoadedForUpdate(task);
            stubSaved();

            var result = service.retry(employeeId, taskId);

            assertThat(result.getStatus()).isEqualTo(PENDING);
            assertThat(result.getLastError()).isNull();
            assertThat(result.getNextAttemptAt())
                    .as("the manual retry is immediate, never backoff-delayed (record T46)")
                    .isNull();
            verifyLockedLoad();
        }

        @Test
        @DisplayName("should refuse retrying a task that is not FAILED")
        void retry_RefusesFromRunning() {
            var task = taskInStatus(RUNNING);
            stubLoadedForUpdate(task);

            assertThatThrownBy(() -> service.retry(employeeId, taskId))
                    .isInstanceOf(InvalidTaskStatusTransitionException.class);
            assertThat(task.getStatus()).isEqualTo(RUNNING);
            verifyLockedLoad();
            verify(taskRepository, never()).save(any());
        }

        @Test
        @DisplayName("should refuse an unknown or foreign task")
        void retry_RefusesUnknownTask() {
            when(taskRepository.findByIdAndEmployeeIdForUpdate(taskId, employeeId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.retry(employeeId, taskId))
                    .isInstanceOf(AccessProvisioningTaskNotFoundException.class);
            verifyLockedLoad();
            verify(taskRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("approve")
    class ApproveTests {

        @Test
        @DisplayName("should move APPROVAL_PENDING to PENDING and record the decision")
        void approve_FromApprovalPending() {
            var task = taskInStatus(APPROVAL_PENDING);
            stubLoadedForUpdate(task);
            stubSaved();

            var result = service.approve(employeeId, taskId, "approver-1");

            assertThat(result.getStatus()).isEqualTo(PENDING);
            assertThat(result.getDecidedBy()).isEqualTo("approver-1");
            assertThat(result.getDecidedAt()).isNotNull();
            assertThat(result.getLastError()).isNull();
            assertThat(result.getNextAttemptAt())
                    .as("approval releases the task to the queue due immediately")
                    .isNull();
            verifyLockedLoad();
        }

        @Test
        @DisplayName("should refuse approving a task that is not APPROVAL_PENDING")
        void approve_RefusesFromPending() {
            var task = taskInStatus(PENDING);
            stubLoadedForUpdate(task);

            assertThatThrownBy(() -> service.approve(employeeId, taskId, "approver-1"))
                    .isInstanceOf(InvalidTaskStatusTransitionException.class);
            assertThat(task.getStatus()).isEqualTo(PENDING);
            verifyLockedLoad();
            verify(taskRepository, never()).save(any());
        }

        @Test
        @DisplayName("should refuse an unknown or foreign task")
        void approve_RefusesUnknownTask() {
            when(taskRepository.findByIdAndEmployeeIdForUpdate(taskId, employeeId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.approve(employeeId, taskId, "approver-1"))
                    .isInstanceOf(AccessProvisioningTaskNotFoundException.class);
            verifyLockedLoad();
            verify(taskRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("reject")
    class RejectTests {

        @Test
        @DisplayName("should move APPROVAL_PENDING to REJECTED, record the decision and keep the reason")
        void reject_FromApprovalPending() {
            var task = taskInStatus(APPROVAL_PENDING);
            stubLoadedForUpdate(task);
            stubSaved();

            var result = service.reject(employeeId, taskId, "approver-1", "role outside the allowlist");

            assertThat(result.getStatus()).isEqualTo(REJECTED);
            assertThat(result.getDecidedBy()).isEqualTo("approver-1");
            assertThat(result.getDecidedAt()).isNotNull();
            assertThat(result.getLastError()).isEqualTo("role outside the allowlist");
            verifyLockedLoad();
        }

        @Test
        @DisplayName("should refuse rejecting a task that is not APPROVAL_PENDING")
        void reject_RefusesFromPending() {
            var task = taskInStatus(PENDING);
            stubLoadedForUpdate(task);

            assertThatThrownBy(() -> service.reject(employeeId, taskId, "approver-1", "no"))
                    .isInstanceOf(InvalidTaskStatusTransitionException.class);
            assertThat(task.getStatus()).isEqualTo(PENDING);
            verifyLockedLoad();
            verify(taskRepository, never()).save(any());
        }

        @Test
        @DisplayName("should refuse an unknown or foreign task")
        void reject_RefusesUnknownTask() {
            when(taskRepository.findByIdAndEmployeeIdForUpdate(taskId, employeeId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.reject(employeeId, taskId, "approver-1", "no"))
                    .isInstanceOf(AccessProvisioningTaskNotFoundException.class);
            verifyLockedLoad();
            verify(taskRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("transition map")
    class TransitionMatrixTests {

        @Test
        @DisplayName("should accept exactly the six legal edges")
        void acceptsLegalEdges() {
            assertThatCode(() -> AccessProvisioningTaskService.requireTransition(PENDING, RUNNING))
                    .doesNotThrowAnyException();
            assertThatCode(() -> AccessProvisioningTaskService.requireTransition(RUNNING, APPLIED))
                    .doesNotThrowAnyException();
            assertThatCode(() -> AccessProvisioningTaskService.requireTransition(RUNNING, FAILED))
                    .doesNotThrowAnyException();
            assertThatCode(() -> AccessProvisioningTaskService.requireTransition(FAILED, PENDING))
                    .doesNotThrowAnyException();
            assertThatCode(() -> AccessProvisioningTaskService.requireTransition(APPROVAL_PENDING, PENDING))
                    .doesNotThrowAnyException();
            assertThatCode(() -> AccessProvisioningTaskService.requireTransition(APPROVAL_PENDING, REJECTED))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should refuse every pair that is not one of the six legal edges")
        void refusesEveryIllegalPair() {
            var refused = 0;
            for (var from : AccessProvisioningTaskStatus.values()) {
                for (var to : AccessProvisioningTaskStatus.values()) {
                    if (isLegal(from, to)) {
                        continue;
                    }
                    assertThatThrownBy(() -> AccessProvisioningTaskService.requireTransition(from, to))
                            .as("%s -> %s", from, to)
                            .isInstanceOf(InvalidTaskStatusTransitionException.class);
                    refused++;
                }
            }
            assertThat(refused).isEqualTo(30);
        }

        @Test
        @DisplayName("should refuse a null initial status as a transition, not an NPE")
        void refusesNullTargetOnCreation() {
            assertThatThrownBy(() -> AccessProvisioningTaskService.requireTransition(null, null))
                    .isInstanceOf(InvalidTaskStatusTransitionException.class);
        }

        @Test
        @DisplayName("should refuse a null target from a known status, not an NPE")
        void refusesNullTargetFromKnownStatus() {
            assertThatThrownBy(() -> AccessProvisioningTaskService.requireTransition(RUNNING, null))
                    .isInstanceOf(InvalidTaskStatusTransitionException.class);
        }

        private static boolean isLegal(AccessProvisioningTaskStatus from, AccessProvisioningTaskStatus to) {
            return (from == PENDING && to == RUNNING)
                    || (from == RUNNING && to == APPLIED)
                    || (from == RUNNING && to == FAILED)
                    || (from == FAILED && to == PENDING)
                    || (from == APPROVAL_PENDING && to == PENDING)
                    || (from == APPROVAL_PENDING && to == REJECTED);
        }
    }
}
