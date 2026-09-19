package com.lifecontrol.api.inventory.model;

/**
 * Kind of stock movement recorded in {@code inventory_movements}.
 *
 * <p>Persisted as a string in the {@code movement_type VARCHAR(30)} column, following the existing
 * enum-in-a-column convention of the module ({@code MeasureUnit#unitType}). Only {@link #RECEIPT}
 * exists while the sales rework (workstream W3) is deferred: the sales path does not write movements
 * yet, so {@code SALE} and {@code ADJUSTMENT} arrive with W3.</p>
 */
public enum MovementType {
    RECEIPT
}
