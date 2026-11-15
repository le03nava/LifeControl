package com.lifecontrol.api.config.fiscal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The Solución Factible stamping adapter's configuration: where to call, who to call as, and how long to wait
 * ({@code T7a}, decision {@code L18}(f) of {@code odd/tasks/cfdi-fiscal-document.md}).
 *
 * <p>Prefix: {@code app.fiscal.provider.solucion-factible}. Supplied by {@code application.properties} as
 * {@code ${FISCAL_SF_ENDPOINT:}} and its three siblings, which means <b>every value is empty unless an operator
 * sets it</b> — and {@link Validated} is what turns that into a boot failure instead of a call. The registration
 * is gated: {@link FiscalProviderConfig} only exists with {@code app.fiscal.provider.enabled=true}, so an
 * installation that does not invoice binds none of this and validates nothing.</p>
 *
 * <p><b>Why nothing here has a default.</b> The vendor documents a public test account, which is exactly why a
 * silent fallback to it would be reachable by accident: a tenant's document would travel to a sandbox and the
 * stamp would be refused with {@code 633} <i>after</i> the XML left this process. An endpoint default is worse
 * in the other direction — a production installation pointed at the testing host stamps nothing it can invoice.
 * The same decision the tree already made for {@code WORKER_*} values: absent is a boot failure, never a guess
 * ({@code L18}(f)).</p>
 *
 * <p><b>Why the credentials are a plain password field and not a mounted secret reader.</b> The values arrive
 * from the environment like every other credential of this API, and the properties are sealed into the adapter's
 * fields: nothing logs them, nothing renders them, and this record's {@code toString} is the only place they
 * could leak. That is the reason the record deliberately overrides it.</p>
 *
 * @param endpoint the operation's URL — the vendor's {@code /ws/services/Timbrado} service, testing or
 *     production as the operator declares; validated to be an absolute {@code http(s)} URI at boot
 * @param usuario the provider's account user
 * @param password the provider's account password, sent per call: this vendor issues no token to cache
 * @param timeoutSeconds how long a stamp call may take, in seconds — both the connect timeout and the whole
 *     exchange, because a hung PAC would otherwise hold the stamp's transaction open for as long as the socket
 *     lives
 */
@Validated
@ConfigurationProperties(prefix = "app.fiscal.provider.solucion-factible")
public record SolucionFactibleProperties(
        @NotBlank(message = "app.fiscal.provider.solucion-factible.endpoint is required (FISCAL_SF_ENDPOINT)")
        String endpoint,

        @NotBlank(message = "app.fiscal.provider.solucion-factible.usuario is required (FISCAL_SF_USUARIO)")
        String usuario,

        @NotBlank(message = "app.fiscal.provider.solucion-factible.password is required (FISCAL_SF_PASSWORD)")
        String password,

        @NotNull(
                message = "app.fiscal.provider.solucion-factible.timeout-seconds is required "
                        + "(FISCAL_SF_TIMEOUT_SECONDS)")
        @Positive(
                message = "app.fiscal.provider.solucion-factible.timeout-seconds has to be a positive number of "
                        + "seconds")
        Integer timeoutSeconds) {

    /**
     * Renders the properties with the credentials withheld.
     *
     * <p>Spring Boot binds properties records and logs them at {@code TRACE}; a record's synthesized
     * {@code toString} would put the vendor's password in that line, and the property is also what appears in a
     * startup failure. The password is replaced and never partially shown: a masked prefix is still a leak.</p>
     *
     * @return the record's values, with the password withheld
     */
    @Override
    public String toString() {
        return "SolucionFactibleProperties[endpoint=" + endpoint + ", usuario=" + usuario + ", password=***"
                + ", timeoutSeconds=" + timeoutSeconds + "]";
    }
}
