package com.lifecontrol.api.product.supplier.dto;

import java.util.UUID;

public record SupplierProductResponse(
    UUID productId,
    String productName,
    String sku
) {}
