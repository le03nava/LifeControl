package com.lifecontrol.api.product.exception;

import com.lifecontrol.api.exception.ConflictException;
import java.util.UUID;

/**
 * Thrown when removing a variant would leave its product with none.
 *
 * <p>ADR-0006's invariant C1 is "a product has at least one variant", and the ADR names its two halves: the
 * single creation transaction and "a delete that would leave zero is refused by the write path and counted by
 * a query". This class is the write path's half.</p>
 *
 * <p>The count it refuses on is of the product's <b>live</b> variants, because a variant definition is
 * soft-deleted ({@code enabled = false}) rather than removed: the row survives, but a sale, a receipt and the
 * POS listing can only reach the enabled ones, so a product whose last enabled variant was just disabled is
 * exactly the incomplete catalog entry C1 forbids. It is a <b>409</b> — thin subclass of
 * {@link ConflictException}, which {@code GlobalExceptionHandler} already maps — because the request is valid
 * and the stored state is what refuses it, the same shape {@link MeasureUnitChangeNotAllowedException} uses.</p>
 */
public class VariantRemovalWouldEmptyProductException extends ConflictException {

    public VariantRemovalWouldEmptyProductException(UUID productId) {
        super("The variant cannot be removed: the product " + productId + " would be left without any variant, "
                + "and a product has at least one variant (ADR-0006 invariant C1). Add or re-enable another "
                + "variant first, or soft-delete the product itself.");
    }
}
