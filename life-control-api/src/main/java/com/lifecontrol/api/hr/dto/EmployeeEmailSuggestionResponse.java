package com.lifecontrol.api.hr.dto;

/**
 * Answer of {@code GET /api/companies/{companyId}/employees/suggest-email}.
 *
 * <p>Exactly one of the two fields is set: the free candidate address, or a machine-readable reason
 * ({@code NO_EMAIL_DOMAIN}, {@code EMPTY_LOCAL_PART}, {@code NO_FREE_CANDIDATE}) the form can turn
 * into a blocking instruction.
 * The suggestion reserves nothing (decision T8): no lock is taken and two operators can be shown the
 * same candidate.</p>
 */
public record EmployeeEmailSuggestionResponse(String email, String reason) {}
