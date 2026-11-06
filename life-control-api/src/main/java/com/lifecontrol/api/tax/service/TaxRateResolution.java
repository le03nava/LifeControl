package com.lifecontrol.api.tax.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * What a document's date resolves to: the rate row that applies, and the three facts a line snapshots.
 *
 * <p>It carries the row's id as well as its numbers because ADR-0002's {@code V58:26-32} leaves
 * {@code variant_listing.tax_rate_id} for a later slice: when that pointer lands, the resolution is already
 * answering with the row it points at, and no caller has to look it up twice.</p>
 *
 * @param taxRateId the {@code tax_rate} row this resolution came from
 * @param taxCode the catalog code, copied verbatim onto the line
 * @param rate the fraction to apply, {@code 0.160000} for 16%
 * @param effectiveFrom the day that rate started to apply, kept so a reprint can explain the number
 */
public record TaxRateResolution(UUID taxRateId, String taxCode, BigDecimal rate, LocalDate effectiveFrom) {}
