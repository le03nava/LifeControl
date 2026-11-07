package com.lifecontrol.api.salesorder.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One payment of a sales order, as the API answers it (ADR-0002 decision 5, migration
 * {@code V83__sale_payment.sql}).
 *
 * <p><b>Why the response states every column of the row.</b> A payment is a row and a sale may have many, so
 * the only way a client can reconcile a drawer is to read them all: the method, the direction, the amount and
 * the moment are what a report groups by, and the tender pair is what says how much cash came in and how much
 * went back. The list is read-only in both directions — nothing in a request states a payment row and nothing
 * edits one afterwards, because {@code sale_payment} is append-only by trigger and by grant.</p>
 *
 * @param id the row's identity. A void cites it: a payment is void if and only if a {@code sale_payment_void}
 *     row names it, which is why it is answered even though nothing in this slice appends a void
 * @param methodId the {@code payment_methods} row the money came through
 * @param direction {@code CHARGE} or {@code REFUND} — the sign of the row, stated once here so no amount ever
 *     carries a negative (the column holds {@code amount > 0})
 * @param amount the money that moved through this row, at the four decimals of the column and never rounded
 *     per payment: the rounding happens once, at the document boundary
 * @param tenderedAmount what the customer handed over, only for a cash method. Null on a card or a transfer,
 *     and null when the tender was not stated — the pair is absent together or not at all
 * @param changeAmount what came back, held to {@code tendered_amount - amount} by {@code ck_payment_tender}
 * @param reference the operator's note for the row (a card's authorization code, a transfer's folio)
 * @param paidAt the moment the money moved. The order of the list is this column's, oldest first
 * @param actor who took it — the authenticated principal of the charge, not the order's mutable
 *     {@code user_id}, because a drawer report cites the person
 */
public record SalePaymentResponse(
        UUID id,
        UUID methodId,
        String direction,
        BigDecimal amount,
        BigDecimal tenderedAmount,
        BigDecimal changeAmount,
        String reference,
        OffsetDateTime paidAt,
        String actor) {}
