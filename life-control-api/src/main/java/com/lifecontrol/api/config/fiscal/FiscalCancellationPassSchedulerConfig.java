package com.lifecontrol.api.config.fiscal;

import com.lifecontrol.api.fiscal.service.FiscalCancellationPass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Puts the fiscal cancellation pass on a schedule — and only while a deployment has opted into it ({@code S6} of issue
 * {@code #994}, {@code T5} of {@code odd/tasks/fiscal-cancellation-cadence.md}).
 *
 * <p><b>Off by default is structural, not a check inside a running job.</b> This is the same decision
 * {@code ProvisioningWorkerSchedulerConfig} states, copied rather than re-derived, because the failure it removes is
 * the same one: with {@code app.fiscal.cancellation.pass.enabled} absent or {@code false}, no scheduling
 * post-processor, no scheduler thread pool and no registered task exist in the context at all. A condition that only
 * skipped the method body would leave a thread prowling every interval while the "disabled" pass stayed green and
 * silent.</p>
 *
 * <p><b>Why the annotation lives here and not on the application class.</b> {@code LifeControlApiApplication} is
 * deliberately bare, and {@code @EnableScheduling} appears only in a configuration that a property can remove. A second
 * conditional configuration is the same idiom: the two of them can be on together — Spring registers the scheduler
 * infrastructure once — and with both off the context owns no scheduler, which is what "off" has to mean. Enabling
 * this pass therefore never enables the provisioning worker, and vice versa.</p>
 *
 * <p><b>{@code fixedDelay} and not {@code fixedRate}, and the property is seconds.</b> A pass runs a database read and,
 * per document, an HTTPS round trip to the authority, so its duration is bounded by somebody else's server. With
 * {@code fixedRate} a slow pass would start a second overlapping pass in the same instance; {@code fixedDelay} measures
 * from the end of the previous one. The binding is {@code interval-seconds} — the unit is in the name so a bare number
 * cannot be read as milliseconds — while {@code @Scheduled} reads a bare {@code fixedDelayString} as milliseconds, so
 * the {@code * 1000} is stated in the expression instead of being hidden in a concatenated literal.</p>
 *
 * <p><b>A pass-level failure is meant to escape.</b> The scheduled method has no catch: a broken query throws out of
 * {@link FiscalCancellationPass#runPass()} into Spring's scheduled-task error handler, which logs it and keeps the
 * schedule alive. Catching it would make a dead enumeration look exactly like a system with nothing to settle, which is
 * the silent state this unit exists to remove. Per-document and per-tenant failures are already contained inside the
 * pass.</p>
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.fiscal.cancellation.pass", name = "enabled", havingValue = "true")
public class FiscalCancellationPassSchedulerConfig {

    /**
     * The bean whose method {@link Scheduled} drives; it exists only because the class above passed its condition.
     *
     * @param pass the pass to run
     * @return the holder registered with this context
     */
    @Bean
    public ScheduledPass fiscalCancellationScheduledPass(FiscalCancellationPass pass) {
        return new ScheduledPass(pass);
    }

    /**
     * The scheduled entry point, kept as a nested holder so the domain pass carries no scheduling state and can be
     * driven by a test, an operator or a future manual trigger without Spring's scheduler in the way.
     */
    static final class ScheduledPass {

        private final FiscalCancellationPass pass;

        ScheduledPass(FiscalCancellationPass pass) {
            this.pass = pass;
        }

        /**
         * Runs one pass every {@code app.fiscal.cancellation.pass.interval-seconds} seconds, measured from the end of
         * the previous pass. The property always resolves here: this class is loaded only when the pass is enabled,
         * and {@code application.properties} supplies a default for the interval.
         */
        @Scheduled(fixedDelayString = "#{${app.fiscal.cancellation.pass.interval-seconds} * 1000}")
        void runPass() {
            pass.runPass();
        }
    }
}
