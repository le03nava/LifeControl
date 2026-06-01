package com.lifecontrol.api.product.repository;

import com.lifecontrol.api.product.model.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ProductRepository extends JpaRepository<Product, UUID> {

    boolean existsBySku(String sku);

    boolean existsBySkuAndIdNot(String sku, UUID id);

    Page<Product> findByEnabledTrue(Pageable pageable);

    @Query("""
        SELECT p FROM Product p
        WHERE (LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(p.sku) LIKE LOWER(CONCAT('%', :search, '%')))
        AND p.enabled = true
        """)
    Page<Product> findBySearchTermAndEnabledTrue(
        @Param("search") String search, Pageable pageable);

    @Query("""
        SELECT p FROM Product p
        WHERE LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%'))
           OR LOWER(p.sku) LIKE LOWER(CONCAT('%', :search, '%'))
        """)
    Page<Product> findBySearchTerm(
        @Param("search") String search, Pageable pageable);
}
