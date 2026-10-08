package com.lifecontrol.api.config.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.provisioning.service.AccessProvisioningWorker;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * Tests the conditional scheduling configuration and the scheduled pass holder.
 *
 * <p>Two groups, mirroring {@code ProvisioningWorkerConfigTest}: a plain JUnit group that drives the
 * holder by hand to pin the delegation and the propagation, and an {@link ApplicationContextRunner}
 * group that pins what the condition <b>structurally</b> puts in — or keeps out of — the context.</p>
 */
@DisplayName("ProvisioningWorkerSchedulerConfig Tests")
class ProvisioningWorkerSchedulerConfigTest {

    /**
     * Deliberately neither the {@code application.properties} default (60) nor a round minute: a
     * delay read back in seconds cannot be mistaken for a default that happens to be {@code 60_000} ms.
     */
    private static final int INTERVAL_SECONDS = 5;

    @Nested
    @DisplayName("The scheduled pass holder")
    class TheScheduledPass {

        private final AccessProvisioningWorker worker = mock(AccessProvisioningWorker.class);

        private final ProvisioningWorkerSchedulerConfig.ScheduledPass holder =
                new ProvisioningWorkerSchedulerConfig.ScheduledPass(worker);

        @Test
        @DisplayName("delegates to the worker's pass")
        void delegatesToWorkerPass() {
            holder.runPass();

            verify(worker).runPass();
        }

        @Test
        @DisplayName("lets a pass-level failure propagate instead of swallowing it")
        void propagatesPassFailure() {
            var failure = new IllegalStateException("broken due query");
            when(worker.runPass()).thenThrow(failure);

            assertThatThrownBy(holder::runPass).isSameAs(failure);
        }
    }

    @Nested
    @DisplayName("The conditional registration")
    class TheConditionalRegistration {

        private final AccessProvisioningWorker worker = mock(AccessProvisioningWorker.class);

        @Test
        @DisplayName("with the flag true, registers one fixed-delay task whose delay is the interval in seconds")
        void registeredWhenEnabled() {
            runner(true, true).run(context -> {
                var holder = context.getBean(ScheduledTaskHolder.class);

                assertThat(holder.getScheduledTasks()).singleElement().satisfies(task -> {
                    assertThat(task.getTask()).isInstanceOf(FixedDelayTask.class);
                    var fixedDelay = (FixedDelayTask) task.getTask();
                    // The property says 5 seconds and the annotation reads milliseconds, so the
                    // registered delay must be 5_000 ms. A bare 5 here would fail the assertion
                    // rather than silently scheduling a pass every 5 ms.
                    assertThat(fixedDelay.getIntervalDuration()).isEqualTo(Duration.ofSeconds(INTERVAL_SECONDS));
                });
            });
        }

        @Test
        @DisplayName("with the flag false, no scheduling post-processor and no registered task exist")
        void absentWhenDisabled() {
            runner(false, true)
                    .run(context -> assertThat(context.getBeansOfType(ScheduledTaskHolder.class))
                            .isEmpty());
        }

        @Test
        @DisplayName("with the flag absent entirely, no scheduling machinery exists")
        void absentWhenFlagMissing() {
            runner(false, false)
                    .run(context -> assertThat(context.getBeansOfType(ScheduledTaskHolder.class))
                            .isEmpty());
        }

        private ApplicationContextRunner runner(boolean enabled, boolean present) {
            var runner = new ApplicationContextRunner()
                    .withUserConfiguration(ProvisioningWorkerSchedulerConfig.class)
                    .withBean(AccessProvisioningWorker.class, () -> worker);
            if (present) {
                runner = runner.withPropertyValues(
                        "app.provisioning.worker.enabled=" + enabled,
                        "app.provisioning.worker.interval-seconds=" + INTERVAL_SECONDS);
            }
            return runner;
        }
    }
}
