package com.lifecontrol.api.currency.repository;

import com.lifecontrol.api.currency.model.Currency;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Reads the platform currency catalog. The key is the ISO code, not a generated id: a caller names the
 * currency it wants by {@code MXN}, and {@code companies.currency} references the same value.
 */
@Repository
public interface CurrencyRepository extends JpaRepository<Currency, String> {}
