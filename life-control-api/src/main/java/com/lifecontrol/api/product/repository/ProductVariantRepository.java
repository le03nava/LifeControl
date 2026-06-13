package com.lifecontrol.api.product.repository;

import com.lifecontrol.api.product.model.ProductVariant;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProductVariantRepository extends JpaRepository<ProductVariant, UUID> {

    List<ProductVariant> findByProductId(UUID productId);

    Page<ProductVariant> findByProductIdAndEnabledTrueOrderByCreatedAtDesc(UUID productId, Pageable pageable);

    Optional<ProductVariant> findByBarCode(String barCode);

    Optional<ProductVariant> findBySku(String sku);

    List<ProductVariant> findByCompanyStoreId(UUID companyStoreId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT pv FROM ProductVariant pv WHERE pv.id = :id AND pv.enabled = true")
    Optional<ProductVariant> findByIdForUpdate(@Param("id") UUID id);
}
