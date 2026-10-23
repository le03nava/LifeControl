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
 * whole company, and a foreign {@code company_id} claim is still denied.</p>
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

        var company = Company.builder()
                .companyKey("COUNTRY-SCOPE-HTTP-KEY")
                .companyName("Country Scope HTTP Company")
                .rfc("CSHT010101AB")
                .build();
        var foreignCompany = Company.builder()
                .companyKey("COUNTRY-SCOPE-HTTP-FOREIGN-KEY")
                .companyName("Country Scope HTTP Foreign Company")
                .rfc("CSHT020202CD")
                .build();
        em.persist(company);
        em.persist(foreignCompany);
        companyId = company.getId();
        foreignCompanyId = foreignCompany.getId();

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

        var foreignCountryRelation = CompanyCountry.builder()
                .company(foreignCompany)
                .country(claimedCountry)
                .build();
        em.persist(foreignCountryRelation);

        em.flush();
        em.clear();
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
}
