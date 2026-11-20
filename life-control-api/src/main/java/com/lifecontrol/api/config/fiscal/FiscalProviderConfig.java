package com.lifecontrol.api.config.fiscal;

import com.lifecontrol.api.fiscal.FiscalStampProvider;
import com.lifecontrol.api.fiscal.cancellation.FiscalCancellationProvider;
import com.lifecontrol.api.fiscal.cancellation.FiscalCancellationReconciliationPort;
import com.lifecontrol.api.fiscal.cancellation.SolucionFactibleFiscalCancellationProvider;
import com.lifecontrol.api.fiscal.cancellation.SolucionFactibleFiscalCancellationReconciler;
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
 *
 * <p><b>Why cancelling is a second bean behind the same switch and not a second configuration.</b> The vendor that
 * stamps a document is the one that cancels it, through the same service URL and the same account, so the
 * cancelling half binds {@link SolucionFactibleProperties} rather than a copy of it — two records over one vendor
 * would be two secrets and two endpoints free to disagree about which environment is being called ({@code T8c}).
 * The switch stays single for the same reason: an installation that does not invoice neither stamps nor cancels,
 * and an installation that does gets both, which is what keeps {@code FiscalDocumentStamper}'s and the cancelling
 * service's refusals describing one configuration.</p>
 *
 * <p><b>Why reconciling is a third bean and not a call the cancelling adapter grows.</b> The two surfaces are two
 * capabilities of one account ({@code L3} of {@code odd/tasks/fiscal-cancellation-reconciliation.md}): the
 * synchronous cancel answers a verdict now, and the asynchronous pair registers a request and is asked about it
 * later, behind {@link FiscalCancellationReconciliationPort}. Registering the third adapter — from these same
 * properties, under this same switch — is not bookkeeping ({@code L7}(b) of that record): a bean reachable only
 * through an {@code ObjectProvider} is <b>found missing by nobody</b>, because the missing case has a good answer
 * in {@code FiscalCancellationReconciliationService}, and an installation with the switch on would answer a 503 for
 * a capability it believes it has. The suite that pins it is
 * {@code SolucionFactibleFiscalCancellationReconcilerConfigTest}, one context per half of the switch.</p>
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

    /**
     * The vendor's cancelling operation, behind its own port and the same credentials.
     *
     * <p>Registered under the same condition as the stamping bean, because the two are two capabilities of one
     * account: {@code enviarSolicitudCancelacion} takes {@code usuario} and {@code password} exactly as
     * {@code timbrarBase64} does, and the document it cancels is the one stamped through here.</p>
     *
     * @param properties the same bound {@code app.fiscal.provider.solucion-factible} values, validated again by
     *     the adapter's constructor
     * @return the one implementation of {@link FiscalCancellationProvider} this context registers
     */
    @Bean
    public FiscalCancellationProvider solucionFactibleFiscalCancellationProvider(
            SolucionFactibleProperties properties) {
        return new SolucionFactibleFiscalCancellationProvider(properties);
    }

    /**
     * The vendor's asynchronous cancellation operations, behind the reconciling port and the same credentials.
     *
     * <p>Registered beside the other two and under the same condition, because this is the third capability of one
     * account: the two operations it speaks ({@code enviarSolicitudCancelacionAsincrono} and
     * {@code getStatusCancelacionAsincrona}) take the same {@code usuario} and {@code password} as
     * {@code timbrarBase64}, and the transaction they resolve is the cancellation the bean above submitted
     * ({@code L3}).</p>
     *
     * <p><b>Why this bean is the point and not a detail.</b> The reconciling service asks for the port through an
     * {@code ObjectProvider}, which is right — an installation that does not invoice must still start — but
     * an {@code ObjectProvider} cannot tell "this deployment has no reconciler" from "nobody wired the one it has"
     * ({@code L7}(b)). A bean definition is the only place the difference is observable, and it is what the switch
     * promises: with {@code app.fiscal.provider.enabled=true} the context holds one reconciler.</p>
     *
     * @param properties the same bound {@code app.fiscal.provider.solucion-factible} values, validated again by the
     *     adapter's constructor so a mistake is a boot failure instead of a failed reconciliation
     * @return the one implementation of {@link FiscalCancellationReconciliationPort} this context registers
     */
    @Bean
    public FiscalCancellationReconciliationPort solucionFactibleFiscalCancellationReconciler(
            SolucionFactibleProperties properties) {
        return new SolucionFactibleFiscalCancellationReconciler(properties);
    }
}
