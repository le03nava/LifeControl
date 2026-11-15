package com.lifecontrol.api.fiscal.model;

/**
 * The three attributes the authority's {@code cfdi:Comprobante} requires and this tree cannot compute: the
 * serial of the issuer's certificate, the certificate itself and the seal over the document
 * (decision {@code L12}(b) of {@code odd/tasks/cfdi-fiscal-document.md}).
 *
 * <p><b>Why this is an input and not the builder's job.</b> {@code Sello}, {@code NoCertificado} and
 * {@code Certificado} are <b>required</b> by the fourth revision's schema, and producing them is certificate
 * handling: a private key, its password, its validity and the {@code cadena original} it signs. That is
 * {@code T7}'s subject, and keeping it out of the builder is what makes the builder pure and its output a
 * function of the row set alone. A test passes a fixed seal, and no crypto enters this slice.</p>
 *
 * <p><b>Why the three are one record and not three parameters.</b> They are one fact — "this is who signs this
 * document" — and they always travel together: a certificate with the wrong serial is as wrong as no seal at
 * all, and a signature over a document whose {@code NoCertificado} names another certificate is a document the
 * authority refuses. The builder writes all three or it writes a {@code Comprobante} that cannot be stamped.</p>
 *
 * @param noCertificado the serial of the signing certificate, {@code Comprobante/@NoCertificado}
 * @param certificado the certificate in base 64, {@code Comprobante/@Certificado}
 * @param sello the signature over the document, {@code Comprobante/@Sello}
 */
public record IssuerSeal(String noCertificado, String certificado, String sello) {}
