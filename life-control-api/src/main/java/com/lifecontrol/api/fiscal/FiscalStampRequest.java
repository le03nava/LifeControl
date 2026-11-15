package com.lifecontrol.api.fiscal;

/**
 * What is sent to the stamping authority: the document this tree built, and the one identity the provider
 * needs to address the request.
 *
 * <p><b>Why the XML and not the row set.</b> A CFDI is stamped as a document: the provider signs the bytes it
 * receives, so what travels is exactly what {@link CfdiXmlBuilder} rendered and never a re-derivation of it.
 * The returned document — with the {@code TimbreFiscalDigital} the provider added — is what becomes the
 * artefact ({@code S10}), so the request and the answer are two different strings and this record carries the
 * first.</p>
 *
 * <p><b>Why the emitter's RFC is the only identity beside it.</b> A PAC addresses a stamp by the issuer whose
 * certificate stamps it: the emitter's RFC is the credential's identity in the vendors this tree may use, and
 * it is a fact the document already froze ({@code emisor_rfc}, {@code S9}) — nothing secret travels here, the
 * certificate and its private key live behind the port in {@code T7}. Every other fact a vendor might ask for
 * (the receiver's RFC, the total, the currency) is inside the XML, so restating it beside the document would
 * be a second copy the two could disagree about.</p>
 *
 * @param xml the document as rendered for stamping, with no {@code TimbreFiscalDigital}
 * @param emisorRfc the issuer's RFC, frozen on the document this XML states
 */
public record FiscalStampRequest(String xml, String emisorRfc) {}
