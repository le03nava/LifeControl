package com.lifecontrol.api.product.exception;

import com.lifecontrol.api.measureunit.model.MeasureUnit;

/**
 * Thrown when a variant write names a measure unit that exists but is soft-deleted ({@code enabled =
 * false}).
 *
 * <p>Thin subclass of {@link IllegalArgumentException}: the {@code GlobalExceptionHandler} already
 * maps that class to 400 and needs no method of its own for this type. It is a client error rather
 * than a server one because the picker offers the enabled catalog only (S4), so a disabled unit can
 * only arrive from a caller holding a stale selection.</p>
 */
public class DisabledMeasureUnitException extends IllegalArgumentException {

    public DisabledMeasureUnitException(MeasureUnit measureUnit) {
        super("Measure unit is disabled and cannot be assigned to a product variant: "
                + measureUnit.getMeasureUnitShortName() + " (id=" + measureUnit.getId() + ")");
    }
}
