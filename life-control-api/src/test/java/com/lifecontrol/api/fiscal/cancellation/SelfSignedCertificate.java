package com.lifecontrol.api.fiscal.cancellation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.security.auth.x500.X500Principal;

/**
 * A self-signed X.509 certificate generated at run time, so a signature is verified with the public key of the
 * very certificate its {@code KeyInfo} states ({@code T8b}, decision {@code L20}(g) of
 * {@code odd/tasks/cfdi-fiscal-document.md}).
 *
 * <p><b>Why this class exists at all.</b> An enveloped XMLDSig is verified against a <b>public key</b>, and the
 * key a cancellation request must verify against is the one inside the certificate the request itself embeds. The
 * suite cannot get that pair from the committed fixture in {@code fiscal/csd/}: that certificate's private key was
 * deliberately discarded (its README says so, for the reason that a committed private key is a credential), so
 * signing with it is impossible. Generating only a key pair is not enough either — a
 * {@link javax.xml.crypto.dsig.dom.DOMValidateContext} built from the certificate's public key would then be
 * describing a certificate that never signed anything.</p>
 *
 * <p><b>What it does not add.</b> No dependency and no external process: the JDK has no certificate
 * <b>builder</b>, but it has every piece this needs — {@link X500Principal#getEncoded()} for the distinguished
 * names and {@link KeyPair#getPublic()} for the {@code SubjectPublicKeyInfo} — so the four remaining structures are
 * written with sixty lines of DER. The certificate states no extensions, which is legal and irrelevant here: the
 * only facts the suite reads back are its public key, its serial and its bytes.</p>
 *
 * <p><b>What it is not.</b> It is not a CSD, the SAT never saw it, and it can stamp nothing. It is a key pair that
 * can prove a signature round trip.</p>
 */
final class SelfSignedCertificate {

    /** {@code 1.2.840.113549.1.1.11} — {@code sha256WithRSAEncryption}, as an {@code AlgorithmIdentifier}. */
    private static final byte[] SHA_256_WITH_RSA = {
        0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x0b, 0x05, 0x00
    };

    /** The one subject every generated certificate states; nothing reads it, and a constant is a constant. */
    private static final String SUBJECT = "CN=LifeControl Cancellation Fixture";

    private SelfSignedCertificate() {}

    /**
     * Builds a self-signed X.509 v3 certificate for this key pair.
     *
     * @param pair the key pair the certificate belongs to, and whose private half signs it
     * @param serial the serial the certificate states, which is the value a document would carry as
     *     {@code NoCertificado}
     * @return the certificate, parseable by the JDK's own {@link CertificateFactory}
     */
    static X509Certificate of(KeyPair pair, BigInteger serial) {
        try {
            var name = new X500Principal(SUBJECT).getEncoded();
            var toBeSigned = sequence(
                    // [0] EXPLICIT version 2 (v3)
                    tagged(0xa0, integer(BigInteger.valueOf(2))),
                    integer(serial),
                    SHA_256_WITH_RSA,
                    name,
                    // Validity: two UTCTimes, fixed, because a test does not need a moving certificate.
                    sequence(utcTime("250101000000Z"), utcTime("350101000000Z")),
                    name,
                    pair.getPublic().getEncoded());

            var signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(pair.getPrivate());
            signer.update(toBeSigned);
            var signature = signer.sign();

            var certificate =
                    sequence(toBeSigned, SHA_256_WITH_RSA, tagged(0x03, concatenate(new byte[] {0}, signature)));
            return (X509Certificate)
                    CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(certificate));
        } catch (java.security.GeneralSecurityException cannotBuild) {
            throw new IllegalStateException(
                    "The self-signed fixture certificate could not be built, which is a "
                            + "defect of this suite and not of the code it exercises.",
                    cannotBuild);
        }
    }

    // ─── the four DER shapes a bare certificate needs ───────────────────────────────────────────────────

    private static byte[] sequence(byte[]... parts) {
        return tagged(0x30, concatenate(parts));
    }

    private static byte[] integer(BigInteger value) {
        return tagged(0x02, value.toByteArray());
    }

    private static byte[] utcTime(String value) {
        return tagged(0x17, value.getBytes(StandardCharsets.US_ASCII));
    }

    /** One DER tag-length-value, with the long form for any content of 128 bytes or more. */
    private static byte[] tagged(int tag, byte[] content) {
        var out = new ByteArrayOutputStream();
        out.write(tag);
        if (content.length < 0x80) {
            out.write(content.length);
        } else {
            var lengthBytes = (Integer.SIZE - Integer.numberOfLeadingZeros(content.length) + 7) / 8;
            out.write(0x80 | lengthBytes);
            for (var index = lengthBytes - 1; index >= 0; index--) {
                out.write((content.length >>> (8 * index)) & 0xff);
            }
        }
        out.writeBytes(content);
        return out.toByteArray();
    }

    private static byte[] concatenate(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
