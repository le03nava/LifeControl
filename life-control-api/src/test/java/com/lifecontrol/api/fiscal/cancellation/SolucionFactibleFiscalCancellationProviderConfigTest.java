package com.lifecontrol.api.fiscal.cancellation;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.config.fiscal.FiscalProviderConfig;
import com.lifecontrol.api.config.fiscal.SolucionFactibleProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * What the switch {@code app.fiscal.provider.enabled} puts in — and keeps out of — the context for the cancelling
 * half ({@code T8c}, decision {@code L20}(j) of {@code odd/tasks/cfdi-fiscal-document.md}).
 *
 * <p><b>Why the condition is pinned at the context and not at the bean.</b> The property {@code T6} already relies
 * on is that an installation which does not invoice starts <b>exactly</b> as it did before A3: no provider bean and
 * no credentials validated. Cancellation adds a second bean behind the <b>same</b> switch, so the claim to pin is
 * that the second one is as absent as the first — a cancellation adapter that registered unconditionally would
 * demand credentials from an installation that never cancels anything, and would fail its boot.</p>
 *
 * <p><b>Why it sits in this package.</b> The class under test is {@code config.fiscal.FiscalProviderConfig}; its
 * suite lives beside the adapter it registers, which is the package this slice owns.</p>
 */
@DisplayName("The provider switch, for the cancelling half (T8c)")
class SolucionFactibleFiscalCancellationProviderConfigTest {

    private static final String ENDPOINT = "https://testing.solucionfactible.com/ws/services/Timbrado";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(FiscalProviderConfig.class);

    @Test
    @DisplayName("with the switch absent, registers no cancellation provider and binds no cancellation credential")
    void absentSwitchRegistersNothing() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(FiscalCancellationProvider.class);
            assertThat(context).doesNotHaveBean(SolucionFactibleProperties.class);
        });
    }

    @Test
    @DisplayName("with the switch explicitly false, the same absence: a disabled vendor cancels nothing at boot")
    void falseSwitchRegistersNothing() {
        runner.withPropertyValues("app.fiscal.provider.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(FiscalCancellationProvider.class));
    }

    @Test
    @DisplayName("with the switch on, registers the Solución Factible adapter behind the cancellation port")
    void enabledSwitchRegistersTheAdapter() {
        enabled().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(FiscalCancellationProvider.class);
            assertThat(context)
                    .getBean(FiscalCancellationProvider.class)
                    .isInstanceOf(SolucionFactibleFiscalCancellationProvider.class);
        });
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
