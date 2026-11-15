package com.lifecontrol.api.fiscal.cancellation;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.util.StringUtils;

/**
 * Renders one cancellation request as the Anexo 20 document the authority is asked to cancel — by hand, with no
 * XML library, no I/O and no clock ({@code T8b}, decision {@code L20}(g) of
 * {@code odd/tasks/cfdi-fiscal-document.md}).
 *
 * <p><b>The document, and the one reference it was read from.</b> Solución Factible publishes the SAT's own example
 * as {@code solicitudCancelacion-anexo20.zip} → {@code CancelaCFDIEjemplo.xml}, and this class renders that shape:
 * a {@code Cancelacion} root in {@code http://cancelacfd.sat.gob.mx} with {@code Fecha} and {@code RfcEmisor}, a
 * {@code Folios} wrapper holding one {@code Folio} with {@code UUID}, {@code Motivo} and — when there is one —
 * {@code FolioSustitucion}. The committed copy is the fixture of
 * {@code VendorCancellationKnownAnswerTest}, which asserts that these bytes and the vendor's are the same bytes for
 * the same values, character for character. Nothing here is recalled from a manual.</p>
 *
 * <p><b>Why it is written compactly, with no indentation, and why that is not a style choice.</b> The document's
 * bytes are its meaning: an XML Digital Signature digests the <b>canonicalised</b> document, so every newline and
 * space between elements would travel into the digest and into the signature. The vendor's example is one line with
 * a single newline after the declaration, and rendering it the same way makes the comparison in the known-answer
 * test a diff of identical bytes rather than a normalisation this tree invented. Whitespace <b>inside</b> an element
 * would be a different document; there is none to state.</p>
 *
 * <p><b>Why there are two entry points, and why the second takes a {@code Signature} it does not build.</b> This is
 * the same shape {@code CfdiXmlBuilder} has, and for a related reason: the seal-less rendering is what gets
 * <b>digested</b>, so the document that is signed and the document that is sent have to differ in the signature and
 * in nothing else ({@code L18}(d) makes the argument for the CFDI's {@code Sello}; {@code L20}(g) says why that
 * mechanism cannot be reused here). Patching a signature into a rendered string would be a signature over bytes
 * nobody kept, and building the {@code Signature} element is the sealer's job, not this class's: it holds no key, no
 * certificate and no canonicalisation, and it is a pure function of the request, the issuer and the instant.</p>
 *
 * <p><b>Why the instant is a parameter and there is no {@code Clock}.</b> {@code CfdiXmlBuilder} takes the
 * already-rendered {@link LocalDateTime}, and its caller — {@code FiscalDocumentStamper} — reads the configured
 * clock <b>once</b> per attempt and renders twice from that one value, because {@code Fecha} has second precision
 * and a second read is a document the authority never received ({@code L19}(d)). A cancellation request is the
 * <b>same instant</b> as the document it concerns ({@code L20}(g)), so this class copies the seam exactly: the one
 * read belongs to the caller, and because there is no clock here, a second read is not expressible rather than
 * merely untested. The format is the CFDI's — {@code yyyy-MM-dd'T'HH:mm:ss} with no offset, which the XSD's pattern
 * requires.</p>
 *
 * <p><b>What it deliberately leaves out.</b> No {@code Signature}, no {@code KeyInfo} and no certificate
 * {@code Certificado} attribute: a cancellation request states none of those, and the certificate travels inside the
 * {@code Signature}'s {@code KeyInfo} instead — which is why the sealer embeds it and this class never sees one. No
 * batch, either: the schema lets {@code Folios} hold several {@code Folio} and this class renders one, because a
 * cancellation here is one document's fate ({@code S7}) and a batch would need a decision about partial outcomes
 * that no caller in this tree has made.</p>
 */
public final class CfdiCancellationRequestBuilder {

    /** The Anexo 20 cancellation namespace, as the vendor's own example declares it. */
    private static final String CANCELLATION_NAMESPACE = "http://cancelacfd.sat.gob.mx";

    private static final String XSD_NAMESPACE = "http://www.w3.org/2001/XMLSchema";

    private static final String XSI_NAMESPACE = "http://www.w3.org/2001/XMLSchema-instance";

    /** The closing tag the sealed rendering is built from: the signature goes immediately before it. */
    private static final String CLOSING = "</Cancelacion>";

    /**
     * {@code Fecha} carries no offset: the XSD pattern forbids one and the value is the local time of the request,
     * which is the same second the CFDI it concerns states ({@code L20}(g)).
     */
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /**
     * Renders the request <b>without</b> a signature: the bytes an XML Digital Signature digests.
     *
     * @param request the document to cancel, its motive and, when the motive replaces it, the folio that does
     * @param rfcEmisor the issuer's RFC, {@code Cancelacion/@RfcEmisor}, which is the RFC of the company that
     *     stamped the document and not the receiver's
     * @param fecha the instant of the request, already rendered in the configured fiscal zone and carrying no offset
     * @return the cancellation document with no {@code Signature}, one newline after the declaration and none
     *     anywhere else
     */
    public String build(CancellationRequest request, String rfcEmisor, LocalDateTime fecha) {
        return render(request, rfcEmisor, fecha, null);
    }

    /**
     * Renders the request with the enveloped signature that covers the {@linkplain #build(CancellationRequest,
     * String, LocalDateTime) seal-less rendering}.
     *
     * <p>The signature is written <b>verbatim</b>, never escaped: it is markup the sealer produced from these same
     * bytes, and escaping it would turn a document into its own description. A blank one is refused instead of
     * written, because a document that states an empty {@code Signature} element claims a seal nobody made.</p>
     *
     * @param request the document to cancel, its motive and, when the motive replaces it, the folio that does
     * @param rfcEmisor the issuer's RFC, exactly as the seal-less rendering received it
     * @param fecha the same instant the seal-less rendering received, because the two documents are one
     * @param signature the {@code Signature} element the sealer answered for those bytes, in the
     *     {@code http://www.w3.org/2000/09/xmldsig#} namespace
     * @return the request to send, whose bytes are the seal-less rendering plus that one element
     */
    public String build(CancellationRequest request, String rfcEmisor, LocalDateTime fecha, String signature) {
        if (!StringUtils.hasText(signature)) {
            throw new IllegalArgumentException("A sealed cancellation request states its Signature element, and the "
                    + "one it was given is blank. A document with an empty Signature claims a seal nobody made; a "
                    + "document with none is what build(request, rfcEmisor, fecha) answers.");
        }
        return render(request, rfcEmisor, fecha, signature);
    }

    /** One renderer for the two entry points, so the signature is the only thing they can disagree on. */
    private String render(CancellationRequest request, String rfcEmisor, LocalDateTime fecha, String signature) {
        Objects.requireNonNull(request, "a cancellation request is what is rendered");
        Objects.requireNonNull(rfcEmisor, "a cancellation request states its issuer's RfcEmisor");
        Objects.requireNonNull(fecha, "a cancellation request states its Fecha, and it has no default");
        if (!StringUtils.hasText(rfcEmisor)) {
            throw new IllegalArgumentException("A cancellation request states RfcEmisor, and it is blank. Without it "
                    + "the authority has no issuer to match the document against, so the request is refused at the "
                    + "source instead of by the PAC.");
        }

        var xml = new StringBuilder(512);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<Cancelacion");
        attribute(xml, "xmlns", CANCELLATION_NAMESPACE);
        attribute(xml, "xmlns:xsd", XSD_NAMESPACE);
        attribute(xml, "xmlns:xsi", XSI_NAMESPACE);
        attribute(xml, "Fecha", FECHA.format(fecha));
        attribute(xml, "RfcEmisor", rfcEmisor);
        xml.append("><Folios>");
        folio(xml, request);
        xml.append("</Folios>");
        if (StringUtils.hasText(signature)) {
            xml.append(signature);
        }
        xml.append(CLOSING);
        return xml.toString();
    }

    /**
     * One {@code Folio}, with its attributes in the order the vendor's example states them.
     *
     * <p>The order is readability and nothing else: attribute order is not significant in XML, and an inclusive C14N
     * sorts every attribute before it is digested, so this order cannot change the signature. Matching the published
     * example is worth it because the known-answer test diffs these bytes against the vendor's.</p>
     */
    private static void folio(StringBuilder xml, CancellationRequest request) {
        xml.append("<Folio");
        if (request.statesSubstitutingFolio()) {
            attribute(xml, "FolioSustitucion", fiscalUuid(request.substitutingFolio()));
        }
        attribute(xml, "Motivo", request.motive().code());
        attribute(xml, "UUID", fiscalUuid(request.uuid()));
        // The space before the slash is the vendor's own empty-element form, kept so the two renderings compare.
        xml.append(" />");
    }

    /**
     * The fiscal UUID as the authority's documents state it: upper case, as every published example spells it.
     *
     * <p>{@link UUID#toString()} lower-cases, and the value is the same 128 bits either way — the XSD's pattern
     * admits both cases, and the SAT's own examples, including the vendor's, are upper case. This states the form
     * the examples show rather than the type's canonical spelling, because the document is read by an auditor
     * against those examples and not by a {@code UUID} parser.</p>
     */
    private static String fiscalUuid(UUID uuid) {
        return Objects.requireNonNull(uuid).toString().toUpperCase(Locale.ROOT);
    }

    private static void attribute(StringBuilder xml, String name, String value) {
        xml.append(' ')
                .append(name)
                .append("=\"")
                .append(escape(Objects.requireNonNull(value, "attribute " + name + " states a value")))
                .append('"');
    }

    /**
     * The five characters XML cannot carry literally, and nothing else.
     *
     * <p>The same rule {@code CfdiXmlBuilder} states, repeated here because that method is private to a class this
     * unit does not edit. It is not cosmetic in either place: a legal entity's RFC may carry an {@code &}, and an
     * unescaped one turns the request into a document the authority cannot parse. No whitespace folding and no
     * re-encoding — a second transformation would be a rule this class invented for a document the authority
     * already defines.</p>
     */
    private static String escape(String value) {
        var escaped = new StringBuilder(value.length() + 16);
        for (var index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            switch (character) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&apos;");
                default -> escaped.append(character);
            }
        }
        return escaped.toString();
    }
}
