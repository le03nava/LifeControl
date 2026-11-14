package com.lifecontrol.api.accounting.dto;

import java.math.BigDecimal;

/**
 * One account of the trial balance over a range, in one currency: the two sums the book holds and their
 * difference.
 *
 * <p>{@code balance} is {@code debit − credit}, computed here and never stored: it is a view of the account's
 * own lines for the range, and a stored balance would be a second thing that can disagree with the book.
 *
 * <p><b>The currency is part of the row's identity and not a column of a total.</b> {@code companies.currency}
 * is editable, so one tenant's book can hold MXN before a change and USD after it, and there is no exchange
 * rate (ADR-0002). Two currencies are two rows even for the same account, because a single total over both
 * would be an amount of nothing.
 */
public record TrialBalanceRowResponse(
        String accountCode,
        String accountName,
        String accountKind,
        String currency,
        BigDecimal debit,
        BigDecimal credit,
        BigDecimal balance) {}
