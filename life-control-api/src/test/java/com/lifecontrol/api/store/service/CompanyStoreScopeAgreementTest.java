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

    private UUID caId, cbId, ccaId, ccbId, raId, rbId, zaId, zbId, saId, sbId;

    @BeforeEach
    void seed() {
        var country = Country.builder().countryCode("ZQ").countryName("Probe").build();
        em.persist(country);
        var ca = Company.builder()
                .companyKey("PB-A")
                .companyName("A")
                .rfc("XAXX010101003")
                .build();
        var cb = Company.builder()
                .companyKey("PB-B")
                .companyName("B")
                .rfc("XAXX010101004")
                .build();
        em.persist(ca);
        em.persist(cb);
        caId = ca.getId();
        cbId = cb.getId();
        var cca = CompanyCountry.builder().company(ca).country(country).build();
        var ccb = CompanyCountry.builder().company(cb).country(country).build();
        em.persist(cca);
        em.persist(ccb);
        ccaId = cca.getId();
        ccbId = ccb.getId();
        var ra = CompanyRegion.builder()
                .companyCountry(cca)
                .regionCode("PRA")
                .regionName("RA")
                .build();
        var rb = CompanyRegion.builder()
                .companyCountry(ccb)
                .regionCode("PRB")
                .regionName("RB")
                .build();
        em.persist(ra);
        em.persist(rb);
        raId = ra.getId();
        rbId = rb.getId();
        var za = CompanyZone.builder()
                .companyRegion(ra)
                .zoneCode("PZA")
                .zoneName("ZA")
                .build();
        var zb = CompanyZone.builder()
                .companyRegion(rb)
                .zoneCode("PZB")
                .zoneName("ZB")
                .build();
        em.persist(za);
        em.persist(zb);
        zaId = za.getId();
        zbId = zb.getId();
        var sa = CompanyStore.builder()
                .companyZone(za)
                .storeName("SA-probe")
                .enabled(true)
                .build();
        var sb = CompanyStore.builder()
                .companyZone(zb)
                .storeName("SB-probe")
                .enabled(true)
                .build();
        em.persist(sa);
        em.persist(sb);
        saId = sa.getId();
        sbId = sb.getId();
        em.flush();
        em.clear();
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
        var ids = scopeIds(List.of("lc-company-read"), "company_id", caId.toString());
        assertThat(ids).contains(saId).doesNotContain(sbId);
        var idsB = scopeIds(List.of("lc-company-read"), "company_id", cbId.toString());
        assertThat(idsB).contains(sbId).doesNotContain(saId);
    }
}
