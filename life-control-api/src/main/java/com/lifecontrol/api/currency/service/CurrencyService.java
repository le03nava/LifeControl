package com.lifecontrol.api.currency.service;

import com.lifecontrol.api.currency.dto.CurrencyResponse;
import com.lifecontrol.api.currency.model.Currency;
import com.lifecontrol.api.currency.repository.CurrencyRepository;
import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the platform currency catalog — the reference data a company's operating currency is picked from
 * (ADR-0002 decision 2, {@code V57__company_currency.sql}).
 *
 * <p><b>Read-only on purpose.</b> The catalog has no administration screen and no write endpoint: it is
 * seeded by migration, and the one row it carries today ({@code MXN}) is the only code the product states
 * anywhere. A second row is an {@code INSERT} in a reviewed migration, not a request, which is why this
 * class has no {@code @CacheEvict} counterpart — nothing in the application can change a row that is
 * cached below.</p>
 *
 * <p><b>Why the read is cached, and why the key carries the flag.</b> The catalog is global and changes
 * only with a deploy, so it is the cheapest kind of cacheable read; the two shapes it serves are different
 * answers, so {@code includeDisabled} is part of the key exactly as {@code MeasureUnitService} and
 * {@code CountryService} state it — one region, one entry per shape, never an entry of one served to a
 * caller who asked for the other.</p>
 *
 * <p><b>Why {@code enabled} is a filter and not a delete.</b> A retired code stays referenceable by
 * history: {@code companies.currency} may already point at it, so the row is never removed. It is simply
 * not offered — {@code CompanyService.resolveCurrency} refuses a disabled code with a 400 — which is why
 * the default answer here is the enabled set only.</p>
 */
@Service
public class CurrencyService {

    private final CurrencyRepository currencyRepository;

    public CurrencyService(CurrencyRepository currencyRepository) {
        this.currencyRepository = currencyRepository;
    }

    /**
     * The catalog as a flat list, enabled codes only unless {@code includeDisabled} asks for the retired
     * ones too.
     *
     * @param includeDisabled {@code true} to serve every row of the catalog, retired codes included
     */
    @Cacheable(value = "currencies", key = "'all-' + #includeDisabled")
    @Transactional(readOnly = true)
    public List<CurrencyResponse> getAllCurrencies(boolean includeDisabled) {
        if (includeDisabled) {
            return currencyRepository.findAll().stream().map(this::toResponse).toList();
        }
        return currencyRepository.findByEnabledTrue().stream()
                .map(this::toResponse)
                .toList();
    }

    private CurrencyResponse toResponse(Currency currency) {
        return new CurrencyResponse(
                currency.getCode(), currency.getMinorUnits(), currency.getSymbol(), currency.getEnabled());
    }
}
