package com.lifecontrol.api.customer.dto;

import com.lifecontrol.api.customer.validation.FiscalProfileComplete;
import com.lifecontrol.api.validation.ValidRFC;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * One customer, at the till and at issue.
 *
 * <p><b>The receiver's fiscal profile (S2 of {@code odd/tasks/cfdi-fiscal-document.md}, decision L8 of the same
 * record).</b> Seven components below the login fields carry what a CFDI states of the receiver: legal name,
 * regime, the fiscal domicile with its postal code, the default use of the voucher and the country. The profile is
 * required <b>at issue</b>, not at the till — a cash customer states none of it — so every one of them is
 * nullable, and {@link FiscalProfileComplete} refuses the one shape that is neither: a partial profile.</p>
 *
 * <p><b>Why the codes travel as strings.</b> {@code regimenFiscal} and {@code defaultUsoCfdi} are spoken by the
 * authority: {@code regimenFiscal} is a {@code c_RegimenFiscal} code and {@code defaultUsoCfdi} a
 * {@code c_UsoCFDI} one. The record does not enumerate them in an annotation — a {@code @Pattern} over nineteen
 * plus twenty-five codes would be a second copy of two vocabularies, free to drift — so the service resolves each
 * against {@code com.lifecontrol.api.fiscal.model.RegimenFiscal} and {@code UsoCfdi} and stores the canonical
 * code, refusing an unknown one with a 400 that names the field.</p>
 *
 * <p><b>Why blank becomes null.</b> An empty string is not a legal name, a regime, a postal code or a use, and
 * the all-or-nothing rule asks a question that only has two honest answers: stated or absent. Normalizing here is
 * the pair of questions {@code @Pattern} and the vocabulary can answer, the same normalization
 * {@code CompanyRequest} gives its two fiscal fields.</p>
 */
@FiscalProfileComplete
public record CustomerRequest(
        @NotBlank(message = "name is required") String name,
        String email,
        String phone,

        /**
         * The receiver's RFC, validated <b>for shape</b> when present (S2, decision L8(d)).
         *
         * <p><b>The tree's one validator, not a second regex.</b> {@code @ValidRFC} is what
         * {@code CompanyRequest.rfc} already uses, and a copy of its pattern here could disagree with it. It
         * accepts {@code null}, because a cash customer has no fiscal identifier — and a value it refuses is a 400
         * that names {@code rfc}, before any column is written.</p>
         */
        @ValidRFC String rfc,
        @NotBlank(message = "salesChannel is required") String salesChannel,
        Boolean enabled,

        /**
         * The receiver's legal name as the fiscal document states it, or {@code null} (S2).
         *
         * <p>Deliberately not {@link #name}: that is the trade name an operator recognizes in a list and may be a
         * nickname, and a fiscal fact sharing a column with a mailing name is one nobody can rely on afterwards
         * (the same refusal L6(b) made about {@code companies.zip_code}).</p>
         */
        @Size(max = 255, message = "legalName must not exceed 255 characters")
        String legalName,

        /**
         * A {@code c_RegimenFiscal} code — the same catalog the issuer declares in
         * {@code companies.regimen_fiscal} — or {@code null}. Resolved against
         * {@code com.lifecontrol.api.fiscal.model.RegimenFiscal} by {@code CustomerService}; an unknown code is a
         * 400 that names this field instead of a constraint violation that names only the rule.
         */
        String regimenFiscal,

        /** The street and number of the fiscal domicile, optional: a CFDI needs the postal code, not the street. */
        @Size(max = 255, message = "fiscalStreet must not exceed 255 characters")
        String fiscalStreet,

        /**
         * The receiver's fiscal postal code — five digits — or {@code null}; the shape is refused here with a 400
         * that names this field, and the column carries the same rule as
         * {@code ck_customers_fiscal_zip_code} for every writer that bypasses the API.
         */
        @Pattern(regexp = "\\d{5}", message = "fiscalZipCode must be five digits (SAT postal code)")
        String fiscalZipCode,

        /** The state or province of the fiscal domicile, optional with the street. */
        @Size(max = 120, message = "fiscalState must not exceed 120 characters")
        String fiscalState,

        /** The country of the fiscal domicile, optional, named by the id of a {@code countries} row. */
        UUID fiscalCountryId,

        /**
         * A {@code c_UsoCFDI} code — the default use of the voucher a stamp proposes — or {@code null}. Resolved
         * against {@code com.lifecontrol.api.fiscal.model.UsoCfdi} by {@code CustomerService}.
         */
        String defaultUsoCfdi) {

    public CustomerRequest {
        if (enabled == null) {
            enabled = true;
        }
        legalName = blankToNull(legalName);
        regimenFiscal = blankToNull(regimenFiscal);
        fiscalStreet = blankToNull(fiscalStreet);
        fiscalZipCode = blankToNull(fiscalZipCode);
        fiscalState = blankToNull(fiscalState);
        defaultUsoCfdi = blankToNull(defaultUsoCfdi);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
