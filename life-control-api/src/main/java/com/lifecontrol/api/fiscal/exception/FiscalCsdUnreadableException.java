package com.lifecontrol.api.fiscal.exception;

/**
 * The configured CSD material could not be read, so no document can be sealed
 * (decision {@code L18}(h) of {@code odd/tasks/cfdi-fiscal-document.md)).
 *
 * <p><b>Why the message names the property and never the material.</b> The two things a deployment gets wrong are
 * a value that is not what this tree reads — a certificate that is PEM instead of DER, a key that is still a SAT
 * {@code .key} container — and a password that opens nothing. Both are configuration mistakes an operator fixes by
 * re-running the documented conversion, so the message names the property, restates the format that property
 * holds, and stops there: a truncated base 64 string is a fragment of a credential, and a message that showed the
 * password or the key to make debugging easier would put it in every log the failure reaches.</p>
 *
 * <p><b>Why a SAT {@code .key} is named as a limit rather than supported.</b> Opening that container needs a
 * cryptographic provider this tree does not add, and no CSD exists in this repository to prove a reader against.
 * So the configuration takes PKCS#8 and the record says the {@code openssl pkcs8} conversion is the operator's
 * step — which is why this exception names it too, at the moment an operator is looking at exactly that
 * problem.</p>
 */
public class FiscalCsdUnreadableException extends IllegalStateException {

    private FiscalCsdUnreadableException(String message, Throwable cause) {
        super(message, cause);
    }

    /** The configured certificate is not the base 64 DER this tree reads. */
    public static FiscalCsdUnreadableException ofCertificate(String property, Throwable cause) {
        return new FiscalCsdUnreadableException(
                property + " does not hold a base 64 DER X.509 certificate, so no document can state its serial or "
                        + "its bytes. The value is the CSD's certificate encoded as DER and then base 64 — "
                        + "`openssl x509 -in certificado.cer -outform DER | openssl base64 -A` — and a PEM file or a "
                        + "certificate in any other encoding fails here, at boot, before a stamp is attempted.",
                cause);
    }

    /** The configured value is not base 64 at all, which is a different mistake from a wrong certificate. */
    public static FiscalCsdUnreadableException ofNotBase64(String property, Throwable cause) {
        return new FiscalCsdUnreadableException(
                property + " is not base 64, so it cannot hold the CSD material this tree reads. Both properties "
                        + "carry base 64 of a binary file — DER for the certificate, PKCS#8 for the key — and a PEM "
                        + "block is not that value either: its BEGIN and END lines are letters of the base 64 "
                        + "alphabet, so they decode as data and the file stops parsing as what it is.",
                cause);
    }

    /** The configured private key is not the base 64 PKCS#8 this tree reads, or its password does not open it. */
    public static FiscalCsdUnreadableException ofPrivateKey(
            String property, boolean passwordProtected, Throwable cause) {
        var password = passwordProtected
                ? ", or app.fiscal.csd.password is not the password that protects it"
                : " (or it is protected and app.fiscal.csd.password is empty)";
        return new FiscalCsdUnreadableException(
                property + " does not hold a base 64 PKCS#8 RSA private key" + password + ". A SAT .key container is "
                        + "not PKCS#8: converting it is the operator's step — "
                        + "`openssl pkcs8 -topk8 -inform DER -in llave.key -outform DER -out llave.pk8` — and this "
                        + "tree reads the result, not the container (T7b, L18(h)).",
                cause);
    }
}
