package com.lifecontrol.api.fiscal.seal;

import com.lifecontrol.api.config.fiscal.CsdProperties;
import com.lifecontrol.api.fiscal.exception.FiscalCsdUnreadableException;
import com.lifecontrol.api.fiscal.model.IssuerCertificate;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.util.StringUtils;

/**
 * The CSD sealer: the certificate facts a document states and the {@code SHA256withRSA} signature over the
 * SAT's {@code cadena original} ({@code T7b}, decisions {@code L18}(d) and {@code L18}(h) of
 * {@code odd/tasks/cfdi-fiscal-document.md)).
 *
 * <p><b>The three steps, and why none of them can be skipped.</b> The document is rendered <b>without</b> a
 * {@code Sello}; the authority's committed stylesheet turns those bytes into the {@code cadena original}
 * ({@link CadenaOriginal}); and the signature over that string's <b>UTF-8</b> bytes is the seal. Every step is a
 * different authority's rule — the schema's, the SAT's transform, and the key's — so folding any two of them
 * together would leave one of them unstated.</p>
 *
 * <p><b>What the configuration states, and what it does not.</b> A certificate as base 64 DER and a private key as
 * base 64 PKCS#8, with an optional password. It does <b>not</b> read a SAT {@code .key} container: that needs a
 * cryptographic provider this tree does not add, and no CSD exists in this repository to prove a reader against, so
 * {@code L18}(h) makes the {@code openssl pkcs8} conversion the operator's documented step and this class reads
 * the result. The limit is stated here rather than discovered by the first operator who pastes a {@code .key}.</p>
 *
 * <p><b>Why the material is read once, in the constructor.</b> A certificate, a key and a compiled stylesheet are
 * the same for every stamp of a deployment, and reading them per stamp would parse a certificate and compile an
 * XSLT inside a transaction that is already open. It also moves every configuration mistake to the <b>boot</b> —
 * where {@code FiscalSealConfig} makes the properties required — instead of leaving it to the first stamp of the
 * day, after the draft exists. A wrong password is refused by name at that same moment.</p>
 *
 * <p><b>Why the private key never leaves this class.</b> It is a field and is used in {@link #seal} only: nothing
 * returns it, nothing logs it, and the record that carries the configuration renders the password withheld. The
 * interface exists so that no caller ever holds either.</p>
 */
public final class CsdCfdiSealer implements CfdiSealer {

    /** The property the certificate comes from, named in a refusal so an operator knows what to fix. */
    private static final String CERTIFICATE_PROPERTY = "app.fiscal.csd.certificado";

    /** The property the private key comes from, named the same way. */
    private static final String KEY_PROPERTY = "app.fiscal.csd.llave";

    /** The authority's signature algorithm: an RSA key and a SHA-256 digest, as CFDI 4.0 states it. */
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

    private final IssuerCertificate certificate;
    private final PrivateKey privateKey;
    private final CadenaOriginal cadenaOriginal;

    /**
     * Reads the CSD material and compiles the transform.
     *
     * @param properties the bound {@code app.fiscal.csd.*} values
     * @throws FiscalCsdUnreadableException when the certificate or the key is not what this class reads, or the
     *     key's password does not open it
     */
    public CsdCfdiSealer(CsdProperties properties) {
        var x509 = certificateOf(properties.certificado());
        this.certificate = new IssuerCertificate(
                x509.getSerialNumber().toString(), Base64.getEncoder().encodeToString(encodedOf(x509)));
        this.privateKey = privateKeyOf(properties.llave(), properties.password());
        this.cadenaOriginal = new CadenaOriginal();
    }

    @Override
    public IssuerCertificate certificate() {
        return certificate;
    }

    @Override
    public String seal(String unsignedXml) {
        Objects.requireNonNull(unsignedXml, "a seal signs a document, and there is none to sign here");
        var cadenaOriginalOfDocument = cadenaOriginal.of(unsignedXml);
        try {
            var signature = Signature.getInstance(SIGNATURE_ALGORITHM);
            signature.initSign(privateKey);
            signature.update(cadenaOriginalOfDocument.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException cannotSign) {
            throw new IllegalStateException(
                    "The cadena original could not be signed with " + SIGNATURE_ALGORITHM + ". The configured key "
                            + "is read successfully, so this is a defect of the runtime rather than of the "
                            + "configuration.",
                    cannotSign);
        }
    }

    // ──────────────────────────────────────
    // the CSD material
    // ──────────────────────────────────────

    private static X509Certificate certificateOf(String encodedCertificate) {
        var der = decode(encodedCertificate, CERTIFICATE_PROPERTY);
        try {
            return (X509Certificate)
                    CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
        } catch (CertificateException notACertificate) {
            throw FiscalCsdUnreadableException.ofCertificate(CERTIFICATE_PROPERTY, notACertificate);
        }
    }

    /**
     * The private key, clear or password-protected, with the JDK alone.
     *
     * <p>A clear key is a PKCS#8 structure the JDK reads directly. A protected one is an
     * {@link EncryptedPrivateKeyInfo} envelope whose algorithm name tells which {@link SecretKeyFactory} derives
     * the key that unwraps it — the algorithm is read from the envelope and never assumed, so the key can be
     * protected with any PBE scheme the platform supports. No provider is added on this side or the operator's.</p>
     */
    private static PrivateKey privateKeyOf(String encodedKey, String password) {
        var der = decode(encodedKey, KEY_PROPERTY);
        try {
            if (!StringUtils.hasText(password)) {
                return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
            }
            var envelope = new EncryptedPrivateKeyInfo(der);
            var secret = SecretKeyFactory.getInstance(envelope.getAlgName())
                    .generateSecret(new PBEKeySpec(password.toCharArray()));
            var unwrapped = envelope.getKeySpec(secret);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(unwrapped.getEncoded()));
        } catch (GeneralSecurityException | java.io.IOException cannotOpen) {
            throw FiscalCsdUnreadableException.ofPrivateKey(KEY_PROPERTY, StringUtils.hasText(password), cannotOpen);
        }
    }

    private static byte[] decode(String encoded, String property) {
        try {
            return Base64.getMimeDecoder().decode(encoded);
        } catch (IllegalArgumentException notBase64) {
            throw FiscalCsdUnreadableException.ofNotBase64(property, notBase64);
        }
    }

    private static byte[] encodedOf(X509Certificate x509) {
        try {
            return x509.getEncoded();
        } catch (CertificateException cannotEncode) {
            throw FiscalCsdUnreadableException.ofCertificate(CERTIFICATE_PROPERTY, cannotEncode);
        }
    }
}
