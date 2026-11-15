package com.lifecontrol.api.config.fiscal;

import java.time.Clock;
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
 *
 * <p><b>Why the clock is a bean too, and why it is derived from that same zone ({@code T7b}'s review).</b> A stamp
 * renders the document twice from <b>one</b> instant ({@code L18}(d)), and "one instant" was argued in prose until
 * now: {@code Fecha} has second precision, so a regression that read the clock once per rendering — in the same
 * second — would pass every test and still send a document whose {@code Fecha} is not the one the seal was
 * computed over. Handing the attempt a {@link Clock} makes that detectable instead of argued, because a clock a
 * test can advance separates the two reads. Its default is {@code Clock.system(zoneId)} with the zone above, so
 * the rendered {@code Fecha} and the deployment's behaviour are exactly what they were.</p>
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

    /**
     * The clock the stamp reads the document's instant from, in the same configured zone.
     *
     * <p>Derived from {@link #fiscalZoneId} rather than from {@code Clock.systemDefaultZone()}, so the zone the
     * bean states and the zone {@code Fecha} is rendered in cannot disagree. The deployment's behaviour is
     * unchanged: {@code Clock.system(zone)} answers the same wall-clock time every other read of the system clock
     * does.</p>
     *
     * @param fiscalZoneId the configured zone, which the clock carries so {@code LocalDateTime.now(clock)} renders
     *     the document's date of issue where the company issues
     * @return the system clock in that zone
     */
    @Bean
    public Clock fiscalClock(ZoneId fiscalZoneId) {
        return Clock.system(fiscalZoneId);
    }
}
