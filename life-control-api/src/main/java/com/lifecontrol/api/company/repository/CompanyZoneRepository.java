package com.lifecontrol.api.company.repository;

import com.lifecontrol.api.company.model.CompanyZone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface CompanyZoneRepository extends JpaRepository<CompanyZone, UUID> {

    List<CompanyZone> findByCompanyRegionIdOrderByZoneNameAsc(UUID regionId);

    Optional<CompanyZone> findByIdAndCompanyRegionId(UUID id, UUID regionId);

    boolean existsByCompanyRegionIdAndZoneCode(UUID regionId, String zoneCode);

    boolean existsByCompanyRegionIdAndZoneCodeAndIdNot(UUID regionId, String zoneCode, UUID excludeId);

    /**
     * Returns CompanyZone records whose IDs are in the given set AND belong to the given company region.
     * Used for lc-company-zone filtered GET to scope results to the user's allowed zone IDs.
     */
    List<CompanyZone> findByIdInAndCompanyRegionId(Set<UUID> zoneIds, UUID regionId);
}
