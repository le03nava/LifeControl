package com.lifecontrol.api.config.provisioning;

import com.lifecontrol.api.common.worker.WorkerRetryPolicy;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Enables binding of {@link ProvisioningWorkerProperties} and produces the worker's retry policy.
 *
 * <p>Mirrors {@code config.invitation.InvitationConfig}: a {@code @Configuration} class placed beside
 * its properties record, registering it with {@code @EnableConfigurationProperties}. The repository
 * registers every properties record this way rather than through component scanning.</p>
 *
 * <p>This class is the <b>producer</b> of the policy that decides every retry deadline: before it,
 * {@link WorkerRetryPolicy} was a plain class that read no properties and was built by nothing in
 * {@code src/main}, so the policy would have reached production only because someone remembered to
 * construct it (record T45). The bean below is the single place where {@code app.provisioning.worker.*}
 * values become that policy, and it is the same idiom the platform already uses for its other
 * configured collaborators: convert the {@code ...Seconds} bindings with
 * {@link Duration#ofSeconds(long)} at the boundary, never inside the policy.</p>
 *
 * <p><b>Out of scope for this unit:</b> there is no scheduler here — no {@code @EnableScheduling}, no
 * {@code @Scheduled}, no {@code @ConditionalOnProperty} and no {@code TaskScheduler} — and no Keycloak
 * surface. There is likewise no consumer for {@code enabled}, {@code intervalSeconds},
 * {@code batchSize} or {@code stalenessThresholdSeconds}: record T52 places those consumers with the
 * scheduler in the next unit.</p>
 */
@Configuration
@EnableConfigurationProperties(ProvisioningWorkerProperties.class)
public class ProvisioningWorkerConfig {

    /**
     * The retry backoff and ceiling policy of the worker, built from the bound properties.
     *
     * @param properties the bound {@code app.provisioning.worker.*} values
     * @return a policy whose base delay, ceiling and attempt limit come from configuration
     */
    @Bean
    public WorkerRetryPolicy workerRetryPolicy(ProvisioningWorkerProperties properties) {
        return new WorkerRetryPolicy(
                properties.maxAttempts(),
                Duration.ofSeconds(properties.baseDelaySeconds()),
                Duration.ofSeconds(properties.maxDelaySeconds()));
    }
}
