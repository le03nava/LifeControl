package com.lifecontrol.api.config.provisioning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Announces the access-provisioning worker's switch at start-up (records S3-D and S5.3).
 *
 * <p><b>Why this component is not inside {@link ProvisioningWorkerSchedulerConfig}.</b> That
 * configuration is gated by
 * {@code @ConditionalOnProperty(prefix = "app.provisioning.worker", name = "enabled", havingValue = "true")},
 * so <b>with the worker off the class is not loaded at all</b> — the off state is exactly the one in
 * which a warning written there could never run. This component therefore lives outside that
 * condition and reads the property directly instead of injecting {@link ProvisioningWorkerProperties},
 * so it depends on no configuration class the switch itself gates.</p>
 *
 * <p><b>What it says is the consequence, not the value.</b> With the switch off, provisioning tasks
 * are still enqueued and are never applied, so no account is created and no activation email is sent;
 * the fix is to set {@code WORKER_ENABLED=true} for the environment. Logging only "worker disabled"
 * would restate the symptom the incident of record S5.1 spent a day on.</p>
 *
 * <p>Bound to {@link ApplicationReadyEvent}, so it runs once per start-up rather than per request.</p>
 */
@Component
public class ProvisioningWorkerStateLogger {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningWorkerStateLogger.class);

    private final boolean workerEnabled;

    /**
     * @param workerEnabled the effective {@code app.provisioning.worker.enabled} value; the binding's
     *     own default is {@code false}
     */
    public ProvisioningWorkerStateLogger(@Value("${app.provisioning.worker.enabled:false}") boolean workerEnabled) {
        this.workerEnabled = workerEnabled;
    }

    /**
     * States the worker's switch once the application is up: {@code INFO} when the pass runs,
     * {@code WARN} with the consequence when it does not.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void logState() {
        if (workerEnabled) {
            log.info("Access provisioning worker is ENABLED (app.provisioning.worker.enabled=true):"
                    + " enqueued provisioning tasks are applied by the scheduled pass.");
            return;
        }
        log.warn("Access provisioning worker is DISABLED (app.provisioning.worker.enabled=false):"
                + " provisioning tasks are enqueued but never applied, so no account is created and no"
                + " activation email is sent. Set WORKER_ENABLED=true for this environment to apply them.");
    }
}
