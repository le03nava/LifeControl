package com.lifecontrol.api.product.repository;

import com.lifecontrol.api.product.model.ProductVariantStoreStock;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-store stock and pricing rows of a product variant.
 *
 * <p>This repository now owns the serialization point of the variant model: the pessimistic write
 * lock that used to live on {@code ProductVariantRepository#findByIdForUpdate} moved here as
 * {@link #findByProductVariantIdAndCompanyStoreIdForUpdate(UUID, UUID)}, mirroring
 * {@code ProductVariantLocationRepository}'s lock pattern and javadoc. Two movers of the same
 * variant's stock in the same store queue on this row instead of on the shared global definition.</p>
 */
@Repository
public interface ProductVariantStoreStockRepository extends JpaRepository<ProductVariantStoreStock, UUID> {

    /** Non-locking read of one store's row, for read-only callers and assertions. */
    Optional<ProductVariantStoreStock> findByProductVariantIdAndCompanyStoreId(
            UUID productVariantId, UUID companyStoreId);

    /** Store membership probe: does this variant exist operationally in this store? */
    boolean existsByProductVariantIdAndCompanyStoreId(UUID productVariantId, UUID companyStoreId);

    /**
     * Whether the variant holds stock different from the given value in <strong>any</strong> store.
     *
     * <p><b>Nothing asks this any more.</b> It used to be half of the measure-unit guard of decision D3; since
     * T3c the guard asks the stock ledger ({@code StockLedgerWriter#hasMovements}), because this column stops
     * being maintained by the write path and a guard reading it would answer "no history" for a variant with
     * plenty. The method survives only so {@code ProductVariantServiceTest} can assert it is <em>never</em>
     * called, and it goes with the column in T4. Do not wire a new reader to it: the number it returns is a
     * frozen copy of a fact the ledger owns.</p>
     */
    boolean existsByProductVariantIdAndStockNot(UUID productVariantId, BigDecimal stock);

    /**
     * Pessimistic write lock on the {@code (variant, store)} stock row, mirroring
     * {@code ProductVariantLocationRepository#findByProductVariantIdAndStoreLocationIdForUpdate}.
     * Callers must take this lock before moving a variant's stock so two concurrent movers of the
     * same store row queue instead of losing an update. It is the FIRST lock a stock mover takes on
     * the store-scoped row; the lock order documented by the callers is
     * {@code storeStock -> locationBalance}, and the goods-receipt path additionally takes the
     * purchase-order lock ahead of both.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT pvss FROM ProductVariantStoreStock pvss
            WHERE pvss.productVariantId = :productVariantId
              AND pvss.companyStoreId = :companyStoreId
            """)
    Optional<ProductVariantStoreStock> findByProductVariantIdAndCompanyStoreIdForUpdate(
            @Param("productVariantId") UUID productVariantId, @Param("companyStoreId") UUID companyStoreId);

    /**
     * Inserts the store row for the pair only when it does not exist yet, relying on
     * {@code UNIQUE(product_variant_id, company_store_id)} and {@code ON CONFLICT DO NOTHING}
     * instead of catching the unique violation: a failed insert aborts the PostgreSQL transaction,
     * so a caught {@code DataIntegrityViolationException} could not be recovered from inside the
     * same transaction. The row's {@code stock}, {@code created_at} and {@code updated_at} take their
     * column defaults. The method carries its own read-write transaction so a direct call cannot
     * land in the read-only default transaction of Spring Data query methods.
     */
    @Modifying
    @Transactional
    @Query(value = """
                    INSERT INTO product_variant_store_stock (product_variant_id, company_store_id)
                    VALUES (:productVariantId, :companyStoreId)
                    ON CONFLICT (product_variant_id, company_store_id) DO NOTHING
                    """, nativeQuery = true)
    void insertStoreStockIfAbsent(
            @Param("productVariantId") UUID productVariantId, @Param("companyStoreId") UUID companyStoreId);
}
