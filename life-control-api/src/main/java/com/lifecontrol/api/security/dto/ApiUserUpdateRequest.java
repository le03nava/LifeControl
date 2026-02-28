package com.lifecontrol.api.security.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiUserUpdateRequest {

    @Size(max = 100, message = "Name must not exceed 100 characters")
    private String name;

    @Size(max = 100, message = "Lastname must not exceed 100 characters")
    private String lastname;

    @Size(max = 50, message = "Phone must not exceed 50 characters")
    private String phone;

    private Boolean enabled;
}
