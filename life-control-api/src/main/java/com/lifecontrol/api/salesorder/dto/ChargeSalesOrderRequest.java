package com.lifecontrol.api.salesorder.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * What a charge states: the payments that came in, and nothing else (ADR-0002 decision 5 and decision 7).
 *
 * <p><b>A sale may have many payments, so the request carries a list.</b> Cash and card in one sale are two rows,
 * each with its own method, its own amount and its own tender. One field could not express mixed tender at all,
 * which is why the single {@code paymentMethodId} this record used to carry is gone: the document still reports
 * one method, but that column is the first payment's, and ADR-0002 migration step 6 drops it once every reader
 * has moved to the rows.</p>
 *
 * <p><b>Why the list is not empty and why its items are validated.</b> {@code sale_payment} holds
 * {@code CHECK (amount > 0)} and a charge is what makes a document {@code Completed}, so an empty list is a
 * request that states nothing — never a charge of zero. {@code @Valid} cascades into each payment, because a
 * constraint declared on {@link SalePaymentRequest} is not read unless the container asks for it, and a shape
 * that is only stated in one of the two places is a shape nothing enforces.</p>
 *
 * <p><b>No {@code direction} is accepted, by D-g.</b> The server writes {@code CHARGE}: this endpoint records
 * money coming in and never gives it back. See {@link SalePaymentRequest} for why.</p>
 */
public record ChargeSalesOrderRequest(
        @NotNull(message = "payments is required")
        @NotEmpty(message = "a charge must state at least one payment")
        @Valid
        List<SalePaymentRequest> payments) {}
