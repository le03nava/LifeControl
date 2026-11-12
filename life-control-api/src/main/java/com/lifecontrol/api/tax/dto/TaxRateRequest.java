package com.lifecontrol.api.tax.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Write payload for declaring a rate, i.e. appending a row to {@code tax_rate}.
 *
 * <p><b>Why the date is required and never defaulted to today.</b> A rate is resolved by
 * {@code effective_from <= the document's date}, so a server-chosen "today" would silently backdate nothing
 * and silently refuse every document a company already issued this month. The caller states the day it
 * applies from, which is the one fact only the company knows.</p>
 *
 * <p><b>Why {@code rate} is bounded here and not only by {@code CHECK (rate >= 0 AND rate <= 1)}.</b> The
 * column would refuse it too, but as a {@code DataIntegrityViolationException} translated into a 409 that
 * says "violates a data constraint" — a conflict for what is a malformed request. The bounds the schema
 * states are restated as validation so the answer is a 400 that names the field.</p>
 *
 * <p><b>Why {@code enabled} is required and never defaulted.</b> A flag whose default direction is "start
 * taxing" must not be invented for a caller who stated the field with no value, and an omission is not a
 * statement either: a rate that applies from a day changes what every document from that day is charged, so
 * the caller says whether it applies. The tree already decided this direction twice — {@code
 * CurrencyController#enabledFlag} refuses a present null with "answering the first with the default would
 * mean this endpoint silently turned 'no value' into 'enable it', which is the one direction an operator
 * never means when they send the field", and {@code companies.currency} is {@code @NotBlank} for the same
 * reason, recorded in {@code AGENTS.md} as "un MXN silencioso escribe la moneda de un país sobre las ventas
 * de otro sin decirlo". This record follows that rule rather than inventing a local preference.</p>
 */
public record TaxRateRequest(
        @NotBlank(message = "taxCode is required") @Size(max = 20)
        String taxCode,

        @NotNull(message = "rate is required")
        @DecimalMin(value = "0.0", message = "rate must be between 0 and 1, as a fraction")
        @DecimalMax(value = "1.0", message = "rate must be between 0 and 1, as a fraction")
        @Digits(integer = 1, fraction = 6, message = "rate carries at most 6 decimals, the column's scale")
        BigDecimal rate,

        @NotNull(message = "effectiveFrom is required: a rate applies from a date")
        LocalDate effectiveFrom,

        @NotNull(message = "enabled is required: state whether the rate applies from that day")
        Boolean enabled) {}
