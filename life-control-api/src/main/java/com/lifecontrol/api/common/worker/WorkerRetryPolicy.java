package com.lifecontrol.api.common.worker;

import java.time.Duration;

/**
 * The retry backoff and ceiling policy of the platform worker, as <b>pure functions</b>.
 *
 * <p>This class is the reusable half of the worker (record T44): it knows nothing about a task kind,
 * a provisioning table or a Keycloak call, so the same policy can drive the group mirror. It is a
 * plain class with a constructor — <b>not</b> a {@code @Component} and <b>not</b> a
 * {@code @Configuration} — and it reads <b>no properties</b>: the {@code app.provisioning.worker.*}
 * bindings belong to W4b (record T45), which constructs one of these from them.</p>
 *
 * <p>The class reads no clock at all. {@link #backoffFor(int)} takes no time input and returns a
 * {@link Duration}, so the policy owns <b>"how long"</b> while the caller owns <b>"what time is it
 * now"</b>. That is what keeps {@code common/worker/} free of an absolute-time type and stops this
 * policy from growing a clock of its own: the caller composes the deadline, for example
 * {@code LocalDateTime.now().plus(policy.backoffFor(attempts))}.</p>
 *
 * <p>The delay grows exponentially from {@link #baseDelay} — {@code baseDelay * 2^(attempts - 1)} —
 * and is capped at {@link #maxDelay}. {@code attempts} is at least 1 by construction, because the
 * claim increments it before a failure can be recorded (record T18); an attempt below 1 is defined
 * as the first retry rather than left undefined, so the class is total and an off-by-one cannot
 * strand the unattended worker.</p>
 */
public class WorkerRetryPolicy {

    private final int maxAttempts;
    private final Duration baseDelay;
    private final Duration maxDelay;

    /**
     * @param maxAttempts the number of attempts after which {@link #exhausted(int)} is {@code true}
     * @param baseDelay the delay of the first retry (the {@code attempts == 1} delay)
     * @param maxDelay the ceiling no delay ever exceeds
     */
    public WorkerRetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay) {
        this.maxAttempts = maxAttempts;
        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
    }

    /**
     * The delay to wait before retrying after {@code attempts} failed attempts, doubling from
     * {@link #baseDelay} and never exceeding {@link #maxDelay}.
     *
     * <p>{@code backoffFor(1)} is {@link #baseDelay}; every further attempt doubles. A non-positive
     * attempt count is treated as the first attempt, which is the only value an incremented claim can
     * actually produce.</p>
     */
    public Duration backoffFor(int attempts) {
        if (attempts <= 1) {
            return cap(baseDelay);
        }
        var delay = baseDelay;
        for (var i = 1; i < attempts; i++) {
            if (delay.compareTo(maxDelay) >= 0) {
                return maxDelay;
            }
            delay = delay.multipliedBy(2);
        }
        return cap(delay);
    }

    /**
     * Whether the task has used up its attempts. At the ceiling the worker stops enqueueing a retry
     * and the task stays {@code FAILED} — the operator's manual retry is the only way back (record
     * T43).
     */
    public boolean exhausted(int attempts) {
        return attempts >= maxAttempts;
    }

    private Duration cap(Duration delay) {
        return delay.compareTo(maxDelay) > 0 ? maxDelay : delay;
    }
}
