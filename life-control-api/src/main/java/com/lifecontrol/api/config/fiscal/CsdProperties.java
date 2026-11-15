package com.lifecontrol.api.config.fiscal;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The issuer's CSD: the certificate that is stated on every document and the private key that seals it
 * ({@code T7b}, decision {@code L18}(h) of {@code odd/tasks/cfdi-fiscal-document.md)).
 *
 * <p>Prefix: {@code app.fiscal.csd}. Supplied by {@code application.properties} as
 * {@code ${FISCAL_CSD_CERTIFICADO:}}, {@code ${FISCAL_CSD_LLAVE:}} and {@code ${FISCAL_CSD_PASSWORD:}}, which means
 * every value is empty unless an operator sets it — and {@link Validated} turns the two required ones into a boot
 * failure instead of a stamp that cannot be sealed. {@link FiscalSealConfig} only exists with
 * {@code app.fiscal.provider.enabled=true}, so an installation that does not invoice binds none of this and
 * validates nothing.</p>
 *
 * <p><b>The two required values are base 64 of a binary file, and the record says which binary.</b> The certificate
 * is the CSD's certificate as <b>DER</b>; the private key is <b>PKCS#8</b>, optionally protected by
 * {@link #password()}. A SAT {@code .key} container is neither of those, it cannot be opened without a
 * cryptographic provider this tree does not add, and no CSD exists in this repository to prove a reader against —
 * so the conversion is the operator's documented {@code openssl pkcs8} step and the record states that limit
 * instead of accepting the container and failing later ({@code L18}(h)).</p>
 *
 * <p><b>Why a missing value is never defaulted.</b> The alternative would be a sealer that starts and signs with
 * nothing — or, worse, a fallback certificate in the repository whose seal the PAC refuses with an error about the
 * issuer while looking like a configuration success. The same direction the provider's own properties take: absent
 * is a boot failure, never a guess.</p>
 *
 * <p><b>Why {@link #password()} is optional and unvalidated.</b> A CSD key may be clear, and requiring a password
 * would force every deployment to protect a key it deliberately does not. It is not blank-checked either: an empty
 * value and an absent one both mean "this key has no password", which is the same fact and not a mistake to
 * refuse.</p>
 *
 * @param certificado the CSD's certificate, DER in base 64
 * @param llave the CSD's private key, PKCS#8 in base 64
 * @param password the password protecting {@code llave}, or blank when it is not protected
 */
@Validated
@ConfigurationProperties(prefix = "app.fiscal.csd")
public record CsdProperties(
        @NotBlank(message = "app.fiscal.csd.certificado is required to seal (FISCAL_CSD_CERTIFICADO)")
        String certificado,

        @NotBlank(message = "app.fiscal.csd.llave is required to seal (FISCAL_CSD_LLAVE)")
        String llave,

        String password) {

    /**
     * Renders the properties with the key's password withheld.
     *
     * <p>The certificate and the key are public and private material respectively, and only one of the three
     * values is a secret — but a password rendered by a record's synthesized {@code toString} would land in the
     * {@code TRACE} line Spring Boot binds properties with, and in the message of any startup failure. It is
     * replaced and never partially shown: a masked prefix is still a leak. The two base 64 values are not printed
     * either, for a simpler reason: nothing needs them in a log, and a truncated key is a fragment of a
     * credential.</p>
     *
     * @return the record's shape with every value withheld
     */
    @Override
    public String toString() {
        return "CsdProperties[certificado=***, llave=***, password=***]";
    }
}
