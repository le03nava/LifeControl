package com.lifecontrol.api.tax.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Read model of one of a company's tax rates.
 *
 * <p>{@code rate} is served as the fraction the column holds ({@code 0.160000}), not as a percentage, because
 * that is the value a line snapshots and the value {@code ck_item_tax} compares against.</p>
 */
public record TaxRateResponse(
        UUID id, UUID companyId, String taxCode, BigDecimal rate, LocalDate effectiveFrom, Boolean enabled) {}
