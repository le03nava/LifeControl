package com.lifecontrol.api.purchaseorder.dto;

import java.math.BigDecimal;
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
 * @param currency ISO 4217 code the order was written in, taken from the company at creation and frozen there
 *     (S4): a later change of the company's currency does not restate a historical document
 * @param subtotal the sum of the enabled lines' {@code subtotal}, at four decimals
 * @param taxTotal the sum of the lines' tax — {@code 0} in this slice, because
 *     {@code odd/tasks/purchase-order-line-tax.md} is the unit that states a tax at all
 * @param totalAmount {@code subtotal + taxTotal}, stated by {@code ck_po_order_total} and never computed by a
 *     client: the screen that used to sum rows in the browser with an {@code 'MXN'} literal reads this
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
        String currency,
        BigDecimal subtotal,
        BigDecimal taxTotal,
        BigDecimal totalAmount,
        List<PurchaseOrderDetailResponse> details) {}
