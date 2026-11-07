package com.lifecontrol.api.salesorder.model;

/**
 * Which way a payment row moves money, and the whole of the sign (ADR-0002 decision 5, {@code V83}).
 *
 * <p><b>Why a direction and not a negative amount.</b> The two representations would put the sign in two
 * places at once and make every sum a {@code CASE} over both, so the schema states it once:
 * {@code amount} is always positive ({@code CHECK (amount > 0)}) and this column says what it does. A
 * refund subtracts because of the direction, not because of a minus.</p>
 *
 * <p>Mapped as the enum and not as a string, like every other typed state in this tree: the column's
 * {@code CHECK} lists exactly these two names, and an unmapped value becomes a load failure inside
 * Hibernate instead of a guess in a report.</p>
 */
public enum SalePaymentDirection {
    CHARGE,
    REFUND
}
