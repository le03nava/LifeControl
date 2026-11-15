package com.lifecontrol.api.fiscal.model;

/**
 * The two attributes the authority's {@code cfdi:Comprobante} requires of the issuer's certificate and which say
 * nothing about the seal: the serial and the certificate itself (decision {@code L18}(d) of
 * {@code odd/tasks/cfdi-fiscal-document.md)).
 *
 * <p><b>Why this is a record of its own instead of an {@link IssuerSeal} with a null seal.</b> The two facts are
 * what a document states <b>before</b> it is signed: the {@code Sello} is a signature over the document that
 * already carries them, so it cannot exist yet when those bytes are rendered. Overloading {@link IssuerSeal}
 * with a blank {@code sello} would leave one record whose javadoc says the three always travel together and
 * whose real contract would be "two of them always do" — the shape a caller has to read the code to discover. Two
 * records state the two stages instead, and the sealed one is the only one that claims a signature exists.</p>
 *
 * <p><b>Why it is not the sealer's interface.</b> The builder writes these attributes and knows nothing about
 * keys, certificates or signatures; {@code com.lifecontrol.api.fiscal.seal.CfdiSealer} produces them and
 * implements no XML. This record is the value they meet in.</p>
 *
 * @param noCertificado the serial of the signing certificate in <b>decimal</b>, {@code Comprobante/@NoCertificado}
 * @param certificado the certificate's DER in base 64, {@code Comprobante/@Certificado}
 */
public record IssuerCertificate(String noCertificado, String certificado) {}
