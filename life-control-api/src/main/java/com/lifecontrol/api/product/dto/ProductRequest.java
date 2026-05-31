package com.lifecontrol.api.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record ProductRequest(

    @NotBlank(message = "sku es requerido")
    @Size(max = 50, message = "sku no puede exceder 50 caracteres")
    String sku,

    @NotBlank(message = "name es requerido")
    @Size(max = 255, message = "name no puede exceder 255 caracteres")
    String name,

    @Size(max = 100, message = "shortName no puede exceder 100 caracteres")
    String shortName,

    @Size(max = 20, message = "satCode no puede exceder 20 caracteres")
    String satCode,

    @Size(max = 50, message = "productType no puede exceder 50 caracteres")
    String productType,

    Map<String, Object> attributes

) {}
