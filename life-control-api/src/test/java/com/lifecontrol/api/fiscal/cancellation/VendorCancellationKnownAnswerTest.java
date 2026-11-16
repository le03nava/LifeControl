package com.lifecontrol.api.fiscal.cancellation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.lifecontrol.api.fiscal.model.FiscalCancellationMotive;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;
import javax.xml.crypto.MarshalException;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureException;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * The known-answer test: the vendor's own published cancellation request, read as the format it claims to be
 * ({@code T8b}, decisions {@code L20}(g) and {@code L20}(h) of {@code odd/tasks/cfdi-fiscal-document.md}).
 *
 * <p><b>What it proves, and it is not "our sealer works".</b> The committed file is
 * {@code CancelaCFDIEjemplo.xml} from Solución Factible's {@code solicitudCancelacion-anexo20.zip}: a real Anexo 20
 * request, signed in 2021 by a real CSD, whose {@code KeyInfo} carries the certificate that signed it. So this
 * suite asks the <b>JDK</b> to verify it — the digest over the enveloped document, and the {@code SignatureValue}
 * over {@code SignedInfo}, against the public key of the certificate inside the file. If either fails, this tree's
 * reading of the format (the namespace, the {@code Reference URI=""}, the single enveloped transform, the
 * inclusive C14N) is not the vendor's, and nothing this tree renders can be trusted to be a cancellation request.
 * The last case goes one step further and compares the file's bytes, minus its {@code Signature}, with what
 * {@link CfdiCancellationRequestBuilder} renders from the same values.</p>
 *
 * <p><b>Why the SHA-1 algorithms do not weaken either case, and how secure validation is handled.</b> The example
 * was signed in 2021 with {@code rsa-sha1} and {@code sha1}, which the JDK refuses outright while secure validation
 * is enabled — asserted below, because that refusal is what makes the flag's absence meaningful. Turning it off
 * for <b>this</b> file proves a digest and a signature that already exist; it says nothing about what this tree
 * emits, which is pinned to SHA-256 and RSA-SHA256 by {@link CsdCfdiCancellationSealerTest} with secure validation
 * <b>on</b>. The digest algorithm an old file used is its age, not the contract ({@code L20}(g)).</p>
 *
 * <p><b>Why the expected value prepends a declaration the vendor's file does not have.</b> This tree's renderer
 * writes {@code <?xml version="1.0" encoding="UTF-8"?>} and the vendor's file was saved without one. A prolog is
 * not part of the document's infoset and cannot be signed — an inclusive C14N starts at the document element — so
 * it is written by convention and not by the format. It is stated in the expectation rather than skipped, so the
 * comparison covers <b>every</b> byte of both documents and the only difference is the one byte sequence whose
 * absence the format neither requires nor forbids.</p>
 */
@DisplayName("The vendor's own cancellation request, validated as the format it is (T8b, L20(g), L20(h))")
class VendorCancellationKnownAnswerTest {

    /** The vendor's own example, committed verbatim with its provenance in the README beside it. */
    private static final String FIXTURE = "fiscal/solucionfactible/CancelaCFDIEjemplo.xml";

    private static final String DSIG = "http://www.w3.org/2000/09/xmldsig#";

    /**
     * The {@code DigestValue} the vendor's file states, which is the vendor's own arithmetic over its own document.
     *
     * <p>Kept as a literal: the case that follows recomputes it, so a fixture replaced with another file that
     * happens to be self-consistent would still be caught here.</p>
     */
    private static final String VENDORS_DIGEST_VALUE = "G/Rcmm3qED8kd+r+/HLpR6fp+Gg=";

    /** The instant the vendor's request states, which is local time with no offset ({@code L20}(g)). */
    private static final LocalDateTime VENDORS_FECHA = LocalDateTime.of(2021, 12, 22, 9, 4, 6);

    private static final String VENDORS_RFC = "EKU9003173C9";

    private static final UUID VENDORS_UUID = UUID.fromString("86b8719c-2a66-4f52-8b6c-36e69c57cf5a");

    private static final UUID VENDORS_SUBSTITUTE = UUID.fromString("8c3f076a-ce2a-4847-8566-43ab1cba228c");

    private static final String CLOSING = "</Cancelacion>";

    /** The prolog this tree's renderer writes and the vendor's saved file does not (see the class javadoc). */
    private static final String DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";

    @Test
    @DisplayName("the file's own signature validates against the certificate embedded in its own KeyInfo")
    void theVendorsDocumentValidatesAgainstItsOwnCertificate() {
        var context = contextOverTheVendorsSignature();
        var signature = unmarshal(context);

        assertThat(validates(signature, context))
                .as("a vendor's published request validates with the JDK alone, which is this tree's reading of "
                        + "the format being the vendor's")
                .isTrue();
        var reference = (Reference) signature.getSignedInfo().getReferences().getFirst();
        assertThat(Base64.getEncoder().encodeToString(reference.getDigestValue()))
                .as("and the digest is the vendor's own, not one this suite computed")
                .isEqualTo(VENDORS_DIGEST_VALUE);
        assertThat(referenceValidates(reference, context))
                .as("the JDK's C14N digests the vendor's document to the vendor's DigestValue")
                .isTrue();
        assertThat(signature.getSignedInfo().getCanonicalizationMethod().getAlgorithm())
                .as("inclusive C14N, the algorithm both renderings state")
                .isEqualTo(CanonicalizationMethod.INCLUSIVE);
    }

    @Test
    @DisplayName("the JDK refuses the file's SHA-1 signature while secure validation is on, and that is why the "
            + "flag is turned off for it")
    void secureValidationRefusesTheVendorsSha1() {
        var context = new DOMValidateContext(
                certificateOfTheVendorsKeyInfo().getPublicKey(), element(fixture(), "Signature"));

        var refusal =
                catchThrowableOfType(MarshalException.class, () -> factory().unmarshalXMLSignature(context));

        assertThat(refusal)
                .as("the refusal is the flag's, not this tree's: it is what the assertions above override, "
                        + "for a file signed before SHA-256 was the answer")
                .isNotNull();
        assertThat(refusal.getMessage())
                .as("and it names the algorithm a 2021 request used")
                .contains("sha1");
    }

    @Test
    @DisplayName("our renderer states the vendor's document byte for byte, minus its Signature")
    void ourRendererStatesTheVendorsDocument() {
        var vendorsDocument = fixture();
        var withoutSignature = vendorsDocument.substring(0, vendorsDocument.indexOf("<Signature")) + CLOSING;

        var ours = new CfdiCancellationRequestBuilder()
                .build(
                        new CancellationRequest(
                                VENDORS_UUID, FiscalCancellationMotive.ERRORS_WITH_RELATION, VENDORS_SUBSTITUTE),
                        VENDORS_RFC,
                        VENDORS_FECHA);

        assertThat(ours)
                .as("the same values render the vendor's own bytes: their namespace, their attribute spellings, "
                        + "their empty-element form and their Folios/Folio shape")
                .isEqualTo(DECLARATION + withoutSignature);
    }

    // ──────────────────────────────────────
    // the file and the signature inside it
    // ──────────────────────────────────────

    /**
     * A validate context over the vendor's signature, with the public key from the vendor's own {@code X509Data}.
     *
     * <p>The certificate's base 64 carries the line breaks its signer wrote, so it is decoded with the MIME
     * decoder — the tolerance a decoder needs for a document a human may have wrapped.</p>
     */
    private DOMValidateContext contextOverTheVendorsSignature() {
        var context = new DOMValidateContext(
                certificateOfTheVendorsKeyInfo().getPublicKey(), element(fixture(), "Signature"));
        context.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.FALSE);
        return context;
    }

    private static X509Certificate certificateOfTheVendorsKeyInfo() {
        var encoded = element(fixture(), "X509Certificate").getTextContent();
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(
                            new ByteArrayInputStream(Base64.getMimeDecoder().decode(encoded)));
        } catch (GeneralSecurityException cannotRead) {
            throw new IllegalStateException(
                    "The certificate embedded in the vendor's example could not be read, which would make this "
                            + "known-answer test a statement about nothing.",
                    cannotRead);
        }
    }

    private static boolean validates(XMLSignature signature, DOMValidateContext context) {
        try {
            return signature.validate(context);
        } catch (XMLSignatureException cannotValidate) {
            throw new IllegalStateException("The vendor's signature could not be validated at all.", cannotValidate);
        }
    }

    private static boolean referenceValidates(Reference reference, DOMValidateContext context) {
        try {
            return reference.validate(context);
        } catch (XMLSignatureException cannotValidate) {
            throw new IllegalStateException("The vendor's reference could not be validated at all.", cannotValidate);
        }
    }

    private static XMLSignature unmarshal(DOMValidateContext context) {
        try {
            return factory().unmarshalXMLSignature(context);
        } catch (MarshalException cannotRead) {
            throw new IllegalStateException("The vendor's file is not an XMLDSig this runtime can read.", cannotRead);
        }
    }

    private static XMLSignatureFactory factory() {
        return XMLSignatureFactory.getInstance("DOM");
    }

    private static Element element(String xml, String name) {
        var nodes = parse(xml).getElementsByTagNameNS(DSIG, name);
        assertThat(nodes.getLength()).as("the file states exactly one " + name).isOne();
        return (Element) nodes.item(0);
    }

    private static String fixture() {
        try (InputStream stream =
                VendorCancellationKnownAnswerTest.class.getClassLoader().getResourceAsStream(FIXTURE)) {
            assertThat(stream)
                    .as("the vendor's example is on the test classpath: " + FIXTURE)
                    .isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException cannotRead) {
            throw new IllegalStateException("The vendor's example cannot be read.", cannotRead);
        }
    }

    private static org.w3c.dom.Document parse(String xml) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException | SAXException | IOException cannotParse) {
            throw new IllegalStateException("The file is not well-formed XML.", cannotParse);
        }
    }
}
