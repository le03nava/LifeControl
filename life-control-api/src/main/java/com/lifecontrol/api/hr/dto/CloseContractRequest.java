package com.lifecontrol.api.hr.dto;

import java.time.LocalDate;

/**
 * Optional body of the close-contract route.
 *
 * <p>The end date is nullable on purpose and the whole body is optional at the controller
 * ({@code @RequestBody(required = false)}): an omitted body and a {@code null} {@code endDate} both
 * mean "close today". A date before the contract's start date is a 400 — which is also the honest
 * outcome of closing a future contract without a date, and that is intended.</p>
 *
 * <p>Closing sets only the end date: it changes nothing else, because a contract is history whose
 * single mutation is being closed once (decision T2).</p>
 */
public record CloseContractRequest(LocalDate endDate) {}
