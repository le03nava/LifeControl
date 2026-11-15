package com.lifecontrol.api.fiscal.seal;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.config.fiscal.CsdProperties;
import com.lifecontrol.api.config.fiscal.FiscalSealConfig;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * What the switch {@code app.fiscal.provider.enabled} puts in — and keeps out of — the context for the
 * <b>sealing</b> half, and what turning it on requires ({@code T7b}, decision {@code L18}(f) of
 * {@code odd/tasks/cfdi-fiscal-document.md)).
 *
 * <p><b>Why the seal hangs off the same switch as the provider.</b> A stamp is one capability with two inputs:
 * the document has to be sealed before it is sent, and it has to be sent to be stamped. A deployment with the
 * switch off validates nothing and starts exactly as it did before this unit — no sealer bean, no CSD bound, no
 * CSD required — which is what {@code T6}'s {@code ObjectProvider} refusal was waiting for. A deployment with
 * the switch on cannot stamp without a certificate, so the absence of one belongs at the <b>boot</b>, where an
 * operator sees it, and not at the first stamp of the day, after the draft exists and the transaction is open.</p>
 *
 * <p><b>Why it sits in this package.</b> The class under test is {@code config.fiscal.FiscalSealConfig}; its
 * suite lives beside the sealer it registers, the way the provider's suite lives beside the adapter
 * ({@code SolucionFactibleFiscalProviderConfigTest}).</p>
 */
@DisplayName("The seal switch (T7b)")
class FiscalSealConfigTest {

    private static String certificateBase64;

    private static String clearKeyBase64;

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(FiscalSealConfig.class);

    @BeforeAll
    static void stateTheCertificateAndAKeyThisDeploymentCouldOwn()
            throws GeneralSecurityException, java.io.IOException {
        try (InputStream stream = FiscalSealConfigTest.class
                .getClassLoader()
                .getResourceAsStream("fiscal/csd/cadena-original-fixture.crt.der")) {
            assertThat(stream)
                    .as("the certificate fixture is on the test classpath")
                    .isNotNull();
            certificateBase64 = Base64.getEncoder().encodeToString(stream.readAllBytes());
        }
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        clearKeyBase64 = Base64.getEncoder()
                .encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
    }

    @Test
    @DisplayName("with the switch absent, registers no sealer and binds no CSD, so the API starts as today")
    void absentSwitchRegistersNothing() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(CfdiSealer.class);
            assertThat(context)
                    .as("the properties are not even bound, so no certificate and no key are required")
                    .doesNotHaveBean(CsdProperties.class);
        });
    }

    @Test
    @DisplayName("with the switch explicitly false, the same absence: a seal nobody needs costs nothing at boot")
    void falseSwitchRegistersNothing() {
        runner.withPropertyValues("app.fiscal.provider.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(CfdiSealer.class));
    }

    @Test
    @DisplayName("with the switch on and the CSD present, registers exactly one sealer behind the interface")
    void enabledSwitchRegistersOneSealer() {
        enabled().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(CfdiSealer.class);
            assertThat(context).getBean(CfdiSealer.class).isInstanceOf(CsdCfdiSealer.class);
        });
    }

    @Test
    @DisplayName(
            "with the switch on and no certificate, the boot fails naming the property and not the first " + "stamp")
    void enabledSwitchWithoutACertificateFailsTheBoot() {
        enabled().withPropertyValues("app.fiscal.csd.certificado=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("app.fiscal.csd.certificado")
                    .hasStackTraceContaining("required");
        });
    }

    @Test
    @DisplayName("with the switch on and no key, the boot fails naming the property too")
    void enabledSwitchWithoutAKeyFailsTheBoot() {
        enabled().withPropertyValues("app.fiscal.csd.llave=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("app.fiscal.csd.llave");
        });
    }

    @Test
    @DisplayName("with the switch on and a certificate that is not DER, the boot names the property instead of "
            + "failing the first seal")
    void enabledSwitchWithAnUnreadableCertificateFailsTheBoot() {
        enabled()
                .withPropertyValues("app.fiscal.csd.certificado=bm8gZXMgdW4gY2VydGlmaWNhZG8=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("app.fiscal.csd.certificado");
                });
    }

    private ApplicationContextRunner enabled() {
        return runner.withPropertyValues(
                "app.fiscal.provider.enabled=true",
                "app.fiscal.csd.certificado=" + certificateBase64,
                "app.fiscal.csd.llave=" + clearKeyBase64);
    }
}
