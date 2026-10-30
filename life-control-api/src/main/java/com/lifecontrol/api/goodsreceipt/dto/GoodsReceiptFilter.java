package com.lifecontrol.api.goodsreceipt.dto;

import java.util.UUID;

/**
 * The optional location filters of {@code GET /api/goods-receipts}: the company, company country,
 * region, zone and store ids the caller narrowed the receipt list to.
 *
 * <p>Every field is a filter and not a scope. They combine with {@code AND} among themselves, with the
 * {@code search} term and with the {@code enabled = true} the list already applied, and an absent
 * (null) level constrains nothing. The record exists so the five ids travel as one value through the
 * controller and the service instead of as five positional {@code UUID} parameters that are easy to
 * transpose.</p>
 *
 * <p>Unlike {@code PurchaseOrderFilter}, whose list derives no scope from the token, this one is
 * <strong>narrowed by the caller's own scope</strong> before it reaches the query: the receipt list is
 * tenant-scoped (W2-D10), so the filter can only subtract from what the token already reaches. A
 * filter is therefore never an authorization input — it cannot widen, and
 * {@code GoodsReceiptService#getAllReceipts} intersects it with the resolved scope.</p>
 */
public record GoodsReceiptFilter(
        UUID companyId, UUID companyCountryId, UUID regionId, UUID zoneId, UUID companyStoreId) {

    /** The unfiltered list: every level absent, which leaves the page exactly as it was before. */
    public static final GoodsReceiptFilter NONE = new GoodsReceiptFilter(null, null, null, null, null);

    /** Whether no level was chosen, which is what makes the filter skip the hierarchy lookup. */
    public boolean isEmpty() {
        return companyId == null
                && companyCountryId == null
                && regionId == null
                && zoneId == null
                && companyStoreId == null;
    }
}
