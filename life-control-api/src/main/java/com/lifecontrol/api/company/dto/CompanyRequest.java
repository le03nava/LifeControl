package com.lifecontrol.api.company.dto;

import com.lifecontrol.api.common.address.dto.AddressRequest;
import com.lifecontrol.api.validation.ValidRFC;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

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

    AddressRequest address
) {
    public CompanyRequest {
        if (enabled == null) {
            enabled = true;
        }
    }
}