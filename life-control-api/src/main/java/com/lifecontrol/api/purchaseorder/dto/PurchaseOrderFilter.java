package com.lifecontrol.api.purchaseorder.dto;

import java.util.UUID;

/**
 * The optional location filters of {@code GET /api/purchase-orders} (record S4): the company, company
 * country, region, zone and store ids the caller narrowed the list to.
 *
 * <p>Every field is a filter and not a scope: they combine with {@code AND} among themselves, with the
 * {@code search} term and with the {@code enabled = true} the list already applied, and an absent
 * (null) level constrains nothing. The record exists so the five ids travel as one value through the
 * controller and the service instead of as five positional {@code UUID} parameters that are easy to
 * transpose. It is not authorization: the list derives nothing from the caller's scope claims, which
 * is a previous, out-of-scope decision (S6).</p>
 */
public record PurchaseOrderFilter(
        UUID companyId, UUID companyCountryId, UUID regionId, UUID zoneId, UUID companyStoreId) {

    /** The unfiltered list: every level absent, which leaves the page exactly as it was before. */
    public static final PurchaseOrderFilter NONE = new PurchaseOrderFilter(null, null, null, null, null);
}
