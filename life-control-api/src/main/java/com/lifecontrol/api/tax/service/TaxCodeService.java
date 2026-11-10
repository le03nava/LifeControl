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
 * <p><b>What this class carries besides the read.</b> Which codes are zero by definition and which need a
 * {@code tax_rate} row — decision <b>D-a</b> of {@code odd/tasks/product-tax-code.md}: the codes in
 * {@link #ZERO_RATE_CODES} state rate {@code 0} with no lookup, and every other code either resolves
 * ({@link TaxRateService}) or refuses the line naming the code and the document's date. The rule lives here
 * and not in a caller because it is about what a code <i>means</i>, which is a fact of the catalog and not of
 * a document. It was written inside the sales consumer, and the second consumer ({@code purchaseorder}) is
 * what made the extraction legitimate rather than a refactor for its own sake (D3 of
 * {@code odd/tasks/purchase-order-line-tax.md}).</p>
 */
@Service
public class TaxCodeService {

    /**
     * The codes that are <b>zero by definition</b>, so a line carrying one states rate {@code 0} without a
     * {@code tax_rate} row: decision <b>D-a</b> of {@code odd/tasks/product-tax-code.md} (S6), taken 2026-11-08
     * because decision 6 does not answer it.
     *
     * <p><b>Why these three and not "any code with no rate".</b> {@code NA} is the column's own default and
     * every environment's {@code tax_rate} table is empty, so "no rate means refuse" applied uniformly would
     * refuse every line in the tree — and "no rate means zero" applied uniformly is the silent zero ADR-0002
     * exists to forbid. These three say <i>why</i> their zero is a zero, which is also why the catalog separates
     * them: CFDI distinguishes Tasa 0% from Exento and both charge nothing (D-d).</p>
     *
     * <p><b>Why a {@link List} and not a {@code Set}.</b> Three values, one {@code contains} and a refusal that
     * names them: an insertion-ordered list makes that sentence the same on every run, and a {@code Set.of}'s
     * iteration order is deliberately unspecified.</p>
     *
     * <p><b>Why the name alone decides, with no catalog read.</b> The rule is about the code, not about the row:
     * a rate row of {@code 0} would make {@code IVA} zero too, which is the mistake the whole decision protects
     * against. A code outside this set needs a rate, and one that cannot be resolved refuses the line.</p>
     */
    public static final List<String> ZERO_RATE_CODES = List.of("NA", "TASA_0", "EXENTO");

    private final TaxCodeRepository taxCodeRepository;

    public TaxCodeService(TaxCodeRepository taxCodeRepository) {
        this.taxCodeRepository = taxCodeRepository;
    }

    /**
     * Whether {@code taxCode} states rate {@code 0} by definition, so no {@code tax_rate} row is consulted.
     *
     * <p>The one answer the two line writers — sales and purchases — have to agree on, which is why it is a
     * method beside the list instead of a {@code contains} each caller repeats.</p>
     *
     * @param taxCode the code copied from a product's {@code tax_code}; never {@code null}, because the column
     *     is {@code NOT NULL} since {@code V89}
     */
    public static boolean isZeroRateByDefinition(String taxCode) {
        return ZERO_RATE_CODES.contains(taxCode);
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
