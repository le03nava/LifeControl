package com.lifecontrol.api.config.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.common.worker.WorkerRetryPolicy;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Tests the worker retry policy producer and the binding that feeds it.
 *
 * <p>There is no local precedent for testing a {@code @ConfigurationProperties} holder: the
 * invitation holder ({@code config.invitation.InvitationProperties}) has no test at all. The shape
 * therefore follows {@code config.security.JwtDecoderConfigTest}, the repository's precedent for
 * testing a {@code @Configuration} class — plain JUnit constructing the config and passing an
 * explicit properties record — plus a Spring {@link ApplicationContextRunner} group to prove the
 * values really travel from properties into the bean.</p>
 */
@DisplayName("ProvisioningWorkerConfig Tests")
class ProvisioningWorkerConfigTest {

    /** Producer group: deliberately compact values so every branch is reachable by hand. */
    private static final int PRODUCER_MAX_ATTEMPTS = 4;

    private static final int PRODUCER_BASE_DELAY_SECONDS = 15;
    private static final int PRODUCER_MAX_DELAY_SECONDS = 60;

    @Nested
    @DisplayName("The producer")
    class TheProducer {

        private final ProvisioningWorkerConfig config = new ProvisioningWorkerConfig();

        private final ProvisioningWorkerProperties properties = new ProvisioningWorkerProperties(
                true, 30, PRODUCER_MAX_ATTEMPTS, 10, PRODUCER_BASE_DELAY_SECONDS, PRODUCER_MAX_DELAY_SECONDS, 900);

        @Test
        @DisplayName("backoffFor(1) is the configured base delay")
        void backoffForFirstAttemptIsBaseDelay() {
            var policy = config.workerRetryPolicy(properties);

            assertThat(policy).isNotNull();
            assertThat(policy.backoffFor(1)).isEqualTo(Duration.ofSeconds(PRODUCER_BASE_DELAY_SECONDS));
        }

        @Test
        @DisplayName("backoffFor(2) doubles the base delay")
        void backoffForSecondAttemptDoubles() {
            var policy = config.workerRetryPolicy(properties);

            assertThat(policy).isNotNull();
            assertThat(policy.backoffFor(2)).isEqualTo(Duration.ofSeconds(PRODUCER_BASE_DELAY_SECONDS * 2L));
        }

        @Test
        @DisplayName("a high attempt count is capped at the configured maximum delay")
        void backoffIsCappedAtMaxDelay() {
            var policy = config.workerRetryPolicy(properties);

            assertThat(policy).isNotNull();
            assertThat(policy.backoffFor(10)).isEqualTo(Duration.ofSeconds(PRODUCER_MAX_DELAY_SECONDS));
        }

        @Test
        @DisplayName("exhausted flips exactly at maxAttempts")
        void exhaustedFlipsAtMaxAttempts() {
            var policy = config.workerRetryPolicy(properties);

            assertThat(policy).isNotNull();
            assertThat(policy.exhausted(PRODUCER_MAX_ATTEMPTS - 1)).isFalse();
            assertThat(policy.exhausted(PRODUCER_MAX_ATTEMPTS)).isTrue();
            assertThat(policy.exhausted(PRODUCER_MAX_ATTEMPTS + 1)).isTrue();
        }
    }

    @Nested
    @DisplayName("The binding and the bean in a real Spring context")
    class TheBinding {

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(ProvisioningWorkerConfig.class)
                .withPropertyValues(
                        "app.provisioning.worker.max-attempts=7",
                        "app.provisioning.worker.base-delay-seconds=15",
                        "app.provisioning.worker.max-delay-seconds=120");

        @Test
        @DisplayName("binds the properties and produces a policy that follows the bound values")
        void bindsPropertiesAndProducesPolicy() {
            runner.run(context -> {
                assertThat(context).hasSingleBean(ProvisioningWorkerProperties.class);

                var policy = context.getBeanProvider(WorkerRetryPolicy.class).getIfAvailable();

                assertThat(policy).isNotNull();
                // Each assertion is distinct from the application.properties defaults (base 30 s,
                // max-delay 3600 s, max-attempts 5): the policy follows the bound values.
                assertThat(policy.backoffFor(1)).isEqualTo(Duration.ofSeconds(15));
                assertThat(policy.backoffFor(2)).isEqualTo(Duration.ofSeconds(30));
                assertThat(policy.backoffFor(6)).isEqualTo(Duration.ofSeconds(120));
                assertThat(policy.exhausted(6)).isFalse();
                assertThat(policy.exhausted(7)).isTrue();
            });
        }
    }
}
