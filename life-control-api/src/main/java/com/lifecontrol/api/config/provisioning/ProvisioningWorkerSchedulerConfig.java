package com.lifecontrol.api.config.provisioning;

import com.lifecontrol.api.provisioning.service.AccessProvisioningWorker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Puts the provisioning worker's pass on a schedule — and only while the worker is enabled.
 *
 * <p><b>Off by default is structural, not a check inside a running job (records T51 and T45).</b> The
 * whole class is gated by {@link ConditionalOnProperty}: with {@code app.provisioning.worker.enabled}
 * absent or {@code false}, no {@link EnableScheduling}, no scheduling post-processor, no scheduler
 * thread pool and no registered task exist in the context at all. {@code @EnableScheduling} therefore
 * lives here rather than on {@code LifeControlApiApplication}, whose bare {@code @SpringBootApplication}
 * deliberately enables no scheduling: a condition that only skipped the method body would leave a
 * thread pool ticking forever while the "disabled" worker stayed green and silent.</p>
 *
 * <p><b>Why {@code fixedDelay} and not {@code fixedRate}.</b> A pass runs synchronously through a
 * Keycloak round trip and a database write, so its duration is not bounded by the interval. With
 * {@code fixedRate} the next execution is scheduled relative to the <em>start</em> of the previous
 * one, so a pass that outlives its period would immediately start a second overlapping pass inside the
 * same instance — two threads converging the same employee's roles. {@code fixedDelay} measures from
 * the end of the previous run, so one instance never overlaps itself.</p>
 *
 * <p><b>The property is seconds; the annotation reads milliseconds (record T61).</b> The binding is
 * {@code interval-seconds} and a bare number must mean seconds, never milliseconds, which is the whole
 * reason the property name carries its unit. {@code @Scheduled}, however, reads a bare
 * {@code fixedDelayString} as milliseconds, so the conversion is stated in the expression as a
 * multiplication rather than hidden in a concatenated literal: a reader sees the {@code * 1000} and
 * cannot mistake {@code 60} for 60 ms.</p>
 *
 * <p><b>A pass-level failure is meant to escape.</b> The scheduled method has no catch. A broken query
 * throws out of {@link AccessProvisioningWorker#runPass()} and out of here, where Spring's own
 * scheduled-task error handler logs it and keeps the schedule alive for the next period. Catching it
 * would make a dead query look exactly like an idle worker — green, quiet and doing nothing — which is
 * the failure mode this worker exists to avoid. Per-row failures are already contained by the worker's
 * own tick, so nothing recoverable reaches this boundary.</p>
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.provisioning.worker", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ProvisioningWorkerProperties.class)
public class ProvisioningWorkerSchedulerConfig {

    /**
     * The bean whose method {@link Scheduled} drives; it exists only because the class above passed its
     * condition.
     *
     * @param worker the pass to run
     * @return the holder registered with this context
     */
    @Bean
    public ScheduledPass provisioningWorkerScheduledPass(AccessProvisioningWorker worker) {
        return new ScheduledPass(worker);
    }

    /**
     * The scheduled entry point: one fixed-delay method that delegates to the worker's full pass.
     *
     * <p>Kept as a nested holder rather than folding the annotation onto
     * {@link AccessProvisioningWorker} so the domain pass stays free of scheduling state — it can be
     * driven by a test, an operator or a future manual trigger without dragging Spring's scheduler into
     * the domain class. The method is package-private; {@link Scheduled} reflects over it and the
     * holder is only reached through its bean.</p>
     */
    static final class ScheduledPass {

        private final AccessProvisioningWorker worker;

        ScheduledPass(AccessProvisioningWorker worker) {
            this.worker = worker;
        }

        /**
         * Runs one pass every {@code app.provisioning.worker.interval-seconds} seconds, measured from
         * the end of the previous pass.
         *
         * <p>The delay is composed from the bound property with the seconds-to-milliseconds conversion
         * stated in the expression: the binding is seconds and the annotation reads milliseconds, so the
         * {@code * 1000} <em>is</em> the conversion rather than a formatting detail. A missing
         * {@code interval-seconds} cannot reach here: this class is only loaded when the worker is
         * enabled, and {@code application.properties} supplies a default for the property.</p>
         */
        @Scheduled(fixedDelayString = "#{${app.provisioning.worker.interval-seconds} * 1000}")
        void runPass() {
            worker.runPass();
        }
    }
}
