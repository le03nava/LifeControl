package com.lifecontrol.api.common.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Focused unit tests for {@link WorkerRetryPolicy}, the pure backoff and ceiling policy the access
 * worker (W4b) will drive. Every input is a parameter — {@code backoffFor} takes an attempt count and
 * returns a {@link java.time.Duration} — so the policy is provable without a clock, a container or a
 * Keycloak call, which is the whole reason W4 was cut at the substrate/execution seam (record T41).
 */
@DisplayName("WorkerRetryPolicy Tests")
class WorkerRetryPolicyTest {

    private static final Duration BASE = Duration.ofSeconds(10);
    private static final Duration MAX = Duration.ofMinutes(10);

    private final WorkerRetryPolicy policy = new WorkerRetryPolicy(5, BASE, MAX);

    @Test
    @DisplayName("should grow the delay exponentially from the base delay")
    void backoffGrows() {
        assertThat(policy.backoffFor(1)).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.backoffFor(2)).isEqualTo(Duration.ofSeconds(20));
        assertThat(policy.backoffFor(3)).isEqualTo(Duration.ofSeconds(40));
        assertThat(policy.backoffFor(4)).isEqualTo(Duration.ofSeconds(80));
        assertThat(policy.backoffFor(5)).isEqualTo(Duration.ofSeconds(160));
    }

    @Test
    @DisplayName("should cap the delay at the maximum instead of growing past it")
    void backoffIsCapped() {
        var capped = new WorkerRetryPolicy(10, Duration.ofSeconds(10), Duration.ofSeconds(30));

        assertThat(capped.backoffFor(1)).isEqualTo(Duration.ofSeconds(10));
        assertThat(capped.backoffFor(2)).isEqualTo(Duration.ofSeconds(20));
        assertThat(capped.backoffFor(3)).as("the uncapped 40s is capped to 30s").isEqualTo(Duration.ofSeconds(30));
        assertThat(capped.backoffFor(4)).isEqualTo(Duration.ofSeconds(30));
        assertThat(capped.backoffFor(30))
                .as("a very large attempt count does not overflow the cap")
                .isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("should treat an attempt below 1 as the first attempt, never as an undefined delay")
    void backoffBelowOneIsTheFirstRetry() {
        // The claim increments attempts before a failure is recorded, so attempts is >= 1 by
        // construction. Defining the below-1 boundary keeps the policy total: the unattended worker
        // cannot be stranded by an off-by-one.
        assertThat(policy.backoffFor(0)).isEqualTo(BASE);
        assertThat(policy.backoffFor(-3)).isEqualTo(BASE);
    }

    @Test
    @DisplayName("should report exhaustion at the maximum and beyond")
    void exhaustedAtTheMaximum() {
        assertThat(policy.exhausted(0)).isFalse();
        assertThat(policy.exhausted(4)).isFalse();
        assertThat(policy.exhausted(5))
                .as("attempts == maxAttempts is exhausted")
                .isTrue();
        assertThat(policy.exhausted(6)).isTrue();
    }
}
