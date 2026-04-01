package com.lifecontrol.api.company.repository;

import com.lifecontrol.api.company.model.Company;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CompanyRepository extends JpaRepository<Company, UUID> {

    Optional<Company> findByCompanyKey(String companyKey);

    Optional<Company> findByCompanyId(Integer companyId);

    boolean existsByCompanyKey(String companyKey);

    boolean existsByCompanyId(Integer companyId);

    boolean existsByRfc(String rfc);
}