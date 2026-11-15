package com.lifecontrol.api.fiscal;

import com.lifecontrol.api.fiscal.exception.FiscalTaxMappingMissingException;
import com.lifecontrol.api.fiscal.model.FiscalDocument;
import com.lifecontrol.api.fiscal.model.FiscalDocumentLine;
import com.lifecontrol.api.fiscal.model.IssuerSeal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.util.StringUtils;

/**
 * Renders one frozen fiscal document as the CFDI 4.0 the authority stamps — manually, with no XML library, no
 * I/O, no repository and no network (decisions {@code L12}(b)–({@code f}) of
 * {@code odd/tasks/cfdi-fiscal-document.md}).
 *
 * <p><b>Why a {@code StringBuilder} and not an XML library.</b> The tree ships none, {@code L12} says not to add
 * one, and the output is a fixed shape whose escaping rules are five characters wide. {@link #escape} writes
 * {@code & < > " '} and transforms nothing else; a library would bring a serializer, a dependency and a
 * namespace policy to a document this class already knows field by field.</p>
 *
 * <p><b>Why the output is deterministic, and why that matters.</b> The same document and lines produce the same
 * bytes, twice: the lines are rendered in the order the caller hands them (which is the order the snapshot
 * writer wrote them), the tax summary is grouped in first-seen order, and every number is
 * {@link BigDecimal#toPlainString()} on a value whose scale this class states. A builder that reordered
 * anything would make the sent document and the row set two accounts of one fact.</p>
 *
 * <p><b>The two catalog facts it is given and does not read.</b> {@code S9} freezes every fact the document
 * states, and two of them are not columns of the row set:</p>
 *
 * <ul>
 *   <li>the authority's {@code Impuesto} code ({@code 002} for IVA) of each concept — the line froze the
 *       <b>tree's</b> code ({@code IVA}, {@code TASA_0}, {@code EXENTO}), not the authority's coordinate, so
 *       this class receives the mapping and never touches {@code tax_code} itself;</li>
 *   <li>the document currency's scale — the money is {@code NUMERIC(19,4)} and the authority states the
 *       document's three amounts at the <b>currency's</b> decimals, read from {@code currency.minor_units} and
 *       never from a literal ({@code L12}(d)).</li>
 * </ul>
 *
 * <p><b>Why they are constructor parameters and not a repository.</b> {@code L12} asks for a builder with no
 * I/O and no repository, and the alternative reading — a {@code @Component} holding {@code TaxCodeRepository}
 * and {@code CurrencyRepository} — would make «the same input twice» a claim about the catalog instead of about
 * the document. Resolving the two facts is the attempt's job ({@code FiscalDocumentStamper}), which reads them
 * <b>once</b> per stamp, and this class is then a function of the row set: the same document, lines, seal and
 * instant render the same bytes forever. The choice is deliberate; the instruction in the slice brief said to
 * say which was kept and why.</p>
 *
 * <p><b>What it deliberately does not write.</b> No {@code cfdi:Complemento} and no
 * {@code cfdi:TimbreFiscalDigital}: this is the document <b>sent</b> for stamping, and the timbre is what the
 * provider adds and returns in its own XML ({@code FiscalStampResult.Stamped#xml()}), which is the bytes the
 * artefact keeps ({@code S10}).</p>
 */
public final class CfdiXmlBuilder {

    /** The fourth revision's namespace, as the authority publishes it. */
    private static final String CFDI_NAMESPACE = "http://www.sat.gob.mx/cfd/4";

    private static final String XSI_NAMESPACE = "http://www.w3.org/2001/XMLSchema-instance";

    /**
     * The published {@code xsi:schemaLocation}, with the leading space it carries there.
     *
     * <p>The space is not a typo to trim: a PAC validates against the pair and a wrong {@code schemaLocation} is
     * a documented rejection, so the value is copied as published and pinned by a test.</p>
     */
    private static final String SCHEMA_LOCATION =
            " http://www.sat.gob.mx/cfd/4 http://www.sat.gob.mx/sitio_internet/cfd/4/cfdv40.xsd";

    private static final String CFDI_VERSION = "4.0";

    /** {@code c_ObjetoImp} {@code 01}: not subject to the tax, so the concept states no tax node. */
    private static final String TAX_OBJECT_NOT_SUBJECT = "01";

    /** The only factor this tree can state with a number and an amount ({@code V103}, {@code L12}(f)). */
    private static final String FACTOR_RATE = "Tasa";

    /** The concept's amounts carry the authority's maximum of six decimals. */
    private static final int CONCEPT_DECIMALS = 6;

    /** {@code TasaOCuota} is stated at six decimals, as the authority's schema allows. */
    private static final int RATE_DECIMALS = 6;

    /**
     * {@code Fecha} carries no offset: the XSD pattern forbids one and the guide says it is the local time where
     * the document is issued, which is why the attempt renders it in one configured zone ({@code L12}(c)).
     */
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** How many decimals the document's own three amounts carry: its currency's, never a literal. */
    private final int currencyMinorUnits;

    /** The authority's {@code Impuesto} per tree tax code, resolved once by the caller (see the class javadoc). */
    private final Map<String, String> satImpuestoByTaxCode;

    /**
     * @param currencyMinorUnits the document currency's decimals, from {@code currency.minor_units}
     * @param satImpuestoByTaxCode the authority's {@code Impuesto} per tax code; a subject concept whose code is
     *     absent is refused by name instead of guessed
     */
    public CfdiXmlBuilder(int currencyMinorUnits, Map<String, String> satImpuestoByTaxCode) {
        if (currencyMinorUnits < 0 || currencyMinorUnits > 6) {
            throw new IllegalArgumentException(
                    "A currency states between 0 and 6 decimals (currency.minor_units is SMALLINT BETWEEN 0 AND 4), "
                            + "and " + currencyMinorUnits + " is not one of them.");
        }
        this.currencyMinorUnits = currencyMinorUnits;
        this.satImpuestoByTaxCode = Map.copyOf(satImpuestoByTaxCode);
    }

    /**
     * Renders the document.
     *
     * @param document the frozen header, as the snapshot writer left it
     * @param lines its concepts, in the order the document states them
     * @param seal the three attributes {@code T7} produces and this class only writes
     * @param fecha the attempt's instant, already rendered in the configured zone and carrying no offset
     * @return the CFDI 4.0 to send, with no {@code TimbreFiscalDigital}
     */
    public String build(FiscalDocument document, List<FiscalDocumentLine> lines, IssuerSeal seal, LocalDateTime fecha) {
        Objects.requireNonNull(document, "a document is what is rendered");
        Objects.requireNonNull(lines, "a document states its concepts");
        Objects.requireNonNull(seal, "a CFDI states the certificate and the seal that stamp it");
        Objects.requireNonNull(fecha, "a CFDI states its date of issue");

        var xml = new StringBuilder(8192);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<cfdi:Comprobante");
        attribute(xml, "xmlns:cfdi", CFDI_NAMESPACE);
        attribute(xml, "xmlns:xsi", XSI_NAMESPACE);
        attribute(xml, "xsi:schemaLocation", SCHEMA_LOCATION);
        attribute(xml, "Version", CFDI_VERSION);
        // Serie and Folio are an optional local convention (ADR-0007 decision 2): written when the row has them
        // and absent otherwise, because an empty attribute is a value the authority reads as a stated one.
        if (StringUtils.hasText(document.getSerie())) {
            attribute(xml, "Serie", document.getSerie());
        }
        if (StringUtils.hasText(document.getFolio())) {
            attribute(xml, "Folio", document.getFolio());
        }
        attribute(xml, "Fecha", FECHA.format(fecha));
        // FormaPago is conditional in the fourth revision: stated only when the row states it (S13's slice is
        // what makes it mandatory-forever, and it is not this one).
        if (StringUtils.hasText(document.getFormaPago())) {
            attribute(xml, "FormaPago", document.getFormaPago());
        }
        attribute(xml, "MetodoPago", document.getMetodoPago());
        attribute(xml, "Moneda", document.getCurrency());
        attribute(xml, "TipoDeComprobante", document.getTipoComprobante());
        attribute(xml, "Exportacion", document.getExportacion());
        attribute(xml, "LugarExpedicion", document.getLugarExpedicion());
        attribute(xml, "SubTotal", documentAmount(document.getSubtotal()));
        if (document.getDiscountTotal().signum() != 0) {
            attribute(xml, "Descuento", documentAmount(document.getDiscountTotal()));
        }
        attribute(xml, "Total", documentAmount(document.getTotalAmount()));
        attribute(xml, "NoCertificado", seal.noCertificado());
        attribute(xml, "Certificado", seal.certificado());
        attribute(xml, "Sello", seal.sello());
        xml.append(">\n");

        xml.append("  <cfdi:Emisor");
        attribute(xml, "Rfc", document.getEmisorRfc());
        attribute(xml, "Nombre", document.getEmisorName());
        attribute(xml, "RegimenFiscal", document.getEmisorRegimen());
        xml.append("/>\n");

        xml.append("  <cfdi:Receptor");
        attribute(xml, "Rfc", document.getReceptorRfc());
        attribute(xml, "Nombre", document.getReceptorName());
        attribute(xml, "DomicilioFiscalReceptor", document.getReceptorZipCode());
        attribute(xml, "RegimenFiscalReceptor", document.getReceptorRegimen());
        attribute(xml, "UsoCFDI", document.getReceptorUsoCfdi());
        xml.append("/>\n");

        xml.append("  <cfdi:Conceptos>\n");
        for (var line : lines) {
            concept(xml, line);
        }
        xml.append("  </cfdi:Conceptos>\n");

        transferredTaxes(xml, document, lines);
        xml.append("</cfdi:Comprobante>\n");
        return xml.toString();
    }

    // ─── the concept, and the tax node the authority builds from it ────────────────────────────────────

    /**
     * One {@code cfdi:Concepto}, with its {@code Traslado} only when the item is subject to the tax.
     *
     * <p>The three cases {@code L12}(e)–(f) and {@code V110} separate, and they are not interchangeable:</p>
     *
     * <ul>
     *   <li><b>not subject</b> ({@code ObjetoImp = 01}): no {@code cfdi:Impuestos} node at all, because the
     *       authority gives a concept that is subject to nothing no {@code Traslado} and no {@code TipoFactor};</li>
     *   <li><b>exempt</b> ({@code TipoFactor = Exento}): a {@code Traslado} that states its base, its
     *       {@code Impuesto} and its factor, and <b>neither</b> {@code TasaOCuota} nor {@code Importe} — a zero
     *       would be a rate, which is a different statement;</li>
     *   <li><b>rate</b> ({@code TipoFactor = Tasa}): the same node with the rate and the amount.</li>
     * </ul>
     */
    private void concept(StringBuilder xml, FiscalDocumentLine line) {
        xml.append("    <cfdi:Concepto");
        attribute(xml, "ClaveProdServ", line.getClaveProdServ());
        attribute(xml, "Cantidad", conceptAmount(line.getQuantity()));
        attribute(xml, "ClaveUnidad", line.getClaveUnidad());
        attribute(xml, "Descripcion", line.getDescription());
        attribute(xml, "ValorUnitario", conceptAmount(line.getUnitValue()));
        attribute(xml, "Importe", conceptAmount(line.getBase()));
        if (line.getDiscount().signum() != 0) {
            attribute(xml, "Descuento", conceptAmount(line.getDiscount()));
        }
        attribute(xml, "ObjetoImp", line.getTaxObject());

        if (TAX_OBJECT_NOT_SUBJECT.equals(line.getTaxObject())) {
            xml.append("/>\n");
            return;
        }

        var factor = Objects.requireNonNull(
                line.getTipoFactor(),
                "a concept that is subject to the tax states its factor, and V110 couples the two columns");
        xml.append(">\n");
        xml.append("      <cfdi:Impuestos>\n");
        xml.append("        <cfdi:Traslados>\n");
        xml.append("          <cfdi:Traslado");
        attribute(xml, "Base", conceptAmount(line.getBase()));
        attribute(xml, "Impuesto", impuestoOf(line));
        attribute(xml, "TipoFactor", factor);
        if (FACTOR_RATE.equals(factor)) {
            Objects.requireNonNull(
                    line.getTasaOCuota(), "a concept stated as a rate states the rate it carries (L12(f))");
            attribute(xml, "TasaOCuota", rateAmount(line.getTasaOCuota()));
            attribute(xml, "Importe", conceptAmount(line.getTaxAmount()));
        }
        xml.append("/>\n");
        xml.append("        </cfdi:Traslados>\n");
        xml.append("      </cfdi:Impuestos>\n");
        xml.append("    </cfdi:Concepto>\n");
    }

    /**
     * The authority's {@code Impuesto} of one concept, from the mapping the caller resolved.
     *
     * <p>A code with no mapping is refused by name rather than written as a guessed code: that is the whole
     * reason {@code V103} left the two coordinates nullable ({@code S3}, ADR-0007 decision 11), and a stamped
     * document is the one thing this tree can never recompute.</p>
     */
    private String impuestoOf(FiscalDocumentLine line) {
        var impuesto = satImpuestoByTaxCode.get(line.getTaxCode());
        if (!StringUtils.hasText(impuesto)) {
            throw FiscalTaxMappingMissingException.ofMissingColumns(
                    line.getSalesOrderItemId(), line.getTaxCode(), List.of("sat_impuesto"));
        }
        return impuesto;
    }

    // ─── the document's own transferred tax ────────────────────────────────────────────────────────────

    /**
     * The document-level {@code cfdi:Impuestos}, when the document carries transferred tax.
     *
     * <p>A document with no transferred tax states no {@code Impuestos} node: its {@code TotalImpuestosTrasladados}
     * is zero and the node would state nothing. Inside it, the {@code Traslado} summary is one row per
     * {@code (Impuesto, TasaOCuota)} pair, summed from the concepts that state a rate — an exempt or
     * not-subject concept contributes no document-level row, because the summary is what the authority
     * reconciles and an {@code Exento} row there would claim a transferred tax of nothing. The order is
     * first-seen, so the bytes are a function of the line order.</p>
     */
    private void transferredTaxes(StringBuilder xml, FiscalDocument document, List<FiscalDocumentLine> lines) {
        if (document.getTaxTransferred() == null || document.getTaxTransferred().signum() == 0) {
            return;
        }
        Map<RateKey, BigDecimal[]> groups = new LinkedHashMap<>();
        for (var line : lines) {
            if (TAX_OBJECT_NOT_SUBJECT.equals(line.getTaxObject()) || !FACTOR_RATE.equals(line.getTipoFactor())) {
                continue;
            }
            var key = new RateKey(impuestoOf(line), line.getTasaOCuota());
            var sums = groups.computeIfAbsent(key, ignored -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            sums[0] = sums[0].add(line.getBase());
            sums[1] = sums[1].add(line.getTaxAmount());
        }

        xml.append("  <cfdi:Impuestos");
        attribute(xml, "TotalImpuestosTrasladados", documentAmount(document.getTaxTransferred()));
        xml.append(">\n");
        xml.append("    <cfdi:Traslados>\n");
        groups.forEach((key, sums) -> {
            xml.append("      <cfdi:Traslado");
            attribute(xml, "Base", documentAmount(sums[0]));
            attribute(xml, "Impuesto", key.impuesto());
            attribute(xml, "TipoFactor", FACTOR_RATE);
            attribute(xml, "TasaOCuota", rateAmount(key.rate()));
            attribute(xml, "Importe", documentAmount(sums[1]));
            xml.append("/>\n");
        });
        xml.append("    </cfdi:Traslados>\n");
        xml.append("  </cfdi:Impuestos>\n");
    }

    // ─── the two renderers and the one escaper ────────────────────────────────────────────────────────

    /** An amount at the document's level, rounded to the currency's decimals ({@code L12}(d)). */
    private String documentAmount(BigDecimal value) {
        return value.setScale(currencyMinorUnits, RoundingMode.HALF_UP).toPlainString();
    }

    private static String conceptAmount(BigDecimal value) {
        return value.setScale(CONCEPT_DECIMALS, RoundingMode.HALF_UP).toPlainString();
    }

    private static String rateAmount(BigDecimal value) {
        return value.setScale(RATE_DECIMALS, RoundingMode.HALF_UP).toPlainString();
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
     * <p>No whitespace folding, no control-character stripping, no re-encoding: the description a catalog states
     * is what a reprint must show, and any second transformation would be a rule this class invented for a
     * document the authority already defines.</p>
     */
    private static String escape(String value) {
        var escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
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

    /** What a document-level {@code Traslado} row summarizes: the authority's tax and the rate it applies. */
    private record RateKey(String impuesto, BigDecimal rate) {}
}
