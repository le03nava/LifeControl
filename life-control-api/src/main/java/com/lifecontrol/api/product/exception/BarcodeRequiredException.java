package com.lifecontrol.api.product.exception;

import com.lifecontrol.api.exception.FieldViolationException;
import java.util.UUID;

/**
 * Thrown when a variant of a {@code MULTI} product is written without a barcode.
 *
 * <p>ADR-0006 decision 4 makes the barcode required for {@code MULTI} and optional for {@code SIMPLE} —
 * "a single-variant article has no barcode to give" — so the requirement belongs to the <b>product's
 * mode</b>, not to the request shape. It is stated in the schema too ({@code V56}'s
 * {@code trg_variant_matches_mode}), and the two halves exist for different reasons: the trigger is what
 * no writer can skip, this exception is what gives the caller a field name instead of a trigger message.</p>
 *
 * <p>Subclass of {@link FieldViolationException}: the 400 carries {@code errors: {barCode: <message>}}
 * next to the same sentence in {@code message}. The key is the field, never a row index, because the rule
 * is about the variant the endpoint was called for — the request names one variant, so there is no list
 * to point into. It is deliberately not a "not found": the barcode is absent from the <b>request</b>, so
 * no row was ever named.</p>
 */
public class BarcodeRequiredException extends FieldViolationException {

    public BarcodeRequiredException(UUID productId) {
        super(
                "barCode",
                "barCode is required: the product " + productId + " is MULTI and every one of its variants "
                        + "declares one (ADR-0006 decision 4)");
    }
}
