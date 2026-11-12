package com.lifecontrol.api.tax.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Write payload for superseding a rate: the successor's value and the day it applies from.
 *
 * <p><b>Why {@code taxCode} is absent.</b> A successor is the next statement of <b>the same</b> code — the row
 * named by the path already says which one — so accepting a code here would make "supersede" able to
 * silently move a rate onto a different tax, which is a different act ({@code POST}).</p>
 *
 * <p><b>Why nothing is editable in place.</b> The ADR's words are that a rate change appends the next
 * {@code effective_from} and never rewrites history: {@code rate}, {@code tax_code} and {@code effective_from}
 * are {@code updatable = false} on the entity, so there is no shape of this payload that could edit the old
 * row even if it carried one.</p>
 *
 * <p><b>Why {@code enabled} is required and never defaulted.</b> Same rule as {@link TaxRateRequest}: a flag
 * whose default direction is "start taxing" must not be invented for a caller who stated the field with no
 * value. The precedents are the repository's, not this record's preference — {@code
 * CurrencyController#enabledFlag} refuses a present null with "answering the first with the default would
 * mean this endpoint silently turned 'no value' into 'enable it', which is the one direction an operator
 * never means when they send the field", and {@code companies.currency} is {@code @NotBlank} for the same
 * reason ({@code AGENTS.md}: "un MXN silencioso escribe la moneda de un país sobre las ventas de otro sin
 * decirlo"). A successor is the next statement of a rate, so it says whether it applies from its day.</p>
 */
public record TaxRateSupersedeRequest(
        @NotNull(message = "rate is required")
        @DecimalMin(value = "0.0", message = "rate must be between 0 and 1, as a fraction")
        @DecimalMax(value = "1.0", message = "rate must be between 0 and 1, as a fraction")
        @Digits(integer = 1, fraction = 6, message = "rate carries at most 6 decimals, the column's scale")
        BigDecimal rate,

        @NotNull(message = "effectiveFrom is required: a successor applies from a date")
        LocalDate effectiveFrom,

        @NotNull(message = "enabled is required: state whether the rate applies from that day")
        Boolean enabled) {}
