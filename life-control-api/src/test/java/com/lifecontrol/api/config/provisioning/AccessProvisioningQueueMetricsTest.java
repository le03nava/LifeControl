package com.lifecontrol.api.config.provisioning;

import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus.APPLIED;
import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus.APPROVAL_PENDING;
import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus.FAILED;
import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus.PENDING;
import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus.REJECTED;
import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus.RUNNING;
import static com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus.SUPERSEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus;
import com.lifecontrol.api.provisioning.repository.AccessProvisioningTaskRepository;
import com.lifecontrol.api.provisioning.repository.AccessProvisioningTaskStatusCount;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Pins what the access-provisioning queue looks like from Prometheus (S11): one series per state and
 * the age of the oldest {@code PENDING} row.
 *
 * <p>The cases assert the <b>published numbers</b> — the value a scrape reads — against what the
 * repository answers, never the internal cells that carry them, so a rewrite that keeps the metric
 * names and the arithmetic still passes. The registry is a {@link SimpleMeterRegistry}: the behaviour
 * under test is the registration and the refresh, and a live Prometheus would add a scrape loop to the
 * test without adding an assertion.</p>
 *
 * <p>The failure and timer cases exist because both are ways this gauge could lie rather than break. A
 * failed read that published {@code 0} would read as "the queue is empty" exactly when the database is
 * unreachable, and a scheduled task that throws is cancelled by
 * {@link java.util.concurrent.ScheduledExecutorService#scheduleWithFixedDelay}, which would freeze the
 * gauge at its last value with nothing to say so.</p>
 */
@DisplayName("AccessProvisioningQueueMetrics Tests")
class AccessProvisioningQueueMetricsTest {

    private final AccessProvisioningTaskRepository taskRepository = mock(AccessProvisioningTaskRepository.class);

    private MeterRegistry meterRegistry;
    private AccessProvisioningQueueMetrics metrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        metrics = new AccessProvisioningQueueMetrics(taskRepository, meterRegistry);
    }

    @AfterEach
    void tearDown() {
        metrics.stopRefreshing();
        ((SimpleMeterRegistry) meterRegistry).close();
    }

    @Nested
    @DisplayName("The registered series")
    class TheRegisteredSeries {

        @Test
        @DisplayName("registers one state-tagged gauge per status the entity has, all at zero before the first read")
        void registersOneGaugePerState() {
            assertThat(meterRegistry
                            .get(AccessProvisioningQueueMetrics.TASKS_METRIC)
                            .gauges())
                    .hasSize(AccessProvisioningTaskStatus.values().length);
            for (var status : AccessProvisioningTaskStatus.values()) {
                assertThat(meterRegistry
                                .find(AccessProvisioningQueueMetrics.TASKS_METRIC)
                                .tag(AccessProvisioningQueueMetrics.STATE_TAG, status.name())
                                .gauge())
                        .isNotNull();
                assertThat(depthOf(status)).isZero();
            }
        }

        @Test
        @DisplayName("registers the oldest-pending age in seconds, which is the unit Prometheus names it with")
        void registersTheAgeInSeconds() {
            var gauge = meterRegistry
                    .find(AccessProvisioningQueueMetrics.OLDEST_PENDING_AGE_METRIC)
                    .gauge();

            assertThat(gauge).isNotNull();
            assertThat(gauge.getId().getBaseUnit()).isEqualTo("seconds");
            assertThat(gauge.value()).isZero();
        }
    }

    @Nested
    @DisplayName("The published queue depth")
    class ThePublishedQueueDepth {

        @Test
        @DisplayName("publishes the repository's count per state, and zero for a state the table has no row in")
        void publishesTheCountPerState() {
            givenCounts(count(PENDING, 3), count(APPROVAL_PENDING, 1), count(RUNNING, 2), count(APPLIED, 40));

            metrics.refresh();

            assertThat(depthOf(PENDING)).isEqualTo(3);
            assertThat(depthOf(APPROVAL_PENDING)).isEqualTo(1);
            assertThat(depthOf(RUNNING)).isEqualTo(2);
            assertThat(depthOf(APPLIED)).isEqualTo(40);
            // A status with no rows is absent from the grouped read, and "absent" has to read as zero:
            // an omitted series would make an empty state and an uninstrumented one indistinguishable.
            assertThat(depthOf(FAILED)).isZero();
            assertThat(depthOf(REJECTED)).isZero();
            assertThat(depthOf(SUPERSEDED)).isZero();
        }

        @Test
        @DisplayName("moves to the new numbers on the next refresh instead of keeping the first read")
        void followsTheQueue() {
            givenCounts(count(PENDING, 5));
            metrics.refresh();
            assertThat(depthOf(PENDING)).isEqualTo(5);

            givenCounts(count(PENDING, 1), count(RUNNING, 4));
            metrics.refresh();

            assertThat(depthOf(PENDING)).isEqualTo(1);
            assertThat(depthOf(RUNNING)).isEqualTo(4);
        }

        @Test
        @DisplayName("reads an empty table as zero everywhere, not as a missing series")
        void emptyTableReadsZero() {
            givenCounts();
            givenOldestPendingRequestedAt(null);

            metrics.refresh();

            assertThat(meterRegistry
                            .get(AccessProvisioningQueueMetrics.TASKS_METRIC)
                            .gauges())
                    .hasSize(AccessProvisioningTaskStatus.values().length)
                    .allSatisfy(gauge -> assertThat(gauge.value()).isZero());
            assertThat(oldestPendingAgeSeconds()).isZero();
        }
    }

    @Nested
    @DisplayName("The oldest pending age")
    class TheOldestPendingAge {

        @Test
        @DisplayName("is the age of the oldest PENDING row, in seconds")
        void isTheAgeOfTheOldestPendingRow() {
            givenCounts(count(PENDING, 2));
            givenOldestPendingRequestedAt(LocalDateTime.now().minusSeconds(3600));

            metrics.refresh();

            // One hour of waiting has to read as one hour: this is the number that turns "the queue has
            // items" into "the queue has been stuck". The window absorbs the clock read between the
            // fixture and the refresh; it is not a tolerance on the arithmetic.
            assertThat(oldestPendingAgeSeconds()).isBetween(3599.0, 3605.0);
        }

        @Test
        @DisplayName("follows the oldest row, so a cleared backlog reads as recovered")
        void followsTheOldestRow() {
            givenOldestPendingRequestedAt(LocalDateTime.now().minusSeconds(60));
            metrics.refresh();
            assertThat(oldestPendingAgeSeconds()).isBetween(59.0, 65.0);

            givenOldestPendingRequestedAt(LocalDateTime.now().minusSeconds(7200));
            metrics.refresh();

            assertThat(oldestPendingAgeSeconds()).isBetween(7199.0, 7205.0);
        }

        @Test
        @DisplayName("reads zero, not a negative or unknown age, when nothing is pending")
        void isZeroWithoutPendingWork() {
            givenCounts(count(APPLIED, 7));
            givenOldestPendingRequestedAt(null);

            metrics.refresh();

            assertThat(oldestPendingAgeSeconds()).isZero();
        }

        @Test
        @DisplayName("is measured on PENDING, the only status the worker claims")
        void isMeasuredOnPending() {
            givenOldestPendingRequestedAt(null);

            metrics.refresh();

            assertThat(AccessProvisioningQueueMetrics.AGE_STATUS).isEqualTo(PENDING);
            verify(taskRepository).findOldestRequestedAtByStatus(PENDING);
        }
    }

    @Nested
    @DisplayName("A failed refresh")
    class AFailedRefresh {

        @Test
        @DisplayName("keeps the last measurement instead of publishing zero or throwing into the timer")
        void keepsTheLastMeasurement() {
            givenCounts(count(PENDING, 6));
            givenOldestPendingRequestedAt(LocalDateTime.now().minusSeconds(120));
            metrics.refresh();
            var measuredAge = oldestPendingAgeSeconds();
            assertThat(depthOf(PENDING)).isEqualTo(6);

            when(taskRepository.countTasksByStatus()).thenThrow(new IllegalStateException("database is down"));

            assertThatNoException().isThrownBy(metrics::refreshQuietly);
            assertThat(depthOf(PENDING)).isEqualTo(6);
            assertThat(oldestPendingAgeSeconds()).isEqualTo(measuredAge);
        }
    }

    @Nested
    @DisplayName("The refresher")
    class TheRefresher {

        @Test
        @DisplayName("publishes the queue without anyone calling refresh")
        void publishesOnItsOwnTimer() throws InterruptedException {
            givenCounts(count(PENDING, 4));
            givenOldestPendingRequestedAt(LocalDateTime.now().minusSeconds(30));

            metrics.startRefreshing();

            // The first execution has no initial delay, so this converges immediately; the bound fails
            // loudly instead of hanging if the timer never ran at all.
            var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (depthOf(PENDING) != 4 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }

            assertThat(depthOf(PENDING)).isEqualTo(4);
            assertThat(oldestPendingAgeSeconds()).isBetween(29.0, 35.0);
        }
    }

    @Nested
    @DisplayName("The worker switch")
    class TheWorkerSwitch {

        @Test
        @DisplayName("does not gate the gauges: with the worker off they are still registered and still read the table")
        void registeredWithTheWorkerDisabled() {
            givenCounts(count(PENDING, 9));
            // A registry of this context's own: Micrometer's registration is idempotent per name and
            // tags, so reusing the field's registry would hand back the gauges the fixture already
            // registered and the assertion would read the wrong component's cells.
            var contextRegistry = new SimpleMeterRegistry();

            new ApplicationContextRunner()
                    .withPropertyValues("app.provisioning.worker.enabled=false")
                    .withBean(AccessProvisioningTaskRepository.class, () -> taskRepository)
                    .withBean(MeterRegistry.class, () -> contextRegistry)
                    .withUserConfiguration(AccessProvisioningQueueMetrics.class)
                    .run(context -> {
                        assertThat(context).hasSingleBean(AccessProvisioningQueueMetrics.class);
                        // The switch being off is exactly when a growing queue matters most, so the
                        // series must exist and describe the real table rather than report zero.
                        assertThat(contextDepthOf(contextRegistry, PENDING)).isZero();
                        context.getBean(AccessProvisioningQueueMetrics.class).refresh();
                        assertThat(contextDepthOf(contextRegistry, PENDING)).isEqualTo(9);
                    });
        }
    }

    private static AccessProvisioningTaskStatusCount count(AccessProvisioningTaskStatus status, long total) {
        return new AccessProvisioningTaskStatusCount(status, total);
    }

    private void givenCounts(AccessProvisioningTaskStatusCount... counts) {
        when(taskRepository.countTasksByStatus()).thenReturn(List.of(counts));
    }

    private void givenOldestPendingRequestedAt(LocalDateTime requestedAt) {
        when(taskRepository.findOldestRequestedAtByStatus(AccessProvisioningQueueMetrics.AGE_STATUS))
                .thenReturn(requestedAt);
    }

    private double depthOf(AccessProvisioningTaskStatus status) {
        return contextDepthOf(meterRegistry, status);
    }

    private static double contextDepthOf(MeterRegistry registry, AccessProvisioningTaskStatus status) {
        return registry.get(AccessProvisioningQueueMetrics.TASKS_METRIC)
                .tag(AccessProvisioningQueueMetrics.STATE_TAG, status.name())
                .gauge()
                .value();
    }

    private double oldestPendingAgeSeconds() {
        return meterRegistry
                .get(AccessProvisioningQueueMetrics.OLDEST_PENDING_AGE_METRIC)
                .gauge()
                .value();
    }
}
