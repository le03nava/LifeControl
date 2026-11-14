package com.lifecontrol.api.accounting.dto;

import java.util.List;

/**
 * The trial balance of a date range: one row per {@code (currency, account)} with activity in the range, and
 * one total per currency.
 *
 * <p><b>By range and not by period, for now.</b> {@code S18} says "by period", and {@code accounting_period} is
 * T11 of {@code odd/tasks/derived-accounting.md}: nothing in this read model reads or writes it, so the closed
 * month arrives with B5 and the range serves the same question today. A report that read the period table
 * before the slice that defines its semantics would be a contract written by accident.</p>
 */
public record TrialBalanceResponse(List<TrialBalanceRowResponse> rows, List<TrialBalanceTotalResponse> totals) {}
