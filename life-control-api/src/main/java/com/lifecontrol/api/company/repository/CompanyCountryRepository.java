package com.lifecontrol.api.company.repository;

import com.lifecontrol.api.company.model.CompanyCountry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CompanyCountryRepository extends JpaRepository<CompanyCountry, UUID> {

    List<CompanyCountry> findByCompanyId(UUID companyId);

    boolean existsByCompanyIdAndCountryId(UUID companyId, UUID countryId);

    Optional<CompanyCountry> findByCompanyIdAndCountryId(UUID companyId, UUID countryId);
}
