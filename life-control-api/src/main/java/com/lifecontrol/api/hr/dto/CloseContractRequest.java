package com.lifecontrol.api.hr.dto;

import java.time.LocalDate;

/**
 * Optional body of the close-contract route.
 *
 * <p>The end date is nullable on purpose and the whole body is optional at the controller
 * ({@code @RequestBody(required = false)}): an omitted body and a {@code null} {@code endDate} both
 * mean "close today". When present it is the <b>last day covered</b> (inclusive), the way
 * {@code employees.termination_date} reads; the column stores the exclusive bound, one day later
 * (decision D14), so closing today means the contract covers today. A date before the contract's start
 * date is a 400 — which is also the honest outcome of closing a future contract without a date, and
 * that is intended.</p>
 *
 * <p>Closing sets only the end date: it changes nothing else, because a contract is history whose
 * single mutation is being closed once (decision T2).</p>
 */
public record CloseContractRequest(
        // The last day covered (inclusive). The service stores one day later, the first day NOT
        // covered (decision D14).
        LocalDate endDate) {}
