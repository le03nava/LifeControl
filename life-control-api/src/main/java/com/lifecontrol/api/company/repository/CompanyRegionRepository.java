package com.lifecontrol.api.company.repository;

import com.lifecontrol.api.company.model.CompanyRegion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CompanyRegionRepository extends JpaRepository<CompanyRegion, UUID> {

    List<CompanyRegion> findByCompanyCountryIdOrderByRegionNameAsc(UUID companyCountryId);

    Optional<CompanyRegion> findByIdAndCompanyCountryId(UUID id, UUID companyCountryId);

    boolean existsByCompanyCountryIdAndRegionCode(UUID companyCountryId, String regionCode);

    boolean existsByCompanyCountryIdAndRegionCodeAndIdNot(UUID companyCountryId, String regionCode, UUID excludeId);
}
