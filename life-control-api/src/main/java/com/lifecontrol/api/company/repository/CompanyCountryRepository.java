package com.lifecontrol.api.company.repository;

import com.lifecontrol.api.company.model.CompanyCountry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface CompanyCountryRepository extends JpaRepository<CompanyCountry, UUID> {

    List<CompanyCountry> findByCompanyId(UUID companyId);

    boolean existsByCompanyIdAndCountryId(UUID companyId, UUID countryId);

    Optional<CompanyCountry> findByCompanyIdAndCountryId(UUID companyId, UUID countryId);

    Optional<CompanyCountry> findByCompanyIdAndId(UUID companyId, UUID id);

    List<CompanyCountry> findAllByIdIn(Set<UUID> ids);

    /**
     * Returns CompanyCountry records whose IDs are in the given set AND belong to the given company.
     * Used for lc-company-country filtered GET to scope results to the user's allowed country IDs.
     */
    List<CompanyCountry> findByIdInAndCompanyId(Set<UUID> ids, UUID companyId);
}
