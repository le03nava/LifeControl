package com.lifecontrol.api.config.fiscal;

import java.time.ZoneId;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Enables binding of {@link FiscalProperties} and turns its zone into the {@link ZoneId} the stamp renders.
 *
 * <p>Mirrors {@code config.invitation.InvitationConfig} and {@code config.provisioning.ProvisioningWorkerConfig}:
 * a {@code @Configuration} class placed beside its properties record, registering it with
 * {@code @EnableConfigurationProperties}. The repository registers every properties record this way rather than
 * through component scanning.</p>
 *
 * <p><b>Why the {@code ZoneId} is a bean and not a string the stamper parses per call.</b> {@link ZoneId#of}
 * refuses an unknown zone with an {@code IllegalArgumentException}, and doing that here means a misspelled
 * {@code app.fiscal.timezone} fails the <b>boot</b> — where an operator sees it and the container restarts —
 * instead of failing the first stamp of the day, after the draft exists and with a message about a document that
 * has nothing to do with the typo. It is the same fail-fast direction the worker's properties take for a
 * {@code WORKER_*} value.</p>
 */
@Configuration
@EnableConfigurationProperties(FiscalProperties.class)
public class FiscalConfig {

    /**
     * The zone the document's date of issue is rendered in.
     *
     * @param properties the bound {@code app.fiscal.*} values
     * @return the configured zone, or a refusal at startup when the value is not a zone id
     */
    @Bean
    public ZoneId fiscalZoneId(FiscalProperties properties) {
        return ZoneId.of(properties.timezone());
    }
}
