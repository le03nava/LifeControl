package com.lifecontrol.api.accounting.dto;

import java.math.BigDecimal;

/**
 * The two sides of one currency's trial balance, so a reader can verify the one property the book guarantees
 * at every commit: {@code debit == credit} ({@code V97}'s deferred balance trigger, A1).
 *
 * <p>One total per currency and never one over all of them — adding MXN to USD is the one arithmetic this book
 * must never do, and there is no exchange rate to do it with (ADR-0002).</p>
 */
public record TrialBalanceTotalResponse(String currency, BigDecimal debit, BigDecimal credit) {}
