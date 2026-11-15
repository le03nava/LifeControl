package com.lifecontrol.api.config.fiscal;

import com.lifecontrol.api.fiscal.seal.CfdiSealer;
import com.lifecontrol.api.fiscal.seal.CsdCfdiSealer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the issuer's CSD behind the sealing interface — under the <b>same</b> switch that registers the
 * provider ({@code T7b}, decisions {@code L18}(d) and {@code L18}(f) of {@code odd/tasks/cfdi-fiscal-document.md)).
 *
 * <p><b>Why the seal hangs off the provider's switch and not one of its own.</b> A stamp is one capability with two
 * inputs: the document is sealed and then sent, and neither half is a stamp alone. Two switches would allow the two
 * states that cannot work — a sealer with no authority to send to, and an authority that receives a document
 * nothing signed — and each would fail at the first stamp of the day instead of at the boot. With the switch absent
 * or false, {@link ConditionalOnProperty} skips this class and, because {@link EnableConfigurationProperties} never
 * runs on a class that was skipped, no {@link CsdProperties} is bound and nothing is required: an installation that
 * does not invoice starts exactly as it did before this unit, and a stamp answers the named refusal
 * {@code T6}'s {@code ObjectProvider} already produced.</p>
 *
 * <p><b>On, and the boot is where a mistake shows.</b> With the switch on, a blank certificate or key fails binding,
 * and a value that is not the base 64 DER and PKCS#8 this tree reads fails the sealer's constructor — which runs
 * here, while the context starts, and not inside a stamp's transaction. That is the difference between a container
 * that will not start, where an operator is looking, and a first stamp of the day that fails after the draft
 * exists.</p>
 *
 * <p><b>Why the bean is the CSD sealer and not a delegating wrapper.</b> Nothing else implements {@link CfdiSealer}
 * in this tree, and a wrapper would exist to hide the one difference a future implementation might have — which is
 * the kind of seam that is added when there are two things to choose between, not before.</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "app.fiscal.provider", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(CsdProperties.class)
public class FiscalSealConfig {

    /**
     * The issuer's sealing pipeline, behind the interface the attempt asks for.
     *
     * @param properties the bound {@code app.fiscal.csd.*} values; read on construction, so a certificate, a key or
     *     a password that cannot be opened stops the boot here rather than the first stamp
     * @return the one implementation of {@link CfdiSealer} this context registers
     */
    @Bean
    public CfdiSealer csdCfdiSealer(CsdProperties properties) {
        return new CsdCfdiSealer(properties);
    }
}
