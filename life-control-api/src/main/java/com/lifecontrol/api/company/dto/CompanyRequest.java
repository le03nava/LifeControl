package com.lifecontrol.api.company.dto;

import com.lifecontrol.api.validation.ValidRFC;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CompanyRequest(
    @NotBlank(message = "companyKey es requerido")
    @Size(max = 50, message = "companyKey no puede exceder 50 caracteres")
    String companyKey,

    @NotBlank(message = "companyName es requerido")
    @Size(max = 200, message = "companyName no puede exceder 200 caracteres")
    String companyName,

    @Min(value = 1, message = "tipoPersonaId debe ser al menos 1")
    @Max(value = 5, message = "tipoPersonaId no puede exceder 5")
    Integer tipoPersonaId,

    @Size(max = 300, message = "razonSocial no puede exceder 300 caracteres")
    String razonSocial,

    @NotBlank(message = "RFC es requerido")
    @ValidRFC
    String rfc,

    @Size(max = 20, message = "phone no puede exceder 20 caracteres")
    String phone,

    @Email(message = "email debe tener formato válido")
    @Size(max = 100, message = "email no puede exceder 100 caracteres")
    String email,

    Boolean enabled,

    @Size(max = 255, message = "street no puede exceder 255 caracteres")
    String street,

    @Size(max = 20, message = "streetNumber no puede exceder 20 caracteres")
    String streetNumber,

    @Size(max = 20, message = "internalNumber no puede exceder 20 caracteres")
    String internalNumber,

    @Size(max = 255, message = "neighborhood no puede exceder 255 caracteres")
    String neighborhood,

    @Size(max = 10, message = "zipCode no puede exceder 10 caracteres")
    String zipCode,

    @Size(max = 255, message = "city no puede exceder 255 caracteres")
    String city,

    @Size(max = 255, message = "state no puede exceder 255 caracteres")
    String state,

    UUID countryId
) {
    public CompanyRequest {
        if (enabled == null) {
            enabled = true;
        }
    }
}