package com.lifecontrol.api.company.dto;

import com.lifecontrol.api.common.address.dto.AddressRequest;
import com.lifecontrol.api.validation.ValidRFC;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CompanyRequest(
        @NotBlank(message = "companyKey is required")
        @Size(max = 50, message = "companyKey must not exceed 50 characters")
        String companyKey,

        @NotBlank(message = "companyName is required")
        @Size(max = 200, message = "companyName must not exceed 200 characters")
        String companyName,

        @Min(value = 1, message = "tipoPersonaId must be at least 1")
        @Max(value = 5, message = "tipoPersonaId must not exceed 5")
        Integer tipoPersonaId,

        @Size(max = 300, message = "razonSocial must not exceed 300 characters")
        String razonSocial,

        @NotBlank(message = "RFC is required") @ValidRFC String rfc,

        @Size(max = 20, message = "phone must not exceed 20 characters")
        String phone,

        @Email(message = "email must have a valid format")
        @Size(max = 100, message = "email must not exceed 100 characters")
        String email,

        // Null and blank stay legal on purpose: a company without a domain fails the employee write path
        // closed with a 400 (feature decision D8), and there is no manual-entry mode to validate here.
        // This record is a full replacement: a PUT that omits emailDomain clears a configured domain, the
        // same convention email and phone already follow, and intentional per D8.
        @Size(max = 255, message = "emailDomain must not exceed 255 characters")
        @Pattern(
                regexp = "^$|^(?!-)[A-Za-z0-9-]+(?<!-)(\\.(?!-)[A-Za-z0-9-]+(?<!-))+$",
                message = "emailDomain must be a valid domain (e.g. acme.com)")
        String emailDomain,

        Boolean enabled,
        AddressRequest address,

        /**
         * The company's operating currency (ADR-0002 decision 2, D-c), named by ISO 4217 code.
         *
         * <p><b>Required, with no default.</b> A missing value is a 400 that names this field, because a
         * silent {@code MXN} is the failure class the decision refuses: it would write one country's
         * currency onto another's sales and never say so. The code is resolved against the platform
         * catalog by {@code CompanyService}, so a code the catalog does not carry is a 404 and a
         * disabled one is a 400 — neither of them reaches the row.</p>
         *
         * <p>Declared last so the components a caller passes positionally keep their order and the new
         * required field cannot be silently confused with an optional one.</p>
         */
        @NotBlank(message = "currency is required") String currency,

        /**
         * The issuer's fiscal regime — a code of SAT's {@code c_RegimenFiscal} catalog — or {@code null} while
         * the company has not declared one (S1 of {@code odd/tasks/cfdi-fiscal-document.md}, decision L6(a)).
         *
         * <p><b>Nullable on purpose</b>: S1 is explicit that the fiscal identity is "required to issue and
         * neither required to sell", so a company that sells without being able to invoice is a complete
         * row. A blank value is normalized to {@code null} here, because an empty string is not a regime.
         * A code outside the vocabulary is refused by {@code CompanyService} with a 400 that names this
         * field ({@code InvalidRegimenFiscalException}) instead of reaching
         * {@code ck_companies_regimen_fiscal}, which would answer with a constraint violation naming only
         * the rule.</p>
         *
         * <p>On update, an absent value means "leave what is stored", never "clear it": both fiscal fields
         * are optional, so a {@code PUT} that edits a phone number must not silently make a company unable
         * to issue.</p>
         */
        String regimenFiscal,

        /**
         * The issuer's place of issue — five digits — as the fiscal document will freeze it at stamp time
         * (S9, decision L6(b)).
         *
         * <p><b>Not the mailing address.</b> {@code companies.zip_code} is a twenty-character mailing field
         * whose shape nothing checks, and the fiscal fact is deliberately its own column. Nullable for the
         * same reason as {@link #regimenFiscal}, and blank is normalized to {@code null}; a value of any
         * other shape is a 400 that names this field, raised by bean validation here — the convention the
         * tree already uses for a shape on an optional field ({@code ProductCategoryRequest.satCode}).</p>
         */
        @Pattern(regexp = "\\d{5}", message = "fiscalZipCode must be five digits (SAT postal code)")
        String fiscalZipCode) {
    public CompanyRequest {
        if (enabled == null) {
            enabled = true;
        }
        // One absent value, not two: an empty string is not a regime and not a postal code. `@Pattern` and
        // the vocabulary are then asked about a value or about nothing, which is the pair of questions they
        // can answer — the same normalization `ProductCategoryRequest` gives a blank `satCode`.
        if (regimenFiscal != null && regimenFiscal.isBlank()) {
            regimenFiscal = null;
        }
        if (fiscalZipCode != null && fiscalZipCode.isBlank()) {
            fiscalZipCode = null;
        }
    }
}
