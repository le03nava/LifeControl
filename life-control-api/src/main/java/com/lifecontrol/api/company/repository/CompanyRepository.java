package com.lifecontrol.api.company.repository;

import com.lifecontrol.api.company.model.Company;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CompanyRepository extends JpaRepository<Company, UUID> {

    Optional<Company> findByCompanyId(Integer companyId);

    boolean existsByCompanyId(Integer companyId);

    boolean existsByRfc(String rfc);

    boolean existsByRfcAndIdNot(String rfc, UUID id);
}