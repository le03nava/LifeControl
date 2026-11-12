package com.lifecontrol.api.tax.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Read model of one of a company's tax rates.
 *
 * <p>{@code rate} is served as the fraction the column holds ({@code 0.160000}), not as a percentage, because
 * that is the value a line snapshots and the value {@code ck_item_tax} compares against.</p>
 *
 * <p>{@code regionId} and {@code regionName} are what make the row's scope readable: {@code null} on both is
 * the company-wide rate, and a named region is the rate that applies in the places whose store chain reaches
 * it ({@code company_stores.company_zone_id} &rarr; {@code company_zones.company_region_id}). The name is
 * projected beside the id because an ABM is read by a person deciding which place a rate belongs to — the same
 * reason {@code CompanyStoreResponse} carries its zone's and its region's names rather than only their ids —
 * and because the id alone would make two rates for one code and one day indistinguishable on screen.</p>
 */
public record TaxRateResponse(
        UUID id,
        UUID companyId,
        String taxCode,
        BigDecimal rate,
        LocalDate effectiveFrom,
        Boolean enabled,
        UUID regionId,
        String regionName) {}
