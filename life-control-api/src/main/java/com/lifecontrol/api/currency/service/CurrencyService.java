package com.lifecontrol.api.currency.service;

import com.lifecontrol.api.currency.dto.CurrencyRequest;
import com.lifecontrol.api.currency.dto.CurrencyResponse;
import com.lifecontrol.api.currency.exception.CurrencyNotFoundException;
import com.lifecontrol.api.currency.exception.DuplicateCurrencyException;
import com.lifecontrol.api.currency.model.Currency;
import com.lifecontrol.api.currency.repository.CurrencyRepository;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes the platform currency catalog — the reference data a company's operating currency is
 * picked from (ADR-0002 decision 2, {@code V57__company_currency.sql}).
 *
 * <p><b>Why the read is cached, and why the key carries the flag.</b> The catalog is global and changes
 * rarely, so it is the cheapest kind of cacheable read; the two shapes it serves are different answers, so
 * {@code includeDisabled} is part of the key exactly as {@code MeasureUnitService} and {@code CountryService}
 * state it — one region, one entry per shape, never an entry of one served to a caller who asked for the
 * other.</p>
 *
 * <p><b>Why every write evicts the whole region.</b> Until this class had writes, the absence of eviction
 * was correct: nothing in the application could change a row, so the cached answer could only be stale
 * after a deploy and a restart cleared it. A write path changes that — a retire that leaves the cached
 * enabled list in place makes {@code GET /api/currencies} keep offering a code the operator just retired,
 * which is the one behaviour this catalog's write half exists to fix. So each write evicts
 * {@code currencies} with {@code allEntries = true}, the shape {@code PaymentMethodService} uses: the two
 * shapes of the read are two entries of one region, and an edit can move a code between them.</p>
 *
 * <p><b>Why {@code enabled} is a filter and not a delete.</b> A retired code stays referenceable by
 * history: {@code companies.currency} and four other FK columns may already point at it, so the row is
 * never removed by a retire. It is simply not offered — {@code CompanyService.resolveCurrency} refuses a
 * disabled code with a 400 — which is why the default answer here is the enabled set only. A hard
 * {@code DELETE} is therefore a different operation, and the database refuses it whenever anything
 * references the row; that refusal is a 409, not a 500.</p>
 */
@Service
public class CurrencyService {

    private static final Logger logger = LoggerFactory.getLogger(CurrencyService.class);

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

    /**
     * Adds a code to the catalog.
     *
     * <p>{@code code} is the key, so a code the catalog already carries is a <b>409</b> and never a silent
     * overwrite: a create that replaced the row would also replace a retired flag and a {@code minorUnits}
     * that existing documents were rounded against, on a request whose author believed they were adding a
     * currency.</p>
     */
    @CacheEvict(value = "currencies", allEntries = true)
    @Transactional
    public CurrencyResponse createCurrency(CurrencyRequest request) {
        logger.info("Creating currency with code: {}", request.code());

        if (currencyRepository.existsById(request.code())) {
            throw new DuplicateCurrencyException(request.code());
        }

        var currency = currencyRepository.save(
                new Currency(request.code(), request.minorUnits(), request.symbol(), request.enabled()));
        logger.info("Currency created successfully with code: {}", currency.getCode());

        return toResponse(currency);
    }

    /**
     * Replaces the state of one code.
     *
     * <p>The code itself is <b>not</b> editable here: the path names the row and the body only describes
     * its state, so a body stating a different code is refused rather than accepted-and-ignored. Renaming
     * the key would leave the five foreign keys that reference it without a target.</p>
     */
    @CacheEvict(value = "currencies", allEntries = true)
    @Transactional
    public CurrencyResponse updateCurrency(String code, CurrencyRequest request) {
        logger.info("Updating currency with code: {}", code);

        var currency = currencyRepository.findById(code).orElseThrow(() -> new CurrencyNotFoundException(code));

        // The path names the row and the body describes its state; accepting a body that names another code
        // would either rename a key five foreign columns reference or use a value the caller never chose.
        if (!Objects.equals(code, request.code())) {
            throw new IllegalArgumentException("a currency's code cannot be renamed: the path names '" + code
                    + "' and the body states '" + request.code() + "'");
        }

        currency.setMinorUnits(request.minorUnits());
        currency.setSymbol(request.symbol());
        currency.setEnabled(request.enabled());
        var updated = currencyRepository.save(currency);
        logger.info("Currency updated successfully with code: {}", updated.getCode());

        return toResponse(updated);
    }

    /**
     * Removes the row.
     *
     * <p>A hard delete, unlike a retire, and the database refuses it whenever any of the five FK columns
     * points at the code — {@code companies.currency} ({@code V57:82}), {@code variant_listing.currency}
     * ({@code V58:98}), {@code stock_movement.currency} and {@code stock_valuation.currency}
     * ({@code V60:125,226}) and {@code sales_orders.currency} ({@code V80:84}). That refusal arrives as a
     * {@code DataIntegrityViolationException}, which {@code GlobalExceptionHandler} already serves as a
     * 409; a 500 here would tell the operator nothing about which rows are holding the code, and a silent
     * success would be impossible. Retiring a code that is in use is {@link #setCurrencyEnabled}.</p>
     */
    @CacheEvict(value = "currencies", allEntries = true)
    @Transactional
    public void deleteCurrency(String code) {
        logger.info("Deleting currency with code: {}", code);

        var currency = currencyRepository.findById(code).orElseThrow(() -> new CurrencyNotFoundException(code));

        currencyRepository.delete(currency);
        // Flushed here, not only at commit: the foreign keys judge the statement, and their refusal has to
        // surface as the DataIntegrityViolationException the 409 is built on rather than as an ambiguous
        // commit-time failure after the endpoint has already claimed success.
        currencyRepository.flush();
        logger.info("Currency deleted: code={}", currency.getCode());
    }

    /**
     * Sets the {@code enabled} flag, which is how a code is retired and how it is brought back.
     *
     * <p>Disabling is the reversible half of a delete: the row survives, every historical reference keeps
     * resolving, and {@code CompanyService.resolveCurrency} stops offering it for new assignments.</p>
     */
    @CacheEvict(value = "currencies", allEntries = true)
    @Transactional
    public CurrencyResponse setCurrencyEnabled(String code, boolean enabled) {
        logger.info("Setting currency enabled={} for code: {}", enabled, code);

        var currency = currencyRepository.findById(code).orElseThrow(() -> new CurrencyNotFoundException(code));

        currency.setEnabled(enabled);
        var updated = currencyRepository.save(currency);

        return toResponse(updated);
    }

    private CurrencyResponse toResponse(Currency currency) {
        return new CurrencyResponse(
                currency.getCode(), currency.getMinorUnits(), currency.getSymbol(), currency.getEnabled());
    }
}
