package com.lifecontrol.api.hr.dto;

import java.time.LocalDate;

/**
 * Optional body of the close-store-assignment route.
 *
 * <p>The end date is nullable on purpose and the whole body is optional at the controller
 * ({@code @RequestBody(required = false)}): an omitted body and a {@code null} {@code endDate} both
 * mean "close today". When present it is the <b>last day covered</b> (inclusive), the way
 * {@code employees.termination_date} reads and the way the contract's own close does (decision D5);
 * the column stores the exclusive bound, one day later, so closing today means the assignment covers
 * today. A date before the assignment's {@code validFrom} is a 400 — which is also the honest
 * outcome of closing a future assignment without a date, and that is intended.</p>
 *
 * <p>Closing sets only the end date: it changes nothing else, because an assignment is history whose
 * single mutation is being closed once (decision T1).</p>
 */
public record CloseStoreAssignmentRequest(
        // The last day covered (inclusive). The service stores one day later, the first day NOT
        // covered (decision D5).
        LocalDate endDate) {}
