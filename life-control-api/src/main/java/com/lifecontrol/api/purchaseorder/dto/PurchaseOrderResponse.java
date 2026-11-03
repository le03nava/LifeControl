package com.lifecontrol.api.purchaseorder.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One purchase order, as the API answers it.
 *
 * @param statusId the catalog row the order's state is labelled by, <b>projected from {@code status}</b>: the
 *     document stores no pointer any more (ADR-0004 step 9 dropped {@code status_id}), so the pair is a lookup
 *     by code in the purchase-order family. The request keeps its shape, so no screen changes here
 * @param statusName the label of that row — presentation, never the order's state
 * @param status the order's typed state as its code (ADR-0004 decision 1) — {@code DRAFT}, {@code SENT},
 *     {@code ACCEPTED}, {@code IN_TRANSIT}, {@code RECEIVED}, {@code BILLED}, {@code CLOSED} or
 *     {@code REJECTED}, never the catalog's label — and the authority the pair is derived from
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
