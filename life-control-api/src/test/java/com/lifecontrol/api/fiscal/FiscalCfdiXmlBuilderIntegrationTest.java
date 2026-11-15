package com.lifecontrol.api.fiscal;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.accounting.catalog.StarterAccountingCatalogApplier;
import com.lifecontrol.api.fiscal.model.FiscalDocument;
import com.lifecontrol.api.fiscal.model.FiscalDocumentLine;
import com.lifecontrol.api.fiscal.model.IssuerSeal;
import com.lifecontrol.api.fiscal.repository.FiscalDocumentLineRepository;
import com.lifecontrol.api.fiscal.repository.FiscalDocumentRepository;
import com.lifecontrol.api.fiscal.service.FiscalDocumentSnapshotWriter;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import java.io.StringReader;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * The CFDI 4.0 builder: one frozen document rendered as the document the authority stamps, with no XML library,
 * no I/O and no catalog read ({@code S10} of {@code odd/tasks/cfdi-fiscal-document.md}, decisions
 * {@code L12}(b)–({@code f}) of the same record).
 *
 * <p><b>Why every case parses the XML and none of them substring-matches it.</b> The subject is a document the
 * authority validates against an XSD, so what matters is the tree a parser reads — a namespace-qualified
 * element, an attribute with a value, an element that must be <b>absent</b> — and not the order of characters
 * in a string. Asserting on {@code contains} would pass on a document nested under the wrong node, which is
 * exactly the failure a CFDI's structure exists to prevent. The one place a raw string is asserted is the
 * escaper's output, because escaping is by definition a byte-level fact.</p>
 *
 * <p><b>Why the document comes from a real draft and not from hand-set fields.</b> The builder's input is the
 * frozen row set, and a hand-built entity would be a second account of what that row set states. The draft is
 * written by the real {@link FiscalDocumentSnapshotWriter} over the real sale, then read back through the
 * repositories, so every attribute asserted below was produced by the schema's own defaults plus the snapshot —
 * including the four columns the entity cannot set ({@code tipo_comprobante}, {@code metodo_pago},
 * {@code forma_pago}, {@code exportacion}).</p>
 *
 * <p><b>The two catalog facts are stated by this suite and never read by the builder.</b> The currency's scale
 * ({@code MXN}, two decimals) and the authority's {@code Impuesto} ({@code 002} for {@code IVA} and
 * {@code EXENTO}) are the two values the attempt resolves and hands to the builder, so the suite hands them the
 * same way: a builder that read the catalogs itself could not be proven deterministic.</p>
 */
@SpringBootTest
@Transactional
@Import(FiscalStampProviderTestConfiguration.class)
@DisplayName("CFDI 4.0 builder (S10, L12(b)-(f))")
class FiscalCfdiXmlBuilderIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The attempt every case creates its draft under. */
    private static final String ATTEMPT = "cfdi-builder-2026-11-15";

    /** The instant the document states: fixed, so {@code Fecha} is asserted and not merely pattern-matched. */
    private static final LocalDateTime FECHA = LocalDateTime.of(2026, 11, 15, 10, 15, 30);

    /** The CFDI namespace, as the authority publishes it. */
    private static final String CFDI = "http://www.sat.gob.mx/cfd/4";

    /** The published {@code xsi:schemaLocation}, leading space included: a wrong one is a documented rejection. */
    private static final String SCHEMA_LOCATION =
            " http://www.sat.gob.mx/cfd/4 http://www.sat.gob.mx/sitio_internet/cfd/4/cfdv40.xsd";

    /** The seal is {@code T7}'s to produce; this slice only writes it, so the suite passes a fixed one. */
    private static final IssuerSeal SEAL = new IssuerSeal("30001000000400002434", "MIIBc2VhbA==", "c2VsbG8tY2Zk");

    @Autowired
    private FiscalDocumentSnapshotWriter writer;

    @Autowired
    private FiscalDocumentRepository documentRepository;

    @Autowired
    private FiscalDocumentLineRepository lineRepository;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * The persistence context of this test's transaction, used for one thing: reading back the four header
     * columns the schema states and the entity cannot write ({@code tipo_comprobante}, {@code metodo_pago},
     * {@code forma_pago}, {@code exportacion}). Hibernate does not re-read a non-insertable column after an
     * insert, so without the refresh the entity would carry {@code null} in all four and the builder would be
     * tested against a row the database does not have. The attempt does the same thing for the same reason
     * ({@code FiscalDocumentStamper#refreshStatementFacts}).
     */
    @Autowired
    private EntityManager entityManager;

    @Autowired
    private StarterAccountingCatalogApplier accountingCatalog;

    private FiscalDocumentSeed seed;

    @BeforeEach
    void seedACompletedSaleWithAFiscalProfile() {
        seed = new FiscalDocumentSeed(jdbc, accountingCatalog).aCompletedSaleWithAFiscalProfile();
    }

    @AfterEach
    void clearTheIdentity() {
        SecurityContextHolder.clearContext();
    }

    // ──────────────────────────────────────
    // the document's own shape
    // ──────────────────────────────────────

    @Test
    @DisplayName("S10/L12(b): the Comprobante states the fourth revision, the place of issue, the money at the "
            + "currency's decimals and the three attributes the seal carries")
    void theComprobanteStatesTheRevisionThePlaceOfIssueTheMoneyAndTheSeal() {
        var document = parse(renderDraft());

        assertThat(document.getDocumentElement().getNamespaceURI())
                .as("the root is in the authority's namespace, not in no namespace at all")
                .isEqualTo(CFDI);
        assertThat(document.getDocumentElement().getLocalName()).isEqualTo("Comprobante");

        var comprobante = document.getDocumentElement();
        assertThat(attribute(comprobante, "Version")).isEqualTo("4.0");
        assertThat(attribute(comprobante, "xsi:schemaLocation"))
                .as("the published value, with its leading space: a wrong one is a documented PAC rejection")
                .isEqualTo(SCHEMA_LOCATION);
        assertThat(attribute(comprobante, "Fecha"))
                .as("no offset, because the XSD pattern forbids one (L12(c))")
                .isEqualTo("2026-11-15T10:15:30");
        assertThat(attribute(comprobante, "MetodoPago")).isEqualTo("PUE");
        assertThat(attribute(comprobante, "Moneda")).isEqualTo("MXN");
        assertThat(attribute(comprobante, "TipoDeComprobante")).isEqualTo("I");
        assertThat(attribute(comprobante, "Exportacion")).isEqualTo("01");
        assertThat(attribute(comprobante, "LugarExpedicion")).isEqualTo(FiscalDocumentSeed.ISSUER_ZIP);
        assertThat(attribute(comprobante, "SubTotal"))
                .as("the sale's subtotal at the currency's two decimals, never a literal (L12(d))")
                .isEqualTo("351.50");
        assertThat(attribute(comprobante, "Descuento")).isEqualTo("11.50");
        assertThat(attribute(comprobante, "Total")).isEqualTo("394.40");
        assertThat(attribute(comprobante, "NoCertificado")).isEqualTo(SEAL.noCertificado());
        assertThat(attribute(comprobante, "Certificado")).isEqualTo(SEAL.certificado());
        assertThat(attribute(comprobante, "Sello")).isEqualTo(SEAL.sello());

        assertThat(comprobante.hasAttribute("Serie"))
                .as("Serie and Folio are an optional local convention: absent when the row states none")
                .isFalse();
        assertThat(comprobante.hasAttribute("Folio")).isFalse();
        assertThat(comprobante.hasAttribute("FormaPago"))
                .as("FormaPago is conditional in the fourth revision and the row states none yet")
                .isFalse();

        var emisor = first(document, "Emisor");
        assertThat(attribute(emisor, "Rfc")).isEqualTo(issuerRfc());
        assertThat(attribute(emisor, "Nombre")).isEqualTo(FiscalDocumentSeed.ISSUER_NAME);
        assertThat(attribute(emisor, "RegimenFiscal")).isEqualTo(FiscalDocumentSeed.ISSUER_REGIME);

        var receptor = first(document, "Receptor");
        assertThat(attribute(receptor, "Rfc")).isEqualTo(FiscalDocumentSeed.RECEIVER_RFC);
        assertThat(attribute(receptor, "Nombre")).isEqualTo(FiscalDocumentSeed.RECEIVER_NAME);
        assertThat(attribute(receptor, "DomicilioFiscalReceptor")).isEqualTo(FiscalDocumentSeed.RECEIVER_ZIP);
        assertThat(attribute(receptor, "RegimenFiscalReceptor")).isEqualTo(FiscalDocumentSeed.RECEIVER_REGIME);
        assertThat(attribute(receptor, "UsoCFDI")).isEqualTo(FiscalDocumentSeed.RECEIVER_USE);

        assertThat(all(document, "TimbreFiscalDigital"))
                .as("the builder produces what is sent: the timbre is the provider's and arrives in its XML")
                .isEmpty();
        assertThat(all(document, "Complemento")).isEmpty();
    }

    // ──────────────────────────────────────
    // the concepts
    // ──────────────────────────────────────

    @Test
    @DisplayName("L12(e): one Concepto per sale line, stating its two authority keys, its own description, its "
            + "money at six decimals and the object that says whether it carries tax")
    void oneConceptoPerSaleLineStatesItsKeysItsMoneyAndItsTax() {
        var document = parse(renderDraft());

        var conceptos = all(document, "Concepto");
        assertThat(conceptos).as("one concept per enabled sale line").hasSize(2);

        var first = conceptoWithImporte(document, "200.000000");
        assertThat(attribute(first, "ClaveProdServ")).isEqualTo(FiscalDocumentSeed.PRODUCT_KEY);
        assertThat(attribute(first, "ClaveUnidad")).isEqualTo(FiscalDocumentSeed.UNIT_KEY);
        assertThat(attribute(first, "Cantidad")).isEqualTo("2.000000");
        assertThat(attribute(first, "ValorUnitario")).isEqualTo("100.000000");
        assertThat(attribute(first, "Importe")).isEqualTo("200.000000");
        assertThat(attribute(first, "Descuento")).isEqualTo("10.000000");
        assertThat(attribute(first, "Descripcion")).isEqualTo("Fiscal Stamp Product unica");
        assertThat(attribute(first, "ObjetoImp"))
                .as("a taxable concept is subject and breaks its tax out")
                .isEqualTo("02");

        var traslado = descendant(first, "Traslado");
        assertThat(attribute(traslado, "Base")).isEqualTo("200.000000");
        assertThat(attribute(traslado, "Impuesto"))
                .as("the authority's code for IVA, resolved from the tax code the line froze")
                .isEqualTo("002");
        assertThat(attribute(traslado, "TipoFactor")).isEqualTo("Tasa");
        assertThat(attribute(traslado, "TasaOCuota"))
                .as("a rate is stated at six decimals")
                .isEqualTo("0.160000");
        assertThat(attribute(traslado, "Importe")).isEqualTo("30.400000");

        var second = conceptoWithImporte(document, "151.500000");
        assertThat(attribute(second, "Cantidad")).isEqualTo("3.000000");
        assertThat(attribute(second, "ValorUnitario")).isEqualTo("50.500000");
        assertThat(attribute(second, "Descuento")).isEqualTo("1.500000");
        assertThat(attribute(descendant(second, "Traslado"), "Importe")).isEqualTo("24.000000");
    }

    @Test
    @DisplayName("L12(f)/V110: an exempt concept states a factor and no rate, and a not-subject one states the "
            + "object 01 with no tax node at all")
    void anExemptConceptStatesNoRateAndANotSubjectConceptStatesNoTaxNode() {
        jdbc.update(
                "UPDATE sales_order_items SET tax_code = 'EXENTO', tax_rate = 0 WHERE sales_order_id = ?", seed.sale());
        noTransferredTax();
        var exempt = parse(renderDraft(newDraft()));

        var exemptTraslado = descendant(conceptoWithImporte(exempt, "200.000000"), "Traslado");
        assertThat(attribute(exemptTraslado, "TipoFactor")).isEqualTo("Exento");
        assertThat(exemptTraslado.hasAttribute("TasaOCuota"))
                .as("a zero here would be a rate, which is a different statement (L12(f))")
                .isFalse();
        assertThat(exemptTraslado.hasAttribute("Importe")).isFalse();
        assertThat(childElements(exempt.getDocumentElement(), "Impuestos"))
                .as("an exempt document states no document-level transferred tax at all")
                .isEmpty();

        jdbc.update("UPDATE sales_order_items SET tax_code = 'NA', tax_rate = 0 WHERE sales_order_id = ?", seed.sale());
        var notSubject = parse(renderDraft(newDraft()));

        var concept = conceptoWithImporte(notSubject, "200.000000");
        assertThat(attribute(concept, "ObjetoImp"))
                .as("`NA` is not subject to the tax")
                .isEqualTo("01");
        assertThat(childElements(concept, "Impuestos"))
                .as("ObjetoImp 01 carries no Traslado and therefore no tax node, not an `Exento` one (V110)")
                .isEmpty();
    }

    // ──────────────────────────────────────
    // the document's tax summary
    // ──────────────────────────────────────

    @Test
    @DisplayName("L12(f): the document-level summary states the transferred total and one Traslado per tax and "
            + "rate, summed from the concepts that state a rate")
    void theDocumentLevelSummarySumsTheConceptsThatStateARate() {
        var document = parse(renderDraft());

        var impuestos = childElements(document.getDocumentElement(), "Impuestos");
        assertThat(impuestos).hasSize(1);
        assertThat(attribute(impuestos.get(0), "TotalImpuestosTrasladados"))
                .as("the document's own transferred tax at the currency's decimals")
                .isEqualTo("54.40");

        var traslados = childElements(firstChild(impuestos.get(0), "Traslados"), "Traslado");
        assertThat(traslados).hasSize(1);
        var summary = traslados.get(0);
        assertThat(attribute(summary, "Base")).isEqualTo("351.50");
        assertThat(attribute(summary, "Impuesto")).isEqualTo("002");
        assertThat(attribute(summary, "TipoFactor")).isEqualTo("Tasa");
        assertThat(attribute(summary, "TasaOCuota")).isEqualTo("0.160000");
        assertThat(attribute(summary, "Importe")).isEqualTo("54.40");
    }

    // ──────────────────────────────────────
    // determinism and the escaper
    // ──────────────────────────────────────

    @Test
    @DisplayName("L12: the same document, concepts, seal and instant render the same bytes twice")
    void theSameInputTwiceIsByteIdentical() {
        var draft = newDraft();
        var first = renderDraft(draft);
        var second = renderDraft(draft);

        assertThat(second)
                .as("a builder that reordered or re-derived anything would make the sent document and the row set "
                        + "two accounts of one fact")
                .isEqualTo(first);
    }

    @Test
    @DisplayName("L12(b): the escaper writes the five XML entities and transforms nothing else")
    void theEscaperWritesTheFiveEntitiesAndNothingElse() {
        var description = "A & B <c> \"d\" 'e'";
        jdbc.update(
                "UPDATE sales_order_items SET product_name_snapshot = ?, variant_name_snapshot = NULL WHERE id = ?",
                description,
                seed.firstLine());
        var xml = renderDraft(newDraft());

        assertThat(attribute(conceptoWithImporte(parse(xml), "200.000000"), "Descripcion"))
                .as("what a parser reads is the description the catalog states")
                .isEqualTo(description + " unica");
        assertThat(xml)
                .as("and it travels as the five entities, with no other transformation")
                .contains("Descripcion=\"A &amp; B &lt;c&gt; &quot;d&quot; &apos;e&apos; unica\"");
        assertThat(xml).doesNotContain("A & B");
    }

    // ──────────────────────────────────────
    // the fixtures
    // ──────────────────────────────────────

    /**
     * A fresh draft of the seeded sale under a key that case has not used.
     *
     * <p>The persistence context is cleared first because several cases state a fact with a raw
     * {@code UPDATE} — the tax code of a sale line, the sale's own tax — and the writer reads those rows through
     * the repositories: without the clear it would freeze the instance Hibernate cached before the statement,
     * and the case would assert against a value the database no longer holds. The sibling writer suite's
     * {@code forgetCatalogReads()} exists for the same trap.</p>
     */
    private UUID newDraft() {
        entityManager.clear();
        return writer.createDraft(
                        seed.sale(),
                        ATTEMPT + "-" + UUID.randomUUID().toString().substring(0, 8))
                .id();
    }

    /** Renders the draft this case created, reading the frozen row set back through the repositories. */
    private String renderDraft() {
        return renderDraft(newDraft());
    }

    /**
     * The XML of one draft, built from the rows as the database holds them.
     *
     * <p>The two facts the attempt resolves are stated here for the same reason: the currency's two decimals and
     * the authority's code for the two tax codes the sale may carry. Nothing else is supplied, because nothing
     * else exists.</p>
     */
    private String renderDraft(UUID documentId) {
        FiscalDocument document = documentRepository.findById(documentId).orElseThrow();
        documentRepository.flush();
        entityManager.refresh(document);
        List<FiscalDocumentLine> lines =
                new ArrayList<>(lineRepository.findByFiscalDocumentIdOrderByCreatedAtAsc(documentId));
        var builder = new CfdiXmlBuilder(2, Map.of("IVA", "002", "TASA_0", "002", "EXENTO", "002"));
        return builder.build(document, lines, SEAL, FECHA);
    }

    /** Zeroes the sale's tax so an exempt or not-subject draft carries none, which is when the node is absent. */
    private void noTransferredTax() {
        jdbc.update("UPDATE sales_orders SET tax_total = 0, total_amount = 340.0000 WHERE id = ?", seed.sale());
    }

    private String issuerRfc() {
        return jdbc.queryForObject("SELECT rfc FROM companies WHERE id = ?", String.class, seed.company());
    }

    // ──────────────────────────────────────
    // parsing
    // ──────────────────────────────────────

    private static Document parse(String xml) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException | SAXException | java.io.IOException cannotParse) {
            throw new IllegalStateException("the builder's own output has to be well formed XML", cannotParse);
        }
    }

    private static String attribute(org.w3c.dom.Node node, String name) {
        return ((Element) node).getAttribute(name);
    }

    private static List<Element> all(Document document, String localName) {
        return asElements(document.getElementsByTagNameNS(CFDI, localName));
    }

    private static Element first(Document document, String localName) {
        var elements = document.getElementsByTagNameNS(CFDI, localName);
        assertThat(elements.getLength())
                .as("the document states exactly one cfdi:" + localName)
                .isGreaterThanOrEqualTo(1);
        return (Element) elements.item(0);
    }

    private static Element descendant(Element scope, String localName) {
        var elements = scope.getElementsByTagNameNS(CFDI, localName);
        assertThat(elements.getLength()).as("cfdi:" + localName + " is present").isGreaterThanOrEqualTo(1);
        return (Element) elements.item(0);
    }

    private static List<Element> childElements(Element parent, String localName) {
        var children = new ArrayList<Element>();
        for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element
                    && CFDI.equals(element.getNamespaceURI())
                    && localName.equals(element.getLocalName())) {
                children.add(element);
            }
        }
        return children;
    }

    private static Element firstChild(Element parent, String localName) {
        var children = childElements(parent, localName);
        assertThat(children)
                .as("cfdi:" + localName + " is a child of this node")
                .isNotEmpty();
        return children.get(0);
    }

    private static Element conceptoWithImporte(Document document, String importe) {
        for (var concepto : all(document, "Concepto")) {
            if (importe.equals(attribute(concepto, "Importe"))) {
                return concepto;
            }
        }
        throw new AssertionError("no Concepto states Importe " + importe);
    }

    private static List<Element> asElements(NodeList nodes) {
        var elements = new ArrayList<Element>(nodes.getLength());
        for (int index = 0; index < nodes.getLength(); index++) {
            elements.add((Element) nodes.item(index));
        }
        return elements;
    }
}
