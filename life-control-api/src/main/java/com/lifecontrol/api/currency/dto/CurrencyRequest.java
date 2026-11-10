package com.lifecontrol.api.currency.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The state a caller states for one row of the platform currency catalog.
 *
 * <p><b>Why {@code code} travels in the body at all, when it is also the path variable.</b> {@code POST}
 * has no path to carry it, and one record for both verbs is the shape {@code PaymentMethodRequest} and
 * {@code MeasureUnitRequest} already have: a {@code PUT} states the whole row it wants, and which row that
 * is comes from the path. The two must agree — {@code CurrencyService} refuses a body that names a
 * different code than the path, rather than accepting a value and silently using another.</p>
 *
 * <p><b>Why the bounds are here and not only in the DDL.</b> {@code V57__company_currency.sql:60-65}
 * declares {@code minor_units SMALLINT NOT NULL CHECK (minor_units BETWEEN 0 AND 4)} and
 * {@code symbol VARCHAR(6)}. A request that violates either would reach the database and come back as a
 * 500 on a body the API could have judged, so both are refused at the boundary as a 400 that names the
 * field.</p>
 *
 * <p><b>Why {@code code} is a pattern and not a length.</b> The column is {@code CHAR(3)}, so a
 * length check would let {@code mxn}, {@code MX$} and {@code M X} through, and each of them is a
 * <b>second row for a currency the catalog already carries</b>: {@code companies.currency} and
 * {@code CompanyService.resolveCurrency} look up exactly the string they are given, so a catalog holding
 * {@code MXN} and {@code mxn} is two currencies as far as every read is concerned. The rule is
 * {@code [A-Z]{3}} — an ISO 4217 code is three uppercase letters — and it is a <b>refusal</b>, never a
 * silent rewrite: nothing in this class or in {@code CurrencyService} uppercases the caller's string, so
 * a caller who sends {@code mxn} learns the rule instead of storing a row nobody types again.</p>
 *
 * <p><b>Why {@code @NotBlank} is still there beside {@code @Pattern}.</b> {@code @Pattern} ignores
 * {@code null} (its own contract), so it cannot be the only rule on this field: without
 * {@code @NotBlank}, a body with no {@code code} would pass validation with {@code null}, and the refusal
 * would move to a primary-key {@code NOT NULL} violation in the database — a 500 on a request the API
 * could have judged. Measured through the endpoint: {@code {"code": null}} and a body that omits
 * {@code code} are both a 400 whose {@code errors.code} reads {@code code is required}.</p>
 */
public record CurrencyRequest(
        @NotBlank(message = "code is required")
        @Pattern(regexp = "[A-Z]{3}", message = "code must be three uppercase letters (ISO 4217)")
        String code,

        @NotNull(message = "minorUnits is required")
        @Min(value = 0, message = "minorUnits must be between 0 and 4")
        @Max(value = 4, message = "minorUnits must be between 0 and 4")
        Short minorUnits,

        @Size(max = 6, message = "symbol must be at most 6 characters")
        String symbol,

        Boolean enabled) {

    public CurrencyRequest {
        // The column default is `enabled BOOLEAN NOT NULL DEFAULT true` (V57:63), and a create that does
        // not mention the flag creates an assignable code. Retiring a row is an explicit `enabled: false`
        // or the PATCH endpoint, never an omission.
        if (enabled == null) {
            enabled = true;
        }
    }
}
