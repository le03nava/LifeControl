package com.lifecontrol.api.inventory.repository;

import com.lifecontrol.api.inventory.model.StockValuation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * The ledger's valuation projection, read — never written.
 *
 * <p>{@code stock_valuation} is derived by {@code V61}'s {@code project_stock_movement} trigger and by the
 * allocation writer, so this repository deliberately declares no {@code save}, no {@code delete} and no
 * modifying query: the only operation a caller has is the read that answers "what does this store's stock of this
 * variant cost on average". A writer added here would be a second author of a derived row, which is the defect
 * ADR-0001 decision 2 exists to prevent.</p>
 */
@Repository
public interface StockValuationRepository extends JpaRepository<StockValuation, StockValuation.Key> {

    /**
     * The valuation of one {@code (store, variant)} pair.
     *
     * @return empty when the ledger holds no movement for the pair, which is "no cost has ever been observed
     *     here" — not a zero, and the reason {@code averageCost} is nullable in the responses that read it
     */
    Optional<StockValuation> findByCompanyStoreIdAndProductVariantId(UUID companyStoreId, UUID productVariantId);
}
