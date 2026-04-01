package com.lifecontrol.api.company.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanyRequest {

    @NotNull(message = "companyId es requerido")
    private Integer companyId;

    @NotBlank(message = "companyName es requerido")
    @Size(max = 200, message = "companyName no puede exceder 200 caracteres")
    private String companyName;

    private Integer tipoPersonaId;

    @Size(max = 300, message = "razonSocial no puede exceder 300 caracteres")
    private String razonSocial;

    @NotBlank(message = "RFC es requerido")
    @Size(min = 10, max = 13, message = "RFC debe tener entre 10 y 13 caracteres")
    private String rfc;

    @Size(max = 20, message = "phone no puede exceder 20 caracteres")
    private String phone;

    @Email(message = "email debe tener formato válido")
    @Size(max = 100, message = "email no puede exceder 100 caracteres")
    private String email;

    @Builder.Default
    private Boolean enabled = true;
}