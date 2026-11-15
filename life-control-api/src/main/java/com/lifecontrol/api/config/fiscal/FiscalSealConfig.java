package com.lifecontrol.api.config.fiscal;

import com.lifecontrol.api.fiscal.cancellation.CfdiCancellationSealer;
import com.lifecontrol.api.fiscal.cancellation.CsdCfdiCancellationSealer;
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

    /**
     * The same CSD, behind the interface a cancellation asks for — a <b>second</b> capability from one certificate,
     * and the reason it is a second bean rather than a second property is that the two sign different things.
     *
     * <p><b>Why this bean exists at all, stated as the defect it closes.</b> {@code T8b} wrote
     * {@link CsdCfdiCancellationSealer} and {@code T8d} wrote the endpoint that asks for it, and until this method
     * existed nothing registered it: with the provider switched on, a stamp worked and every cancellation answered
     * the named {@code 503} the service's {@code ObjectProvider} produces. The refusal was correct and the
     * deployment was still broken, which is the failure mode a {"cannot be missing"} bean is supposed to prevent —
     * so the bean is added here, beside its sibling, and not behind a switch of its own (L22).</p>
     *
     * <p><b>Why one CSD serves both.</b> The seal over a CFDI and the XMLDSig over a cancellation request are both
     * the issuer's signature; using two certificates for them would make the authority's record of the issuer
     * inconsistent, and the SAT's own revocation code {@code 303} (“the CSD that signed the cancellation is not
     * the issuer of the CFDI”) is what that would look like.</p>
     *
     * @param properties the bound {@code app.fiscal.csd.*} values, read on construction so a key that cannot be
     *     opened stops the boot here rather than the first cancellation of the day
     * @return the one implementation of {@link CfdiCancellationSealer} this context registers
     */
    @Bean
    public CfdiCancellationSealer csdCfdiCancellationSealer(CsdProperties properties) {
        return new CsdCfdiCancellationSealer(properties);
    }
}
