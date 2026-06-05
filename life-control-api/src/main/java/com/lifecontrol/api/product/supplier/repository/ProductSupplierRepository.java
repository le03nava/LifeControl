package com.lifecontrol.api.product.supplier.repository;

import com.lifecontrol.api.product.supplier.model.ProductSupplier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProductSupplierRepository extends JpaRepository<ProductSupplier, UUID> {

    List<ProductSupplier> findByProductId(UUID productId);

    List<ProductSupplier> findBySupplierId(UUID supplierId);

    @Query("""
        SELECT ps FROM ProductSupplier ps
        JOIN FETCH ps.product p
        WHERE ps.supplier.id = :supplierId
        AND p.enabled = true
        AND (:search IS NULL OR :search = ''
             OR LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%'))
             OR LOWER(p.sku) LIKE LOWER(CONCAT('%', :search, '%')))
        """)
    List<ProductSupplier> findBySupplierIdWithSearch(
            @Param("supplierId") UUID supplierId,
            @Param("search") String search);

    boolean existsByProductIdAndSupplierId(UUID productId, UUID supplierId);

    Optional<ProductSupplier> findByProductIdAndId(UUID productId, UUID id);

    Optional<ProductSupplier> findByProductIdAndSupplierId(UUID productId, UUID supplierId);

    @Query("SELECT ps FROM ProductSupplier ps WHERE ps.product.id = :productId AND ps.main = true")
    Optional<ProductSupplier> findMainByProductId(UUID productId);
}
