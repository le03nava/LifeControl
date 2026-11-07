package com.lifecontrol.api.salesorder.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One payment a charge is asked to append (ADR-0002 decision 5, migration {@code V83__sale_payment.sql}).
 *
 * <p><b>There is no {@code direction}, and that is decision D-g.</b> The schema admits {@code CHARGE} and
 * {@code REFUND} on {@code sale_payment.direction}, and this request admits neither: the server writes
 * {@code CHARGE} for every row it appends, so the endpoint that records money coming in never becomes the
 * endpoint money leaves by. A refund is a row with its own authorization, and who authorizes one is a workflow
 * this schema deliberately leaves out of scope — answering it with "whoever can charge" would be a money-out
 * surface with no control of its own. Until that unit exists a {@code REFUND} is not expressible here, and a
 * client that tries to state one is refused by name rather than quietly charged.</p>
 *
 * <p><b>Why the tender pair is optional and why null is not zero.</b> {@code amount} is what this payment
 * applies to the sale; {@code tenderedAmount} is what the customer handed over and {@code changeAmount} is what
 * came back. A card or a transfer counts no cash at all, so it states neither, and a cash payment whose tender
 * nobody counted states neither as well — a zero here would report a customer who handed over nothing, which is
 * a different fact from a drawer that was never opened. Stated together or not at all, because
 * {@code ck_payment_tender} reads both columns and half a pair says neither.</p>
 *
 * <p><b>Why the digits are stated here.</b> {@code sale_payment.amount}, {@code tendered_amount} and
 * {@code change_amount} are {@code NUMERIC(19,4)}: fifteen integer digits and four decimals. A fifth decimal is
 * rounded by the column and then disagrees with the total the document states, so it is refused as a field
 * violation that names the payment instead of arriving as a M3 refusal for a number the client never sent.</p>
 *
 * @param paymentMethodId the {@code payment_methods} row the money comes through. Resolved against the catalog,
 *     and read for its {@code is_cash} when a tender is stated (invariant M4)
 * @param amount what this payment applies to the sale. Between the payments of one charge these add up to the
 *     document's total <b>exactly</b> (M3): a sum below it leaves the document unpaid, and a sum above it would
 *     record money the document does not cost
 * @param tenderedAmount what the customer handed over, only for a cash method. Null when no cash was counted
 * @param changeAmount what came back, held to {@code tenderedAmount - amount} — the same arithmetic
 *     {@code ck_payment_tender} holds behind it
 */
public record SalePaymentRequest(
        @NotNull(message = "paymentMethodId is required") UUID paymentMethodId,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.0001", message = "amount must be at least 0.0001")
        @Digits(integer = 15, fraction = 4, message = "amount must carry at most 15 integer digits and 4 decimals")
        BigDecimal amount,

        @Digits(
                integer = 15,
                fraction = 4,
                message = "tenderedAmount must carry at most 15 integer digits and 4 decimals")
        BigDecimal tenderedAmount,

        @Digits(
                integer = 15,
                fraction = 4,
                message = "changeAmount must carry at most 15 integer digits and 4 decimals")
        BigDecimal changeAmount) {}
