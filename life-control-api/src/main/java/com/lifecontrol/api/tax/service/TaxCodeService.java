package com.lifecontrol.api.tax.service;

import com.lifecontrol.api.tax.dto.TaxCodeResponse;
import com.lifecontrol.api.tax.model.TaxCode;
import com.lifecontrol.api.tax.repository.TaxCodeRepository;
import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the platform tax-code catalog — the vocabulary a product's tax code is picked from
 * (ADR-0002 decision 6, {@code V82__tax_rate.sql}). It is the read that {@code V82:22} says nobody had, and
 * it is the same platform-catalog shape {@code CurrencyService} already serves.
 *
 * <p><b>Read-only on purpose.</b> The catalog has no administration screen and no write endpoint: it is
 * seeded by migration, and the four codes it carries ({@code NA}, {@code IVA}, {@code TASA_0},
 * {@code EXENTO}) are the whole vocabulary the ADR needs. A fifth code is an {@code INSERT} in a reviewed
 * migration, not a request, which is why this class has no {@code @CacheEvict} counterpart — nothing in the
 * application can change a row that is cached below.</p>
 *
 * <p><b>Why the read is cached, and why the key carries the flag.</b> The catalog is global and changes only
 * with a deploy, so it is the cheapest kind of cacheable read, and it is read on every product form; the two
 * shapes it serves are different answers, so {@code includeDisabled} is part of the key exactly as
 * {@code CurrencyService} states it — one region, one entry per shape, never an entry of one served to a
 * caller who asked for the other.</p>
 *
 * <p><b>Why {@code enabled} is a filter and not a delete.</b> A retired code stays referenceable by history:
 * {@code sales_order_items.tax_code} snapshots the code a line was written with, so the row is never
 * removed. It is simply not offered — which is why the default answer here is the enabled set only.</p>
 *
 * <p><b>What this class deliberately does not do.</b> It does not say which codes are zero by definition
 * and which need a {@code tax_rate} row: that is D-a of {@code odd/tasks/product-tax-code.md}, and it
 * belongs to the resolver ({@link TaxRateService}) that answers for a company and a document date, not to a
 * catalog read that has neither.</p>
 */
@Service
public class TaxCodeService {

    private final TaxCodeRepository taxCodeRepository;

    public TaxCodeService(TaxCodeRepository taxCodeRepository) {
        this.taxCodeRepository = taxCodeRepository;
    }

    /**
     * The catalog as a flat list, enabled codes only unless {@code includeDisabled} asks for the retired
     * ones too.
     *
     * @param includeDisabled {@code true} to serve every row of the catalog, retired codes included
     */
    @Cacheable(value = "taxCodes", key = "'all-' + #includeDisabled")
    @Transactional(readOnly = true)
    public List<TaxCodeResponse> getAllTaxCodes(boolean includeDisabled) {
        if (includeDisabled) {
            return taxCodeRepository.findAll().stream().map(this::toResponse).toList();
        }
        return taxCodeRepository.findByEnabledTrue().stream()
                .map(this::toResponse)
                .toList();
    }

    private TaxCodeResponse toResponse(TaxCode taxCode) {
        return new TaxCodeResponse(taxCode.getCode(), taxCode.getLabel(), taxCode.getEnabled());
    }
}
