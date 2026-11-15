package com.lifecontrol.api.fiscal.cancellation;

/**
 * Seals an Anexo 20 cancellation request: the seam between the document this tree renders and the XML Digital
 * Signature the authority verifies ({@code T8b}, decision {@code L20}(g) of
 * {@code odd/tasks/cfdi-fiscal-document.md}).
 *
 * <p><b>Why this is not {@code CfdiSealer} and cannot be.</b> A CFDI's seal is
 * {@code SHA256withRSA(cadena original)} — a signature over a pipe-separated string the SAT's own stylesheet
 * produces. A cancellation request states no such string: its signature <b>is</b> an element of the document, an
 * enveloped XMLDSig where canonicalisation turns the document into a node-set digest, that digest goes into
 * {@code SignedInfo}, and the signature covers {@code SignedInfo}. Two mechanisms, two interfaces; reusing the
 * first would produce a request the authority refuses while looking signed.</p>
 *
 * <p><b>Why it answers an element and not a document.</b> The bytes that are digested are the {@code Cancelacion}
 * <b>without</b> its {@code Signature}, and only the caller holds them: {@link CfdiCancellationRequestBuilder}
 * renders them, this sealer covers them, and the same builder writes the signature back into a second rendering of
 * the same request. A sealer that parsed, signed and returned a whole document would be a second renderer of it, and
 * the two renderings — the one digested and the one sent — could then disagree in something other than the
 * signature, which is the failure {@code L18}(d) exists to exclude.</p>
 *
 * <p><b>Why it is an interface at all.</b> So a test, or a deployment without a CSD, substitutes it: a fake answers
 * a fixed element and the builder's bytes are pinned without any cryptography, and the real implementation is
 * proven by validating what it produced. {@code FiscalDocumentStamper} asks for its own sealer through an
 * {@code ObjectProvider} for the same reason, and the service that sends a cancellation will do the same.</p>
 */
public interface CfdiCancellationSealer {

    /**
     * Seals these bytes.
     *
     * @param unsignedRequestXml the request exactly as {@link CfdiCancellationRequestBuilder} rendered it with no
     *     signature: the document the {@code Reference} digests through the enveloped transform
     * @return the {@code Signature} element alone, in {@code http://www.w3.org/2000/09/xmldsig#}, to be written
     *     inside the {@code Cancelacion} it covers
     */
    String seal(String unsignedRequestXml);
}
