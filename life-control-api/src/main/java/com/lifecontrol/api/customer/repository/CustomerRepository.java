package com.lifecontrol.api.customer.repository;

import com.lifecontrol.api.customer.model.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    List<Customer> findByEnabledTrue();

    Page<Customer> findByEnabledTrue(Pageable pageable);

    Page<Customer> findByEnabledTrueOrderByCreatedAtDesc(Pageable pageable);

    List<Customer> findBySalesChannel(String salesChannel);

    boolean existsByEmail(String email);

    @Query("""
        SELECT c FROM Customer c
        WHERE c.enabled = true
          AND (LOWER(c.name) LIKE LOWER(CONCAT('%', :search, '%'))
             OR LOWER(c.email) LIKE LOWER(CONCAT('%', :search, '%')))
        ORDER BY c.createdAt DESC
        """)
    Page<Customer> findBySearchTerm(@Param("search") String search, Pageable pageable);
}
