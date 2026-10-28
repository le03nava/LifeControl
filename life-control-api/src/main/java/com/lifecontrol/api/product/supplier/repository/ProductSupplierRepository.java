package com.lifecontrol.api.product.supplier.repository;

import com.lifecontrol.api.product.supplier.model.ProductSupplier;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductSupplierRepository extends JpaRepository<ProductSupplier, UUID> {

    List<ProductSupplier> findByProductId(UUID productId);

    List<ProductSupplier> findBySupplierId(UUID supplierId);

    /**
     * The relations of one supplier whose product belongs to the company, enabled, optionally filtered by
     * product name or SKU.
     *
     * <p>The company predicate is what keeps the by-supplier read inside the tenant: suppliers are global,
     * so the same supplier legitimately supplies several companies, and without this a caller of company A
     * would receive the catalog of company B. It is a query predicate and not a post-filter so the result
     * cannot become a page whose count contradicts what it returns.</p>
     */
    @Query("""
        SELECT ps FROM ProductSupplier ps
        JOIN FETCH ps.product p
        WHERE ps.supplier.id = :supplierId
        AND p.company.id = :companyId
        AND p.enabled = true
        AND (:search IS NULL OR :search = ''
             OR LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%'))
             OR LOWER(p.sku) LIKE LOWER(CONCAT('%', :search, '%')))
        """)
    List<ProductSupplier> findByCompanyIdAndSupplierIdWithSearch(
            @Param("companyId") UUID companyId, @Param("supplierId") UUID supplierId, @Param("search") String search);

    boolean existsByProductIdAndSupplierId(UUID productId, UUID supplierId);

    Optional<ProductSupplier> findByProductIdAndId(UUID productId, UUID id);

    Optional<ProductSupplier> findByProductIdAndSupplierId(UUID productId, UUID supplierId);

    @Query("SELECT ps FROM ProductSupplier ps WHERE ps.product.id = :productId AND ps.main = true")
    Optional<ProductSupplier> findMainByProductId(UUID productId);
}
