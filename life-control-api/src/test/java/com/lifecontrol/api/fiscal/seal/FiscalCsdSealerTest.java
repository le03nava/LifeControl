package com.lifecontrol.api.fiscal.seal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.config.fiscal.CsdProperties;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The sealer: the two certificate facts the builder writes and the signature that is the CFDI's {@code Sello}
 * ({@code T7b}, decisions {@code L18}(d) and {@code L18}(h) of {@code odd/tasks/cfdi-fiscal-document.md}).
 *
 * <p><b>Why the test generates the key pair instead of carrying one.</b> {@code L18}(h) states the split
 * honestly: the <b>certificate</b> is a fixture (its serial and its DER bytes are what a document states), and
 * the <b>private key</b> is generated here, handed to the sealer as PKCS#8 base64 configuration, and never
 * committed. So the signature is verified against the public key of the pair that produced it — a real proof
 * that the pipeline signed these bytes — instead of compared with a fixed base64 string, which would prove only
 * that a constant can be copied.</p>
 *
 * <p><b>What is not proven, and is stated rather than implied.</b> No part of this verifies that the string the
 * transform produces is the one the authority computes; that is a stamp accepted by a PAC, and
 * {@link FiscalCadenaOriginalTest} says so in its own terms. Nor does any of this read a SAT {@code .key} container:
 * {@code L18}(h) makes the {@code openssl pkcs8} conversion the operator's documented step, and the
 * configuration takes the result.</p>
 */
@DisplayName("The CSD sealer (T7b, L18(d), L18(h))")
class FiscalCsdSealerTest {

    /** The password the protected-key cases state, and the one a stamp would be configured with. */
    private static final String PASSWORD = "f1sc4l-de-prueba";

    /** The committed fixture certificate: the serial and the bytes a document states. */
    private static final String FIXTURE = "fiscal/csd/cadena-original-fixture.crt.der";

    /** The algorithm name a PKCS#8 password protection states, and the one the sealer has to open. */
    private static final String PBE_ALGORITHM = "PBEWithHmacSHA256AndAES_256";

    private static KeyPair keyPair;

    private static byte[] fixtureDer;

    private static X509Certificate fixture;

    @BeforeAll
    static void generateAKeyPairAndReadTheCertificateFixture() throws GeneralSecurityException, java.io.IOException {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();

        try (InputStream stream = FiscalCsdSealerTest.class.getClassLoader().getResourceAsStream(FIXTURE)) {
            assertThat(stream)
                    .as("the certificate fixture is on the test classpath")
                    .isNotNull();
            fixtureDer = stream.readAllBytes();
        }
        fixture = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(fixtureDer));
    }

    // ──────────────────────────────────────
    // the two facts the builder writes
    // ──────────────────────────────────────

    @Test
    @DisplayName("the certificate facts are the fixture's serial in DECIMAL and its DER in base 64")
    void theCertificateFactsAreTheFixtureSerialInDecimalAndItsDerInBase64() {
        var certificate = clearKeySealer().certificate();

        assertThat(certificate.noCertificado())
                .as("NoCertificado is the serial in decimal, never the hex openssl prints")
                .isEqualTo(fixture.getSerialNumber().toString())
                .isEqualTo("30001000000400002434");
        assertThat(certificate.certificado())
                .as("Certificado is the certificate's DER in base 64, which is what the authority matches "
                        + "against NoCertificado")
                .isEqualTo(Base64.getEncoder().encodeToString(fixtureDer));
    }

    // ──────────────────────────────────────
    // the signature
    // ──────────────────────────────────────

    @Test
    @DisplayName("the seal is SHA256withRSA over the cadena original's UTF-8 bytes, verified with the configured "
            + "pair's public key")
    void theSealVerifiesWithThePublicKeyOfTheConfiguredPair() throws GeneralSecurityException {
        var sealer = clearKeySealer();

        var sello = sealer.seal(FiscalCadenaOriginalTest.UNSIGNED);

        assertThat(Base64.getDecoder().decode(sello))
                .as("an RSA-2048 signature is 256 bytes of base 64 that decodes back to 256")
                .hasSize(256);
        assertThat(verifies(FiscalCadenaOriginalTest.UNSIGNED, sello, keyPair.getPublic()))
                .as("the signature covers exactly the cadena original the committed stylesheet produces")
                .isTrue();
        assertThat(verifies(FiscalCadenaOriginalTest.UNSIGNED.replace("01000", "01001"), sello, keyPair.getPublic()))
                .as("and nothing else: one field of the document changed is a seal the authority refuses")
                .isFalse();
        assertThat(verifies(FiscalCadenaOriginalTest.UNSIGNED, sello, otherPublicKey()))
                .as("and no other key can claim it, which is what NoCertificado is for")
                .isFalse();
    }

    // ──────────────────────────────────────
    // the key, clear and password-protected
    // ──────────────────────────────────────

    @Test
    @DisplayName("a password-protected PKCS#8 key opens with its password, and the seal still verifies")
    void aPasswordProtectedKeyOpensWithItsPassword() throws GeneralSecurityException, java.io.IOException {
        var properties = new CsdProperties(
                Base64.getEncoder().encodeToString(fixtureDer), encryptedKeyBase64(PASSWORD), PASSWORD);

        var sello = new CsdCfdiSealer(properties).seal(FiscalCadenaOriginalTest.UNSIGNED);

        assertThat(verifies(FiscalCadenaOriginalTest.UNSIGNED, sello, keyPair.getPublic()))
                .as("the protected key is the same key, so it signs the same chain")
                .isTrue();
    }

    @Test
    @DisplayName(
            "a wrong password is refused by name, naming the property, and never the material it could not " + "open")
    void aWrongPasswordIsRefusedByThePropertyName() throws GeneralSecurityException, java.io.IOException {
        var properties = new CsdProperties(
                Base64.getEncoder().encodeToString(fixtureDer), encryptedKeyBase64(PASSWORD), "otra-password");

        assertThatThrownBy(() -> new CsdCfdiSealer(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.fiscal.csd.llave")
                .hasMessageContaining("password")
                .hasMessageNotContaining("otra-password");
    }

    @Test
    @DisplayName("a certificate that is not DER is refused by name instead of reaching the builder as bytes")
    void anUnreadableCertificateIsRefusedByThePropertyName() {
        var properties = new CsdProperties(
                Base64.getEncoder().encodeToString("<no es un certificado>".getBytes(StandardCharsets.UTF_8)),
                Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()),
                null);

        assertThatThrownBy(() -> new CsdCfdiSealer(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.fiscal.csd.certificado");
    }

    // ──────────────────────────────────────
    // the fixtures
    // ──────────────────────────────────────

    private static CsdCfdiSealer clearKeySealer() {
        return new CsdCfdiSealer(new CsdProperties(
                Base64.getEncoder().encodeToString(fixtureDer),
                Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()),
                null));
    }

    /** Whether {@code sello} is a signature over the cadena original of {@code document} by {@code key}. */
    private static boolean verifies(String document, String sello, PublicKey key) throws GeneralSecurityException {
        var signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(key);
        signature.update(new CadenaOriginal().of(document).getBytes(StandardCharsets.UTF_8));
        return signature.verify(Base64.getDecoder().decode(sello));
    }

    /**
     * The generated key as a password-protected PKCS#8, built with the JDK alone.
     *
     * <p>It has to be the envelope an operator's {@code openssl pkcs8 -topk8 -v2 aes-256-cbc} produces, because
     * that is what the configuration will hold: PBES2 over PBKDF2-HMAC-SHA256 and AES-256. The JDK cannot write
     * that structure from an algorithm <b>name</b> ({@code EncryptedPrivateKeyInfo} recognizes only the legacy PBE
     * identifiers when it builds one), so the parameters are taken from the cipher that just encrypted the key and
     * re-read as a {@code PBES2} parameter block — the same bytes the envelope carries. The sealer then opens the
     * result through the identical statements it uses on an operator's key, so what this case pins is the reading
     * path and not a fixture the reader was written around.</p>
     */
    private static String encryptedKeyBase64(String password) throws GeneralSecurityException, java.io.IOException {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        var parameterSpec = new PBEParameterSpec(salt, 60_000);

        var secret = SecretKeyFactory.getInstance(PBE_ALGORITHM).generateSecret(new PBEKeySpec(password.toCharArray()));
        var cipher = Cipher.getInstance(PBE_ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, secret, parameterSpec);
        var encrypted = cipher.doFinal(keyPair.getPrivate().getEncoded());

        var pbes2 = AlgorithmParameters.getInstance("PBES2");
        pbes2.init(cipher.getParameters().getEncoded());
        return Base64.getEncoder().encodeToString(new EncryptedPrivateKeyInfo(pbes2, encrypted).getEncoded());
    }

    /** A key pair nobody configured, so "the seal verifies" is not satisfied by any seal at all. */
    private static PublicKey otherPublicKey() throws GeneralSecurityException {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair().getPublic();
    }
}
