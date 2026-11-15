package com.lifecontrol.api.fiscal.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.config.fiscal.FiscalProviderConfig;
import com.lifecontrol.api.config.fiscal.SolucionFactibleProperties;
import com.lifecontrol.api.fiscal.FiscalStampProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * What the switch {@code app.fiscal.provider.enabled} puts in — and keeps out of — the context
 * ({@code T7a}, {@code L18}(f) of {@code odd/tasks/cfdi-fiscal-document.md}).
 *
 * <p><b>Why the condition is pinned at the context and not at the bean.</b> The property {@code T6} already
 * relies on is that an installation which does not invoice starts <b>exactly</b> as it did before this unit: no
 * provider bean, and no credentials validated. That is a statement about the bean definition set, so the
 * evidence has to be the context, not a call. And the other half is symmetric — turning the switch on with a
 * missing value has to fail the <b>boot</b>, naming the property, instead of failing the first stamp of the day
 * after a draft exists.</p>
 *
 * <p><b>Why it sits in this package.</b> The class under test is {@code config.fiscal.FiscalProviderConfig}; its
 * suite lives beside the adapter it registers, which is the package the fiscal slice owns.</p>
 */
@DisplayName("The provider switch (T7a)")
class SolucionFactibleFiscalProviderConfigTest {

    private static final String ENDPOINT = "https://testing.solucionfactible.com/ws/services/Timbrado";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(FiscalProviderConfig.class);

    @Test
    @DisplayName("with the switch absent, registers no provider and binds no credentials, so the API starts as today")
    void absentSwitchRegistersNothing() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(FiscalStampProvider.class);
            assertThat(context)
                    .as("the properties are not even bound, so no credential is validated and none is required")
                    .doesNotHaveBean(SolucionFactibleProperties.class);
        });
    }

    @Test
    @DisplayName("with the switch explicitly false, the same absence: a disabled vendor costs nothing at boot")
    void falseSwitchRegistersNothing() {
        runner.withPropertyValues("app.fiscal.provider.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(FiscalStampProvider.class));
    }

    @Test
    @DisplayName("with the switch on and every value present, registers exactly one provider behind the port")
    void enabledSwitchRegistersOneProvider() {
        enabled().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(FiscalStampProvider.class);
            assertThat(context)
                    .getBean(FiscalStampProvider.class)
                    .isInstanceOf(SolucionFactibleFiscalStampProvider.class);
        });
    }

    @Test
    @DisplayName("with the switch on and a blank usuario, the boot fails naming the property and not the first stamp")
    void enabledSwitchWithoutAUserFailsTheBoot() {
        enabled()
                .withPropertyValues("app.fiscal.provider.solucion-factible.usuario=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("app.fiscal.provider.solucion-factible.usuario")
                            .hasStackTraceContaining("required");
                });
    }

    @Test
    @DisplayName("with the switch on and a blank password, the boot fails naming the property too")
    void enabledSwitchWithoutAPasswordFailsTheBoot() {
        enabled()
                .withPropertyValues("app.fiscal.provider.solucion-factible.password=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("app.fiscal.provider.solucion-factible.password");
                });
    }

    @Test
    @DisplayName("with the switch on and an endpoint that is not an absolute http(s) URI, the boot names the property")
    void enabledSwitchWithAnUnusableEndpointFailsTheBoot() {
        enabled()
                .withPropertyValues("app.fiscal.provider.solucion-factible.endpoint=testing.solucionfactible.com")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("app.fiscal.provider.solucion-factible.endpoint");
                });
    }

    @Test
    @DisplayName("with the switch on and no timeout, the boot fails naming the timeout instead of hanging a stamp")
    void enabledSwitchWithoutATimeoutFailsTheBoot() {
        enabled()
                .withPropertyValues("app.fiscal.provider.solucion-factible.timeout-seconds=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("app.fiscal.provider.solucion-factible.timeout-seconds");
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
