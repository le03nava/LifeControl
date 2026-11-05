package com.lifecontrol.api.currency.dto;

/**
 * One row of the platform currency catalog, as {@code GET /api/currencies} serves it.
 *
 * <p><b>Why exactly these four fields.</b> They are the four columns of {@code currency}
 * ({@code V57__company_currency.sql}) and nothing else: the catalog has no timestamps and no generated id,
 * because an ISO 4217 code is the identity — {@code companies.currency} references it, and a caller names
 * the currency it wants by {@code MXN}. {@code minorUnits} is the number ADR-0002 decision 1 rounds and
 * prints against at the document boundary, and {@code symbol} is presentation only: the label a form
 * prints, never what a caller filters by.</p>
 *
 * <p><b>Why the shape is flat.</b> It is the same shape {@code MeasureUnitResponse} and
 * {@code CountryResponse} have — one row, one object, no envelope — so the list can be held and filtered
 * by a client without unwrapping anything, and a retired code stays visible under
 * {@code ?includeDisabled=true} with its own {@code enabled} flag instead of disappearing from a shape
 * that could not say why.</p>
 */
public record CurrencyResponse(String code, Short minorUnits, String symbol, Boolean enabled) {}
