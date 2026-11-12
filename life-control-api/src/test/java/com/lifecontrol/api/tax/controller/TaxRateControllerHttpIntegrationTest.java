package com.lifecontrol.api.tax.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import com.lifecontrol.api.tax.service.TaxRateResolution;
import com.lifecontrol.api.tax.service.TaxRateService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * HTTP acceptance for the tax-rate ABM — issue #858, the write half of ADR-0002 decision 6.
 *
 * <p>Before this resource existed no layer of the tree wrote {@code tax_rate}: the catalog was seeded by
 * {@code V82} and the table was born empty per company, so a company could not state a single VAT rate and
 * every taxable sale it attempted was refused. Every case here is one the issue names, and each refusal is
 * paired with an assertion that the database did not move — a 409 asserted alone would also pass on a write
 * that happened and then threw.</p>
 *
 * <p>The suite runs against real PostgreSQL with the Flyway schema applied, the pool connected as the
 * application role, so {@code tax_rate}'s {@code FORCE} row-level security is live: a case that does not
 * state its tenant reads no row, which is exactly the cross-tenant answer S4 requires.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("Tax rate ABM — HTTP acceptance")
class TaxRateControllerHttpIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String BASE_URL = "/api/companies/{companyId}/tax-rates";
    private static final LocalDate JANUARY = LocalDate.of(2026, 1, 1);
    private static final LocalDate JULY = LocalDate.of(2026, 7, 1);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager em;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TaxRateService taxRateService;

    private UUID companyId;
    private UUID otherCompanyId;

    @BeforeEach
    void seed() {
        companyId = insertCompany("TAX-RATE-KEY", "Tax Rate Company", "TRCO010101AA1");
        otherCompanyId = insertCompany("TAX-RATE-OTHER-KEY", "Tax Rate Other", "TRCO010101AA2");
        useTenant(companyId);
        em.clear();
    }

    // ---- S1: the row it writes is the one resolve answers afterwards ----

    @Test
    @DisplayName("a declared IVA rate is the one resolve answers for a document dated on or after it")
    void declaredRateIsTheOneResolveAnswers() throws Exception {
        mockMvc.perform(post(BASE_URL, companyId)
                        .with(taxWriter())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rateBody("IVA", "0.16", JANUARY)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.companyId").value(companyId.toString()))
                .andExpect(jsonPath("$.taxCode").value("IVA"))
                .andExpect(jsonPath("$.rate").value(0.16))
                .andExpect(jsonPath("$.effectiveFrom").value("2026-01-01"))
                .andExpect(jsonPath("$.enabled").value(true));

        em.flush();
        assertThat(taxRateService.resolve(companyId, "IVA", JANUARY))
                .get()
                .extracting(TaxRateResolution::rate)
                .isEqualTo(new BigDecimal("0.160000"));

        assertThat(taxRateService.resolve(companyId, "IVA", JANUARY.minusDays(1)))
                .as("a document dated before effective_from is not covered by the declared rate")
                .isEmpty();
    }

    @Test
    @DisplayName("a later rate supersedes the earlier one without rewriting it, and history keeps resolving")
    void laterRateSupersedesWithoutRewriting() throws Exception {
        var firstId = createRate(companyId, "IVA", "0.16", JANUARY);

        var superseded = mockMvc.perform(put(BASE_URL + "/{id}", companyId, firstId)
                        .with(taxWriter())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(supersedeBody("0.08", JULY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rate").value(0.08))
                .andExpect(jsonPath("$.effectiveFrom").value("2026-07-01"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var secondId = idOf(superseded);
        assertThat(secondId)
                .as("superseding appends a new row instead of editing the old one")
                .isNotEqualTo(firstId);

        em.flush();
        assertThat(taxRateService.resolve(companyId, "IVA", JULY))
                .get()
                .extracting(TaxRateResolution::taxRateId, TaxRateResolution::rate)
                .containsExactly(UUID.fromString(secondId), new BigDecimal("0.080000"));
        assertThat(taxRateService.resolve(companyId, "IVA", JANUARY.plusDays(1)))
                .as("a document dated between the two effective_from values still resolves the first rate")
                .get()
                .extracting(TaxRateResolution::taxRateId, TaxRateResolution::rate)
                .containsExactly(UUID.fromString(firstId), new BigDecimal("0.160000"));

        assertRow(firstId, "0.160000", JANUARY, true);
    }

    // ---- S3: 409 and 400, and neither writes anything ----

    @Test
    @DisplayName("a duplicate (company, tax_code, effective_from) answers 409 and writes nothing")
    void duplicateTripleIsAConflictAndWritesNothing() throws Exception {
        createRate(companyId, "IVA", "0.16", JANUARY);
        var rowsBefore = countRates(companyId);

        mockMvc.perform(post(BASE_URL, companyId)
                        .with(taxWriter())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rateBody("IVA", "0.08", JANUARY)))
                .andExpect(status().isConflict());

        assertThat(countRates(companyId)).isEqualTo(rowsBefore);
        assertThat(ratesOf(companyId)).containsOnly(new BigDecimal("0.160000"));
    }

    @Test
    @DisplayName("a rate outside [0, 1] answers 400 and writes nothing")
    void rateOutsideTheUnitIntervalIsRejected() throws Exception {
        mockMvc.perform(post(BASE_URL, companyId)
                        .with(taxWriter())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rateBody("IVA", "1.16", JANUARY)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(BASE_URL, companyId)
                        .with(taxWriter())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rateBody("IVA", "-0.01", JANUARY)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(BASE_URL, companyId)
                        .with(taxWriter())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rateBody("IVA", "0.16", null)))
                .andExpect(status().isBadRequest());

        assertThat(countRates(companyId)).isZero();
    }

    @Test
    @DisplayName("an explicit enabled null on a create answers 400 naming the field, and writes nothing")
    void statedNullEnabledIsRefusedAndWritesNothing() throws Exception {
        mockMvc.perform(post(BASE_URL, companyId)
                        .with(taxWriter())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rateBody("IVA", "0.16", JANUARY, ", \"enabled\": null")))
                .andExpect(status().isBadRequest())
                // The flag states no value, so the only honest answer is that the caller has to state it: a
                // default of true would start taxing documents on a request that said nothing.
                .andExpect(jsonPath("$.errors.enabled", containsString("enabled is required")));

        assertThat(countRates(companyId))
                .as("a rate the request failed to declare must not be appended")
                .isZero();
    }

    @Test
    @DisplayName("a create that omits enabled answers 400 naming the field, and writes nothing")
    void absentEnabledIsRefusedAndWritesNothing() throws Exception {
        mockMvc.perform(post(BASE_URL, companyId)
                        .with(taxWriter())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rateBody("IVA", "0.16", JANUARY, "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.enabled", containsString("enabled is required")));

        assertThat(countRates(companyId))
                .as("a create whose body missed a required field must not be appended")
                .isZero();
    }

    @Test
    @DisplayName("a code the catalog does not declare answers 404 and writes nothing")
    void unknownTaxCodeIsNotFound() throws Exception {
        mockMvc.perform(post(BASE_URL, companyId)
                        .with(taxWriter())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rateBody("NOT_A_CODE", "0.16", JANUARY)))
                .andExpect(status().isNotFound())
                // The status alone is also what an unmapped path answers, so the body has to be the refusal of
                // this request: a route that does not exist cannot name the code the caller sent.
                .andExpect(jsonPath("$.message", containsString("NOT_A_CODE")));

        assertThat(countRates(companyId)).isZero();
    }

    @Test
    @DisplayName("a supersede that would not move forward answers 400 and writes nothing")
    void supersedeMustMoveForward() throws Exception {
        var id = createRate(companyId, "IVA", "0.16", JULY);

        mockMvc.perform(put(BASE_URL + "/{id}", companyId, id)
                        .with(taxWriter())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(supersedeBody("0.08", JULY)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put(BASE_URL + "/{id}", companyId, id)
                        .with(taxWriter())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(supersedeBody("0.08", JANUARY)))
                .andExpect(status().isBadRequest());

        assertThat(countRates(companyId)).isEqualTo(1);
    }

    @Test
    @DisplayName("a supersede that states enabled with no value answers 400 and writes nothing")
    void supersedeWithStatedNullEnabledIsRefused() throws Exception {
        var id = createRate(companyId, "IVA", "0.16", JANUARY);

        mockMvc.perform(put(BASE_URL + "/{id}", companyId, id)
                        .with(taxWriter())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(supersedeBody("0.08", JULY, ", \"enabled\": null")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.enabled", containsString("enabled is required")));

        assertThat(countRates(companyId))
                .as("a successor the request failed to declare must not be appended")
                .isEqualTo(1);
        assertRow(id, "0.160000", JANUARY, true);
    }

    // ---- S4: the role, and the other company ----

    @Test
    @DisplayName("a caller without lc-tax or lc-admin is refused on every write, and nothing is written")
    void callerWithoutTheRoleIsForbidden() throws Exception {
        var sales = caller(new String[] {"lc-sales"}, "company_id", companyId.toString());

        mockMvc.perform(post(BASE_URL, companyId)
                        .with(sales)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rateBody("IVA", "0.16", JANUARY)))
                .andExpect(status().isForbidden());

        assertThat(countRates(companyId)).as("the refused create wrote nothing").isZero();

        var id = createRate(companyId, "IVA", "0.16", JANUARY);
        mockMvc.perform(put(BASE_URL + "/{id}", companyId, id)
                        .with(sales)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(supersedeBody("0.08", JULY)))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch(BASE_URL + "/{id}/enable", companyId, id)
                        .with(sales)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false}"))
                .andExpect(status().isForbidden());

        assertThat(countRates(companyId))
                .as("neither the refused supersede nor the refused retirement moved the table")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an administrator writes a rate too, and reads stay open to any authenticated caller")
    void administratorCanWriteAndAnyCallerCanRead() throws Exception {
        createRate(companyId, "IVA", "0.16", JANUARY);

        mockMvc.perform(get(BASE_URL, companyId)
                        .with(caller(new String[] {"lc-sales"}, "company_id", companyId.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(post(BASE_URL, companyId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rateBody("TASA_0", "0.00", JANUARY)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("a rate of another company answers 404 for read, supersede and enable, and stays untouched")
    void anotherCompanysRateIsNotFound() throws Exception {
        var otherRateId = createRate(otherCompanyId, "IVA", "0.16", JANUARY);
        useTenant(companyId);

        mockMvc.perform(get(BASE_URL + "/{id}", companyId, otherRateId).with(admin()))
                .andExpect(status().isNotFound())
                // The status alone is what an unmapped path answers too, so each of the three has to be the
                // refusal of this request: only the row lookup can name the id the caller addressed.
                .andExpect(jsonPath("$.message", containsString(otherRateId)));
        mockMvc.perform(put(BASE_URL + "/{id}", companyId, otherRateId)
                        .with(taxWriter())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(supersedeBody("0.08", JULY)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", containsString(otherRateId)));
        mockMvc.perform(patch(BASE_URL + "/{id}/enable", companyId, otherRateId)
                        .with(taxWriter())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", containsString(otherRateId)));

        useTenant(otherCompanyId);
        assertRow(otherRateId, "0.160000", JANUARY, true);
    }

    // ---- reads, and the enabled flag the resolver depends on ----

    @Test
    @DisplayName("the list answers this company's rates, retired ones only on request")
    void listIsPerCompanyAndEnabledAware() throws Exception {
        var ivaId = createRate(companyId, "IVA", "0.16", JANUARY);
        createRate(companyId, "TASA_0", "0.00", JANUARY);
        createRate(otherCompanyId, "IVA", "0.08", JANUARY);
        useTenant(companyId);
        disable(companyId, ivaId);

        mockMvc.perform(get(BASE_URL, companyId).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].taxCode").value("TASA_0"));

        mockMvc.perform(get(BASE_URL, companyId)
                        .param("includeDisabled", "true")
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].taxCode").value("IVA"))
                .andExpect(jsonPath("$[0].effectiveFrom").value("2026-01-01"))
                .andExpect(jsonPath("$[1].taxCode").value("TASA_0"));
    }

    @Test
    @DisplayName("retiring a rate makes the resolver step over it and answer the previous enabled one")
    void disablingARateFallsBackToThePreviousOne() throws Exception {
        createRate(companyId, "IVA", "0.10", JANUARY);
        var laterId = createRate(companyId, "IVA", "0.16", JULY);

        em.flush();
        assertThat(taxRateService.resolve(companyId, "IVA", JULY))
                .get()
                .extracting(TaxRateResolution::rate)
                .isEqualTo(new BigDecimal("0.160000"));

        disable(companyId, laterId);

        em.flush();
        assertThat(taxRateService.resolve(companyId, "IVA", JULY))
                .as("a retired rate keeps its row but stops being answered")
                .get()
                .extracting(TaxRateResolution::rate)
                .isEqualTo(new BigDecimal("0.100000"));
        assertRow(laterId, "0.160000", JULY, false);
    }

    @Test
    @DisplayName("a PATCH that states enabled with no value answers 400, and the row does not move")
    void patchWithStatedNullEnabledIsRefusedAndTheRowDoesNotMove() throws Exception {
        var id = createRate(companyId, "IVA", "0.16", JANUARY);

        mockMvc.perform(patch(BASE_URL + "/{id}/enable", companyId, id)
                        .with(taxWriter())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": null}"))
                .andExpect(status().isBadRequest())
                // A present key with no value used to reach the catch-all as an NPE, i.e. a 500 for a request
                // this endpoint can judge. It is judged: 400, and the refusal names the flag.
                .andExpect(jsonPath("$.message", containsString("enabled")));

        em.flush();
        assertRow(id, "0.160000", JANUARY, true);
    }

    @Test
    @DisplayName("a PATCH that omits the key keeps the one default this endpoint has: the rate is enabled")
    void patchWithoutTheKeyEnablesTheRate() throws Exception {
        var id = createRate(companyId, "IVA", "0.16", JANUARY);
        disable(companyId, id);
        em.flush();
        assertRow(id, "0.160000", JANUARY, false);

        mockMvc.perform(patch(BASE_URL + "/{id}/enable", companyId, id)
                        .with(taxWriter())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        em.flush();
        assertRow(id, "0.160000", JANUARY, true);
    }

    // ---- helpers ----

    private String createRate(UUID company, String taxCode, String rate, LocalDate effectiveFrom) throws Exception {
        useTenant(company);
        var body = mockMvc.perform(post(BASE_URL, company)
                        .with(taxWriter(company))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rateBody(taxCode, rate, effectiveFrom)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return idOf(body);
    }

    private void disable(UUID company, String id) throws Exception {
        useTenant(company);
        mockMvc.perform(patch(BASE_URL + "/{id}/enable", company, id)
                        .with(taxWriter(company))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    /**
     * The create body, with {@code enabled} stated.
     *
     * <p>The flag is required, so a case that left it out would be asserting the validation instead of the
     * behaviour it names; the overload below is how a case writes the field with no value, or omits it on
     * purpose.</p>
     */
    private String rateBody(String taxCode, String rate, LocalDate effectiveFrom) {
        return rateBody(taxCode, rate, effectiveFrom, ", \"enabled\": true");
    }

    /** The create body with {@code enabledFragment} appended verbatim after {@code effectiveFrom}. */
    private String rateBody(String taxCode, String rate, LocalDate effectiveFrom, String enabledFragment) {
        return """
                {"taxCode": "%s", "rate": %s, "effectiveFrom": %s%s}
                """.formatted(
                        taxCode, rate, effectiveFrom == null ? "null" : "\"" + effectiveFrom + "\"", enabledFragment);
    }

    /** The supersede body, with {@code enabled} stated for the same reason {@link #rateBody} states it. */
    private String supersedeBody(String rate, LocalDate effectiveFrom) {
        return supersedeBody(rate, effectiveFrom, ", \"enabled\": true");
    }

    /** The supersede body with {@code enabledFragment} appended verbatim after {@code effectiveFrom}. */
    private String supersedeBody(String rate, LocalDate effectiveFrom, String enabledFragment) {
        return """
                {"rate": %s, "effectiveFrom": "%s"%s}
                """.formatted(rate, effectiveFrom, enabledFragment);
    }

    private static String idOf(String response) {
        return response.replaceAll("(?s).*?\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    }

    private int countRates(UUID company) {
        // The fixture's raw readers flush first: a declare runs inside this suite's transaction and Hibernate
        // has not written it yet, so a JdbcTemplate count issued between two requests would report the state
        // before the write and make "nothing was written" hold for the wrong reason.
        em.flush();
        Integer count =
                jdbc.queryForObject("SELECT count(*) FROM tax_rate WHERE company_id = ?", Integer.class, company);
        return count == null ? 0 : count;
    }

    private List<BigDecimal> ratesOf(UUID company) {
        em.flush();
        return jdbc.queryForList(
                "SELECT rate FROM tax_rate WHERE company_id = ? ORDER BY rate", BigDecimal.class, company);
    }

    private void assertRow(String id, String rate, LocalDate effectiveFrom, boolean enabled) {
        Map<String, Object> row =
                jdbc.queryForMap("SELECT rate, effective_from, enabled FROM tax_rate WHERE id = ?::uuid", id);
        assertThat((BigDecimal) row.get("rate")).isEqualByComparingTo(rate);
        assertThat(row.get("effective_from")).isEqualTo(java.sql.Date.valueOf(effectiveFrom));
        assertThat(row.get("enabled")).isEqualTo(enabled);
    }

    private RequestPostProcessor admin() {
        return caller(new String[] {"lc-admin"}, "company_id", companyId.toString());
    }

    private RequestPostProcessor taxWriter() {
        return taxWriter(companyId);
    }

    private RequestPostProcessor taxWriter(UUID company) {
        return caller(new String[] {"lc-tax"}, "company_id", company.toString());
    }

    /** A caller with the given roles and claim pairs, as a real JWT in the security context. */
    private RequestPostProcessor caller(String[] roles, Object... claimPairs) {
        var token = Jwt.withTokenValue("probe-token").header("alg", "RS256").subject("probe");
        for (var i = 0; i < claimPairs.length; i += 2) {
            token = token.claim((String) claimPairs[i], claimPairs[i + 1]);
        }
        var authorities = Arrays.stream(roles)
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toArray(SimpleGrantedAuthority[]::new);
        return jwt().jwt(token.build()).authorities(authorities);
    }

    private UUID insertCompany(String key, String name, String rfc) {
        var company = Company.builder()
                .id(UUID.randomUUID())
                .companyKey(key)
                .companyName(name)
                .rfc(rfc)
                .currency(TenantFixtures.SEED_CURRENCY)
                .build();
        useTenant(company.getId());
        em.persist(company);
        return company.getId();
    }

    /** States the tenant inside the suite's own transaction, flushing the previous one's pending writes. */
    private void useTenant(UUID companyId) {
        em.flush();
        TenantFixtures.stateTenantWithinTheOpenTransaction(companyId, jdbc);
    }
}
