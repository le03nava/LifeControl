package com.lifecontrol.api.purchaseorder.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record PurchaseOrderResponse(
    UUID id,
    String orderNumber,
    UUID supplierId,
    String supplierName,
    UUID companyStoreId,
    String companyStoreName,
    UUID paymentMethodId,
    String paymentMethodName,
    UUID statusId,
    String statusName,
    String comments,
    Boolean enabled,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    List<PurchaseOrderDetailResponse> details
) {}
