package com.lifecontrol.api.supplier;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import jakarta.persistence.EntityManager;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * HTTP-level acceptance for the supplier catalog being company-scoped — issue #672.
 *
 * <p>Every request carries a real {@code jwt()} whose authorities and claims {@code CurrentUserContext}
 * reads, against real PostgreSQL with the Flyway schema applied. The cases are the ones the issue names,
 * and each one has a positive control beside it, because a 404 asserted alone would also pass on a path
 * that does not exist at all:</p>
 *
 * <ul>
 *   <li>the same RFC is accepted by two different companies and refused twice inside one;
 *   <li>a supplier of one company is not addressable from another company's path for read, update or
 *       delete — and after the refused update and delete the row is asserted to be untouched, so a
 *       partial write cannot hide behind the status code;
 *   <li>{@code /api/suppliers} no longer exists, so the unscoped surface is gone rather than duplicated;
 *   <li>a caller whose {@code company_id} claim names a company it cannot reach is denied before any row
 *       is read.
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("Supplier company scope — HTTP acceptance")
class SupplierCompanyScopeHttpIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String BASE_URL = "/api/companies/{companyId}/suppliers";
    private static final String SHARED_RFC = "SPCS010101AB1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager em;

    private UUID companyId;
    private UUID otherCompanyId;

    @BeforeEach
    void seed() {
        companyId = insertCompany("SUPPLIER-SCOPE-KEY", "Supplier Scope Company", "SPSC010101AA1");
        otherCompanyId = insertCompany("SUPPLIER-SCOPE-OTHER-KEY", "Supplier Scope Other", "SPSC010101AA2");
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("a created supplier belongs to the company the path names")
    void createdSupplierCarriesTheCompanyFromThePath() throws Exception {
        mockMvc.perform(post(BASE_URL, companyId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(supplierBody("Proveedor Uno", SHARED_RFC)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.companyId").value(companyId.toString()))
                .andExpect(jsonPath("$.rfc").value(SHARED_RFC));
    }

    @Test
    @DisplayName("the same RFC is accepted by another company, and refused twice inside one company")
    void rfcIsUniquePerCompanyInsteadOfGlobally() throws Exception {
        createSupplier(companyId, "Proveedor Uno", SHARED_RFC);

        mockMvc.perform(post(BASE_URL, otherCompanyId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(supplierBody("Proveedor Dos", SHARED_RFC)))
                .andExpect(status().isCreated());

        mockMvc.perform(post(BASE_URL, companyId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(supplierBody("Proveedor Tres", SHARED_RFC)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a supplier of one company is not readable from another company's path")
    void foreignSupplierIsNotReadable() throws Exception {
        var supplierId = createSupplier(companyId, "Proveedor Ajeno", SHARED_RFC);

        mockMvc.perform(get(BASE_URL + "/{id}", companyId, supplierId).with(admin()))
                .andExpect(status().isOk());

        mockMvc.perform(get(BASE_URL + "/{id}", otherCompanyId, supplierId).with(admin()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an update through another company's path is refused and stores nothing")
    void foreignSupplierCannotBeUpdated() throws Exception {
        var supplierId = createSupplier(companyId, "Proveedor Intacto", SHARED_RFC);

        mockMvc.perform(put(BASE_URL + "/{id}", otherCompanyId, supplierId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(supplierBody("Nombre Secuestrado", SHARED_RFC)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get(BASE_URL + "/{id}", companyId, supplierId).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supplierName").value("Proveedor Intacto"));
    }

    @Test
    @DisplayName("a delete through another company's path is refused and the supplier stays enabled")
    void foreignSupplierCannotBeDeleted() throws Exception {
        var supplierId = createSupplier(companyId, "Proveedor Vivo", SHARED_RFC);

        mockMvc.perform(delete(BASE_URL + "/{id}", otherCompanyId, supplierId).with(admin()))
                .andExpect(status().isNotFound());

        mockMvc.perform(get(BASE_URL + "/{id}", companyId, supplierId).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    @DisplayName("the unscoped /api/suppliers surface is gone")
    void unscopedPathIsGone() throws Exception {
        mockMvc.perform(get("/api/suppliers").with(admin())).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/suppliers")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(supplierBody("Proveedor Suelto", "SPCS010101AB2")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a caller whose claim names a company it cannot reach is denied before any row is read")
    void callerWithAForeignCompanyClaimIsDenied() throws Exception {
        createSupplier(companyId, "Proveedor Protegido", SHARED_RFC);

        // `lc-admin` is exempt from the company check by design: CurrentUserContext.isAdmin()
        // short-circuits verifyCompanyAccess, which is what lets an admin work across tenants. The
        // case therefore uses a company-scoped role, where the claim is the only thing that can grant
        // access -- and the two answers below stay exactly the two the case always asked for.
        mockMvc.perform(get(BASE_URL, otherCompanyId)
                        .with(caller(new String[] {"lc-company"}, "company_id", otherCompanyId.toString())))
                .andExpect(status().isOk());

        mockMvc.perform(get(BASE_URL, companyId)
                        .with(caller(new String[] {"lc-company"}, "company_id", otherCompanyId.toString())))
                .andExpect(status().isForbidden());
    }

    private UUID createSupplier(UUID companyOfSupplier, String name, String rfc) throws Exception {
        var response = mockMvc.perform(post(BASE_URL, companyOfSupplier)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(supplierBody(name, rfc)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(response.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
    }

    private String supplierBody(String name, String rfc) {
        return """
                {"supplierName": "%s", "rfc": "%s", "email": "proveedor@example.com"}
                """.formatted(name, rfc);
    }

    private RequestPostProcessor admin() {
        return caller(new String[] {"lc-admin"}, "company_id", companyId.toString());
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
        var company = TenantFixtures.persist(
                em, Company.builder().companyKey(key).companyName(name).rfc(rfc).build());
        return company.getId();
    }
}
