package com.lifecontrol.api.config.fiscal;

import com.lifecontrol.api.fiscal.FiscalStampProvider;
import com.lifecontrol.api.fiscal.provider.SolucionFactibleFiscalStampProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers Solución Factible behind the stamping port — and only while a provider is switched on
 * ({@code T7a}, decision {@code L18}(f) of {@code odd/tasks/cfdi-fiscal-document.md}).
 *
 * <p><b>Off by default is structural, not a check inside a call.</b> The whole class is gated by
 * {@link ConditionalOnProperty}, the shape {@code ProvisioningWorkerSchedulerConfig} already uses, so with
 * {@code app.fiscal.provider.enabled} absent or {@code false} the context holds no provider bean and —
 * because {@link EnableConfigurationProperties} never runs on a class that was skipped — no
 * {@link SolucionFactibleProperties} either. Nothing binds, so nothing is validated and nothing is required:
 * an installation that does not invoice starts exactly as it did before this unit, which is the half
 * {@code FiscalDocumentStamper}'s {@code ObjectProvider} refusal depends on ({@code L14}(e)).</p>
 *
 * <p><b>On, and the boot is where a mistake shows.</b> With the switch on, every value becomes required:
 * a blank {@code usuario} fails {@code @Validated} binding, and an endpoint that is not an absolute
 * {@code http(s)} URI fails the adapter's constructor. Both are <b>startup</b> failures naming the property,
 * which is the difference between a container that will not start — where an operator is looking — and a first
 * stamp of the day that fails after the draft exists and the document has already been built.</p>
 *
 * <p><b>Why the bean is the vendor's adapter and not a delegating wrapper.</b> {@code T7} split the adapter
 * ({@code T7a}) from the sealing pipeline ({@code T7b}) because they are testable separately: this class
 * registers the first, and {@code T7b} is what will make the seal a function of the bytes the builder produced
 * rather than an argument a caller supplies.</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "app.fiscal.provider", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(SolucionFactibleProperties.class)
public class FiscalProviderConfig {

    /**
     * The vendor's SOAP client, behind the port this tree's attempt already asks for.
     *
     * @param properties the bound {@code app.fiscal.provider.solucion-factible} values, which the adapter
     *     validates again on construction so a context that bypassed binding still cannot point it at nothing
     * @return the one implementation of {@link FiscalStampProvider} this context registers
     */
    @Bean
    public FiscalStampProvider solucionFactibleFiscalStampProvider(SolucionFactibleProperties properties) {
        return new SolucionFactibleFiscalStampProvider(properties);
    }
}
