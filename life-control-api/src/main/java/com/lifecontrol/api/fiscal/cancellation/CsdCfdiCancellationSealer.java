package com.lifecontrol.api.fiscal.cancellation;

import com.lifecontrol.api.config.fiscal.CsdProperties;
import com.lifecontrol.api.fiscal.exception.FiscalCsdUnreadableException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.xml.crypto.MarshalException;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureException;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.keyinfo.X509Data;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.springframework.util.StringUtils;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * The CSD sealer for a cancellation request: an enveloped XML Digital Signature over the Anexo 20 document, built
 * with the JDK's {@code javax.xml.crypto.dsig} and the certificate and key this tree already loads
 * ({@code T8b}, decisions {@code L20}(a) and {@code L20}(g) of {@code odd/tasks/cfdi-fiscal-document.md}).
 *
 * <p><b>Why the JDK's own API and no dependency.</b> {@code XMLSignatureFactory} is the platform's implementation of
 * the same standard ({@code XML-DSig}) the authority validates, so this is the trade {@code L18}(g) already made for
 * SOAP: a named standard from the platform instead of a library that brings a provider, a version and a review of
 * somebody else's canonicalisation. It also keeps the private key inside this process, which is the whole reason
 * {@code L20}(a) chose the operation it chose — the vendor's alternative operations send the key to the PAC on every
 * call.</p>
 *
 * <p><b>The four algorithms, and why each is stated rather than defaulted.</b> {@code SignatureMethod} is
 * {@code RSA_SHA256} and {@code DigestMethod} is {@code SHA256}, because this tree emits the fourth revision's
 * algorithms and not the SHA-1 of the vendor's 2021 example — that example's algorithms are its age, not the
 * contract ({@code L20}(g)). {@code CanonicalizationMethod} is <b>inclusive</b> C14N, which is what every published
 * Anexo 20 request states and what makes the digest independent of the serialisation the verifier happens to read.
 * The one {@link Transform} is the enveloped-signature transform: it removes the {@code Signature} element from the
 * node-set before digesting, so the document that is signed states no signature at all — the same "the seal cannot
 * be computed from a document that already carries it" that {@code L18}(d) states for the CFDI's {@code Sello}.</p>
 *
 * <p><b>Why the certificate goes into {@code KeyInfo} and not into an attribute.</b> A cancellation request states
 * no {@code Certificado} and no {@code NoCertificado}: the certificate travels inside {@code X509Data}, and that is
 * where the verifier finds the public key. So this class embeds the configured certificate there and returns no
 * separate certificate facts — unlike {@code CsdCfdiSealer}, whose two attributes a CFDI states on its root.</p>
 *
 * <p><b>Why the material is read once, in the constructor, with the CSD's own refusals.</b> A certificate and a key
 * are the same for every request of a deployment, and a configuration mistake belongs at <b>boot</b>, where the
 * container restarts and an operator sees it, rather than at the first cancellation of the day, after a document
 * has been read and a caller is waiting. The formats are the ones {@code CsdProperties} documents — the certificate
 * as base 64 <b>DER</b>, the key as base 64 <b>PKCS#8</b>, optionally protected — and the refusals are
 * {@link FiscalCsdUnreadableException}'s, so a stamp and a cancellation fail with one vocabulary and name the same
 * properties ({@code app.fiscal.csd.certificado}, {@code app.fiscal.csd.llave}, {@code app.fiscal.csd.password}).
 * A SAT {@code .key} container is still not read here, for the reason {@code L18}(h) records.</p>
 *
 * <p><b>What it does not share with {@code CsdCfdiSealer}, and why that is stated instead of hidden.</b> The CSD
 * <b>reading</b> — decode, parse the certificate, open the key — is the same shape in both classes and is repeated
 * here rather than extracted, because the CFDI sealer keeps its key private to itself by design and this unit may
 * not edit it. A third reader would make a shared {@code Csd} type worth its own unit; two is where a copy is still
 * cheaper than an indirection, and the failure both would report is the same one.</p>
 */
public final class CsdCfdiCancellationSealer implements CfdiCancellationSealer {

    /** The property the certificate comes from, named in a refusal so an operator knows what to fix. */
    private static final String CERTIFICATE_PROPERTY = "app.fiscal.csd.certificado";

    /** The property the private key comes from, named the same way. */
    private static final String KEY_PROPERTY = "app.fiscal.csd.llave";

    private static final String DSIG_NAMESPACE = "http://www.w3.org/2000/09/xmldsig#";

    private final XMLSignatureFactory signatureFactory = XMLSignatureFactory.getInstance("DOM");

    private final X509Certificate certificate;

    private final PrivateKey privateKey;

    /**
     * Reads the CSD material a deployment configures.
     *
     * @param properties the bound {@code app.fiscal.csd.*} values, the same ones a stamp uses
     * @throws FiscalCsdUnreadableException when the certificate or the key is not what this class reads, or the
     *     key's password does not open it
     */
    public CsdCfdiCancellationSealer(CsdProperties properties) {
        Objects.requireNonNull(properties, "a sealer reads the CSD configuration, and it is absent");
        this.certificate = certificateOf(properties.certificado());
        this.privateKey = privateKeyOf(properties.llave(), properties.password());
    }

    @Override
    public String seal(String unsignedRequestXml) {
        Objects.requireNonNull(unsignedRequestXml, "a signature covers a document, and there is none to cover here");
        var document = parse(unsignedRequestXml);
        var root = document.getDocumentElement();
        var signature = signatureFor();
        try {
            // The signature is built into this document and not into a copy: the reference digests the root through
            // the enveloped transform, which removes the Signature element it is about to add — so what is covered
            // is exactly the seal-less rendering the caller handed over.
            signature.sign(new DOMSignContext(privateKey, root));
            var signatures = document.getElementsByTagNameNS(DSIG_NAMESPACE, "Signature");
            if (signatures.getLength() != 1) {
                throw new IllegalStateException("The signature was built into the request and the document states "
                        + signatures.getLength() + " Signature elements instead of one, so there is nothing to "
                        + "return.");
            }
            return serialized(signatures.item(0));
        } catch (MarshalException | XMLSignatureException cannotSign) {
            throw new IllegalStateException(
                    "The cancellation request could not be signed with " + SignatureMethod.RSA_SHA256
                            + ". The configured key is read successfully and the JDK provides the algorithm, so this "
                            + "is a defect of the runtime rather than of the configuration.",
                    cannotSign);
        }
    }

    // ──────────────────────────────────────
    // the signature
    // ──────────────────────────────────────

    /**
     * The enveloped signature structure, with no value in it yet.
     *
     * <p>Every algorithm is stated explicitly and none is defaulted: a default here would be whichever the factory
     * happens to prefer, and the authority validates a named algorithm. The reference carries no {@code Type} and no
     * {@code Id} because the format states neither, and its {@code URI} is empty because the enveloping document is
     * the thing being signed.</p>
     */
    private XMLSignature signatureFor() {
        try {
            var enveloped = signatureFactory.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null);
            var reference = signatureFactory.newReference(
                    "", signatureFactory.newDigestMethod(DigestMethod.SHA256, null), List.of(enveloped), null, null);
            var signedInfo = signatureFactory.newSignedInfo(
                    signatureFactory.newCanonicalizationMethod(
                            CanonicalizationMethod.INCLUSIVE, (C14NMethodParameterSpec) null),
                    signatureFactory.newSignatureMethod(SignatureMethod.RSA_SHA256, null),
                    List.of(reference));
            return signatureFactory.newXMLSignature(signedInfo, keyInfo());
        } catch (GeneralSecurityException cannotBuild) {
            throw new IllegalStateException(
                    "The JDK does not provide the algorithms an enveloped XMLDSig for a cancellation request states "
                            + "(inclusive C14N, SHA-256, RSA-SHA256, enveloped transform), which means the runtime is "
                            + "not the one this class was written against.",
                    cannotBuild);
        }
    }

    /** The certificate inside {@code KeyInfo/X509Data}, which is the only place a request can state it. */
    private javax.xml.crypto.dsig.keyinfo.KeyInfo keyInfo() {
        KeyInfoFactory factory = signatureFactory.getKeyInfoFactory();
        X509Data data = factory.newX509Data(List.of(certificate));
        return factory.newKeyInfo(List.of(data));
    }

    // ──────────────────────────────────────
    // the CSD material
    // ──────────────────────────────────────

    private static X509Certificate certificateOf(String encodedCertificate) {
        if (!StringUtils.hasText(encodedCertificate)) {
            throw FiscalCsdUnreadableException.ofCertificate(
                    CERTIFICATE_PROPERTY, new IllegalArgumentException("the value is blank"));
        }
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
     * <p>A protected key is an {@link EncryptedPrivateKeyInfo} envelope whose algorithm name tells which
     * {@link SecretKeyFactory} derives the key that unwraps it — the algorithm is read from the envelope and never
     * assumed. A blank value is refused by name instead of reaching a decoder that would answer with an empty array
     * and a {@code KeyFactory} failure an operator cannot read.</p>
     */
    private static PrivateKey privateKeyOf(String encodedKey, String password) {
        var protectedKey = StringUtils.hasText(password);
        if (!StringUtils.hasText(encodedKey)) {
            throw FiscalCsdUnreadableException.ofPrivateKey(
                    KEY_PROPERTY, protectedKey, new IllegalArgumentException("the value is blank"));
        }
        var der = decode(encodedKey, KEY_PROPERTY);
        try {
            if (!protectedKey) {
                return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
            }
            var envelope = new EncryptedPrivateKeyInfo(der);
            var secret = SecretKeyFactory.getInstance(envelope.getAlgName())
                    .generateSecret(new PBEKeySpec(password.toCharArray()));
            var unwrapped = envelope.getKeySpec(secret);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(unwrapped.getEncoded()));
        } catch (GeneralSecurityException | IOException cannotOpen) {
            throw FiscalCsdUnreadableException.ofPrivateKey(KEY_PROPERTY, protectedKey, cannotOpen);
        }
    }

    private static byte[] decode(String encoded, String property) {
        try {
            return Base64.getMimeDecoder().decode(encoded);
        } catch (IllegalArgumentException notBase64) {
            throw FiscalCsdUnreadableException.ofNotBase64(property, notBase64);
        }
    }

    // ──────────────────────────────────────
    // the document
    // ──────────────────────────────────────

    /**
     * Parses the request, refusing a document that declares a {@code DOCTYPE}.
     *
     * <p>The input is this tree's own rendering, so the refusal is defence in depth rather than a live boundary —
     * but it is the same hardening the SAT's stylesheet needed ({@code L19}(d)): a signature is computed over a
     * node-set, and an entity a {@code DOCTYPE} expands is part of it, so a document that can declare one can sign
     * something other than the bytes it appears to state.</p>
     */
    private static org.w3c.dom.Document parse(String requestXml) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(requestXml)));
        } catch (ParserConfigurationException | SAXException | IOException cannotParse) {
            throw new IllegalArgumentException(
                    "The cancellation request is not a well-formed XML document that declares no DOCTYPE, so "
                            + "nothing can be signed over it.",
                    cannotParse);
        }
    }

    /**
     * The {@code Signature} element alone, as markup.
     *
     * <p>It is serialised rather than hand-written: canonicalisation makes the node-set, not the serialisation, the
     * thing that is signed, so the builder can write these bytes back into a fresh rendering of the same request
     * and the verifier still reads the same {@code SignedInfo}. The base 64 values the JDK marshals carry MIME line
     * breaks, which the XML schema's {@code base64Binary} collapses and every decoder this document reaches
     * tolerates — the vendor's own example wraps them too, with spaces.</p>
     */
    private static String serialized(Node signature) {
        try {
            var transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            var out = new StringWriter();
            transformer.transform(new DOMSource(signature), new StreamResult(out));
            return out.toString();
        } catch (TransformerException cannotSerialize) {
            throw new IllegalStateException(
                    "The Signature element was built and could not be serialised, so the request would be signed "
                            + "without stating the signature. This is a defect of the runtime.",
                    cannotSerialize);
        }
    }
}
