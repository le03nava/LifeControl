package com.lifecontrol.api.company;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * HTTP-level acceptance for the currency half of {@code POST /api/companies} — ADR-0002 decision 2 and
 * record T1 of {@code odd/tasks/money-first-class.md}.
 *
 * <p><b>Why the create path and not the migration.</b> {@code V57__company_currency.sql} backfills the
 * rows that already exist; this suite pins the other half of the same decision — a company created from
 * now on <b>states</b> its operating currency, and an absent value is a refusal that names the field
 * instead of a silent default. Four cases, one per verdict: stated, absent, unknown code, disabled code.
 * Each refusal also asserts that nothing was stored, because a 400 on the response and a committed row
 * would be the same defect wearing a green test.</p>
 *
 * <p><b>Why a superuser connection reads the result.</b> {@code companies} is armed by {@code V53} and
 * isolated by its own {@code id}, so an unscoped read through the pool answers zero rows for every role
 * — the isolation working, and useless as an assertion that a refused create stored nothing. The
 * container's own user is a superuser, which bypasses the policies regardless of what they say, so it is
 * the only credential here that can see a row it did not scope first.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Creating a company states its operating currency")
class CompanyCurrencyCreateHttpIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String BASE_URL = "/api/companies";

    /** The role the controller admits for a create. */
    private static final String ADMIN_ROLE = "lc-admin";

    /** A currency this suite disables for its own case; {@code V57} seeds only {@code MXN}. */
    private static final String DISABLED_CODE = "XXX";

    /** An RFC that is valid for the constraint and unique to this suite in the shared container. */
    private static final String RFC = "CCUR010101AB1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** The company keys this suite created, so the shared container does not accumulate tenants. */
    private final List<String> createdCompanyKeys = new ArrayList<>();

    @BeforeEach
    void seedDisabledCurrency() {
        jdbcTemplate.update("DELETE FROM currency WHERE code = ?", DISABLED_CODE);
        jdbcTemplate.update(
                "INSERT INTO currency (code, minor_units, symbol, enabled) VALUES (?, 2, NULL, false)", DISABLED_CODE);
    }

    @AfterEach
    void cleanUp() throws Exception {
        jdbcTemplate.update("DELETE FROM currency WHERE code = ?", DISABLED_CODE);
        for (var companyKey : createdCompanyKeys) {
            var id = idOf(companyKey);
            if (id == null) {
                continue;
            }
            // Leaf-first, and inside the created company's own tenant: `product_categories` and
            // `companies` are armed, so an unscoped DELETE removes zero rows and reports success.
            // `CompanyService.createCompany` seeds the starter product catalog through
            // `CompanyCreatedEvent`, and `product_categories.company_id` is a foreign key.
            TenantFixtures.inTenant(id, () -> {
                jdbcTemplate.update(
                        "DELETE FROM product_categories WHERE parent_category_id IS NOT NULL AND company_id = ?", id);
                jdbcTemplate.update("UPDATE product_categories SET parent_category_id = NULL WHERE company_id = ?", id);
                jdbcTemplate.update("DELETE FROM product_categories WHERE company_id = ?", id);
                jdbcTemplate.update("DELETE FROM companies WHERE id = ?", id);
            });
        }
        createdCompanyKeys.clear();
    }

    // --- the four verdicts ---

    @Test
    @DisplayName("a company created with a code stores it and returns it")
    void aStatedCodeIsStored() throws Exception {
        var companyKey = uniqueKey("CURRENCY-STATED");
        createdCompanyKeys.add(companyKey);

        mockMvc.perform(post(BASE_URL)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(companyKey, "MXN")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency").value("MXN"));

        assertThat(storedCurrency(companyKey))
                .as("the row carries the code the caller stated: the request is the only source of a "
                        + "company's operating currency")
                .isEqualTo("MXN");
    }

    @Test
    @DisplayName("a company created without a code is refused with a 400 that names currency")
    void anAbsentCodeIsRefused() throws Exception {
        var companyKey = uniqueKey("CURRENCY-ABSENT");
        createdCompanyKeys.add(companyKey);

        mockMvc.perform(post(BASE_URL)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(companyKey, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.currency").value("currency is required"));

        assertThat(countByKey(companyKey))
                .as("and nothing was stored: a silent default here is exactly the failure class D-c names")
                .isZero();
    }

    @Test
    @DisplayName("a company naming a code the catalog does not carry is refused")
    void anUnknownCodeIsRefused() throws Exception {
        var companyKey = uniqueKey("CURRENCY-UNKNOWN");
        createdCompanyKeys.add(companyKey);

        mockMvc.perform(post(BASE_URL)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(companyKey, "ZZZ")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("ZZZ")));

        assertThat(countByKey(companyKey))
                .as("and nothing was stored: the code is resolved before the insert, not by the foreign key "
                        + "after it")
                .isZero();
    }

    @Test
    @DisplayName("a company naming a disabled code is refused")
    void aDisabledCodeIsRefused() throws Exception {
        var companyKey = uniqueKey("CURRENCY-DISABLED");
        createdCompanyKeys.add(companyKey);

        mockMvc.perform(post(BASE_URL)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(companyKey, DISABLED_CODE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString(DISABLED_CODE)));

        assertThat(countByKey(companyKey))
                .as("and nothing was stored: the row exists, so the foreign key would have accepted it — the "
                        + "refusal is the catalog's `enabled` flag, read by the service")
                .isZero();
    }

    // --- request and caller ---

    private RequestPostProcessor admin() {
        var token = Jwt.withTokenValue("probe-token")
                .header("alg", "RS256")
                .subject("probe")
                .claim("sub", "probe")
                .build();
        return jwt().jwt(token).authorities(new SimpleGrantedAuthority("ROLE_" + ADMIN_ROLE));
    }

    /**
     * A create body, with {@code currency} omitted when {@code currency} is {@code null} — the case that
     * matters is the field being <b>absent</b>, not the field being null.
     */
    private String body(String companyKey, String currency) {
        var json = "{\"companyKey\":\"" + companyKey + "\","
                + "\"companyName\":\"Currency Probe\","
                + "\"tipoPersonaId\":2,"
                + "\"razonSocial\":\"Currency Probe SA\","
                + "\"rfc\":\"" + RFC + "\","
                + "\"enabled\":true";
        if (currency != null) {
            json += ",\"currency\":\"" + currency + "\"";
        }
        return json + "}";
    }

    private static String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    // --- reads: the superuser, because the policies answer everyone else with no row ---

    private Connection superuser() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private String storedCurrency(String companyKey) throws SQLException {
        try (var connection = superuser();
                var statement = connection.prepareStatement("SELECT currency FROM companies WHERE company_key = ?")) {
            statement.setString(1, companyKey);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
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
