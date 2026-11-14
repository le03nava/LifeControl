package com.lifecontrol.api.company;

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

import com.lifecontrol.api.company.model.RegimenFiscal;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The issuer's fiscal identity — S1 of {@code odd/tasks/cfdi-fiscal-document.md}, L6(a) and L6(b) of the same
 * record, and {@code V102__company_fiscal_identity.sql}.
 *
 * <p><b>What S1 makes true, and therefore what is asserted.</b> A company states a fiscal regime from a
 * closed vocabulary and a five-digit place of issue, <b>both nullable</b>: "required to issue and neither
 * required to sell". The two halves of that sentence are the two halves of this suite — a company that
 * declares neither is created and sold normally with both {@code null}, and a company that declares both
 * reads them back. Everything else here is a refusal: an unknown regime code and a malformed postal code are
 * 400s that name their field, and each of them is asserted together with "nothing was stored", because a 400
 * on the response over a committed row is the same defect wearing a green test.</p>
 *
 * <p><b>Why the update cases exist.</b> Both columns are optional, so a request that omits them must not be
 * read as "clear them": a product's update path already distinguishes "absent" from "null" for
 * {@code attributes} and {@code taxCode}, and a company that silently lost its fiscal regime on an unrelated
 * edit of its phone number would be unable to issue until someone noticed. The rule is asserted in both
 * directions — an omitted field keeps what is stored, and a stated one replaces it — because a rule that only
 * pins one direction is satisfied by a service that ignores the field entirely.</p>
 *
 * <p><b>Why the vocabulary is pinned against the constraint and not against a literal list here.</b>
 * {@link RegimenFiscal} and {@code ck_companies_regimen_fiscal} state the same closed list, and the only
 * failure this suite can prevent is the two drifting apart: the case below inserts every code the enum
 * declares, reads the constraint's own definition back, and refuses a code outside both. A hand-written list
 * in this file would pass while both the enum and the schema were wrong in the same way.</p>
 *
 * <p><b>Why a superuser connection reads the stored values.</b> {@code companies} is armed by {@code V53}
 * and isolated by its own {@code id}, so an unscoped read through the pool answers zero rows for every role.
 * The container's own user bypasses the policies, which is what makes "nothing was stored" observable at
 * all.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("The issuer's fiscal identity (S1)")
class CompanyFiscalIdentityHttpIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String BASE_URL = "/api/companies";

    /** The role the controller admits for a create and for an update. */
    private static final String ADMIN_ROLE = "lc-admin";

    /** A regime of the SAT catalog, and the code the positive cases state. */
    private static final String GENERAL_REGIME = "601";

    /** A second catalog code, so an update is told apart from a literal written at creation. */
    private static final String PHYSICAL_ACTIVITIES_REGIME = "612";

    /** The place of issue a case states: five digits, and deliberately not {@code companies.zip_code}. */
    private static final String PLACE_OF_ISSUE = "06700";

    /** An RFC that is valid for the constraint and unique to this suite in the shared container. */
    private static final String RFC = "CFIS010101AB1";

    /** A code no catalog version carries, so the refusal is the vocabulary's and not a formatting one. */
    private static final String UNKNOWN_REGIME = "999";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** The company keys this suite created, so the shared container does not accumulate tenants. */
    private final List<String> createdCompanyKeys = new ArrayList<>();

    @AfterEach
    void cleanUp() throws Exception {
        for (var companyKey : createdCompanyKeys) {
            var id = idOf(companyKey);
            if (id == null) {
                continue;
            }
            // Leaf-first, and inside the created company's own tenant: `product_categories`, `account` and
            // `companies` are armed, so an unscoped DELETE removes zero rows and reports success.
            // `CompanyService.createCompany` seeds the starter product and accounting catalogs through
            // `CompanyCreatedEvent`, and every one of those rows is a foreign key to `companies`.
            TenantFixtures.inTenant(id, () -> {
                jdbcTemplate.update(
                        "DELETE FROM product_categories WHERE parent_category_id IS NOT NULL AND company_id = ?", id);
                jdbcTemplate.update("UPDATE product_categories SET parent_category_id = NULL WHERE company_id = ?", id);
                jdbcTemplate.update("DELETE FROM product_categories WHERE company_id = ?", id);
                jdbcTemplate.update("DELETE FROM posting_rule_line WHERE company_id = ?", id);
                jdbcTemplate.update("DELETE FROM posting_rule WHERE company_id = ?", id);
                jdbcTemplate.update("DELETE FROM account WHERE company_id = ?", id);
                jdbcTemplate.update("DELETE FROM companies WHERE id = ?", id);
            });
        }
        createdCompanyKeys.clear();
    }

    // --- S1: optional to sell, stated to issue ---

    @Test
    @DisplayName("a company that declares no fiscal identity is created and reads back with both fields null")
    void aCompanyWithoutFiscalIdentityIsCreated() throws Exception {
        var companyKey = uniqueKey("FISCAL-NONE");
        createdCompanyKeys.add(companyKey);

        var created = mockMvc.perform(post(BASE_URL)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(companyKey, null, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.regimenFiscal").value(nullValue()))
                .andExpect(jsonPath("$.fiscalZipCode").value(nullValue()))
                .andReturn();

        var id = readId(created.getResponse().getContentAsString());

        mockMvc.perform(get(BASE_URL + "/" + id).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regimenFiscal").value(nullValue()))
                .andExpect(jsonPath("$.fiscalZipCode").value(nullValue()));

        assertThat(stored(companyKey))
                .as("both columns are NULL: S1 makes them required to issue and never required to sell, so a "
                        + "company that has not declared its regime is a complete row")
                .isEqualTo("null|null");
    }

    @Test
    @DisplayName("a company that states its regime and place of issue reads both back")
    void aCompanyWithFiscalIdentityReadsItBack() throws Exception {
        var companyKey = uniqueKey("FISCAL-STATED");
        createdCompanyKeys.add(companyKey);

        mockMvc.perform(post(BASE_URL)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(companyKey, GENERAL_REGIME, PLACE_OF_ISSUE)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.regimenFiscal").value(GENERAL_REGIME))
                .andExpect(jsonPath("$.fiscalZipCode").value(PLACE_OF_ISSUE));

        var id = idOf(companyKey);
        mockMvc.perform(get(BASE_URL + "/" + id).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regimenFiscal").value(GENERAL_REGIME))
                .andExpect(jsonPath("$.fiscalZipCode").value(PLACE_OF_ISSUE));

        assertThat(stored(companyKey))
                .as("the row carries the code and the place of issue, and not the mailing zip_code column")
                .isEqualTo(GENERAL_REGIME + "|" + PLACE_OF_ISSUE);
    }

    @Test
    @DisplayName("a blank regime and a whitespace-only postal code are stored as null, not as empty strings")
    void blankFiscalIdentityIsStoredAsNull() throws Exception {
        var companyKey = uniqueKey("FISCAL-BLANK");
        createdCompanyKeys.add(companyKey);

        mockMvc.perform(post(BASE_URL)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(companyKey, "", "   ")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.regimenFiscal").value(nullValue()))
                .andExpect(jsonPath("$.fiscalZipCode").value(nullValue()));

        assertThat(stored(companyKey))
                .as("an empty string is not a regime and not a postal code: the one absent value is NULL, the "
                        + "shape `ProductCategoryRequest` already gives a blank `satCode`")
                .isEqualTo("null|null");
    }

    // --- the two refusals, each with nothing stored ---

    @Test
    @DisplayName("an unknown regime code is a 400 that names the field, and stores nothing")
    void anUnknownRegimeIsRefusedNamingTheField() throws Exception {
        var companyKey = uniqueKey("FISCAL-UNKNOWN");
        createdCompanyKeys.add(companyKey);

        mockMvc.perform(post(BASE_URL)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(companyKey, UNKNOWN_REGIME, PLACE_OF_ISSUE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.regimenFiscal").value(containsString(UNKNOWN_REGIME)))
                .andExpect(jsonPath("$.errors.regimenFiscal").value(containsString(GENERAL_REGIME)));

        assertThat(countByKey(companyKey))
                .as("and nothing was stored: the vocabulary is resolved before the insert, not by the CHECK after it")
                .isZero();
    }

    @Test
    @DisplayName("a four-digit postal code is a 400 that names the field, and stores nothing")
    void aMalformedPostalCodeIsRefusedNamingTheField() throws Exception {
        var companyKey = uniqueKey("FISCAL-ZIP");
        createdCompanyKeys.add(companyKey);

        mockMvc.perform(post(BASE_URL)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(companyKey, GENERAL_REGIME, "0670")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.fiscalZipCode").value(containsString("five digits")));

        assertThat(countByKey(companyKey))
                .as("and nothing was stored, for the same reason: the shape is refused at the boundary")
                .isZero();
    }

    // --- the update path: absent keeps, stated replaces ---

    @Test
    @DisplayName("an update that states neither field keeps both stored values")
    void anUpdateWithoutTheFieldsKeepsThem() throws Exception {
        var companyKey = uniqueKey("FISCAL-KEEP");
        createdCompanyKeys.add(companyKey);
        createWithFiscalIdentity(companyKey);

        mockMvc.perform(put(BASE_URL + "/" + idOf(companyKey))
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(companyKey, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regimenFiscal").value(GENERAL_REGIME))
                .andExpect(jsonPath("$.fiscalZipCode").value(PLACE_OF_ISSUE));

        assertThat(stored(companyKey))
                .as("an omitted field is not a request to clear it: the row still declares the regime and the "
                        + "place of issue it declared before")
                .isEqualTo(GENERAL_REGIME + "|" + PLACE_OF_ISSUE);
    }

    @Test
    @DisplayName("an update that states the regime replaces it and leaves an unstated postal code alone")
    void anUpdateCopiesTheFieldItStatesAndNoOther() throws Exception {
        var companyKey = uniqueKey("FISCAL-REPLACE");
        createdCompanyKeys.add(companyKey);
        createWithFiscalIdentity(companyKey);

        mockMvc.perform(put(BASE_URL + "/" + idOf(companyKey))
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(companyKey, PHYSICAL_ACTIVITIES_REGIME, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regimenFiscal").value(PHYSICAL_ACTIVITIES_REGIME))
                .andExpect(jsonPath("$.fiscalZipCode").value(PLACE_OF_ISSUE));

        assertThat(stored(companyKey))
                .as("the stated code replaced the stored one, and the postal code the request did not state "
                        + "kept its value: two fields, two decisions")
                .isEqualTo(PHYSICAL_ACTIVITIES_REGIME + "|" + PLACE_OF_ISSUE);
    }

    // --- the vocabulary and its two statements, read back ---

    @Test
    @DisplayName("the CHECK admits exactly the enum's codes, and refuses one that is not in it")
    void theCheckAdmitsExactlyTheEnumVocabulary() throws Exception {
        var definition = regimeConstraintDefinition();

        for (var regime : RegimenFiscal.values()) {
            assertThat(definition)
                    .as("ck_companies_regimen_fiscal states %s, which %s declares", regime.code(), regime)
                    .contains("'" + regime.code() + "'");

            // The database is the authority for every writer that bypasses the API, so the list is read by
            // inserting, not by matching text alone.
            var id = UUID.randomUUID();
            var key = uniqueKey("FISCAL-REGIME-" + regime.code());
            createdCompanyKeys.add(key);
            TenantFixtures.inTenant(
                    id,
                    () -> jdbcTemplate.update(
                            "INSERT INTO companies (id, company_key, company_name, rfc, currency, regimen_fiscal) "
                                    + "VALUES (?, ?, 'Regime probe', ?, 'MXN', ?)",
                            id,
                            key,
                            "R" + regime.code() + "0101AB1",
                            regime.code()));
        }

        for (var retired : List.of("600", "602", "604", "609", "613")) {
            assertThat(definition)
                    .as(
                            "%s belongs to the RENAPO-era catalog and version 4.0 replaced it, so it is not a code "
                                    + "this column may hold",
                            retired)
                    .doesNotContain("'" + retired + "'");
        }

        var refusedId = UUID.randomUUID();
        var refusedKey = uniqueKey("FISCAL-REGIME-UNKNOWN");
        createdCompanyKeys.add(refusedKey);
        assertThatThrownBy(() -> TenantFixtures.inTenant(
                        refusedId,
                        () -> jdbcTemplate.update(
                                "INSERT INTO companies (id, company_key, company_name, rfc, currency, regimen_fiscal) "
                                        + "VALUES (?, ?, 'Regime probe', 'R9990101AB1', 'MXN', ?)",
                                refusedId,
                                refusedKey,
                                UNKNOWN_REGIME)))
                .as("the constraint, not only the service, refuses a code outside the vocabulary")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_companies_regimen_fiscal");
    }

    @Test
    @DisplayName("the two columns are nullable, and the postal code is five characters wide")
    void theColumnsAreTheShapeTheMigrationDeclares() throws Exception {
        assertThat(columnShape("regimen_fiscal"))
                .as("VARCHAR(3) and nullable: the code the SAT catalog states, absent until the issuer declares it")
                .isEqualTo("character varying|3|YES");
        assertThat(columnShape("fiscal_zip_code"))
                .as("VARCHAR(5) and nullable: the place of issue, which is not the twenty-character mailing zip")
                .isEqualTo("character varying|5|YES");
        assertThat(count("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' "
                        + "AND table_name = 'companies' AND column_name IN ('regimen_fiscal', 'fiscal_zip_code')"))
                .as("both columns exist, and neither is a rename of an existing one")
                .isEqualTo(2);
    }

    // --- request and caller ---

    private void createWithFiscalIdentity(String companyKey) throws Exception {
        mockMvc.perform(post(BASE_URL)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(companyKey, GENERAL_REGIME, PLACE_OF_ISSUE)))
                .andExpect(status().isCreated());
    }

    private RequestPostProcessor admin() {
        var token = Jwt.withTokenValue("probe-token")
                .header("alg", "RS256")
                .subject("probe")
                .claim("sub", "probe")
                .build();
        return jwt().jwt(token).authorities(new SimpleGrantedAuthority("ROLE_" + ADMIN_ROLE));
    }

    /**
     * A create body whose two fiscal fields are <b>absent</b> when the argument is {@code null} — the case
     * that matters for the update paths is the field being omitted, not the field being null.
     */
    private String body(String companyKey, String regimenFiscal, String fiscalZipCode) {
        var json = "{\"companyKey\":\"" + companyKey + "\","
                + "\"companyName\":\"Fiscal Probe\","
                + "\"tipoPersonaId\":2,"
                + "\"razonSocial\":\"Fiscal Probe SA\","
                + "\"rfc\":\"" + RFC + "\","
                + "\"enabled\":true,"
                + "\"currency\":\"MXN\"";
        if (regimenFiscal != null) {
            json += ",\"regimenFiscal\":\"" + regimenFiscal + "\"";
        }
        if (fiscalZipCode != null) {
            json += ",\"fiscalZipCode\":\"" + fiscalZipCode + "\"";
        }
        return json + "}";
    }

    private static String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private static UUID readId(String responseBody) {
        var start = responseBody.indexOf("\"id\":\"") + 6;
        return UUID.fromString(responseBody.substring(start, start + 36));
    }

    // --- reads: the superuser, because the policies answer everyone else with no row ---

    private Connection superuser() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private String stored(String companyKey) throws SQLException {
        try (var connection = superuser();
                var statement = connection.prepareStatement(
                        "SELECT coalesce(regimen_fiscal, 'null') || '|' || coalesce(fiscal_zip_code, 'null') "
                                + "FROM companies WHERE company_key = ?")) {
            statement.setString(1, companyKey);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }

    private String columnShape(String column) throws SQLException {
        try (var connection = superuser();
                var statement = connection.prepareStatement(
                        "SELECT data_type || '|' || coalesce(character_maximum_length, 0) || '|' || is_nullable "
                                + "FROM information_schema.columns "
                                + "WHERE table_schema = 'public' AND table_name = 'companies' AND column_name = ?")) {
            statement.setString(1, column);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }

    private String regimeConstraintDefinition() throws SQLException {
        try (var connection = superuser();
                var statement = connection.prepareStatement("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE conname = 'ck_companies_regimen_fiscal' AND conrelid = 'companies'::regclass")) {
            try (var result = statement.executeQuery()) {
                assertThat(result.next())
                        .as("ck_companies_regimen_fiscal is the named constraint V102 declares, and a named one "
                                + "is what makes a violation name the rule it broke")
                        .isTrue();
                return result.getString(1);
            }
        }
    }

    private int count(String sql) throws SQLException {
        try (var connection = superuser();
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private int countByKey(String companyKey) throws SQLException {
        try (var connection = superuser();
                var statement = connection.prepareStatement("SELECT count(*) FROM companies WHERE company_key = ?")) {
            statement.setString(1, companyKey);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private UUID idOf(String companyKey) throws SQLException {
        try (var connection = superuser();
                var statement = connection.prepareStatement("SELECT id FROM companies WHERE company_key = ?")) {
            statement.setString(1, companyKey);
            try (var result = statement.executeQuery()) {
                return result.next() ? result.getObject(1, UUID.class) : null;
            }
        }
    }
}
