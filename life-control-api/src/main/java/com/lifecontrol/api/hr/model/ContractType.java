package com.lifecontrol.api.hr.model;

/**
 * Legal form of an employment contract, persisted as a string in the
 * {@code employee_contracts.contract_type VARCHAR(30)} column, following the existing
 * enum-in-a-column convention of the module ({@code MovementType#movementType},
 * {@code MeasureUnit#unitType}): the column carries no CHECK, and the enum is the Java counterpart
 * of the stored value (decision D13).
 *
 * <p>{@code PART_TIME} is deliberately absent: the column names the legal form and not the
 * workload (gap G9). A workload-valued constant here would be the first step to closing G9 in the
 * wrong place; a {@code weekly_hours} column is deferred until payroll needs it.</p>
 *
 * <p>Adding a sixth value later needs no migration, only this enum and its tests. The request parse
 * (an unknown value is an {@code IllegalArgumentException} and therefore a 400) belongs to the
 * contract service, not here.</p>
 */
public enum ContractType {
    /** Open-ended employment with no fixed term. */
    PERMANENT,
    /** Employment for a term fixed in advance. */
    FIXED_TERM,
    /** Employment covering a temporary need without a fixed term. */
    TEMPORARY,
    /** Training placement, typically time-bounded and at reduced pay. */
    INTERNSHIP,
    /** Services rendered under a contract for services rather than an employment relationship. */
    CONTRACTOR
}
