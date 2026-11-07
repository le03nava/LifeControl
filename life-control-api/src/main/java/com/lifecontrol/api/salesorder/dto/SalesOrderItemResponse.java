package com.lifecontrol.api.salesorder.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One line of a sales order, as the API answers it.
 *
 * @param statusId the catalog row the line's state is labelled by, <b>projected from {@code status}</b>: the
 *     line stores no pointer any more, so the pair is a lookup by code in the line's own family
 * @param statusName the label of that row — presentation, never the line's state
 * @param status the line's typed state as its code (ADR-0004 decision 3) — {@code PENDING}, {@code ADDED} or
 *     {@code CANCELLED} — and the authority the pair is derived from
 * @param subtotal the line's money as {@code V81} derives it: {@code round(list_price * quantity, 4)}, by the
 *     trigger {@code trg_sales_order_items_subtotal}. Read from the row, never re-derived here
 * @param taxCode the catalog code the rate was resolved from (decision 6); {@code NA} until a unit states a
 *     line's tax code, which is T4's open question
 * @param taxRate the rate as it stood at write time — a snapshot, so a later {@code tax_rate} row never
 *     restates a past sale
 * @param taxAmount {@code round((subtotal - discount_applied) * tax_rate, 4)}, held by {@code ck_item_tax}
 * @param finalPrice the line's total as {@code V85} derives it: {@code subtotal - discount_applied +
 *     tax_amount}. It reports the stored line total and not the unit price the line was built from
 * @param productNameSnapshot the product's name as the catalog gave it when {@code V81} backfilled the line,
 *     so a ticket reprinted in a year reads the same whatever the catalog says then (decision 4). Null on a
 *     line written since: nothing in the application writes this column yet, and inventing one here would be
 *     a second snapshot with a different moment
 * @param variantNameSnapshot the variant's name, on the same terms as {@link #productNameSnapshot}
 */
public record SalesOrderItemResponse(
        UUID id,
        UUID salesOrderId,
        UUID productVariantId,
        BigDecimal quantity,
        BigDecimal listPrice,
        BigDecimal discountApplied,
        BigDecimal subtotal,
        String taxCode,
        BigDecimal taxRate,
        BigDecimal taxAmount,
        BigDecimal finalPrice,
        String productNameSnapshot,
        String variantNameSnapshot,
        UUID promotionId,
        UUID statusId,
        String statusName,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {}
