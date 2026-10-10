package com.lifecontrol.api.provisioning.service;

import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind.ACTIVATE;
import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind.RECONCILE;
import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus.APPROVAL_PENDING;
import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus.PENDING;
import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus.RUNNING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.common.worker.WorkerRetryPolicy;
import com.lifecontrol.api.common.worker.WorkerTick;
import com.lifecontrol.api.config.provisioning.ProvisioningWorkerProperties;
import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.provisioning.exception.AccountLinkRefusedException;
import com.lifecontrol.api.provisioning.exception.InvalidTaskStatusTransitionException;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTask;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind;
import com.lifecontrol.api.provisioning.repository.AccessProvisioningTaskRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Focused unit tests for {@link AccessProvisioningWorker}: the staleness rescue and the claim loop of
 * the full pass.
 *
 * <p>The repository, the state machine, the handler and the employee repository are Mockito mocks —
 * the pass's contract is which ids it hands to the guard, with which deadline, which employee and kind
 * it dispatches, and what it does when one row is refused — but the {@link WorkerTick} is <b>real</b>,
 * so the containment claim ("one bad row cannot abort the batch") is exercised by the same loop
 * production uses rather than asserted about a mock.</p>
 *
 * <p>The real-PostgreSQL proof that the staleness bound actually selects the rows this unit intends
 * is a separate suite
 * ({@code com.lifecontrol.api.provisioning.AccessProvisioningWorkerRescueIntegrationTest}), because a
 * mocked finder cannot prove what the query's {@code updated_at < bound} predicate does.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccessProvisioningWorker Tests")
class AccessProvisioningWorkerTest {

    private static final int MAX_ATTEMPTS = 3;
    private static final int BASE_DELAY_SECONDS = 30;
    private static final int MAX_DELAY_SECONDS = 3600;
    private static final int BATCH_SIZE = 4;
    private static final int STALENESS_SECONDS = 900;

    @Mock
    private AccessProvisioningTaskRepository taskRepository;

    @Mock
    private AccessProvisioningTaskService taskService;

    @Mock
    private AccessProvisioningWorkerHandler handler;

    @Mock
    private AccessProvisioningGateService gateService;

    @Mock
    private EmployeeRepository employeeRepository;

    private WorkerRetryPolicy retryPolicy;

    private AccessProvisioningWorker worker;

    @BeforeEach
    void setUp() {
        var properties = new ProvisioningWorkerProperties(
                false, 60, MAX_ATTEMPTS, BATCH_SIZE, BASE_DELAY_SECONDS, MAX_DELAY_SECONDS, STALENESS_SECONDS);
        retryPolicy = new WorkerRetryPolicy(
                MAX_ATTEMPTS, Duration.ofSeconds(BASE_DELAY_SECONDS), Duration.ofSeconds(MAX_DELAY_SECONDS));
        worker = new AccessProvisioningWorker(
                taskRepository,
                taskService,
                employeeRepository,
                handler,
                gateService,
                new WorkerTick(BATCH_SIZE),
                retryPolicy,
                properties);
    }

    private AccessProvisioningTask runningTask(UUID taskId, UUID employeeId, int attempts) {
        return AccessProvisioningTask.builder()
                .id(taskId)
                .employee(Employee.builder().id(employeeId).build())
                .kind(ACTIVATE)
                .status(RUNNING)
                .attempts(attempts)
                .build();
    }

    private AccessProvisioningTask pendingTask(UUID taskId, UUID employeeId, AccessProvisioningTaskKind kind) {
        return AccessProvisioningTask.builder()
                .id(taskId)
                .employee(Employee.builder().id(employeeId).build())
                .kind(kind)
                .status(PENDING)
                .attempts(0)
                .build();
    }

    private AccessProvisioningTask claimedTask(
            UUID taskId, UUID employeeId, AccessProvisioningTaskKind kind, int attempts) {
        return AccessProvisioningTask.builder()
                .id(taskId)
                .employee(Employee.builder().id(employeeId).build())
                .kind(kind)
                .status(RUNNING)
                .attempts(attempts)
                .build();
    }

    private void stubStaleIds(UUID... ids) {
        when(taskRepository.findStaleRunningTaskIds(any(), any())).thenReturn(List.of(ids));
    }

    @Nested
    @DisplayName("the staleness query")
    class TheStalenessQuery {

        @Test
        @DisplayName("bounds the query by now minus the configured threshold and by batchSize")
        void boundsTheQueryByTheThresholdAndTheBatchSize() {
            stubStaleIds();

            // The worker reads the clock inside the call, so the value it derives must sit between
            // "before the call minus the threshold" and "after the call minus the threshold".
            var before = LocalDateTime.now().minusSeconds(STALENESS_SECONDS);
            worker.rescueStaleRunningTasks();
            var after = LocalDateTime.now().minusSeconds(STALENESS_SECONDS);

            var boundCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
            var pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(taskRepository).findStaleRunningTaskIds(boundCaptor.capture(), pageableCaptor.capture());

            assertThat(boundCaptor.getValue())
                    .as("the staleness bound is the pass's now minus stalenessThresholdSeconds")
                    .isBetween(before, after);
            assertThat(pageableCaptor.getValue())
                    .as("the query is bounded by the configured batch size")
                    .isEqualTo(PageRequest.of(0, BATCH_SIZE));
        }

        @Test
        @DisplayName("still bounds the batch to batchSize when the query hands back more ids")
        void appliesTheRealTickBatchBound() {
            var ids = IntStream.range(0, BATCH_SIZE + 2)
                    .mapToObj(i -> UUID.randomUUID())
                    .toArray(UUID[]::new);
            var employeeId = UUID.randomUUID();
            stubStaleIds(ids);
            when(taskRepository.findById(any()))
                    .thenAnswer(invocation -> Optional.of(runningTask(invocation.getArgument(0), employeeId, 1)));

            var report = worker.rescueStaleRunningTasks();

            assertThat(report.attempted())
                    .as("the real tick caps the batch at batchSize even if the query over-delivers")
                    .isEqualTo(BATCH_SIZE);
            verify(taskService, times(BATCH_SIZE)).markFailed(eq(employeeId), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("the rescue deadline (T58)")
    class TheRescueDeadline {

        @Test
        @DisplayName("a row below the ceiling is given the policy's backoff deadline")
        void belowTheCeilingGetsTheBackoffDeadline() {
            var taskId = UUID.randomUUID();
            var employeeId = UUID.randomUUID();
            stubStaleIds(taskId);
            when(taskRepository.findById(taskId)).thenReturn(Optional.of(runningTask(taskId, employeeId, 1)));

            var before = LocalDateTime.now().plusSeconds(BASE_DELAY_SECONDS);
            worker.rescueStaleRunningTasks();
            var after = LocalDateTime.now().plusSeconds(BASE_DELAY_SECONDS);

            var deadlineCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(taskService).markFailed(eq(employeeId), eq(taskId), any(), deadlineCaptor.capture());
            assertThat(deadlineCaptor.getValue())
                    .as("now + backoffFor(1), the same deadline every failure takes")
                    .isBetween(before, after);
        }

        @Test
        @DisplayName("a row at the ceiling is given a null deadline and stays FAILED")
        void atTheCeilingGetsANullDeadline() {
            var taskId = UUID.randomUUID();
            var employeeId = UUID.randomUUID();
            stubStaleIds(taskId);
            when(taskRepository.findById(taskId))
                    .thenReturn(Optional.of(runningTask(taskId, employeeId, MAX_ATTEMPTS)));

            worker.rescueStaleRunningTasks();

            verify(taskService).markFailed(eq(employeeId), eq(taskId), any(), isNull());
        }
    }

    @Nested
    @DisplayName("the rescue reason and the per-row containment")
    class TheRescueReasonAndContainment {

        @Test
        @DisplayName("names the staleness rescue and the configured threshold, within last_error's width")
        void namesTheRescueAndTheThreshold() {
            var taskId = UUID.randomUUID();
            var employeeId = UUID.randomUUID();
            stubStaleIds(taskId);
            when(taskRepository.findById(taskId)).thenReturn(Optional.of(runningTask(taskId, employeeId, 1)));

            worker.rescueStaleRunningTasks();

            var reasonCaptor = ArgumentCaptor.forClass(String.class);
            verify(taskService).markFailed(eq(employeeId), eq(taskId), reasonCaptor.capture(), any());
            assertThat(reasonCaptor.getValue())
                    .as("the reason names the staleness rescue")
                    .containsIgnoringCase("staleness rescue");
            assertThat(reasonCaptor.getValue())
                    .as("the reason names the threshold that fired")
                    .contains(String.valueOf(STALENESS_SECONDS));
            assertThat(reasonCaptor.getValue().length())
                    .as("last_error is VARCHAR(500), so the reason must fit")
                    .isLessThanOrEqualTo(500);
        }

        @Test
        @DisplayName("skips a row that vanished between the query and the rescue")
        void skipsAVanishedRow() {
            var vanished = UUID.randomUUID();
            var present = UUID.randomUUID();
            var employeeId = UUID.randomUUID();
            stubStaleIds(vanished, present);
            when(taskRepository.findById(vanished)).thenReturn(Optional.empty());
            when(taskRepository.findById(present)).thenReturn(Optional.of(runningTask(present, employeeId, 1)));

            var report = worker.rescueStaleRunningTasks();

            verify(taskService, never()).markFailed(any(), eq(vanished), any(), any());
            verify(taskService).markFailed(eq(employeeId), eq(present), any(), any());
            assertThat(report)
                    .as("the vanished row is attempted (the tick counts before invoking) but not failed")
                    .isEqualTo(new WorkerTick.TickReport(2, 0));
        }

        @Test
        @DisplayName("a refusal from markFailed does not abort the remaining rows")
        void oneRefusedRowDoesNotAbortTheRest() {
            var first = UUID.randomUUID();
            var refused = UUID.randomUUID();
            var last = UUID.randomUUID();
            var employeeId = UUID.randomUUID();
            stubStaleIds(first, refused, last);
            when(taskRepository.findById(any()))
                    .thenAnswer(invocation -> Optional.of(runningTask(invocation.getArgument(0), employeeId, 1)));
            when(taskService.markFailed(eq(employeeId), any(), any(), any())).thenAnswer(invocation -> {
                if (refused.equals(invocation.getArgument(1))) {
                    throw new InvalidTaskStatusTransitionException("APPLIED", "FAILED");
                }
                return null;
            });

            var report = worker.rescueStaleRunningTasks();

            verify(taskService).markFailed(eq(employeeId), eq(first), any(), any());
            verify(taskService).markFailed(eq(employeeId), eq(last), any(), any());
            assertThat(report)
                    .as("the refused row is contained and counted, and the pass keeps going")
                    .isEqualTo(new WorkerTick.TickReport(3, 1));
        }
    }

    @Nested
    @DisplayName("the pass (runPass)")
    class ThePass {

        @Test
        @DisplayName("runs the rescue before it queries or claims any due row")
        void rescueRunsBeforeTheDueQuery() {
            var taskId = UUID.randomUUID();
            var employeeId = UUID.randomUUID();
            var employee = Employee.builder().id(employeeId).build();
            when(taskRepository.findDueTaskIds(any(), any())).thenReturn(List.of(taskId));
            when(taskRepository.findById(taskId)).thenReturn(Optional.of(pendingTask(taskId, employeeId, ACTIVATE)));
            when(taskService.claim(employeeId, taskId)).thenReturn(claimedTask(taskId, employeeId, ACTIVATE, 1));
            when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
            when(handler.apply(employee, ACTIVATE)).thenReturn(Set.of());

            worker.runPass();

            var inOrder = inOrder(taskRepository, taskService);
            inOrder.verify(taskRepository).findStaleRunningTaskIds(any(), any());
            inOrder.verify(taskRepository).findDueTaskIds(any(), any());
            inOrder.verify(taskService).claim(employeeId, taskId);
        }

        @Test
        @DisplayName("reads the due clock once and bounds the query by batchSize")
        void boundsTheDueQueryByTheBatchSize() {
            var before = LocalDateTime.now();
            when(taskRepository.findDueTaskIds(any(), any())).thenReturn(List.of());

            worker.runPass();

            var nowCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
            var pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(taskRepository).findDueTaskIds(nowCaptor.capture(), pageableCaptor.capture());
            assertThat(nowCaptor.getValue())
                    .as("the due clock is the pass's own LocalDateTime, taken once")
                    .isBetween(before, LocalDateTime.now());
            assertThat(pageableCaptor.getValue())
                    .as("the query is bounded by the configured batch size")
                    .isEqualTo(PageRequest.of(0, BATCH_SIZE));
        }

        @Test
        @DisplayName("claims with (employeeId, taskId), dispatches the claimed kind and stores exactly the touched set")
        void dispatchesTheClaimedKindAndStoresTheTouchedSet() {
            var taskId = UUID.randomUUID();
            var employeeId = UUID.randomUUID();
            var employee = Employee.builder().id(employeeId).build();
            var touched = new TreeSet<>(Set.of("ROLE_A", "ROLE_B"));
            when(taskRepository.findDueTaskIds(any(), any())).thenReturn(List.of(taskId));
            // The unlocked read carries a stale kind on purpose: the dispatch must take the kind from
            // the claimed row, never from this snapshot (record T51 carries no authority).
            when(taskRepository.findById(taskId)).thenReturn(Optional.of(pendingTask(taskId, employeeId, RECONCILE)));
            when(taskService.claim(employeeId, taskId)).thenReturn(claimedTask(taskId, employeeId, ACTIVATE, 2));
            when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
            when(handler.apply(employee, ACTIVATE)).thenReturn(touched);

            var report = worker.runPass();

            var inOrder = inOrder(taskService, handler);
            inOrder.verify(taskService).claim(employeeId, taskId);
            // ACTIVATE is the claimed row's kind even though the unlocked row said RECONCILE.
            inOrder.verify(handler).apply(employee, ACTIVATE);

            ArgumentCaptor<Set<String>> touchedCaptor = ArgumentCaptor.captor();
            verify(taskService).markApplied(eq(employeeId), eq(taskId), touchedCaptor.capture());
            assertThat(touchedCaptor.getValue())
                    .as("markApplied receives exactly the set the handler returned")
                    .isSameAs(touched);
            assertThat(report).as("one claimed row, no failure").isEqualTo(new WorkerTick.TickReport(1, 0));
        }

        @Test
        @DisplayName("checks the re-gate after the claim and before the apply")
        void checksTheReGateAfterTheClaimAndBeforeTheApply() {
            var taskId = UUID.randomUUID();
            var employeeId = UUID.randomUUID();
            var employee = Employee.builder().id(employeeId).build();
            when(taskRepository.findDueTaskIds(any(), any())).thenReturn(List.of(taskId));
            when(taskRepository.findById(taskId)).thenReturn(Optional.of(pendingTask(taskId, employeeId, ACTIVATE)));
            when(taskService.claim(employeeId, taskId)).thenReturn(claimedTask(taskId, employeeId, ACTIVATE, 1));
            when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
            when(handler.apply(employee, ACTIVATE)).thenReturn(Set.of());

            worker.runPass();

            var inOrder = inOrder(taskService, gateService, handler);
            inOrder.verify(taskService).claim(employeeId, taskId);
            inOrder.verify(gateService).reenterGateIfReviewedSetChanged(employee, taskId);
            inOrder.verify(handler).apply(employee, ACTIVATE);
        }

        @Test
        @DisplayName("a re-gated task is left APPROVAL_PENDING: nothing is applied and nothing is failed")
        void reGatedTaskNeverAppliesAndIsNeverFailed() {
            var taskId = UUID.randomUUID();
            var employeeId = UUID.randomUUID();
            var employee = Employee.builder().id(employeeId).build();
            var claimed = claimedTask(taskId, employeeId, ACTIVATE, 1);
            when(taskRepository.findDueTaskIds(any(), any())).thenReturn(List.of(taskId));
            when(taskRepository.findById(taskId)).thenReturn(Optional.of(pendingTask(taskId, employeeId, ACTIVATE)));
            when(taskService.claim(employeeId, taskId)).thenReturn(claimed);
            when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
            // The real gate service returns the task to the gate: the worker must then stop.
            when(gateService.reenterGateIfReviewedSetChanged(employee, taskId)).thenAnswer(invocation -> {
                claimed.setStatus(APPROVAL_PENDING);
                return true;
            });

            var report = worker.runPass();

            assertThat(claimed.getStatus())
                    .as("the task is left at the gate, never applied and never failed")
                    .isEqualTo(APPROVAL_PENDING);
            verify(handler, never()).apply(any(), any());
            verify(taskService, never()).markApplied(any(), any(), any());
            verify(taskService, never()).markFailed(any(), any(), any(), any());
            assertThat(report)
                    .as("the early return is not a failure and not a throw")
                    .isEqualTo(new WorkerTick.TickReport(1, 0));
        }

        @Test
        @DisplayName("fails a claimed row with the exception's reason and a deadline from the claimed attempts")
        void handlerFailureFailsWithBackoffFromClaimedAttempts() {
            var taskId = UUID.randomUUID();
            var employeeId = UUID.randomUUID();
            var employee = Employee.builder().id(employeeId).build();
            when(taskRepository.findDueTaskIds(any(), any())).thenReturn(List.of(taskId));
            when(taskRepository.findById(taskId)).thenReturn(Optional.of(pendingTask(taskId, employeeId, ACTIVATE)));
            // attempts = 2 is the claimed, incremented count, not the unlocked row's 0.
            when(taskService.claim(employeeId, taskId)).thenReturn(claimedTask(taskId, employeeId, ACTIVATE, 2));
            when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
            when(handler.apply(employee, ACTIVATE))
                    .thenThrow(new AccountLinkRefusedException("account link refused for an ambiguous email"));

            var before = LocalDateTime.now().plus(retryPolicy.backoffFor(2));
            var report = worker.runPass();
            var after = LocalDateTime.now().plus(retryPolicy.backoffFor(2));

            var reasonCaptor = ArgumentCaptor.forClass(String.class);
            var deadlineCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(taskService)
                    .markFailed(eq(employeeId), eq(taskId), reasonCaptor.capture(), deadlineCaptor.capture());
            assertThat(reasonCaptor.getValue())
                    .as("the reason is the exception's message")
                    .contains("account link refused for an ambiguous email");
            assertThat(deadlineCaptor.getValue())
                    .as("now + backoffFor(2), composed from the claimed row's attempts")
                    .isBetween(before, after);
            verify(taskService, never()).markApplied(any(), any(), any());
            assertThat(report)
                    .as("the failure is contained, so the tick reports no throw")
                    .isEqualTo(new WorkerTick.TickReport(1, 0));
        }

        @Test
        @DisplayName("falls back to the exception's class name when its message is blank")
        void blankMessageFallsBackToTheClassName() {
            var taskId = UUID.randomUUID();
            var employeeId = UUID.randomUUID();
            var employee = Employee.builder().id(employeeId).build();
            when(taskRepository.findDueTaskIds(any(), any())).thenReturn(List.of(taskId));
            when(taskRepository.findById(taskId)).thenReturn(Optional.of(pendingTask(taskId, employeeId, ACTIVATE)));
            when(taskService.claim(employeeId, taskId)).thenReturn(claimedTask(taskId, employeeId, ACTIVATE, 1));
            when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
            when(handler.apply(employee, ACTIVATE)).thenThrow(new IllegalStateException("   "));

            worker.runPass();

            var reasonCaptor = ArgumentCaptor.forClass(String.class);
            verify(taskService).markFailed(eq(employeeId), eq(taskId), reasonCaptor.capture(), any());
            assertThat(reasonCaptor.getValue())
                    .as("a blank message has nothing to persist, so the class name is the reason")
                    .isEqualTo(IllegalStateException.class.getName());
        }

        @Test
        @DisplayName("gives a claimed row at the ceiling a null deadline so it stays FAILED")
        void atTheCeilingGetsANullDeadline() {
            var taskId = UUID.randomUUID();
            var employeeId = UUID.randomUUID();
            var employee = Employee.builder().id(employeeId).build();
            when(taskRepository.findDueTaskIds(any(), any())).thenReturn(List.of(taskId));
            when(taskRepository.findById(taskId)).thenReturn(Optional.of(pendingTask(taskId, employeeId, ACTIVATE)));
            when(taskService.claim(employeeId, taskId))
                    .thenReturn(claimedTask(taskId, employeeId, ACTIVATE, MAX_ATTEMPTS));
            when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
            when(handler.apply(employee, ACTIVATE)).thenThrow(new IllegalStateException("still failing"));

            worker.runPass();

            verify(taskService).markFailed(eq(employeeId), eq(taskId), any(), isNull());
            verify(taskService, never()).markApplied(any(), any(), any());
        }

        @Test
        @DisplayName("skips a vanished due row and still claims, dispatches and applies the real one beside it")
        void skipsAVanishedDueRow() {
            var vanished = UUID.randomUUID();
            var claimable = UUID.randomUUID();
            var employeeId = UUID.randomUUID();
            var employee = Employee.builder().id(employeeId).build();
            var touched = Set.of("ROLE_A");
            when(taskRepository.findDueTaskIds(any(), any())).thenReturn(List.of(vanished, claimable));
            when(taskRepository.findById(vanished)).thenReturn(Optional.empty());
            when(taskRepository.findById(claimable))
                    .thenReturn(Optional.of(pendingTask(claimable, employeeId, ACTIVATE)));
            when(taskService.claim(employeeId, claimable)).thenReturn(claimedTask(claimable, employeeId, ACTIVATE, 1));
            when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
            when(handler.apply(employee, ACTIVATE)).thenReturn(touched);

            var report = worker.runPass();

            // The vanished row is skipped: it is not this pass's row to claim, dispatch or fail.
            verify(taskService, never()).claim(any(), eq(vanished));
            verify(taskService, never()).markApplied(any(), eq(vanished), any());
            verify(taskService, never()).markFailed(any(), eq(vanished), any(), any());
            // The positive half in the same batch: the real row is claimed, dispatched and applied, so
            // this method cannot pass for a pass body that does nothing.
            verify(taskService).claim(employeeId, claimable);
            verify(handler).apply(employee, ACTIVATE);
            ArgumentCaptor<Set<String>> touchedCaptor = ArgumentCaptor.captor();
            verify(taskService).markApplied(eq(employeeId), eq(claimable), touchedCaptor.capture());
            assertThat(touchedCaptor.getValue())
                    .as("the real row is dispatched end to end in the same batch")
                    .isEqualTo(touched);
            assertThat(report)
                    .as("both rows are attempted: the vanished one is skipped, the real one is applied")
                    .isEqualTo(new WorkerTick.TickReport(2, 0));
        }

        @Test
        @DisplayName("contains a claim refused by the guard and still processes the next due row")
        void aRefusedClaimDoesNotAbortTheNextRow() {
            var first = UUID.randomUUID();
            var refused = UUID.randomUUID();
            var last = UUID.randomUUID();
            var employeeId = UUID.randomUUID();
            var employee = Employee.builder().id(employeeId).build();
            when(taskRepository.findDueTaskIds(any(), any())).thenReturn(List.of(first, refused, last));
            when(taskRepository.findById(any()))
                    .thenAnswer(
                            invocation -> Optional.of(pendingTask(invocation.getArgument(0), employeeId, ACTIVATE)));
            when(taskService.claim(employeeId, first)).thenReturn(claimedTask(first, employeeId, ACTIVATE, 1));
            when(taskService.claim(employeeId, refused))
                    .thenThrow(new InvalidTaskStatusTransitionException("APPLIED", "RUNNING"));
            when(taskService.claim(employeeId, last)).thenReturn(claimedTask(last, employeeId, ACTIVATE, 1));
            when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
            when(handler.apply(employee, ACTIVATE)).thenReturn(Set.of());

            var report = worker.runPass();

            verify(handler, times(2)).apply(employee, ACTIVATE);
            verify(taskService, never()).markFailed(any(), any(), any(), any());
            assertThat(report)
                    .as("the refused claim is contained; the first and last rows still ran")
                    .isEqualTo(new WorkerTick.TickReport(3, 1));
        }

        @Test
        @DisplayName("a missing employee still ends in markFailed rather than leaving the row RUNNING")
        void aMissingEmployeeIsStillFailed() {
            var taskId = UUID.randomUUID();
            var employeeId = UUID.randomUUID();
            when(taskRepository.findDueTaskIds(any(), any())).thenReturn(List.of(taskId));
            when(taskRepository.findById(taskId)).thenReturn(Optional.of(pendingTask(taskId, employeeId, ACTIVATE)));
            when(taskService.claim(employeeId, taskId)).thenReturn(claimedTask(taskId, employeeId, ACTIVATE, 1));
            when(employeeRepository.findById(employeeId)).thenReturn(Optional.empty());

            worker.runPass();

            verify(handler, never()).apply(any(), any());
            var reasonCaptor = ArgumentCaptor.forClass(String.class);
            verify(taskService).markFailed(eq(employeeId), eq(taskId), reasonCaptor.capture(), any());
            assertThat(reasonCaptor.getValue())
                    .as("the reason names the employee that could not be loaded")
                    .contains(employeeId.toString());
            verify(taskService, never()).markApplied(any(), any(), any());
        }

        @Test
        @DisplayName("bounds an over-long failure message so the recovery write cannot overflow last_error")
        void boundsAnOverLongFailureMessage() {
            var taskId = UUID.randomUUID();
            var employeeId = UUID.randomUUID();
            var employee = Employee.builder().id(employeeId).build();
            var longMessage = "x".repeat(600);
            when(taskRepository.findDueTaskIds(any(), any())).thenReturn(List.of(taskId));
            when(taskRepository.findById(taskId)).thenReturn(Optional.of(pendingTask(taskId, employeeId, ACTIVATE)));
            when(taskService.claim(employeeId, taskId)).thenReturn(claimedTask(taskId, employeeId, ACTIVATE, 1));
            when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
            when(handler.apply(employee, ACTIVATE)).thenThrow(new IllegalStateException(longMessage));

            worker.runPass();

            var reasonCaptor = ArgumentCaptor.forClass(String.class);
            verify(taskService).markFailed(eq(employeeId), eq(taskId), reasonCaptor.capture(), any());
            assertThat(reasonCaptor.getValue())
                    .as("last_error is VARCHAR(500): the reason is truncated, not passed through")
                    .hasSize(AccessProvisioningWorker.LAST_ERROR_MAX_LENGTH);
            assertThat(longMessage).startsWith(reasonCaptor.getValue());
        }
    }
}
