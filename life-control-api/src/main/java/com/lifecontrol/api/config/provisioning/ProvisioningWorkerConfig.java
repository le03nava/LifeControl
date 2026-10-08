package com.lifecontrol.api.config.provisioning;

import com.lifecontrol.api.common.worker.WorkerRetryPolicy;
import com.lifecontrol.api.common.worker.WorkerTick;
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
 * <p><b>Out of scope for this unit:</b> there is still no scheduler here — no
 * {@code @EnableScheduling}, no {@code @Scheduled}, no {@code @ConditionalOnProperty} and no
 * {@code TaskScheduler} — and no Keycloak surface. The two values below finally have consumers;
 * {@code enabled} and {@code intervalSeconds} remain bound but unconsumed until the scheduler lands
 * (record T52).</p>
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

    /**
     * The mechanical loop the rescue pass drives, bounded by {@code batchSize}.
     *
     * <p>This bean is where {@code app.provisioning.worker.batch-size} finally earns its consumer
     * (record T52): {@link WorkerTick} deliberately takes its limit as a constructor parameter and
     * reads no property, so <b>this</b> method is the single translation from the bound value to the
     * tick's own bound. The tick re-applies it on top of the repository's {@code Pageable}, so even a
     * query that returned more ids than configured cannot widen the batch.</p>
     *
     * <p>There is deliberately <b>no Java-side default</b> for the batch size: the default lives in
     * {@code application.properties} ({@code ${WORKER_BATCH_SIZE:20}}) as the single source, and a
     * context that binds nothing — such as an isolated slice — must supply it rather than receive a
     * silently invented value.</p>
     *
     * @param properties the bound {@code app.provisioning.worker.*} values
     * @return a tick bounded by the configured batch size
     */
    @Bean
    public WorkerTick workerTick(ProvisioningWorkerProperties properties) {
        return new WorkerTick(properties.batchSize());
    }
}
