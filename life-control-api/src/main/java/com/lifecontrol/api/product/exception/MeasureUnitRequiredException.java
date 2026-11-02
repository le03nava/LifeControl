package com.lifecontrol.api.product.exception;

/**
 * Thrown when a variant write does not declare the measure unit its quantities are expressed in.
 *
 * <p>ADR-0006 decision 3 makes the unit mandatory — "a quantity without a unit is a meaningless
 * number" — and {@code V55} states it in the schema: {@code product_variants.measure_unit_id} is
 * {@code NOT NULL}. This exception is the write path's half of the same rule, and it exists so the
 * caller receives a 400 that <b>names the field</b> instead of the 500 a constraint violation would
 * produce after the row was already built.</p>
 *
 * <p>Thin subclass of {@link IllegalArgumentException}: the {@code GlobalExceptionHandler} already maps
 * that class to 400 and needs no method of its own for this type. It is deliberately not a "not found":
 * the unit is absent from the <b>request</b>, so no catalog row was ever named. A unit that is named and
 * does not exist is {@link com.lifecontrol.api.measureunit.exception.MeasureUnitNotFoundException}
 * (404), and one that is named and soft-deleted is {@link DisabledMeasureUnitException} (400).</p>
 */
public class MeasureUnitRequiredException extends IllegalArgumentException {

    public MeasureUnitRequiredException() {
        super("measureUnitId is required: a variant declares the unit its quantities are expressed in "
                + "(ADR-0006 decision 3)");
    }
}
