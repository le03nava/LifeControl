package com.lifecontrol.api.hr.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request body of the replace-salary-bands operation.
 *
 * <p>Mirrors {@code SchedulingAvailabilityRequest}: a list body wrapped in a record. The set is
 * replaced as a whole, but — unlike the scheduling template — the replacement is an <b>upsert</b>
 * keyed on {@code (position_id, seniority_level_id)} (decision T10): a stored key the request omits
 * is disabled, never deleted (decision D13). An empty list therefore clears every configured band
 * without removing a single row.</p>
 */
public record PositionSalaryBandsRequest(
        @NotNull(message = "bands is required")
        @Size(max = 100, message = "bands must contain at most 100 entries")
        @Valid
        List<PositionSalaryBandRequest> bands) {}
