package com.lifecontrol.api.fiscal.seal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The SAT's official transform, committed and pinned: the stylesheet is the authority's, its includes are
 * committed beside it, and one fixed document is pinned to the {@code cadena original} the transform answers
 * ({@code T7b}, decision {@code L18}(d) of {@code odd/tasks/cfdi-fiscal-document.md}).
 *
 * <p><b>What the golden string is, and what it is not.</b> The expected {@code cadena original} below is a
 * <b>change detector against the committed stylesheet</b>, not a proof of correctness: it was produced by this
 * tree's transform of a fixed document, so it says "the same input still answers the same string" and would
 * fail if the resource, the includes or the XSLT engine moved the output by one character. It cannot say the
 * string is the one the SAT computes — only a stamp accepted by a PAC can say that — and the case that pins the
 * shape ({@code ||4.0|}, pipe-separated fields, one line) exists so a wrong-looking string is visible even when
 * the golden one is updated in the same commit.</p>
 *
 * <p><b>Why the include inventory is a case and not a comment.</b> The stylesheet statically includes 33
 * stylesheets by absolute URL under {@code www.sat.gob.mx}. An {@code xsl:include} is resolved at compile time,
 * so Saxon cannot compile the transform without every one of them: a resolver that stubbed, skipped or fetched
 * one would change the transform this file exists to keep exact, and fetching one would make a stamp depend on
 * the network in a way that fails in an environment without it. The committed set is what makes the pipeline
 * offline, and the case below fails the moment the authority's stylesheet names one this tree does not carry.</p>
 */
@DisplayName("The SAT's cadena original transform (T7b, L18(d))")
class FiscalCadenaOriginalTest {

    /**
     * The documented SHA-256 of the committed stylesheet, as published in the README beside it.
     *
     * <p>Stated here as a literal on purpose: the case compares the file to this constant <b>and</b> the
     * production constant to it, so a commit that edits both the resource and the code still fails here.</p>
     */
    private static final String DOCUMENTED_SHA_256 = "b0559b380e73b850ca8a3da53b077a6051a09d87068d8d98e82dfd4acfba7565";

    /**
     * The {@code cadena original} the committed stylesheet answers for {@link #UNSIGNED}.
     *
     * <p>Produced once by this tree's own transform, and kept as a literal from then on. See the class javadoc
     * for what it does and does not prove. Two of its fields are worth reading: the third concept's description
     * travels as {@code A & B <c>} and not as XML entities, because the authority's stylesheet declares a
     * {@code text} output — the chain is a string, and escaping it twice would sign something else; and the
     * exempt concept states its factor and neither a rate nor an amount ({@code …|002|Exento|}), which is the
     * branch {@code L12}(f) separates from a rate of zero.</p>
     */
    private static final String GOLDEN_CADENA_ORIGINAL =
            "||4.0|2026-11-15T10:15:30|30001000000400002434|351.50|11.50|MXN|394.40|I|01|PUE|01000|AAA010101AAA|LifeControl Fixture|601|BBB010101BBB|Receptor Fixture|64000|612|G03|01010101|2.000000|H87|Concepto gravado|100.000000|200.000000|10.000000|02|200.000000|002|Tasa|0.160000|30.400000|01010101|3.000000|H87|Concepto exento|50.500000|151.500000|1.500000|02|151.500000|002|Exento|01010101|1.000000|H87|A & B <c>|0.000000|0.000000|01|200.000000|002|Tasa|0.160000|30.400000|30.40||";

    /**
     * One fixed seal-less CFDI 4.0 — the shape the builder renders, minus its {@code Sello}.
     *
     * <p>It is a literal and not a row set on purpose: the subject here is the transform, whose input is a
     * string, so the vector has to be the string itself. Two concepts state the two tax branches the builder can
     * produce (a rate with its amount, an exempt one with neither), which makes the golden string a detector of
     * both.</p>
     */
    static final String UNSIGNED = """
            <?xml version="1.0" encoding="UTF-8"?>
            <cfdi:Comprobante xmlns:cfdi="http://www.sat.gob.mx/cfd/4" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation=" http://www.sat.gob.mx/cfd/4 http://www.sat.gob.mx/sitio_internet/cfd/4/cfdv40.xsd" Version="4.0" Fecha="2026-11-15T10:15:30" MetodoPago="PUE" Moneda="MXN" TipoDeComprobante="I" Exportacion="01" LugarExpedicion="01000" SubTotal="351.50" Descuento="11.50" Total="394.40" NoCertificado="30001000000400002434" Certificado="MIIBc2VhbA==">
              <cfdi:Emisor Rfc="AAA010101AAA" Nombre="LifeControl Fixture" RegimenFiscal="601"/>
              <cfdi:Receptor Rfc="BBB010101BBB" Nombre="Receptor Fixture" DomicilioFiscalReceptor="64000" RegimenFiscalReceptor="612" UsoCFDI="G03"/>
              <cfdi:Conceptos>
                <cfdi:Concepto ClaveProdServ="01010101" Cantidad="2.000000" ClaveUnidad="H87" Descripcion="Concepto gravado" ValorUnitario="100.000000" Importe="200.000000" Descuento="10.000000" ObjetoImp="02">
                  <cfdi:Impuestos>
                    <cfdi:Traslados>
                      <cfdi:Traslado Base="200.000000" Impuesto="002" TipoFactor="Tasa" TasaOCuota="0.160000" Importe="30.400000"/>
                    </cfdi:Traslados>
                  </cfdi:Impuestos>
                </cfdi:Concepto>
                <cfdi:Concepto ClaveProdServ="01010101" Cantidad="3.000000" ClaveUnidad="H87" Descripcion="Concepto exento" ValorUnitario="50.500000" Importe="151.500000" Descuento="1.500000" ObjetoImp="02">
                  <cfdi:Impuestos>
                    <cfdi:Traslados>
                      <cfdi:Traslado Base="151.500000" Impuesto="002" TipoFactor="Exento"/>
                    </cfdi:Traslados>
                  </cfdi:Impuestos>
                </cfdi:Concepto>
                <cfdi:Concepto ClaveProdServ="01010101" Cantidad="1.000000" ClaveUnidad="H87" Descripcion="A &amp; B &lt;c&gt;" ValorUnitario="0.000000" Importe="0.000000" ObjetoImp="01"/>
              </cfdi:Conceptos>
              <cfdi:Impuestos TotalImpuestosTrasladados="30.40">
                <cfdi:Traslados>
                  <cfdi:Traslado Base="200.000000" Impuesto="002" TipoFactor="Tasa" TasaOCuota="0.160000" Importe="30.400000"/>
                </cfdi:Traslados>
              </cfdi:Impuestos>
            </cfdi:Comprobante>

            """;

    @Test
    @DisplayName("the committed stylesheet is the SAT's, byte for byte: its SHA-256 is the documented one")
    void theCommittedStylesheetIsTheDocumentedOne() throws IOException, NoSuchAlgorithmException {
        assertThat(CadenaOriginal.SHA_256)
                .as("the constant the sealer checks its resource against is the documented hash")
                .isEqualTo(DOCUMENTED_SHA_256);
        assertThat(sha256OfCommittedStylesheet())
                .as("a substituted stylesheet compiles, transforms and signs a document the authority refuses")
                .isEqualTo(DOCUMENTED_SHA_256);
    }

    @Test
    @DisplayName("one fixed document transforms into the golden cadena original, whole")
    void oneFixedDocumentStatesTheGoldenCadenaOriginal() {
        var cadena = new CadenaOriginal().of(UNSIGNED);

        assertThat(cadena)
                .as("a change detector against the committed stylesheet: the same document still answers the "
                        + "same string, and any edit to the resource or the includes shows up here")
                .isEqualTo(GOLDEN_CADENA_ORIGINAL);
    }

    @Test
    @DisplayName("the cadena states the documented shape: ||4.0|, pipe-separated fields, one line, closed by ||")
    void theCadenaStatesTheDocumentedShape() {
        var cadena = new CadenaOriginal().of(UNSIGNED);

        assertThat(cadena).startsWith("||4.0|");
        assertThat(cadena).endsWith("||");
        assertThat(cadena)
                .as("the authority's chain is one line: a newline in it is a space the signature would cover")
                .doesNotContain("\n")
                .doesNotContain("\r");
        var fields = cadena.split("\\|", -1);
        assertThat(fields)
                .as("its fields are pipe-separated, so the delimiters are the count plus one")
                .hasSizeGreaterThan(20);
        assertThat(fields).contains("MXN", "PUE", "601", "G03", "0.160000", "30.400000");
    }

    @Test
    @DisplayName("every include the stylesheet names is committed, so the transform compiles with no network")
    void everyIncludeTheStylesheetNamesIsCommitted() throws IOException {
        List<String> hrefs = includeHrefsOfTheCommittedStylesheet();
        assertThat(hrefs)
                .as("the stylesheet statically includes its complemento modules; an xsl:include is resolved at "
                        + "compile time, so a missing one is not a warning but a transform that never runs")
                .isNotEmpty();

        for (var href : hrefs) {
            try (InputStream include = CadenaOriginal.include(href)) {
                assertThat(include).as("the committed set carries %s", href).isNotNull();
            }
        }
    }

    @Test
    @DisplayName("an include the committed set does not carry is refused by name, never fetched")
    void anIncludeTheCommittedSetDoesNotCarryIsRefused() {
        assertThatThrownBy(() ->
                        CadenaOriginal.include("http://www.sat.gob.mx/sitio_internet/cfd/4/no-committed-module.xslt"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no-committed-module.xslt");
    }

    // ──────────────────────────────────────
    // the fixtures
    // ──────────────────────────────────────

    private static String sha256OfCommittedStylesheet() throws IOException, NoSuchAlgorithmException {
        try (InputStream stylesheet =
                FiscalCadenaOriginalTest.class.getClassLoader().getResourceAsStream(CadenaOriginal.RESOURCE)) {
            assertThat(stylesheet).as("the stylesheet is on the classpath").isNotNull();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stylesheet.readAllBytes()));
        }
    }

    /** The {@code href} of every {@code xsl:include} the committed stylesheet declares, in document order. */
    private static List<String> includeHrefsOfTheCommittedStylesheet() throws IOException {
        String stylesheet;
        try (InputStream stream =
                FiscalCadenaOriginalTest.class.getClassLoader().getResourceAsStream(CadenaOriginal.RESOURCE)) {
            assertThat(stream).as("the stylesheet is on the classpath").isNotNull();
            stylesheet = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var hrefs = new ArrayList<String>();
        var matcher = Pattern.compile("<xsl:include[^>]*href=\"([^\"]+)\"").matcher(stylesheet);
        while (matcher.find()) {
            hrefs.add(matcher.group(1));
        }
        return hrefs;
    }
}
