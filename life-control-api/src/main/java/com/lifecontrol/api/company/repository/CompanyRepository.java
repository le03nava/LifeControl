package com.lifecontrol.api.company.repository;

import com.lifecontrol.api.company.model.Company;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CompanyRepository extends JpaRepository<Company, UUID> {

    Optional<Company> findByCompanyKey(String companyKey);

    boolean existsByCompanyKey(String companyKey);

    boolean existsByRfc(String rfc);

    boolean existsByRfcAndIdNot(String rfc, UUID id);

    @Query("""
            SELECT c FROM Company c
            WHERE LOWER(c.companyName) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.rfc) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.razonSocial) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.email) LIKE LOWER(CONCAT('%', :search, '%'))
            """)
    Page<Company> findBySearchTerm(@Param("search") String search, Pageable pageable);
}