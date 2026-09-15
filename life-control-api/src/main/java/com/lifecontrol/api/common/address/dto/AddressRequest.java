package com.lifecontrol.api.common.address.dto;

import jakarta.validation.constraints.Size;
import java.util.UUID;

public record AddressRequest(
        @Size(max = 255, message = "Street must not exceed {max} characters")
        String street,

        @Size(max = 20, message = "Street number must not exceed {max} characters")
        String streetNumber,

        @Size(max = 20, message = "Internal number must not exceed {max} characters")
        String internalNumber,

        @Size(max = 255, message = "Neighborhood must not exceed {max} characters")
        String neighborhood,

        @Size(max = 20, message = "Zip code must not exceed {max} characters")
        String zipCode,

        @Size(max = 255, message = "City must not exceed {max} characters")
        String city,

        @Size(max = 255, message = "State must not exceed {max} characters")
        String state,

        UUID countryId) {}
