package com.lifecontrol.api.company.service;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.company.repository.CompanyCountryRepository;
import com.lifecontrol.api.company.repository.CompanyRegionRepository;
import com.lifecontrol.api.company.repository.CompanyZoneRepository;
import com.lifecontrol.api.country.model.Country;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Pins the decision that the company-scoped company-zone reads are deliberately uncached.
 *
 * <p>The sibling of {@link CompanyRegionServiceScopedReadCacheTest}, and the same defect: the calls
 * travel through the Spring proxy so the annotations are observable at all, and each of two identical
 * calls must reach {@link CurrentUserContext#verifyCompanyZoneAccess}. On a cache <b>hit</b> the scope
 * check never runs, and the stored value carried the caller's own result — the claim-filtered list for
 * a caller holding {@code lc-company-zone}, the whole region for one holding {@code lc-company-region}
 * or broader — under one key shared by both.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("CompanyZoneService scoped reads are deliberately uncached")
class CompanyZoneServiceScopedReadCacheTest {

    @Autowired
    private CompanyZoneService companyZoneService;

    @MockitoBean
    private CompanyZoneRepository companyZoneRepository;

    @MockitoBean
    private CompanyRegionRepository companyRegionRepository;

    @MockitoBean
    private CompanyCountryRepository companyCountryRepository;

    @MockitoBean
    private CurrentUserContext currentUserContext;

    @Test
    @DisplayName("two identical getAllZones calls each reach verifyCompanyZoneAccess")
    void getAllZones_TwoIdenticalCalls_ReachVerifyCompanyZoneAccessTwice() {
        var companyId = UUID.randomUUID();
        var companyCountryId = UUID.randomUUID();
        var region = region(companyCountry(companyCountryId));
        var zone = zone(region);

        when(companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId))
                .thenReturn(Optional.of(region.getCompanyCountry()));
        when(companyRegionRepository.findByIdAndCompanyCountryId(region.getId(), companyCountryId))
                .thenReturn(Optional.of(region));
        when(companyZoneRepository.findByCompanyRegionIdOrderByZoneNameAsc(region.getId()))
                .thenReturn(List.of(zone));

        companyZoneService.getAllZones(companyId, companyCountryId, region.getId(), false);
        companyZoneService.getAllZones(companyId, companyCountryId, region.getId(), false);

        // A @Cacheable on getAllZones would serve the second call from the cache and skip the scope
        // check, turning this into "Wanted 2 times but was 1".
        verify(currentUserContext, times(2)).verifyCompanyZoneAccess(companyId, companyCountryId, region.getId(), null);
    }

    @Test
    @DisplayName("two identical getZoneById calls each reach verifyCompanyZoneAccess")
    void getZoneById_TwoIdenticalCalls_ReachVerifyCompanyZoneAccessTwice() {
        var companyId = UUID.randomUUID();
        var companyCountryId = UUID.randomUUID();
        var region = region(companyCountry(companyCountryId));
        var zone = zone(region);

        when(companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId))
                .thenReturn(Optional.of(region.getCompanyCountry()));
        when(companyRegionRepository.findByIdAndCompanyCountryId(region.getId(), companyCountryId))
                .thenReturn(Optional.of(region));
        when(companyZoneRepository.findByIdAndCompanyRegionId(zone.getId(), region.getId()))
                .thenReturn(Optional.of(zone));

        companyZoneService.getZoneById(companyId, companyCountryId, region.getId(), zone.getId());
        companyZoneService.getZoneById(companyId, companyCountryId, region.getId(), zone.getId());

        // Same load-bearing assertion, on the read that used to be keyed by the zone id alone.
        verify(currentUserContext, times(2))
                .verifyCompanyZoneAccess(companyId, companyCountryId, region.getId(), zone.getId());
    }

    private static CompanyCountry companyCountry(UUID companyCountryId) {
        var company = Company.builder()
                .id(UUID.randomUUID())
                .companyKey("1")
                .companyName("Test Company")
                .rfc("XAXX010101000")
                .enabled(true)
                .build();
        var country = Country.builder()
                .id(UUID.randomUUID())
                .countryCode("MX")
                .countryName("México")
                .enabled(true)
                .build();
        return CompanyCountry.builder()
                .id(companyCountryId)
                .company(company)
                .country(country)
                .localAlias("Oficina MX")
                .build();
    }

    private static CompanyRegion region(CompanyCountry companyCountry) {
        return CompanyRegion.builder()
                .id(UUID.randomUUID())
                .companyCountry(companyCountry)
                .regionCode("NORTE")
                .regionName("Norte")
                .enabled(true)
                .build();
    }

    private static CompanyZone zone(CompanyRegion region) {
        return CompanyZone.builder()
                .id(UUID.randomUUID())
                .companyRegion(region)
                .zoneCode("CEN")
                .zoneName("Centro")
                .enabled(true)
                .build();
    }
}
