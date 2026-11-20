package com.lifecontrol.api.fiscal.cancellation;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.config.fiscal.FiscalProviderConfig;
import com.lifecontrol.api.config.fiscal.SolucionFactibleProperties;
import com.lifecontrol.api.fiscal.FiscalStampProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * What the switch {@code app.fiscal.provider.enabled} registers for the <b>reconciling</b> half
 * ({@code T8} of {@code odd/tasks/fiscal-cancellation-reconciliation.md}, decision {@code L7}(b) of the same
 * record).
 *
 * <p><b>Why the condition is pinned at the context and not at the bean.</b> {@code L7}(b) states the defect this
 * suite exists to catch, and it is not "the adapter throws": it is that a bean which exists <b>only</b> behind an
 * {@code ObjectProvider} is found missing by nobody, because the missing case has a good answer — a 503. With the
 * switch on, this deployment <b>has</b> the capability, so the reconciling bean has to be in the definition set;
 * with the switch off it has to be as absent as the other two, so an installation that does not invoice still
 * binds no credential. Both halves are statements about the bean definitions, so the evidence is the context.</p>
 *
 * <p><b>Why it sits in this package.</b> The class under test is {@code config.fiscal.FiscalProviderConfig}; its
 * suite lives beside the adapter it registers, which is the package this slice owns — the shape
 * {@code SolucionFactibleFiscalProviderConfigTest} and {@code SolucionFactibleFiscalCancellationProviderConfigTest}
 * already state.</p>
 */
@DisplayName("The provider switch, for the reconciling half (T8, L7b)")
class SolucionFactibleFiscalCancellationReconcilerConfigTest {

    private static final String ENDPOINT = "https://testing.solucionfactible.com/ws/services/Timbrado";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(FiscalProviderConfig.class);

    @Test
    @DisplayName("L7(b): with the switch on, the reconciling port has exactly one bean and it is the vendor's "
            + "adapter — not a bean a 503 is expected to stand in for")
    void enabledSwitchRegistersTheReconciler() {
        enabled().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(FiscalCancellationReconciliationPort.class);
            assertThat(context)
                    .getBean(FiscalCancellationReconciliationPort.class)
                    .isInstanceOf(SolucionFactibleFiscalCancellationReconciler.class);
        });
    }

    @Test
    @DisplayName("L7(b): with the switch on, the one switch registers all three capabilities of the account — "
            + "stamping, cancelling and reconciling — not two of them")
    void enabledSwitchRegistersTheThreeCapabilitiesTogether() {
        enabled().run(context -> {
            assertThat(context).hasSingleBean(FiscalStampProvider.class);
            assertThat(context).hasSingleBean(FiscalCancellationProvider.class);
            assertThat(context).hasSingleBean(FiscalCancellationReconciliationPort.class);
        });
    }

    @Test
    @DisplayName("with the switch absent, no reconciling bean and no credential: an installation that does not "
            + "invoice does not start holding one")
    void absentSwitchRegistersNothing() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(FiscalCancellationReconciliationPort.class);
            assertThat(context).doesNotHaveBean(SolucionFactibleProperties.class);
        });
    }

    @Test
    @DisplayName("with the switch explicitly false, the same absence")
    void falseSwitchRegistersNothing() {
        runner.withPropertyValues("app.fiscal.provider.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(FiscalCancellationReconciliationPort.class));
    }

    private ApplicationContextRunner enabled() {
        return runner.withPropertyValues(
                "app.fiscal.provider.enabled=true",
                "app.fiscal.provider.solucion-factible.endpoint=" + ENDPOINT,
                "app.fiscal.provider.solucion-factible.usuario=usuario.de.pruebas@example.test",
                "app.fiscal.provider.solucion-factible.password=s3cr3t0",
                "app.fiscal.provider.solucion-factible.timeout-seconds=30");
    }
}
