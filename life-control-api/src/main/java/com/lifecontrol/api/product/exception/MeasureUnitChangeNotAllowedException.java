package com.lifecontrol.api.product.exception;

import com.lifecontrol.api.exception.ConflictException;
import java.util.UUID;

/**
 * Thrown when the measure unit of a variant would be changed after the variant acquired a quantity
 * meaning (D3): one of its store rows holds a non-zero stock balance, or the inventory ledger already has a
 * movement for it.
 *
 * <p>Thin subclass of {@link ConflictException}: the {@code GlobalExceptionHandler} maps the parent
 * to 409 and needs no method of its own for this type. The stored numbers carry no unit of their
 * own, so reinterpreting them silently is exactly what this guard forbids — the write is refused and
 * the entity, its stock rows and the ledger are left untouched.</p>
 */
public class MeasureUnitChangeNotAllowedException extends ConflictException {

    public MeasureUnitChangeNotAllowedException(UUID variantId, String reason) {
        super("The measure unit of product variant " + variantId + " cannot be changed because " + reason
                + ". Only a variant without a quantity meaning can be re-expressed in another unit.");
    }
}
