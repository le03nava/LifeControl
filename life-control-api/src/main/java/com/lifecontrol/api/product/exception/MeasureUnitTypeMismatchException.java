package com.lifecontrol.api.product.exception;

import com.lifecontrol.api.measureunit.model.MeasureUnitType;
import java.util.UUID;

/**
 * Thrown when a variant would mix {@code unit_type}s inside one product (D4).
 *
 * <p>A product cannot hold a {@code PRODUCT} variant and a {@code SERVICE} variant at the same time:
 * the type is a property of the thing being sold, so the second type arriving means the caller is
 * attaching a unit that contradicts the product's other variants. The guard is not derived from
 * {@code products.product_type} because that column is free text and cannot be trusted to name a
 * unit type.</p>
 *
 * <p>Thin subclass of {@link IllegalArgumentException}: the {@code GlobalExceptionHandler} maps that
 * class to 400 and needs no method of its own for this type.</p>
 */
public class MeasureUnitTypeMismatchException extends IllegalArgumentException {

    public MeasureUnitTypeMismatchException(
            UUID productId, MeasureUnitType requestedType, MeasureUnitType typeAlreadyInUse) {
        super("Product " + productId + " already uses measure units of type " + typeAlreadyInUse
                + ", so a variant of it cannot use a measure unit of type " + requestedType);
    }
}
