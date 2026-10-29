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
     * <p>The company predicate is what keeps the by-supplier read inside the tenant. Since {@code V43}
     * a relation cannot even pair a supplier with a product of another company —
     * {@code fk_product_suppliers_supplier_same_company} refuses it — so the predicate no longer
     * rescues rows the schema admits; it is the scope of the company named by the path, kept as a query
     * predicate so the result cannot become a page whose count contradicts what it returns.</p>
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
