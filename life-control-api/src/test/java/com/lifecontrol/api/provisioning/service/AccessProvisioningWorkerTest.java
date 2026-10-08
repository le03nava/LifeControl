package com.lifecontrol.api.provisioning.service;

import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind.ACTIVATE;
import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus.RUNNING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.common.worker.WorkerRetryPolicy;
import com.lifecontrol.api.common.worker.WorkerTick;
import com.lifecontrol.api.config.provisioning.ProvisioningWorkerProperties;
import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.provisioning.exception.InvalidTaskStatusTransitionException;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTask;
import com.lifecontrol.api.provisioning.repository.AccessProvisioningTaskRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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
 * Focused unit tests for {@link AccessProvisioningWorker}'s rescue pass.
 *
 * <p>The repository and the state machine are Mockito mocks — the pass's contract is which ids it
 * hands to the guard, with which deadline, and what it does when the guard refuses one — but the
 * {@link WorkerTick} is <b>real</b>, so the containment claim ("one bad row cannot abort the batch")
 * is exercised by the same loop production uses rather than asserted about a mock.</p>
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

    private AccessProvisioningWorker worker;

    @BeforeEach
    void setUp() {
        var properties = new ProvisioningWorkerProperties(
                false, 60, MAX_ATTEMPTS, BATCH_SIZE, BASE_DELAY_SECONDS, MAX_DELAY_SECONDS, STALENESS_SECONDS);
        worker = new AccessProvisioningWorker(
                taskRepository,
                taskService,
                new WorkerTick(BATCH_SIZE),
                new WorkerRetryPolicy(
                        MAX_ATTEMPTS, Duration.ofSeconds(BASE_DELAY_SECONDS), Duration.ofSeconds(MAX_DELAY_SECONDS)),
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
}
