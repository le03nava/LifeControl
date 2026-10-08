package com.lifecontrol.api.config.provisioning;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the access-<b>provisioning worker</b> — whether the pass runs, how
 * often, and the retry and staleness policy that governs a row it claimed.
 *
 * <p>Prefix: {@code app.provisioning.worker}. Units live <b>in the property names</b>
 * ({@code ...Seconds}), mirroring {@link com.lifecontrol.api.config.invitation.InvitationProperties#lifespanSeconds}:
 * a bare {@code Duration} binding would make a bare number mean milliseconds, which is exactly the
 * ambiguity an operator writing a docker env value must not have — {@code 60} in
 * {@code WORKER_INTERVAL_SECONDS} has to mean a minute, not 60 ms.</p>
 *
 * <p>{@code enabled} is a primitive {@code boolean}, so an absent binding means <b>disabled</b>. That
 * is the fail-safe direction for a worker that mutates another system: forgetting to set the flag
 * stops the work instead of starting it.</p>
 *
 * <p>Consumed by this unit: {@code maxAttempts}, {@code baseDelaySeconds} and {@code maxDelaySeconds},
 * which {@link ProvisioningWorkerConfig} turns into the {@code WorkerRetryPolicy} bean, plus
 * {@code batchSize} and {@code stalenessThresholdSeconds}, consumed here by that holder's
 * {@code WorkerTick} bean and by {@link com.lifecontrol.api.provisioning.service.AccessProvisioningWorker}'s
 * rescue pass. The remaining values — {@code enabled} and {@code intervalSeconds} — are <b>bound but
 * not consumed</b>: record T52 places their consumer with the scheduler in the next unit, and this
 * unit does not claim every value has a consumer on the day it lands.</p>
 *
 * <p>Supplied by {@code application.properties} as
 * {@code app.provisioning.worker.enabled=${WORKER_ENABLED:false}} and the {@code WORKER_*} siblings.</p>
 *
 * @param enabled whether the worker pass runs at all; absent means {@code false} (disabled)
 * @param intervalSeconds how often the pass ticks, in seconds
 * @param maxAttempts the number of attempts after which a failed task is exhausted and stays
 *     {@code FAILED}
 * @param batchSize how many claimable rows one tick takes at most
 * @param baseDelaySeconds the delay of the first retry, in seconds
 * @param maxDelaySeconds the ceiling no retry delay ever exceeds, in seconds
 * @param stalenessThresholdSeconds how long a {@code RUNNING} row may stay claimed before another
 *     tick may reclaim it, in seconds
 */
@ConfigurationProperties(prefix = "app.provisioning.worker")
public record ProvisioningWorkerProperties(
        boolean enabled,
        Integer intervalSeconds,
        Integer maxAttempts,
        Integer batchSize,
        Integer baseDelaySeconds,
        Integer maxDelaySeconds,
        Integer stalenessThresholdSeconds) {}
