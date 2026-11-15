package com.lifecontrol.api.fiscal.cancellation;

import java.util.Objects;
import java.util.UUID;

/**
 * One document's cancellation, as the port sends it: the Anexo 20 request <b>already built and sealed</b>, and the
 * UUID of the document it cancels ({@code T8c}, decisions {@code L20}(a) and {@code L20}(g) of
 * {@code odd/tasks/cfdi-fiscal-document.md}).
 *
 * <p><b>Why the document arrives as a string and is never built here.</b> The request the authority accepts is an
 * enveloped XMLDSig over a {@code Cancelacion} element ({@code L20}(g)), and producing it is {@code T8b}'s two
 * capabilities — the renderer and the sealer. A port that took a motive and a folio would have to hold a builder
 * and a key, and the adapter behind it would be the thing deciding what the document says. Sending is one job: what
 * was sealed travels as bytes, and an adapter that re-rendered or re-signed it would produce a document nobody
 * kept.</p>
 *
 * <p><b>Why the UUID travels beside the document that states it.</b> The answer names a UUID
 * ({@code CFDIResultadoCancelacion/UUID}) and it has to be compared with the document this call is about — the rule
 * {@link SolucionFactibleFiscalCancellationProvider} applies and the caller cannot check afterwards, because the
 * caller no longer has the wire. The document is authoritative for what is sent; this field is the same value the
 * caller wrote into it, carried in the type the row uses so the comparison cannot be made against a differently
 * spelled string.</p>
 *
 * @param requestXml the sealed {@code Cancelacion} document, byte for byte as the sealer answered it
 * @param uuid the fiscal UUID of the document being cancelled, {@code Folio/@UUID} inside that document
 */
public record FiscalCancellationRequest(String requestXml, UUID uuid) {

    /**
     * Refuses a request the port could not send or could not check.
     *
     * <p>Both fields are required and for different reasons: a blank document is nothing to send, and a missing UUID
     * is the one comparison the adapter performs against the answer thrown away — an answer about another document
     * would then be indistinguishable from this one's.</p>
     */
    public FiscalCancellationRequest {
        if (requestXml == null || requestXml.isBlank()) {
            throw new IllegalArgumentException(
                    "A cancellation carries the sealed Anexo 20 request it sends, and the one it was given is blank: "
                            + "the authority has nothing to cancel and nothing to verify a signature over.");
        }
        Objects.requireNonNull(
                uuid,
                "A cancellation carries the UUID of the document it cancels, because the answer states one and "
                        + "an answer about another document has to be recognisable as such.");
    }
}
