package com.lifecontrol.api.fiscal.seal;

import com.lifecontrol.api.fiscal.model.IssuerCertificate;

/**
 * The two things a stamp needs from the issuer's CSD, and nothing else ({@code T7b}, decisions {@code L18}(d)
 * and {@code L18}(h) of {@code odd/tasks/cfdi-fiscal-document.md)).
 *
 * <p><b>Why the interface is this small.</b> A stamp needs two facts and one operation: the serial and the
 * certificate the document states, and the signature over the bytes it just rendered. That is the whole surface,
 * so no {@code PrivateKey} and no stylesheet appears in a signature here — a caller that could pass a key would be
 * a caller that could sign something other than the document it built, and a caller that could pass a stylesheet
 * would be one that could choose what the {@code cadena original} is. Both live inside the implementation.</p>
 *
 * <p><b>Why {@link #seal(String)} takes the document and not the chain.</b> The chain is the authority's
 * composition over the document, produced by the authority's own transform; making it the caller's argument would
 * put the one string this tree must not invent within reach of every caller. The implementation transforms, then
 * signs — one step from the outside, three inside.</p>
 *
 * <p><b>What a stamp does when this is absent.</b> Nothing implements it in a deployment that does not invoice:
 * {@code com.lifecontrol.api.config.fiscal.FiscalSealConfig} registers it only with
 * {@code app.fiscal.provider.enabled=true}, and the attempt asks for it the way it asks for the provider, refusing
 * <b>that attempt</b> by name
 * ({@code com.lifecontrol.api.fiscal.exception.FiscalSealNotConfiguredException}). A stamp needs a seal and a PAC,
 * so the pair is one capability and one switch.</p>
 */
public interface CfdiSealer {

    /**
     * The certificate the document states, as {@code NoCertificado} in decimal and {@code Certificado} in base 64.
     *
     * @return the serial and the DER of the configured CSD certificate
     */
    IssuerCertificate certificate();

    /**
     * Signs {@code unsignedXml} and answers the base 64 signature that becomes the document's {@code Sello}.
     *
     * @param unsignedXml a CFDI 4.0 rendered <b>without</b> a {@code Sello}; a document that states one cannot be
     *     sealed, because the signature covers these bytes and not a patched copy of them
     * @return the signature over the {@code cadena original} of that document, in base 64
     */
    String seal(String unsignedXml);
}
