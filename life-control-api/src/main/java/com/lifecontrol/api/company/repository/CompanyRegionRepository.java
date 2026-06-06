package com.lifecontrol.api.company.repository;

import com.lifecontrol.api.company.model.CompanyRegion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface CompanyRegionRepository extends JpaRepository<CompanyRegion, UUID> {

    List<CompanyRegion> findByCompanyCountryIdOrderByRegionNameAsc(UUID companyCountryId);

    Optional<CompanyRegion> findByIdAndCompanyCountryId(UUID id, UUID companyCountryId);

    boolean existsByCompanyCountryIdAndRegionCode(UUID companyCountryId, String regionCode);

    boolean existsByCompanyCountryIdAndRegionCodeAndIdNot(UUID companyCountryId, String regionCode, UUID excludeId);

    /**
     * Returns CompanyRegion records whose IDs are in the given set AND belong to the given company country.
     * Used for lc-company-region filtered GET to scope results to the user's allowed region IDs.
     */
    List<CompanyRegion> findByIdInAndCompanyCountryId(Set<UUID> regionIds, UUID companyCountryId);
}
