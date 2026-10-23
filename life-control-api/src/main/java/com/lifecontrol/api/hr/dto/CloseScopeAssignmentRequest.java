package com.lifecontrol.api.hr.dto;

import java.time.LocalDate;

/**
 * Optional body of the close-scope-assignment route.
 *
 * <p>The end date is nullable on purpose and the whole body is optional at the controller
 * ({@code @RequestBody(required = false)}): an omitted body and a {@code null} {@code endDate} both
 * mean "close today". When present it is the <b>last day covered</b> (inclusive), the same convention
 * {@code CloseStoreAssignmentRequest} and {@code employees.termination_date} use; the column stores
 * the exclusive bound, one day later, so closing today means the assignment covers today. A date
 * before the assignment's {@code validFrom} is a 400.</p>
 *
 * <p>Closing sets only the end date: it changes nothing else, because an assignment is history whose
 * single mutation is being closed once.</p>
 */
public record CloseScopeAssignmentRequest(
        // The last day covered (inclusive). The service stores one day later, the first day NOT covered.
        LocalDate endDate) {}
