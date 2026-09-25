package com.lifecontrol.api.inventory.model;

/**
 * Kind of stock movement recorded in {@code inventory_movements}.
 *
 * <p>Persisted as a string in the {@code movement_type VARCHAR(30)} column, following the existing
 * enum-in-a-column convention of the module ({@code MeasureUnit#unitType}).</p>
 *
 * <p>W3-D7: the type carries the sign, so every {@code quantity} in the ledger stays positive and
 * the balance of a location is {@code SUM(RECEIPT) + SUM(SALE_REVERSAL) - SUM(SALE)}. There is no
 * {@code ADJUSTMENT} member because nothing writes one.</p>
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
    SALE_REVERSAL
}
