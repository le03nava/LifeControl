package com.lifecontrol.api.company.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanyResponse {

    private Integer companyId;
    private String companyKey;
    private String companyName;
    private Integer tipoPersonaId;
    private String razonSocial;
    private String rfc;
    private String phone;
    private String email;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}