package com.lifecontrol.api.currency.repository;

import com.lifecontrol.api.currency.model.Currency;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Reads the platform currency catalog. The key is the ISO code, not a generated id: a caller names the
 * currency it wants by {@code MXN}, and {@code companies.currency} references the same value.
 */
@Repository
public interface CurrencyRepository extends JpaRepository<Currency, String> {

    /**
     * The codes that may still be assigned — {@code enabled = true}, the default answer of
     * {@code GET /api/currencies}.
     *
     * <p>A derived query and not a {@code @Query}, like {@code MeasureUnitRepository.findByEnabledTrue()}:
     * the predicate is a column, and a retired row is kept rather than deleted so history that references
     * it keeps resolving.</p>
     */
    List<Currency> findByEnabledTrue();
}
