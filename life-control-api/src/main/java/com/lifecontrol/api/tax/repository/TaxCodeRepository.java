package com.lifecontrol.api.tax.repository;

import com.lifecontrol.api.tax.model.TaxCode;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Reads the platform tax-code catalog. The key is the code, not a generated id: a caller names the code it
 * wants by {@code IVA}, and {@code tax_rate.tax_code} and {@code sales_order_items.tax_code} hold the same
 * value.
 *
 * <p><b>Why there is no writer.</b> {@code V82:45-49} seeds the four codes and a new one is an
 * {@code INSERT} in a reviewed migration, so this interface declares no {@code save} of its own and no
 * service in this tree calls one — the catalog is the platform-catalog shape {@code CurrencyRepository}
 * already has. Nothing here is tenant-scoped either: {@code tax_code} has no {@code company_id} and no
 * policy, so a read needs no scope and a missing scope hides no row.</p>
 */
@Repository
public interface TaxCodeRepository extends JpaRepository<TaxCode, String> {

    /**
     * The codes that may still be assigned — {@code enabled = true}, the default answer of
     * {@code GET /api/tax-codes}.
     *
     * <p>A derived query and not a {@code @Query}, like {@code CurrencyRepository.findByEnabledTrue()}:
     * the predicate is a column, and a retired code is kept rather than deleted so a document that already
     * snapshots it keeps resolving.</p>
     */
    List<TaxCode> findByEnabledTrue();
}
