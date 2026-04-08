package com.lifecontrol.api.company.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CompanyRequest(
    @NotNull(message = "companyId es requerido")
    Integer companyId,

    @NotBlank(message = "companyName es requerido")
    @Size(max = 200, message = "companyName no puede exceder 200 caracteres")
    String companyName,

    Integer tipoPersonaId,

    @Size(max = 300, message = "razonSocial no puede exceder 300 caracteres")
    String razonSocial,

    @NotBlank(message = "RFC es requerido")
    @Size(min = 10, max = 13, message = "RFC debe tener entre 10 y 13 caracteres")
    String rfc,

    @Size(max = 20, message = "phone no puede exceder 20 caracteres")
    String phone,

    @Email(message = "email debe tener formato válido")
    @Size(max = 100, message = "email no puede exceder 100 caracteres")
    String email,

    Boolean enabled
) {
    public CompanyRequest {
        if (enabled == null) {
            enabled = true;
        }
    }
}