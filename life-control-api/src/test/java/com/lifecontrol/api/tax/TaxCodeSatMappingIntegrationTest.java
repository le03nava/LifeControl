package com.lifecontrol.api.tax;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * S3 of {@code odd/tasks/cfdi-fiscal-document.md} and L6(d) of the same record:
 * {@code V103__tax_code_sat_mapping.sql} gives the tax vocabulary the authority's two coordinates instead of
 * a second catalog beside it.
 *
 * <p><b>The one fact this slice buys, stated as a test.</b> Today a zero rate, an exempt item and a
 * not-applicable one are three rows that differ only by the string in a code; a document built from them
 * could not say why its tax is zero. After {@code V103} the difference is data: {@code IVA} and
 * {@code TASA_0} share {@code (002, Tasa)} — the rate separates them, not the coordinates —, {@code EXENTO}
 * states {@code (002, Exento)}, and {@code NA} states nothing at all, because "not subject to the tax" is the
 * <b>absence</b> of a tax node and not a tax of zero. The cases below assert exactly that, in both directions:
 * what the four declared codes carry, and that no fifth row was mapped by accident.</p>
 *
 * <p><b>Why the shape is read through {@code GET /api/tax-codes}.</b> The two coordinates are only useful to
 * the document builder if they travel out of the catalog, so the assertion is on the served row and not on
 * the column: a migration that mapped the table and a response that dropped the fields would leave the
 * feature exactly where it started. The columns are asserted separately (nullable and <b>unconstrained</b>),
 * because decision 11 refuses to pin the authority's catalog in this tree and a {@code CHECK} or a
 * {@code NOT NULL} here would be precisely that pin.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("The tax vocabulary's SAT coordinates (S3)")
class TaxCodeSatMappingIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The authority's `Impuesto` code for IVA: the only one any of the four declared codes can state. */
    private static final String IVA_IMPUESTO = "002";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    @DisplayName("the four declared codes carry the coordinates V103 states")
    void theDeclaredCodesCarryTheirCoordinates() throws Exception {
        var catalog = catalogByCode();

        assertThat(catalog.get("IVA"))
                .as("a taxed line states Impuesto 002 and factor Tasa; how much is the rate's, not this row's")
                .isEqualTo(IVA_IMPUESTO + "|Tasa");
        assertThat(catalog.get("TASA_0"))
                .as("Tasa 0% is the same pair as IVA on purpose: the rate is what separates them, so a "
                        + "reader that needed a different coordinate here would be reading the rate twice")
                .isEqualTo(IVA_IMPUESTO + "|Tasa");
        assertThat(catalog.get("EXENTO"))
                .as("Exento is the authority's own factor, and the reason this column exists")
                .isEqualTo(IVA_IMPUESTO + "|Exento");
        assertThat(catalog.get("NA"))
                .as("not subject to the tax is the absence of a tax node, never a tax of zero")
                .isEqualTo("null|null");
    }

    @Test
    @DisplayName("EXENTO is distinguishable from TASA_0 and from NA")
    void exentoIsDistinguishableFromTasaZeroAndNotApplicable() throws Exception {
        var catalog = catalogByCode();

        assertThat(catalog.get("EXENTO"))
                .as("before V103 these two differed only by the string in the code, which is what a document "
                        + "cannot state")
                .isNotEqualTo(catalog.get("TASA_0"));
        assertThat(catalog.get("EXENTO"))
                .as("and an exempt item is not the same fact as a line the tax does not apply to")
                .isNotEqualTo(catalog.get("NA"));
        assertThat(catalog.get("TASA_0"))
                .as("while a zero rate and a not-applicable code stay different rows: one states coordinates, "
                        + "the other states none")
                .isNotEqualTo(catalog.get("NA"));

        assertThat(mappedRowCount())
                .as("exactly three of the four declared codes carry coordinates — IVA, TASA_0 and EXENTO — and "
                        + "no fifth row was mapped by accident: a mapping nobody declared is a tax a document "
                        + "would state without a decision behind it. NA is the fourth and states none, which is "
                        + "also why four would be the wrong number to demand here")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("the two columns are nullable and carry no constraint, because the catalog is the authority's")
    void theColumnsAreNullableAndUnconstrained() {
        var widths = jdbcTemplate.queryForMap("""
                SELECT max(CASE WHEN column_name = 'sat_impuesto' THEN character_maximum_length END) AS impuesto,
                       max(CASE WHEN column_name = 'sat_tipo_factor' THEN character_maximum_length END) AS factor,
                       bool_or(is_nullable = 'YES') AS nullable
                  FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'tax_code'
                   AND column_name IN ('sat_impuesto', 'sat_tipo_factor')
                """);
        assertThat(widths.get("impuesto"))
                .as("VARCHAR(3): the authority's Impuesto code")
                .isEqualTo(3);
        assertThat(widths.get("factor"))
                .as("VARCHAR(10): 'Tasa', 'Cuota', 'Exento' and whatever a reissue adds")
                .isEqualTo(10);
        assertThat(widths.get("nullable"))
                .as("both nullable, so a code with no mapping is visible as null instead of guessing one")
                .isEqualTo(Boolean.TRUE);

        assertThat(constraintsNamingTheCoordinates())
                .as("no CHECK and no NOT NULL pin the coordinate values: decision 11 leaves the catalog to the "
                        + "authority, so a reissue is a migration of data and never a constraint this tree "
                        + "invented")
                .isEmpty();
    }

    // --- reads ---

    /**
     * The served catalog keyed by code, each value flattened to {@code impuesto|factor} with the absent
     * coordinate spelled out — so a missing field and a null one are told apart from the coordinates a code
     * actually carries.
     */
    private Map<String, String> catalogByCode() throws Exception {
        var body = mockMvc.perform(get("/api/tax-codes").with(jwt().jwt(token -> token.subject("probe"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode rows = objectMapper.readTree(body);
        Map<String, String> byCode = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            byCode.put(row.get("code").asText(), text(row, "satImpuesto") + "|" + text(row, "satTipoFactor"));
        }
        return byCode;
    }

    private static String text(JsonNode row, String field) {
        var value = row.get(field);
        return value == null || value.isNull() ? "null" : value.asText();
    }

    private long mappedRowCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tax_code WHERE sat_impuesto IS NOT NULL OR sat_tipo_factor IS NOT NULL",
                Long.class);
    }

    /** Every constraint on {@code tax_code} whose definition mentions either coordinate. */
    private List<String> constraintsNamingTheCoordinates() {
        return jdbcTemplate.queryForList("""
                SELECT conname || ': ' || pg_get_constraintdef(oid)
                  FROM pg_constraint
                 WHERE conrelid = 'tax_code'::regclass
                   AND pg_get_constraintdef(oid) ~ '(sat_impuesto|sat_tipo_factor)'
                """, String.class);
    }
}
