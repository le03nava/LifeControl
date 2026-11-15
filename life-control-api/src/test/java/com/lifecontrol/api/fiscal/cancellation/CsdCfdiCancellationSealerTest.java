package com.lifecontrol.api.fiscal.cancellation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.config.fiscal.CsdProperties;
import com.lifecontrol.api.fiscal.exception.FiscalCsdUnreadableException;
import com.lifecontrol.api.fiscal.model.FiscalCancellationMotive;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.xml.crypto.MarshalException;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureException;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * The enveloped XMLDSig a cancellation request carries, produced with the JDK's {@code javax.xml.crypto.dsig} and
 * the CSD this tree already loads ({@code T8b}, decisions {@code L20}(g) and {@code L20}(a) of
 * {@code odd/tasks/cfdi-fiscal-document.md}).
 *
 * <p><b>Why the round trip is the central case.</b> A signature is only worth its bytes if a verifier reads the
 * same canonical form the signer wrote. So the suite seals a seal-less request through the real builder and the
 * real sealer, and then <b>validates</b> the result with {@link DOMValidateContext} over the certificate's public
 * key: the certificate the {@code KeyInfo} states is the one whose public key verifies, which is exactly the
 * check the authority makes and the one no amount of string comparison can substitute for.</p>
 *
 * <p><b>Why the certificate is generated here and not committed.</b> The fixture in {@code fiscal/csd/} has no
 * private key, deliberately — its README says a committed private key is a credential. So this class generates a
 * key pair <b>and</b> a self-signed certificate for it ({@link SelfSignedCertificate}), hands both to the sealer as
 * the base 64 configuration a deployment would supply, and verifies against that certificate's own public key. The
 * split {@code L18}(h) states for the CFDI sealer is kept: the material is configuration, and the proof is real
 * cryptography over real bytes rather than a copied constant.</p>
 *
 * <p><b>What is not proven, stated rather than implied.</b> No PAC has verified a signature produced here, because
 * no CSD and no cancellation sandbox exist in this repository ({@code L19}(e), {@code L20}(i)). What <b>is</b>
 * proven is that the document the builder renders, the digest the signer computed and the canonicalisation the
 * verifier applies are one and the same — the property {@code L20}(g) says the stamping pipeline's
 * {@code Sello} cannot give a cancellation request.</p>
 */
@DisplayName("The CSD sealer for a cancellation request (T8b, L20(a), L20(g))")
class CsdCfdiCancellationSealerTest {

    /** The one instant both renderings are produced from, and the reason it is a parameter and not a clock. */
    private static final LocalDateTime FECHA = LocalDateTime.of(2026, 11, 15, 10, 15, 30);

    private static final String RFC = "AAA010101AAA";

    /** The digital signature namespace every element this class asserts lives in. */
    private static final String DSIG = "http://www.w3.org/2000/09/xmldsig#";

    private static final UUID CANCELLED = UUID.fromString("86b8719c-2a66-4f52-8b6c-36e69c57cf5a");

    private static final UUID SUBSTITUTE = UUID.fromString("8c3f076a-ce2a-4847-8566-43ab1cba228c");

    /** A serial shaped like a real CSD's: it is wider than a {@code long}, which is why it is a string. */
    private static final String SERIAL = "30001000000400002434";

    private static KeyPair keyPair;

    private static X509Certificate certificate;

    private static String encodedCertificate;

    private static String encodedKey;

    private final CfdiCancellationRequestBuilder builder = new CfdiCancellationRequestBuilder();

    @BeforeAll
    static void generateTheMaterialADeploymentWouldSupply() throws GeneralSecurityException {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        certificate = SelfSignedCertificate.of(keyPair, new BigInteger(SERIAL));
        encodedCertificate = Base64.getEncoder().encodeToString(encoded(certificate));
        encodedKey = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
    }

    // ──────────────────────────────────────
    // the round trip
    // ──────────────────────────────────────

    @Test
    @DisplayName("the signature the sealer answers validates over the bytes the builder rendered")
    void theSignatureValidatesOverTheRenderedDocument() {
        var request = new CancellationRequest(CANCELLED, FiscalCancellationMotive.ERRORS_WITH_RELATION, SUBSTITUTE);
        var sealLess = builder.build(request, RFC, FECHA);

        var sealed = builder.build(request, RFC, FECHA, sealer().seal(sealLess));

        assertThat(validates(sealed))
                .as("the verifier reads the canonicalisation the signer wrote, over the certificate's public key")
                .isTrue();
        assertThat(sealed)
                .as("and the document it covers is the seal-less rendering, byte for byte (L18(d))")
                .startsWith(sealLess.substring(0, sealLess.length() - "</Cancelacion>".length()));
    }

    @Test
    @DisplayName("the Reference covers the whole document through the enveloped transform, and its digest verifies")
    void theReferenceIsTheEnvelopedDocument() {
        var request = new CancellationRequest(CANCELLED, FiscalCancellationMotive.ERRORS_WITHOUT_RELATION, null);
        var sealed = sealed(request);

        var context = newContext(sealed);
        var signature = unmarshal(context);
        List<Reference> references = signature.getSignedInfo().getReferences().stream()
                .map(Reference.class::cast)
                .toList();

        assertThat(references)
                .as("the request is one document, so one Reference")
                .hasSize(1);
        var reference = references.getFirst();
        assertThat(reference.getURI())
                .as("an empty URI is the enveloping document itself")
                .isEmpty();
        assertThat(reference.getTransforms().stream().map(Transform::getAlgorithm))
                .as("exactly the enveloped transform, which removes the Signature before digesting")
                .containsExactly(Transform.ENVELOPED);
        assertThat(referenceValidates(reference, context))
                .as("the digest verifies")
                .isTrue();
    }

    @Test
    @DisplayName("the signature states SHA-256 and RSA-SHA256 with inclusive C14N, never the vendor example's SHA-1")
    void theAlgorithmsAreTheFourthRevisionsAndNotTheExamples() {
        var request = new CancellationRequest(CANCELLED, FiscalCancellationMotive.OPERATION_NOT_CARRIED_OUT, null);
        var sealed = sealed(request);

        var signedInfo = unmarshal(newContext(sealed)).getSignedInfo();

        assertThat(signedInfo.getCanonicalizationMethod().getAlgorithm())
                .as("inclusive C14N, which is the algorithm every published Anexo 20 request states")
                .isEqualTo(CanonicalizationMethod.INCLUSIVE);
        assertThat(signedInfo.getSignatureMethod().getAlgorithm())
                .as("RSA-SHA256: the 2021 example's rsa-sha1 is its age, not the contract (L20(g))")
                .isEqualTo(SignatureMethod.RSA_SHA256);
        assertThat(((Reference) signedInfo.getReferences().getFirst())
                        .getDigestMethod()
                        .getAlgorithm())
                .isEqualTo(DigestMethod.SHA256);
    }

    @Test
    @DisplayName("the KeyInfo states the configured certificate, so the request can be verified without it")
    void theKeyInfoCarriesTheConfiguredCertificate() {
        var request =
                new CancellationRequest(CANCELLED, FiscalCancellationMotive.NOMINAL_OPERATION_IN_GLOBAL_INVOICE, null);
        var sealed = sealed(request);

        var embedded = element(sealed, "X509Certificate").getTextContent().replaceAll("\\s", "");

        assertThat(embedded)
                .as("the certificate travels inside the signature: the authority has no other way to get it, "
                        + "because a cancellation request states no Certificado attribute")
                .isEqualTo(encodedCertificate);
    }

    @Test
    @DisplayName("a document changed after it was sealed no longer validates")
    void aChangedDocumentNoLongerValidates() {
        var request = new CancellationRequest(CANCELLED, FiscalCancellationMotive.ERRORS_WITHOUT_RELATION, null);
        var sealed = sealed(request);
        var tampered = sealed.replace("\"02\"", "\"03\"");

        assertThat(tampered)
                .as("the tampering case actually changed the document")
                .isNotEqualTo(sealed);
        assertThat(validates(tampered))
                .as("the digest covers the document, so one attribute changed is a request the authority refuses")
                .isFalse();
    }

    // ──────────────────────────────────────
    // the configuration this sealer cannot do without
    // ──────────────────────────────────────

    @Test
    @DisplayName("a missing private key is refused, naming app.fiscal.csd.llave")
    void aMissingPrivateKeyIsRefusedNamingTheConfiguration() {
        assertThatThrownBy(() -> new CsdCfdiCancellationSealer(new CsdProperties(encodedCertificate, null, null)))
                .as("the same vocabulary the stamping half uses, so an operator fixes one thing and not two")
                .isInstanceOf(FiscalCsdUnreadableException.class)
                .hasMessageContaining("app.fiscal.csd.llave");
    }

    @Test
    @DisplayName("a blank private key is refused the same way, and not with a decoder's exception")
    void aBlankPrivateKeyIsRefusedNamingTheConfiguration() {
        assertThatThrownBy(() -> new CsdCfdiCancellationSealer(new CsdProperties(encodedCertificate, "  ", null)))
                .isInstanceOf(FiscalCsdUnreadableException.class)
                .hasMessageContaining("app.fiscal.csd.llave");
    }

    @Test
    @DisplayName("a key that is not PKCS#8 is refused, naming the conversion that produces one")
    void aKeyThatIsNotPkcs8IsRefused() {
        var notAKey = Base64.getEncoder().encodeToString("this is not a private key".getBytes());

        assertThatThrownBy(() -> new CsdCfdiCancellationSealer(new CsdProperties(encodedCertificate, notAKey, null)))
                .isInstanceOf(FiscalCsdUnreadableException.class)
                .hasMessageContaining("app.fiscal.csd.llave")
                .hasMessageContaining("openssl pkcs8");
    }

    @Test
    @DisplayName("a certificate that is not DER is refused, naming app.fiscal.csd.certificado")
    void aCertificateThatIsNotDerIsRefused() {
        var notACertificate = Base64.getEncoder().encodeToString("this is not a certificate".getBytes());

        assertThatThrownBy(() -> new CsdCfdiCancellationSealer(new CsdProperties(notACertificate, encodedKey, null)))
                .isInstanceOf(FiscalCsdUnreadableException.class)
                .hasMessageContaining("app.fiscal.csd.certificado");
    }

    // ──────────────────────────────────────
    // the pipeline
    // ──────────────────────────────────────

    private CsdCfdiCancellationSealer sealer() {
        return new CsdCfdiCancellationSealer(new CsdProperties(encodedCertificate, encodedKey, null));
    }

    /** Seals a request the way the service will: render seal-less, seal those bytes, render again with the seal. */
    private String sealed(CancellationRequest request) {
        return builder.build(request, RFC, FECHA, sealer().seal(builder.build(request, RFC, FECHA)));
    }

    /**
     * A validate context over the document's own {@code Signature} element and the certificate's public key.
     *
     * <p>Secure validation stays <b>on</b>: the algorithms this tree emits are the ones the JDK considers secure,
     * and a regression back to the example's SHA-1 would be refused here instead of quietly accepted — which is
     * the opposite of what {@code VendorCancellationKnownAnswerTest} has to do for the vendor's 2021 file.</p>
     */
    private DOMValidateContext newContext(String sealedXml) {
        return new DOMValidateContext(certificate.getPublicKey(), element(sealedXml, "Signature"));
    }

    private boolean validates(String sealedXml) {
        var context = newContext(sealedXml);
        try {
            return unmarshal(context).validate(context);
        } catch (XMLSignatureException cannotValidate) {
            throw new IllegalStateException("The sealed request could not be validated at all.", cannotValidate);
        }
    }

    private static boolean referenceValidates(Reference reference, DOMValidateContext context) {
        try {
            return reference.validate(context);
        } catch (XMLSignatureException cannotValidate) {
            throw new IllegalStateException("The reference could not be validated at all.", cannotValidate);
        }
    }

    private static XMLSignature unmarshal(DOMValidateContext context) {
        try {
            return XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(context);
        } catch (MarshalException cannotRead) {
            throw new IllegalStateException(
                    "The sealed request could not be read as an XMLDSig, which means the sealer wrote something "
                            + "that is not one.",
                    cannotRead);
        }
    }

    private static Element element(String xml, String name) {
        var document = parse(xml);
        var nodes = document.getElementsByTagNameNS(DSIG, name);
        assertThat(nodes.getLength())
                .as("the sealed request states exactly one " + name)
                .isOne();
        return (Element) nodes.item(0);
    }

    private static org.w3c.dom.Document parse(String xml) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException | SAXException | IOException cannotParse) {
            throw new IllegalStateException("The request is not well-formed XML.", cannotParse);
        }
    }

    private static byte[] encoded(X509Certificate x509) {
        try {
            return x509.getEncoded();
        } catch (java.security.cert.CertificateException cannotEncode) {
            throw new IllegalStateException("The fixture certificate cannot be encoded.", cannotEncode);
        }
    }
}
