package com.lifecontrol.api.company;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.country.model.Country;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import jakarta.persistence.EntityManager;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * HTTP-level acceptance for the scoped read of {@code GET /api/companies/{id}/countries} — issue
 * #548, specs S1–S3.
 *
 * <p>Every request carries a real {@code jwt()} whose authorities and claims
 * {@code CurrentUserContext} reads from the security context, against real PostgreSQL with the
 * Flyway schema applied. The company owns two countries, so "exactly one country" is a body
 * assertion and not a tautology: a list that fell back to the unscoped query returns both and fails
 * here, which is what makes the empty-claim case a proof of the closed branch instead of a case that
 * merely happens not to hit it.</p>
 *
 * <p>The roles are the measured consumers of a country selector (decision D1), one case per role,
 * and the sibling cases pin the composition the endpoint already had (S3): the country roles keep
 * filtering by their claim, {@code lc-company} / {@code lc-company-read} / {@code lc-admin} keep the
 * whole company, and a foreign {@code company_id} claim is still denied. The last case pins the
 * composite widening declared in {@code D5} of {@code odd/tasks/countries-list-scope-roles.md}: {@code
 * lc-employee} accompanied by a company-level catalog role reads the whole company, and the same
 * caller without it does not.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("Company Country scoped read — HTTP acceptance")
class CompanyCountryScopedReadHttpIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String BASE_URL = "/api/companies/{companyId}/countries";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager em;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID companyId;
    private UUID foreignCompanyId;
    private UUID claimedCountryRelationId;
    private UUID siblingCountryRelationId;

    @BeforeEach
    void seed() {
        var claimedCountry =
                Country.builder().countryCode("QX").countryName("Probe Claimed").build();
        var siblingCountry =
                Country.builder().countryCode("QY").countryName("Probe Sibling").build();
        em.persist(claimedCountry);
        em.persist(siblingCountry);

        var company = persistCompany("COUNTRY-SCOPE-HTTP-KEY", "Country Scope HTTP Company", "CSHT010101AB");
        var foreignCompany =
                persistCompany("COUNTRY-SCOPE-HTTP-FOREIGN-KEY", "Country Scope HTTP Foreign Company", "CSHT020202CD");
        companyId = company.getId();
        foreignCompanyId = foreignCompany.getId();

        useTenant(companyId);
        var claimed = CompanyCountry.builder()
                .company(company)
                .country(claimedCountry)
                .build();
        var sibling = CompanyCountry.builder()
                .company(company)
                .country(siblingCountry)
                .build();
        em.persist(claimed);
        em.persist(sibling);
        claimedCountryRelationId = claimed.getId();
        siblingCountryRelationId = sibling.getId();

        useTenant(foreignCompanyId);
        var foreignCountryRelation = CompanyCountry.builder()
                .company(foreignCompany)
                .country(claimedCountry)
                .build();
        em.persist(foreignCountryRelation);

        // Back to the tenant every case below works on; the switch flushes the foreign row under its own
        // scope first, so nothing is left pending when the persistence context is cleared.
        useTenant(companyId);
        em.clear();
    }

    /**
     * Persists one {@code companies} row in <b>this suite's own</b> transaction, stating that row's id as the
     * tenant before the insert.
     *
     * <p>Why the id is chosen here: a company's tenant is its own id, so the value has to be known and stated
     * before the insert that writes it, and no single stated value can admit two {@code companies} rows.</p>
     *
     * <p>This suite's transaction is the test's own, opened before {@code @BeforeEach} ran, so
     * {@code TenantFixtures.persist} cannot help here: its scope is read by a boundary that has already
     * happened (record L24, cause B), which is why the scope is stated inside the open transaction.</p>
     */
    private Company persistCompany(String companyKey, String companyName, String rfc) {
        var company = Company.builder()
                .id(UUID.randomUUID())
                .companyKey(companyKey)
                .companyName(companyName)
                .rfc(rfc)
                // Required since V57: a company states its operating currency, and this fixture states the
                // one the catalog seeds.
                .currency(TenantFixtures.SEED_CURRENCY)
                .build();
        useTenant(company.getId());
        em.persist(company);
        return company;
    }

    /**
     * Switches the transaction to {@code companyId}: the statements the previous tenant left pending run under
     * that tenant first, and the new value is stated after them.
     *
     * <p>The flush is not optional. A stated tenant applies to the statements that execute while it is stated,
     * and Hibernate defers an insert until the next flush — so switching without flushing would run the
     * previous tenant's insert under the new tenant's scope and meet the {@code WITH CHECK} of its table.</p>
     */
    private void useTenant(UUID companyId) {
        em.flush();
        TenantFixtures.stateTenantWithinTheOpenTransaction(companyId, jdbc);
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

    private RequestPostProcessor scopedToClaimedCountry(String... roles) {
        return caller(
                roles, "company_id", companyId.toString(), "company_country_id", claimedCountryRelationId.toString());
    }

    // ─── S1: every measured consumer reads, bounded by its own claim ─────

    @ParameterizedTest
    @ValueSource(
            strings = {
                "lc-company-region",
                "lc-company-region-read",
                "lc-company-zone",
                "lc-company-zone-read",
                "lc-company-store",
                "lc-company-store-read",
                "lc-receiving",
                "lc-employee"
            })
    @DisplayName("a scoped consumer reads exactly the country named by its claim")
    void scopedConsumerReadsOnlyItsClaimedCountry(String role) throws Exception {
        mockMvc.perform(get(BASE_URL, companyId).with(scopedToClaimedCountry(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(claimedCountryRelationId.toString()))
                .andExpect(jsonPath("$[0].countryCode").value("QX"));
    }

    // ─── S2: an absent claim is an empty list, never the unscoped query ──

    @ParameterizedTest
    @ValueSource(
            strings = {
                "lc-company-region",
                "lc-company-region-read",
                "lc-company-zone",
                "lc-company-zone-read",
                "lc-company-store",
                "lc-company-store-read",
                "lc-receiving",
                "lc-employee"
            })
    @DisplayName("a scoped consumer without a company_country_id claim gets an empty list, not the company")
    void scopedConsumerWithoutClaimGetsEmptyList(String role) throws Exception {
        mockMvc.perform(get(BASE_URL, companyId).with(caller(new String[] {role}, "company_id", companyId.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(hasSize(0)));
    }

    // ─── S3: the composition the endpoint already had ────────────────────

    @Test
    @DisplayName("lc-company-country keeps filtering by its claim")
    void countryRoleKeepsFiltering() throws Exception {
        mockMvc.perform(get(BASE_URL, companyId).with(scopedToClaimedCountry("lc-company-country")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(claimedCountryRelationId.toString()));
    }

    @Test
    @DisplayName("lc-company-country-read keeps filtering by its claim")
    void countryReadRoleKeepsFiltering() throws Exception {
        mockMvc.perform(get(BASE_URL, companyId).with(scopedToClaimedCountry("lc-company-country-read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(claimedCountryRelationId.toString()));
    }

    @Test
    @DisplayName("lc-company-country with both countries in the claim reads both")
    void countryRoleWithBothCountriesInTheClaimReadsBoth() throws Exception {
        var claims = new Object[] {
            "company_id",
            companyId.toString(),
            "company_country_id",
            List.of(claimedCountryRelationId.toString(), siblingCountryRelationId.toString())
        };

        mockMvc.perform(get(BASE_URL, companyId).with(caller(new String[] {"lc-company-country"}, claims)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(hasSize(2)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"lc-company", "lc-company-read", "lc-admin"})
    @DisplayName("a company-level reader keeps every country of the company")
    void companyLevelReaderSeesEveryCountry(String role) throws Exception {
        mockMvc.perform(get(BASE_URL, companyId).with(caller(new String[] {role}, "company_id", companyId.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(hasSize(2)));
    }

    @Test
    @DisplayName("lc-company-read outranks the narrower country read role that accompanies it")
    void companyReadOutranksAccompanyingCountryRead() throws Exception {
        mockMvc.perform(get(BASE_URL, companyId)
                        .with(scopedToClaimedCountry("lc-company-read", "lc-company-country-read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(hasSize(2)));
    }

    @Test
    @DisplayName("a scoped caller with a foreign company_id claim is still denied")
    void foreignCompanyClaimIsDenied() throws Exception {
        mockMvc.perform(get(BASE_URL, companyId)
                        .with(caller(
                                new String[] {"lc-company-region"},
                                "company_id",
                                foreignCompanyId.toString(),
                                "company_country_id",
                                claimedCountryRelationId.toString())))
                .andExpect(status().isForbidden());
    }

    // ─── D5: the composite widening, declared and pinned ──────────────────

    /**
     * The composite shape `#549` exists to declare: `lc-employee` has no level in the hierarchy, so on
     * its own it is bounded by its claim (the parameterized case above pins exactly that), but
     * accompanied by a role registered at {@code ScopeLevel.COMPANY} — {@code lc-department} or
     * {@code lc-position}, both of which are assigned manually, not from a position template — its
     * broadest granted scope <em>is</em> company, and the whole company is what it reads.
     *
     * <p>Both halves run here on purpose: the widening belongs to the catalog role, not to
     * {@code lc-employee}, and a test that asserted only the row count of the composite could not tell
     * the difference between "the catalog role widened it" and "nothing was filtered at all".</p>
     */
    @ParameterizedTest
    @ValueSource(strings = {"lc-department", "lc-position"})
    @DisplayName("lc-employee plus a company-level catalog role reads the whole company, alone it reads its claim")
    void employeeWithCompanyLevelCatalogRoleReadsWholeCompany(String catalogRole) throws Exception {
        mockMvc.perform(get(BASE_URL, companyId).with(scopedToClaimedCountry("lc-employee", catalogRole)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(hasSize(2)));

        mockMvc.perform(get(BASE_URL, companyId).with(scopedToClaimedCountry("lc-employee")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(claimedCountryRelationId.toString()));
    }
}
