package com.lifecontrol.product.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record ProductUpdateRequest(
        @Size(min = 1, max = 255, message = "El nombre debe tener entre 1 y 255 caracteres")
        String name,
        
        @Size(max = 1000, message = "La descripción no puede exceder 1000 caracteres")
        String description,
        
        @Positive(message = "El precio debe ser mayor a cero")
        BigDecimal price
) {}
