package com.lifecontrol.api.company.service;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.repository.CompanyCountryRepository;
import com.lifecontrol.api.company.repository.CompanyRegionRepository;
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
 * Pins the decision that the company-scoped company-region reads are deliberately uncached.
 *
 * <p>A plain unit test cannot observe caching at all: the {@code @Cacheable}/{@code @CacheEvict}
 * annotations only take effect when the call travels through the Spring proxy. This test therefore
 * runs {@link CompanyRegionService} through its proxy — {@code @SpringBootTest} with the {@code test}
 * profile, where {@code spring.cache.type=simple} supplies the simple in-memory cache manager. It
 * asserts that two identical calls each reach {@link CurrentUserContext#verifyCompanyRegionAccess}.
 *
 * <p>The assertion is load-bearing, and it is the reason the cache was removed rather than keyed
 * differently: on a cache <b>hit</b> the scope check never runs, and the cached value also carried
 * the <b>caller's</b> result — a caller holding {@code lc-company-region} received the claim-filtered
 * list while one holding {@code lc-company-country} received the whole country, both stored under the
 * same key, so whichever caller warmed the entry decided what every later caller saw. A later
 * {@code @Cacheable} on either method would serve the second call from the cache, skip the scope
 * check, and make these tests fail with {@code Wanted 2 times but was 1}.
 *
 * <p>The same decision, and the same kind of test, already exist for the six company-scoped HR reads
 * (record T16 of {@code odd/tasks/hr-org-structure.md}); this is the last pair that dissented.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("CompanyRegionService scoped reads are deliberately uncached")
class CompanyRegionServiceScopedReadCacheTest {

    @Autowired
    private CompanyRegionService companyRegionService;

    @MockitoBean
    private CompanyRegionRepository companyRegionRepository;

    @MockitoBean
    private CompanyCountryRepository companyCountryRepository;

    @MockitoBean
    private CurrentUserContext currentUserContext;

    @Test
    @DisplayName("two identical getAllRegions calls each reach verifyCompanyRegionAccess")
    void getAllRegions_TwoIdenticalCalls_ReachVerifyCompanyRegionAccessTwice() {
        var companyId = UUID.randomUUID();
        var companyCountryId = UUID.randomUUID();
        var companyCountry = companyCountry(companyCountryId, country());
        var region = region(companyCountry);

        when(companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId))
                .thenReturn(Optional.of(companyCountry));
        when(companyRegionRepository.findByCompanyCountryIdOrderByRegionNameAsc(companyCountryId))
                .thenReturn(List.of(region));

        companyRegionService.getAllRegions(companyId, companyCountryId, false);
        companyRegionService.getAllRegions(companyId, companyCountryId, false);

        // A @Cacheable on getAllRegions would serve the second call from the cache and skip the scope
        // check, turning this into "Wanted 2 times but was 1".
        verify(currentUserContext, times(2)).verifyCompanyRegionAccess(companyId, companyCountryId, null);
    }

    @Test
    @DisplayName("two identical getRegionById calls each reach verifyCompanyRegionAccess")
    void getRegionById_TwoIdenticalCalls_ReachVerifyCompanyRegionAccessTwice() {
        var companyId = UUID.randomUUID();
        var companyCountryId = UUID.randomUUID();
        var companyCountry = companyCountry(companyCountryId, country());
        var region = region(companyCountry);

        when(companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId))
                .thenReturn(Optional.of(companyCountry));
        when(companyRegionRepository.findByIdAndCompanyCountryId(region.getId(), companyCountryId))
                .thenReturn(Optional.of(region));

        companyRegionService.getRegionById(companyId, companyCountryId, region.getId());
        companyRegionService.getRegionById(companyId, companyCountryId, region.getId());

        // Same load-bearing assertion, on the read that used to be keyed by the region id alone.
        verify(currentUserContext, times(2)).verifyCompanyRegionAccess(companyId, companyCountryId, region.getId());
    }

    private static Country country() {
        return Country.builder()
                .id(UUID.randomUUID())
                .countryCode("MX")
                .countryName("México")
                .enabled(true)
                .build();
    }

    private static CompanyCountry companyCountry(UUID companyCountryId, Country country) {
        var company = Company.builder()
                .id(UUID.randomUUID())
                .companyKey("1")
                .companyName("Test Company")
                .rfc("XAXX010101000")
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
}
