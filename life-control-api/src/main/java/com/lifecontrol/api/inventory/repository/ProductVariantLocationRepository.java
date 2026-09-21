package com.lifecontrol.api.inventory.repository;

import com.lifecontrol.api.inventory.model.ProductVariantLocation;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface ProductVariantLocationRepository extends JpaRepository<ProductVariantLocation, UUID> {

    /**
     * Pessimistic write lock on the {@code (variant, location)} balance, mirroring
     * {@code ProductVariantStoreStockRepository#findByProductVariantIdAndCompanyStoreIdForUpdate}.
     * Callers must take the per-store stock lock first and then this balance lock, so two concurrent
     * receipts against the same balance queue instead of losing an update; the documented order is
     * {@code storeStock -> locationBalance}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT pvl FROM ProductVariantLocation pvl
            WHERE pvl.productVariantId = :productVariantId
              AND pvl.storeLocationId = :storeLocationId
            """)
    Optional<ProductVariantLocation> findByProductVariantIdAndStoreLocationIdForUpdate(
            @Param("productVariantId") UUID productVariantId, @Param("storeLocationId") UUID storeLocationId);

    /** Non-locking read of the balance, for assertions and read-only callers. */
    Optional<ProductVariantLocation> findByProductVariantIdAndStoreLocationId(
            UUID productVariantId, UUID storeLocationId);

    /**
     * Inserts the balance row for the pair only when it does not exist yet, relying on
     * {@code UNIQUE(product_variant_id, store_location_id)} and {@code ON CONFLICT DO NOTHING}
     * instead of catching the unique violation: a failed insert aborts the PostgreSQL transaction,
     * so a caught {@code DataIntegrityViolationException} could not be recovered from inside the
     * same transaction. The row's {@code stock}, {@code created_at} and {@code updated_at} take
     * their column defaults. The method carries its own read-write transaction so a direct call
     * cannot land in the read-only default transaction of Spring Data query methods.
     */
    @Modifying
    @Transactional
    @Query(value = """
                    INSERT INTO product_variant_locations (product_variant_id, store_location_id)
                    VALUES (:productVariantId, :storeLocationId)
                    ON CONFLICT (product_variant_id, store_location_id) DO NOTHING
                    """, nativeQuery = true)
    void insertBalanceIfAbsent(
            @Param("productVariantId") UUID productVariantId, @Param("storeLocationId") UUID storeLocationId);
}
