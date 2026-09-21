package com.lifecontrol.api.product.repository;

import com.lifecontrol.api.product.model.ProductVariantStoreStock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductVariantStoreStockRepository extends JpaRepository<ProductVariantStoreStock, UUID> {

    /** Non-locking read of one store's row, for read-only callers and assertions. */
    Optional<ProductVariantStoreStock> findByProductVariantIdAndCompanyStoreId(
            UUID productVariantId, UUID companyStoreId);

    /** Store membership probe: does this variant exist operationally in this store? */
    boolean existsByProductVariantIdAndCompanyStoreId(UUID productVariantId, UUID companyStoreId);
}
