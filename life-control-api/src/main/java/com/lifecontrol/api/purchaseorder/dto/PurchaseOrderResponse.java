package com.lifecontrol.api.purchaseorder.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One purchase order, as the API answers it.
 *
 * @param status the order's typed state as its code (ADR-0004 decision 1) — {@code DRAFT}, {@code SENT},
 *     {@code ACCEPTED}, {@code IN_TRANSIT}, {@code RECEIVED}, {@code BILLED}, {@code CLOSED} or
 *     {@code REJECTED}, never the catalog's label — added beside {@code statusId}/{@code statusName}, which are
 *     unchanged until T8 drops the pointer. The request keeps its shape, so no screen changes here
 */
public record PurchaseOrderResponse(
        UUID id,
        String orderNumber,
        UUID supplierId,
        String supplierName,
        UUID companyStoreId,
        String companyStoreName,
        UUID companyId,
        UUID companyCountryId,
        UUID regionId,
        UUID zoneId,
        UUID paymentMethodId,
        String paymentMethodName,
        UUID statusId,
        String statusName,
        String status,
        String comments,
        Boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<PurchaseOrderDetailResponse> details) {}
