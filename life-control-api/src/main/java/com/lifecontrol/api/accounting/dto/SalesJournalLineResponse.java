package com.lifecontrol.api.accounting.dto;

import java.math.BigDecimal;

/**
 * One line of one posted entry, as the sales journal states it: the account by its code and name, and which
 * side of the entry the money landed on.
 *
 * <p>{@code debit} and {@code credit} are both stated and exactly one of them is zero — {@code V97}'s
 * {@code ck_account_entry_line_single_side} — so the pair is printed as the book holds it instead of being
 * folded into a signed amount. A signed column would be a second vocabulary for the same fact and would make
 * a credit read as a negative debit in every consumer.</p>
 */
public record SalesJournalLineResponse(String accountCode, String accountName, BigDecimal debit, BigDecimal credit) {}
