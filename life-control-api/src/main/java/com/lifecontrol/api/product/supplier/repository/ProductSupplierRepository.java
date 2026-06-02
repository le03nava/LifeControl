package com.lifecontrol.api.product.supplier.repository;

import com.lifecontrol.api.product.supplier.model.ProductSupplier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProductSupplierRepository extends JpaRepository<ProductSupplier, UUID> {

    List<ProductSupplier> findByProductId(UUID productId);

    boolean existsByProductIdAndSupplierId(UUID productId, UUID supplierId);

    Optional<ProductSupplier> findByProductIdAndId(UUID productId, UUID id);

    Optional<ProductSupplier> findByProductIdAndSupplierId(UUID productId, UUID supplierId);

    @Query("SELECT ps FROM ProductSupplier ps WHERE ps.product.id = :productId AND ps.main = true")
    Optional<ProductSupplier> findMainByProductId(UUID productId);
}
