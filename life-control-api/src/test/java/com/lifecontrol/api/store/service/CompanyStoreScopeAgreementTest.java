package com.lifecontrol.api.store.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.country.model.Country;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;

/**
 * The list scope and the detail authorization must agree for the same token — record S1, S2, S3.
 *
 * <p>This is the one test that runs the <em>real</em> JPQL against a database: it resolves the store
 * set from a real {@link Jwt} through the real resolver and compares it, shape by shape, with the
 * verdict {@code verifyCompanyStoreAccess} gives for the same token. The mocked variants
 * ({@code GoodsReceiptScopeAgreementTest}, {@code GoodsReceiptServiceReadTest}) can only pin wiring;
 * this one fails if a finder loses an ancestor predicate, which is the defect independent
 * verification found in the first version of the fix.</p>
 */
@SpringBootTest
@Transactional
@DisplayName("Company hierarchy scope agreement: list vs detail")
class CompanyStoreScopeAgreementTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private EntityManager em;

    @Autowired
    private CompanyStoreRepository companyStoreRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID caId, cbId, ccaId, ccbId, raId, rbId, zaId, zbId, saId, sbId;

    @BeforeEach
    void seed() {
        var country = Country.builder().countryCode("ZQ").countryName("Probe").build();
        em.persist(country);
        // Each company is stated as its own tenant while its chain is written: a company's tenant is its own
        // id, so no single stated value admits two `companies` rows, and every child row inherits its
        // parent's tenant (records L24/L26). The transaction is this suite's own — Spring's test transaction
        // began before @BeforeEach ran — so the tenant is stated inside it.
        var ca = persistCompany("PB-A", "A", "XAXX010101003");
        var cb = persistCompany("PB-B", "B", "XAXX010101004");
        caId = ca.getId();
        cbId = cb.getId();

        useTenant(caId);
        var cca = CompanyCountry.builder().company(ca).country(country).build();
        em.persist(cca);
        ccaId = cca.getId();
        var ra = CompanyRegion.builder()
                .companyCountry(cca)
                .regionCode("PRA")
                .regionName("RA")
                .build();
        em.persist(ra);
        raId = ra.getId();
        var za = CompanyZone.builder()
                .companyRegion(ra)
                .zoneCode("PZA")
                .zoneName("ZA")
                .build();
        em.persist(za);
        zaId = za.getId();
        var sa = CompanyStore.builder()
                .companyZone(za)
                .storeName("SA-probe")
                .enabled(true)
                .build();
        em.persist(sa);
        saId = sa.getId();

        // The switch flushes A's chain under A's own tenant before B's is stated.
        useTenant(cbId);
        var ccb = CompanyCountry.builder().company(cb).country(country).build();
        em.persist(ccb);
        ccbId = ccb.getId();
        var rb = CompanyRegion.builder()
                .companyCountry(ccb)
                .regionCode("PRB")
                .regionName("RB")
                .build();
        em.persist(rb);
        rbId = rb.getId();
        var zb = CompanyZone.builder()
                .companyRegion(rb)
                .zoneCode("PZB")
                .zoneName("ZB")
                .build();
        em.persist(zb);
        zbId = zb.getId();
        var sb = CompanyStore.builder()
                .companyZone(zb)
                .storeName("SB-probe")
                .enabled(true)
                .build();
        em.persist(sb);
        sbId = sb.getId();

        // Back to the tenant every case below is about (store SA of company A); the switch flushes B's chain
        // under B's tenant first.
        useTenant(caId);
        em.clear();
    }

    /**
     * Persists one {@code companies} row at an id chosen in memory, stating it as the tenant before the insert.
     */
    private Company persistCompany(String companyKey, String companyName, String rfc) {
        var company = Company.builder()
                .id(UUID.randomUUID())
                .companyKey(companyKey)
                .companyName(companyName)
                .rfc(rfc)
                .build();
        useTenant(company.getId());
        em.persist(company);
        return company;
    }

    /**
     * Switches this suite's transaction to {@code companyId}: the statements the previous tenant left pending
     * run under that tenant first, and the new value is stated after them.
     *
     * <p>The whole hierarchy this suite builds is on armed tables, so the order matters twice: the state has to
     * be stated before the insert that carries it (a company's tenant is its own id, its children's is their
     * parent's), and a stated value covers only the statements that execute while it is stated — Hibernate
     * defers an insert to the next flush, so switching without flushing would run the previous tenant's row
     * under the new tenant's scope. The suite is {@code @Transactional}, so the value lives inside Spring's own
     * test transaction, which began before {@code @BeforeEach} and cannot be retro-fitted (record L24, cause
     * B).</p>
     */
    private void useTenant(UUID companyId) {
        em.flush();
        TenantFixtures.stateTenantWithinTheOpenTransaction(companyId, jdbc);
    }

    private Set<UUID> scopeIds(List<String> roles, Object... claimPairs) {
        var builder = Jwt.withTokenValue("t").header("alg", "RS256").subject("probe");
        Map<String, Object> claims = new LinkedHashMap<>();
        for (var i = 0; i < claimPairs.length; i += 2) {
            claims.put((String) claimPairs[i], claimPairs[i + 1]);
        }
        claims.forEach(builder::claim);
        List<GrantedAuthority> authorities = new ArrayList<>();
        roles.forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(builder.build(), authorities));
        var ctx = new CurrentUserContext();
        var scope = new CompanyStoreScopeResolver(ctx, companyStoreRepository).scopeForCurrentUser();
        return scope.unscoped() ? Set.of(saId, sbId) : scope.storeIds();
    }

    /** The detail's verdict for store SA. */
    private boolean detailAdmitsSa() {
        try {
            new CurrentUserContext().verifyCompanyStoreAccess(caId, ccaId, raId, zaId, saId);
            return true;
        } catch (AccessDeniedException e) {
            return false;
        }
    }

    private void agree(String label, Set<UUID> ids, boolean admits) {
        assertThat(ids.contains(saId))
                .as(label + ": list scope vs detail SA (detailAdmits=" + admits + ")")
                .isEqualTo(admits);
        assertThat(detailAdmitsSa()).as(label + ": detail verdict stability").isEqualTo(admits);
    }

    @Test
    @DisplayName("every token shape: list contains SA iff the detail admits SA")
    void listAndDetailAgreeOnEveryShape() {
        // Company A is the stated tenant: the agreement is about store SA, and every case below compares what
        // A's rows let a token list against what the detail says about that same store. A case whose claim is
        // foreign still asks for A's store, so it is measured against A's visible rows and the ancestor
        // predicates are what exclude them.
        useTenant(caId);
        // 1. country reader, foreign company claim -> must be denied by both
        var ids = scopeIds(
                List.of("lc-company-country-read"),
                "company_id",
                cbId.toString(),
                "company_country_id",
                ccaId.toString());
        agree("country+foreign company", ids, detailAdmitsSa());

        // 2. region reader, no ancestor claims
        ids = scopeIds(List.of("lc-company-region-read"), "company_region_id", raId.toString());
        agree("region no ancestors", ids, detailAdmitsSa());

        // 3. consistent country reader
        ids = scopeIds(
                List.of("lc-company-country-read"),
                "company_id",
                caId.toString(),
                "company_country_id",
                ccaId.toString());
        agree("consistent country", ids, detailAdmitsSa());

        // 4. company reader
        ids = scopeIds(List.of("lc-company-read"), "company_id", caId.toString());
        agree("company", ids, detailAdmitsSa());

        // 5. zone reader
        ids = scopeIds(
                List.of("lc-company-zone-read"),
                "company_id",
                caId.toString(),
                "company_country_id",
                ccaId.toString(),
                "company_region_id",
                raId.toString(),
                "company_zone_id",
                zaId.toString());
        agree("zone", ids, detailAdmitsSa());

        // 6. store reader, full chain
        ids = scopeIds(
                List.of("lc-company-store-read"),
                "company_id",
                caId.toString(),
                "company_country_id",
                ccaId.toString(),
                "company_region_id",
                raId.toString(),
                "company_zone_id",
                zaId.toString(),
                "company_store_id",
                saId.toString());
        agree("store full chain", ids, detailAdmitsSa());

        // 7. store reader missing the zone claim
        ids = scopeIds(
                List.of("lc-company-store-read"),
                "company_id",
                caId.toString(),
                "company_country_id",
                ccaId.toString(),
                "company_region_id",
                raId.toString(),
                "company_store_id",
                saId.toString());
        agree("store no zone claim", ids, detailAdmitsSa());

        // 8. zone reader whose region claim is foreign
        ids = scopeIds(
                List.of("lc-company-zone-read"),
                "company_id",
                caId.toString(),
                "company_country_id",
                ccaId.toString(),
                "company_region_id",
                rbId.toString(),
                "company_zone_id",
                zaId.toString());
        agree("zone+foreign region", ids, detailAdmitsSa());

        // 9. region reader whose country claim is foreign
        ids = scopeIds(
                List.of("lc-company-region-read"),
                "company_id",
                caId.toString(),
                "company_country_id",
                ccbId.toString(),
                "company_region_id",
                raId.toString());
        agree("region+foreign country", ids, detailAdmitsSa());
    }

    @Test
    @DisplayName("no cross-tenant bleed: company A reader never resolves company B's store")
    void noBleed() {
        useTenant(caId);
        var ids = scopeIds(List.of("lc-company-read"), "company_id", caId.toString());
        assertThat(ids).contains(saId).doesNotContain(sbId);

        // The other half reads company B's stores, and one transaction states one tenant: the switch is the
        // second half of the same assertion, not a second test.
        useTenant(cbId);
        var idsB = scopeIds(List.of("lc-company-read"), "company_id", cbId.toString());
        assertThat(idsB).contains(sbId).doesNotContain(saId);
    }
}
