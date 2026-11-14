package com.lifecontrol.api.salesorder.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * What a caller states to create or replace a sale.
 *
 * @param customerId the receiver, or {@code null} for a counter sale — no receiver, no fiscal profile,
 *     nothing to invoice (S2). There is deliberately <b>no default</b>: until this slice a {@code null}
 *     was replaced by the seeded {@code 'Cliente General'} ({@code 00000000-0000-0000-0000-000000000001}),
 *     which meant every sale created without a customer named a fictitious one, and the row it named
 *     {@code V105} deletes. {@code sales_orders.customer_id} is nullable since that migration, so the
 *     request can say what the schema says
 */
public record SalesOrderRequest(
        UUID customerId,
        @NotNull(message = "companyStoreId is required") UUID companyStoreId,
        UUID shiftId,
        String userId,
        List<SalesOrderItemRequest> items) {}
