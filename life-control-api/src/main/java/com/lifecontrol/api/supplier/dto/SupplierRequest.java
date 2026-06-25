package com.lifecontrol.api.supplier.dto;

import com.lifecontrol.api.common.address.dto.AddressRequest;
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

    @Size(max = 20, message = "internalNumber no puede exceder 20 caracteres")
    String internalNumber,

    AddressRequest address,

    Boolean enabled
) {
    public SupplierRequest {
        if (enabled == null) {
            enabled = true;
        }
    }
}
