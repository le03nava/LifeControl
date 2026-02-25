package com.lifecontrol.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record ProductCreateRequest(
        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 255, message = "El nombre no puede exceder 255 caracteres")
        String name,
        
        @Size(max = 1000, message = "La descripción no puede exceder 1000 caracteres")
        String description,
        
        @Positive(message = "El precio debe ser mayor a cero")
        BigDecimal price
) {}
