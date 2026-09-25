package com.lifecontrol.api.inventory.model;

/**
 * Kind of stock movement recorded in {@code inventory_movements}.
 *
 * <p>Persisted as a string in the {@code movement_type VARCHAR(30)} column, following the existing
 * enum-in-a-column convention of the module ({@code MeasureUnit#unitType}).</p>
 *
 * <p>W3-D7: the type carries the sign, so every {@code quantity} in the ledger stays positive and
 * the balance of a location is
 * {@code SUM(RECEIPT) + SUM(SALE_REVERSAL) + SUM(ADJUSTMENT_INCREASE) - SUM(SALE) - SUM(ADJUSTMENT_DECREASE)}.
 * The two manual-edit types are the writer added by W3-D14 (the per-store stock editor): the
 * direction of a hand edit is the type, exactly as a sale and its reversal already carry theirs,
 * so an edit that lowers stock does not need a signed quantity.</p>
 */
public enum MovementType {
    /** Stock entering the store through a goods receipt. */
    RECEIPT,
    /** Stock leaving the store through a sale, one row per store location consumed. */
    SALE,
    /**
     * Stock returned to the exact locations a {@code SALE} took it from, compensating that sale.
     * It is written only for the uncovered remainder of the reference, which makes the reversal
     * idempotent.
     */
    SALE_REVERSAL,
    /**
     * Stock added to the store's sales location by a manual per-store stock edit (W3-D14). Positive
     * quantity; the direction lives in the type, mirroring {@link #SALE}/{@link #SALE_REVERSAL}.
     */
    ADJUSTMENT_INCREASE,
    /**
     * Stock removed by a manual per-store stock edit (W3-D14), allocated over the store's locations
     * priority-first — the sales location first — and then FIFO, exactly as a sale is (W3-D1). The
     * sales location is only the first candidate, not the source: one {@code ADJUSTMENT_DECREASE} row
     * is written per location actually drawn from
     * ({@code InventoryService.applyAdjustmentDecrease}). Positive quantity; the direction lives in
     * the type. This is the ledger's first decrease that is not a sale, which is why the type pair
     * exists rather than a signed quantity (W3-D7).
     */
    ADJUSTMENT_DECREASE
}
