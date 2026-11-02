package com.lifecontrol.api.product.repository;

import com.lifecontrol.api.product.model.VariantListing;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The listings of ADR-0006 decision 5: one price per {@code (variant, store)} ({@code variant_listing},
 * {@code V58}).
 *
 * <p>Two operations, and the small surface is the point: this slice moves the price <b>reads</b> onto the
 * listing and gives the store-stock endpoint a single writer for it, while {@code product_variant_store_stock}
 * keeps the position. There is no delete and no "disable": nothing in the tree may make a listing disappear
 * without the operator seeing it, and {@code enabled} has no writer yet (see {@link VariantListing}).</p>
 */
@Repository
public interface VariantListingRepository extends JpaRepository<VariantListing, UUID> {

    /** The listing of one pair, or empty when the store does not sell the variant. */
    Optional<VariantListing> findByProductVariantIdAndCompanyStoreId(UUID productVariantId, UUID companyStoreId);

    /**
     * Writes the price of one pair, creating the listing on the first price.
     *
     * <p><b>Why one statement and not read-then-write.</b> {@code list_price} is {@code NOT NULL}, so the
     * store row's shape — "insert a placeholder row, then lock it" — has no equivalent here: there is no
     * price-less listing to insert. A read-then-save would therefore have to choose between creating a
     * listing at a price nobody stated and letting two concurrent first-prices collide on
     * {@code uq_listing_variant_store}. {@code ON CONFLICT (product_variant_id, company_store_id) DO UPDATE}
     * answers both: the first writer inserts, every later one updates the row the unique key already
     * guarantees, and no transaction is aborted by a violation it would have to catch.</p>
     *
     * <p><b>Why the company and the currency come from the statement.</b> {@code company_id} is the variant's
     * own company — which {@code fk_listing_store} then requires to be the store's too (invariant C6), so a
     * cross-tenant pair is refused by the database rather than trusted here — and {@code currency} is read
     * from that company ({@code companies.currency}, {@code V57}), exactly as {@code V58}'s backfill reads it.
     * Copying it in the same statement keeps one rule for both writers of a listing instead of a second
     * derivation that could disagree.</p>
     *
     * <p><b>What an update does NOT touch.</b> On conflict only {@code list_price} changes: the stored
     * currency is the currency that price was stated in, and rewriting it would reinterpret an amount the
     * company already published under a different one — the failure class ADR-0002 decision 2 refuses
     * ({@code V57:35-37}). {@code enabled} is left as it is for the same reason: a price write is not an
     * availability toggle.</p>
     *
     * <p>The method carries its own read-write transaction, like
     * {@code ProductVariantStoreStockRepository#insertStoreStockIfAbsent}, so a direct call cannot land in
     * the read-only default transaction of a Spring Data query method. The caller's own transaction is what
     * the row is written in: it joins it.</p>
     *
     * @return the number of rows written — 1 on insert, 1 on update, and <b>0</b> when the variant named no
     *     row, which the caller treats as a failure rather than a silent no-op
     */
    @Modifying
    @Transactional
    @Query(value = """
                    INSERT INTO variant_listing (
                           company_id, product_variant_id, company_store_id, list_price, currency)
                    SELECT v.company_id, v.id, :companyStoreId, :listPrice, c.currency
                      FROM product_variants v
                      JOIN companies c ON c.id = v.company_id
                     WHERE v.id = :productVariantId
                    ON CONFLICT (product_variant_id, company_store_id)
                    DO UPDATE SET list_price = EXCLUDED.list_price
                    """, nativeQuery = true)
    int upsertListPrice(
            @Param("productVariantId") UUID productVariantId,
            @Param("companyStoreId") UUID companyStoreId,
            @Param("listPrice") BigDecimal listPrice);
}
