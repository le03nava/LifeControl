package com.lifecontrol.api.supplier.dto;

import com.lifecontrol.api.validation.ValidRFC;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SupplierRequest(
    @NotBlank(message = "supplierName es requerido")
    @Size(max = 200, message = "supplierName no puede exceder 200 caracteres")
    String supplierName,

    @Size(max = 300, message = "razonSocial no puede exceder 300 caracteres")
    String razonSocial,

    @NotBlank(message = "RFC es requerido")
    @ValidRFC
    String rfc,

    @Email(message = "email debe tener formato válido")
    @Size(max = 100, message = "email no puede exceder 100 caracteres")
    String email,

    @Size(max = 20, message = "phoneNumber no puede exceder 20 caracteres")
    String phoneNumber,

    @Size(max = 255, message = "street no puede exceder 255 caracteres")
    String street,

    @Size(max = 20, message = "streetNumber no puede exceder 20 caracteres")
    String streetNumber,

    @Size(max = 255, message = "neighborhood no puede exceder 255 caracteres")
    String neighborhood,

    @Size(max = 20, message = "zipCode no puede exceder 20 caracteres")
    String zipCode,

    @Size(max = 255, message = "city no puede exceder 255 caracteres")
    String city,

    @Size(max = 255, message = "state no puede exceder 255 caracteres")
    String state,

    Boolean enabled
) {
    public SupplierRequest {
        if (enabled == null) {
            enabled = true;
        }
    }
}
