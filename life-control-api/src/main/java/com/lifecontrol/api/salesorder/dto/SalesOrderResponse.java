package com.lifecontrol.api.salesorder.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One sales order, as the API answers it.
 *
 * @param statusId the catalog row the order's state is labelled by, <b>projected from {@code status}</b>: the
 *     document stores no pointer any more (ADR-0004 step 9 dropped {@code status_id}), so the pair below is a
 *     lookup by code in the family's own catalog rows
 * @param statusName the label of that row — presentation, and a rename here changes what the screen says and
 *     never what the order is. A screen may read {@code status} or this pair; both are answered on purpose
 * @param status the order's typed state as its code (ADR-0004 decision 1) — {@code DRAFT}, {@code ACTIVE},
 *     {@code PENDING}, {@code COMPLETED} or {@code CANCELLED}, never the catalog's label — and the authority
 * @param currency the document's own currency code (ADR-0002 decision 2). {@code V80} derives it from the
 *     company that owns the document and refuses one that differs from it, so this answers what the row says
 *     and never a platform default: a client that prints an amount has no reason to assume a currency, and a
 *     hardcoded one is how a sale of one country gets printed with another's money
 * @param subtotal the document's own part (decision 3): Σ of the lines' subtotals, at the four decimals the
 *     column carries
 * @param discountTotal Σ of the lines' discounts — the same money the line table shows, at the document's
 *     level
 * @param taxTotal Σ of the lines' tax amounts. Zero until some unit states a line's {@code tax_code} (T4)
 * @param roundingAdjustment the single rounding to the currency's {@code minor_units} (decision 1), stated so
 *     the four parts add up to {@code totalAmount} <b>exactly</b> (M1). It is not a tolerance: a client that
 *     ignores it is a client whose total will not match the row
 * @param totalAmount what the document is worth, and the sum of the four parts above — never a second
 *     derivation of them
 * @param payments the payments the document has taken, oldest first (decision 5). Read-only: a payment is an
 *     appended row and nothing in a request states one. Empty until the charge, which is the only writer. Since
 *     ADR-0002 step 6 dropped {@code sales_orders.payment_method_id} ({@code V87}), this list is <b>the only
 *     statement of how a document was paid</b>: a tender is a row with a method, a direction and an amount, and
 *     there is no single-method column left to answer a second time
 */
public record SalesOrderResponse(
        UUID id,
        String orderNumber,
        UUID customerId,
        UUID companyStoreId,
        UUID shiftId,
        String userId,
        LocalDateTime orderDate,
        UUID statusId,
        String statusName,
        String status,
        String currency,
        BigDecimal subtotal,
        BigDecimal discountTotal,
        BigDecimal taxTotal,
        BigDecimal roundingAdjustment,
        BigDecimal totalAmount,
        Boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<SalesOrderItemResponse> items,
        List<SalePaymentResponse> payments) {}
