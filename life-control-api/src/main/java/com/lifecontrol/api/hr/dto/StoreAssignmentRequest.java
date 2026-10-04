package com.lifecontrol.api.hr.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Write payload for a new store assignment.
 *
 * <p>It carries exactly what the record decided (decision T13): the store and the first day covered.
 * There is deliberately <b>no</b> end date — an assignment ends only through the close route
 * (decision T5), and a fixed-term store assignment is a product need nobody has stated, so mirroring
 * the contract's optional {@code endDate} would buy a second entry point into the same {@code '[)'}
 * arithmetic for no requirement. If that need appears, one optional component is added to a DTO no
 * client uses yet.</p>
 *
 * <p>{@code validFrom} is the first day the assignment covers and it is allowed to be in the future:
 * a future row is legal and simply derives nothing until it covers today. There is no {@code enabled}
 * field — a new assignment is always enabled, and the only mutation this history allows is being
 * closed once (decision T1).</p>
 */
public record StoreAssignmentRequest(
        @NotNull(message = "companyStoreId is required") UUID companyStoreId,

        @NotNull(message = "validFrom is required") LocalDate validFrom) {}
