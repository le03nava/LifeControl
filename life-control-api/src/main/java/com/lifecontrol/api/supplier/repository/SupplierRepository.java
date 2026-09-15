package com.lifecontrol.api.supplier.repository;

import com.lifecontrol.api.supplier.model.Supplier;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface SupplierRepository extends JpaRepository<Supplier, UUID> {

    Optional<Supplier> findByRfc(String rfc);

    boolean existsByRfc(String rfc);

    boolean existsByRfcAndIdNot(String rfc, UUID id);

    Page<Supplier> findByEnabledTrue(Pageable pageable);

    @Query(
            """
            SELECT s FROM Supplier s
            WHERE LOWER(s.supplierName) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(s.rfc) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(s.razonSocial) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(s.email) LIKE LOWER(CONCAT('%', :search, '%'))
            """)
    Page<Supplier> findBySearchTerm(@Param("search") String search, Pageable pageable);

    @Query(
            """
            SELECT s FROM Supplier s
            WHERE (LOWER(s.supplierName) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(s.rfc) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(s.razonSocial) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(s.email) LIKE LOWER(CONCAT('%', :search, '%')))
              AND s.enabled = true
            """)
    Page<Supplier> findBySearchTermAndEnabledTrue(@Param("search") String search, Pageable pageable);
}
