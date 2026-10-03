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
        AddressRequest address) {
    public CompanyRequest {
        if (enabled == null) {
            enabled = true;
        }
    }
}
