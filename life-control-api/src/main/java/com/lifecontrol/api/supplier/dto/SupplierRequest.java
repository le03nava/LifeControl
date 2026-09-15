package com.lifecontrol.api.supplier.dto;

import com.lifecontrol.api.common.address.dto.AddressRequest;
import com.lifecontrol.api.validation.ValidRFC;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SupplierRequest(
        @NotBlank(message = "supplierName is required")
                @Size(max = 200, message = "supplierName must not exceed 200 characters")
                String supplierName,
        @Size(max = 300, message = "razonSocial must not exceed 300 characters") String razonSocial,
        @NotBlank(message = "RFC is required") @ValidRFC String rfc,
        @Email(message = "email must have a valid format")
                @Size(max = 100, message = "email must not exceed 100 characters")
                String email,
        @Size(max = 20, message = "phoneNumber must not exceed 20 characters") String phoneNumber,
        @Size(max = 20, message = "internalNumber must not exceed 20 characters") String internalNumber,
        AddressRequest address,
        Boolean enabled) {
    public SupplierRequest {
        if (enabled == null) {
            enabled = true;
        }
    }
}
