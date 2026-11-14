package com.lifecontrol.api.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.fiscal.model.RegimenFiscal;
import com.lifecontrol.api.fiscal.model.UsoCfdi;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The receiver's fiscal profile — S2 of {@code odd/tasks/cfdi-fiscal-document.md}, decisions L8(a)–L8(f) of the
 * same record, {@code V107__customer_fiscal_profile.sql} and decision 5 of
 * {@code docs/adr/0007-fiscal-document-is-separate.md}.
 *
 * <p><b>What S2 makes true, and therefore what is asserted.</b> A customer carries legal name, regime, fiscal
 * address with a postal code and a default use, and {@code rfc} is validated for shape when present. The
 * profile is <b>required at issue, not at the till</b>: a cash customer states none of it and is created
 * normally, which is why the first case here is a complete round-trip and the second group is the four ways a
 * <b>partial</b> profile is refused. Every refusal is asserted together with "nothing was stored", because a
 * 400 over a committed row is the same defect wearing a green test.</p>
 *
 * <p><b>Why the requests go through the real chain.</b> {@code /api/customers} names no company, so the tenant
 * a write runs under is decided by {@code TenantScopeFilter} from the caller's single {@code company_id}
 * claim. The tokens here carry it and the chain states the scope exactly as it does in delivery, so the cases
 * exercise the write path and not a fixture.</p>
 *
 * <p><b>Why the schema cases are raw SQL.</b> L8(b) and L8(d) put each closed vocabulary in two places — the
 * enum and a named {@code CHECK} — and the only failure worth preventing is the two drifting apart. So each
 * case inserts every code the enum declares through a statement the API never sees, reads the constraint's own
 * definition back, and confirms a code outside both is refused by the database. A hand-written list here would
 * pass while both the enum and the schema were wrong in the same way.</p>
 *
 * <p><b>Why a superuser connection reads the stored values.</b> {@code customers} is armed with
 * {@code ENABLE} + {@code FORCE} since {@code V105}, so an unscoped read through the pool answers zero rows for
 * every role. The container's own user bypasses the policies, which is what makes "nothing was stored"
 * observable at all.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("The receiver's fiscal profile (S2)")
class CustomerFiscalProfileHttpIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String BASE_URL = "/api/customers";

    /** A regime of the SAT catalog: the one every positive case states. */
    private static final String GENERAL_REGIME = "601";

    /** A second catalog code, so an update is told apart from a literal written at creation. */
    private static final String PHYSICAL_ACTIVITIES_REGIME = "612";

    /** A use of the SAT catalog: the default a positive case states. */
    private static final String GASTOS_EN_GENERAL = "G03";

    /** A second use, so an update is told apart from a literal written at creation. */
    private static final String EQUIPO_DE_COMPUTO = "I04";

    /** The fiscal postal code a case states: five digits, and deliberately not {@code customers.zip_code}. */
    private static final String POSTAL_CODE = "06700";

    /** A valid RFC — a persona moral's twelve characters — so the positive cases pin the stored value. */
    private static final String RFC = "CFPF010101AB1";

    /** A code no version of either catalog carries, so the refusal is the vocabulary's and not a shape's. */
    private static final String UNKNOWN_CODE = "999";

    /** The trade name every case writes, so a raw read can name the row it is about. */
    private static final String CUSTOMER_NAME = "Fiscal Receiver Probe";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** The company every case works under: created per case, removed leaf-first below. */
    private UUID company;

    /** The connection whose reads no policy filters. */
    private Connection superuser;

    @BeforeEach
    void seedACompanyToWriteUnder() throws SQLException {
        var suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        company = TenantFixtures.insertCompany(
                jdbcTemplate,
                "CUSTOMER-FISCAL-" + suffix,
                "Fiscal Receiver Co",
                "RFC" + suffix,
                TenantFixtures.SEED_CURRENCY);

        superuser = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        superuser.setAutoCommit(false);
    }

    @AfterEach
    void removeTheFixtures() throws SQLException {
        try {
            if (superuser == null || company == null) {
                return;
            }
            superuser.rollback();
            superuser.setAutoCommit(true);
            try (var statement = superuser.createStatement()) {
                statement.execute("DELETE FROM customers WHERE company_id = '" + company + "'");
                statement.execute("DELETE FROM companies WHERE id = '" + company + "'");
            }
        } finally {
            if (superuser != null) {
                superuser.close();
                superuser = null;
            }
        }
    }

    // ──────────────────────────────────────
    // 1. S2: a stated profile round-trips, and every field is on the response
    // ──────────────────────────────────────

    @Test
    @DisplayName("a customer that states the whole profile reads every field back, on the response and on the row")
    void aFullProfileRoundTrips() throws Exception {
        var countryId = countryIdOfMexico();

        var created = mockMvc.perform(post(BASE_URL)
                        .with(token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fullProfileBody(RFC, countryId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.legalName").value(CUSTOMER_NAME + " SA"))
                .andExpect(jsonPath("$.regimenFiscal").value(GENERAL_REGIME))
                .andExpect(jsonPath("$.fiscalStreet").value("Av. Reforma 1"))
                .andExpect(jsonPath("$.fiscalZipCode").value(POSTAL_CODE))
                .andExpect(jsonPath("$.fiscalState").value("Ciudad de México"))
                .andExpect(jsonPath("$.fiscalCountryId").value(countryId.toString()))
                .andExpect(jsonPath("$.defaultUsoCfdi").value(GASTOS_EN_GENERAL))
                .andExpect(jsonPath("$.rfc").value(RFC))
                .andReturn();

        var id = readId(created.getResponse().getContentAsString());

        mockMvc.perform(get(BASE_URL + "/" + id).with(token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legalName").value(CUSTOMER_NAME + " SA"))
                .andExpect(jsonPath("$.regimenFiscal").value(GENERAL_REGIME))
                .andExpect(jsonPath("$.fiscalStreet").value("Av. Reforma 1"))
                .andExpect(jsonPath("$.fiscalZipCode").value(POSTAL_CODE))
                .andExpect(jsonPath("$.fiscalState").value("Ciudad de México"))
                .andExpect(jsonPath("$.fiscalCountryId").value(countryId.toString()))
                .andExpect(jsonPath("$.defaultUsoCfdi").value(GASTOS_EN_GENERAL))
                .andExpect(jsonPath("$.rfc").value(RFC));

        assertThat(storedProfile())
                .as("the row carries the seven columns, in the order the profile declares them, and the fiscal "
                        + "postal code is its own column and not the mailing one")
                .isEqualTo(CUSTOMER_NAME + " SA|" + GENERAL_REGIME + "|Av. Reforma 1|" + POSTAL_CODE
                        + "|Ciudad de México|" + countryId + "|" + GASTOS_EN_GENERAL);
    }

    @Test
    @DisplayName("a customer that states none of it is a cash customer: created with all seven fields null")
    void aCustomerWithoutAProfileIsCreated() throws Exception {
        mockMvc.perform(post(BASE_URL)
                        .with(token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginOnlyBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.legalName").value(nullValue()))
                .andExpect(jsonPath("$.regimenFiscal").value(nullValue()))
                .andExpect(jsonPath("$.fiscalStreet").value(nullValue()))
                .andExpect(jsonPath("$.fiscalZipCode").value(nullValue()))
                .andExpect(jsonPath("$.fiscalState").value(nullValue()))
                .andExpect(jsonPath("$.fiscalCountryId").value(nullValue()))
                .andExpect(jsonPath("$.defaultUsoCfdi").value(nullValue()));

        assertThat(storedProfile())
                .as("S2 makes the profile required at issue and never at the till, so a cash customer is a "
                        + "complete row with nothing declared")
                .isEqualTo("null|null|null|null|null|null|null");
    }

    // ──────────────────────────────────────
    // 2. S2/L8(e): the required four are all-or-nothing at write time
    // ──────────────────────────────────────

    @Test
    @DisplayName("no legal name while the other three are stated is a 400 that names it, and stores nothing")
    void aMissingLegalNameIsRefusedNamingIt() throws Exception {
        mockMvc.perform(post(BASE_URL)
                        .with(token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, GENERAL_REGIME, POSTAL_CODE, GASTOS_EN_GENERAL)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.legalName").value(containsString("legalName")))
                .andExpect(jsonPath("$.errors.regimenFiscal").doesNotExist())
                .andExpect(jsonPath("$.errors.fiscalZipCode").doesNotExist())
                .andExpect(jsonPath("$.errors.defaultUsoCfdi").doesNotExist());

        assertNothingWasStored();
    }

    @Test
    @DisplayName("no regime while the other three are stated is a 400 that names it, and stores nothing")
    void aMissingRegimeIsRefusedNamingIt() throws Exception {
        mockMvc.perform(post(BASE_URL)
                        .with(token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(CUSTOMER_NAME + " SA", null, POSTAL_CODE, GASTOS_EN_GENERAL)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.regimenFiscal").value(containsString("regimenFiscal")))
                .andExpect(jsonPath("$.errors.legalName").doesNotExist())
                .andExpect(jsonPath("$.errors.fiscalZipCode").doesNotExist())
                .andExpect(jsonPath("$.errors.defaultUsoCfdi").doesNotExist());

        assertNothingWasStored();
    }

    @Test
    @DisplayName("no fiscal postal code while the other three are stated is a 400 that names it, and stores nothing")
    void aMissingPostalCodeIsRefusedNamingIt() throws Exception {
        mockMvc.perform(post(BASE_URL)
                        .with(token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(CUSTOMER_NAME + " SA", GENERAL_REGIME, null, GASTOS_EN_GENERAL)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.fiscalZipCode").value(containsString("fiscalZipCode")))
                .andExpect(jsonPath("$.errors.legalName").doesNotExist())
                .andExpect(jsonPath("$.errors.regimenFiscal").doesNotExist())
                .andExpect(jsonPath("$.errors.defaultUsoCfdi").doesNotExist());

        assertNothingWasStored();
    }

    @Test
    @DisplayName("no default use while the other three are stated is a 400 that names it, and stores nothing")
    void aMissingDefaultUseIsRefusedNamingIt() throws Exception {
        mockMvc.perform(post(BASE_URL)
                        .with(token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(CUSTOMER_NAME + " SA", GENERAL_REGIME, POSTAL_CODE, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.defaultUsoCfdi").value(containsString("defaultUsoCfdi")))
                .andExpect(jsonPath("$.errors.legalName").doesNotExist())
                .andExpect(jsonPath("$.errors.regimenFiscal").doesNotExist())
                .andExpect(jsonPath("$.errors.fiscalZipCode").doesNotExist());

        assertNothingWasStored();
    }

    @Test
    @DisplayName("one of the four stated alone is a 400 that names each of the other three")
    void aPartialProfileNamesEveryMissingFact() throws Exception {
        mockMvc.perform(post(BASE_URL)
                        .with(token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(CUSTOMER_NAME + " SA", null, null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.regimenFiscal").exists())
                .andExpect(jsonPath("$.errors.fiscalZipCode").exists())
                .andExpect(jsonPath("$.errors.defaultUsoCfdi").exists())
                .andExpect(jsonPath("$.errors.legalName").doesNotExist());

        assertNothingWasStored();
    }

    @Test
    @DisplayName("the optional half alone is not a partial profile: street, state and country need no regime")
    void theOptionalHalfAloneIsAccepted() throws Exception {
        var countryId = countryIdOfMexico();

        mockMvc.perform(post(BASE_URL)
                        .with(token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, null, null, null, null, "Av. Reforma 1", "Ciudad de México", countryId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fiscalStreet").value("Av. Reforma 1"))
                .andExpect(jsonPath("$.fiscalState").value("Ciudad de México"))
                .andExpect(jsonPath("$.fiscalCountryId").value(countryId.toString()))
                .andExpect(jsonPath("$.legalName").value(nullValue()))
                .andExpect(jsonPath("$.regimenFiscal").value(nullValue()))
                .andExpect(jsonPath("$.fiscalZipCode").value(nullValue()))
                .andExpect(jsonPath("$.defaultUsoCfdi").value(nullValue()));
    }

    // ──────────────────────────────────────
    // 3. the three shapes, each a 400 that names its field and stores nothing
    // ──────────────────────────────────────

    @Test
    @DisplayName("a four-digit postal code is a 400 that names the field, and stores nothing")
    void aFourDigitPostalCodeIsRefused() throws Exception {
        mockMvc.perform(post(BASE_URL)
                        .with(token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(CUSTOMER_NAME + " SA", GENERAL_REGIME, "0670", GASTOS_EN_GENERAL)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.fiscalZipCode").value(containsString("five digits")));

        assertNothingWasStored();
    }

    @Test
    @DisplayName("an unknown regime code is a 400 that names the field and the catalog, and stores nothing")
    void anUnknownRegimeIsRefused() throws Exception {
        mockMvc.perform(post(BASE_URL)
                        .with(token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(CUSTOMER_NAME + " SA", UNKNOWN_CODE, POSTAL_CODE, GASTOS_EN_GENERAL)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.regimenFiscal").value(containsString(UNKNOWN_CODE)))
                .andExpect(jsonPath("$.errors.regimenFiscal").value(containsString(GENERAL_REGIME)));

        assertNothingWasStored();
    }

    @Test
    @DisplayName("an unknown use code is a 400 that names the field and the catalog, and stores nothing")
    void anUnknownDefaultUseIsRefused() throws Exception {
        mockMvc.perform(post(BASE_URL)
                        .with(token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(CUSTOMER_NAME + " SA", GENERAL_REGIME, POSTAL_CODE, UNKNOWN_CODE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.defaultUsoCfdi").value(containsString(UNKNOWN_CODE)))
                .andExpect(jsonPath("$.errors.defaultUsoCfdi").value(containsString(GASTOS_EN_GENERAL)));

        assertNothingWasStored();
    }

    // ──────────────────────────────────────
    // 4. S2/L8(d): the rfc's shape is the tree's one validator
    // ──────────────────────────────────────

    @Test
    @DisplayName("a malformed rfc is a 400 that names the field, and stores nothing")
    void aMalformedRfcIsRefused() throws Exception {
        mockMvc.perform(post(BASE_URL)
                        .with(token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginOnlyBody().replace("\"enabled\":true", "\"rfc\":\"12\",\"enabled\":true")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.rfc").exists());

        assertNothingWasStored();
    }

    // ──────────────────────────────────────
    // 5. an update cannot silently clear a declared profile
    // ──────────────────────────────────────

    @Test
    @DisplayName("an update that states none of the profile keeps every stored field")
    void anUpdateWithoutTheProfileKeepsIt() throws Exception {
        var countryId = countryIdOfMexico();
        var created = mockMvc.perform(post(BASE_URL)
                        .with(token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fullProfileBody(RFC, countryId)))
                .andExpect(status().isCreated())
                .andReturn();
        var id = readId(created.getResponse().getContentAsString());

        mockMvc.perform(put(BASE_URL + "/" + id)
                        .with(token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginOnlyBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legalName").value(CUSTOMER_NAME + " SA"))
                .andExpect(jsonPath("$.regimenFiscal").value(GENERAL_REGIME))
                .andExpect(jsonPath("$.fiscalZipCode").value(POSTAL_CODE))
                .andExpect(jsonPath("$.defaultUsoCfdi").value(GASTOS_EN_GENERAL));

        assertThat(storedProfile())
                .as("an omitted field is not a request to clear it: a PUT that edits a phone number must not "
                        + "silently make a customer unable to be invoiced")
                .isEqualTo(CUSTOMER_NAME + " SA|" + GENERAL_REGIME + "|Av. Reforma 1|" + POSTAL_CODE
                        + "|Ciudad de México|" + countryId + "|" + GASTOS_EN_GENERAL);
    }

    @Test
    @DisplayName("an update that states the whole profile replaces it")
    void anUpdateThatStatesTheProfileReplacesIt() throws Exception {
        var countryId = countryIdOfMexico();
        var created = mockMvc.perform(post(BASE_URL)
                        .with(token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fullProfileBody(RFC, countryId)))
                .andExpect(status().isCreated())
                .andReturn();
        var id = readId(created.getResponse().getContentAsString());

        mockMvc.perform(put(BASE_URL + "/" + id)
                        .with(token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(
                                CUSTOMER_NAME + " SA DE CV", PHYSICAL_ACTIVITIES_REGIME, "64000", EQUIPO_DE_COMPUTO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legalName").value(CUSTOMER_NAME + " SA DE CV"))
                .andExpect(jsonPath("$.regimenFiscal").value(PHYSICAL_ACTIVITIES_REGIME))
                .andExpect(jsonPath("$.fiscalZipCode").value("64000"))
                .andExpect(jsonPath("$.defaultUsoCfdi").value(EQUIPO_DE_COMPUTO));

        assertThat(storedProfile())
                .as("the stated profile replaced the stored one, code for code")
                .isEqualTo(CUSTOMER_NAME + " SA DE CV|" + PHYSICAL_ACTIVITIES_REGIME + "|Av. Reforma 1|64000"
                        + "|Ciudad de México|" + countryId + "|" + EQUIPO_DE_COMPUTO);
    }

    @Test
    @DisplayName("an update that states only one of the four is refused, and leaves the stored profile alone")
    void aPartialUpdateIsRefusedAndStoresNothing() throws Exception {
        var countryId = countryIdOfMexico();
        var created = mockMvc.perform(post(BASE_URL)
                        .with(token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fullProfileBody(RFC, countryId)))
                .andExpect(status().isCreated())
                .andReturn();
        var id = readId(created.getResponse().getContentAsString());

        mockMvc.perform(put(BASE_URL + "/" + id)
                        .with(token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(CUSTOMER_NAME + " SA", PHYSICAL_ACTIVITIES_REGIME, null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.fiscalZipCode").exists())
                .andExpect(jsonPath("$.errors.defaultUsoCfdi").exists());

        assertThat(storedProfile())
                .as("the refusal happened before the write, so the row still declares the profile it declared")
                .isEqualTo(CUSTOMER_NAME + " SA|" + GENERAL_REGIME + "|Av. Reforma 1|" + POSTAL_CODE
                        + "|Ciudad de México|" + countryId + "|" + GASTOS_EN_GENERAL);
    }

    // ──────────────────────────────────────
    // 6. the CHECKs, pinned by raw SQL: a schema rule only the API enforces is half a rule
    // ──────────────────────────────────────

    @Test
    @DisplayName("ck_customers_fiscal_zip_code refuses a four-digit postal code written directly")
    void thePostalCodeCheckRefusesAWrongShape() {
        assertThatThrownBy(() -> insertRaw("'0670'", "NULL", "NULL"))
                .as("the column carries the same five-digit rule V102 gave the issuer, so a writer that "
                        + "bypasses the API cannot store a postal code no stamp accepts")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_customers_fiscal_zip_code");
    }

    @Test
    @DisplayName("ck_customers_regimen_fiscal admits exactly the enum's codes and refuses one that is not in it")
    void theRegimeCheckAdmitsExactlyTheEnumVocabulary() throws Exception {
        var definition = checkDefinition("ck_customers_regimen_fiscal");

        for (var regime : RegimenFiscal.values()) {
            assertThat(definition)
                    .as("ck_customers_regimen_fiscal states %s, which %s declares", regime.code(), regime)
                    .contains("'" + regime.code() + "'");

            assertThat(insertRaw("NULL", "'" + regime.code() + "'", "NULL"))
                    .as("the database is the authority for every writer that bypasses the API, so the code is "
                            + "proven by inserting it and not by matching text alone")
                    .isEqualTo(1);
        }

        for (var retired : List.of("600", "602", "604", "609", "613")) {
            assertThat(definition)
                    .as(
                            "%s belongs to the RENAPO-era catalog and version 4.0 replaced it, so the receiver may "
                                    + "not declare it either",
                            retired)
                    .doesNotContain("'" + retired + "'");
        }

        assertThatThrownBy(() -> insertRaw("NULL", "'" + UNKNOWN_CODE + "'", "NULL"))
                .as("the constraint, and not only the service, refuses a code outside the vocabulary")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_customers_regimen_fiscal");
    }

    @Test
    @DisplayName("ck_customers_default_uso_cfdi admits exactly the enum's codes and refuses one that is not in it")
    void theUseCheckAdmitsExactlyTheEnumVocabulary() throws Exception {
        var definition = checkDefinition("ck_customers_default_uso_cfdi");

        for (var use : UsoCfdi.values()) {
            assertThat(definition)
                    .as("ck_customers_default_uso_cfdi states %s, which %s declares", use.code(), use)
                    .contains("'" + use.code() + "'");

            assertThat(insertRaw("NULL", "NULL", "'" + use.code() + "'"))
                    .as("the use is proven by inserting it, the same way the regime is")
                    .isEqualTo(1);
        }

        assertThatThrownBy(() -> insertRaw("NULL", "NULL", "'" + UNKNOWN_CODE + "'"))
                .as("a default the stamp cannot use is worse than no default (L8(b)), so the schema refuses "
                        + "one the catalog does not carry")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_customers_default_uso_cfdi");
    }

    @Test
    @DisplayName("the seven columns are nullable and the three narrow ones have the widths V107 declares")
    void theColumnsAreTheShapeTheMigrationDeclares() throws SQLException {
        assertThat(columnShape("legal_name")).isEqualTo("character varying|255|YES");
        assertThat(columnShape("regimen_fiscal"))
                .as("VARCHAR(3) and nullable: absent until the customer declares a profile")
                .isEqualTo("character varying|3|YES");
        assertThat(columnShape("fiscal_street")).isEqualTo("character varying|255|YES");
        assertThat(columnShape("fiscal_zip_code"))
                .as("VARCHAR(5), narrowed from the ADR sketch's ten by L8(c): the fact is five digits")
                .isEqualTo("character varying|5|YES");
        assertThat(columnShape("fiscal_state")).isEqualTo("character varying|120|YES");
        assertThat(columnShape("fiscal_country_id")).isEqualTo("uuid|0|YES");
        assertThat(columnShape("default_uso_cfdi")).isEqualTo("character varying|5|YES");

        assertThat(stringOf("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' "
                        + "AND table_name = 'customers' AND is_nullable = 'NO' AND column_name IN "
                        + "('legal_name', 'regimen_fiscal', 'fiscal_street', 'fiscal_zip_code', "
                        + "'fiscal_state', 'fiscal_country_id', 'default_uso_cfdi')"))
                .as("a required column would refuse every cash customer this table already stores")
                .isEqualTo("0");
    }

    // ──────────────────────────────────────
    // helpers
    // ──────────────────────────────────────

    private RequestPostProcessor token() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_lc-sales"))
                .jwt(builder -> builder.subject("kc-subject-customer-fiscal").claim("company_id", company.toString()));
    }

    /** A JSON body with the four required facts (or the subset the argument states) plus the login fields. */
    private String body(String legalName, String regimenFiscal, String fiscalZipCode, String defaultUsoCfdi) {
        return body(legalName, regimenFiscal, fiscalZipCode, defaultUsoCfdi, null, null, null, null);
    }

    /**
     * The same body with the three optional facts, the country and the rfc, each <b>absent</b> from the JSON when
     * its argument is {@code null} — the difference the update cases turn on is the field being omitted, not the
     * field being null.
     */
    private String body(
            String legalName,
            String regimenFiscal,
            String fiscalZipCode,
            String defaultUsoCfdi,
            String rfc,
            String fiscalStreet,
            String fiscalState,
            UUID fiscalCountryId) {
        var json = new StringBuilder("{\"name\":\"" + CUSTOMER_NAME + "\",\"salesChannel\":\"STORE\",\"enabled\":true");
        if (rfc != null) {
            json.append(",\"rfc\":\"").append(rfc).append('"');
        }
        if (legalName != null) {
            json.append(",\"legalName\":\"").append(legalName).append('"');
        }
        if (regimenFiscal != null) {
            json.append(",\"regimenFiscal\":\"").append(regimenFiscal).append('"');
        }
        if (fiscalStreet != null) {
            json.append(",\"fiscalStreet\":\"").append(fiscalStreet).append('"');
        }
        if (fiscalZipCode != null) {
            json.append(",\"fiscalZipCode\":\"").append(fiscalZipCode).append('"');
        }
        if (fiscalState != null) {
            json.append(",\"fiscalState\":\"").append(fiscalState).append('"');
        }
        if (fiscalCountryId != null) {
            json.append(",\"fiscalCountryId\":\"").append(fiscalCountryId).append('"');
        }
        if (defaultUsoCfdi != null) {
            json.append(",\"defaultUsoCfdi\":\"").append(defaultUsoCfdi).append('"');
        }
        return json.append('}').toString();
    }

    private String fullProfileBody(String rfc, UUID countryId) {
        return body(
                CUSTOMER_NAME + " SA",
                GENERAL_REGIME,
                POSTAL_CODE,
                GASTOS_EN_GENERAL,
                rfc,
                "Av. Reforma 1",
                "Ciudad de México",
                countryId);
    }

    private String loginOnlyBody() {
        return "{\"name\":\"" + CUSTOMER_NAME + "\",\"salesChannel\":\"STORE\",\"enabled\":true}";
    }

    private static UUID readId(String responseBody) {
        var start = responseBody.indexOf("\"id\":\"") + 6;
        return UUID.fromString(responseBody.substring(start, start + 36));
    }

    private UUID countryIdOfMexico() {
        return jdbcTemplate.queryForObject("SELECT id FROM countries WHERE country_code = 'MX'", UUID.class);
    }

    private void assertNothingWasStored() throws SQLException {
        assertThat(countOf("SELECT count(*) FROM customers WHERE company_id = '" + company + "'"))
                .as("a 400 over a committed row is the same defect wearing a green test")
                .isZero();
    }

    /**
     * The one raw insert every schema case uses: a customer of this case's company with the fiscal facts given
     * as SQL literals, run through the same tenant scope production's boundary installs (the table is armed).
     *
     * @return the number of rows the statement wrote, so a case can assert the schema admitted the value
     */
    private int insertRaw(String fiscalZipCode, String regimenFiscal, String defaultUsoCfdi) {
        var id = UUID.randomUUID();
        var sql = "INSERT INTO customers (id, company_id, name, sales_channel, fiscal_zip_code, regimen_fiscal,"
                + " default_uso_cfdi) VALUES (?, ?, 'Raw probe', 'STORE', " + fiscalZipCode + ", " + regimenFiscal
                + ", " + defaultUsoCfdi + ")";
        return TenantFixtures.inTenant(company, () -> {
            return jdbcTemplate.update(sql, id, company);
        });
    }

    private String storedProfile() throws SQLException {
        return stringOf("SELECT coalesce(legal_name, 'null') || '|' || coalesce(regimen_fiscal, 'null') || '|' "
                + "|| coalesce(fiscal_street, 'null') || '|' || coalesce(fiscal_zip_code, 'null') || '|' "
                + "|| coalesce(fiscal_state, 'null') || '|' || coalesce(fiscal_country_id::text, 'null') || '|' "
                + "|| coalesce(default_uso_cfdi, 'null') FROM customers WHERE company_id = '" + company + "'");
    }

    private String checkDefinition(String constraintName) throws SQLException {
        return stringOf("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = '" + constraintName
                + "' AND conrelid = 'customers'::regclass");
    }

    private String columnShape(String column) throws SQLException {
        return stringOf("SELECT data_type || '|' || coalesce(character_maximum_length, 0) || '|' || is_nullable "
                + "FROM information_schema.columns WHERE table_schema = 'public' "
                + "AND table_name = 'customers' AND column_name = '" + column + "'");
    }

    private long countOf(String sql) throws SQLException {
        try (var statement = superuser.createStatement();
                var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private String stringOf(String sql) throws SQLException {
        try (var statement = superuser.createStatement();
                var result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }
}
